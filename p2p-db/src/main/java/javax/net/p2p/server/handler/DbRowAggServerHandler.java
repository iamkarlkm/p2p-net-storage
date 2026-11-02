package javax.net.p2p.server.handler;

import java.io.File;
import java.util.List;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbRowAggRequest;
import javax.net.p2p.model.DbRowAggResponse;
import javax.net.p2p.model.P2PWrapper;

/**
 * 服务端聚合下推处理器（P53 OQ12-㉛② DB_ROW_AGG）。
 *
 * <p>
 * 处理流程：命令校验 → 载荷类型/实体名校验 → 定位列存根 → 复用 P52
 * {@link ColumnScanPushdown#filter} 取命中 rowId（谓词在服务端列存上求交，0 改复用）→
 * {@link ColumnAggPushdown#compute} 在列值上做 GROUP BY + COUNT/SUM/AVG/MIN/MAX + HAVING +
 * ORDER BY + 分页 → {@link ColumnAggPushdown#encode} 编码 → 以
 * {@link P2PCommand#R_OK_DB_ROW_AGG} 回传 {@link DbRowAggResponse#payload}。</p>
 *
 * <p>
 * 该 handler 落既有 {@code javax.net.p2p.server.handler} 包：由 {@code AbstractUdpMessageProcessor}
 * 的包扫描注册机制（{@code scannerClass("javax.net.p2p.server.handler")}）自动发现，public 无参构造 +
 * {@link P2PCommandHandler} 实现即完成注册，无需改动任何注册文件或 SPI 服务文件（Q4 实证路径）。</p>
 *
 * <p>
 * 错误码口径对齐 {@code DbRowQueryIdsServerHandler}：{@link IllegalArgumentException}（参数/谓词非法）
 * → {@code INVALID_REQUEST}；其余异常 → {@code INTERNAL_ERROR}；命令不匹配 → {@code ROUTING_HANDLER_MISMATCH}。
 * handler 内联计算、0 新线程、0 写盘（R8/R10）。</p>
 *
 * @author SP3.1 Implement Agent
 * @since P53 OQ12-㉛② 服务端聚合函数下发 / ORDER BY 服务端排序
 */
public class DbRowAggServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_ROW_AGG;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_ROW_AGG) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbRowAggRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.aggs == null || payload.aggs.isEmpty()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing aggs");
            }
            DbQuery query = payload.query == null ? new DbQuery() : payload.query;
            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? 100 : payload.limit;

            DsDatabaseLocal db = DsDatabaseLocal.load();
            File dbRoot = db.getRoot();

            TableMetaStore metaStore = new TableMetaStore(dbRoot);
            TableMetaStore.TableMeta meta = metaStore.getMeta(payload.entityClassName);
            if (meta.signature == null || meta.signature.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing table meta");
            }

            List<Long> matched = ColumnScanPushdown.filter(dbRoot, payload.entityClassName, query);
            List<Object[]> rows = ColumnAggPushdown.compute(dbRoot, payload.entityClassName, matched,
                    payload.aggs, payload.groupBy, payload.having, payload.orderBy, offset, limit);
            byte[] out = ColumnAggPushdown.encode(rows);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_ROW_AGG, new DbRowAggResponse(out));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
