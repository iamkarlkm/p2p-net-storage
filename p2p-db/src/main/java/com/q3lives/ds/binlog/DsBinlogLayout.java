package com.q3lives.ds.binlog;

/**
 * DsBinlog 二进制日志布局常量（对齐 MySQL binlog 的 fixed+dynamic 分区思想）。
 *
 * <p>三个文件独立存储、零相互写放大：
 * <ul>
 *   <li>{@code dsbinlog_<dayKey>_fixed.dat}   — 固定帧区（每帧 FIXED_FRAME_SIZE=256B，便于 O(1) seek）。</li>
 *   <li>{@code dsbinlog_<dayKey>_dyn.dat}     — 动态列大字节区（append-only，按帧内 dynStart/dynLen 引用）。</li>
 *   <li>{@code dsbinlog_bytes.idx}            — 跨天 DsBytes 字典索引（复用 DsData 内容寻址 + refCount）。</li>
 * </ul>
 *
 * <p>fixed frame 256B 字段（全部是 primitive 定长 + 原地读，性能与 MySQL binlog row_event 固定头同阶）：
 * <pre>
 *   0..3    MAGIC       (0x4453424C = "DSBL")
 *   4..7    version     int32, 恒 = 1
 *   8..11   flags       int32  bit0=TOMBSTONE  bit1=HAS_DYN_BYTES(非0字节引用)
 *  12..15   crc32       int32  IEEE 0xEDB88320 (写前先将此处清 0 再算，回放时同序对齐)
 *  16..19   opType      int32  OpType 枚举 ordinal
 *  20..23   serverId    int32  节点 serverId，主从/多副本区分
 *  24..31   sequence    int64  同 serverId 单调递增（乱序回放按 seq 覆盖）
 *  32..39   timestamp   int64  millis
 *  40..43   tableId     int32  逻辑表 id
 *  44..47   rowKey      int32  行 key hash/主键低 32 位（也可由 colValues 里的列表示，这里只为快速 seek）
 *  48..55   dynStart    int64  在 dyn 文件中的绝对字节偏移（无动态列则 = -1）
 *  56..59   dynLen      int32  动态列总字节数（0 表示无）
 *  60..63   dynCount    int32  本帧携带的动态列个数
 *  64..127  colIdsMask  64B = 512 bit 位图（最多支持 512 列，每 bit=1 表示该列在本帧出现）
 * 128..255  colFixed    128B = 64 × int16 slot：
 *                       若 colIdsMask 第 k 位 = 1 且值可 fit 进 2B → 直接存 (value & 0xFFFF) + dynIndex=0xFFFF
 *                       否则 → 高 12 bit = dynColumnIndex (0..dynCount-1)  低 4 bit = DynValueType(type,见下)
 * </pre>
 *
 * <p>动态列 payload（dyn 文件中按 frame 聚簇写，不跨帧共享）：
 * <pre>
 *   每个动态列 = [4B len][N bytes value]
 *   len = 0 → NULL 语义
 * </pre>
 *
 * <p>DsBytes 索引（当用户传 byte[] 且不想重复内容写入时，走 {@link DsBytes} 存 DsData indexId 8B）：
 * <pre>
 *   若上层通过 DsBytesRef 传入 → colFixed slot = 高 12 bit=index  低 4 bit=0xE (= DsBytesRef)
 *   → dyn 区 [8B long indexId]，通过 DsBytes.get(indexId) 取实际内容。
 * </pre>
 */
public final class DsBinlogLayout {

    public static final int FIXED_FRAME_SIZE = 256;

    public static final int MAGIC = 0x4453424C; // "DSBL"
    public static final int VERSION = 1;

    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_FLAGS = 8;
    public static final int OFF_CRC32 = 12;
    public static final int OFF_OP_TYPE = 16;
    public static final int OFF_SERVER_ID = 20;
    public static final int OFF_SEQUENCE = 24;
    public static final int OFF_TIMESTAMP = 32;
    public static final int OFF_TABLE_ID = 40;
    public static final int OFF_ROW_KEY = 44;
    public static final int OFF_DYN_START = 48;
    public static final int OFF_DYN_LEN = 56;
    public static final int OFF_DYN_COUNT = 60;

    public static final int OFF_COL_IDS_MASK = 64;
    public static final int COL_IDS_MASK_BYTES = 64;
    public static final int COL_IDS_MAX = COL_IDS_MASK_BYTES * 8; // 512

    public static final int OFF_COL_FIXED = 128;
    public static final int COL_FIXED_SLOT_BYTES = 2;
    public static final int COL_FIXED_SLOT_COUNT = (FIXED_FRAME_SIZE - OFF_COL_FIXED) / COL_FIXED_SLOT_BYTES; // 64

    public static final int FLAG_BIT_TOMBSTONE = 1 << 0;
    public static final int FLAG_BIT_HAS_DYN = 1 << 1;

    public static final int DYN_INDEX_NONE = 0x7FF;
    public static final int COL_SLOT_INLINE_BIT = 0x8000;   // bit15 = 1 → 带符号 15 位 short inline
    public static final int COL_SLOT_INLINE_MASK = 0x7FFF;  // inline value（low 15 bits，sign-ext 还原）

    public static final int DVT_BYTES_RAW    = 0x1;   // dyn 区 [4B len][raw bytes]
    public static final int DVT_LONG         = 0x2;   // dyn 区 8B long
    public static final int DVT_INT          = 0x3;   // dyn 区 4B int
    public static final int DVT_DOUBLE       = 0x4;   // dyn 区 8B double
    public static final int DVT_FLOAT        = 0x5;   // dyn 区 4B float
    public static final int DVT_SHORT        = 0x6;   // dyn 区 2B short (溢出 ±16383 inline 的 short)
    public static final int DVT_DS_BYTES_REF = 0xE;   // dyn 区 8B DsData indexId（DsBytes）
    public static final int DVT_NULL         = 0xF;   // 空值（dyn 不写任何字节）

    public static final String FILE_PREFIX = "dsbinlog_";
    public static final String FIXED_SUFFIX = "_fixed.dat";
    public static final String DYN_SUFFIX = "_dyn.dat";
    public static final String DSBYTES_STORE_NAME = "_binlog_bytes";

    /**
     * bit15=0 → non-inline: [bit14..bit4] 共 11 bit = dynIndex (0..2047) ; [bit3..bit0] = dvType (1..15)
     */
    public static int colSlotPack(int dynIndex, int dvType) {
        // 清除 bit15 保证 non-inline
        int v = ((dynIndex & 0x7FF) << 4) | (dvType & 0xF);
        if ((v & COL_SLOT_INLINE_BIT) != 0) {
            // 极端情况 bit15=1 了（dynIndex high bit），限制 dynIndex 范围以避免冲突
            v = ((0x3FF) << 4) | (dvType & 0xF);
        }
        return v & 0xFFFF;
    }

    public static int colSlotDynIndex(int packed) {
        return (packed >>> 4) & 0x7FF;
    }

    public static int colSlotDvType(int packed) {
        return packed & 0xF;
    }

    public static boolean colSlotIsInline(int packed) {
        return (packed & COL_SLOT_INLINE_BIT) != 0;
    }

    public static short colSlotInlineValue(int packed) {
        // low 15 bits 做 sign-extend 还原成 short (15 位补码)
        int v = packed & COL_SLOT_INLINE_MASK;
        if ((v & 0x4000) != 0) v = v - 0x8000;
        return (short) v;
    }

    public static int colSlotPackInline(short value) {
        int i = value;
        int u = ((i << 1) >> 1); // sign-preserve 截断到 15 bit 语义
        int bits15 = u & COL_SLOT_INLINE_MASK;
        return (bits15 | COL_SLOT_INLINE_BIT) & 0xFFFF;
    }

    public static boolean canInlineShort(short value) {
        return value >= -16384 && value <= 16383; // 15-bit signed
    }

    public static boolean canInlineInt(int value) {
        return value >= -16384 && value <= 16383;
    }

    public static boolean canInlineLong(long value) {
        return value >= -16384L && value <= 16383L;
    }

    private DsBinlogLayout() {
    }
}
