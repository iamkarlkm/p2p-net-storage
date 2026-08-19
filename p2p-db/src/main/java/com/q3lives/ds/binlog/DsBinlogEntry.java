package com.q3lives.ds.binlog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * 解析后不变的 binlog 帧。
 * <p>字段：
 * <ul>
 *   <li>{@link #sequence} 同 serverId 单调递增；回放按 seq 覆写旧值。</li>
 *   <li>{@link #colIds} 出现的列 id（colIdsMask 中 bit=1 的位置集合，最多 512 列）。</li>
 *   <li>{@link #colValueSlots} 与 colIds 同序，每个 slot 存 2 字节原始 packed（由 replayer 或使用者通过 {@link #decodeCol(int, DsBytes)} 按类型解析）。</li>
 *   <li>{@link #dynBytes} 仅用于回放产出的 raw bytes；写侧直接塞 dyn 文件（写侧走 DsBinlogStore.append）。</li>
 * </ul>
 */
public final class DsBinlogEntry {

    public static final class ColValue {
        public final int dvType;
        /** DVT_INLINE_SHORT → short 值；DVT_DS_BYTES_REF → dsBytes indexId；DVT_BYTES_RAW/LONG/INT/DOUBLE/FLOAT → dyn 区 offset (long) 占位。 */
        public final long rawValue;
        /** DVT_BYTES_RAW 时为 bytes；其它类型按需解析后填入（可 null）。 */
        public final Object decoded;

        ColValue(int dvType, long rawValue, Object decoded) {
            this.dvType = dvType;
            this.rawValue = rawValue;
            this.decoded = decoded;
        }
    }

    private final int opTypeOrdinal;
    private final int serverId;
    private final long sequence;
    private final long timestamp;
    private final int tableId;
    private final int rowKey;
    private final BitSet colIdsBitSet;
    private final int[] colIds;
    private final short[] colValueSlots;
    private final List<byte[]> dynFrames;

    DsBinlogEntry(int opTypeOrdinal, int serverId, long sequence, long timestamp,
                  int tableId, int rowKey, BitSet colIdsBitSet, int[] colIds,
                  short[] colValueSlots, List<byte[]> dynFrames) {
        this.opTypeOrdinal = opTypeOrdinal;
        this.serverId = serverId;
        this.sequence = sequence;
        this.timestamp = timestamp;
        this.tableId = tableId;
        this.rowKey = rowKey;
        this.colIdsBitSet = colIdsBitSet;
        this.colIds = colIds;
        this.colValueSlots = colValueSlots;
        this.dynFrames = dynFrames != null ? dynFrames : new ArrayList<>(0);
    }

    public DsBinlogOpType opType() {
        DsBinlogOpType[] all = DsBinlogOpType.values();
        if (opTypeOrdinal < 0 || opTypeOrdinal >= all.length) return DsBinlogOpType.NOOP;
        return all[opTypeOrdinal];
    }

    public int opTypeOrdinal() { return opTypeOrdinal; }
    public int serverId() { return serverId; }
    public long sequence() { return sequence; }
    public long timestamp() { return timestamp; }
    public int tableId() { return tableId; }
    public int rowKey() { return rowKey; }
    public BitSet colIdsBitSet() { return (BitSet) colIdsBitSet.clone(); }
    public int[] colIds() { return Arrays.copyOf(colIds, colIds.length); }
    public int colCount() { return colIds.length; }

    /**
     * 按出现顺序的第 i 列解析值（0<=i<colCount）。
     * @param dsBytes 可为 null（无 DsBytesRef 类型列时可省略）。
     */
    public ColValue decodeCol(int i, DsBytes dsBytes) throws java.io.IOException {
        if (i < 0 || i >= colIds.length) throw new IndexOutOfBoundsException(String.valueOf(i));
        int packed = colValueSlots[i] & 0xFFFF;
        if (DsBinlogLayout.colSlotIsInline(packed)) {
            short v = DsBinlogLayout.colSlotInlineValue(packed);
            return new ColValue(-1, (long) v, v);
        }
        int dvType = DsBinlogLayout.colSlotDvType(packed);
        switch (dvType) {
            case DsBinlogLayout.DVT_NULL:
                return new ColValue(dvType, 0L, null);
            case DsBinlogLayout.DVT_LONG: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                long v = java.nio.ByteBuffer.wrap(b).getLong(0);
                return new ColValue(dvType, v, v);
            }
            case DsBinlogLayout.DVT_INT: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                int v = java.nio.ByteBuffer.wrap(b).getInt(0);
                return new ColValue(dvType, v, v);
            }
            case DsBinlogLayout.DVT_DOUBLE: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                double v = java.nio.ByteBuffer.wrap(b).getDouble(0);
                return new ColValue(dvType, Double.doubleToRawLongBits(v), v);
            }
            case DsBinlogLayout.DVT_FLOAT: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                float v = java.nio.ByteBuffer.wrap(b).getFloat(0);
                return new ColValue(dvType, Float.floatToRawIntBits(v), v);
            }
            case DsBinlogLayout.DVT_SHORT: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                short v = java.nio.ByteBuffer.wrap(b).getShort(0);
                return new ColValue(dvType, (long) v, v);
            }
            case DsBinlogLayout.DVT_BYTES_RAW: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                return new ColValue(dvType, (long) b.length, b);
            }
            case DsBinlogLayout.DVT_DS_BYTES_REF: {
                int idx = DsBinlogLayout.colSlotDynIndex(packed);
                byte[] b = dynFrames.get(idx);
                long indexId = java.nio.ByteBuffer.wrap(b).getLong(0);
                byte[] real = dsBytes == null ? null : dsBytes.get(indexId);
                return new ColValue(dvType, indexId, real);
            }
            default:
                return new ColValue(dvType, 0L, null);
        }
    }
}
