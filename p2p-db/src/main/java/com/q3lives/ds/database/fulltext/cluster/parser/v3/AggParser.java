package com.q3lives.ds.database.fulltext.cluster.parser.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer.TokenEntry;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParseException;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsePos;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * AggParser 静态工具类（P38 OQ1 DSL v3 聚合函数 MVP）：DSL → {@link AggQueryPlan} 主入口。
 *
 * <p>
 * 3 阶段管线（纯解析，0 副作用）：
 * <ol>
 * <li><b>切分</b>：{@link #splitAtAgg(String)} 在 paren 深度 0 且跳过单引号字符串的前提下， 定位首个独立
 * {@code AGG} 词并匹配其外层括号，得「表达式段」+「聚合段」（R8 防误命中）。</li>
 * <li><b>表达式段</b>：直接复用 P34 {@link DnfParser#parseOr} 做 OR/NOT/括号 DNF 拆分
 * （{@link DnfReturnMode#DNF_ONLY}），得 {@link DnfClause}。</li>
 * <li><b>聚合段</b>：复用 P30 {@link DslTokenizer} 扫描 {@code aggItem (',' aggItem)*}，
 * 每项解析为 {@link AggSpec}，最后组装 {@link AggQueryPlan}。</li>
 * </ol>
 *
 * <p>
 * 与 P30/P34 的关系（关键约束）：
 * <ul>
 * <li>{@code AGG} / {@code COUNT} / {@code SUM} / {@code AVG} / {@code MIN} /
 * {@code MAX} 均为 <b>未注册关键字</b>，词法层落 {@code IDENT} —— P30 {@code DslToken} /
 * {@code DslTokenizer} 源码字节级 0 改。</li>
 * <li>本类为 NEW 独立类，不改 {@code HybridQueryParser}/{@code DnfParser} 任何字段与方法体。</li>
 * <li>只产计划（N 个 {@code (minterm, aggFn)} 对），<b>不</b> 调用 ORM/FT/DHQC、不做聚合合并
 * （执行与合并归业务方，MVP 边界）。</li>
 * </ul>
 *
 * <p>
 * 6 IAE / 异常守卫：{@code entityClass == null} / {@code dsl == null} / {@code maxAggCount < 1} /
 * {@code maxAggCount > MAX_AGG} / 表达式段为空 → {@link IllegalArgumentException}；
 * {@code AGG} 子句语法非法（缺 {@code '('} / 非法函数名 / 非法列 / 括号不匹配 / 多余 token） →
 * {@link HybridQueryParseException}。二者均不静默忽略。
 *
 * @author SP3.1 Implement Agent
 * @since P38 OQ1 DSL v3 聚合函数 MVP
 */
public final class AggParser {

    private AggParser() {
    }

    /**
     * 解析 DSL 字符串 → {@link AggQueryPlan} 不可变实例。
     *
     * @param entityClass 实体类（非 null；透传 DnfParser 做列名校验用）
     * @param dsl DSL 字符串（非 null；尾部可含 {@code AGG(aggItem, ...)}）
     * @param weightMap 允许 null / empty（透传 P34 DnfParser）
     * @param ltrMap 允许 null / empty（同上）
     * @param maxDnfSize 传给 {@link DnfParser#parseOr} 的 DNF
     * 上限（1..{@code DnfParser.MAX_DNF_SIZE}）
     * @param maxAggCount 聚合项上限（1..{@link AggQueryPlan#MAX_AGG}）
     * @param <T> DsTableAdapter 实体
     * @return AggQueryPlan 不可变实例
     * @throws IllegalArgumentException null 入参 / maxAggCount 越界 / 表达式段为空
     * @throws HybridQueryParseException AGG 子句语法非法（或缺 '(' / 非法函数名 / 多余 token 等）
     */
    public static <T extends DsTableAdapter> AggQueryPlan parse(
            Class<T> entityClass,
            String dsl,
            Map<String, ToDoubleFunction<T>> weightMap,
            Map<String, ToDoubleFunction<T>> ltrMap,
            int maxDnfSize,
            int maxAggCount) {
        if (entityClass == null) {
            throw new IllegalArgumentException("entityClass cannot be null");
        }
        if (dsl == null) {
            throw new IllegalArgumentException("dsl cannot be null");
        }
        if (maxAggCount < 1) {
            throw new IllegalArgumentException("maxAggCount 必须 ≥ 1: " + maxAggCount);
        }
        if (maxAggCount > AggQueryPlan.MAX_AGG) {
            throw new IllegalArgumentException("maxAggCount 超过硬上限 " + AggQueryPlan.MAX_AGG + ": " + maxAggCount);
        }
        String[] parts = splitAtAgg(dsl);
        String expr = parts[0];
        String aggInner = parts[1];
        if (expr == null || expr.trim().isEmpty()) {
            throw new IllegalArgumentException("表达式段为空（AGG 前无查询条件）");
        }
        DnfClause clause = DnfParser.parseOr(entityClass, expr, weightMap, ltrMap, maxDnfSize,
                DnfReturnMode.DNF_ONLY);
        List<AggSpec> aggs = aggInner == null ? new ArrayList<>() : parseAggItems(aggInner, maxAggCount);
        return new AggQueryPlan(clause, aggs);
    }

    /* ============================================================
     *  阶段 1：AGG 切分（R8 防字符串/括号误命中）
     * ============================================================ */
    /**
     * 切分 DSL 为 {@code [表达式段, 聚合段内部]}。
     * <ul>
     * <li>无 top-level {@code AGG} → 返回 {@code [dsl, null]}</li>
     * <li>有 → 表达式段 = AGG 之前；聚合段内部 = AGG 外层括号内文本</li>
     * </ul>
     * 仅认 <b>首个</b> paren 深度 0 且后随 {@code '('} 的独立 {@code AGG} 词；其后存在非空白 残留 →
     * {@link HybridQueryParseException}。
     */
    static String[] splitAtAgg(String dsl) {
        int depth = 0;
        boolean inStr = false;
        int n = dsl.length();
        for (int i = 0; i < n; i++) {
            char c = dsl.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inStr = false;
                }
                continue;
            }
            if (c == '\'') {
                inStr = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && (c == 'A' || c == 'a') && matchesAggAt(dsl, i)) {
                int j = i + 3;
                while (j < n && Character.isWhitespace(dsl.charAt(j))) {
                    j++;
                }
                if (j >= n || dsl.charAt(j) != '(') {
                    // 独立 AGG 但后随非 '(' → 视为普通标识符（列名），继续向后扫描
                    continue;
                }
                int close = matchParen(dsl, j);
                if (close < 0) {
                    throw err("AGG 子句括号不匹配", dsl, i);
                }
                String rest = dsl.substring(close + 1).trim();
                if (!rest.isEmpty()) {
                    throw err("AGG 子句后存在多余 token: " + rest, dsl, close + 1);
                }
                return new String[]{dsl.substring(0, i), dsl.substring(j + 1, close)};
            }
        }
        return new String[]{dsl, null};
    }

    private static boolean matchesAggAt(String s, int i) {
        if (i + 3 > s.length()) {
            return false;
        }
        if (!s.regionMatches(true, i, "AGG", 0, 3)) {
            return false;
        }
        if (i > 0 && isIdentChar(s.charAt(i - 1))) {
            return false;
        }
        return i + 3 >= s.length() || !isIdentChar(s.charAt(i + 3));
    }

    private static boolean isIdentChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private static int matchParen(String s, int open) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inStr = false;
                }
                continue;
            }
            if (c == '\'') {
                inStr = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /* ============================================================
     *  阶段 3：聚合项解析
     * ============================================================ */
    private static List<AggSpec> parseAggItems(String aggInner, int maxAggCount) {
        DslTokenizer tz = new DslTokenizer(aggInner);
        List<AggSpec> out = new ArrayList<>();
        while (true) {
            TokenEntry fnTok = tz.nextToken();
            if (fnTok == null) {
                if (out.isEmpty()) {
                    throw err("AGG 子句为空（至少 1 个聚合项）", aggInner, 0);
                }
                throw err("',' 后缺少聚合项", aggInner, aggInner.length());
            }
            if (fnTok.type != DslToken.IDENT) {
                throw err("聚合函数名必须为标识符，得到 " + fnTok.type, aggInner, 0);
            }
            AggFn fn = AggFn.fromName(String.valueOf(fnTok.value));
            expect(tz, DslToken.LPAREN, aggInner);
            TokenEntry colTok = expect(tz, DslToken.IDENT, aggInner);
            expect(tz, DslToken.RPAREN, aggInner);
            out.add(new AggSpec(fn, String.valueOf(colTok.value)));
            TokenEntry next = tz.peekToken(0);
            if (next == null) {
                break;
            }
            if (next.type != DslToken.COMMA) {
                throw err("聚合项之间仅允许 ','，得到 " + next.type, aggInner, 0);
            }
            tz.nextToken();
        }
        if (out.size() > maxAggCount) {
            throw new IllegalArgumentException("聚合项 " + out.size() + " 超过 maxAggCount=" + maxAggCount);
        }
        return out;
    }

    private static TokenEntry expect(DslTokenizer tz, DslToken type, String src) {
        TokenEntry e = tz.nextToken();
        if (e == null) {
            throw err("意外结尾，期望 " + type, src, src.length());
        }
        if (e.type != type) {
            throw err("期望 " + type + " 但得到 " + e.type, src, 0);
        }
        return e;
    }

    private static HybridQueryParseException err(String msg, String src, int idx) {
        int line = 1;
        int col = 1;
        int bound = Math.min(idx, src.length());
        for (int i = 0; i < bound; i++) {
            if (src.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        int from = Math.max(0, idx - 5);
        int to = Math.min(src.length(), idx + 5);
        return new HybridQueryParseException(msg, ParsePos.single(line, col), src.substring(from, to));
    }
}
