package com.q3lives.ds.database.fulltext.cluster.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 令牌桶限流守卫。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 按 {@code qps} 速率补充令牌，桶最大 {@code burst} 个；令牌耗尽时直接拒绝并抛 {@link RateLimitExceededException}。
 *
 * <p>状态可观测：{@link #getAvailableTokens()}。
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public class RateLimiterGuard {

    private final int qps;
    private final int burst;
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillNanos;
    private final Object refillLock = new Object();

    public RateLimiterGuard(int qps, int burst) {
        if (qps < 1) throw new IllegalArgumentException("qps must be >= 1, got " + qps);
        if (burst < 1) throw new IllegalArgumentException("burst must be >= 1, got " + burst);
        this.qps = qps;
        this.burst = burst;
        this.availableTokens = new AtomicLong(burst);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    public int getQps() { return qps; }
    public int getBurst() { return burst; }

    public long getAvailableTokens() {
        refill();
        return Math.max(0, availableTokens.get());
    }

    private void refill() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsedNs = now - last;
        if (elapsedNs <= 0) return;
        long tokensToAdd = (elapsedNs * qps) / 1_000_000_000L;
        if (tokensToAdd <= 0) return;
        synchronized (refillLock) {
            long cur = availableTokens.get();
            long newVal = Math.min(burst, cur + tokensToAdd);
            availableTokens.set(newVal);
            lastRefillNanos.set(now);
        }
    }

    public <T> T wrap(Callable<T> call) throws Exception {
        if (call == null) throw new NullPointerException("call must not be null");
        refill();
        long cur = availableTokens.get();
        while (cur > 0) {
            if (availableTokens.compareAndSet(cur, cur - 1)) {
                return call.call();
            }
            cur = availableTokens.get();
        }
        throw new RateLimitExceededException(
                "rate limit exceeded: qps=" + qps + " burst=" + burst, burst - cur, qps);
    }
}
