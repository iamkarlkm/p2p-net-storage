package com.q3lives.ds.cluster;

import com.q3lives.ds.database.columnar.DsPartitionManager;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前 JVM 内分布式节点的静态注册表。
 *
 * <p>供无状态的服务端 handler（{@code DbNode*ServerHandler}）根据 rowId 中的 serverId
 * 找到对应的本地 {@link DsPartitionManager} 实例。每个节点在启动时将自己的
 * serverId 与分区管理器注册到这里。</p>
 */
public final class DsNodeContext {

    private static final ConcurrentHashMap<Integer, DsPartitionManager> MANAGERS = new ConcurrentHashMap<>();

    private DsNodeContext() {
    }

    /**
     * 注册一个节点的分区管理器。
     */
    public static void register(int serverId, DsPartitionManager manager) {
        if (manager == null) {
            throw new NullPointerException("manager");
        }
        MANAGERS.put(serverId, manager);
    }

    /**
     * 注销一个节点。
     */
    public static DsPartitionManager unregister(int serverId) {
        return MANAGERS.remove(serverId);
    }

    /**
     * 返回指定 serverId 的分区管理器；若未注册返回 {@code null}。
     */
    public static DsPartitionManager getManager(int serverId) {
        return MANAGERS.get(serverId);
    }

    /**
     * 根据 rowId 高 12 位返回对应的分区管理器。
     */
    public static DsPartitionManager getManager(long rowId) {
        return getManager(DsIdLayout.serverId(rowId));
    }

    /**
     * 返回当前注册的所有 serverId。
     */
    public static int[] listServerIds() {
        return MANAGERS.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * 清空注册表，主要用于测试。
     */
    public static void clear() {
        MANAGERS.clear();
    }
}
