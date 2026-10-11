package com.q3lives.ds.database.fulltext.cluster;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;

/**
 * P28 FR-2 混合合查结果组合类（非继承 final，组合模式）。
 *
 * <p>严格三值全等契约：
 * {@code docId = entity.getId() = ftx.core.docId}；构造内强校验，不匹配直接抛
 * {@link IllegalArgumentException}，避免 ORM 与全文 Join 不一致。
 *
 * @since P28
 */
public final class HybridHit<T extends DsTableAdapter> {

    public final T entity;
    public final ScoredDocHitWithExtras ftx;
    public final double finalHybridScore;
    public final long docId;
    public final Map<Class<?>, Object> joinFetch;

    /**
     * 唯一公开构造（不允许无参构造 / 反射绕过 null 校验）。
     *
     * @param e   ORM 实体（非 null，且 e.getId() 非空且非 0）
     * @param f   全文命中结果（非 null，且 f.core.docId = e.getId()）
     * @throws IllegalArgumentException 三值不匹配
     * @throws NullPointerException     参数为 null
     */
    public HybridHit(T e, ScoredDocHitWithExtras f) {
        this.entity = Objects.requireNonNull(e, "entity cannot be null");
        this.ftx = Objects.requireNonNull(f, "ScoredDocHitWithExtras cannot be null");
        Long eid = e.getId();
        if (eid == null || eid == 0L) {
            throw new IllegalArgumentException("entity.id is empty: id=" + eid);
        }
        long fid = f.core.docId;
        if (eid.longValue() != fid) {
            throw new IllegalArgumentException(
                    "HybridHit docId mismatch: entity.id=" + eid + " vs ftx.core.docId=" + fid);
        }
        this.docId = fid;
        this.finalHybridScore = f.core.finalScore;
        this.joinFetch = Collections.emptyMap();
    }

    /**
     * P29 FR-1 新增 3 参数构造：允许 override finalHybridScore（线性融合 / LTR 路径用），
     * 仍必做三值全等 docId 校验。
     *
     * @param e                    ORM 实体（非 null，且 e.getId() 非空且非 0）
     * @param f                    全文命中（非 null，且 f.core.docId = e.getId()）
     * @param overrideHybridScore  自定义 finalHybridScore（必须有限值 Double.isFinite）
     * @throws IllegalArgumentException 三值不匹配 / overrideHybridScore 非有限
     * @throws NullPointerException     参数为 null
     * @since P29
     */
    public HybridHit(T e, ScoredDocHitWithExtras f, double overrideHybridScore) {
        this.entity = Objects.requireNonNull(e, "entity cannot be null");
        this.ftx = Objects.requireNonNull(f, "ScoredDocHitWithExtras cannot be null");
        if (!Double.isFinite(overrideHybridScore)) {
            throw new IllegalArgumentException(
                    "overrideHybridScore must be finite, got " + overrideHybridScore);
        }
        Long eid = e.getId();
        if (eid == null || eid == 0L) {
            throw new IllegalArgumentException("entity.id is empty: id=" + eid);
        }
        long fid = f.core.docId;
        if (eid.longValue() != fid) {
            throw new IllegalArgumentException(
                    "HybridHit docId mismatch: entity.id=" + eid + " vs ftx.core.docId=" + fid);
        }
        this.docId = fid;
        this.finalHybridScore = overrideHybridScore;
        this.joinFetch = Collections.emptyMap();
    }
}
