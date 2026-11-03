package com.q3lives.ds.cluster.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P55 db-stats 不可变可观测快照。
 *
 * <p>
 * 由 {@link DbStatsRegistry#snapshot()} 在调用线程内同步聚合产出，承载 4 类运行时指标的一个时间点视图：
 * <ul>
 * <li>{@link #cacheHitRate()}：缓存命中率 = {@code coldReadHits / (coldReadHits + coldReadMisses)}，分母 0 → 0；</li>
 * <li>{@link #walReplayLag()}：<b>binlog 复制 lag 口径</b>（dsdb 无 WAL 对象），= Σ 已注册 replicator 的
 * {@code source.sequence − applied.sequence}；</li>
 * <li>{@link #txnLatencyP99Nanos()}：事务提交耗时 p99（环形缓冲精确分位）；</li>
 * <li>{@link #compactionBytesPerSec()}：compaction 归并吞吐（字节/秒）。</li>
 * </ul>
 *
 * <p>
 * 纯 POJO：0 外部依赖 / 0 可变状态；仅 JDK import（NG-3）。{@link #toMap()} 输出 JSON 友好的五组键
 * {@code cache / wal / txn / compaction / replica}。
 */
public final class DbStats {

    private final double cacheHitRate;
    private final int cacheActiveBlocks;
    private final long cacheCachedBytes;
    private final long walReplayLag;
    private final long txnCount;
    private final long txnLatencyP99Nanos;
    private final double compactionBytesPerSec;
    private final long compactionRunsSucceeded;
    private final long replicaLag;

    /**
     * 构造不可变快照。
     *
     * @param cacheHitRate            缓存命中率，∈ [0,1]
     * @param cacheActiveBlocks       活跃缓存块数
     * @param cacheCachedBytes        缓存占用的字节数
     * @param walReplayLag            binlog 复制 lag（未应用序列数，Σ）
     * @param txnCount                累计采集的事务提交笔数
     * @param txnLatencyP99Nanos      事务提交耗时 p99（纳秒）
     * @param compactionBytesPerSec   compaction 归并吞吐（字节/秒）
     * @param compactionRunsSucceeded 累计成功合并次数
     * @param replicaLag              副本 lag（各 replicator 的最大值）
     */
    public DbStats(double cacheHitRate, int cacheActiveBlocks, long cacheCachedBytes,
                   long walReplayLag, long txnCount, long txnLatencyP99Nanos,
                   double compactionBytesPerSec, long compactionRunsSucceeded, long replicaLag) {
        this.cacheHitRate = cacheHitRate;
        this.cacheActiveBlocks = cacheActiveBlocks;
        this.cacheCachedBytes = cacheCachedBytes;
        this.walReplayLag = walReplayLag;
        this.txnCount = txnCount;
        this.txnLatencyP99Nanos = txnLatencyP99Nanos;
        this.compactionBytesPerSec = compactionBytesPerSec;
        this.compactionRunsSucceeded = compactionRunsSucceeded;
        this.replicaLag = replicaLag;
    }

    /** 缓存命中率。 */
    public double cacheHitRate() {
        return cacheHitRate;
    }

    /** 活跃缓存块数。 */
    public int cacheActiveBlocks() {
        return cacheActiveBlocks;
    }

    /** 缓存占用字节数。 */
    public long cacheCachedBytes() {
        return cacheCachedBytes;
    }

    /** binlog 复制 lag（未应用序列数，Σ）。 */
    public long walReplayLag() {
        return walReplayLag;
    }

    /** 累计采集的事务提交笔数。 */
    public long txnCount() {
        return txnCount;
    }

    /** 事务提交耗时 p99（纳秒）。 */
    public long txnLatencyP99Nanos() {
        return txnLatencyP99Nanos;
    }

    /** compaction 归并吞吐（字节/秒）。 */
    public double compactionBytesPerSec() {
        return compactionBytesPerSec;
    }

    /** 累计成功合并次数。 */
    public long compactionRunsSucceeded() {
        return compactionRunsSucceeded;
    }

    /** 副本 lag（各 replicator 的最大值）。 */
    public long replicaLag() {
        return replicaLag;
    }

    /**
     * JSON 友好五组键视图：
     * <pre>
     * {cache:{hitRate,activeBlocks,cachedBytes}, wal:{replayLag}, txn:{count,latencyP99Nanos},
     *  compaction:{bytesPerSec,runsSucceeded}, replica:{lag}}
     * </pre>
     *
     * @return 有序不可变语义的嵌套 Map（仅本快照读取一次）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hitRate", cacheHitRate);
        cache.put("activeBlocks", cacheActiveBlocks);
        cache.put("cachedBytes", cacheCachedBytes);
        root.put("cache", cache);
        Map<String, Object> wal = new LinkedHashMap<>();
        wal.put("replayLag", walReplayLag);
        root.put("wal", wal);
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("count", txnCount);
        txn.put("latencyP99Nanos", txnLatencyP99Nanos);
        root.put("txn", txn);
        Map<String, Object> compaction = new LinkedHashMap<>();
        compaction.put("bytesPerSec", compactionBytesPerSec);
        compaction.put("runsSucceeded", compactionRunsSucceeded);
        root.put("compaction", compaction);
        Map<String, Object> replica = new LinkedHashMap<>();
        replica.put("lag", replicaLag);
        root.put("replica", replica);
        return root;
    }
}
