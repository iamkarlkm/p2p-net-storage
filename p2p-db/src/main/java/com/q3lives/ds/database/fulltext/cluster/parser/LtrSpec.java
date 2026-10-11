package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * LtrSpec LTR 线性配置（MVP v1.0 @since P30）：Parser 输出 LTR 尾段的不可变二元结构。
 * <ul>
 *   <li>featureExtractors：特征提取函数 List（unmodifiable；顺序严格与 DHQC 7 参 searchHybridWithLtr 一致）
 *   <li>weights：double[] 点积权重数组（克隆后保存，外部修改不影响；长度必须 == extractors.size）
 * </ul>
 * 严格对齐 P29 FR-4：LTR MVP 仅支持纯 dot product Σf[i]*w[i]；Parser 本身不做 finite 校验（留给
 * HybridScoreEngine.ltrLinearScore 统一断言非有限值 IAE），但必须保证 extractors 与 weights 长度相等。
 * DSL 未写 LTR 段 → ParsedHybridQuery.ltrSpec = null；业务显式判断后三选一合查方法。
 *
 * @param <T> DsTableAdapter 子类（ToDoubleFunction<T> 目标实体类型）
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public final class LtrSpec<T> {

    public final List<ToDoubleFunction<T>> featureExtractors;
    public final double[] weights;

    /**
     * 构造 LtrSpec：extractors 列表 + weights 数组（防御性拷贝 clone + 长度 1:1）。
     *
     * @param featureExtractors 非空 List，每个元素非空（允许 empty 空特征，但 DHQC 会报 IAE）
     * @param weights 非空 double[]，长度 == extractors.size()；每个元素允许正负（P29 LTR 无范围限制）
     * @throws NullPointerException 任一参数 null / extractors 任一元素 null
     * @throws IllegalArgumentException extractors.size != weights.length
     */
    public LtrSpec(List<ToDoubleFunction<T>> featureExtractors, double[] weights) {
        Objects.requireNonNull(featureExtractors, "featureExtractors list cannot be null");
        Objects.requireNonNull(weights, "weights array cannot be null");
        List<ToDoubleFunction<T>> copyList = new java.util.ArrayList<>(featureExtractors.size());
        for (ToDoubleFunction<T> fn : featureExtractors) {
            copyList.add(Objects.requireNonNull(fn, "featureExtractors.element cannot be null"));
        }
        if (copyList.size() != weights.length) {
            throw new IllegalArgumentException("featureExtractors.size=" + copyList.size()
                    + " must equal weights.length=" + weights.length);
        }
        this.featureExtractors = Collections.unmodifiableList(copyList);
        this.weights = weights.clone();
    }

    /**
     * weights 数组不可变视图：返回内部 clone（外部改数组不影响本实例）。
     *
     * @return weights 拷贝数组
     */
    public double[] getWeightsCopy() {
        return weights.clone();
    }

    /**
     * 特征数：extractors.size（== weights.length，构造已校验）。
     *
     * @return 特征 int 数
     */
    public int featureCount() {
        return featureExtractors.size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LtrSpec<?> that)) return false;
        return featureExtractors.equals(that.featureExtractors) && Arrays.equals(weights, that.weights);
    }

    @Override
    public int hashCode() {
        int h = featureExtractors.hashCode();
        h = 31 * h + Arrays.hashCode(weights);
        return h;
    }
}
