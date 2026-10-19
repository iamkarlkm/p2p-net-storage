package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;

/**
 * P36 DistributedResilienceConfig：P32 ResilienceConfig 8 字段保留 + append-only 4
 * 字段。
 *
 * <p>
 * 原 P32 8 字段：timeoutMs / maxRetries / baseDelayMs / failureRateThreshold /
 * slidingWindowSize / openDurationMs / qps / burst（1 字不改 0 删除，R4 Facade 单调不减）。
 *
 * <p>
 * P36 append-only 4 字段：
 * <ul>
 * <li>distributedKpiSampleIntervalMs：跨节点 KPI 采样间隔（≥ 1000ms，R8 广播风暴防护）</li>
 * <li>distributedQuorum：跨节点投票 quorum（1 ≤ x ≤ 1024，R9 quorum 安全）</li>
 * <li>adaptiveMinFailureRate：Adaptive 阈值下限（[0, 1]，R7 防跌破 0）</li>
 * <li>adaptiveMaxOpenDurationMs：Adaptive openDuration 上限（≥ 1000ms，R7
 * 恢复期防过短）</li>
 * </ul>
 *
 * <p>
 * 硬约束：4 IAE 守卫 + Builder 严格校验。
 *
 * <p>
 * 红线：NG-3 零依赖，ZeroLeak3=3（仅在 resilience/distributed/ 子包）。
 *
 * @author SP3.1 Implement Agent
 * @since P36 OQ1 Distributed Circuit Breaker MVP
 */
public final class DistributedResilienceConfig {

    // ---- P32 8 字段保留 (1 字不改) ----
    public final long timeoutMs;
    public final int maxRetries;
    public final long baseDelayMs;
    public final double failureRateThreshold;
    public final int slidingWindowSize;
    public final long openDurationMs;
    public final int qps;
    public final int burst;

    // ---- P36 append-only 4 字段 ----
    public final long distributedKpiSampleIntervalMs;
    public final int distributedQuorum;
    public final double adaptiveMinFailureRate;
    public final long adaptiveMaxOpenDurationMs;

    public DistributedResilienceConfig(Builder b) {
        // P32 8 字段校验（与 P32 ResilienceConfig.build 完全等价，1 字不改）
        if (b.timeoutMs < 1) {
            throw new IllegalArgumentException("timeoutMs must be >= 1, got " + b.timeoutMs);
        }
        if (b.maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + b.maxRetries);
        }
        if (b.baseDelayMs < 1) {
            throw new IllegalArgumentException("baseDelayMs must be >= 1, got " + b.baseDelayMs);
        }
        if (b.failureRateThreshold < 0.0 || b.failureRateThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "failureRateThreshold must be in [0, 1], got " + b.failureRateThreshold);
        }
        if (b.slidingWindowSize < 1) {
            throw new IllegalArgumentException(
                    "slidingWindowSize must be >= 1, got " + b.slidingWindowSize);
        }
        if (b.openDurationMs < 1) {
            throw new IllegalArgumentException(
                    "openDurationMs must be >= 1, got " + b.openDurationMs);
        }
        if (b.qps < 1) {
            throw new IllegalArgumentException("qps must be >= 1, got " + b.qps);
        }
        if (b.burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1, got " + b.burst);
        }
        // P36 4 字段校验
        if (b.distributedKpiSampleIntervalMs < 1000) {
            throw new IllegalArgumentException(
                    "distributedKpiSampleIntervalMs must be >= 1000, got "
                    + b.distributedKpiSampleIntervalMs);
        }
        if (b.distributedQuorum < 1 || b.distributedQuorum > 1024) {
            throw new IllegalArgumentException(
                    "distributedQuorum must be in [1, 1024], got " + b.distributedQuorum);
        }
        if (b.adaptiveMinFailureRate < 0.0 || b.adaptiveMinFailureRate > 1.0) {
            throw new IllegalArgumentException(
                    "adaptiveMinFailureRate must be in [0, 1], got " + b.adaptiveMinFailureRate);
        }
        if (b.adaptiveMaxOpenDurationMs < 1000) {
            throw new IllegalArgumentException(
                    "adaptiveMaxOpenDurationMs must be >= 1000, got "
                    + b.adaptiveMaxOpenDurationMs);
        }
        this.timeoutMs = b.timeoutMs;
        this.maxRetries = b.maxRetries;
        this.baseDelayMs = b.baseDelayMs;
        this.failureRateThreshold = b.failureRateThreshold;
        this.slidingWindowSize = b.slidingWindowSize;
        this.openDurationMs = b.openDurationMs;
        this.qps = b.qps;
        this.burst = b.burst;
        this.distributedKpiSampleIntervalMs = b.distributedKpiSampleIntervalMs;
        this.distributedQuorum = b.distributedQuorum;
        this.adaptiveMinFailureRate = b.adaptiveMinFailureRate;
        this.adaptiveMaxOpenDurationMs = b.adaptiveMaxOpenDurationMs;
    }

    /**
     * 从 P32 ResilienceConfig 构造（保留 P32 8 字段），P36 4 字段取默认。
     */
    public static DistributedResilienceConfig fromBase(ResilienceConfig base) {
        if (base == null) {
            throw new IllegalArgumentException("base ResilienceConfig cannot be null");
        }
        return new Builder()
                .timeoutMs(base.timeoutMs)
                .maxRetries(base.maxRetries)
                .baseDelayMs(base.baseDelayMs)
                .failureRateThreshold(base.failureRateThreshold)
                .slidingWindowSize(base.slidingWindowSize)
                .openDurationMs(base.openDurationMs)
                .qps(base.qps)
                .burst(base.burst)
                .distributedKpiSampleIntervalMs(5_000L)
                .distributedQuorum(1)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
    }

    /**
     * 全部 12 字段默认（P32 default + P36 4 字段默认）。
     */
    public static DistributedResilienceConfig defaults() {
        return new Builder()
                .timeoutMs(30_000L)
                .maxRetries(2)
                .baseDelayMs(100L)
                .failureRateThreshold(0.5)
                .slidingWindowSize(50)
                .openDurationMs(5_000L)
                .qps(100)
                .burst(200)
                .distributedKpiSampleIntervalMs(5_000L)
                .distributedQuorum(1)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public double getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public long getOpenDurationMs() {
        return openDurationMs;
    }

    public int getQps() {
        return qps;
    }

    public int getBurst() {
        return burst;
    }

    public long getDistributedKpiSampleIntervalMs() {
        return distributedKpiSampleIntervalMs;
    }

    public int getDistributedQuorum() {
        return distributedQuorum;
    }

    public double getAdaptiveMinFailureRate() {
        return adaptiveMinFailureRate;
    }

    public long getAdaptiveMaxOpenDurationMs() {
        return adaptiveMaxOpenDurationMs;
    }

    /**
     * Builder 模式构造器，12 字段必填字段全部 IAE 校验。
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
        private long distributedKpiSampleIntervalMs = -1;
        private int distributedQuorum = -1;
        private double adaptiveMinFailureRate = -1.0;
        private long adaptiveMaxOpenDurationMs = -1;

        public Builder timeoutMs(long v) {
            this.timeoutMs = v;
            return this;
        }

        public Builder maxRetries(int v) {
            this.maxRetries = v;
            return this;
        }

        public Builder baseDelayMs(long v) {
            this.baseDelayMs = v;
            return this;
        }

        public Builder failureRateThreshold(double v) {
            this.failureRateThreshold = v;
            return this;
        }

        public Builder slidingWindowSize(int v) {
            this.slidingWindowSize = v;
            return this;
        }

        public Builder openDurationMs(long v) {
            this.openDurationMs = v;
            return this;
        }

        public Builder qps(int v) {
            this.qps = v;
            return this;
        }

        public Builder burst(int v) {
            this.burst = v;
            return this;
        }

        public Builder distributedKpiSampleIntervalMs(long v) {
            this.distributedKpiSampleIntervalMs = v;
            return this;
        }

        public Builder distributedQuorum(int v) {
            this.distributedQuorum = v;
            return this;
        }

        public Builder adaptiveMinFailureRate(double v) {
            this.adaptiveMinFailureRate = v;
            return this;
        }

        public Builder adaptiveMaxOpenDurationMs(long v) {
            this.adaptiveMaxOpenDurationMs = v;
            return this;
        }

        public DistributedResilienceConfig build() {
            return new DistributedResilienceConfig(this);
        }
    }
}
