package com.q3lives.ds.database.fulltext.autocompact;

/**
 * P48 自动 compaction 触发策略（不可变值对象 + 纯函数判定）。
 *
 * <p>
 * 承载写路径装饰器 {@link AutoCompactionHook} 的两个触发阈值：
 * <ul>
 * <li>{@link #writesPerTrigger()}：自上次成功合并以来的累计段写入数达到即「够格」触发一轮
 * {@code tick()}，必须 ≥ 1；</li>
 * <li>{@link #minSegmentsToCompact()}：参与合并的段数下限，段数低于该值即便写入数达标也<b>不触发</b>
 * （避免为空仓库反复空跑），必须 ≥ 2。</li>
 * </ul>
 *
 * <p>
 * {@link #shouldTrigger(int, int)} 为<b>纯函数</b>：仅当两条件同时满足才返回 {@code true}；同一输入恒得同一输出，
 * 便于跨进程复现（继承 P47 R12 触发确定性）。本类不自持线程、不触磁盘，仅做判定。
 *
 * <p>
 * 2 条 IAE 守卫：① {@code writesPerTrigger < 1} ② {@code minSegmentsToCompact < 2}。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autocompact}，不新增 fulltext 根包 top-level
 * 类；仅 JDK import（NG-3）。
 */
public final class AutoCompactionPolicy {

    private final int writesPerTrigger;
    private final int minSegmentsToCompact;

    /**
     * 构造触发策略（构造即校验 2 条不变量）。
     *
     * @param writesPerTrigger      累计写入段数触发阈值，≥ 1
     * @param minSegmentsToCompact  参与合并的段数下限，≥ 2
     */
    public AutoCompactionPolicy(int writesPerTrigger, int minSegmentsToCompact) {
        if (writesPerTrigger < 1) {
            throw new IllegalArgumentException("policy writesPerTrigger < 1: " + writesPerTrigger);
        }
        if (minSegmentsToCompact < 2) {
            throw new IllegalArgumentException("policy minSegmentsToCompact < 2: " + minSegmentsToCompact);
        }
        this.writesPerTrigger = writesPerTrigger;
        this.minSegmentsToCompact = minSegmentsToCompact;
    }

    /**
     * 默认策略：{@code writesPerTrigger=4 / minSegmentsToCompact=4}（与 P40/P47 默认 {@code fanIn=4} 对齐）。
     */
    public static AutoCompactionPolicy defaults() {
        return new AutoCompactionPolicy(4, 4);
    }

    /** 累计写入段数触发阈值。 */
    public int writesPerTrigger() {
        return writesPerTrigger;
    }

    /** 参与合并的段数下限。 */
    public int minSegmentsToCompact() {
        return minSegmentsToCompact;
    }

    /**
     * 纯函数判定：写入数达标且段数达标时触发。
     *
     * @param writesSinceLastTick 自上次成功合并以来的写入数，≥ 0
     * @param segCount            当前已注册段数，≥ 0
     * @return 两条件同时满足返回 {@code true}
     */
    public boolean shouldTrigger(int writesSinceLastTick, int segCount) {
        return writesSinceLastTick >= writesPerTrigger && segCount >= minSegmentsToCompact;
    }
}
