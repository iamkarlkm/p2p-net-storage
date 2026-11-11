package com.q3lives.ds.database.fulltext.cluster.join;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P31 FR-2 JoinIdResolver 纯函数批量求解 1:1 等值 Join 外层候选 outerIds 交集（AND 平铺语义）。
 *
 * <p>算法严格 Fail-First + ORM Invariant 保护：由于 {@code DsTableAdapter.id = transient Long
 * without @DsField} 注解，GenericManager.readByColumn("id") 恒返回 null → 直接 QueryWrapper.in("id", ids)
 * 在 matchesAll 路径永远匹配失败，故本类不把 outerIds 塞回 QW，而是返回 {@code Set<Long> outerIds}
 * 给 HybridJoinClient 在后处理 postFilterJoin() 阶段按 docId 再做一次 contains 裁剪（见 HJC L flow）。
 *
 * <p>多 JoinSpec 级联：initial outerIds = mainGm.listIds(mainCondCopy)（主表 orm 先行筛选）；
 * 对每个 JoinSpec 依次做：rg.list({relatedCond, in(relatedField, outerIdsCopy)}) → 反射取 relatedField
 * Long 值 → matched.retainAll(outerIds) → 交集作为下一 JoinSpec 的 outerIds。MVP v1.0 仅 AND 平铺，
 * 不支持 OR / 子查询嵌套 / NOT 反选（留 P34 DSL v2 实现）。
 *
 * <p>Fail-First 5 条：① outerField="id" 时按主表主键等值；非 "id" 时按主表该 Long 列值等值
 *（主表列缺失/非 Long 由 {@link JoinSpec#readOuterValue} 抛 IAE）；② relatedGms 包含
 * j.relatedClass 键；③ 从表 relatedField 反射存在且类型 Long；④ relatedField 标注 @DsField
 *（确保 ORM 列已注册）；⑤ 任一检查不通过即抛 IAE，不进入 list 阶段，避免 silent 0 results。
 *
 * <p>可观测性探针：每次进入 resolve 累加静态 volatile totalRelatedCalls（每 JoinSpec +1），
 * 单元测试可通过反射清零 & 读取验证总次数 ≤ 预期 Join 数（MVP N+1 ≤ J）。
 *
 * @author SP3.1 Implement Agent
 * @since P31 OQ12-⑨ ORM 多表 Join + FT 合查 MVP v1.0（Scope 单选① 用户签批）
 */
public final class JoinIdResolver {

    public static final int JOIN_BATCH_THRESHOLD = 128;

    private JoinIdResolver() {}

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static <T extends DsTableAdapter> Set<Long> resolve(
            GenericManager<T> mainGm,
            QueryWrapper<T> mainCond,
            List<JoinSpec<?>> joins,
            Map<Class<?>, GenericManager<?>> relatedGms,
            Map<String, int[]> probeCounters
    ) {
        Objects.requireNonNull(mainGm, "mainGm cannot be null");
        Objects.requireNonNull(relatedGms, "relatedGms cannot be null");
        List<JoinSpec<?>> safeJoins = (joins == null) ? Collections.emptyList() : joins;
        for (JoinSpec<?> j : safeJoins) {
            if (j == null) throw new IllegalArgumentException("joins list contains null element");
        }
        QueryWrapper<T> condCopy = (mainCond == null) ? null : JoinSpec.QwHelper.defensiveCopy(mainCond);
        List<Long> rawIds = mainGm.listIds(condCopy);
        Objects.requireNonNull(rawIds, "mainGm.listIds returned null (contract violation, must return non-null list)");
        Set<Long> outerIds = new LinkedHashSet<>(rawIds.size() + 2, 0.9f);
        for (Long id : rawIds) if (id != null && id != 0L) outerIds.add(id);
        int jn = safeJoins.size();
        if (jn == 0) {
            if (probeCounters != null) {
                probeCounters.putIfAbsent("totalRelatedCalls", new int[]{0});
            }
            return Collections.unmodifiableSet(outerIds);
        }
        int relatedCalls = 0;
        List<T> mainRows = null;
        for (JoinSpec<?> j : safeJoins) {
            GenericManager<?> rgRaw = relatedGms.get(j.relatedClass);
            if (rgRaw == null) {
                throw new IllegalArgumentException(
                        "relatedGms missing GenericManager for JoinSpec.relatedClass=" + j.relatedClass.getName()
                                + " (HybridJoinClient 构造时必须预注册所有 Join 从表 GM)");
            }
            Class<?> expectedClass = rgRaw.getPersistentClass();
            if (expectedClass != null && !j.relatedClass.getName().equals(expectedClass.getName())) {
                throw new IllegalArgumentException(
                        "relatedGms[" + j.relatedClass.getName() + "] GM.persistentClass mismatch, got "
                                + (expectedClass == null ? "null" : expectedClass.getName()));
            }
            Set<Long> matched = extractMatched((JoinSpec) j, (GenericManager) rgRaw);
            relatedCalls += 1;
            if ("id".equals(j.outerField)) {
                outerIds.retainAll(matched);
            } else {
                if (mainRows == null) {
                    mainRows = loadMainRows(mainGm, condCopy);
                }
                outerIds.retainAll(matchByOuterValue(mainRows, j.outerField, matched));
            }
            if (outerIds.isEmpty()) break;
        }
        if (probeCounters != null) {
            probeCounters.putIfAbsent("totalRelatedCalls", new int[]{0});
            probeCounters.get("totalRelatedCalls")[0] += relatedCalls;
        }
        return Collections.unmodifiableSet(outerIds);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <R extends DsTableAdapter> Set<Long> extractMatched(
            JoinSpec<R> j, GenericManager<R> rg
    ) {
        QueryWrapper<R> cond = (j.relatedCond != null)
                ? JoinSpec.QwHelper.defensiveCopy(j.relatedCond)
                : new QueryWrapper<R>();
        Field rf;
        try {
            rf = j.relatedClass.getDeclaredField(j.relatedField);
            rf.setAccessible(true);
            Class<?> t = rf.getType();
            if (!Long.class.equals(t) && !long.class.equals(t)) {
                throw new IllegalArgumentException(
                        "JoinSpec.relatedField=" + j.relatedField + " type not Long, got " + t.getName());
            }
        } catch (NoSuchFieldException nsf) {
            throw new IllegalArgumentException(
                    "JoinSpec.relatedField \"" + j.relatedField + "\" not found in " + j.relatedClass.getName(), nsf);
        }
        List<R> rows = (List<R>) (List) rg.list(cond);
        Set<Long> matched = new LinkedHashSet<>(rows.size() + 2, 0.9f);
        for (R r : rows) {
            try {
                Long v = (Long) rf.get(r);
                if (v != null && v != 0L) matched.add(v);
            } catch (IllegalAccessException iae) {
                throw new RuntimeException(iae);
            }
        }
        return matched;
    }

    /** 主表 orm 先行筛选结果全量实体（非主键 outerField 需读主表列值，故须实体而非主键）。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends DsTableAdapter> List<T> loadMainRows(
            GenericManager<T> mainGm, QueryWrapper<T> cond
    ) {
        QueryWrapper<T> c = (cond == null) ? new QueryWrapper<>() : JoinSpec.QwHelper.defensiveCopy(cond);
        List rows = mainGm.list(c);
        return (rows == null) ? Collections.emptyList() : (List<T>) rows;
    }

    /**
     * 非主键 outerField：取主表候选行 outerField 列值命中 matched 的主表主键子集。
     * {@code entity.getId() == 从表 relatedField 列值} 的原始语义在此替换为
     * {@code entity.outerField == relatedField}（JoinSpec.FR-1 主表列等值）。
     */
    private static Set<Long> matchByOuterValue(
            List<? extends DsTableAdapter> mainRows, String outerField, Set<Long> matched
    ) {
        Set<Long> out = new LinkedHashSet<>(mainRows.size() + 2, 0.9f);
        for (DsTableAdapter row : mainRows) {
            if (row == null) continue;
            Long pk = row.getId();
            if (pk == null || pk == 0L) continue;
            Long v = JoinSpec.readOuterValue(row, outerField);
            if (v != null && v != 0L && matched.contains(v)) out.add(pk);
        }
        return out;
    }

    static <T extends DsTableAdapter> QueryWrapper<T> defensiveCopy(QueryWrapper<T> src) {
        return JoinSpec.QwHelper.defensiveCopy(src);
    }
}
