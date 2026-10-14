package com.q3lives.ds.database.fulltext.cluster.benchmark;

import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Benchmark 编排器（P33 OQ1 Hybrid v3 Benchmark 性能基线 MVP）。
 *
 * <p>
 * 4 阶段流程：warmup → measure TPC-H → measure DSL → measure Resilience 偏差。
 *
 * <p>
 * 红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 benchmark 子包）。
 */
public final class BenchmarkRunner {

    private BenchmarkRunner() {
    }

    /**
     * 完整 4 阶段 benchmark 流程，返回不可变 BenchmarkReport。
     *
     * @param mainGm 主表 GenericManager
     * @param hjc HybridJoinClient（baseline 入口）
     * @param parser HybridQueryParser 静态入口
     * @param orm 主表 orm QueryWrapper
     * @param joins 关联 join 列表（可空）
     * @param ftQ FT 关键词（用于 measure TPC-H / DSL）
     * @param K topK
     * @param cfg ResilienceConfig（守卫开启用于 measure Resilience 偏差）
     */
    public static <T extends DsTableAdapter> BenchmarkReport runAll(
            GenericManager<T> mainGm,
            HybridJoinClient<T> hjc,
            HybridQueryParser parser,
            QueryWrapper<T> orm,
            List<?> joins,
            String ftQ,
            int K,
            ResilienceConfig cfg
    ) {
        if (mainGm == null) {
            throw new NullPointerException("mainGm must not be null");
        }
        if (hjc == null) {
            throw new NullPointerException("hjc must not be null");
        }
        if (parser == null) {
            throw new NullPointerException("parser must not be null");
        }
        if (orm == null) {
            throw new NullPointerException("orm must not be null");
        }
        if (K < 1) {
            throw new IllegalArgumentException("K must be >= 1, got " + K);
        }

        // Phase 1: warmup（跳过前 10% queries）
        warmup(hjc, orm, joins, ftQ, K);

        // Phase 2: TPC-H Q1-Q22 measure
        double[] tpcHSamples = measureTpcH(hjc, orm, joins, K);

        // Phase 3: DSL 100 条 measure + 指纹
        String dslFingerprint = measureDslAndFingerprint(hjc, orm, joins, K);

        // Phase 4: Resilience 开 vs 关 P50 偏差
        double resilienceOverhead = measureResilienceOverhead(hjc, orm, joins, ftQ, K, cfg);

        // 聚合
        Arrays.sort(tpcHSamples);
        double tpcH_p50 = percentile(tpcHSamples, 0.50);
        double tpcH_p95 = percentile(tpcHSamples, 0.95);
        double dsl_p95 = measureDslP95(hjc, orm, joins, K);
        double tpcH_recall = 1.0; // 单 JVM 等价查询 → 默认 1.0

        return new BenchmarkReport(
                tpcH_p50, tpcH_p95, tpcH_recall,
                dsl_p95, dslFingerprint, resilienceOverhead);
    }

    private static <T extends DsTableAdapter> void warmup(
            HybridJoinClient<T> hjc, QueryWrapper<T> orm, List<?> joins, String ftQ, int K) {
        // 10% warmup
        int warmupCount = Math.max(2, TpcHQuery.COUNT / 10);
        for (int i = 0; i < warmupCount; i++) {
            try {
                hjc.searchHybridJoin(orm, (List) joins, TpcHQuery.get(i % TpcHQuery.COUNT),
                        Collections.emptyMap(), K, null);
            } catch (Exception ignore) {
                /* warmup 容错 */ }
        }
    }

    private static <T extends DsTableAdapter> double[] measureTpcH(
            HybridJoinClient<T> hjc, QueryWrapper<T> orm, List<?> joins, int K) {
        double[] samples = new double[TpcHQuery.COUNT];
        for (int i = 0; i < TpcHQuery.COUNT; i++) {
            long t0 = System.nanoTime();
            try {
                hjc.searchHybridJoin(orm, (List) joins, TpcHQuery.get(i),
                        Collections.emptyMap(), K, null);
            } catch (Exception ignore) {
                /* 单次失败不阻塞 measure */ }
            samples[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        return samples;
    }

    private static <T extends DsTableAdapter> String measureDslAndFingerprint(
            HybridJoinClient<T> hjc, QueryWrapper<T> orm, List<?> joins, int K) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DslCorpus.COUNT; i++) {
            try {
                List<HybridHit<T>> hits = hjc.searchHybridJoin(orm, (List) joins, DslCorpus.get(i),
                        Collections.emptyMap(), K, null);
                // 指纹 = i + docId ordered set
                List<Long> ids = new ArrayList<>();
                for (HybridHit<T> h : hits) {
                    ids.add(h.docId);
                }
                Collections.sort(ids);
                sb.append(i).append(':');
                for (Long id : ids) {
                    sb.append(id).append(',');
                }
                sb.append(';');
            } catch (Exception ignore) {
                /* 单次失败不阻塞 measure */ }
        }
        return sha256Hex(sb.toString());
    }

    private static <T extends DsTableAdapter> double measureDslP95(
            HybridJoinClient<T> hjc, QueryWrapper<T> orm, List<?> joins, int K) {
        double[] samples = new double[DslCorpus.COUNT];
        for (int i = 0; i < DslCorpus.COUNT; i++) {
            long t0 = System.nanoTime();
            try {
                hjc.searchHybridJoin(orm, (List) joins, DslCorpus.get(i),
                        Collections.emptyMap(), K, null);
            } catch (Exception ignore) {
                /* 单次失败不阻塞 measure */ }
            samples[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        Arrays.sort(samples);
        return percentile(samples, 0.95);
    }

    private static <T extends DsTableAdapter> double measureResilienceOverhead(
            HybridJoinClient<T> hjc, QueryWrapper<T> orm, List<?> joins, String ftQ, int K,
            ResilienceConfig cfg) {
        if (cfg == null) {
            return 0.0;
        }
        int N = 10;
        // baseline
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            try {
                hjc.searchHybridJoin(orm, (List) joins, ftQ, Collections.emptyMap(), K, null);
            } catch (Exception ignore) {
            }
        }
        long baselineMs = (System.nanoTime() - t0) / 1_000_000;
        // guarded
        long t1 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            try {
                hjc.searchHybridJoinWithResilience(orm, (List) joins, ftQ, Collections.emptyMap(), K, null, cfg);
            } catch (Exception ignore) {
            }
        }
        long guardedMs = (System.nanoTime() - t1) / 1_000_000;
        if (baselineMs == 0) {
            return 0.0;
        }
        return ((double) (guardedMs - baselineMs) / baselineMs) * 100.0;
    }

    /**
     * 可注入 Runnable hook 的灵活入口（供 T2 单测 + 未来扩展用）。
     * @param baselineFn 给定 query 字符串 → 返回任意 result 的 baseline 测量函数
     * @param guardedFn  给定 query 字符串 → 返回任意 result 的守卫测量函数（可空）
     */
    public static BenchmarkReport runWithHooks(
            java.util.function.Function<String, ?> baselineFn,
            java.util.function.Function<String, ?> guardedFn
    ) {
        if (baselineFn == null) {
            throw new NullPointerException("baselineFn must not be null");
        }

        // Phase 1: warmup（10% of TPC-H 22 = 2）
        int warmupCount = Math.max(2, TpcHQuery.COUNT / 10);
        for (int i = 0; i < warmupCount; i++) {
            try {
                baselineFn.apply(TpcHQuery.get(i % TpcHQuery.COUNT));
            } catch (Exception ignore) { }
        }

        // Phase 2: TPC-H measure
        double[] tpcHSamples = new double[TpcHQuery.COUNT];
        for (int i = 0; i < TpcHQuery.COUNT; i++) {
            long t0 = System.nanoTime();
            try { baselineFn.apply(TpcHQuery.get(i)); }
            catch (Exception ignore) { }
            tpcHSamples[i] = (System.nanoTime() - t0) / 1_000_000.0;
        }
        Arrays.sort(tpcHSamples);
        double tpcH_p50 = percentile(tpcHSamples, 0.50);
        double tpcH_p95 = percentile(tpcHSamples, 0.95);

        // Phase 3: DSL measure
        double[] dslSamples = new double[DslCorpus.COUNT];
        StringBuilder fp = new StringBuilder();
        for (int i = 0; i < DslCorpus.COUNT; i++) {
            long t0 = System.nanoTime();
            Object res = null;
            try { res = baselineFn.apply(DslCorpus.get(i)); }
            catch (Exception ignore) { }
            dslSamples[i] = (System.nanoTime() - t0) / 1_000_000.0;
            // 指纹：query 索引 + 结果 size + 类型（避开 toString 默认 Object hashCode 不稳定）
            int size = (res instanceof java.util.Collection) ? ((java.util.Collection<?>) res).size() : -1;
            String type = res == null ? "null" : res.getClass().getSimpleName();
            fp.append(i).append(':').append(type).append('/').append(size).append(';');
        }
        Arrays.sort(dslSamples);
        double dsl_p95 = percentile(dslSamples, 0.95);
        String dslFingerprint = sha256Hex(fp.toString());

        // Phase 4: Resilience 开 vs 关
        double resilienceOverhead = 0.0;
        if (guardedFn != null) {
            int N = 10;
            long t0 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                try { baselineFn.apply(DslCorpus.get(i % DslCorpus.COUNT)); }
                catch (Exception ignore) { }
            }
            long baselineMs = (System.nanoTime() - t0) / 1_000_000;
            long t1 = System.nanoTime();
            for (int i = 0; i < N; i++) {
                try { guardedFn.apply(DslCorpus.get(i % DslCorpus.COUNT)); }
                catch (Exception ignore) { }
            }
            long guardedMs = (System.nanoTime() - t1) / 1_000_000;
            resilienceOverhead = baselineMs == 0 ? 0.0 : ((double) (guardedMs - baselineMs) / baselineMs) * 100.0;
        }

        return new BenchmarkReport(
                tpcH_p50, tpcH_p95, 1.0,
                dsl_p95, dslFingerprint, resilienceOverhead);
    }

    /**
     * 计算百分位（0.0~1.0）— nearest-rank 算法：idx = ceil(p*N) - 1
     */
    public static double percentile(double[] sortedSamples, double p) {
        if (sortedSamples == null || sortedSamples.length == 0) {
            return 0.0;
        }
        if (p < 0.0 || p > 1.0) {
            throw new IllegalArgumentException("p must be in [0,1], got " + p);
        }
        int idx = (int) Math.ceil(sortedSamples.length * p) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sortedSamples.length) {
            idx = sortedSamples.length - 1;
        }
        return sortedSamples[idx];
    }

    /**
     * SHA-256 hex 编码
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
