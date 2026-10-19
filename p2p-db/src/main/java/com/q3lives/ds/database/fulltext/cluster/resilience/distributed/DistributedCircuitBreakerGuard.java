package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitOpenException;
import com.q3lives.ds.database.fulltext.cluster.resilience.RateLimitExceededException;

/**
 * 分布式熔断器守卫（P36 OQ1）。
 *
 * <p>
 * 封装 P32 {@link CircuitBreakerGuard}：每个 serverId 独立维护 CB 状态。 状态变更时通过
 * {@link ClusterStateBroadcaster} 跨节点广播。
 *
 * <p>
 * 不计入失败的情况（同 P32 CircuitBreakerGuard.onFailure 不计入 RateLimit/CircuitOpen）。
 *
 * <p>
 * 硬约束：5 IAE 守卫 + Adaptive 调参 0 越界。
 *
 * <p>
 * 红线：NG-3 零依赖，ZeroLeak3=3（仅在 resilience/distributed/ 子包）。
 *
 * @author SP3.1 Implement Agent
 * @since P36 OQ1 Distributed Circuit Breaker MVP
 */
public final class DistributedCircuitBreakerGuard {

    private static final ConcurrentHashMap<Integer, CircuitBreakerGuard> GUARDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, DistributedCircuitBreakerState> STATES
            = new ConcurrentHashMap<>();
    private static final AtomicLong OPEN_TRANSITIONS = new AtomicLong(0L);

    private DistributedCircuitBreakerGuard() {
    }

    /**
     * 获取或创建 serverId 对应的 P32 CircuitBreakerGuard。
     */
    public static CircuitBreakerGuard getOrCreateGuard(int serverId, DistributedResilienceConfig config) {
        if (serverId < 0 || serverId > DistributedCircuitBreakerState.MAX_SERVER_ID) {
            throw new IllegalArgumentException(
                    "serverId must be in [0, " + DistributedCircuitBreakerState.MAX_SERVER_ID + "], got " + serverId);
        }
        Objects.requireNonNull(config, "config cannot be null");
        return GUARDS.computeIfAbsent(serverId, sid -> new CircuitBreakerGuard(
                config.failureRateThreshold, config.slidingWindowSize, config.openDurationMs));
    }

    /**
     * 获取或创建 serverId 对应的状态快照。
     */
    public static DistributedCircuitBreakerState getOrCreateState(int serverId) {
        return STATES.computeIfAbsent(serverId, sid -> DistributedCircuitBreakerState.closedDefault(sid));
    }

    /**
     * 包装 callable 走分布式 CB 守卫链。
     *
     * <p>
     * 流程：guard.wrap(call) → onSuccess/onFailure → 状态变更触发 broadcast。
     */
    public static <T> T wrap(int serverId, Callable<T> call,
            DistributedResilienceConfig config,
            DsServerDirectory directory) throws Exception {
        if (call == null) {
            throw new IllegalArgumentException("call cannot be null");
        }
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(directory, "directory cannot be null");
        CircuitBreakerGuard guard = getOrCreateGuard(serverId, config);
        CircuitBreakerGuard.State oldState = guard.getState();
        try {
            T result = guard.wrap(call);
            CircuitBreakerGuard.State newState = guard.getState();
            if (oldState != newState) {
                OPEN_TRANSITIONS.incrementAndGet();
                onStateChanged(serverId, newState, oldState, config, directory);
            }
            return result;
        } catch (CircuitOpenException coe) {
            // 不计入失败，但状态变更要广播（如 OPEN → HALF_OPEN）
            CircuitBreakerGuard.State newState = guard.getState();
            if (oldState != newState) {
                OPEN_TRANSITIONS.incrementAndGet();
                onStateChanged(serverId, newState, oldState, config, directory);
            }
            throw coe;
        } catch (RateLimitExceededException rle) {
            // 不计入失败，也不广播
            throw rle;
        } catch (Exception e) {
            // 业务异常后状态可能已变更（如 CLOSED → OPEN），需广播
            CircuitBreakerGuard.State newState = guard.getState();
            if (oldState != newState) {
                OPEN_TRANSITIONS.incrementAndGet();
                onStateChanged(serverId, newState, oldState, config, directory);
            }
            throw e;
        }
    }

    /**
     * 状态变更回调（仅在 CLOSED ↔ OPEN ↔ HALF_OPEN 切换时触发）。
     */
    public static void onStateChanged(int serverId, CircuitBreakerGuard.State newState,
            CircuitBreakerGuard.State oldState,
            DistributedResilienceConfig config,
            DsServerDirectory directory) {
        CircuitBreakerGuard guard = getOrCreateGuard(serverId, config);
        DistributedCircuitBreakerState snap = new DistributedCircuitBreakerState(
                serverId, newState,
                newState == CircuitBreakerGuard.State.OPEN ? System.nanoTime() : 0L,
                guard.currentFailureRate(),
                config.failureRateThreshold,
                config.openDurationMs,
                System.currentTimeMillis());
        STATES.put(serverId, snap);
        // 跨节点广播（失败本地熔断）
        try {
            ClusterStateBroadcaster.broadcast(serverId, snap, directory, config);
        } catch (RuntimeException ignore) {
            // 本地熔断保护：广播失败不传播
        }
    }

    public static long openTransitions() {
        return OPEN_TRANSITIONS.get();
    }

    public static int guardCount() {
        return GUARDS.size();
    }

    /**
     * 重置全局状态（仅用于测试）。
     */
    public static void resetAllForTest() {
        GUARDS.clear();
        STATES.clear();
        OPEN_TRANSITIONS.set(0L);
    }
}
