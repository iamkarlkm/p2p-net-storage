package com.q3lives.ds.database.fulltext.cluster.parser.v2;

import java.util.Objects;

import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;

/**
 * ConditionNode 不可变结构（P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 MVP）。
 *
 * <p>
 * 单条件节点 = FT 段 / ORM 列段 / IS NULL / IS NOT NULL；kind 四选一必填其一。
 * <ul>
 * <li>kind=FT：ftQuery 必填且非 blank；ormCol/ormOp/ormValueA/ormValueB 必须 null</li>
 * <li>kind=ORM：ormCol/ormOp/ormValueA 必填；ormValueB 仅 BETWEEN 必填，其他 null</li>
 * <li>kind=IS_NULL：ormCol 必填，ormOp=KW_IS，ormValueA/B 必须 null</li>
 * <li>kind=IS_NOT_NULL：ormCol 必填，ormOp=KW_IS，ormValueA/B 必须 null</li>
 * </ul>
 *
 * <p>
 * 8 IAE 守卫（与 P30 ParsedHybridQuery deepEquals 同款 strict 校验）：
 * <ol>
 * <li>kind == null → IAE</li>
 * <li>kind=FT 但 ftQuery == null/blank → IAE</li>
 * <li>kind=FT 但 ormCol/ormOp/ormValueA/ormValueB 任意非 null → IAE</li>
 * <li>kind=ORM 但 ftQuery != null → IAE</li>
 * <li>kind=ORM 但 ormCol == null/blank → IAE</li>
 * <li>kind=ORM 但 ormOp == null → IAE</li>
 * <li>kind=ORM BETWEEN 但 ormValueB == null → IAE</li>
 * <li>kind=ORM 非 BETWEEN 但 ormValueB != null → IAE</li>
 * </ol>
 *
 * <p>
 * IS_NULL/IS_NOT_NULL 额外校验：ormCol 非空 + ormOp == KW_IS + ormValueA/B == null。
 *
 * <p>
 * 解析流程：DnfParser.parseOr 第 3 阶段 distribute AND over OR 完成后，每条 minterm 内的每个
 * ConditionNode 都经历此 8 重 IAE 校验；任一不通过抛 HybridQueryParseException（含 ParsePos）。
 *
 * <p>
 * 业务调用方拿 DnfClause 后，可对每个 ConditionNode 做 hash/equals 比较（同 kind/op/col/a/b 100%
 * 全等） 用于跨进程 DnfClause deepEquals 指纹对齐（T4 TR4.2）。
 *
 * @author SP3.1 Implement Agent
 * @since P34 OQ1 DSL v2 MVP
 */
public final class ConditionNode {

    public enum Kind {
        FT, ORM, IS_NULL, IS_NOT_NULL
    }

    public final Kind kind;
    public final String ftQuery;
    public final String ormCol;
    public final DslToken ormOp;
    public final Object ormValueA;
    public final Object ormValueB;

    /**
     * 构造 ConditionNode 不可变节点（8 IAE 全检 + IS_NULL/IS_NOT_NULL 额外校验）。
     *
     * @param kind FT/ORM/IS_NULL/IS_NOT_NULL（必填）
     * @param ftQuery kind=FT 必填且非 blank；其他 null
     * @param ormCol kind=ORM/IS_NULL/IS_NOT_NULL 必填且非 blank；其他 null
     * @param ormOp kind=ORM 必填；IS_NULL/IS_NOT_NULL 必填 KW_IS；FT null
     * @param ormValueA kind=ORM 必填（允许 null 表示 IS NULL）；IS_NULL/IS_NOT_NULL 必须
     * null
     * @param ormValueB BETWEEN 必填；其他 null
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public ConditionNode(Kind kind, String ftQuery, String ormCol, DslToken ormOp,
            Object ormValueA, Object ormValueB) {
        if (kind == null) {
            throw new IllegalArgumentException("kind cannot be null");
        }
        if (kind == Kind.FT) {
            if (ftQuery == null || ftQuery.isBlank()) {
                throw new IllegalArgumentException("FT 段 ftQuery 必填且非 blank");
            }
            if (ormCol != null || ormOp != null || ormValueA != null || ormValueB != null) {
                throw new IllegalArgumentException("FT 段不允许 ormCol/ormOp/ormValueA/ormValueB 非 null");
            }
        } else if (kind == Kind.IS_NULL || kind == Kind.IS_NOT_NULL) {
            if (ftQuery != null) {
                throw new IllegalArgumentException("IS_NULL/IS_NOT_NULL ftQuery 必须 null");
            }
            if (ormCol == null || ormCol.isBlank()) {
                throw new IllegalArgumentException("IS_NULL/IS_NOT_NULL ormCol 必填且非 blank");
            }
            if (ormOp != DslToken.KW_IS) {
                throw new IllegalArgumentException("IS_NULL/IS_NOT_NULL ormOp 必须 KW_IS");
            }
            if (ormValueA != null || ormValueB != null) {
                throw new IllegalArgumentException("IS_NULL/IS_NOT_NULL 不允许 ormValueA/B 非 null");
            }
        } else { // ORM
            if (ftQuery != null) {
                throw new IllegalArgumentException("ORM 段 ftQuery 必须 null");
            }
            if (ormCol == null || ormCol.isBlank()) {
                throw new IllegalArgumentException("ORM 段 ormCol 必填且非 blank");
            }
            if (ormOp == null) {
                throw new IllegalArgumentException("ORM 段 ormOp 必填");
            }
            if (ormValueB != null && ormOp != DslToken.KW_BETWEEN) {
                throw new IllegalArgumentException("非 BETWEEN 不允许 ormValueB 非 null: " + ormOp);
            }
            if (ormValueB == null && ormOp == DslToken.KW_BETWEEN) {
                throw new IllegalArgumentException("BETWEEN 必须 ormValueB 非 null");
            }
        }
        this.kind = kind;
        this.ftQuery = ftQuery;
        this.ormCol = ormCol;
        this.ormOp = ormOp;
        this.ormValueA = ormValueA;
        this.ormValueB = ormValueB;
    }

    /**
     * FT 段便捷工厂。
     */
    public static ConditionNode ft(String ftQuery) {
        return new ConditionNode(Kind.FT, Objects.requireNonNull(ftQuery, "ftQuery").trim(),
                null, null, null, null);
    }

    /**
     * ORM 段便捷工厂（单值）。
     */
    public static ConditionNode orm(String col, DslToken op, Object valueA) {
        return new ConditionNode(Kind.ORM, null, col, op, valueA, null);
    }

    /**
     * ORM 段便捷工厂（BETWEEN）。
     */
    public static ConditionNode between(String col, Object valueA, Object valueB) {
        return new ConditionNode(Kind.ORM, null, col, DslToken.KW_BETWEEN, valueA, valueB);
    }

    /**
     * IS NULL 便捷工厂。
     */
    public static ConditionNode isNull(String col) {
        return new ConditionNode(Kind.IS_NULL, null, Objects.requireNonNull(col, "col"), DslToken.KW_IS, null, null);
    }

    /**
     * IS NOT NULL 便捷工厂。
     */
    public static ConditionNode isNotNull(String col) {
        return new ConditionNode(Kind.IS_NOT_NULL, null, Objects.requireNonNull(col, "col"), DslToken.KW_IS, null, null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConditionNode)) {
            return false;
        }
        ConditionNode that = (ConditionNode) o;
        return kind == that.kind
                && Objects.equals(ftQuery, that.ftQuery)
                && Objects.equals(ormCol, that.ormCol)
                && ormOp == that.ormOp
                && Objects.equals(ormValueA, that.ormValueA)
                && Objects.equals(ormValueB, that.ormValueB);
    }

    @Override
    public int hashCode() {
        int h = 1;
        h = 31 * h + (kind == null ? 0 : kind.hashCode());
        h = 31 * h + (ftQuery == null ? 0 : ftQuery.hashCode());
        h = 31 * h + (ormCol == null ? 0 : ormCol.hashCode());
        h = 31 * h + (ormOp == null ? 0 : ormOp.hashCode());
        h = 31 * h + (ormValueA == null ? 0 : ormValueA.hashCode());
        h = 31 * h + (ormValueB == null ? 0 : ormValueB.hashCode());
        return h;
    }

    @Override
    public String toString() {
        if (kind == Kind.FT) {
            return "FT(" + ftQuery + ")";
        }
        if (kind == Kind.IS_NULL) {
            return ormCol + " IS NULL";
        }
        if (kind == Kind.IS_NOT_NULL) {
            return ormCol + " IS NOT NULL";
        }
        if (ormOp == DslToken.KW_BETWEEN) {
            return ormCol + " BETWEEN " + ormValueA + " AND " + ormValueB;
        }
        String opStr;
        switch (ormOp) {
            case OP_EQ:
                opStr = "=";
                break;
            case OP_NE:
                opStr = "!=";
                break;
            case OP_GT:
                opStr = ">";
                break;
            case OP_GE:
                opStr = ">=";
                break;
            case OP_LT:
                opStr = "<";
                break;
            case OP_LE:
                opStr = "<=";
                break;
            case KW_BETWEEN:
                opStr = "BETWEEN";
                break;
            case KW_IN:
                opStr = "IN";
                break;
            case KW_IS:
                opStr = "IS";
                break;
            case KW_LIKE:
                opStr = "LIKE";
                break;
            default:
                opStr = ormOp.name();
        }
        return ormCol + " " + opStr + " " + ormValueA;
    }
}
