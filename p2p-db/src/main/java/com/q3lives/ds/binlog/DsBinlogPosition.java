package com.q3lives.ds.binlog;

import java.util.Objects;

/**
 * DsBinlog 水位：dayKey + fixed 文件偏移 + sequence。
 *
 * <p>语义为<b>排他水位</b>：{@code fixedOffset} 是下一个待写入帧的字节偏移，
 * 也是回放时应停止的位置。基于该水位可实现无暂停写的在线热备：
 * snapshot 瞬间记录水位，后续增量备份只捕获该水位之后的 binlog 帧，
 * 恢复时先还原基线再重放 [basePosition, targetPosition) 区间的事件。
 */
public final class DsBinlogPosition {

    public static final DsBinlogPosition LATEST = new DsBinlogPosition("", Long.MAX_VALUE, Long.MAX_VALUE);

    private final String dayKey;
    private final long fixedOffset;
    private final long sequence;

    public DsBinlogPosition(String dayKey, long fixedOffset, long sequence) {
        this.dayKey = dayKey != null ? dayKey : "";
        this.fixedOffset = fixedOffset;
        this.sequence = sequence;
    }

    public String dayKey() { return dayKey; }
    public long fixedOffset() { return fixedOffset; }
    public long sequence() { return sequence; }

    public boolean isLatest() {
        return fixedOffset == Long.MAX_VALUE && "".equals(dayKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DsBinlogPosition)) return false;
        DsBinlogPosition that = (DsBinlogPosition) o;
        return fixedOffset == that.fixedOffset
                && sequence == that.sequence
                && dayKey.equals(that.dayKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dayKey, fixedOffset, sequence);
    }

    @Override
    public String toString() {
        return "DsBinlogPosition{dayKey='" + dayKey + "', fixedOffset=" + fixedOffset + ", sequence=" + sequence + '}';
    }
}
