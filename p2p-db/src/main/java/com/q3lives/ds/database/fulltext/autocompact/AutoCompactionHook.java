package com.q3lives.ds.database.fulltext.autocompact;

import java.io.IOException;

import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;
import com.q3lives.ds.database.fulltext.sched.CompactionStats;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P48 写路径装饰器：把 P47 compaction 调度「机会式」挂到段写入路径上。
 *
 * <p>
 * 在本类之上，P47 {@link CompactionScheduler} 仍需由调用方手动驱动 {@code tick()}；本装饰器把
 * 「写完整段之后是否顺带合并一轮」变成<b>自动、可配阈值、可观测</b>的行为：{@link #append(long, DsIndexSegment)}
 * 先<b>委托</b> P37 {@link DsIndexSegmentStore#append(long, DsIndexSegment)} 落盘，再按
 * {@link AutoCompactionPolicy} 判定是否在<b>同一写入线程内</b>内联调用 {@code tick()}。
 *
 * <p>
 * 关键红线：
 * <ul>
 * <li><b>R10 零线程</b>：不新增任何线程 —— {@code tick()} 完全内联于<b>写入者线程</b>，严禁
 * {@code new Thread} / {@code ExecutorService} / {@code Timer}；</li>
 * <li><b>R11 写路径透明性</b>：阈值未达时，{@code append} 除计数器自增与只读 {@code list()} 外，其
 * observable 效果（manifest 登记 + 返回值 + 异常语义）与裸 {@code store.append} <b>逐字节一致</b>。</li>
 * </ul>
 *
 * <p>
 * 3 条 IAE 守卫：① store 为 null ② schedCfg 为 null ③ policy 为 null。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autocompact}（因 {@code sched/} 递归计数=3
 * 已锁死），不新增 fulltext 根包 top-level 类；P37 {@code segment/}、P40 {@code compact/}、P47
 * {@code sched/} 源码 0 改；仅 JDK + first-party import（NG-3）。
 */
public final class AutoCompactionHook implements AutoCloseable {

    private final DsIndexSegmentStore store;
    private final AutoCompactionPolicy policy;
    private final CompactionScheduler scheduler;
    private final AutoCompactionStats autoStats;

    private int writesSinceLastTick;

    /**
     * 创建写路径装饰器。
     *
     * @param store    目标段仓库，非 null
     * @param schedCfg P47 调度配置，非 null
     * @param policy   自动触发策略，非 null
     */
    public AutoCompactionHook(DsIndexSegmentStore store, CompactionSchedConfig schedCfg,
                              AutoCompactionPolicy policy) {
        if (store == null) {
            throw new IllegalArgumentException("hook store null");
        }
        if (schedCfg == null) {
            throw new IllegalArgumentException("hook schedCfg null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("hook policy null");
        }
        this.store = store;
        this.policy = policy;
        this.scheduler = new CompactionScheduler(store, schedCfg);
        this.autoStats = new AutoCompactionStats();
    }

    /**
     * 装饰化的段写入：委托 {@link DsIndexSegmentStore#append} 后按策略机会式内联一轮 compaction。
     *
     * <p>
     * 时序：① 委托落盘（写 manifest）→ ② 写入计数 +1 → ③ 读段数 → ④ 阈值达标则在<b>当前线程</b>
     * 内联 {@code tick()}，成功合并则记触发并复位写计数。阈值未达时本方法对外表现与裸 {@code store.append}
     * 一致（R11）。
     *
     * @param segId 段 id，≥ 0
     * @param seg   段描述对象
     * @return 从磁盘段头解析出的段描述对象（与裸 {@code store.append} 同值）
     */
    public DsIndexSegment append(long segId, DsIndexSegment seg) throws IOException {
        DsIndexSegment onDisk = store.append(segId, seg);
        autoStats.recordWrite();
        writesSinceLastTick++;
        int segCount = store.list().length;
        if (writesSinceLastTick >= policy.writesPerTrigger()
                && segCount < policy.minSegmentsToCompact()) {
            autoStats.recordSkip();
        } else if (policy.shouldTrigger(writesSinceLastTick, segCount)) {
            int runs = scheduler.tick();
            if (runs > 0) {
                autoStats.recordTrigger();
                writesSinceLastTick = 0;
            }
        }
        return onDisk;
    }

    /**
     * 显式手动触发一轮 {@code tick()}（绕过写入阈值判定），供运维 / 测试按需驱动。
     *
     * @return 本轮实际执行的合并次数
     */
    public int tickNow() throws IOException {
        int runs = scheduler.tick();
        if (runs > 0) {
            writesSinceLastTick = 0;
        }
        return runs;
    }

    /** 自上次成功合并以来的累计写入数。 */
    public int writesSinceLastTick() {
        return writesSinceLastTick;
    }

    /** 底层 P47 调度统计（累计合并细节，只读）。 */
    public CompactionStats stats() {
        return scheduler.stats();
    }

    /** 本装饰器的写路径触发统计（只读）。 */
    public AutoCompactionStats autoStats() {
        return autoStats;
    }

    /**
     * 关闭底层编排器（转调 {@link CompactionScheduler#close()}，幂等）。
     */
    @Override
    public void close() {
        scheduler.close();
    }
}
