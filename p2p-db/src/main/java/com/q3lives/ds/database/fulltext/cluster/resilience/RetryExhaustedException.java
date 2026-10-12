package com.q3lives.ds.database.fulltext.cluster.resilience;

/**
 * 重试次数耗尽后抛出的异常。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 当 {@code RetryGuard.maxRetries} 次重试均失败时，包装最后一次失败异常抛出。
 *
 * <p>特性：
 * <ul>
 *   <li><b>计入 CircuitBreaker 失败</b>：业务调用确实失败，应触发熔断判定</li>
 *   <li>不可重试：达到最大重试次数，必须放弃</li>
 *   <li>携带原异常链：调用方可读取 {@link #getCause()} 定位根因</li>
 * </ul>
 */
public class RetryExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 已尝试的总次数（含首次调用） */
    private final int attempts;

    public RetryExhaustedException(String message, int attempts, Throwable cause) {
        super(message, cause);
        this.attempts = attempts;
    }

    public int getAttempts() {
        return attempts;
    }
}
