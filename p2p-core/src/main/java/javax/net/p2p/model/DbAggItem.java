package javax.net.p2p.model;

/**
 * 服务端聚合项（P53 OQ12-㉛②）：{@code 函数 + 列名} 二元组。
 *
 * <p>
 * 一个 {@link DbAggItem} 描述对某一列做一次聚合：{@code fn = COUNT, column = "price"} 即
 * {@code COUNT(price)}。请求 {@link DbRowAggRequest#aggs} 中各项的<b>声明序</b>即服务端聚合
 * 结果的输出列序（R6 结果确定性）。</p>
 *
 * <p>
 * 构造即校验（6 条 IAE，对齐 P38 {@code AggSpec} 口径）：
 * <ol>
 * <li>{@code fn == null} → IAE</li>
 * <li>{@code column == null} → IAE</li>
 * <li>{@code column} 为空白串（{@code isBlank()}）→ IAE</li>
 * <li>{@code column} 含任意空白字符 → IAE</li>
 * <li>{@code column} 含非 {@code [A-Za-z0-9_]} 字符 → IAE</li>
 * <li>{@code column} 与保留字 {@code AGG} 冲突（大小写不敏感）→ IAE</li>
 * </ol>
 * </p>
 *
 * <p>
 * 为兼容 protostuff 反射反序列化，本类保留公有无参构造与公有字段；带参构造承载全部守卫，
 * 服务端引擎在读取聚合项时亦会复检 {@code fn}/{@code column} 合法性，双重兜底。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public class DbAggItem {

    /** 聚合函数（非 null）。 */
    public DbAggFn fn;

    /** 聚合列名（非空白，仅 [A-Za-z0-9_]，非保留字 AGG）。 */
    public String column;

    /**
     * protostuff 反序列化用无参构造（不做校验，字段随后由反射填充）。
     */
    public DbAggItem() {
    }

    /**
     * 构造聚合项并执行 6 条 IAE 校验。
     *
     * @param fn     聚合函数（非 null）
     * @param column 聚合列名（非 null / 非空白 / 仅 [A-Za-z0-9_] / 非 AGG）
     * @throws IllegalArgumentException 任一守卫触发
     */
    public DbAggItem(DbAggFn fn, String column) {
        if (fn == null) {
            throw new IllegalArgumentException("DbAggItem fn cannot be null");
        }
        if (column == null) {
            throw new IllegalArgumentException("DbAggItem column cannot be null");
        }
        if (column.isBlank()) {
            throw new IllegalArgumentException("DbAggItem column cannot be blank");
        }
        for (int i = 0; i < column.length(); i++) {
            char c = column.charAt(i);
            if (Character.isWhitespace(c)) {
                throw new IllegalArgumentException("DbAggItem column 不允许含空白字符: " + column);
            }
            boolean ident = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ident) {
                throw new IllegalArgumentException("DbAggItem column 含非法字符 '" + c + "': " + column);
            }
        }
        if (column.equalsIgnoreCase("AGG")) {
            throw new IllegalArgumentException("DbAggItem column 与保留字 AGG 冲突: " + column);
        }
        this.fn = fn;
        this.column = column;
    }

    /**
     * 别名形式：{@code FN(col)}，例如 {@code SUM(price)}；供 HAVING / ORDER BY 引用聚合列。
     *
     * @return 聚合别名（非 null）
     */
    public String alias() {
        return fn + "(" + column + ")";
    }
}
