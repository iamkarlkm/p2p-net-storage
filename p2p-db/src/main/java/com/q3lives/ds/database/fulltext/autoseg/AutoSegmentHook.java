package com.q3lives.ds.database.fulltext.autoseg;

import java.io.IOException;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;
import com.q3lives.ds.database.fulltext.sched.CompactionStats;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P49 写路径段化装饰器：把 P42 「内存分层倒排 → 不可变段」的导出动作机会式挂到写路径上。
 *
 * <p>
 * P21 {@link FulltextStore} 的写方法（{@code putDoc} / {@code ingestDoc} / {@code insert}）本身<b>只写
 * 内存 WeightStore、从不产段</b>；P42 {@link LayeredIndexExporter#export} 需要调用方显式驱动。本装饰器把
 * 「写满 N 次之后是否顺带导出一个段、段数够了是否顺带合并一轮」变成<b>自动、可配阈值、可观测</b>的行为：
 * 每次装饰化写先<b>委托</b> P21 落内存，再按 {@link AutoSegmentPolicy} 判定是否在<b>同一写入线程内</b>导出
 * 全量快照段，并按段数阈值内联 P47 {@code CompactionScheduler.tick()}。
 *
 * <p>
 * 关键红线：
 * <ul>
 * <li><b>R10 零线程</b>：不新增任何线程 —— 导出与 {@code tick()} 完全内联于<b>写入者线程</b>，严禁
 * {@code new Thread} / {@code ExecutorService} / {@code Timer}；</li>
 * <li><b>R11 段 id 确定性</b>：每次导出的 segId = {@code max(store.list()) + 1}（无已注册段时为 0），
 * 同写入序列 + 同仓库状态恒得同 segId 序列，绝不与既存/在飞段冲突；</li>
 * <li><b>R13 写路径透明性</b>：未达导出阈值时，装饰化写的 observable 效果（返回值 + 内存 WeightStore
 * 状态 + {@code store.list()}）与裸 {@code ft.putDoc/ingestDoc/insert} <b>一致</b>，不产任何段。</li>
 * </ul>
 *
 * <p>
 * 4 条 IAE 守卫：① ft 为 null ② store 为 null ③ schedCfg 为 null ④ policy 为 null。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.autoseg}（因 {@code sched/} 与 {@code autocompact/}
 * 递归计数=3 均已锁死）；P21 {@code FulltextStore}、P37 {@code segment/}、P40 {@code compact/}、P42
 * {@code layered/}、P47 {@code sched/}、P48 {@code autocompact/} 源码 0 改；仅 JDK + first-party import
 * （NG-3）。
 */
public final class AutoSegmentHook implements AutoCloseable {

    private final FulltextStore ft;
    private final DsIndexSegmentStore store;
    private final AutoSegmentPolicy policy;
    private final LayeredIndexExporter exporter;
    private final CompactionScheduler scheduler;
    private final AutoSegmentStats segStats;

    private int pendingWrites;

    /**
     * 创建写路径段化装饰器。
     *
     * @param ft       被装饰的全量全文存储（写内存 WeightStore），非 null
     * @param store    目标段仓库，非 null
     * @param schedCfg P47 调度配置，非 null
     * @param policy   自动段化策略，非 null
     */
    public AutoSegmentHook(FulltextStore ft, DsIndexSegmentStore store,
                           CompactionSchedConfig schedCfg, AutoSegmentPolicy policy) {
        if (ft == null) {
            throw new IllegalArgumentException("autoseg ft null");
        }
        if (store == null) {
            throw new IllegalArgumentException("autoseg store null");
        }
        if (schedCfg == null) {
            throw new IllegalArgumentException("autoseg schedCfg null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("autoseg policy null");
        }
        this.ft = ft;
        this.store = store;
        this.policy = policy;
        this.exporter = new LayeredIndexExporter();
        this.scheduler = new CompactionScheduler(store, schedCfg);
        this.segStats = new AutoSegmentStats();
    }

    /**
     * 装饰化的 putDoc：委托 {@link FulltextStore#putDoc} 后按策略机会式导出段并内联一轮 compaction。
     *
     * @param docId   文档 id
     * @param newText 新文本
     * @return P21 返回的 ingest 结果对象（与裸写同值）
     */
    public IngestStat putDoc(long docId, String newText) throws IOException {
        IngestStat st = ft.putDoc(docId, newText);
        afterWrite();
        return st;
    }

    /**
     * 装饰化的 ingestDoc：委托 {@link FulltextStore#ingestDoc} 后按策略机会式导出段并内联一轮 compaction。
     *
     * @param docId 文档 id
     * @param text  文本
     * @return P21 返回的 ingest 结果对象（与裸写同值）
     */
    public IngestStat ingestDoc(long docId, String text) throws IOException {
        IngestStat st = ft.ingestDoc(docId, text);
        afterWrite();
        return st;
    }

    /**
     * 装饰化的 insert：委托 {@link FulltextStore#insert} 后按策略机会式导出段并内联一轮 compaction。
     *
     * @param row 文本单元行
     */
    public void insert(TermDictStore.TuRow row) throws IOException {
        ft.insert(row);
        afterWrite();
    }

    /**
     * 显式导出 1 个全量快照段（绕过写入阈值），随后按段数阈值机会式内联一轮 compaction。
     *
     * @return 本次导出的不可变快照描述
     */
    public LayeredSnapshot flushNow() throws IOException {
        return doFlush();
    }

    /** 自上次导出段以来的累计写入数。 */
    public int writesSinceFlush() {
        return pendingWrites;
    }

    /** 本装饰器的段化/合并触发统计（只读）。 */
    public AutoSegmentStats stats() {
        return segStats;
    }

    /** 底层 P47 调度统计（累计合并细节，只读）。 */
    public CompactionStats compactionStats() {
        return scheduler.stats();
    }

    /**
     * 关闭底层调度器（转调 {@link CompactionScheduler#close()}，幂等）。
     */
    @Override
    public void close() {
        scheduler.close();
    }

    /**
     * 写后置：计一次写入，达导出阈值则导出一段。
     */
    private void afterWrite() throws IOException {
        segStats.recordWrite();
        pendingWrites++;
        if (policy.shouldFlush(pendingWrites)) {
            doFlush();
        }
    }

    /**
     * 导出 1 段（R11 确定性 segId）并按段数阈值机会式内联一轮 compaction。
     *
     * <p>时序：① 取 {@code max(list)+1} 作新 segId → ② P42 {@code export}（内部 {@code finish} 先于
     * {@code store.append}，R9）→ ③ 复位写计数 → ④ 段数达标则在<b>当前线程</b>内联 {@code tick()}，成功
     * 合并记 compact，否则记 skip。
     */
    private LayeredSnapshot doFlush() throws IOException {
        LayeredSnapshot snap = exporter.export(ft, store, nextSegId());
        segStats.recordFlush();
        pendingWrites = 0;
        if (policy.shouldCompact(store.list().length)) {
            int runs = scheduler.tick();
            if (runs > 0) {
                segStats.recordCompact();
                return snap;
            }
        }
        segStats.recordSkip();
        return snap;
    }

    /**
     * R11 确定性段 id：{@code max(store.list()) + 1}；无已注册段时为 0。
     */
    private long nextSegId() throws IOException {
        long max = -1L;
        for (long id : store.list()) {
            if (id > max) {
                max = id;
            }
        }
        return max + 1L;
    }
}
