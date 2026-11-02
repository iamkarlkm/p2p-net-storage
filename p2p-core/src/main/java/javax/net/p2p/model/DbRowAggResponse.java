package javax.net.p2p.model;

/**
 * 服务端聚合响应（P53 OQ12-㉛②）：承载编码后的分组聚合结果字节。
 *
 * <p>
 * 结果以<b>分组结果矩阵</b>编码：每行 = 分组键列（按 {@code groupBy} 声明序）+
 * 聚合值列（按 {@code aggs} 声明序，恒为 {@code double}）。编码/解码由服务端聚合引擎
 * {@code ColumnAggPushdown#encode}/{@code #decode} 成对提供，字节格式为「行数 + 逐行
 * 列数 + 逐格类型标签 + 值」的紧凑二进制，无外部依赖、可跨进程稳定往返。</p>
 *
 * <p>
 * 与 {@link DbRowQueryIdsResponse} 保持一致的设计：仅暴露一个 {@code byte[]} 载荷字段，
 * 把具体编解码细节留在两端引擎中，模型层保持极简，便于 protostuff 序列化与后续演进
 * （例如未来追加列级元信息而不破坏既有字段）。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public class DbRowAggResponse {

    /** 编码后的分组聚合结果（{@code groupKey[] + aggValue[]} 逐行展开；可 null）。 */
    public byte[] payload;

    /**
     * protostuff 反序列化用无参构造。
     */
    public DbRowAggResponse() {
    }

    /**
     * 构造响应并携带编码后的分组结果。
     *
     * @param payload 编码后的分组聚合结果字节
     */
    public DbRowAggResponse(byte[] payload) {
        this.payload = payload;
    }
}
