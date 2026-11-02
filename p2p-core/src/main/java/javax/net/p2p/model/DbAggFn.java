package javax.net.p2p.model;

/**
 * 服务端聚合函数枚举（P53 OQ12-㉛② 服务端聚合函数下发）。
 *
 * <p>
 * 描述一次服务端聚合所使用的聚合函数，配合 {@link DbAggItem#column} 组成形如
 * {@code COUNT(price)} / {@code SUM(amount)} 的聚合项。取值与原客户端本地聚合
 * （fulltext {@code parser/v3/AggFn}）保持一一对应，确保「服务端列值聚合 ≡ 客户端实体聚合」
 * 的逐值等价口径（R7）。</p>
 *
 * <p>
 * 语义约定（与 P39 {@code AggExecutor} 完全对齐）：
 * <ul>
 * <li>{@code COUNT}：统计非 NULL 值的个数；空组（全 NULL）返回 {@code 0}。</li>
 * <li>{@code SUM}：非 NULL 值求和；全 NULL 组返回 {@code 0.0}。</li>
 * <li>{@code AVG}：先累加非 NULL 值再除以个数；空组返回 {@code 0.0}。</li>
 * <li>{@code MIN}/{@code MAX}：非 NULL 值的最值；空组返回 {@code 0.0}。</li>
 * </ul>
 * </p>
 *
 * <p>
 * 本类型仅承载「函数名」这一维信息，不含任何行为实现，避免把执行逻辑下沉到模型层，
 * 保证模型可随 protostuff 稳定序列化（无状态、无引用字段）。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public enum DbAggFn {

    /** 计数聚合：统计非 NULL 值个数，空组为 0。 */
    COUNT,

    /** 求和聚合：非 NULL 值求和，全 NULL 组为 0.0。 */
    SUM,

    /** 平均聚合：非 NULL 值先累加后除以个数，空组为 0.0。 */
    AVG,

    /** 最小值聚合：非 NULL 值最小值，空组为 0.0。 */
    MIN,

    /** 最大值聚合：非 NULL 值最大值，空组为 0.0。 */
    MAX
}
