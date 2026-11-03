package com.q3lives.ds.cluster.stats;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

import com.q3lives.ds.core.DsMemory;
import com.q3lives.ds.database.fulltext.sched.CompactionStats;

/**
 * P55 db-stats 静态可观测快照注册表。
 *
 * <p>
 * 把分散在各组件中的运行时指标源（缓存 / compaction / binlog 复制）统一注册，并在 {@link #snapshot()} 时于
 * <b>调用线程内同步</b>聚合为一个 {@link DbStats} 快照，供 p2p-sync 的 {@code /sync/api/db-stats} 端点消费。
 *
 * <p>设计要点（对应 P55 红线）：
 * <ul>
 * <li><b>0 新线程</b>（R5）：所有聚合为 pull 式的同步计算，无 {@code ExecutorService} / {@code Timer}；</li>
 * <li><b>写路径 0 阻塞</b>（R6）：{@link #recordTxnLatency(long)} 仅一次 {@link AtomicLongArray} 环形写 +
 * 一次 {@link AtomicLong} 自增，0 锁 / 0 分配 / 0 IO；</li>
 * <li><b>并发安全</b>（R7）：注册源容器用 {@link ConcurrentHashMap}，采样器用定长 {@link AtomicLongArray}
 * 环形缓冲 + {@link AtomicLong} 游标，快照为弱一致视图；</li>
 * <li><b>降级确定</b>（R8）：无任何注册源时 {@link #snapshot()} 返回全 0 快照（不抛异常）。</li>
 * </ul>
 *
 * <p>
 * 口径说明：{@code walReplayLag} 沿用 roadmap 字段名，但 dsdb 无 WAL 对象，实际语义为
 * <b>binlog 复制 lag</b>（{@code source.sequence − applied.sequence}）。
 */
public final class DbStatsRegistry {

    /** 事务延迟环形缓冲容量（2 的幂，便于位掩码取模）。 */
    private static final int SAMPLE_CAPACITY = 1024;
    private static final int SAMPLE_MASK = SAMPLE_CAPACITY - 1;
    /** 未显式指定 tick 周期时的 compaction 默认节拍（毫秒）。 */
    private static final long DEFAULT_COMPACTION_TICK_MS = 1000L;

    private static final Map<String, Supplier<DsMemory.CacheStats>> CACHE_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, CompactionSource> COMPACTION_SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, Supplier<Long>> REPLICATOR_SOURCES = new ConcurrentHashMap<>();

    private static final AtomicLongArray TXN_SAMPLES = new AtomicLongArray(SAMPLE_CAPACITY);
    private static final AtomicLong TXN_CURSOR = new AtomicLong();
    private static final AtomicLong TXN_COUNT = new AtomicLong();

    private DbStatsRegistry() {
    }

    /**
     * 注册一个缓存指标源。
     *
     * @param name 唯一名，非 null/非空
     * @param src  指标供给器，非 null
     */
    public static void registerCache(String name, Supplier<DsMemory.CacheStats> src) {
        requireSource(name, src);
        CACHE_SOURCES.put(name, src);
    }

    /**
     * 注册一个 compaction 指标源（默认按 1 秒 tick 节拍折算吞吐）。
     *
     * @param name 唯一名，非 null/非空
     * @param src  指标供给器，非 null
     */
    public static void registerCompaction(String name, Supplier<CompactionStats> src) {
        registerCompaction(name, DEFAULT_COMPACTION_TICK_MS, src);
    }

    /**
     * 注册一个 compaction 指标源。
     *
     * @param name   唯一名，非 null/非空
     * @param tickMs 该调度器单 tick 节拍（毫秒），&gt; 0
     * @param src    指标供给器，非 null
     */
    public static void registerCompaction(String name, long tickMs, Supplier<CompactionStats> src) {
        requireSource(name, src);
        if (tickMs <= 0L) {
            throw new IllegalArgumentException("tickMs must be positive: " + tickMs);
        }
        COMPACTION_SOURCES.put(name, new CompactionSource(tickMs, src));
    }

    /**
     * 注册一个 binlog 复制 lag 源（供给「未应用序列数」）。
     *
     * @param name 唯一名，非 null/非空
     * @param src  lag 供给器，非 null
     */
    public static void registerReplicator(String name, Supplier<Long> src) {
        requireSource(name, src);
        REPLICATOR_SOURCES.put(name, src);
    }

    /**
     * 注销一个名字下的全部指标源（三类容器按名移除）。
     *
     * @param name 待注销名；null 时为空操作
     */
    public static void unregister(String name) {
        if (name == null) {
            return;
        }
        CACHE_SOURCES.remove(name);
        COMPACTION_SOURCES.remove(name);
        REPLICATOR_SOURCES.remove(name);
    }

    /**
     * 记录一笔事务提交耗时（写入口，0 锁 / 0 分配 / 0 IO）。
     *
     * @param nanos 耗时纳秒
     */
    public static void recordTxnLatency(long nanos) {
        long seq = TXN_CURSOR.getAndIncrement();
        TXN_SAMPLES.set((int) (seq & SAMPLE_MASK), nanos);
        TXN_COUNT.incrementAndGet();
    }

    /** 累计采集的事务提交笔数（单调累加）。 */
    public static long txnCount() {
        return TXN_COUNT.get();
    }

    /**
     * 事务提交耗时 p99（环形缓冲精确分位）。
     *
     * <p>分位定义：升序第 {@code ceil(0.99 × n) − 1} 个；空样本 → 0。仅对「最近 min(样本数, 容量)」笔取样。
     *
     * @return p99 纳秒
     */
    public static long txnLatencyP99Nanos() {
        int n = (int) Math.min(TXN_COUNT.get(), SAMPLE_CAPACITY);
        if (n <= 0) {
            return 0L;
        }
        long cur = TXN_CURSOR.get();
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) {
            long seq = cur - n + i;
            copy[i] = TXN_SAMPLES.get((int) (seq & SAMPLE_MASK));
        }
        Arrays.sort(copy);
        int idx = (int) Math.ceil(0.99 * n) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= n) {
            idx = n - 1;
        }
        return copy[idx];
    }

    /**
     * 聚合当前已注册源为不可变快照；无注册源时返回全 0 快照（不抛异常）。
     *
     * @return 时间点快照
     */
    public static DbStats snapshot() {
        double hits = 0.0d;
        double misses = 0.0d;
        long activeBlocks = 0L;
        long cachedBytes = 0L;
        for (Supplier<DsMemory.CacheStats> src : CACHE_SOURCES.values()) {
            DsMemory.CacheStats cs = pollQuietly(src);
            if (cs == null) {
                continue;
            }
            hits += cs.getColdReadHits();
            misses += cs.getColdReadMisses();
            activeBlocks += cs.getActiveCachedBlocks();
            cachedBytes += cs.getCachedBytes();
        }
        double totalReads = hits + misses;
        double hitRate = totalReads <= 0.0d ? 0.0d : hits / totalReads;

        long mergedBytes = 0L;
        long runsSucceeded = 0L;
        double seconds = 0.0d;
        for (CompactionSource src : COMPACTION_SOURCES.values()) {
            CompactionStats cs = pollQuietly(src.supplier);
            if (cs == null) {
                continue;
            }
            mergedBytes += cs.mergedBytes();
            runsSucceeded += cs.runsSucceeded();
            seconds += cs.ticks() * src.tickMs / 1000.0d;
        }
        double bytesPerSec = seconds <= 0.0d ? 0.0d : mergedBytes / seconds;

        long sumLag = 0L;
        long maxLag = 0L;
        for (Supplier<Long> src : REPLICATOR_SOURCES.values()) {
            Long v = pollQuietly(src);
            if (v == null) {
                continue;
            }
            sumLag += v;
            if (v > maxLag) {
                maxLag = v;
            }
        }

        return new DbStats(hitRate, (int) activeBlocks, cachedBytes, sumLag,
                TXN_COUNT.get(), txnLatencyP99Nanos(), bytesPerSec, runsSucceeded, maxLag);
    }

    /**
     * 清空全部注册源与事务采样（仅用于测试）。
     */
    public static void clear() {
        CACHE_SOURCES.clear();
        COMPACTION_SOURCES.clear();
        REPLICATOR_SOURCES.clear();
        TXN_CURSOR.set(0L);
        TXN_COUNT.set(0L);
        for (int i = 0; i < SAMPLE_CAPACITY; i++) {
            TXN_SAMPLES.set(i, 0L);
        }
    }

    private static void requireSource(String name, Object src) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("metric source name must be non-empty");
        }
        if (src == null) {
            throw new IllegalArgumentException("metric source supplier must be non-null");
        }
    }

    /** 边界守卫：单个指标源取数失败（RuntimeException）时跳过，不阻断整体快照（R8）。 */
    private static <T> T pollQuietly(Supplier<T> src) {
        try {
            return src.get();
        } catch (RuntimeException ignore) {
            return null;
        }
    }

    /** compaction 源：连同其调度节拍一并注册，用于把累计计数折算为「每秒」吞吐。 */
    private static final class CompactionSource {
        private final long tickMs;
        private final Supplier<CompactionStats> supplier;

        private CompactionSource(long tickMs, Supplier<CompactionStats> supplier) {
            this.tickMs = tickMs;
            this.supplier = supplier;
        }
    }
}
