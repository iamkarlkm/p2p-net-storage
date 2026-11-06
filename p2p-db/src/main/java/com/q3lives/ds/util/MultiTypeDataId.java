package com.q3lives.ds.util;

/**
 * 64-bit 三段式数据 ID 位运算工具类。
 *
 * <p>位布局：
 * <pre>
 *  63      48       40                                                  0
 * +---------+--------+----------------------------------------------------+
 * | TypeID  |  power |              index ID                              |
 * | (16bits)| (8bits)|              (40 bits)                             |
 * +---------+--------+----------------------------------------------------+
 * </pre>
 *
 * <p>严格按用户设计，全部使用位运算（shl / shr / and），不做条件分支，CPU 层面几个时钟周期完成。
 *
 * <ul>
 *   <li>TypeID ：[63:48] 共 16 bit，可存 0 ~ 65535 种数据类型</li>
 *   <li>power  ：[47:40] 共 8 bit，bucketSize = 2^power，与 DsFixedBucketStore 兼容</li>
 *   <li>indexId：[39: 0] 共 40 bit，每类型下每 power 可存 2^40 ≈ 1 万亿个定长单元</li>
 * </ul>
 */
public final class MultiTypeDataId {

    public static final int TYPE_BITS = 16;
    public static final int POWER_BITS = 8;
    public static final int INDEX_BITS = 40;

    public static final int TYPE_SHIFT = POWER_BITS + INDEX_BITS; // 48
    public static final int POWER_SHIFT = INDEX_BITS;             // 40

    public static final long TYPE_MASK = (1L << TYPE_BITS) - 1;   // 0xFFFF
    public static final long POWER_MASK = (1L << POWER_BITS) - 1; // 0xFF
    public static final long INDEX_MASK = (1L << INDEX_BITS) - 1; // 0xFFFFFFFFFF (10 个 F)

    public static final int MAX_TYPE = (1 << TYPE_BITS) - 1;
    public static final int MAX_POWER = (1 << POWER_BITS) - 1;
    public static final long MAX_INDEX = (1L << INDEX_BITS) - 1;

    private MultiTypeDataId() {
    }

    public static long encode(int typeId, int power, long indexId) {
        return (((long) typeId & TYPE_MASK) << TYPE_SHIFT)
                | (((long) power & POWER_MASK) << POWER_SHIFT)
                | (indexId & INDEX_MASK);
    }

    public static int decodeTypeId(long id) {
        return (int) ((id >>> TYPE_SHIFT) & TYPE_MASK);
    }

    public static int decodePower(long id) {
        return (int) ((id >>> POWER_SHIFT) & POWER_MASK);
    }

    public static long decodeIndexId(long id) {
        return id & INDEX_MASK;
    }

    public static boolean isValid(long id) {
        return id != 0L;
    }
}
