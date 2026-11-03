package com.q3lives.ds.cluster;

/**
 * 分布式节点目录，维护 {@code serverId → endpoint} 映射。
 *
 * <p>供 {@link DsNodeRouter} 按 rowId 高 12 位解析目标节点地址。</p>
 */
public interface DsServerDirectory {

    /**
     * 节点地址描述。
     *
     * @param host 主机名或 IP
     * @param port 端口
     * @param queueSize 节点处理队列大小，用于客户端连接池配置
     */
    record Endpoint(String host, int port, int queueSize) {
    }

    /**
     * 返回当前已知所有 serverId（可为空）。
     */
    int[] listServerIds();

    /**
     * 返回指定 serverId 的 endpoint；若不存在返回 {@code null}。
     */
    Endpoint endpoint(int serverId);

    /**
     * 返回指定 serverId 的副本节点 serverId 列表。
     *
     * <p>默认实现返回空数组，表示该 serverId 没有额外副本。分布式副本 fan-out
     * 会调用此方法，将主节点写入同步复制到所有副本节点。</p>
     */
    default int[] replicas(int serverId) {
        return new int[0];
    }
}
