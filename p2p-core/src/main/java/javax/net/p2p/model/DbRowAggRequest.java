package javax.net.p2p.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端聚合请求（P53 OQ12-㉛②）：一次「谓词 + 分组 + 聚合 + HAVING + ORDER BY + 分页」下发。
 *
 * <p>
 * 字段语义：
 * <ul>
 * <li>{@code entityClassName}：目标实体类名（非空白；服务端据此定位列元与列值）。</li>
 * <li>{@code query}：行谓词（{@link DbQuery}，复用 P50/P52 口径，由列扫描下推取值）。</li>
 * <li>{@code aggs}：聚合项列表（非空；声明序 = 输出聚合列序）。</li>
 * <li>{@code groupBy}：分组键列名（可空；空 = 全局聚合无分组）。</li>
 * <li>{@code having}：分组后过滤谓词（可空；左值为分组键列名或聚合别名 {@code FN(col)}）。</li>
 * <li>{@code orderBy}：结果排序（可空；按分组键列名或聚合别名 {@code FN(col)} 排序）。</li>
 * <li>{@code offset}/{@code limit}：施加于「HAVING 过滤 + 排序后」结果的分页（{@code limit<=0} 表示不限）。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 本类为纯数据载体，不承载校验与执行逻辑：字段校验由服务端 {@code DbRowAggServerHandler}
 * 与聚合引擎 {@code ColumnAggPushdown} 在系统边界完成，以保持模型可被 protostuff 稳定
 * 序列化（公有无参构造 + 公有字段）。</p>
 *
 * <p>
 * 与 {@link DbQuery} 的分工：{@code DbQuery} 只描述行谓词（{@code where/anyOf/orderBy}），
 * 不承载聚合语义；聚合语义集中在本请求，避免污染既有 id 查询模型（Q1=B 路线）。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public class DbRowAggRequest {

    /** 目标实体类名（非空白）。 */
    public String entityClassName;

    /** 行谓词（可 null；null 或无谓词 → 分区全量）。 */
    public DbQuery query;

    /** 聚合项列表（非空；声明序 = 输出聚合列序）。 */
    public List<DbAggItem> aggs;

    /** 分组键列名列表（可空；空 = 全局聚合无分组）。 */
    public List<String> groupBy;

    /** 分组后过滤谓词（可空；左值为分组键列名或聚合别名 {@code FN(col)}）。 */
    public List<DbQueryCriterion> having;

    /** 结果排序（可空；按分组键列名或聚合别名 {@code FN(col)}）。 */
    public List<DbQueryOrder> orderBy;

    /** 分页偏移（负值按 0 处理）。 */
    public int offset;

    /** 分页条数（{@code <=0} 表示不限）。 */
    public int limit;

    /**
     * protostuff 反序列化用无参构造。
     */
    public DbRowAggRequest() {
        this.aggs = new ArrayList<>();
        this.groupBy = new ArrayList<>();
        this.having = new ArrayList<>();
        this.orderBy = new ArrayList<>();
    }

    /**
     * 全参构造（不做校验；由服务端边界与聚合引擎负责守卫）。
     *
     * @param entityClassName 目标实体类名
     * @param query           行谓词（可 null）
     * @param aggs            聚合项（非空由服务端校验）
     * @param groupBy         分组键（可 null）
     * @param having          分组后过滤（可 null）
     * @param orderBy         结果排序（可 null）
     * @param offset          分页偏移
     * @param limit           分页条数
     */
    public DbRowAggRequest(String entityClassName, DbQuery query, List<DbAggItem> aggs,
            List<String> groupBy, List<DbQueryCriterion> having, List<DbQueryOrder> orderBy,
            int offset, int limit) {
        this.entityClassName = entityClassName;
        this.query = query;
        this.aggs = aggs;
        this.groupBy = groupBy;
        this.having = having;
        this.orderBy = orderBy;
        this.offset = offset;
        this.limit = limit;
    }
}
