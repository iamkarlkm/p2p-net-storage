package com.q3lives.ds.database.fulltext.cluster.resilience;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * 指数退避重试守卫。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 区分可重试异常（默认 {@link java.io.IOException} / {@link java.util.concurrent.TimeoutException}）与不可重试异常（其他）。
 * 退避公式：{@code baseDelayMs * 2^attempt}。
 *
 * <p>可重试异常集合构造时可自定义；构造时 NPE 校验：callable 与 retryOn 均非 null。
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public class RetryGuard {

    private final int maxRetries;
    private final long baseDelayMs;
    private final Set<Class<? extends Throwable>> retryOn;

    public RetryGuard(int maxRetries, long baseDelayMs, Set<Class<? extends Throwable>> retryOn) {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        if (baseDelayMs < 1) throw new IllegalArgumentException("baseDelayMs must be >= 1, got " + baseDelayMs);
        if (retryOn == null) throw new NullPointerException("retryOn must not be null");
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.retryOn = Collections.unmodifiableSet(new HashSet<>(retryOn));
    }

    public static Set<Class<? extends Throwable>> defaultRetryOn() {
        return new HashSet<>(Arrays.asList(
                java.io.IOException.class,
                java.util.concurrent.TimeoutException.class,
                QueryTimeoutException.class));
    }

    public int getMaxRetries() { return maxRetries; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public Set<Class<? extends Throwable>> getRetryOn() { return retryOn; }

    public <T> T wrap(Callable<T> call) throws Exception {
        if (call == null) throw new NullPointerException("call must not be null");
        Throwable last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return call.call();
            } catch (Throwable t) {
                last = t;
                if (!isRetryable(t) || attempt >= maxRetries) {
                    if (attempt > 0) {
                        throw new RetryExhaustedException(
                                "retry exhausted after " + (attempt + 1) + " attempts", attempt + 1, t);
                    }
                    if (t instanceof Exception) throw (Exception) t;
                    throw new RuntimeException(t);
                }
                long delay = baseDelayMs * (1L << attempt);
                Thread.sleep(delay);
            }
        }
        if (last instanceof Exception) throw (Exception) last;
        throw new RuntimeException(last);
    }

    private boolean isRetryable(Throwable t) {
        for (Class<? extends Throwable> c : retryOn) {
            if (c.isInstance(t)) return true;
        }
        return false;
    }
}
