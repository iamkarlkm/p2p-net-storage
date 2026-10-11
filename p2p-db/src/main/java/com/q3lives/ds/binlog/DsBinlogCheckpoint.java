package com.q3lives.ds.binlog;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * binlog checkpoint 元数据：记录已可靠落盘的数据位点。
 *
 * <p>存储位置：{@code <dbHome>/_binlog/_binlog_checkpoint/position.yaml}
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code dayKey} / {@code fixedOffset} / {@code sequence}：checkpoint 帧在 binlog 文件中的物理位置。</li>
 *   <li>{@code committedSequence}：此前所有数据变更已序列化到 dataFile/index（COMMIT_DURABLE 边界）。</li>
 *   <li>{@code replayedSequence}：此前所有 binlog 已回放并落盘到 dataFile/index（REPLAY_DURABLE 边界）。</li>
 *   <li>{@code timestamp}：checkpoint 写入时间（millis）。</li>
 * </ul>
 */
public final class DsBinlogCheckpoint {

    public static final String CHECKPOINT_DIR = "_binlog_checkpoint";
    public static final String POSITION_FILE = "position.yaml";

    private final String dayKey;
    private final long fixedOffset;
    private final long sequence;
    private final long committedSequence;
    private final long replayedSequence;
    private final long timestamp;

    public DsBinlogCheckpoint(String dayKey, long fixedOffset, long sequence,
                              long committedSequence, long replayedSequence, long timestamp) {
        this.dayKey = dayKey == null ? "" : dayKey;
        this.fixedOffset = fixedOffset;
        this.sequence = sequence;
        this.committedSequence = committedSequence;
        this.replayedSequence = replayedSequence;
        this.timestamp = timestamp;
    }

    public DsBinlogCheckpoint(DsBinlogPosition position, long committedSequence, long replayedSequence, long timestamp) {
        this(position.dayKey(), position.fixedOffset(), position.sequence(),
                committedSequence, replayedSequence, timestamp);
    }

    public String dayKey() { return dayKey; }
    public long fixedOffset() { return fixedOffset; }
    public long sequence() { return sequence; }
    public long committedSequence() { return committedSequence; }
    public long replayedSequence() { return replayedSequence; }
    public long timestamp() { return timestamp; }

    /**
     * 转换为可用于回放的 binlog 位点。
     */
    public DsBinlogPosition toPosition() {
        return new DsBinlogPosition(dayKey, fixedOffset, sequence);
    }

    /**
     * checkpoint 是否有效：包含合法的 dayKey 且 fixedOffset 按帧对齐。
     */
    public boolean isValid() {
        return dayKey != null && !dayKey.isEmpty()
                && fixedOffset >= 0
                && fixedOffset % DsBinlogLayout.FIXED_FRAME_SIZE == 0;
    }

    /**
     * 加载 checkpoint 文件；不存在或解析失败返回 null。
     */
    public static DsBinlogCheckpoint load(File binlogDir) {
        File file = checkpointFile(binlogDir);
        if (!file.exists() || !file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) return null;
            Map<String, Object> m = (Map<String, Object>) loaded;
            String dayKey = stringValue(m.get("dayKey"));
            long fixedOffset = longValue(m.get("fixedOffset"));
            long sequence = longValue(m.get("sequence"));
            long committedSequence = longValue(m.get("committedSequence"));
            long replayedSequence = longValue(m.get("replayedSequence"));
            long timestamp = longValue(m.get("timestamp"));
            return new DsBinlogCheckpoint(dayKey, fixedOffset, sequence,
                    committedSequence, replayedSequence, timestamp);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 原子保存 checkpoint 文件。
     */
    private static final Object SAVE_LOCK = new Object();

    public void save(File binlogDir) throws IOException {
        synchronized (SAVE_LOCK) {
        File dir = new File(binlogDir, CHECKPOINT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create checkpoint dir: " + dir);
        }
        File target = checkpointFile(binlogDir);
        File tmp = new File(dir, POSITION_FILE + ".tmp");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dayKey", dayKey);
        m.put("fixedOffset", fixedOffset);
        m.put("sequence", sequence);
        m.put("committedSequence", committedSequence);
        m.put("replayedSequence", replayedSequence);
        m.put("timestamp", timestamp);
        try (PrintWriter pw = new PrintWriter(tmp, "UTF-8")) {
            Yaml yaml = new Yaml();
            yaml.dump(m, pw);
        }
        Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    public static File checkpointFile(File binlogDir) {
        return new File(new File(binlogDir, CHECKPOINT_DIR), POSITION_FILE);
    }

    private static String stringValue(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long longValue(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Override
    public String toString() {
        return "DsBinlogCheckpoint{dayKey='" + dayKey + '\'' +
                ", fixedOffset=" + fixedOffset +
                ", sequence=" + sequence +
                ", committedSequence=" + committedSequence +
                ", replayedSequence=" + replayedSequence +
                ", timestamp=" + timestamp + '}';
    }
}
