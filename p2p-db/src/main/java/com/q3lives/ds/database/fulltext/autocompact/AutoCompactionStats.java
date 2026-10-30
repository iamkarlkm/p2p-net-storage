package com.q3lives.ds.database.fulltext.autocompact;

import java.util.concurrent.atomic.AtomicLong;

/**
 * P48 自动 compaction 触发统计（写路径装饰器可观测计数）。
 *
 * <p>
 * 与 P47 {@code CompactionStats}（记录合并执行细节）互补：本类只关注「写路径挂钩」本身的效果 ——
 * 观察了多少次段写入、顺带触发了多少轮合并、以及多少次因段数不足而放弃触发。全部计数用
 * {@link AtomicLong}，写入口为显式 {@code record*} 方法，均为幂等累加（不覆盖）。
 *
 * <p>语义约定：
 * <ul>
 * <li>{@link #writesObserved()}：每次 {@code append} 入口 +1（含未触发轮次）</li>
 * <li>{@link #triggersFired()}：每次自动触发且 {@code tick()} 实际执行了合并 +1</li>
 * <li>{@link #triggersSkipped()}：写入数达标但段数不足而未触发 +1</li>
 * </ul>
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autocompact}；仅 JDK import（NG-3）。
 */
public final class AutoCompactionStats {

    private final AtomicLong writesObserved = new AtomicLong();
    private final AtomicLong triggersFired = new AtomicLong();
    private final AtomicLong triggersSkipped = new AtomicLong();

    /** 记一次段写入观察。 */
    public void recordWrite() {
        writesObserved.incrementAndGet();
    }

    /** 记一次自动触发（本轮确有合并执行）。 */
    public void recordTrigger() {
        triggersFired.incrementAndGet();
    }

    /** 记一次因段数不足而放弃触发。 */
    public void recordSkip() {
        triggersSkipped.incrementAndGet();
    }

    /** 累计观察到的段写入数。 */
    public long writesObserved() {
        return writesObserved.get();
    }

    /** 累计自动触发次数。 */
    public long triggersFired() {
        return triggersFired.get();
    }

    /** 累计因段数不足放弃触发次数。 */
    public long triggersSkipped() {
        return triggersSkipped.get();
    }

    /** 归零全部计数（仅用于测试）。 */
    public void resetForTest() {
        writesObserved.set(0L);
        triggersFired.set(0L);
        triggersSkipped.set(0L);
    }
}
