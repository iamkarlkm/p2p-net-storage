package com.q3lives.ds.cluster;

/**
 * 按 rowId 的低 8 位路由到本地 256 分区之一。
 *
 * <p>仅对聚合层 rowId 有效；存储层 DsHashMap 的 entryId、valueStore 的 recordId
 * 以及 bucketStore 的 dataId 都是纯本地地址，不携带分区信息。</p>
 */
public final class DsPartitionRouter {

    private DsPartitionRouter() {
    }

    /**
     * 从 rowId 提取本地分区号（低 8 位）。
     *
     * @param rowId 聚合层 rowId
     * @return 分区号 [0, 255]
     */
    public static int partitionId(long rowId) {
        return DsIdLayout.partitionId(rowId);
    }

    /**
     * 返回当前位布局支持的最大分区数（2^8 = 256）。
     */
    public static int maxPartitionCount() {
        return (int) (DsIdLayout.PARTITION_MASK + 1L);
    }
}
