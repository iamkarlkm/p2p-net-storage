package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * WeightSpec 权重配置（MVP v1.0 @since P30）：Parser 输出 WEIGHT 尾段的不可变三元结构。
 * <ul>
 *   <li>alpha：FT 分权重 ∈[0,100]（IAE 越界）
 *   <li>beta：ORM 列级线性和权重 ∈[0,100]（IAE 越界）
 *   <li>columnWeights：列名 → weight ∈[0,100] 映射（unmodifiable，每个 weight 独立 IAE 越界）
 * </ul>
 * 默认回退（DSL 无 WEIGHT 段）alpha=1.0、beta=0.0、columnWeights=emptyMap，等价直接调用 P28 5 参 searchHybrid。
 * 严格对齐 P29 Spec FR-2：αβ ∈[0,100]，与下游合查客户端 7 参主构造范围校验 1:1，0 双重标准。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public final class WeightSpec {

    public static final WeightSpec DEFAULT = new WeightSpec(1.0, 0.0, Collections.emptyMap());

    public final double alpha;
    public final double beta;
    public final Map<String, Double> columnWeights;

    /**
     * 构造 WeightSpec：三字段校验后入参；columnWeights 内部防御性拷贝 + unmodifiable。
     *
     * @param alpha ∈[0,100]
     * @param beta ∈[0,100]
     * @param columnWeights 非空（允许 empty）；每个 value ∈[0,100]
     * @throws NullPointerException columnWeights == null
     * @throws IllegalArgumentException alpha/beta/any weight ∉[0,100]、或非有限值
     */
    public WeightSpec(double alpha, double beta, Map<String, Double> columnWeights) {
        Objects.requireNonNull(columnWeights, "columnWeights map cannot be null");
        validateRange("alpha", alpha);
        validateRange("beta", beta);
        Map<String, Double> copy = new LinkedHashMap<>(columnWeights.size());
        for (Map.Entry<String, Double> e : columnWeights.entrySet()) {
            String col = Objects.requireNonNull(e.getKey(), "columnWeights.key cannot be null");
            Double w = Objects.requireNonNull(e.getValue(), "columnWeights[" + col + "] cannot be null");
            validateRange("weight[" + col + "]", w.doubleValue());
            copy.put(col, w);
        }
        this.alpha = alpha;
        this.beta = beta;
        this.columnWeights = Collections.unmodifiableMap(copy);
    }

    private static void validateRange(String name, double v) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite, actual=" + v);
        }
        if (v < 0.0 || v > 100.0) {
            throw new IllegalArgumentException(name + " must in [0,100], actual=" + v);
        }
    }
}
