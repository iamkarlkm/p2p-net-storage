package com.q3lives.ds.database.fulltext.cluster.join.v2;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * JoinIdResolverV2 静态工具类（P35 OQ1 ORM 多表 Join v2 DsManyToOne MVP）。
 *
 * <p>
 * 对 P31
 * {@link com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver#resolve}
 * 做 N:1 聚合端扩展：MANY_TO_ONE 模式走 IN 子查询 + FetchSize 批量切片聚合。
 *
 * <p>
 * 核心算法（{@link #resolveManyToOne}）：
 * <ol>
 * <li>校验 join.mode == MANY_TO_ONE + batchSize ∈ [1, 4096]（R8）</li>
 * <li>rg = relatedGms.get(join.relatedClass)</li>
 * <li>将 outerIds 按 batchSize
 * 切片：{@code subList(i, Math.min(i+batchSize, size))}</li>
 * <li>每片用 {@code rg.list(new QueryWrapper<R>().in(relatedField, sliceIds))}
 * 拉行</li>
 * <li>反射取 relatedField Long 值，与 outerIds 做 retainAll 交集</li>
 * <li>返回 final outerIds Set（unmodifiable）</li>
 * </ol>
 *
 * <p>
 * 树级联算法（{@link #resolveManyToOneTree}，J=2）：
 * <ol>
 * <li>T1_outerIds = mainGm.listIds(orm)</li>
 * <li>R1_matched = resolveManyToOne(T1_outerIds, join1) → 命中 R1 行（用
 * relatedField 作为 R1.id 关联 join2 outerIds）</li>
 * <li>R2_matched = resolveManyToOne(R1_matched_ids, join2) → 命中 R2 行</li>
 * <li>final = T1 ∩ R1 ∩ R2</li>
 * </ol>
 *
 * <p>
 * 聚合端：{@link #aggregateByOuterId} 返回 {@link ManyToOneJoinResult}，含 byOuterId
 * Map (outerId → List&lt;R&gt;) + matchedOuterIds Set。
 *
 * <p>
 * 硬约束：J≤2 硬上限（J=3+ 抛 IAE R7）；P31 JoinIdResolver.resolve 1 字不改。
 *
 * @author SP3.1 Implement Agent
 * @since P35 OQ1 ORM Join v2 MVP
 */
public final class JoinIdResolverV2 {

    public static final int J_TREE_LIMIT = 2;

    private JoinIdResolverV2() {
    }

    /**
     * MANY_TO_ONE 模式求解 outerIds 交集（1 个 JoinSpec + outerIds 候选）。
     *
     * @param outerIds 外层候选 outerId 集合（非 null，非空；空返回空集）
     * @param join JoinSpec（必须 mode=MANY_TO_ONE + batchSize∈[1,4096]）
     * @param relatedGms 从表 GM 注册表（必须包含 join.relatedClass）
     * @return 命中 outerIds 交集（unmodifiable Set）
     * @throws IllegalArgumentException 参数违规
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> Set<Long> resolveManyToOne(
            Set<Long> outerIds,
            JoinSpec<?> join,
            Map<Class<?>, GenericManager<?>> relatedGms) {
        Objects.requireNonNull(outerIds, "outerIds cannot be null");
        Objects.requireNonNull(join, "join cannot be null");
        Objects.requireNonNull(relatedGms, "relatedGms cannot be null");
        if (join.mode != JoinMode.MANY_TO_ONE) {
            throw new IllegalArgumentException(
                    "resolveManyToOne 仅支持 mode=MANY_TO_ONE, got " + join.mode);
        }
        if (join.batchSize < 1 || join.batchSize > JoinSpec.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "MANY_TO_ONE batchSize ∈ [1, " + JoinSpec.MAX_BATCH_SIZE + "], got " + join.batchSize);
        }
        if (outerIds.isEmpty()) {
            return Collections.emptySet();
        }
        GenericManager<?> rgRaw = relatedGms.get(join.relatedClass);
        if (rgRaw == null) {
            throw new IllegalArgumentException(
                    "relatedGms missing GM for relatedClass=" + join.relatedClass.getName());
        }
        Class<?> expectedClass = rgRaw.getPersistentClass();
        if (expectedClass != null && !join.relatedClass.getName().equals(expectedClass.getName())) {
            throw new IllegalArgumentException(
                    "relatedGms[" + join.relatedClass.getName() + "] persistentClass mismatch, got "
                    + (expectedClass == null ? "null" : expectedClass.getName()));
        }
        if (!"id".equals(join.outerField)) {
            throw new IllegalArgumentException(
                    "MANY_TOONE outerField MVP 仅支持 \"id\" (主表 entity.getId() 等值), got \""
                    + join.outerField + "\"");
        }
        // 切片 outerIds 按 batchSize
        List<Long> outerList = new ArrayList<>(outerIds);
        Set<Long> matched = new LinkedHashSet<>();
        int total = outerList.size();
        for (int i = 0; i < total; i += join.batchSize) {
            int end = Math.min(i + join.batchSize, total);
            List<Long> slice = outerList.subList(i, end);
            // 反射 relatedField 类型（必须 Long）
            Field relatedF;
            try {
                relatedF = join.relatedClass.getDeclaredField(join.relatedField);
                relatedF.setAccessible(true);
            } catch (NoSuchFieldException nsf) {
                throw new IllegalArgumentException(
                        "JoinSpec.relatedField \"" + join.relatedField + "\" not found in "
                        + join.relatedClass.getName(), nsf);
            }
            Class<?> t = relatedF.getType();
            if (!Long.class.equals(t) && !long.class.equals(t)) {
                throw new IllegalArgumentException(
                        "JoinSpec.relatedField=" + join.relatedField + " type not Long, got "
                        + t.getName());
            }
            // IN 子查询
            QueryWrapper qw = (join.relatedCond != null
                    ? JoinSpec.QwHelper.defensiveCopy(join.relatedCond)
                    : new QueryWrapper());
            qw.in(join.relatedField, slice);
            // 反射 relatedField 取 Long 值；matched.retainAll
            try {
                List<?> rows = (List<?>) (List) rgRaw.list(qw);
                for (Object r : rows) {
                    Long v = (Long) relatedF.get(r);
                    if (v != null && v != 0L) {
                        matched.add(v);
                    }
                }
            } catch (IllegalAccessException iae) {
                throw new RuntimeException(iae);
            }
        }
        // outerIds ∩ matched
        Set<Long> result = new LinkedHashSet<>(outerIds);
        result.retainAll(matched);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 聚合：按 outerId 聚合从表 R 行（{@link ManyToOneJoinResult}）。
     *
     * @param outerIds 候选 outerId 集合
     * @param join MANY_TO_ONE JoinSpec
     * @param rg 从表 GM
     * @return ManyToOneJoinResult 不可变
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <R extends DsTableAdapter> ManyToOneJoinResult<R> aggregateByOuterId(
            Set<Long> outerIds,
            JoinSpec<R> join,
            GenericManager<R> rg) {
        Objects.requireNonNull(outerIds, "outerIds cannot be null");
        Objects.requireNonNull(join, "join cannot be null");
        Objects.requireNonNull(rg, "rg cannot be null");
        if (join.mode != JoinMode.MANY_TO_ONE) {
            throw new IllegalArgumentException("aggregateByOuterId 仅支持 MANY_TO_ONE, got " + join.mode);
        }
        if (outerIds.isEmpty()) {
            return new ManyToOneJoinResult<>(join.relatedClass, new LinkedHashMap<>(), new LinkedHashSet<>());
        }
        Field relatedF;
        try {
            relatedF = join.relatedClass.getDeclaredField(join.relatedField);
            relatedF.setAccessible(true);
        } catch (NoSuchFieldException nsf) {
            throw new IllegalArgumentException(
                    "JoinSpec.relatedField \"" + join.relatedField + "\" not found", nsf);
        }
        Map<Long, List<R>> byOuterId = new LinkedHashMap<>();
        // 切片 IN
        List<Long> outerList = new ArrayList<>(outerIds);
        int total = outerList.size();
        for (int i = 0; i < total; i += join.batchSize) {
            int end = Math.min(i + join.batchSize, total);
            List<Long> slice = outerList.subList(i, end);
            QueryWrapper<R> qw = (join.relatedCond != null
                    ? JoinSpec.QwHelper.defensiveCopy(join.relatedCond)
                    : new QueryWrapper<>());
            qw.in(join.relatedField, slice);
            @SuppressWarnings("unchecked")
            List<R> rows = (List<R>) (List<?>) rg.list(qw);
            for (R r : rows) {
                try {
                    Long outerId = (Long) relatedF.get(r);
                    if (outerId == null || outerId == 0L) {
                        continue;
                    }
                    byOuterId.computeIfAbsent(outerId, k -> new ArrayList<>()).add(r);
                } catch (IllegalAccessException iae) {
                    throw new RuntimeException(iae);
                }
            }
        }
        return new ManyToOneJoinResult<>(join.relatedClass, byOuterId, byOuterId.keySet());
    }

    /**
     * MANY_TO_ONE 模式求解 outerIds ∩ matched_R（matched_R = 命中行的 PK 而非
     * relatedField）。 用于 J=2 树级联：上一层的 matched_R 作为下一层的 outerIds。
     *
     * @param outerIds 外层候选 outerId 集合（PK in related table）
     * @param join JoinSpec
     * @param relatedGms 从表 GM 注册表
     * @return 命中相关表 PK 集合（unmodifiable Set）
     */
    public static <T extends DsTableAdapter> Set<Long> resolveManyToOnePK(
            Set<Long> outerIds,
            JoinSpec<?> join,
            Map<Class<?>, GenericManager<?>> relatedGms) {
        Objects.requireNonNull(outerIds, "outerIds cannot be null");
        Objects.requireNonNull(join, "join cannot be null");
        Objects.requireNonNull(relatedGms, "relatedGms cannot be null");
        if (join.mode != JoinMode.MANY_TO_ONE) {
            throw new IllegalArgumentException(
                    "resolveManyToOnePK 仅支持 mode=MANY_TO_ONE, got " + join.mode);
        }
        if (join.batchSize < 1 || join.batchSize > JoinSpec.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "MANY_TO_ONE batchSize ∈ [1, " + JoinSpec.MAX_BATCH_SIZE + "], got " + join.batchSize);
        }
        if (outerIds.isEmpty()) {
            return Collections.emptySet();
        }
        GenericManager<?> rgRaw = relatedGms.get(join.relatedClass);
        if (rgRaw == null) {
            throw new IllegalArgumentException(
                    "relatedGms missing GM for relatedClass=" + join.relatedClass.getName());
        }
        // 切片 outerIds 按 batchSize
        List<Long> outerList = new ArrayList<>(outerIds);
        Set<Long> matchedPK = new LinkedHashSet<>();
        int total = outerList.size();
        for (int i = 0; i < total; i += join.batchSize) {
            int end = Math.min(i + join.batchSize, total);
            List<Long> slice = outerList.subList(i, end);
            QueryWrapper qw = (join.relatedCond != null
                    ? JoinSpec.QwHelper.defensiveCopy(join.relatedCond)
                    : new QueryWrapper());
            qw.in(join.relatedField, slice);
            List<?> rows = (List<?>) (List) rgRaw.list(qw);
            for (Object r : rows) {
                DsTableAdapter adapter = (DsTableAdapter) r;
                Long pk = adapter.getId();
                if (pk != null && pk != 0L) {
                    matchedPK.add(pk);
                }
            }
        }
        // 返回 matchedPK（不是 ∩ outerIds，因为这是 PK 集合用于下一层链）
        return Collections.unmodifiableSet(matchedPK);
    }

    /**
     * 树级联 J=2 解析：T1 outerIds ∩ R1 ∩ R2。
     *
     * <p>
     * 语义链：T1（mainGm PK）→ R1（join1.relatedClass PK）→ R2（join2.relatedClass PK）→
     * 回到 T1 ∩ R1 ∩ R2。
     *
     * @param mainGm 主表 GM
     * @param mainCond 主表 QW 过滤
     * @param join1 第 1 层 JoinSpec（MANY_TO_ONE）
     * @param join2 第 2 层 JoinSpec（MANY_TO_ONE；outerField 必为 "id"，R1.id ==
     * R2.relatedField）
     * @param relatedGms 从表 GM 注册表
     * @return 终态 outerIds Set（T1 ∩ R1 ∩ R2）
     * @throws IllegalArgumentException J>2 或 join 非 MANY_TO_ONE
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T extends DsTableAdapter> Set<Long> resolveManyToOneTree(
            GenericManager<T> mainGm,
            QueryWrapper<T> mainCond,
            JoinSpec<?> join1,
            JoinSpec<?> join2,
            Map<Class<?>, GenericManager<?>> relatedGms) {
        Objects.requireNonNull(mainGm, "mainGm cannot be null");
        Objects.requireNonNull(join1, "join1 cannot be null");
        Objects.requireNonNull(join2, "join2 cannot be null");
        if (join1.mode != JoinMode.MANY_TO_ONE || join2.mode != JoinMode.MANY_TO_ONE) {
            throw new IllegalArgumentException("J=2 树级联仅支持 MANY_TOONE");
        }
        int J = 2;
        if (J > J_TREE_LIMIT) {
            throw new IllegalArgumentException("J=" + J + " > J_TREE_LIMIT=" + J_TREE_LIMIT);
        }
        // T1 outerIds
        QueryWrapper<T> condCopy = (mainCond == null) ? new QueryWrapper<>() : JoinSpec.QwHelper.defensiveCopy(mainCond);
        List<Long> rawIds = mainGm.listIds(condCopy);
        Set<Long> t1Ids = new LinkedHashSet<>();
        for (Long id : rawIds) {
            if (id != null && id != 0L) {
                t1Ids.add(id);
            }
        }
        if (t1Ids.isEmpty()) {
            return Collections.emptySet();
        }
        // T1 ∩ R1（这里 R1.PK 是 T1.FK 的反向，得到 R1 的 PK 集合）
        Set<Long> r1PKs = resolveManyToOnePK(t1Ids, join1, relatedGms);
        if (r1PKs.isEmpty()) {
            return Collections.emptySet();
        }
        // R1 ∩ R2：用 R1.PK 去匹配 R2.relatedField；但 R2 是 N:1，命中 R2 行的 R1.PK
        // （即 sub2.sub1Id ∈ r1PKs）。需要收集**被 R2 引用**的 R1.PK（不是 R2.PK）
        GenericManager<?> rg1 = relatedGms.get(join1.relatedClass);
        GenericManager<?> rg2 = relatedGms.get(join2.relatedClass);
        if (rg1 == null) {
            throw new IllegalArgumentException("missing GM for join1.relatedClass=" + join1.relatedClass);
        }
        if (rg2 == null) {
            throw new IllegalArgumentException("missing GM for join2.relatedClass=" + join2.relatedClass);
        }
        Set<Long> r1PKsHitR2 = new LinkedHashSet<>();
        List<Long> r1List = new ArrayList<>(r1PKs);
        for (int i = 0; i < r1List.size(); i += join2.batchSize) {
            int end = Math.min(i + join2.batchSize, r1List.size());
            List<Long> slice = r1List.subList(i, end);
            QueryWrapper q2 = (join2.relatedCond != null
                    ? JoinSpec.QwHelper.defensiveCopy(join2.relatedCond)
                    : new QueryWrapper());
            q2.in(join2.relatedField, slice);
            List<?> r2rows = (List<?>) (List) rg2.list(q2);
            for (Object r2o : r2rows) {
                DsTableAdapter r2 = (DsTableAdapter) r2o;
                Long fkVal = null;
                try {
                    Field f = join2.relatedClass.getDeclaredField(join2.relatedField);
                    f.setAccessible(true);
                    fkVal = (Long) f.get(r2);
                } catch (Exception nse) {
                    throw new RuntimeException(nse);
                }
                if (fkVal != null && r1PKs.contains(fkVal)) {
                    r1PKsHitR2.add(fkVal);
                }
            }
        }
        if (r1PKsHitR2.isEmpty()) {
            return Collections.emptySet();
        }
        // 还原 T1：t1Ids 中有 R1 行（PK ∈ r1PKsHitR2）的 T1.PK
        Set<Long> result = new LinkedHashSet<>();
        List<Long> t1List = new ArrayList<>(t1Ids);
        for (Long t1 : t1List) {
            QueryWrapper q1 = (join1.relatedCond != null
                    ? JoinSpec.QwHelper.defensiveCopy(join1.relatedCond)
                    : new QueryWrapper());
            q1.in(join1.relatedField, java.util.Collections.singletonList(t1));
            List<?> r1rows = (List<?>) (List) rg1.list(q1);
            for (Object r1o : r1rows) {
                DsTableAdapter r1 = (DsTableAdapter) r1o;
                Long r1id = r1.getId();
                if (r1id != null && r1PKsHitR2.contains(r1id)) {
                    result.add(t1);
                    break;
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
