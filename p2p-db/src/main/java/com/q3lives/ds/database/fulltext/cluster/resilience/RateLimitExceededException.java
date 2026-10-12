package com.q3lives.ds.database.fulltext.cluster.resilience;

/**
 * 限流守卫拒绝时抛出的异常。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 用于告知调用方：当前 QPS 超出配置上限，请求被直接拒绝。
 *
 * <p>特性：
 * <ul>
 *   <li><b>不计入 CircuitBreaker 失败</b>：限流是业务正常行为，不应触发熔断</li>
 *   <li>可重试：调用方可在 {@code baseDelayMs} 后重试</li>
 *   <li>不可恢复：当前请求必须放弃</li>
 * </ul>
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public class RateLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 触发限流时的已用令牌数（用于诊断） */
    private final long consumedTokens;
    /** 配置的 QPS 上限 */
    private final int qpsLimit;

    public RateLimitExceededException(String message, long consumedTokens, int qpsLimit) {
        super(message);
        this.consumedTokens = consumedTokens;
        this.qpsLimit = qpsLimit;
    }

    public long getConsumedTokens() {
        return consumedTokens;
    }

    public int getQpsLimit() {
        return qpsLimit;
    }
}
