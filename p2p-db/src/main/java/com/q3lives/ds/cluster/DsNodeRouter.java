package com.q3lives.ds.cluster;

import java.util.Objects;

/**
 * 按 rowId 高 12 位进行跨节点路由。
 *
 * <p>仅对聚合层 rowId 有效；存储层 DsHashMap 的 entryId、valueStore 的 recordId
 * 以及 bucketStore 的 dataId 都是纯本地地址，不携带节点信息。</p>
 */
public final class DsNodeRouter {

    private final DsServerDirectory directory;

    public DsNodeRouter(DsServerDirectory directory) {
        this.directory = Objects.requireNonNull(directory, "directory cannot be null");
    }

    /**
     * 返回 rowId 所属的目标 serverId（高 12 位）。
     */
    public static int serverId(long rowId) {
        return DsIdLayout.serverId(rowId);
    }

    /**
     * 返回当前已知所有 serverId。
     */
    public int[] listServerIds() {
        return directory.listServerIds();
    }

    /**
     * 返回 rowId 所属节点的 endpoint；若该 serverId 未注册则返回 {@code null}。
     */
    public DsServerDirectory.Endpoint endpoint(long rowId) {
        return directory.endpoint(serverId(rowId));
    }

    /**
     * 返回指定 serverId 的 endpoint；若未注册则返回 {@code null}。
     */
    public DsServerDirectory.Endpoint endpoint(int serverId) {
        return directory.endpoint(serverId);
    }

    /**
     * 返回底层目录，用于获取副本列表等扩展信息。
     */
    public DsServerDirectory getDirectory() {
        return directory;
    }
}
