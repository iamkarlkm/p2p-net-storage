package com.q3lives.ds.database.fulltext.autoseg;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P49 自动段化统计（写路径装饰器可观测计数）。
 *
 * <p>
 * 与 P47 {@code CompactionStats}（记录合并执行细节）互补：本类只关注「写路径段化挂钩」本身的效果 ——
 * 观察了多少次写入、顺带导出了多少段、以及每次导段后是否成功合并掉一轮。全部计数用
 * {@link AtomicLong}，写入口为显式 {@code record*} 方法，均为幂等累加（不覆盖）。
 *
 * <p>语义约定：
 * <ul>
 * <li>{@link #writesObserved()}：每次装饰化写入口 +1（含未触发导段的轮次）</li>
 * <li>{@link #flushesFired()}：每次经 P42 {@code LayeredIndexExporter.export} 成功导出 1 段 +1</li>
 * <li>{@link #compactionsFired()}：每次导段后成功合并掉一轮（{@code tick()} 返回 &gt; 0）+1</li>
 * <li>{@link #compactionsSkipped()}：每次导段后未能成功合并一轮（段数低于下限，或 {@code tick()}
 * 无可合并层）+1；恒有 {@code flushesFired == compactionsFired + compactionsSkipped}</li>
 * </ul>
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autoseg}；仅 JDK import（NG-3）。
 */
public final class AutoSegmentStats {

    private final AtomicLong writesObserved = new AtomicLong();
    private final AtomicLong flushesFired = new AtomicLong();
    private final AtomicLong compactionsFired = new AtomicLong();
    private final AtomicLong compactionsSkipped = new AtomicLong();

    /** 记一次写入观察。 */
    public void recordWrite() {
        writesObserved.incrementAndGet();
    }

    /** 记一次成功导段（P42 全量快照段已登记）。 */
    public void recordFlush() {
        flushesFired.incrementAndGet();
    }

    /** 记一次导段后成功合并掉一轮。 */
    public void recordCompact() {
        compactionsFired.incrementAndGet();
    }

    /** 记一次导段后未能成功合并一轮。 */
    public void recordSkip() {
        compactionsSkipped.incrementAndGet();
    }

    /** 累计观察到的写入数。 */
    public long writesObserved() {
        return writesObserved.get();
    }

    /** 累计成功导出的段数。 */
    public long flushesFired() {
        return flushesFired.get();
    }

    /** 累计导段后成功合并的轮数。 */
    public long compactionsFired() {
        return compactionsFired.get();
    }

    /** 累计导段后未合并的轮数。 */
    public long compactionsSkipped() {
        return compactionsSkipped.get();
    }

    /** 归零全部计数（仅用于测试）。 */
    public void resetForTest() {
        writesObserved.set(0L);
        flushesFired.set(0L);
        compactionsFired.set(0L);
        compactionsSkipped.set(0L);
    }
}
