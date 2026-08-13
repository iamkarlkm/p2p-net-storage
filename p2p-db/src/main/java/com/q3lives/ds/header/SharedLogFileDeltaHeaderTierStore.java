package com.q3lives.ds.header;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.q3lives.ds.database.config.DsDbConfig;

public class SharedLogFileDeltaHeaderTierStore implements HeaderTieredStore {

    private static final ConcurrentHashMap<String, SharedLogContext> CONTEXTS = new ConcurrentHashMap<>();

    static final class SharedLogContext {
        final SharedHeaderDeltaLog log;
        final AtomicLong refCount = new AtomicLong(0L);

        SharedLogContext(SharedHeaderDeltaLog log) {
            this.log = log;
        }
    }

    private final String tierDirPath;
    private final String relativePath;
    private final String name;
    private final int blockSize;
    private volatile TierState state = TierState.IDLE;
    private SharedLogContext ctx;
    private long storeId = -1L;
    private ByteBuffer base;
    private volatile boolean anyDirty = false;

    public SharedLogFileDeltaHeaderTierStore(String name, File dataFile, String tierDirPath) throws IOException {
        this(name, dataFile, 64 * 1024, tierDirPath);
    }

    public SharedLogFileDeltaHeaderTierStore(String name, File dataFile, int blockSize, String tierDirPath) throws IOException {
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize");
        if (dataFile == null) throw new NullPointerException("dataFile");
        this.name = name;
        this.blockSize = blockSize;
        String rel;
        try {
            String can = dataFile.getCanonicalPath();
            String root = tierDirPath == null ? new File(".").getCanonicalPath() : new File(tierDirPath).getParentFile() != null ? new File(tierDirPath).getParentFile().getCanonicalPath() : new File(".").getCanonicalPath();
            if (can.startsWith(root)) {
                rel = can.substring(root.length());
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
            } else {
                rel = dataFile.getPath();
            }
        } catch (IOException e) {
            rel = dataFile.getPath();
        }
        rel = safeRelative(rel);
        this.relativePath = rel;
        this.tierDirPath = tierDirPath;
    }

    private static String safeRelative(String s) {
        if (s == null) return "_unknown_";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == ':' || c == '?' || c == '*' || c == '"' || c == '<' || c == '>' || c == '|') sb.append('/');
            else sb.append(c);
        }
        return sb.length() == 0 ? "_unknown_" : sb.toString();
    }

    private SharedLogContext getOrCreateContext() throws IOException {
        SharedLogContext c = ctx;
        if (c != null) return c;
        String key = tierDirPath == null ? "__default__" : tierDirPath;
        c = CONTEXTS.get(key);
        if (c != null) {
            this.ctx = c;
            c.refCount.incrementAndGet();
            return c;
        }
        synchronized (CONTEXTS) {
            c = CONTEXTS.get(key);
            if (c != null) {
                this.ctx = c;
                c.refCount.incrementAndGet();
                return c;
            }
            File dir = tierDirPath == null ? new File(DsDbConfig.TIER_SUB_DIR) : new File(tierDirPath);
            if (!dir.exists()) dir.mkdirs();
            DsDbConfig cfg = DsDbConfig.getInstance();
            StoreIdRegistry registry = cfg.getOrCreateStoreIdRegistry(dir);
            String dayKey = java.time.LocalDate.now().toString();
            SharedHeaderDeltaLog log = new SharedHeaderDeltaLog(dir, dayKey, registry);
            c = new SharedLogContext(log);
            c.refCount.set(1);
            CONTEXTS.put(key, c);
            this.ctx = c;
            return c;
        }
    }

    @Override
    public synchronized void attachBase(ByteBuffer baseHeaderBlock) throws IOException {
        if (baseHeaderBlock == null) throw new NullPointerException("baseHeaderBlock");
        if (baseHeaderBlock.capacity() != blockSize) {
            throw new IOException("header blockSize mismatch: expected=" + blockSize + " actual=" + baseHeaderBlock.capacity());
        }
        this.base = baseHeaderBlock;
        SharedLogContext c = getOrCreateContext();
        this.storeId = c.log.internStoreId(relativePath);
        byte[] snapshot = c.log.getReadSnapshot(storeId);
        if (snapshot != null) {
            int end = Math.min(blockSize, snapshot.length);
            BitSet bs = c.log.getDirtyBitSet(storeId);
            for (int i = 0; i < end; i++) {
                if (bs != null && bs.get(i)) {
                    base.put(i, snapshot[i]);
                }
            }
            if (bs != null && !bs.isEmpty()) anyDirty = true;
        }
        state = TierState.IDLE;
    }

    @Override
    public synchronized ByteBuffer getReadBuffer() {
        if (base == null) throw new IllegalStateException("attachBase not called");
        return base;
    }

    @Override
    public synchronized ByteBuffer getWriteBufferForField(int offset, int len) {
        if (base == null) throw new IllegalStateException("attachBase not called");
        if (offset < 0 || len <= 0 || (long) offset + len > blockSize) {
            throw new IndexOutOfBoundsException("offset=" + offset + " len=" + len);
        }
        return base;
    }

    @Override
    public synchronized void markFieldDirty(int offset, int len) {
        if (base == null || offset < 0 || len <= 0) return;
        int end = Math.min(blockSize, offset + len);
        if (end <= offset) return;
        if (ctx == null) return;
        try {
            ctx.log.markAndAppendIfNeeded(storeId, base, offset, end - offset);
            anyDirty = true;
        } catch (IOException e) {
            throw new IllegalStateException("shared log write failed", e);
        }
    }

    @Override
    public synchronized void markFullDirty() {
        if (base == null || ctx == null) return;
        try {
            ctx.log.markFullAndAppend(storeId, base);
            anyDirty = true;
        } catch (IOException e) {
            throw new IllegalStateException("shared log write failed", e);
        }
    }

    @Override
    public synchronized boolean isDirty() {
        return anyDirty;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (ctx != null) ctx.log.flushAll();
    }

    @Override
    public TierState getState() {
        return state;
    }

    @Override
    public synchronized void rollover(String dayKey) throws IOException {
        if (ctx == null) return;
        try {
            ctx.log.flushAll();
            ctx.log.rollover(dayKey);
            state = TierState.IDLE;
            anyDirty = false;
        } finally {
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            if (ctx != null) {
                try { ctx.log.flushAll(); } finally {}
                long remain = ctx.refCount.decrementAndGet();
                if (remain <= 0L) {
                    synchronized (CONTEXTS) {
                        if (ctx.refCount.get() <= 0L) {
                            String key = tierDirPath == null ? "__default__" : tierDirPath;
                            SharedLogContext cur = CONTEXTS.get(key);
                            if (cur == ctx) CONTEXTS.remove(key);
                            try { ctx.log.close(); } finally {}
                        }
                    }
                }
                ctx = null;
            }
        } finally {
            base = null;
        }
    }

    @Override
    public String debugName() {
        return "SharedLogFileDelta[" + name + ", storeId=" + storeId + ", rel=" + relativePath + "]";
    }
}
