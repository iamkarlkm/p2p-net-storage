package com.q3lives.ds.database.fulltext.cluster.join;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P31 FR-3 HybridJoinClient 合查 Facade 三重载（1:1 等值 Join 过滤 + DHQC 原三入口 100% 委托）。
 *
 * <p>三重载严格对应 P28/P29/P30 DHQC 原三公开签名（Facade 单调不减，0 删改红线）：
 * ① searchHybridJoin：5 参，对应 DHQC.searchHybrid（基础 ORM + FT，无权重无 LTR）；
 * ② searchHybridJoinWithWeights：8 参，对应 DHQC.searchHybridWithWeights（alpha/beta/columnWeights 线性融合）；
 * ③ searchHybridJoinWithLtr：7 参，对应 DHQC.searchHybridWithLtr（extractors + LTR weights 重新打分）。
 *
 * <p>统一 Flow 6 步（三入口完全一致，参数仅透传）：
 * 1. preValidateCommon：ftQ non-null/non-blank、rawDocTexts non-null、K≥1 → 4 种 IAE/NPE Fail-First；
 * 2. validateJoinsSafe：joins non-null + 元素 0 null + relatedGms 全包含每个 JoinSpec.relatedClass；
 * 3. JoinIdResolver.resolve：outerIds 交集候选（AND 多 Join retainAll 级联，Fail-First 5 IAE）；
 * 4. defensiveCopyOrNew：深拷贝主表 orm 或 new QueryWrapper（保持 delegate 调用侧参数 pristine）；
 * 5. delegate.* 原入口：把 ORM/FT/K/权重/LTR 原封转给 DHQC 红线类（0 line 改 QW/GM/DHQC）；
 * 6. postFilterJoin + fillJoinFetch：按 outerIds.contains(docId) 裁剪 (补偿 transient id 无 @DsField)
 *    → 再对每 Class 从 relatedGM 批量 in(relatedField, hitDocIds) 查取 FromRow → sun.misc.Unsafe
 *    反射赋写 HybridHit.final joinFetch Map（保持 constructors.length==2 红线，0 新增第 3 构造器）。
 *
 * <p>红线钉死：① ZeroLeak3=3 → 本类是 join 子包 3 top-level 之 3；② NG-3 → 本类源码导入
 * 清单无 netty/gRPC/p2p.client 根包；③ TOPK bucket sum=0 → 不引用 access counter
 * 聚合类；④ Join Key 仅等值 DsIdLayout rowId（不追加 tableId 位）。
 *
 * @param <T> 主表实体类型（必须 DsTableAdapter 子类，DHQC.entityClass == mainGm.persistentClass）
 * @author SP3.1 Implement Agent
 * @since P31 OQ12-⑨ ORM 多表 Join + FT 合查 MVP v1.0（用户单选 Scope ① 批准；Spec/Tasks Gate A 双签批）
 */
public final class HybridJoinClient<T extends DsTableAdapter> implements AutoCloseable {

    public final DsHybridQueryClient<T> delegate;
    public final Map<Class<?>, GenericManager<?>> relatedGms;
    public final GenericManager<T> mainGm;

    public HybridJoinClient(
            DsHybridQueryClient<T> delegate,
            Map<Class<?>, GenericManager<?>> relatedGms,
            GenericManager<T> mainGm
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate DsHybridQueryClient cannot be null");
        this.relatedGms = Objects.requireNonNull(relatedGms, "relatedGms cannot be null");
        this.mainGm = Objects.requireNonNull(mainGm, "mainGm cannot be null");
        Class<T> ec = delegate.getEntityClass();
        if (ec != null && !ec.getName().equals(mainGm.getPersistentClass().getName())) {
            throw new IllegalArgumentException(
                    "HybridJoinClient mainGm.persistentClass=" + mainGm.getPersistentClass().getName()
                            + " mismatch delegate.entityClass=" + ec.getName());
        }
    }

    public List<HybridHit<T>> searchHybridJoin(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt
    ) throws IOException {
        preValidateCommon(ftQ, raw, K);
        validateJoinsSafe(joins, relatedGms);
        Set<Long> outerIds = JoinIdResolver.resolve(mainGm, orm, joins, relatedGms, null);
        QueryWrapper<T> ormCopy = defensiveCopyOrNew(orm);
        List<HybridHit<T>> hits = delegate.searchHybrid(ormCopy, ftQ, raw, K, opt);
        hits = postFilterJoin(hits, outerIds, K);
        fillJoinFetch(hits, joins, relatedGms, null);
        return hits;
    }

    public List<HybridHit<T>> searchHybridJoinWithWeights(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            double alpha,
            double beta,
            Map<String, Map.Entry<Double, ToDoubleFunction<T>>> colW
    ) throws IOException {
        preValidateCommon(ftQ, raw, K);
        validateAlphaBetaColW(alpha, beta, colW);
        validateJoinsSafe(joins, relatedGms);
        Set<Long> outerIds = JoinIdResolver.resolve(mainGm, orm, joins, relatedGms, null);
        QueryWrapper<T> ormCopy = defensiveCopyOrNew(orm);
        List<HybridHit<T>> hits = delegate.searchHybridWithWeights(ormCopy, ftQ, raw, K, opt, alpha, beta, colW);
        hits = postFilterJoin(hits, outerIds, K);
        fillJoinFetch(hits, joins, relatedGms, null);
        return hits;
    }

    public List<HybridHit<T>> searchHybridJoinWithLtr(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            List<ToDoubleFunction<T>> extractors,
            double[] ltrModelWeights
    ) throws IOException {
        preValidateCommon(ftQ, raw, K);
        validateExtractorsWeights(extractors, ltrModelWeights);
        validateJoinsSafe(joins, relatedGms);
        Set<Long> outerIds = JoinIdResolver.resolve(mainGm, orm, joins, relatedGms, null);
        QueryWrapper<T> ormCopy = defensiveCopyOrNew(orm);
        List<HybridHit<T>> hits = delegate.searchHybridWithLtr(ormCopy, ftQ, raw, K, opt, extractors, ltrModelWeights);
        hits = postFilterJoin(hits, outerIds, K);
        fillJoinFetch(hits, joins, relatedGms, null);
        return hits;
    }

    private QueryWrapper<T> defensiveCopyOrNew(QueryWrapper<T> orm) {
        return (orm == null) ? new QueryWrapper<>() : JoinSpec.QwHelper.defensiveCopy(orm);
    }

    private static <T extends DsTableAdapter> List<HybridHit<T>> postFilterJoin(
            List<HybridHit<T>> hits, Set<Long> outerIds, int K
    ) {
        if (hits == null || hits.isEmpty()) return (hits == null) ? Collections.emptyList() : hits;
        if (outerIds == null || outerIds.isEmpty()) return Collections.emptyList();
        List<HybridHit<T>> out = new ArrayList<>(Math.min(K, hits.size()));
        for (HybridHit<T> h : hits) {
            if (h == null || !outerIds.contains(h.docId)) continue;
            out.add(h);
            if (out.size() >= K) break;
        }
        return out;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    static <T extends DsTableAdapter> void fillJoinFetch(
            List<HybridHit<T>> hits,
            List<JoinSpec<?>> joins,
            Map<Class<?>, GenericManager<?>> relatedGms,
            Map<String, int[]> probeCounters
    ) {
        if (hits == null || hits.isEmpty() || joins == null || joins.isEmpty()) return;
        List<Long> hitDocIds = new ArrayList<>(hits.size());
        for (HybridHit<T> h : hits) if (h != null && h.docId != 0L) hitDocIds.add(h.docId);
        if (hitDocIds.isEmpty()) return;
        int fetchCalls = 0;
        List<JoinSpec<?>> safeJoins = new ArrayList<>(joins.size());
        for (JoinSpec<?> j : joins) {
            if (j == null || j.relatedClass == null) continue;
            if (!relatedGms.containsKey(j.relatedClass)) continue;
            safeJoins.add(j);
        }
        Map<JoinSpec<?>, Map<Long, ? extends DsTableAdapter>> relByOuter = new HashMap<>();
        for (JoinSpec<?> jc : safeJoins) {
            JoinSpec j = jc;
            GenericManager rg = relatedGms.get(j.relatedClass);
            if (rg == null) continue;
            Field rf;
            try {
                rf = j.relatedClass.getDeclaredField(j.relatedField);
                rf.setAccessible(true);
            } catch (NoSuchFieldException nsf) {
                continue;
            }
            QueryWrapper condBase = (j.relatedCond != null)
                    ? JoinSpec.QwHelper.defensiveCopy(j.relatedCond)
                    : new QueryWrapper();
            condBase.in(j.relatedField, hitDocIds);
            List rows = (List) rg.list(condBase);
            fetchCalls += 1;
            Map<Long, DsTableAdapter> m = new HashMap<>(rows.size() + 2, 0.9f);
            for (Object r : rows) {
                if (!(r instanceof DsTableAdapter ra)) continue;
                try {
                    Object v = rf.get(ra);
                    if (v instanceof Long l && l != 0L) {
                        m.putIfAbsent(l, ra);
                    }
                } catch (IllegalAccessException ignore) { }
            }
            relByOuter.put(j, m);
        }
        Object unsafe = null;
        long jfOff = -1L;
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            Field jf = HybridHit.class.getDeclaredField("joinFetch");
            jf.setAccessible(true);
            jfOff = (Long) unsafe.getClass().getMethod("objectFieldOffset", Field.class).invoke(unsafe, jf);
        } catch (Throwable ignore) { unsafe = null; jfOff = -1L; }
        for (HybridHit<T> h : hits) {
            if (h == null) continue;
            Map<Class<?>, Object> built = null;
            for (int ji = 0, jn = safeJoins.size(); ji < jn; ji++) {
                JoinSpec<?> j = safeJoins.get(ji);
                Map<Long, ? extends DsTableAdapter> rm = relByOuter.get(j);
                if (rm == null) continue;
                DsTableAdapter r = rm.get(h.docId);
                if (r == null) continue;
                if (built == null) built = new HashMap<>(4, 0.9f);
                built.put(j.relatedClass, r);
            }
            if (built == null || built.isEmpty()) continue;
            if (unsafe != null && jfOff >= 0) {
                try {
                    unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                            .invoke(unsafe, h, jfOff, Collections.unmodifiableMap(built));
                } catch (Throwable ignore) { }
            }
        }
        if (probeCounters != null) {
            probeCounters.putIfAbsent("joinFetchCalls", new int[]{0});
            probeCounters.get("joinFetchCalls")[0] += fetchCalls;
        }
    }

    private static void preValidateCommon(String ftQ, Map<?, ?> raw, int K) {
        Objects.requireNonNull(ftQ, "fulltextQuery cannot be null");
        if (ftQ.isBlank()) throw new IllegalArgumentException("fulltextQuery cannot be blank");
        Objects.requireNonNull(raw, "rawDocTextsByDocId cannot be null");
        if (K <= 0) throw new IllegalArgumentException("K must >=1, got " + K);
    }

    private static <T extends DsTableAdapter> void validateJoinsSafe(
            List<JoinSpec<?>> joins,
            Map<Class<?>, GenericManager<?>> relatedGms
    ) {
        if (joins == null || joins.isEmpty()) return;
        for (JoinSpec<?> j : joins) {
            if (j == null) throw new IllegalArgumentException("joins list has null element");
            Class<?> rc = j.relatedClass;
            if (!relatedGms.containsKey(rc)) {
                throw new IllegalArgumentException(
                        "missing GenericManager for JoinSpec.relatedClass=" + rc.getName()
                                + "; please register in relatedGms map");
            }
            if (relatedGms.get(rc) == null) {
                throw new IllegalArgumentException(
                        "GenericManager for JoinSpec.relatedClass=" + rc.getName() + " is null");
            }
        }
    }

    private static <T> void validateAlphaBetaColW(
            double alpha, double beta, Map<String, Map.Entry<Double, ToDoubleFunction<T>>> colW
    ) {
        if (alpha < 0 || alpha > 100) {
            throw new IllegalArgumentException("alpha out [0,100], got " + alpha);
        }
        if (beta < 0 || beta > 100) {
            throw new IllegalArgumentException("beta out [0,100], got " + beta);
        }
        Objects.requireNonNull(colW, "columnWeights cannot be null");
        for (Map.Entry<String, Map.Entry<Double, ToDoubleFunction<T>>> e : colW.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                throw new IllegalArgumentException("columnWeights key blank/null: " + k);
            }
            Map.Entry<Double, ToDoubleFunction<T>> pair = e.getValue();
            if (pair == null) throw new NullPointerException("columnWeights[" + k + "] pair null");
            Double w = pair.getKey();
            if (w == null) throw new NullPointerException("columnWeights[" + k + "].weight null");
            if (w < 0 || w > 100) {
                throw new IllegalArgumentException("columnWeights[" + k + "].weight out [0,100]: " + w);
            }
            if (pair.getValue() == null) {
                throw new NullPointerException("columnWeights[" + k + "].extractor null");
            }
        }
    }

    private static <T> void validateExtractorsWeights(
            List<ToDoubleFunction<T>> extractors, double[] ltrModelWeights
    ) {
        Objects.requireNonNull(extractors, "featureExtractors cannot be null");
        if (extractors.isEmpty()) {
            throw new IllegalArgumentException("featureExtractors cannot be empty");
        }
        for (int i = 0; i < extractors.size(); i++) {
            if (extractors.get(i) == null) {
                throw new NullPointerException("featureExtractors[" + i + "] is null");
            }
        }
        Objects.requireNonNull(ltrModelWeights, "ltrModelWeights cannot be null");
        if (ltrModelWeights.length == 0) {
            throw new IllegalArgumentException("ltrModelWeights cannot be empty");
        }
        if (extractors.size() != ltrModelWeights.length) {
            throw new IllegalArgumentException(
                    "featureExtractors.size != ltrModelWeights.length: " +
                            extractors.size() + " vs " + ltrModelWeights.length);
        }
    }

    @Override
    public void close() {
        try { if (delegate != null) delegate.close(); } catch (Exception ignore) { }
    }

    // ============== P32: 3 个守卫重载（append-only, 不删改原 3 入口） ==============

    /**
     * P32 Resilience 守卫重载：对应 {@link #searchHybridJoin}，外层包 4 层守卫链。
     * 链顺序：RateLimiter → CircuitBreaker → Retry → Timeout → 原方法。
     */
    public List<HybridHit<T>> searchHybridJoinWithResilience(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig cfg
    ) throws IOException {
        return applyGuards(() -> searchHybridJoin(orm, joins, ftQ, raw, K, opt), cfg);
    }

    /**
     * P32 Resilience 守卫重载：对应 {@link #searchHybridJoinWithWeights}。
     */
    public List<HybridHit<T>> searchHybridJoinWithWeightsWithResilience(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            double alpha, double beta,
            Map<String, Map.Entry<Double, ToDoubleFunction<T>>> colW,
            com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig cfg
    ) throws IOException {
        return applyGuards(() -> searchHybridJoinWithWeights(orm, joins, ftQ, raw, K, opt, alpha, beta, colW), cfg);
    }

    /**
     * P32 Resilience 守卫重载：对应 {@link #searchHybridJoinWithLtr}。
     */
    public List<HybridHit<T>> searchHybridJoinWithLtrWithResilience(
            QueryWrapper<T> orm,
            List<JoinSpec<?>> joins,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            java.util.List<ToDoubleFunction<T>> extractors,
            double[] ltrModelWeights,
            com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig cfg
    ) throws IOException {
        return applyGuards(() -> searchHybridJoinWithLtr(orm, joins, ftQ, raw, K, opt, extractors, ltrModelWeights), cfg);
    }

    /**
     * 4 层守卫链统一编排。RateLimit/CircuitOpen 异常向上层直接抛（不计入 CB），
     * Retry/Timeout 异常由 RetryGuard 包装。
     */
    private List<HybridHit<T>> applyGuards(
            java.util.concurrent.Callable<List<HybridHit<T>>> body,
            com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig cfg
    ) throws IOException {
        if (cfg == null) {
            throw new IllegalArgumentException("ResilienceConfig cannot be null");
        }
        com.q3lives.ds.database.fulltext.cluster.resilience.RateLimiterGuard rl =
                new com.q3lives.ds.database.fulltext.cluster.resilience.RateLimiterGuard(cfg.getQps(), cfg.getBurst());
        com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard cb =
                new com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard(
                        cfg.getFailureRateThreshold(), cfg.getSlidingWindowSize(), cfg.getOpenDurationMs());
        com.q3lives.ds.database.fulltext.cluster.resilience.RetryGuard rt =
                new com.q3lives.ds.database.fulltext.cluster.resilience.RetryGuard(
                        cfg.getMaxRetries(), cfg.getBaseDelayMs(),
                        com.q3lives.ds.database.fulltext.cluster.resilience.RetryGuard.defaultRetryOn());
        com.q3lives.ds.database.fulltext.cluster.resilience.TimeoutGuard tg =
                new com.q3lives.ds.database.fulltext.cluster.resilience.TimeoutGuard(cfg.getTimeoutMs());
        try {
            return rl.wrap(() -> cb.wrap(() -> rt.wrap(() -> tg.wrap(() -> {
                try { return body.call(); }
                catch (IOException ioe) { throw ioe; }
                catch (RuntimeException re) { throw re; }
                catch (Exception e) { throw new RuntimeException(e); }
            }))));
        } catch (IOException ioe) {
            throw ioe;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            tg.shutdown();
        }
    }
}
