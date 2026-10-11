package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer.TokenEntry;

/**
 * HybridQueryParser 主解析器（MVP v1.0 @since P30）：手写 LL(1) 递归下降零副作用。
 * <ul>
 *   <li>仅做"DSL 字符串 → ParsedHybridQuery 不可变四元组"转换；绝不直接调用 ORM/FT/DHQC（零副作用）
 *   <li>4 参数 parse 主方法：entityClass + dsl + weightNameToExtractor + ltrNameToExtractor（后 2 个
 *       非空，允许 empty Map；LTR 列找不到 → IAE 带 ParsePos）
 *   <li>语法严格按 Spec EBNF：conjunction (AND 平铺单 FT) [WEIGHT(...)] [LTR(...)]
 *   <li>FT 子句最多 1 个，2 个及以上 IAE；OR/NOT 关键字 IAE；WEIGHT 在 LTR 之后 IAE；IN 空列表 IAE
 *   <li>ORM 条件 EQ/NE/GT/GE/LT/LE（6 个 op） + BETWEEN/IN/NOT_IN/LIKE(×3)/IS_NULL/IS_NOT_NULL
 *   <li>所有解析异常走 HybridQueryParseException（含 ParsePos line/col + 10 字符片段）
 * </ul>
 * 业务三选一合查方法（Facade 单调零合并）：Parser 仅输出四元组，业务判断 ltrSpec != null → 7 参 LTR；
 * 否则 alpha!=1 || beta!=0 || !columnWeights.isEmpty → 8 参加权；否则 5 参 baseline。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public final class HybridQueryParser {

    private HybridQueryParser() {}

    /**
     * 解析 DSL 字符串 → ParsedHybridQuery 不可变四元组（纯解析零副作用）。
     *
     * @param entityClass DsTableAdapter 子类，用于 new QueryWrapper<T>
     * @param dsl 原始 DSL（非 null，允许 blank → 后续 IAE 0 condition）
     * @param weightNameToExtractor WEIGHT 列名 → 提取函数（非空，允许 empty；weight 的 extractor
     *        暂不存 WeightSpec，业务层再拼 DHQC 8 参的 Map.Entry 对）
     * @param ltrNameToExtractor LTR 列名 → 提取函数（非空，允许 empty；LTR 列未找到 → IAE）
     * @return ParsedHybridQuery 四元组（非空）
     * @param <T> DsTableAdapter 实体类型
     * @throws NullPointerException 四参数任一 null
     * @throws HybridQueryParseException 解析结构/语法错误（含位置）
     * @throws IllegalArgumentException 范围错误（αβ/weight/空 DSL/0 condition/2 个 FT...）
     */
    public static <T extends DsTableAdapter> ParsedHybridQuery<T> parse(
            Class<T> entityClass,
            String dsl,
            Map<String, ToDoubleFunction<T>> weightNameToExtractor,
            Map<String, ToDoubleFunction<T>> ltrNameToExtractor) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(dsl, "dsl cannot be null");
        Objects.requireNonNull(weightNameToExtractor, "weightNameToExtractor cannot be null");
        Objects.requireNonNull(ltrNameToExtractor, "ltrNameToExtractor cannot be null");
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        DslTokenizer tz = new DslTokenizer(dsl);
        String ftQuery = "";
        int ftCount = 0;
        boolean weightSeen = false;
        boolean ltrSeen = false;
        WeightSpec ws = WeightSpec.DEFAULT;
        LtrSpec<T> ltr = null;
        int condCount = 0;
        while (true) {
            TokenEntry first = tz.peekToken(0);
            if (first == null) break;
            if (first.type == DslToken.KW_WEIGHT || first.type == DslToken.KW_LTR) {
                if (first.type == DslToken.KW_WEIGHT) {
                    if (ltrSeen) throw err(dsl, first.pos, "WEIGHT 必须在 LTR 之前");
                    if (weightSeen) throw err(dsl, first.pos, "重复 WEIGHT 段");
                    consume(tz);
                    ws = parseWeightClause(tz, dsl);
                    weightSeen = true;
                } else {
                    if (ltrSeen) throw err(dsl, first.pos, "重复 LTR 段");
                    consume(tz);
                    ltr = parseLtrClause(tz, dsl, ltrNameToExtractor);
                    ltrSeen = true;
                }
                TokenEntry after = tz.peekToken(0);
                if (after != null && after.type != DslToken.KW_WEIGHT && after.type != DslToken.KW_LTR) {
                    throw err(dsl, after.pos, "WEIGHT/LTR 段后仅允许 WEIGHT/LTR，实际: " + after.type);
                }
                continue;
            }
            if (weightSeen || ltrSeen) {
                throw err(dsl, first.pos, "WEIGHT/LTR 段后不允许再有 ORM/FT 条件: " + first.type);
            }
            if (first.type == DslToken.KW_OR) {
                throw err(dsl, first.pos, "MVP v1.0 仅支持 AND（禁止 OR/NOT）");
            }
            if (first.type == DslToken.KW_NOT) {
                throw err(dsl, first.pos, "MVP v1.0 仅支持 AND（独立 NOT 禁止，仅允许 IS NOT NULL）");
            }
            if (first.type == DslToken.KW_AND) {
                consume(tz);
                continue;
            }
            if (first.type == DslToken.KW_FT) {
                consume(tz);
                expect(tz, DslToken.LPAREN);
                TokenEntry s = expect(tz, DslToken.STRING);
                expect(tz, DslToken.RPAREN);
                if (ftCount >= 1) throw err(dsl, s.pos, "仅允许 1 个 FT 子句");
                ftCount++;
                ftQuery = ((String) s.value).trim();
            } else if (first.type == DslToken.IDENT) {
                applyOrmCondition(wrapper, tz, dsl);
            } else {
                throw err(dsl, first.pos, "未知开头 Token: " + first.type + " (期望 IDENT/FT/AND/WEIGHT/LTR)");
            }
            condCount++;
            TokenEntry afterCond = tz.peekToken(0);
            if (afterCond == null) break;
            if (afterCond.type == DslToken.KW_WEIGHT || afterCond.type == DslToken.KW_LTR) continue;
            if (afterCond.type != DslToken.KW_AND) {
                throw err(dsl, afterCond.pos, "条件后必须跟 AND/WEIGHT/LTR/EOF，实际: " + afterCond.type);
            }
        }
        if (condCount == 0) {
            throw new IllegalArgumentException(
                    "DSL 不能为空，至少 1 个 condition (line " + tz.position().startLine()
                            + ", col " + tz.position().startCol() + ")");
        }
        return new ParsedHybridQuery<>(wrapper, ftQuery, ws, ltr);
    }

    private static <T extends DsTableAdapter> void applyOrmCondition(
            QueryWrapper<T> wrapper, DslTokenizer tz, String dsl) {
        TokenEntry col = consume(tz);
        String colName = (String) col.value;
        TokenEntry op = consume(tz);
        switch (op.type) {
            case OP_EQ -> wrapper.eq(colName, toJava(consume(tz)));
            case OP_NE -> wrapper.ne(colName, toJava(consume(tz)));
            case OP_GT -> wrapper.gt(colName, toJava(consume(tz)));
            case OP_GE -> wrapper.ge(colName, toJava(consume(tz)));
            case OP_LT -> wrapper.lt(colName, toJava(consume(tz)));
            case OP_LE -> wrapper.le(colName, toJava(consume(tz)));
            case KW_BETWEEN -> {
                Object lo = toJava(consume(tz));
                expect(tz, DslToken.KW_AND);
                Object hi = toJava(consume(tz));
                wrapper.between(colName, lo, hi);
            }
            case KW_IN -> {
                expect(tz, DslToken.LPAREN);
                if (tz.peekToken(0).type == DslToken.RPAREN) {
                    TokenEntry r = tz.peekToken(0);
                    throw err(dsl, r.pos, "IN 列表不能为空");
                }
                List<Object> list = new ArrayList<>();
                list.add(toJava(consume(tz)));
                while (tz.peekToken(0) != null && tz.peekToken(0).type == DslToken.COMMA) {
                    consume(tz); list.add(toJava(consume(tz)));
                }
                expect(tz, DslToken.RPAREN);
                wrapper.in(colName, list);
            }
            case KW_NOT -> {
                TokenEntry nx = consume(tz);
                if (nx.type == DslToken.KW_IN) {
                    expect(tz, DslToken.LPAREN);
                    if (tz.peekToken(0).type == DslToken.RPAREN) {
                        throw err(dsl, nx.pos, "IN 列表不能为空");
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(toJava(consume(tz)));
                    while (tz.peekToken(0) != null && tz.peekToken(0).type == DslToken.COMMA) {
                        consume(tz); list.add(toJava(consume(tz)));
                    }
                    expect(tz, DslToken.RPAREN);
                    wrapper.notIn(colName, list);
                } else if (nx.type == DslToken.KW_NULL) {
                    wrapper.isNotNull(colName);
                } else {
                    throw err(dsl, nx.pos, "NOT 后仅允许 IN/NULL");
                }
            }
            case KW_IS -> {
                TokenEntry p = consume(tz);
                if (p.type == DslToken.KW_NOT) {
                    expect(tz, DslToken.KW_NULL);
                    wrapper.isNotNull(colName);
                } else if (p.type == DslToken.KW_NULL) {
                    wrapper.isNull(colName);
                } else {
                    throw err(dsl, p.pos, "IS 后仅允许 [NOT] NULL");
                }
            }
            case KW_LIKE -> {
                Object raw = toJava(consume(tz));
                String pat = raw == null ? "" : raw.toString();
                if (pat.length() >= 2 && pat.endsWith("%") && !pat.startsWith("%")) {
                    wrapper.likeRight(colName, pat.substring(0, pat.length() - 1));
                } else if (pat.length() >= 2 && pat.startsWith("%") && !pat.endsWith("%")) {
                    wrapper.likeLeft(colName, pat.substring(1));
                } else {
                    wrapper.like(colName, pat);
                }
            }
            default -> throw err(dsl, op.pos, "未支持 OP " + op.type);
        }
    }

    /* ---------------- LL(1) 私有递归下降 ---------------- */
    // 旧 collectConditions/consumeOrm/consumeCondition/consumeFt/consumeOrm 全部删除（重复逻辑 bug）

    private static WeightSpec parseWeightClause(DslTokenizer tz, String dsl) {
        expect(tz, DslToken.LPAREN);
        double alpha = toDouble(expect(tz, DslToken.NUMBER), dsl);
        expect(tz, DslToken.COMMA);
        double beta = toDouble(expect(tz, DslToken.NUMBER), dsl);
        Map<String, Double> cols = new LinkedHashMap<>();
        while (tz.peekToken(0) != null && tz.peekToken(0).type == DslToken.COMMA) {
            consume(tz);
            TokenEntry col = expect(tz, DslToken.IDENT);
            expect(tz, DslToken.OP_EQ);
            TokenEntry w = expect(tz, DslToken.NUMBER);
            cols.put((String) col.value, toDouble(w, dsl));
        }
        expect(tz, DslToken.RPAREN);
        return new WeightSpec(alpha, beta, cols);
    }

    private static <T extends DsTableAdapter> LtrSpec<T> parseLtrClause(
            DslTokenizer tz, String dsl, Map<String, ToDoubleFunction<T>> ltrMap) {
        expect(tz, DslToken.LPAREN);
        List<ToDoubleFunction<T>> fns = new ArrayList<>();
        List<Double> ws = new ArrayList<>();
        TokenEntry firstCol = expect(tz, DslToken.IDENT);
        expect(tz, DslToken.OP_EQ);
        TokenEntry firstW = expect(tz, DslToken.NUMBER);
        addLtrEntry(ltrMap, (String) firstCol.value, firstW, fns, ws, dsl);
        while (tz.peekToken(0) != null && tz.peekToken(0).type == DslToken.COMMA) {
            consume(tz);
            TokenEntry c = expect(tz, DslToken.IDENT);
            expect(tz, DslToken.OP_EQ);
            TokenEntry w = expect(tz, DslToken.NUMBER);
            addLtrEntry(ltrMap, (String) c.value, w, fns, ws, dsl);
        }
        expect(tz, DslToken.RPAREN);
        double[] wa = ws.stream().mapToDouble(Double::doubleValue).toArray();
        return new LtrSpec<>(fns, wa);
    }

    private static <T extends DsTableAdapter> void addLtrEntry(
            Map<String, ToDoubleFunction<T>> ltrMap, String col, TokenEntry wTok,
            List<ToDoubleFunction<T>> fns, List<Double> ws, String dsl) {
        ToDoubleFunction<T> fn = ltrMap.get(col);
        if (fn == null) {
            throw new IllegalArgumentException(
                    "LTR 列未在 ltrNameToExtractor 中找到: column=" + col
                            + " (line " + wTok.pos.startLine() + ", col " + wTok.pos.startCol() + ")");
        }
        fns.add(fn);
        ws.add(toDouble(wTok, dsl));
    }

    /* ---------------- 通用工具 ---------------- */

    private static TokenEntry consume(DslTokenizer tz) {
        TokenEntry e = tz.nextToken();
        if (e == null) throw err("EOF 但期望继续", tz.position(), "");
        return e;
    }

    private static TokenEntry expect(DslTokenizer tz, DslToken type) {
        TokenEntry e = consume(tz);
        if (e.type != type) throw err("期望 Token " + type + " 但得到 " + e.type, e.pos, e.value == null ? "" : e.value.toString());
        return e;
    }

    private static Object toJava(TokenEntry e) {
        return switch (e.type) {
            case NUMBER -> {
                double d = ((Number) e.value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) yield Long.valueOf((long) d);
                yield Double.valueOf(d);
            }
            case KW_TRUE -> Boolean.TRUE;
            case KW_FALSE -> Boolean.FALSE;
            case KW_NULL -> null;
            default -> e.value;
        };
    }

    private static double toDouble(TokenEntry e, String dsl) {
        Object v = e.value;
        if (v instanceof Number n) return n.doubleValue();
        throw err("期望 NUMBER 但得到 " + (v == null ? "null" : v.getClass().getSimpleName()), e.pos, dsl);
    }

    @SuppressWarnings("unchecked")
    private static <A> A cast(Object o) { return (A) o; }

    private static HybridQueryParseException err(String msg, ParsePos p, String frag) {
        String f = frag.length() > 10 ? frag.substring(0, 10) : frag;
        return new HybridQueryParseException(msg, p, f);
    }
}
