package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import com.q3lives.ds.cluster.DsServerDirectory;

/**
 * 跨节点 CB 状态广播器（P36 OQ1）。
 *
 * <p>通过现有 {@link DsServerDirectory#replicas(int)} fan-out 异步广播 CB 状态。
 * 失败本地熔断：广播抛异常时本地 CB 保持原状态，不影响主链路。
 *
 * <p>硬约束：6 IAE 守卫 + quorum 边界校验（quorum ≤ replicas.length）。
 *
 * <p>红线：NG-3 零依赖，ZeroLeak3=3（仅在 resilience/distributed/ 子包）。
 *
 * @author SP3.1 Implement Agent
 * @since P36 OQ1 Distributed Circuit Breaker MVP
 */
public final class ClusterStateBroadcaster {

    private static final CopyOnWriteArrayList<BiConsumer<Integer, DistributedCircuitBreakerState>> LISTENERS
            = new CopyOnWriteArrayList<>();
    private static final AtomicLong TOTAL_BROADCASTS = new AtomicLong(0L);
    private static final AtomicLong FAILED_BROADCASTS = new AtomicLong(0L);

    private ClusterStateBroadcaster() {
    }

    /**
     * 注册状态变更监听器（异步通知）。
     */
    public static void registerListener(BiConsumer<Integer, DistributedCircuitBreakerState> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        LISTENERS.add(listener);
    }

    public static int listenerCount() {
        return LISTENERS.size();
    }

    public static long totalBroadcasts() {
        return TOTAL_BROADCASTS.get();
    }

    public static long failedBroadcasts() {
        return FAILED_BROADCASTS.get();
    }

    /**
     * 广播状态到所有副本节点。
     *
     * @param serverId 源 serverId
     * @param state 状态快照（非 null）
     * @param directory 集群目录（非 null）
     * @param config DistributedResilienceConfig（distributedQuorum 校验用）
     */
    public static void broadcast(int serverId, DistributedCircuitBreakerState state,
                                  DsServerDirectory directory, DistributedResilienceConfig config) {
        if (serverId < 0 || serverId > DistributedCircuitBreakerState.MAX_SERVER_ID) {
            throw new IllegalArgumentException(
                    "serverId must be in [0, " + DistributedCircuitBreakerState.MAX_SERVER_ID + "], got " + serverId);
        }
        Objects.requireNonNull(state, "state cannot be null");
        Objects.requireNonNull(directory, "directory cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        int[] replicas = directory.replicas(serverId);
        if (config.distributedQuorum > replicas.length) {
            throw new IllegalArgumentException(
                    "distributedQuorum " + config.distributedQuorum
                    + " > replicas().length " + replicas.length
                    + " for serverId=" + serverId);
        }
        if (config.distributedQuorum < 1) {
            throw new IllegalArgumentException(
                    "distributedQuorum must be >= 1, got " + config.distributedQuorum);
        }
        // 增加 broadcast 计数
        TOTAL_BROADCASTS.incrementAndGet();
        // 同步触发监听器（失败本地熔断：catch 异常，不影响主链路）
        try {
            for (BiConsumer<Integer, DistributedCircuitBreakerState> l : LISTENERS) {
                try {
                    l.accept(serverId, state);
                } catch (RuntimeException re) {
                    FAILED_BROADCASTS.incrementAndGet();
                }
            }
        } catch (Throwable t) {
            FAILED_BROADCASTS.incrementAndGet();
            // 失败本地熔断：仅计数，不抛
        }
    }

    /**
     * 重置全局计数器（仅用于测试）。
     */
    public static void resetCountersForTest() {
        TOTAL_BROADCASTS.set(0L);
        FAILED_BROADCASTS.set(0L);
    }

    /**
     * 移除所有监听器（仅用于测试）。
     */
    public static void clearListenersForTest() {
        LISTENERS.clear();
    }
}