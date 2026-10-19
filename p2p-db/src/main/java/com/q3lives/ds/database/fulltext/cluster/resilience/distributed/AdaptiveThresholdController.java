package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

import java.util.Objects;

import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig;

/**
 * 自适应阈值调参控制器（P36 OQ1）。
 *
 * <p>AimdLike 算法（AIMD 变种）：
 * <ul>
 *   <li>失败率 ↑（≥ 0.7） → 阈值 × 0.9（multiplicative decrease），保 min ≥ adaptiveMinFailureRate</li>
 *   <li>失败率 ↓（≤ 0.2） → 阈值 + 0.05（additive increase），保 max ≤ 1.0</li>
 *   <li>0.2 < 失败率 < 0.7 → 阈值保持不变（稳态区）</li>
 * </ul>
 *
 * <p>openDurationMs 同理（min = 1000ms，max = adaptiveMaxOpenDurationMs）。
 *
 * <p>硬约束：R7（threshold ∈ [0, 1]） + currentThreshold ∈ [0, 1] + recentFailureRates 非空 + config 非空。
 *
 * <p>红线：NG-3 零依赖，ZeroLeak3=3（仅在 resilience/distributed/ 子包）。
 *
 * @author SP3.1 Implement Agent
 * @since P36 OQ1 Distributed Circuit Breaker MVP
 */
public final class AdaptiveThresholdController {

    private AdaptiveThresholdController() {
    }

    /** 失败率高阈值（≥ 此值触发 multiplicative decrease） */
    public static final double HIGH_FAILURE_RATE = 0.7;
    /** 失败率低阈值（≤ 此值触发 additive increase） */
    public static final double LOW_FAILURE_RATE = 0.2;
    /** 阈值减小系数（multiplicative） */
    public static final double DECREASE_FACTOR = 0.9;
    /** 阈值增加步长（additive） */
    public static final double INCREASE_STEP = 0.05;
    /** openDuration 最小值（ms） */
    public static final long MIN_OPEN_DURATION_MS = 1_000L;
    /** openDuration 减小系数（multiplicative） */
    public static final double OPEN_DECREASE_FACTOR = 0.8;
    /** openDuration 增加步长（additive，ms） */
    public static final long OPEN_INCREASE_STEP = 1_000L;

    /**
     * 调参阈值（FailureRate → threshold）。
     *
     * @param currentThreshold 当前阈值 ∈ [0, 1]
     * @param recentFailureRates 最近失败率样本数组（非空）
     * @param config DistributedResilienceConfig（含 adaptiveMinFailureRate + adaptiveMaxOpenDurationMs）
     * @return 调参后阈值 ∈ [adaptiveMinFailureRate, 1.0]
     */
    public static double adjustThreshold(double currentThreshold,
                                          double[] recentFailureRates,
                                          DistributedResilienceConfig config) {
        if (currentThreshold < 0.0 || currentThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "currentThreshold must be in [0, 1], got " + currentThreshold);
        }
        if (recentFailureRates == null || recentFailureRates.length == 0) {
            throw new IllegalArgumentException("recentFailureRates cannot be null or empty");
        }
        Objects.requireNonNull(config, "config cannot be null");
        double avg = avg(recentFailureRates);
        double minThreshold = config.adaptiveMinFailureRate;
        if (avg >= HIGH_FAILURE_RATE) {
            return Math.max(minThreshold, currentThreshold * DECREASE_FACTOR);
        } else if (avg <= LOW_FAILURE_RATE) {
            return Math.min(1.0, currentThreshold + INCREASE_STEP);
        }
        return currentThreshold;
    }

    /**
     * 调参 openDuration（FailureRate → openDurationMs）。
     *
     * @param currentOpenDurationMs 当前 openDuration（ms，≥ 1）
     * @param recentFailureRates 最近失败率样本数组（非空）
     * @param config DistributedResilienceConfig（含 adaptiveMaxOpenDurationMs）
     * @return 调参后 openDuration ∈ [MIN_OPEN_DURATION_MS, adaptiveMaxOpenDurationMs]
     */
    public static long adjustOpenDuration(long currentOpenDurationMs,
                                            double[] recentFailureRates,
                                            DistributedResilienceConfig config) {
        if (currentOpenDurationMs < 1) {
            throw new IllegalArgumentException(
                    "currentOpenDurationMs must be >= 1, got " + currentOpenDurationMs);
        }
        if (recentFailureRates == null || recentFailureRates.length == 0) {
            throw new IllegalArgumentException("recentFailureRates cannot be null or empty");
        }
        Objects.requireNonNull(config, "config cannot be null");
        double avg = avg(recentFailureRates);
        long maxDuration = config.adaptiveMaxOpenDurationMs;
        if (avg >= HIGH_FAILURE_RATE) {
            return Math.min(maxDuration, (long) (currentOpenDurationMs * OPEN_DECREASE_FACTOR));
        } else if (avg <= LOW_FAILURE_RATE) {
            return Math.max(MIN_OPEN_DURATION_MS, currentOpenDurationMs + OPEN_INCREASE_STEP);
        }
        return currentOpenDurationMs;
    }

    /**
     * 计算样本均值（≥ 0 且 ≤ 1 边界检查）。IAE 守卫：null/empty 数组。
     */
    private static double avg(double[] samples) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples cannot be null or empty");
        }
        double sum = 0.0;
        for (double s : samples) {
            if (s < 0.0 || s > 1.0) {
                throw new IllegalArgumentException("sample value must be in [0, 1], got " + s);
            }
            sum += s;
        }
        return sum / samples.length;
    }
}