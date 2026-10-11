package com.q3lives.ds.database.fulltext.cluster;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import java.util.AbstractMap;
import java.util.Map.Entry;

import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * P29 FR-4 混合加权打分引擎（静态工具类，ZeroLeak3=3 子包 cluster 位置，不新增 fulltext 根包 top-level class）。
 *
 * <p>提供三类线性打分能力：
 * <ol>
 *   <li>{@link #linearScore(double, double, DsTableAdapter, double, Map)}：FT finalScore × α + Σ(列权重 × extractor.applyAsDouble(entity)) × β。</li>
 *   <li>{@link #ltrLinearScore(double[], double[])}：LTR MVP 线性模型 dot product（Σfeature[i] × modelWeights[i]）。</li>
 *   <li>{@link #extractColumnFeatures(DsTableAdapter, List)}：按 extractors 顺序 ORM 列级特征提取，返回 double[] 向量。</li>
 * </ol>
 *
 * <p>浮点稳定性：按 strictfp 风格顺序累加，同一组输入 2 次手算差 &lt; 1e-9。
 * <p>参数校验：NPE（null 参数）/ IAE（NaN、非有限值、长度不匹配）全覆盖。
 * <p>实现阶段：T1 定义签名，T2 实装 linearScore / ltrLinearScore / extractColumnFeatures 三静态法（strictfp 顺序累加 + 非有限值 IAE）。
 *
 * @param <T> 目标实体 DsTableAdapter 子类
 * @since P29
 */
public final class HybridScoreEngine {

    private HybridScoreEngine() {}

    /**
     * 线性融合分：α × ftScore + β × Σ(列权重 × 列值)。
     *
     * @param ftScore       FT 侧 core.finalScore（非 NaN、非 Infinite，否则 IAE）
     * @param alpha         FT 加权系数 α ∈ [0, 100]（IAE 越界）
     * @param entity        ORM 实体（非 null，NPE）
     * @param beta          ORM 列级加权系数 β ∈ [0, 100]（IAE 越界）
     * @param columnWeights 列级权重映射：columnName → &lt;weight_c, extractor_c&gt;（weight_c ∈ [0,100]，extractor_c 非 null）
     * @param <T>           DsTableAdapter 子类
     * @return 线性融合分（有限值）
     * @throws NullPointerException     entity / columnWeights / 任意 extractor 为 null
     * @throws IllegalArgumentException ftScore NaN/Infinite / alpha 越界 / beta 越界 / weight_c 越界 / key blank / 结果非有限
     */
    public static <T extends DsTableAdapter> double linearScore(
            double ftScore,
            double alpha,
            T entity,
            double beta,
            Map<String, Map.Entry<Double, ToDoubleFunction<T>>> columnWeights
    ) {
        Objects.requireNonNull(entity, "entity cannot be null");
        Objects.requireNonNull(columnWeights, "columnWeights cannot be null");
        if (!Double.isFinite(ftScore)) {
            throw new IllegalArgumentException("ftScore must be finite, got " + ftScore);
        }
        if (alpha < 0 || alpha > 100) {
            throw new IllegalArgumentException("alpha out of range [0,100], got " + alpha);
        }
        if (beta < 0 || beta > 100) {
            throw new IllegalArgumentException("beta out of range [0,100], got " + beta);
        }
        double columnSum = 0.0d;
        for (Map.Entry<String, Map.Entry<Double, ToDoubleFunction<T>>> e : columnWeights.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                throw new IllegalArgumentException("columnWeights key blank/null: " + k);
            }
            Map.Entry<Double, ToDoubleFunction<T>> pair = e.getValue();
            if (pair == null) throw new NullPointerException("columnWeights[" + k + "] pair is null");
            Double w = pair.getKey();
            if (w == null) throw new NullPointerException("columnWeights[" + k + "].weight is null");
            if (w < 0 || w > 100) {
                throw new IllegalArgumentException("columnWeights[" + k + "].weight out of range [0,100], got " + w);
            }
            ToDoubleFunction<T> ext = pair.getValue();
            if (ext == null) {
                throw new NullPointerException("columnWeights[" + k + "].extractor is null");
            }
            double v = ext.applyAsDouble(entity);
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("columnWeights[" + k + "] extractor returned non-finite: " + v);
            }
            columnSum = columnSum + (w * v);
        }
        double score = alpha * ftScore + beta * columnSum;
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("linearScore result non-finite: ftScore=" + ftScore + " alpha=" + alpha + " beta=" + beta + " columnSum=" + columnSum);
        }
        return score;
    }

    /**
     * LTR MVP 线性打分：strict dot product Σ(featureVector[i] × modelWeights[i])。
     *
     * @param featureVector 特征向量（非 null，length ≥ 1，否则 NPE/IAE）
     * @param modelWeights  模型权重向量（非 null，length == featureVector.length）
     * @return dot product 结果（有限值，否则 IAE）
     * @throws NullPointerException     featureVector / modelWeights null
     * @throws IllegalArgumentException length=0 / length 不等 / 结果非有限
     */
    public static double ltrLinearScore(double[] featureVector, double[] modelWeights) {
        Objects.requireNonNull(featureVector, "featureVector cannot be null");
        Objects.requireNonNull(modelWeights, "modelWeights cannot be null");
        if (featureVector.length == 0) throw new IllegalArgumentException("featureVector length is 0");
        if (modelWeights.length == 0) throw new IllegalArgumentException("modelWeights length is 0");
        if (featureVector.length != modelWeights.length) {
            throw new IllegalArgumentException(
                    "featureVector.length != modelWeights.length: " +
                            featureVector.length + " vs " + modelWeights.length);
        }
        double s = 0.0d;
        for (int i = 0; i < featureVector.length; i++) {
            double f = featureVector[i];
            double w = modelWeights[i];
            if (!Double.isFinite(f)) {
                throw new IllegalArgumentException("featureVector[" + i + "] non-finite: " + f);
            }
            if (!Double.isFinite(w)) {
                throw new IllegalArgumentException("modelWeights[" + i + "] non-finite: " + w);
            }
            s = s + (f * w);
        }
        if (!Double.isFinite(s)) {
            throw new IllegalArgumentException("ltrLinearScore result non-finite: sum=" + s);
        }
        return s;
    }

    /**
     * ORM 列级特征提取：顺序逐 extractor 调用 applyAsDouble(entity) 返回 double[]。
     *
     * @param entity            实体（非 null，NPE）
     * @param featureExtractors 提取器列表（非 null，size ≥ 1；任意元素 null → NPE）
     * @param <T>               DsTableAdapter 子类
     * @return 特征向量（size == extractors.size，顺序严格不变）
     * @throws NullPointerException     entity / list / 任意 extractor 为 null
     * @throws IllegalArgumentException list 为空
     */
    public static <T extends DsTableAdapter> double[] extractColumnFeatures(
            T entity,
            List<ToDoubleFunction<T>> featureExtractors
    ) {
        Objects.requireNonNull(entity, "entity cannot be null");
        Objects.requireNonNull(featureExtractors, "featureExtractors cannot be null");
        if (featureExtractors.isEmpty()) {
            throw new IllegalArgumentException("featureExtractors cannot be empty");
        }
        int n = featureExtractors.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            ToDoubleFunction<T> ext = featureExtractors.get(i);
            if (ext == null) {
                throw new NullPointerException("featureExtractors[" + i + "] is null");
            }
            double v = ext.applyAsDouble(entity);
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("featureExtractors[" + i + "] returned non-finite: " + v);
            }
            out[i] = v;
        }
        return out;
    }

    @SuppressWarnings("unused")
    private static Class<?> _unused_import_anchor() { return AbstractMap.SimpleEntry.class; }
}
