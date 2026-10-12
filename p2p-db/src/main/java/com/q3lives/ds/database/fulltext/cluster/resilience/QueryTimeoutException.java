package com.q3lives.ds.database.fulltext.cluster.resilience;

/**
 * 单次合查调用超时后抛出的异常。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 当 {@code TimeoutGuard} 通过 {@link java.util.concurrent.Future#get(long, java.util.concurrent.TimeUnit)}
 * 在 {@code timeoutMs} 内未完成时，强制取消任务并抛此异常。
 *
 * <p>特性：
 * <ul>
 *   <li><b>计入 CircuitBreaker 失败</b>：超时即业务失败</li>
 *   <li>可重试：调用方在 backoff 后可重试（受 RetryGuard 控制）</li>
 * </ul>
 */
public class QueryTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 超时阈值（ms） */
    private final long timeoutMs;
    /** 已等待时间（ms，触发超时瞬间） */
    private final long elapsedMs;

    public QueryTimeoutException(String message, long timeoutMs, long elapsedMs) {
        super(message);
        this.timeoutMs = timeoutMs;
        this.elapsedMs = elapsedMs;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }
}
