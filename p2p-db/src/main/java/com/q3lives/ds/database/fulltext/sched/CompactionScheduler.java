package com.q3lives.ds.database.fulltext.sched;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.q3lives.ds.database.fulltext.compact.SegmentCompactionPlan;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactionPolicy;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactionRunner;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P47 compaction 自适应调度器（tick 驱动，零自有线程）。
 *
 * <p>
 * 在 P40 三件套 {@link SegmentCompactionPolicy} → {@link SegmentCompactionPlan} →
 * {@link SegmentCompactionRunner} 之上叠加一层「何时合并、一轮最多合并几次」的决策，把 {@link #tick()}
 * 作为<b>纯函数式驱动入口</b>：由调用方在既有线程（写路径 / 既有后台任务 / 测试）显式 调用，<b>不新增任何线程</b>（R10 —— 继承
 * P40「0 新增 {@code ExecutorService}」红线）。
 *
 * <p>
 * 一轮 {@link #tick()} 的执行序：
 * <ol>
 * <li>冷却限速：距上次执行不足 {@link CompactionSchedConfig#cooldownNanos()} → 记 skipped
 * 并返回 0；</li>
 * <li>{@code store.list()} 取已注册段并按 <b>segId 升序排序</b>（manifest 为插入序，非排序），逐段取
 * {@code File.length()} 作体积；</li>
 * <li>自适应：{@link CompactionSchedConfig#effectiveFanIn(int)} 依段数与水位收紧阈值；</li>
 * <li>{@code selectTiers} 选段（纯函数，确定性 —— 限速不参与选段）；单组段数上限经
 * {@link #compatibleMaxFanIn} 与命中层实际段数对齐，避免 P40「恒取 maxFanIn」在薄层越界；</li>
 * <li>逐组构造计划并 {@code runner.run}（继承 P40 append-before-remove 崩溃安全）；组间源段互不相交， 产物
 * id 以 {@code max(segId)+1+runs} 递增避冲突；</li>
 * <li>单 tick 上限限速：超出 {@link CompactionSchedConfig#maxRunsPerTick()} 的组记 skipped
 * 并推迟。</li>
 * </ol>
 *
 * <p>
 * 2 条直接 IAE 守卫：① store 为 null ② config 为 null（执行期源段未注册 / mergedSegId 已注册的 守卫由
 * P40 {@link SegmentCompactionRunner} 承担）。
 *
 * <p>
 * 红线：ZeroLeak3 —— 本类位于新兄弟包 {@code fulltext.sched}（因 {@code compact/} 递归计数=5
 * 已锁死）， 不新增 fulltext 根包 top-level 类；P37 {@code segment/} 与 P40 {@code compact/}
 * 源码 0 改； 仅 JDK + first-party import（NG-3）。
 */
public final class CompactionScheduler implements AutoCloseable {

    private static final long NO_RUN = Long.MIN_VALUE;

    private final DsIndexSegmentStore store;
    private final CompactionSchedConfig config;
    private final SegmentCompactionRunner runner;
    private final CompactionStats stats;
    private long lastRunNanos = NO_RUN;

    /**
     * 创建调度器。
     *
     * @param store 目标段仓库，非 null
     * @param config 调度配置，非 null
     */
    public CompactionScheduler(DsIndexSegmentStore store, CompactionSchedConfig config) {
        if (store == null) {
            throw new IllegalArgumentException("scheduler store null");
        }
        if (config == null) {
            throw new IllegalArgumentException("scheduler config null");
        }
        this.store = store;
        this.config = config;
        this.runner = new SegmentCompactionRunner(store);
        this.stats = new CompactionStats();
    }

    /**
     * 执行一轮调度。
     *
     * @return 本轮实际执行的合并次数（0 = 无候选 / 冷却跳过 / 限速推迟）
     */
    public int tick() throws IOException {
        stats.recordTick();
        long now = System.nanoTime();
        if (lastRunNanos != NO_RUN && (now - lastRunNanos) < config.cooldownNanos()) {
            stats.recordSkippedCooldown();
            return 0;
        }
        long[] ids = store.list();
        if (ids.length == 0) {
            return 0;
        }
        Arrays.sort(ids);
        long[] bytes = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            bytes[i] = store.segmentFile(ids[i]).length();
        }
        int effFanIn = config.effectiveFanIn(ids.length);
        int cap = compatibleMaxFanIn(ids, bytes, effFanIn, config.maxFanIn());
        if (cap < effFanIn) {
            return 0;
        }
        List<long[]> groups = SegmentCompactionPolicy.selectTiers(ids, bytes, effFanIn, cap);
        if (groups.isEmpty()) {
            return 0;
        }
        stats.recordPlans(groups.size());
        long baseId = ids[ids.length - 1];
        int runs = 0;
        for (int gi = 0; gi < groups.size(); gi++) {
            if (runs >= config.maxRunsPerTick()) {
                stats.recordSkippedRateLimit(groups.size() - gi);
                break;
            }
            long[] group = groups.get(gi);
            int tier = tierOf(bytesOf(ids, bytes, group[0]));
            SegmentCompactionPlan plan = new SegmentCompactionPlan(tier, group, baseId + 1 + runs);
            try {
                runner.run(plan);
            } catch (IOException | RuntimeException e) {
                stats.recordRunFailed();
                throw e;
            }
            long gbytes = 0L;
            for (long id : group) {
                gbytes += bytesOf(ids, bytes, id);
            }
            stats.recordRun(group.length, gbytes);
            runs++;
        }
        if (runs > 0) {
            lastRunNanos = System.nanoTime();
        }
        return runs;
    }

    /**
     * 调度统计快照（累计计数，只读）。
     */
    public CompactionStats stats() {
        return stats;
    }

    /**
     * 关闭底层编排器（转调 {@link DsIndexSegmentStore#close()}，幂等空实现）。
     */
    @Override
    public void close() {
        runner.close();
    }

    /**
     * 以升序 ids 二分定位某 segId 的字节体积（miss 返回 0）。
     */
    private static long bytesOf(long[] ids, long[] bytes, long segId) {
        int idx = Arrays.binarySearch(ids, segId);
        return idx >= 0 ? bytes[idx] : 0L;
    }

    /**
     * 计算与 P40 {@link SegmentCompactionPolicy#selectTiers} 相容的单组段数上限。
     *
     * <p>
     * P40 选层在命中层内<b>恒取 {@code maxFanIn} 个</b>段（未按层内实际段数裁剪），故当某命中层的 段数介于
     * {@code effFanIn} 与 {@code maxFanIn} 之间时会越界 —— 自适应把 {@code fanIn} 收紧一档后
     * 极易踩中。本方法取「所有命中层（段数 ≥ {@code effFanIn}）的最小段数」与配置 {@code maxFanIn} 的
     * 较小者作为安全上限，保证永不越界且不改动 P40（R5 0 改红线）。若无任何层达到 {@code effFanIn}， 返回
     * {@code 0}（调用方据此判定无候选）。
     *
     * @return 安全上限；无命中层时为 {@code 0}
     */
    private static int compatibleMaxFanIn(long[] ids, long[] bytes, int effFanIn, int maxFanIn) {
        Map<Integer, Integer> counts = new TreeMap<>();
        for (int i = 0; i < ids.length; i++) {
            counts.merge(tierOf(bytes[i]), 1, Integer::sum);
        }
        int minEligible = Integer.MAX_VALUE;
        for (int c : counts.values()) {
            if (c >= effFanIn && c < minEligible) {
                minEligible = c;
            }
        }
        if (minEligible == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.min(maxFanIn, minEligible);
    }

    /**
     * size-tiered 层号：{@code floor(log_4(sizeBytes))}；非正体积归入 tier 0。
     */
    private static int tierOf(long sizeBytes) {
        int tier = 0;
        long v = sizeBytes;
        while (v >= 4) {
            v /= 4;
            tier++;
        }
        return tier;
    }
}
