package com.q3lives.ds.database.fulltext.cluster.resilience;

/**
 * 熔断器 OPEN 状态时拒绝调用抛出的异常。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 当 CircuitBreaker 滑动窗口失败率超阈值进入 OPEN 状态后，
 * 所有调用在 {@code openDurationMs} 窗口内会被快速失败拒绝。
 *
 * <p>特性：
 * <ul>
 *   <li><b>不计入 CircuitBreaker 失败</b>：已熔断状态下不应重复计入失败率</li>
 *   <li>可恢复：等待 {@code openDurationMs} 后熔断器转 HALF_OPEN 探测</li>
 *   <li>不可重试：当前请求必须放弃</li>
 * </ul>
 */
public class CircuitOpenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 熔断器打开时刻（System.nanoTime()） */
    private final long openedAtNanos;
    /** OPEN 状态持续时间（ms） */
    private final long openDurationMs;
    /** 当前滑动窗口失败率（0~1） */
    private final double failureRate;

    public CircuitOpenException(String message, long openedAtNanos, long openDurationMs, double failureRate) {
        super(message);
        this.openedAtNanos = openedAtNanos;
        this.openDurationMs = openDurationMs;
        this.failureRate = failureRate;
    }

    public long getOpenedAtNanos() {
        return openedAtNanos;
    }

    public long getOpenDurationMs() {
        return openDurationMs;
    }

    public double getFailureRate() {
        return failureRate;
    }
}
