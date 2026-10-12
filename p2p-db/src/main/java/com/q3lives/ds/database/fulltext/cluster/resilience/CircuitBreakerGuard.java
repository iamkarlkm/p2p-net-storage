package com.q3lives.ds.database.fulltext.cluster.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 滑动窗口失败率熔断器守卫。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 三态机：CLOSED → OPEN（失败率超阈值）→ HALF_OPEN（openDurationMs 后）→ CLOSED / OPEN。
 *
 * <p>不计入失败的情况（保持熔断器状态稳定）：
 * <ul>
 *   <li>{@link RateLimitExceededException}：业务正常限流</li>
 *   <li>{@link CircuitOpenException}：已熔断状态下的快速失败</li>
 * </ul>
 *
 * <p>状态可观测：{@link #getState()}。
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public class CircuitBreakerGuard {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final double failureRateThreshold;
    private final int slidingWindowSize;
    private final long openDurationMs;

    private final boolean[] recentFailures;
    private int recentIdx = 0;
    private int recentCount = 0;
    private int recentFailureCount = 0;

    private volatile State state = State.CLOSED;
    private final AtomicLong openedAtNanos = new AtomicLong(0L);

    public CircuitBreakerGuard(double failureRateThreshold, int slidingWindowSize, long openDurationMs) {
        if (failureRateThreshold < 0.0 || failureRateThreshold > 1.0)
            throw new IllegalArgumentException("failureRateThreshold must be in [0,1], got " + failureRateThreshold);
        if (slidingWindowSize < 1)
            throw new IllegalArgumentException("slidingWindowSize must be >= 1, got " + slidingWindowSize);
        if (openDurationMs < 1)
            throw new IllegalArgumentException("openDurationMs must be >= 1, got " + openDurationMs);
        this.failureRateThreshold = failureRateThreshold;
        this.slidingWindowSize = slidingWindowSize;
        this.openDurationMs = openDurationMs;
        this.recentFailures = new boolean[slidingWindowSize];
    }

    public State getState() { return state; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public int getSlidingWindowSize() { return slidingWindowSize; }
    public long getOpenDurationMs() { return openDurationMs; }

    public synchronized <T> T wrap(Callable<T> call) throws Exception {
        if (call == null) throw new NullPointerException("call must not be null");

        if (state == State.OPEN) {
            long elapsedMs = (System.nanoTime() - openedAtNanos.get()) / 1_000_000L;
            if (elapsedMs < openDurationMs) {
                throw new CircuitOpenException(
                        "circuit OPEN", openedAtNanos.get(), openDurationMs, currentFailureRate());
            }
            state = State.HALF_OPEN;
        }

        try {
            T result = call.call();
            onSuccess();
            return result;
        } catch (Throwable t) {
            onFailure(t);
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
    }

    private void onSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            recentIdx = 0;
            recentCount = 0;
            recentFailureCount = 0;
            return;
        }
        recordOutcome(false);
    }

    private void onFailure(Throwable t) {
        if (t instanceof RateLimitExceededException || t instanceof CircuitOpenException) {
            return; // 不计入
        }
        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            openedAtNanos.set(System.nanoTime());
            return;
        }
        recordOutcome(true);
        double rate = currentFailureRate();
        if (state == State.CLOSED && recentCount >= slidingWindowSize && rate >= failureRateThreshold) {
            state = State.OPEN;
            openedAtNanos.set(System.nanoTime());
        }
    }

    private void recordOutcome(boolean failure) {
        if (recentCount == slidingWindowSize) {
            if (recentFailures[recentIdx]) recentFailureCount--;
            recentCount--;
        }
        recentFailures[recentIdx] = failure;
        if (failure) recentFailureCount++;
        recentIdx = (recentIdx + 1) % slidingWindowSize;
        recentCount++;
    }

    public double currentFailureRate() {
        if (recentCount == 0) return 0.0;
        return (double) recentFailureCount / recentCount;
    }
}
