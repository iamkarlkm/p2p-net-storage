package com.q3lives.ds.database.fulltext.cluster.resilience;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次合查调用超时守卫。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 通过 {@link Future#get(long, TimeUnit)} 强制中断：在 {@code timeoutMs} 内未完成则取消并抛 {@link QueryTimeoutException}。
 *
 * <p>状态可观测：{@link #getActiveCallCount()}（当前活跃调用数）。
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public class TimeoutGuard {

    private final long timeoutMs;
    private final ExecutorService executor;
    private final AtomicLong activeCallCount = new AtomicLong(0);

    public TimeoutGuard(long timeoutMs) {
        if (timeoutMs < 1) throw new IllegalArgumentException("timeoutMs must be >= 1, got " + timeoutMs);
        this.timeoutMs = timeoutMs;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("p32-timeout-"));
    }

    public long getTimeoutMs() { return timeoutMs; }
    public long getActiveCallCount() { return activeCallCount.get(); }

    public <T> T wrap(Callable<T> call) throws Exception {
        activeCallCount.incrementAndGet();
        try {
            Future<T> f = executor.submit(call);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                f.cancel(true);
                throw new QueryTimeoutException(
                        "call exceeded timeoutMs=" + timeoutMs,
                        timeoutMs, System.currentTimeMillis() % timeoutMs);
            }
        } finally {
            activeCallCount.decrementAndGet();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        DaemonThreadFactory(String prefix) { this.prefix = prefix; }
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + System.nanoTime());
            t.setDaemon(true);
            return t;
        }
    }
}
