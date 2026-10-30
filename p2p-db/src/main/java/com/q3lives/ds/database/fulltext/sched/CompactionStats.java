package com.q3lives.ds.database.fulltext.sched;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P47 compaction 调度统计（可观测计数）。
 *
 * <p>
 * 累计 {@code CompactionScheduler} 的一轮轮 tick 结果，供调用方做健康度观测与限速/自适应效果评估。
 * 全部计数用 {@link AtomicLong}，可安全跨线程读取；写入口为包内外的显式 {@code record*} 方法，
 * 均为幂等累加（不覆盖）。
 *
 * <p>语义约定：
 * <ul>
 * <li>{@link #ticks()}：每次 {@code tick()} 入口 +1（含被冷却/限速跳过的轮次）</li>
 * <li>{@link #plansSelected()}：一轮内 {@code selectTiers} 选出的待合并组总数</li>
 * <li>{@link #runsSucceeded()}：成功执行的合并次数</li>
 * <li>{@link #runsFailed()}：执行中抛异常而被计入失败的合并次数</li>
 * <li>{@link #skippedCooldown()}：因 {@code cooldownNanos} 未到期而整轮跳过的次数</li>
 * <li>{@link #skippedRateLimit()}：因 {@code maxRunsPerTick} 上限而被推迟的组数</li>
 * <li>{@link #segmentsMerged()}：累计归并掉的源段数</li>
 * <li>{@link #mergedBytes()}：累计归并的源段字节数</li>
 * </ul>
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.sched}；仅 JDK import（NG-3）。
 */
public final class CompactionStats {

    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong plansSelected = new AtomicLong();
    private final AtomicLong runsSucceeded = new AtomicLong();
    private final AtomicLong runsFailed = new AtomicLong();
    private final AtomicLong skippedCooldown = new AtomicLong();
    private final AtomicLong skippedRateLimit = new AtomicLong();
    private final AtomicLong segmentsMerged = new AtomicLong();
    private final AtomicLong mergedBytes = new AtomicLong();

    /** 记一轮 tick。 */
    public void recordTick() {
        ticks.incrementAndGet();
    }

    /**
     * 记本轮选出的待合并组数。
     *
     * @param count 组数，≥ 0
     */
    public void recordPlans(int count) {
        if (count > 0) {
            plansSelected.addAndGet(count);
        }
    }

    /**
     * 记一次成功合并。
     *
     * @param sourceCount 归并的源段数，≥ 1
     * @param bytes       归并的源段总字节数，≥ 0
     */
    public void recordRun(long sourceCount, long bytes) {
        runsSucceeded.incrementAndGet();
        segmentsMerged.addAndGet(sourceCount);
        mergedBytes.addAndGet(bytes);
    }

    /** 记一次失败合并。 */
    public void recordRunFailed() {
        runsFailed.incrementAndGet();
    }

    /** 记一次因冷却未到期而整轮跳过。 */
    public void recordSkippedCooldown() {
        skippedCooldown.incrementAndGet();
    }

    /**
     * 记因单 tick 上限被推迟的组数。
     *
     * @param count 被推迟组数，≥ 0
     */
    public void recordSkippedRateLimit(int count) {
        if (count > 0) {
            skippedRateLimit.addAndGet(count);
        }
    }

    /** tick 轮数。 */
    public long ticks() {
        return ticks.get();
    }

    /** 累计选出待合并组数。 */
    public long plansSelected() {
        return plansSelected.get();
    }

    /** 累计成功合并次数。 */
    public long runsSucceeded() {
        return runsSucceeded.get();
    }

    /** 累计失败合并次数。 */
    public long runsFailed() {
        return runsFailed.get();
    }

    /** 累计冷却跳过轮数。 */
    public long skippedCooldown() {
        return skippedCooldown.get();
    }

    /** 累计限速推迟组数。 */
    public long skippedRateLimit() {
        return skippedRateLimit.get();
    }

    /** 累计归并源段数。 */
    public long segmentsMerged() {
        return segmentsMerged.get();
    }

    /** 累计归并源段字节数。 */
    public long mergedBytes() {
        return mergedBytes.get();
    }

    /** 归零全部计数（仅用于测试）。 */
    public void resetForTest() {
        ticks.set(0L);
        plansSelected.set(0L);
        runsSucceeded.set(0L);
        runsFailed.set(0L);
        skippedCooldown.set(0L);
        skippedRateLimit.set(0L);
        segmentsMerged.set(0L);
        mergedBytes.set(0L);
    }
}
