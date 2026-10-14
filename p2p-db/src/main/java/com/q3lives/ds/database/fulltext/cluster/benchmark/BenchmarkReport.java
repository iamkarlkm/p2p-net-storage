package com.q3lives.ds.database.fulltext.cluster.benchmark;

/**
 * 不可变 benchmark 报表（P33 OQ1 Hybrid v3 Benchmark 性能基线 MVP）。
 *
 * <p>
 * 5 final 字段 + 4 getter + 1 isAcceptable 阈值判断：
 * <ul>
 * <li>{@code tpcH_p50_ms}：TPC-H Q1-Q22 P50（单 JVM 4 shard TCP）</li>
 * <li>{@code tpcH_p95_ms}：TPC-H Q1-Q22 P95</li>
 * <li>{@code tpcH_recall_at_10}：Recall@10（0.0~1.0）</li>
 * <li>{@code dsl_p95_ms}：DSL 100 条随机 P95</li>
 * <li>{@code dsl_fingerprint}：SHA-256 of 100×topK docId ordered set</li>
 * <li>{@code resilience_overhead_pct}：守卫开 vs 关 P50 偏差%</li>
 * </ul>
 *
 * <p>
 * 验收阈值（hard fail = 不达标）：
 * <ul>
 * <li>TPC-H P50 ≤ 500ms</li>
 * <li>TPC-H P95 ≤ 2000ms</li>
 * <li>TPC-H Recall@10 ≥ 0.85</li>
 * <li>DSL P95 ≤ 1000ms</li>
 * <li>Resilience 偏差 ≤ 10%</li>
 * </ul>
 *
 * <p>
 * 红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 benchmark 子包）。
 */
public final class BenchmarkReport {

    /**
     * TPC-H Q1-Q22 P50（ms）
     */
    public final double tpcH_p50_ms;
    /**
     * TPC-H Q1-Q22 P95（ms）
     */
    public final double tpcH_p95_ms;
    /**
     * TPC-H Recall@10（0.0~1.0）
     */
    public final double tpcH_recall_at_10;
    /**
     * DSL 100 条随机 P95（ms）
     */
    public final double dsl_p95_ms;
    /**
     * DSL 100 条指纹 SHA-256（跨 run 一致）
     */
    public final String dsl_fingerprint;
    /**
     * Resilience 守卫开 vs 关 P50 偏差%（正数=守卫更慢）
     */
    public final double resilience_overhead_pct;

    private static final double TPC_H_P50_THRESHOLD = 500.0;
    private static final double TPC_H_P95_THRESHOLD = 2000.0;
    private static final double TPC_H_RECALL_THRESHOLD = 0.85;
    private static final double DSL_P95_THRESHOLD = 1000.0;
    private static final double RESILIENCE_OVERHEAD_THRESHOLD = 10.0;

    public BenchmarkReport(
            double tpcH_p50_ms,
            double tpcH_p95_ms,
            double tpcH_recall_at_10,
            double dsl_p95_ms,
            String dsl_fingerprint,
            double resilience_overhead_pct
    ) {
        if (tpcH_p50_ms < 0) {
            throw new IllegalArgumentException("tpcH_p50_ms must be >= 0, got " + tpcH_p50_ms);
        }
        if (tpcH_p95_ms < 0) {
            throw new IllegalArgumentException("tpcH_p95_ms must be >= 0, got " + tpcH_p95_ms);
        }
        if (tpcH_p95_ms < tpcH_p50_ms) {
            throw new IllegalArgumentException(
                    "tpcH_p95_ms (" + tpcH_p95_ms + ") must be >= tpcH_p50_ms (" + tpcH_p50_ms + ")");
        }
        if (tpcH_recall_at_10 < 0.0 || tpcH_recall_at_10 > 1.0) {
            throw new IllegalArgumentException("tpcH_recall_at_10 must be in [0,1], got " + tpcH_recall_at_10);
        }
        if (dsl_p95_ms < 0) {
            throw new IllegalArgumentException("dsl_p95_ms must be >= 0, got " + dsl_p95_ms);
        }
        if (dsl_fingerprint == null) {
            throw new NullPointerException("dsl_fingerprint must not be null");
        }
        if (dsl_fingerprint.length() != 64) {
            throw new IllegalArgumentException(
                    "dsl_fingerprint must be 64-char hex (SHA-256), got length " + dsl_fingerprint.length());
        }
        this.tpcH_p50_ms = tpcH_p50_ms;
        this.tpcH_p95_ms = tpcH_p95_ms;
        this.tpcH_recall_at_10 = tpcH_recall_at_10;
        this.dsl_p95_ms = dsl_p95_ms;
        this.dsl_fingerprint = dsl_fingerprint;
        this.resilience_overhead_pct = resilience_overhead_pct;
    }

    public double getTpcHP50Ms() {
        return tpcH_p50_ms;
    }

    public double getTpcHP95Ms() {
        return tpcH_p95_ms;
    }

    public double getTpcHRecallAt10() {
        return tpcH_recall_at_10;
    }

    public double getDslP95Ms() {
        return dsl_p95_ms;
    }

    public String getDslFingerprint() {
        return dsl_fingerprint;
    }

    public double getResilienceOverheadPct() {
        return resilience_overhead_pct;
    }

    /**
     * 是否满足 P33 验收阈值（hard fail = 不达标）
     */
    public boolean isAcceptable() {
        return tpcH_p50_ms <= TPC_H_P50_THRESHOLD
                && tpcH_p95_ms <= TPC_H_P95_THRESHOLD
                && tpcH_recall_at_10 >= TPC_H_RECALL_THRESHOLD
                && dsl_p95_ms <= DSL_P95_THRESHOLD
                && Math.abs(resilience_overhead_pct) <= RESILIENCE_OVERHEAD_THRESHOLD;
    }

    @Override
    public String toString() {
        return "BenchmarkReport{"
                + "tpcH_p50_ms=" + tpcH_p50_ms
                + ", tpcH_p95_ms=" + tpcH_p95_ms
                + ", tpcH_recall_at_10=" + tpcH_recall_at_10
                + ", dsl_p95_ms=" + dsl_p95_ms
                + ", dsl_fingerprint=" + dsl_fingerprint.substring(0, Math.min(16, dsl_fingerprint.length())) + "..."
                + ", resilience_overhead_pct=" + resilience_overhead_pct
                + ", acceptable=" + isAcceptable()
                + "}";
    }
}
