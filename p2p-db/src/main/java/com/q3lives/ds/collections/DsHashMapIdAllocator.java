package com.q3lives.ds.collections;

import java.io.IOException;

import com.q3lives.ds.cluster.DsIdLayout;

/**
 * 聚合层 rowId 分配器。
 *
 * <p>每个表持有一个聚合层 DsHashMap，仅用于分配全局唯一的 rowId。
 * 该 map 的 entryId 被用作 rowId 的本地逻辑地址（localAddress），
 * 再通过 {@link DsIdLayout} 嵌入 serverId 与 partitionId。</p>
 */
public class DsHashMapIdAllocator {

    private final DsHashMap aggregationMap;

    public DsHashMapIdAllocator(DsHashMap aggregationMap) {
        if (aggregationMap == null) {
            throw new NullPointerException("aggregationMap");
        }
        this.aggregationMap = aggregationMap;
    }

    /**
     * 分配一个全局 rowId。
     *
     * @param serverId    目标节点，高 22 位
     * @param partitionId 本地分区，低 8 位
     * @return 全局 rowId
     * @throws IOException 若底层 map 分配失败
     */
    public long allocateRowId(int serverId, int partitionId) throws IOException {
        long localAddress = aggregationMap.allocateEntryId();
        if (localAddress > DsIdLayout.LOCAL_ADDRESS_MASK) {
            aggregationMap.recycleEntryId(localAddress);
            throw new IllegalStateException(
                    "localAddress overflow: " + localAddress + " exceeds 24-bit limit");
        }
        return DsIdLayout.compose(serverId, partitionId, localAddress);
    }

    /**
     * 回收一个全局 rowId，使其本地逻辑地址可复用。
     *
     * @param rowId 要回收的 rowId
     */
    public void recycleRowId(long rowId) {
        long localAddress = DsIdLayout.localAddress(rowId);
        aggregationMap.recycleEntryId(localAddress);
    }

    /**
     * 返回底层聚合层 map，仅用于测试或诊断。
     */
    public DsHashMap getAggregationMap() {
        return aggregationMap;
    }
}
