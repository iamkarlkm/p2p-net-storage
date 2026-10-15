package com.q3lives.ds.database.fulltext.cluster.join.v2;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * HybridJoinClientV2Adapter 静态工具类（P35 OQ1 ORM 多表 Join v2 DsManyToOne MVP）。
 *
 * <p>
 * 对 P31 {@link HybridJoinClient} 做 0 改扩展：append-only 新增 4 入口 + J=2 树级联调度。 复用
 * HJC 原 6 入口的 ORM/FT/合查/Raw/Resilience 链路。
 *
 * <p>
 * 4 新入口：
 * <ol>
 * <li>{@link #searchHybridJoinWithManyToOne}：J=1 N:1 聚合 + HybridHit.joinFetch 携
 * List&lt;R&gt;</li>
 * <li>{@link #searchHybridJoinWithManyToOneAndWeights}：加 P29 加权打分</li>
 * <li>{@link #searchHybridJoinWithManyToOneAndLtr}：加 P29 LTR 线性 MVP</li>
 * <li>{@link #searchHybridJoinWithManyToOneTree}：J=2 树级联</li>
 * </ol>
 *
 * <p>
 * 硬约束：P31 HybridJoinClient 6 入口 1 字不改；HybridHit ctor=2 不改。
 *
 * @author SP3.1 Implement Agent
 * @since P35 OQ1 ORM Join v2 MVP
 */
public final class HybridJoinClientV2Adapter {

    private HybridJoinClientV2Adapter() {
    }

    /**
     * J=1 N:1 聚合合查。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> List<HybridHit<T>> searchHybridJoinWithManyToOne(
            HybridJoinClient<T> hjc,
            QueryWrapper<T> orm,
            JoinSpec<?> join,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            GenericManager<T> mainGm,
            Map<Class<?>, GenericManager<?>> relatedGms) throws IOException {
        validateManyToOne(join);
        Set<Long> t1Ids = collectMainIds(mainGm, orm);
        if (t1Ids.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> matched = JoinIdResolverV2.resolveManyToOne(t1Ids, join, relatedGms);
        if (matched.isEmpty()) {
            return new ArrayList<>();
        }
        List<HybridHit<T>> hits = hjc.searchHybridJoin(orm, Collections.singletonList(join), ftQ, raw, K, opt);
        List<HybridHit<T>> filtered = filterByMatched(hits, matched);
        attachManyToOneFetch(filtered, matched, join, relatedGms);
        return filtered;
    }

    /**
     * J=1 N:1 聚合合查（加 P29 加权打分）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> List<HybridHit<T>> searchHybridJoinWithManyToOneAndWeights(
            HybridJoinClient<T> hjc,
            QueryWrapper<T> orm,
            JoinSpec<?> join,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            double alpha,
            double beta,
            Map<String, Map.Entry<Double, ToDoubleFunction<T>>> colW,
            GenericManager<T> mainGm,
            Map<Class<?>, GenericManager<?>> relatedGms) throws IOException {
        validateManyToOne(join);
        Set<Long> t1Ids = collectMainIds(mainGm, orm);
        if (t1Ids.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> matched = JoinIdResolverV2.resolveManyToOne(t1Ids, join, relatedGms);
        if (matched.isEmpty()) {
            return new ArrayList<>();
        }
        List<HybridHit<T>> hits = hjc.searchHybridJoinWithWeights(orm, Collections.singletonList(join),
                ftQ, raw, K, opt, alpha, beta, colW);
        List<HybridHit<T>> filtered = filterByMatched(hits, matched);
        attachManyToOneFetch(filtered, matched, join, relatedGms);
        return filtered;
    }

    /**
     * J=1 N:1 聚合合查（加 P29 LTR 线性 MVP）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> List<HybridHit<T>> searchHybridJoinWithManyToOneAndLtr(
            HybridJoinClient<T> hjc,
            QueryWrapper<T> orm,
            JoinSpec<?> join,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            List<ToDoubleFunction<T>> extractors,
            double[] ltrModelWeights,
            GenericManager<T> mainGm,
            Map<Class<?>, GenericManager<?>> relatedGms) throws IOException {
        validateManyToOne(join);
        Set<Long> t1Ids = collectMainIds(mainGm, orm);
        if (t1Ids.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> matched = JoinIdResolverV2.resolveManyToOne(t1Ids, join, relatedGms);
        if (matched.isEmpty()) {
            return new ArrayList<>();
        }
        List<HybridHit<T>> hits = hjc.searchHybridJoinWithLtr(orm, Collections.singletonList(join),
                ftQ, raw, K, opt, extractors, ltrModelWeights);
        List<HybridHit<T>> filtered = filterByMatched(hits, matched);
        attachManyToOneFetch(filtered, matched, join, relatedGms);
        return filtered;
    }

    /**
     * J=2 树级联合查：(T1 → R1 → R2) 全 MANY_TO_ONE。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> List<HybridHit<T>> searchHybridJoinWithManyToOneTree(
            HybridJoinClient<T> hjc,
            QueryWrapper<T> orm,
            JoinSpec<?> join1,
            JoinSpec<?> join2,
            String ftQ,
            Map<Long, String> raw,
            int K,
            SearchOptions opt,
            GenericManager<T> mainGm,
            Map<Class<?>, GenericManager<?>> relatedGms) throws IOException {
        validateManyToOne(join1);
        validateManyToOne(join2);
        Set<Long> matched = JoinIdResolverV2.resolveManyToOneTree(mainGm, orm, join1, join2, relatedGms);
        if (matched.isEmpty()) {
            return new ArrayList<>();
        }
        List<HybridHit<T>> hits = hjc.searchHybridJoin(orm, Collections.singletonList(join1), ftQ, raw, K, opt);
        List<HybridHit<T>> filtered = filterByMatched(hits, matched);
        if (filtered.isEmpty()) {
            return filtered;
        }
        // R1 聚合
        GenericManager rg1 = relatedGms.get(join1.relatedClass);
        ManyToOneJoinResult r1Res = JoinIdResolverV2.aggregateByOuterId(matched, join1, rg1);
        // R2 聚合（基于 R1_ids 候选）
        Set<Long> r1Matched = r1Res.matchedOuterIds;
        GenericManager rg2 = relatedGms.get(join2.relatedClass);
        ManyToOneJoinResult r2Res = JoinIdResolverV2.aggregateByOuterId(r1Matched, join2, rg2);
        for (HybridHit<T> h : filtered) {
            Map<Class<?>, Object> m = new java.util.HashMap<>();
            m.put(join1.relatedClass, r1Res);
            m.put(join2.relatedClass, r2Res);
            putJoinFetch(h, m);
        }
        return filtered;
    }

    /* ---------------- private helpers ---------------- */
    private static volatile Object UNSAFE;
    private static volatile long JF_OFFSET = -1L;

    private static synchronized long jfOffset() {
        if (JF_OFFSET >= 0L) {
            return JF_OFFSET;
        }
        try {
            Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = theUnsafe.get(null);
            Field jf = HybridHit.class.getDeclaredField("joinFetch");
            jf.setAccessible(true);
            JF_OFFSET = (Long) UNSAFE.getClass().getMethod("objectFieldOffset", Field.class).invoke(UNSAFE, jf);
        } catch (Throwable t) {
            UNSAFE = null;
            JF_OFFSET = -1L;
        }
        return JF_OFFSET;
    }

    private static void putJoinFetch(HybridHit<?> h, Map<Class<?>, Object> m) {
        if (h == null || m == null || m.isEmpty()) {
            return;
        }
        long off = jfOffset();
        if (UNSAFE == null || off < 0L) {
            return;
        }
        try {
            UNSAFE.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                    .invoke(UNSAFE, h, off, Collections.unmodifiableMap(m));
        } catch (Throwable ignore) {
        }
    }

    private static void validateManyToOne(JoinSpec<?> join) {
        if (join == null) {
            throw new IllegalArgumentException("join cannot be null");
        }
        if (join.mode != JoinMode.MANY_TO_ONE) {
            throw new IllegalArgumentException(
                    "HybridJoinClientV2Adapter 入口仅支持 mode=MANY_TO_ONE, got " + join.mode);
        }
        if (join.batchSize < 1 || join.batchSize > JoinSpec.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize 越界: " + join.batchSize);
        }
    }

    private static <T extends DsTableAdapter> Set<Long> collectMainIds(
            GenericManager<T> mainGm, QueryWrapper<T> orm) {
        QueryWrapper<T> ormCopy = (orm == null) ? new QueryWrapper<>() : JoinSpec.QwHelper.defensiveCopy(orm);
        List<Long> rawIds = mainGm.listIds(ormCopy);
        Set<Long> t1Ids = new HashSet<>();
        for (Long id : rawIds) {
            if (id != null && id != 0L) {
                t1Ids.add(id);
            }
        }
        return t1Ids;
    }

    private static <T extends DsTableAdapter> List<HybridHit<T>> filterByMatched(
            List<HybridHit<T>> hits, Set<Long> matched) {
        List<HybridHit<T>> out = new ArrayList<>();
        for (HybridHit<T> h : hits) {
            if (h != null && matched.contains(h.docId)) {
                out.add(h);
            }
        }
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends DsTableAdapter> void attachManyToOneFetch(
            List<HybridHit<T>> hits, Set<Long> matched, JoinSpec<?> join,
            Map<Class<?>, GenericManager<?>> relatedGms) {
        if (hits.isEmpty()) {
            return;
        }
        GenericManager rg = relatedGms.get(join.relatedClass);
        ManyToOneJoinResult manyRes = JoinIdResolverV2.aggregateByOuterId(matched, join, rg);
        for (HybridHit<T> h : hits) {
            Map<Class<?>, Object> m = new java.util.HashMap<>();
            m.put(join.relatedClass, manyRes);
            putJoinFetch(h, m);
        }
    }
}
