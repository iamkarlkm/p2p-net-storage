package com.q3lives.ds.database.fulltext.cluster.parser.v3;

/**
 * AggSpec 不可变结构（P38 OQ1 DSL v3 聚合函数 MVP）：单个聚合项 = 函数 + 列名。
 *
 * <p>
 * 一个 {@code AggSpec} 描述对某一列做一次聚合：{@code fn = COUNT, column = "id"} 即
 * {@code COUNT(id)}。DSL 中写成 {@code AGG(COUNT(id), SUM(price))}，每个 {@code fn(col)}
 * 解析为一个 {@code AggSpec}，声明序即 {@code AggQueryPlan.aggs} 的顺序（R9 确定性）。
 *
 * <p>
 * 6 IAE 守卫（构造即校验，构造后不可变）：
 * <ol>
 * <li>{@code fn == null} → IAE</li>
 * <li>{@code column == null} → IAE</li>
 * <li>{@code column} 为空白串（{@code isBlank()}）→ IAE</li>
 * <li>{@code column} 含任意空白字符 → IAE</li>
 * <li>{@code column} 含非 {@code [A-Za-z0-9_]} 字符 → IAE</li>
 * <li>{@code column} 与保留字 {@code AGG} 冲突（大小写不敏感）→ IAE</li>
 * </ol>
 *
 * <p>
 * {@code equals/hashCode} 按 {@code (fn, column)} 严格判定，供 {@code AggQueryPlan} 的
 * {@code deepEquals} 跨进程指纹对齐；{@code toString()} 输出 {@code "COUNT(col)"} 形式
 * 便于调试与日志。
 *
 * @author SP3.1 Implement Agent
 * @since P38 OQ1 DSL v3 聚合函数 MVP
 */
public final class AggSpec {

    /** 聚合函数（非 null）。 */
    public final AggFn fn;

    /** 聚合列名（非空白，仅 [A-Za-z0-9_]，非保留字 AGG）。 */
    public final String column;

    /**
     * 构造 AggSpec 不可变实例（6 IAE 全检）。
     *
     * @param fn 聚合函数（非 null）
     * @param column 聚合列名（非 null / 非空白 / 仅 [A-Za-z0-9_] / 非 AGG）
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public AggSpec(AggFn fn, String column) {
        if (fn == null) {
            throw new IllegalArgumentException("AggSpec fn cannot be null");
        }
        if (column == null) {
            throw new IllegalArgumentException("AggSpec column cannot be null");
        }
        if (column.isBlank()) {
            throw new IllegalArgumentException("AggSpec column cannot be blank");
        }
        for (int i = 0; i < column.length(); i++) {
            char c = column.charAt(i);
            if (Character.isWhitespace(c)) {
                throw new IllegalArgumentException("AggSpec column 不允许含空白字符: " + column);
            }
            boolean ident = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ident) {
                throw new IllegalArgumentException("AggSpec column 含非法字符 '" + c + "': " + column);
            }
        }
        if (column.equalsIgnoreCase("AGG")) {
            throw new IllegalArgumentException("AggSpec column 与保留字 AGG 冲突: " + column);
        }
        this.fn = fn;
        this.column = column;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AggSpec)) {
            return false;
        }
        AggSpec that = (AggSpec) o;
        return fn == that.fn && column.equals(that.column);
    }

    @Override
    public int hashCode() {
        return 31 * fn.hashCode() + column.hashCode();
    }

    @Override
    public String toString() {
        return fn.name() + "(" + column + ")";
    }
}
