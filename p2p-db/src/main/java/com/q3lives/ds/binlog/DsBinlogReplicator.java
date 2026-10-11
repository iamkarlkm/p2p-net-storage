package com.q3lives.ds.binlog;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.q3lives.ds.database.DsDatabaseLocal;

public class DsBinlogReplicator implements AutoCloseable {

    private final DsDatabaseLocal sourceDb;
    private final DsDatabaseLocal targetDb;
    private final File cursorFile;
    private final long tickMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DsBinlogPosition lastCursor;
    private volatile long lastCursorSequence = Long.MIN_VALUE;

    public DsBinlogReplicator(DsDatabaseLocal sourceDb, DsDatabaseLocal targetDb, File cursorDir, long tickMs) throws IOException {
        this.sourceDb = Objects.requireNonNull(sourceDb, "sourceDb");
        this.targetDb = Objects.requireNonNull(targetDb, "targetDb");
        Objects.requireNonNull(cursorDir, "cursorDir");
        if (!cursorDir.exists() && !cursorDir.mkdirs()) {
            throw new IOException("failed to create cursorDir: " + cursorDir);
        }
        this.cursorFile = new File(cursorDir, "binlog_cursor_" + sourceDb.serverId() + "_" + sourceDb.partitionId()
                + "_to_" + targetDb.serverId() + "_" + targetDb.partitionId() + ".yaml");
        if (tickMs <= 0) throw new IllegalArgumentException("tickMs must be positive");
        this.tickMs = tickMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ds-binlog-replicator-tick");
            t.setDaemon(true);
            return t;
        });
        this.lastCursor = readCursor();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::doTick, tickMs, tickMs, TimeUnit.MILLISECONDS);
        }
    }

    public void stop() {
        running.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ignore) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public long currentCursorSequence() {
        return lastCursorSequence;
    }

    void doTick() {
        if (!running.get()) return;
        try {
            DsBinlogPosition start = lastCursor;
            DsBinlogPosition end = sourceDb.getBinlogStore().getCurrentPosition();
            if (end == null) return;
            if (start != null && start.sequence() >= end.sequence()) {
                return;
            }
            DsBinlogPosition actualStart = (start == null) ? new DsBinlogPosition("", 0, 0) : start;
            DsBinlogContext.setInReplay(true);
            int applied;
            try {
                applied = sourceDb.getBinlogStore().replayBinlog(actualStart, end, targetDb::applyBinlogEntry);
            } finally {
                DsBinlogContext.clear();
            }
            System.out.println("[BINLOG_REPLICATOR_TICK] start=" + actualStart
                    + " end=" + end + " applied=" + applied);
            lastCursor = end;
            lastCursorSequence = end.sequence();
            writeCursor(end);
        } catch (Throwable t) {
            String endStr;
            try {
                endStr = String.valueOf(sourceDb.getBinlogStore().getCurrentPosition());
            } catch (Throwable ignore) { endStr = "?"; }
            System.out.println("[BINLOG_REPLICATOR_TICK_ERROR] start=" + lastCursor
                    + " end=" + endStr
                    + " ex=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
        }
    }

    private DsBinlogPosition readCursor() throws IOException {
        if (!cursorFile.exists()) return null;
        String s = new String(Files.readAllBytes(cursorFile.toPath()), StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\|");
        if (parts.length != 3) return null;
        String dayKey = parts[0];
        long fixedOffset = Long.parseLong(parts[1]);
        long sequence = Long.parseLong(parts[2]);
        lastCursorSequence = sequence;
        return new DsBinlogPosition(dayKey, fixedOffset, sequence);
    }

    private void writeCursor(DsBinlogPosition pos) throws IOException {
        String line = pos.dayKey() + "|" + pos.fixedOffset() + "|" + pos.sequence() + "\n";
        try (RandomAccessFile raf = new RandomAccessFile(cursorFile, "rw")) {
            raf.setLength(0);
            raf.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public void close() throws Exception {
        stop();
    }
}
