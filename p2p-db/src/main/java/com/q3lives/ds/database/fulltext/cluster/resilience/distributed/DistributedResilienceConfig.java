package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

/**
 * 不可变 POJO 副本（暂用，完整 Builder 在 P36 T2 实装）。
 *
 * <p>P36 T1 骨架版：保留 4 个 P36 字段 + 默认值，T2 阶段会替换为完整 builder 模式。
 */
public final class DistributedResilienceConfig {

    public final long distributedKpiSampleIntervalMs;
    public final int distributedQuorum;
    public final double adaptiveMinFailureRate;
    public final long adaptiveMaxOpenDurationMs;

    public DistributedResilienceConfig(long distributedKpiSampleIntervalMs,
                                       int distributedQuorum,
                                       double adaptiveMinFailureRate,
                                       long adaptiveMaxOpenDurationMs) {
        if (distributedKpiSampleIntervalMs < 1000) {
            throw new IllegalArgumentException(
                    "distributedKpiSampleIntervalMs must be >= 1000, got " + distributedKpiSampleIntervalMs);
        }
        if (distributedQuorum < 1 || distributedQuorum > 1024) {
            throw new IllegalArgumentException(
                    "distributedQuorum must be in [1, 1024], got " + distributedQuorum);
        }
        if (adaptiveMinFailureRate < 0.0 || adaptiveMinFailureRate > 1.0) {
            throw new IllegalArgumentException(
                    "adaptiveMinFailureRate must be in [0, 1], got " + adaptiveMinFailureRate);
        }
        if (adaptiveMaxOpenDurationMs < 1000) {
            throw new IllegalArgumentException(
                    "adaptiveMaxOpenDurationMs must be >= 1000, got " + adaptiveMaxOpenDurationMs);
        }
        this.distributedKpiSampleIntervalMs = distributedKpiSampleIntervalMs;
        this.distributedQuorum = distributedQuorum;
        this.adaptiveMinFailureRate = adaptiveMinFailureRate;
        this.adaptiveMaxOpenDurationMs = adaptiveMaxOpenDurationMs;
    }

    public static DistributedResilienceConfig defaults() {
        return new DistributedResilienceConfig(5_000L, 1, 0.1, 60_000L);
    }
}