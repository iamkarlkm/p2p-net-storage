package com.q3lives.ds.database.fulltext.cluster.join.v2;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * ManyToOneJoinResult 不可变 POJO（P35 OQ1 ORM 多表 Join v2 DsManyToOne MVP）。
 *
 * <p>
 * N:1 聚合端结果：1 个主表 outerId 对应 0..N 个从表 R 行。
 * <ul>
 * <li>{@link #relatedClass}：从表实体类（非 null）</li>
 * <li>{@link #byOuterId}：outerId → List&lt;R&gt; 聚合 Map（unmodifiable；缺 R 的
 * outerId 不在 Map 中，调用方用 containsKey 判断）</li>
 * <li>{@link #matchedOuterIds}：所有有 R 命中的 outerIds Set（unmodifiable）</li>
 * </ul>
 *
 * <p>
 * 5 IAE 守卫（Fail-First）：
 * <ol>
 * <li>relatedClass == null</li>
 * <li>byOuterId == null</li>
 * <li>matchedOuterIds == null</li>
 * <li>byOuterId 任一 value == null（含空 List → 缺 R 命中；空 List 不写入 Map）</li>
 * <li>byOuterId.keySet 与 matchedOuterIds 不一致（保证 Set = Map.keys 严格）</li>
 * </ol>
 *
 * <p>
 * 跨进程 deepEquals 稳定：relatedClass.getName() + Map.entrySet (sorted) + Set。 T4
 * TR4.2 跨进程 fingerprint 用此方法比对。
 *
 * <p>
 * HybridHit.joinFetch 携带本对象（P31 携带单 R，本类携带多 R List）。
 *
 * @param <R> 从表实体类型（必须继承 DsTableAdapter）
 * @author SP3.1 Implement Agent
 * @since P35 OQ1 ORM Join v2 MVP
 */
public final class ManyToOneJoinResult<R extends DsTableAdapter> {

    public final Class<R> relatedClass;
    public final Map<Long, java.util.List<R>> byOuterId;
    public final Set<Long> matchedOuterIds;

    /**
     * 构造 ManyToOneJoinResult 不可变实例（5 IAE 全检）。
     *
     * @param relatedClass 从表类（非 null）
     * @param byOuterId 聚合 Map（非 null，unmodifiable 包装后存）
     * @param matchedOuterIds 命中 Set（非 null，unmodifiable 包装后存）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public ManyToOneJoinResult(Class<R> relatedClass,
            Map<Long, java.util.List<R>> byOuterId,
            Set<Long> matchedOuterIds) {
        if (relatedClass == null) {
            throw new IllegalArgumentException("relatedClass cannot be null");
        }
        if (byOuterId == null) {
            throw new IllegalArgumentException("byOuterId cannot be null");
        }
        if (matchedOuterIds == null) {
            throw new IllegalArgumentException("matchedOuterIds cannot be null");
        }
        for (Map.Entry<Long, java.util.List<R>> e : byOuterId.entrySet()) {
            if (e.getValue() == null) {
                throw new IllegalArgumentException(
                        "byOuterId[" + e.getKey() + "] value cannot be null (空 List 也需可空但非 null)");
            }
        }
        if (!byOuterId.keySet().equals(matchedOuterIds)) {
            throw new IllegalArgumentException(
                    "matchedOuterIds 必须 == byOuterId.keySet (matched=" + matchedOuterIds.size()
                    + ", keys=" + byOuterId.keySet().size() + ")");
        }
        this.relatedClass = relatedClass;
        this.byOuterId = Collections.unmodifiableMap(new LinkedHashMap<>(byOuterId));
        this.matchedOuterIds = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(matchedOuterIds));
    }

    /**
     * DeepEquals：relatedClass.getName() + byOuterId entrySet (sorted by
     * outerId) + matchedOuterIds。
     */
    public static <R extends DsTableAdapter> boolean deepEquals(ManyToOneJoinResult<R> a, ManyToOneJoinResult<R> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!a.relatedClass.getName().equals(b.relatedClass.getName())) {
            return false;
        }
        if (a.matchedOuterIds.size() != b.matchedOuterIds.size()) {
            return false;
        }
        if (!a.matchedOuterIds.equals(b.matchedOuterIds)) {
            return false;
        }
        if (a.byOuterId.size() != b.byOuterId.size()) {
            return false;
        }
        for (Map.Entry<Long, java.util.List<R>> ea : a.byOuterId.entrySet()) {
            java.util.List<R> lb = b.byOuterId.get(ea.getKey());
            if (lb == null) {
                return false;
            }
            if (ea.getValue().size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < ea.getValue().size(); i++) {
                if (!Objects.equals(ea.getValue().get(i), lb.get(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ManyToOneJoinResult)) {
            return false;
        }
        return deepEquals(this, (ManyToOneJoinResult) o);
    }

    @Override
    public int hashCode() {
        int h = relatedClass.getName().hashCode();
        h = 31 * h + matchedOuterIds.hashCode();
        for (Map.Entry<Long, java.util.List<R>> e : byOuterId.entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + e.getValue().size();
        }
        return h;
    }

    @Override
    public String toString() {
        return "ManyToOneJoinResult{" + relatedClass.getSimpleName()
                + ", outerIds=" + matchedOuterIds.size() + "}";
    }
}
