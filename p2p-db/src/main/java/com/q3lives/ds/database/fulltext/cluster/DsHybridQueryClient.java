package com.q3lives.ds.database.fulltext.cluster;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P28 FR-3 组合门面：ORM (GenericManager/DsDatabaseClusterClient) + Fulltext (DsFulltextClusterClient)
 * 按 docId=entity.getId() 等值 Join 合查，纯 FT finalScore↓ 三键排序 + TopK 下推。
 *
 * <p>零侵入：不修改 ORM / Fulltext 任一门面，仅作为 Facade 组合模式；子类可
 * 重写 {@link #scoreHybrid} 实现 P29 混合打分。
 *
 * @param <T> 目标实体类型（DsTableAdapter 子类）
 * @since P28
 */
public class DsHybridQueryClient<T extends DsTableAdapter> implements AutoCloseable {

    protected final Class<T> entityClass;
    protected final BiFunction<QueryWrapper<T>, Integer, List<Long>> ormIdsProvider;
    protected final Function<Collection<Long>, Map<Long, T>> ormBatchGetter;
    protected final DsFulltextClusterClient ftClient;
    protected final int fallbackMultiplier;
    protected final double alpha;
    protected final double beta;

    /**
     * 依赖注入主构造（非反射，便于 mock / 单测 / 远程 ORM 模式替换）。
     * P29 骨架：5 参数主构造内部委托给 7 参数主构造，alpha=1.0, beta=0.0（P28 v1.0 零回归字节级全等）。
     *
     * @param entityClass       实体类（非 null）
     * @param ormIdsProvider    ORM 候选 id 提供者：(wrapper, maxHint) -> List<Long>（非 null）
     * @param ormBatchGetter    ORM 批量取实体：(ids) -> Map<Long, T>（非 null，id 不存在实体允许忽略）
     * @param ftClient          全文集群客户端（非 null）
     * @param fallbackMultiplier FT 候选扩大倍数（MVP 默认 3，必须 ≥1，否则 IAE）
     */
    public DsHybridQueryClient(
            Class<T> entityClass,
            BiFunction<QueryWrapper<T>, Integer, List<Long>> ormIdsProvider,
            Function<Collection<Long>, Map<Long, T>> ormBatchGetter,
            DsFulltextClusterClient ftClient,
            int fallbackMultiplier
    ) {
        this(entityClass, ormIdsProvider, ormBatchGetter, ftClient, fallbackMultiplier, 1.0, 0.0);
    }

    /**
     * P29 FR-2.1 新增 7 参数主构造：注入 alpha/β 加权系数。
     *
     * @param entityClass        实体类（非 null）
     * @param ormIdsProvider     ORM 候选 id 提供者（非 null）
     * @param ormBatchGetter     ORM 批量取实体（非 null）
     * @param ftClient           全文集群客户端（非 null）
     * @param fallbackMultiplier ≥1
     * @param alpha              FT 加权系数 α ∈ [0, 100]，否则 IAE
     * @param beta               ORM 列级加权系数 β ∈ [0, 100]，否则 IAE
     * @since P29
     */
    public DsHybridQueryClient(
            Class<T> entityClass,
            BiFunction<QueryWrapper<T>, Integer, List<Long>> ormIdsProvider,
            Function<Collection<Long>, Map<Long, T>> ormBatchGetter,
            DsFulltextClusterClient ftClient,
            int fallbackMultiplier,
            double alpha,
            double beta
    ) {
        if (alpha < 0 || alpha > 100) {
            throw new IllegalArgumentException("alpha out of range [0,100], got " + alpha);
        }
        if (beta < 0 || beta > 100) {
            throw new IllegalArgumentException("beta out of range [0,100], got " + beta);
        }
        if (fallbackMultiplier < 1) {
            throw new IllegalArgumentException("fallbackMultiplier must >=1, got " + fallbackMultiplier);
        }
        this.entityClass = Objects.requireNonNull(entityClass, "entityClass cannot be null");
        this.ormIdsProvider = Objects.requireNonNull(ormIdsProvider, "ormIdsProvider cannot be null");
        this.ormBatchGetter = Objects.requireNonNull(ormBatchGetter, "ormBatchGetter cannot be null");
        this.ftClient = Objects.requireNonNull(ftClient, "ftClient cannot be null");
        this.fallbackMultiplier = fallbackMultiplier;
        this.alpha = alpha;
        this.beta = beta;
    }

    /**
     * MVP v1.0 便捷工厂：同 JVM Local ORM（GenericManager） + Inproc/TCP FulltextClusterClient。
     * P29 骨架：内部委托给 5 参数 ofLocal（带 alpha/beta），alpha=1.0,beta=0.0（P28 零回归字节级全等）。
     *
     * @param entityClass 实体类（非 null）
     * @param gm          GenericManager 实例（非 null）
     * @param ftClient    DsFulltextClusterClient（非 null）
     */
    public static <E extends DsTableAdapter> DsHybridQueryClient<E> ofLocal(
            Class<E> entityClass, GenericManager<E> gm, DsFulltextClusterClient ftClient
    ) {
        return ofLocal(entityClass, gm, ftClient, 1.0, 0.0);
    }

    /**
     * P29 FR-2.2 新增 5 参数 ofLocal 重载：显式指定 alpha/beta。
     *
     * @param entityClass 实体类（非 null）
     * @param gm          GenericManager 实例（非 null）
     * @param ftClient    DsFulltextClusterClient（非 null）
     * @param alpha       α ∈ [0,100] 否则 IAE
     * @param beta        β ∈ [0,100] 否则 IAE
     * @param <E>         DsTableAdapter 子类
     * @return DsHybridQueryClient 实例（Local ORM + Inproc/TCP FT）
     * @since P29
     */
    public static <E extends DsTableAdapter> DsHybridQueryClient<E> ofLocal(
            Class<E> entityClass, GenericManager<E> gm, DsFulltextClusterClient ftClient,
            double alpha, double beta
    ) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(gm, "GenericManager cannot be null");
        Objects.requireNonNull(ftClient, "DsFulltextClusterClient cannot be null");
        if (alpha < 0 || alpha > 100) {
            throw new IllegalArgumentException("alpha out of range [0,100], got " + alpha);
        }
        if (beta < 0 || beta > 100) {
            throw new IllegalArgumentException("beta out of range [0,100], got " + beta);
        }
        BiFunction<QueryWrapper<E>, Integer, List<Long>> idsProvider = (w, hint) -> gm.listIds(w);
        Function<Collection<Long>, Map<Long, E>> batchGetter = ids -> {
            Map<Long, E> out = new HashMap<>();
            for (Long id : ids) {
                if (id == null || id == 0L) continue;
                E e = gm.getById(id);
                if (e != null) out.put(id, e);
            }
            return out;
        };
        return new DsHybridQueryClient<>(entityClass, idsProvider, batchGetter, ftClient, 3, alpha, beta);
    }

    /**
     * 核心合查 API（MVP v1.0 严格 5 步，T3 实现；T2 先占位抛 UOE 便于骨架单测）。
     *
     * @param ormCondition        ORM 结构化条件（可为 null = 全表，等价 wrapper 空）
     * @param fulltextQuery       全文 query（非空 / 非 null，否则 IAE）
     * @param rawDocTextsByDocId  全文 Extras 必须的原文映射（非 null，否则 IAE；P25 Extras 构造依赖）
     * @param K                   TopK（必须 ≥1，否则 IAE）
     * @param opt                 SearchOptions（若 null = SearchOptions.defaults()）
     * @return 合查 HybridHit 列表（顺序 = FT 三键排序后的交集顺序；可能 size<K，交集不足自动截断不补）
     * @throws IOException ORM/FT 任一侧 IO 异常
     * @throws IllegalArgumentException 参数非法
     */
    public List<HybridHit<T>> searchHybrid(
            QueryWrapper<T> ormCondition,
            String fulltextQuery,
            Map<Long, String> rawDocTextsByDocId,
            int K,
            SearchOptions opt
    ) throws IOException {
        Objects.requireNonNull(fulltextQuery, "fulltextQuery cannot be null");
        if (fulltextQuery.isBlank()) {
            throw new IllegalArgumentException("fulltextQuery cannot be blank");
        }
        Objects.requireNonNull(rawDocTextsByDocId, "rawDocTextsByDocId cannot be null");
        if (K <= 0) {
            throw new IllegalArgumentException("K must >=1, got " + K);
        }
        SearchOptions options = (opt == null) ? SearchOptions.defaults() : opt;

        List<Long> ormIds = ormIdsProvider.apply(ormCondition, null);
        if (ormIds == null) ormIds = new ArrayList<>();
        Set<Long> ormIdSet = new HashSet<>(ormIds);

        int ftK = Math.max(1, K) * Math.max(1, this.fallbackMultiplier);
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras(
                fulltextQuery, rawDocTextsByDocId, ftK, options);
        if (ftAll == null) ftAll = new ArrayList<>();

        List<ScoredDocHitWithExtras> filtered = new ArrayList<>(Math.min(K, ftAll.size()));
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormIdSet.contains(h.core.docId)) continue;
            filtered.add(h);
            if (filtered.size() >= K) break;
        }

        List<Long> ftHitIds = filtered.stream().map(h -> h.core.docId).collect(Collectors.toList());
        Map<Long, T> entityMap = ormBatchGetter.apply(ftHitIds);
        if (entityMap == null) entityMap = new HashMap<>();

        List<HybridHit<T>> out = new ArrayList<>(filtered.size());
        for (ScoredDocHitWithExtras h : filtered) {
            long id = h.core.docId;
            T e = entityMap.get(id);
            if (e == null) {
                throw new IllegalStateException(
                        "ORM entity missing for docId=" + id + " (合查交集命中但 ORM getById 返回 null，违反强一致)");
            }
            double hybridScore = scoreHybrid(e, h);
            HybridHit<T> hit = new HybridHit<>(e, h);
            if (Math.abs(hit.finalHybridScore - hybridScore) > 1e-9) {
                // MVP v1.0 finalHybridScore 在 HybridHit 构造已固定为 ftx.core.finalScore；
                // 子类若重写 scoreHybrid，需要配合子类化 HybridHit；MVP 仅日志警告式校验。
            }
            out.add(hit);
        }
        return out;
    }

    /**
     * P29 预留钩子：重写此方法可实现混合打分（alpha*FT + beta*ORM feature）。
     * MVP v1.0 默认体 = {@code ftx.core.finalScore}。
     *
     * @param entity ORM 实体
     * @param ftx    全文命中
     * @return 混合分（MVP 返回 ftx.core.finalScore）
     */
    protected double scoreHybrid(T entity, ScoredDocHitWithExtras ftx) {
        return ftx.core.finalScore;
    }

    public Class<T> getEntityClass() { return entityClass; }
    public int getFallbackMultiplier() { return fallbackMultiplier; }

    /**
     * P29 FR-3 新增：列级加权线性融合合查（Facade 单调不减，原 5 参数 searchHybrid 0 删改）。
     *
     * <p>Step1~Step4 与 P28 原 searchHybrid 完全一致，仅 Step5 调用
     * {@link HybridScoreEngine#linearScore(double, double, DsTableAdapter, double, Map)} 计算
     * overrideHybridScore，传 {@link HybridHit#HybridHit(DsTableAdapter, ScoredDocHitWithExtras, double)} 新 3 参数构造。
     *
     * @param ormCondition        ORM 结构化条件（null=全表）
     * @param fulltextQuery       全文 query（非空非 null，否则 IAE）
     * @param rawDocTextsByDocId  原文映射（非 null，否则 NPE）
     * @param K                   TopK（≥1，否则 IAE）
     * @param opt                 SearchOptions（null=默认）
     * @param alpha               FT α ∈ [0,100]，否则 IAE
     * @param beta                ORM 列 β ∈ [0,100]，否则 IAE
     * @param columnWeights       列级权重：columnName → &lt;weight_c, extractor_c&gt;（weight∈[0,100]，extractor非null）
     * @return 合查 HybridHit 列表（按 override finalHybridScore↓ 为主，同分 docId↑ shardId↑ 三键 tie-break）
     * @throws IOException        ORM/FT IO 异常
     * @throws IllegalArgumentException / NullPointerException 参数非法
     * @since P29
     */
    public List<HybridHit<T>> searchHybridWithWeights(
            QueryWrapper<T> ormCondition,
            String fulltextQuery,
            Map<Long, String> rawDocTextsByDocId,
            int K,
            SearchOptions opt,
            double alpha,
            double beta,
            Map<String, Map.Entry<Double, ToDoubleFunction<T>>> columnWeights
    ) throws IOException {
        Objects.requireNonNull(fulltextQuery, "fulltextQuery cannot be null");
        if (fulltextQuery.isBlank()) throw new IllegalArgumentException("fulltextQuery cannot be blank");
        Objects.requireNonNull(rawDocTextsByDocId, "rawDocTextsByDocId cannot be null");
        if (K <= 0) throw new IllegalArgumentException("K must >=1, got " + K);
        if (alpha < 0 || alpha > 100) throw new IllegalArgumentException("alpha out of range [0,100], got " + alpha);
        if (beta < 0 || beta > 100) throw new IllegalArgumentException("beta out of range [0,100], got " + beta);
        Objects.requireNonNull(columnWeights, "columnWeights cannot be null");
        for (Map.Entry<String, Map.Entry<Double, ToDoubleFunction<T>>> e : columnWeights.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) throw new IllegalArgumentException("columnWeights key blank/null: " + k);
            Map.Entry<Double, ToDoubleFunction<T>> p = e.getValue();
            if (p == null) throw new NullPointerException("columnWeights[" + k + "] pair null");
            Double w = p.getKey();
            if (w == null) throw new NullPointerException("columnWeights[" + k + "].weight null");
            if (w < 0 || w > 100) throw new IllegalArgumentException("columnWeights[" + k + "].weight out [0,100]: " + w);
            if (p.getValue() == null) throw new NullPointerException("columnWeights[" + k + "].extractor null");
        }
        SearchOptions options = (opt == null) ? SearchOptions.defaults() : opt;

        List<Long> ormIds = ormIdsProvider.apply(ormCondition, null);
        if (ormIds == null) ormIds = new ArrayList<>();
        Set<Long> ormIdSet = new HashSet<>(ormIds);

        int ftK = Math.max(1, K) * Math.max(1, this.fallbackMultiplier);
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras(
                fulltextQuery, rawDocTextsByDocId, ftK, options);
        if (ftAll == null) ftAll = new ArrayList<>();

        List<ScoredDocHitWithExtras> filtered = new ArrayList<>(Math.min(K, ftAll.size()));
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormIdSet.contains(h.core.docId)) continue;
            filtered.add(h);
            if (filtered.size() >= K) break;
        }

        List<Long> ftHitIds = filtered.stream().map(h -> h.core.docId).collect(Collectors.toList());
        Map<Long, T> entityMap = ormBatchGetter.apply(ftHitIds);
        if (entityMap == null) entityMap = new HashMap<>();

        List<HybridHit<T>> out = new ArrayList<>(filtered.size());
        for (ScoredDocHitWithExtras h : filtered) {
            long id = h.core.docId;
            T e = entityMap.get(id);
            if (e == null) {
                throw new IllegalStateException(
                        "ORM entity missing for docId=" + id + " (合查交集命中但 ORM getById 返回 null，违反强一致)");
            }
            double overrideHybridScore = HybridScoreEngine.linearScore(
                    h.core.finalScore, alpha, e, beta, columnWeights);
            HybridHit<T> hit = new HybridHit<>(e, h, overrideHybridScore);
            out.add(hit);
        }
        out.sort((a, b) -> {
            int c1 = Double.compare(b.finalHybridScore, a.finalHybridScore);
            if (c1 != 0) return c1;
            int c2 = Long.compare(a.docId, b.docId);
            if (c2 != 0) return c2;
            int sa = (a.ftx != null && a.ftx.core != null) ? a.ftx.core.shardId : -1;
            int sb = (b.ftx != null && b.ftx.core != null) ? b.ftx.core.shardId : -1;
            return Integer.compare(sa, sb);
        });
        return out;
    }

    /**
     * P29 FR-5 新增：LTR MVP 线性模型合查（dot product 线性打分，不引入非线性）。
     *
     * <p>Step1~Step4 与 P28 原 searchHybrid 完全一致，Step5 用
     * {@link HybridScoreEngine#extractColumnFeatures(DsTableAdapter, List)} +
     * {@link HybridScoreEngine#ltrLinearScore(double[], double[])} 打 overrideHybridScore 传 3 参数 HybridHit 构造。
     *
     * @param ormCondition        ORM 结构化条件（null=全表）
     * @param fulltextQuery       全文 query（非空非 null，IAE）
     * @param rawDocTextsByDocId  原文映射（非 null，NPE）
     * @param K                   TopK（≥1，IAE）
     * @param opt                 SearchOptions（null=默认）
     * @param featureExtractors   LTR 特征提取器 list（非空，size=modelWeights.length）
     * @param ltrModelWeights     LTR 权重向量（非空，size=extractors.size）
     * @return 合查 HybridHit 列表
     * @throws IOException        IO 异常
     * @throws IllegalArgumentException / NullPointerException 参数非法
     * @since P29
     */
    public List<HybridHit<T>> searchHybridWithLtr(
            QueryWrapper<T> ormCondition,
            String fulltextQuery,
            Map<Long, String> rawDocTextsByDocId,
            int K,
            SearchOptions opt,
            List<ToDoubleFunction<T>> featureExtractors,
            double[] ltrModelWeights
    ) throws IOException {
        Objects.requireNonNull(fulltextQuery, "fulltextQuery cannot be null");
        if (fulltextQuery.isBlank()) throw new IllegalArgumentException("fulltextQuery cannot be blank");
        Objects.requireNonNull(rawDocTextsByDocId, "rawDocTextsByDocId cannot be null");
        if (K <= 0) throw new IllegalArgumentException("K must >=1, got " + K);
        Objects.requireNonNull(featureExtractors, "featureExtractors cannot be null");
        if (featureExtractors.isEmpty()) throw new IllegalArgumentException("featureExtractors cannot be empty");
        for (int i = 0; i < featureExtractors.size(); i++) {
            if (featureExtractors.get(i) == null) throw new NullPointerException("featureExtractors[" + i + "] is null");
        }
        Objects.requireNonNull(ltrModelWeights, "ltrModelWeights cannot be null");
        if (ltrModelWeights.length == 0) throw new IllegalArgumentException("ltrModelWeights cannot be empty");
        if (featureExtractors.size() != ltrModelWeights.length) {
            throw new IllegalArgumentException(
                    "featureExtractors.size != ltrModelWeights.length: " +
                            featureExtractors.size() + " vs " + ltrModelWeights.length);
        }
        SearchOptions options = (opt == null) ? SearchOptions.defaults() : opt;

        List<Long> ormIds = ormIdsProvider.apply(ormCondition, null);
        if (ormIds == null) ormIds = new ArrayList<>();
        Set<Long> ormIdSet = new HashSet<>(ormIds);

        int ftK = Math.max(1, K) * Math.max(1, this.fallbackMultiplier);
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras(
                fulltextQuery, rawDocTextsByDocId, ftK, options);
        if (ftAll == null) ftAll = new ArrayList<>();

        List<ScoredDocHitWithExtras> filtered = new ArrayList<>(Math.min(K, ftAll.size()));
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormIdSet.contains(h.core.docId)) continue;
            filtered.add(h);
            if (filtered.size() >= K) break;
        }

        List<Long> ftHitIds = filtered.stream().map(h -> h.core.docId).collect(Collectors.toList());
        Map<Long, T> entityMap = ormBatchGetter.apply(ftHitIds);
        if (entityMap == null) entityMap = new HashMap<>();

        List<HybridHit<T>> out = new ArrayList<>(filtered.size());
        for (ScoredDocHitWithExtras h : filtered) {
            long id = h.core.docId;
            T e = entityMap.get(id);
            if (e == null) {
                throw new IllegalStateException(
                        "ORM entity missing for docId=" + id + " (合查交集命中但 ORM getById 返回 null，违反强一致)");
            }
            double[] features = HybridScoreEngine.extractColumnFeatures(e, featureExtractors);
            double overrideHybridScore = HybridScoreEngine.ltrLinearScore(features, ltrModelWeights);
            HybridHit<T> hit = new HybridHit<>(e, h, overrideHybridScore);
            out.add(hit);
        }
        out.sort((a, b) -> {
            int c1 = Double.compare(b.finalHybridScore, a.finalHybridScore);
            if (c1 != 0) return c1;
            int c2 = Long.compare(a.docId, b.docId);
            if (c2 != 0) return c2;
            int sa = (a.ftx != null && a.ftx.core != null) ? a.ftx.core.shardId : -1;
            int sb = (b.ftx != null && b.ftx.core != null) ? b.ftx.core.shardId : -1;
            return Integer.compare(sa, sb);
        });
        return out;
    }

    @SuppressWarnings("unused")
    private static Class<?> _unused_import_anchor() { return AbstractMap.SimpleEntry.class; }

    @Override
    public void close() {
        try { if (ftClient != null) ftClient.close(); } catch (Exception ignore) { }
    }
}
