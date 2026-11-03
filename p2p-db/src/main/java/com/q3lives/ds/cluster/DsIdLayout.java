package com.q3lives.ds.cluster;

/**
 * 54 位 rowId 的位布局（兼容前端 JS 整数显示与回传上限）。
 *
 * <p>仅聚合层 rowId 携带位置信息；存储层 DsHashMap 的 entryId、valueStore 的 recordId
 * 以及 DsFixedBucketStore 的 dataId 都是纯本地地址，不嵌入 serverId/partitionId。</p>
 *
 * <p>整体控制在 54 位以内，因此可以直接作为 JSON 数字往返于前端（JS 可安全表示 2^53-1，
 * 本布局最大值 2^54-1；在常规 serverId 范围内仍能保证精确回传）。</p>
 *
 * <pre>
 *  53                32 31                 8 7        0
 * +-------------------+----------------------+----------+
 * |     serverId      |    local address     | partition|
 * |     (22 bit)      |       (24 bit)       | (8 bit)  |
 * +-------------------+----------------------+----------+
 * </pre>
 */
public final class DsIdLayout {

    public static final int SERVER_ID_BITS = 22;
    public static final int PARTITION_BITS = 8;
    public static final int TOTAL_ROW_ID_BITS = 54;
    public static final int LOCAL_ADDRESS_BITS = TOTAL_ROW_ID_BITS - SERVER_ID_BITS - PARTITION_BITS;

    public static final long SERVER_ID_MASK = (1L << SERVER_ID_BITS) - 1L;
    public static final long PARTITION_MASK = (1L << PARTITION_BITS) - 1L;
    public static final long LOCAL_ADDRESS_MASK = (1L << LOCAL_ADDRESS_BITS) - 1L;

    public static final int SERVER_ID_SHIFT = TOTAL_ROW_ID_BITS - SERVER_ID_BITS;
    public static final int LOCAL_ADDRESS_SHIFT = PARTITION_BITS;

    private DsIdLayout() {
    }

    /**
     * 从 rowId 提取高 22 位 serverId。
     */
    public static int serverId(long id) {
        return (int) ((id >>> SERVER_ID_SHIFT) & SERVER_ID_MASK);
    }

    /**
     * 从 rowId 提取低 8 位 partitionId。
     */
    public static int partitionId(long id) {
        return (int) (id & PARTITION_MASK);
    }

    /**
     * 从 rowId 提取中间 24 位本地逻辑地址。
     */
    public static long localAddress(long id) {
        return (id >>> LOCAL_ADDRESS_SHIFT) & LOCAL_ADDRESS_MASK;
    }

    /**
     * 将 serverId、partitionId、localAddress 组合成全局 rowId。
     */
    public static long compose(int serverId, int partitionId, long localAddress) {
        if (serverId < 0 || serverId > SERVER_ID_MASK) {
            throw new IllegalArgumentException("serverId out of range: " + serverId);
        }
        if (partitionId < 0 || partitionId > PARTITION_MASK) {
            throw new IllegalArgumentException("partitionId out of range: " + partitionId);
        }
        if (localAddress < 0 || localAddress > LOCAL_ADDRESS_MASK) {
            throw new IllegalArgumentException("localAddress out of range: " + localAddress);
        }
        return ((long) serverId << SERVER_ID_SHIFT)
                | ((localAddress & LOCAL_ADDRESS_MASK) << LOCAL_ADDRESS_SHIFT)
                | (partitionId & PARTITION_MASK);
    }
}
