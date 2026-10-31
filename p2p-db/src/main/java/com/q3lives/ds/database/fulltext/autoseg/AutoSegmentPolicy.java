package com.q3lives.ds.database.fulltext.autoseg;

/**
 * P49 自动段化触发策略（不可变值对象 + 纯函数判定）。
 *
 * <p>
 * 承载写路径装饰器 {@link AutoSegmentHook} 的两个阈值：
 * <ul>
 * <li>{@link #writesPerFlush()}：自上次导段以来的累计写入数达到即「够格」导出一个全量快照段，
 * 必须 ≥ 1；</li>
 * <li>{@link #minSegmentsToCompact()}：参与合并的段数下限，段数低于该值即便写入数达标也<b>不合并</b>
 * （避免为空仓库反复空跑），必须 ≥ 2。</li>
 * </ul>
 *
 * <p>
 * {@link #shouldFlush(int)} 与 {@link #shouldCompact(int)} 均为<b>纯函数</b>：同一输入恒得同一输出，
 * 便于跨进程复现（继承 P47 R12 触发确定性）。本类不自持线程、不触磁盘，仅做阈值判定。
 *
 * <p>
 * 2 条 IAE 守卫：① {@code writesPerFlush < 1} ② {@code minSegmentsToCompact < 2}。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autoseg}，不新增 fulltext 根包 top-level
 * 类；仅 JDK import（NG-3）。
 */
public final class AutoSegmentPolicy {

    private final int writesPerFlush;
    private final int minSegmentsToCompact;

    /**
     * 构造自动段化策略（构造即校验 2 条不变量）。
     *
     * @param writesPerFlush        累计写入数触发阈值，≥ 1
     * @param minSegmentsToCompact  参与合并的段数下限，≥ 2
     */
    public AutoSegmentPolicy(int writesPerFlush, int minSegmentsToCompact) {
        if (writesPerFlush < 1) {
            throw new IllegalArgumentException("policy writesPerFlush < 1: " + writesPerFlush);
        }
        if (minSegmentsToCompact < 2) {
            throw new IllegalArgumentException("policy minSegmentsToCompact < 2: " + minSegmentsToCompact);
        }
        this.writesPerFlush = writesPerFlush;
        this.minSegmentsToCompact = minSegmentsToCompact;
    }

    /**
     * 默认策略：{@code writesPerFlush=4 / minSegmentsToCompact=4}（与 P40/P47 默认 {@code fanIn=4} 对齐）。
     */
    public static AutoSegmentPolicy defaults() {
        return new AutoSegmentPolicy(4, 4);
    }

    /** 累计写入数触发阈值。 */
    public int writesPerFlush() {
        return writesPerFlush;
    }

    /** 参与合并的段数下限。 */
    public int minSegmentsToCompact() {
        return minSegmentsToCompact;
    }

    /**
     * 纯函数判定：自上次导段以来的写入数是否达到导出阈值。
     *
     * @param writesSinceFlush 自上次导段以来的写入数，≥ 0
     * @return {@code writesSinceFlush >= writesPerFlush} 返回 {@code true}
     */
    public boolean shouldFlush(int writesSinceFlush) {
        return writesSinceFlush >= writesPerFlush;
    }

    /**
     * 纯函数判定：当前已注册段数是否达到可参与合并的下限。
     *
     * @param segCount 当前已注册段数，≥ 0
     * @return {@code segCount >= minSegmentsToCompact} 返回 {@code true}
     */
    public boolean shouldCompact(int segCount) {
        return segCount >= minSegmentsToCompact;
    }
}
