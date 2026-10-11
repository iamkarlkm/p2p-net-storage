package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.Objects;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.integration.QueryWrapper.Criterion;

/**
 * ParsedHybridQuery 四元组不可变结构（MVP v1.0 @since P30）：Parser 纯解析输出唯一产物。
 * <ul>
 *   <li>ormCond：已填 criteria 的 QueryWrapper<T>（纯内存填，未调用 ORM，允许 empty criteria）
 *   <li>ftQuery：trim 后的 FT 查询字符串（无 FT 段 = ""，下游 DHQC 会 blank 校验 IAE）
 *   <li>weightSpec：WEIGHT 尾段三元（默认 DEFAULT alpha=1 beta=0 empty 列）
 *   <li>ltrSpec：LTR 尾段二元（无 LTR 段 = null；非空 extractors.size == weights.length）
 * </ul>
 * Parser 永远保证四元组零可变：所有字段 public final；weightSpec.columnWeights /
 * ltrSpec.featureExtractors 均 Collections.unmodifiable；ltrSpec.weights 内部 clone；
 * T2 实现 deepEquals(a,b) 递归比对 QueryWrapper.criteria 逐条 Criterion.op/col/a/b 顺序 100% 全等，
 * 保证与手写 Java QueryWrapper 装配字节级 fingerprint 相同。
 *
 * @param <T> DsTableAdapter 子类实体类型
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public final class ParsedHybridQuery<T extends DsTableAdapter> {

    public final QueryWrapper<T> ormCond;
    public final String ftQuery;
    public final WeightSpec weightSpec;
    public final LtrSpec<T> ltrSpec;

    /**
     * 构造 ParsedHybridQuery：四字段全必填（ltrSpec 允许 null 表示无 LTR）。
     *
     * @param ormCond 非空 QueryWrapper（允许空 criteria，业务会 listIds Q-EMPTY）
     * @param ftQuery 非空（允许 blank 空串""）trim 后 FT 字符串
     * @param weightSpec 非空（默认 WeightSpec.DEFAULT）
     * @param ltrSpec 允许 null；非空必须 extractors.size == weights.length（LtrSpec 构造已校验）
     * @throws NullPointerException ormCond/ftQuery/weightSpec == null
     */
    public ParsedHybridQuery(QueryWrapper<T> ormCond, String ftQuery, WeightSpec weightSpec, LtrSpec<T> ltrSpec) {
        this.ormCond = Objects.requireNonNull(ormCond, "ormCond cannot be null");
        this.ftQuery = Objects.requireNonNull(ftQuery, "ftQuery cannot be null");
        this.weightSpec = Objects.requireNonNull(weightSpec, "weightSpec cannot be null");
        this.ltrSpec = ltrSpec;
    }

    /**
     * DeepEquals（T2 实装完整版；T1 仅骨架空实现返回 false，T2 TR2.1 前替换）：
     * 递归比较 ① QueryWrapper.criteria 逐条 Criterion.op/col/a/b（顺序敏感）+
     * ② ftQuery equals + ③ alpha/beta + columnWeights Map(entrySet sorted) + ④ LtrSpec
     * extractors list + weights Arrays；a==b（含都 null）返回 true。
     *
     * @param a 第一个 ParsedHybridQuery（允许 null）
     * @param b 第二个 ParsedHybridQuery（允许 null）
     * @return 字节级全等 true
     * @param <T> DsTableAdapter 子类
     */
    public static <T extends DsTableAdapter> boolean deepEquals(ParsedHybridQuery<T> a, ParsedHybridQuery<T> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (!a.ftQuery.equals(b.ftQuery)) return false;
        if (a.weightSpec.alpha != b.weightSpec.alpha) return false;
        if (a.weightSpec.beta != b.weightSpec.beta) return false;
        if (!a.weightSpec.columnWeights.equals(b.weightSpec.columnWeights)) return false;
        if (a.ltrSpec == null ? b.ltrSpec != null : !a.ltrSpec.equals(b.ltrSpec)) return false;
        var ca = a.ormCond.criteria();
        var cb = b.ormCond.criteria();
        if (ca.size() != cb.size()) return false;
        for (int i = 0; i < ca.size(); i++) {
            Criterion xa = ca.get(i);
            Criterion xb = cb.get(i);
            if (xa.op != xb.op) return false;
            if (!xa.col.equals(xb.col)) return false;
            if (!Objects.equals(xa.a, xb.a)) return false;
            if (!Objects.equals(xa.b, xb.b)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (Criterion c : ormCond.criteria()) {
            h = 31 * h + (c.op == null ? 0 : c.op.hashCode());
            h = 31 * h + (c.col == null ? 0 : c.col.hashCode());
            h = 31 * h + (c.a == null ? 0 : c.a.hashCode());
            h = 31 * h + (c.b == null ? 0 : c.b.hashCode());
        }
        h = 31 * h + ftQuery.hashCode();
        long aBits = Double.doubleToLongBits(weightSpec.alpha);
        long bBits = Double.doubleToLongBits(weightSpec.beta);
        h = 31 * h + (int) (aBits ^ (aBits >>> 32));
        h = 31 * h + (int) (bBits ^ (bBits >>> 32));
        h = 31 * h + weightSpec.columnWeights.hashCode();
        h = 31 * h + (ltrSpec == null ? 0 : ltrSpec.hashCode());
        return h;
    }
}
