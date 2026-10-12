package com.q3lives.ds.database.fulltext.cluster.resilience;

/**
 * 不可变 4 段配置（timeout / retry / circuitBreaker / rateLimiter）。
 *
 * <p>P32 OQ1 合查链路熔断容错/自动重试 MVP（2026-10-11）。
 * 通过 {@link Builder} 创建，4 段必填，任一缺 → IAE。
 *
 * <p>默认值（来自 P32 spec REV1 §1）：
 * <ul>
 *   <li>timeout: 30_000 ms</li>
 *   <li>retry: maxRetries=2, baseDelayMs=100</li>
 *   <li>circuitBreaker: failureRateThreshold=0.5, slidingWindowSize=50, openDurationMs=5_000</li>
 *   <li>rateLimiter: qps=100, burst=200</li>
 * </ul>
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience 子包）。
 */
public final class ResilienceConfig {

    /** 单次合查超时（ms），≥1 */
    public final long timeoutMs;
    /** 最大重试次数（不含首次调用），≥0 */
    public final int maxRetries;
    /** 重试基础退避（ms），指数退避 base * 2^attempt，≥1 */
    public final long baseDelayMs;
    /** 失败率阈值（0~1），达到后熔断 OPEN */
    public final double failureRateThreshold;
    /** 滑动窗口大小（请求数），≥1 */
    public final int slidingWindowSize;
    /** OPEN 状态持续时间（ms），≥1 */
    public final long openDurationMs;
    /** 令牌桶补充速率（QPS），≥1 */
    public final int qps;
    /** 令牌桶最大容量（burst），≥1 */
    public final int burst;

    private ResilienceConfig(Builder b) {
        this.timeoutMs = b.timeoutMs;
        this.maxRetries = b.maxRetries;
        this.baseDelayMs = b.baseDelayMs;
        this.failureRateThreshold = b.failureRateThreshold;
        this.slidingWindowSize = b.slidingWindowSize;
        this.openDurationMs = b.openDurationMs;
        this.qps = b.qps;
        this.burst = b.burst;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 全部 8 字段的默认配置（P32 spec §1） */
    public static ResilienceConfig defaults() {
        return new Builder()
                .timeoutMs(30_000)
                .maxRetries(2)
                .baseDelayMs(100)
                .failureRateThreshold(0.5)
                .slidingWindowSize(50)
                .openDurationMs(5_000)
                .qps(100)
                .burst(200)
                .build();
    }

    public long getTimeoutMs() { return timeoutMs; }
    public int getMaxRetries() { return maxRetries; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public double getFailureRateThreshold() { return failureRateThreshold; }
    public int getSlidingWindowSize() { return slidingWindowSize; }
    public long getOpenDurationMs() { return openDurationMs; }
    public int getQps() { return qps; }
    public int getBurst() { return burst; }

    /**
     * Builder 模式构造器，4 段必填字段全部 IAE 校验。
     */
    public static final class Builder {
        private long timeoutMs = -1;
        private int maxRetries = -1;
        private long baseDelayMs = -1;
        private double failureRateThreshold = -1.0;
        private int slidingWindowSize = -1;
        private long openDurationMs = -1;
        private int qps = -1;
        private int burst = -1;

        public Builder timeoutMs(long v) { this.timeoutMs = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder baseDelayMs(long v) { this.baseDelayMs = v; return this; }
        public Builder failureRateThreshold(double v) { this.failureRateThreshold = v; return this; }
        public Builder slidingWindowSize(int v) { this.slidingWindowSize = v; return this; }
        public Builder openDurationMs(long v) { this.openDurationMs = v; return this; }
        public Builder qps(int v) { this.qps = v; return this; }
        public Builder burst(int v) { this.burst = v; return this; }

        public ResilienceConfig build() {
            if (timeoutMs < 1) throw new IllegalArgumentException("timeoutMs must be >= 1, got " + timeoutMs);
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
            if (baseDelayMs < 1) throw new IllegalArgumentException("baseDelayMs must be >= 1, got " + baseDelayMs);
            if (failureRateThreshold < 0.0 || failureRateThreshold > 1.0)
                throw new IllegalArgumentException("failureRateThreshold must be in [0,1], got " + failureRateThreshold);
            if (slidingWindowSize < 1) throw new IllegalArgumentException("slidingWindowSize must be >= 1, got " + slidingWindowSize);
            if (openDurationMs < 1) throw new IllegalArgumentException("openDurationMs must be >= 1, got " + openDurationMs);
            if (qps < 1) throw new IllegalArgumentException("qps must be >= 1, got " + qps);
            if (burst < 1) throw new IllegalArgumentException("burst must be >= 1, got " + burst);
            return new ResilienceConfig(this);
        }
    }
}
