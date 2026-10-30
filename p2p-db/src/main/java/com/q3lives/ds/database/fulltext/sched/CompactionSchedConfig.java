package com.q3lives.ds.database.fulltext.sched;

/**
 * P47 compaction 调度配置（不可变值对象 + 自适应水位）。
 *
 * <p>
 * 承载 {@code SegmentCompactionPolicy} 的触发阈值（{@link #fanIn()} / {@link #maxFanIn()}）与两类
 * 「限速」参数：{@link #maxRunsPerTick()}（单 tick 最多执行合并次数）与 {@link #cooldownNanos()}
 * （两轮执行性 tick 的最小间隔）；以及自适应水位 {@link #highWatermarkSegments()}。
 *
 * <p>
 * <b>自适应</b>：{@link #effectiveFanIn(int)} 为纯函数 —— 当已注册段数达到水位时把 {@code fanIn}
 * 收紧一档（下界 2），使 compaction 更激进地触发（段越多越急于收敛），否则维持默认。同一输入恒得
 * 同一输出，便于跨进程复现。
 *
 * <p>
 * 5 条 IAE 守卫：① {@code fanIn < 2} ② {@code maxFanIn < fanIn} ③ {@code maxRunsPerTick < 1}
 * ④ {@code cooldownNanos < 0} ⑤ {@code highWatermarkSegments < 1}。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.sched}，不新增 fulltext 根包 top-level 类；
 * 仅 JDK + first-party import（NG-3）。
 */
public final class CompactionSchedConfig {

    private final int fanIn;
    private final int maxFanIn;
    private final int maxRunsPerTick;
    private final long cooldownNanos;
    private final int highWatermarkSegments;

    /**
     * 构造调度配置（构造即校验 5 条不变量）。
     *
     * @param fanIn                层内触发阈值，≥ 2
     * @param maxFanIn             单次合并纳入段数上限，≥ fanIn
     * @param maxRunsPerTick       单 tick 最多执行合并次数，≥ 1
     * @param cooldownNanos        两轮执行性 tick 的最小间隔（纳秒），≥ 0（0 = 不限速）
     * @param highWatermarkSegments 自适应水位（段数达到即收紧 fanIn），≥ 1
     */
    public CompactionSchedConfig(int fanIn, int maxFanIn, int maxRunsPerTick,
                                 long cooldownNanos, int highWatermarkSegments) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("config fanIn < 2: " + fanIn);
        }
        if (maxFanIn < fanIn) {
            throw new IllegalArgumentException("config maxFanIn < fanIn: " + maxFanIn + " < " + fanIn);
        }
        if (maxRunsPerTick < 1) {
            throw new IllegalArgumentException("config maxRunsPerTick < 1: " + maxRunsPerTick);
        }
        if (cooldownNanos < 0) {
            throw new IllegalArgumentException("config cooldownNanos < 0: " + cooldownNanos);
        }
        if (highWatermarkSegments < 1) {
            throw new IllegalArgumentException("config highWatermarkSegments < 1: " + highWatermarkSegments);
        }
        this.fanIn = fanIn;
        this.maxFanIn = maxFanIn;
        this.maxRunsPerTick = maxRunsPerTick;
        this.cooldownNanos = cooldownNanos;
        this.highWatermarkSegments = highWatermarkSegments;
    }

    /**
     * 默认配置：{@code fanIn=4 / maxFanIn=4 / maxRunsPerTick=1 / cooldownNanos=0 / 水位=Integer.MAX_VALUE}。
     */
    public static CompactionSchedConfig defaults() {
        return new CompactionSchedConfig(4, 4, 1, 0L, Integer.MAX_VALUE);
    }

    /** 层内触发阈值。 */
    public int fanIn() {
        return fanIn;
    }

    /** 单次合并段数上限。 */
    public int maxFanIn() {
        return maxFanIn;
    }

    /** 单 tick 最多执行合并次数。 */
    public int maxRunsPerTick() {
        return maxRunsPerTick;
    }

    /** 两轮执行性 tick 的最小间隔（纳秒）。 */
    public long cooldownNanos() {
        return cooldownNanos;
    }

    /** 自适应水位（段数）。 */
    public int highWatermarkSegments() {
        return highWatermarkSegments;
    }

    /**
     * 自适应有效阈值：段数 ≥ 水位时收紧 {@code fanIn} 一档（下界 2），否则保持 {@code fanIn}。
     *
     * @param segCount 当前已注册段数，≥ 0
     * @return 参与 {@code selectTiers} 的有效 {@code fanIn}（恒 ≥ 2 且 ≤ 本配置 {@code fanIn}）
     */
    public int effectiveFanIn(int segCount) {
        if (segCount >= highWatermarkSegments) {
            int lowered = fanIn - 1;
            return lowered < 2 ? 2 : lowered;
        }
        return fanIn;
    }
}
