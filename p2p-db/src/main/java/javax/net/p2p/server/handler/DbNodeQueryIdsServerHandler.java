package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;
import java.io.File;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.DbNodeQueryIdsRequest;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.utils.SerializationUtil;

/**
 * 分布式节点分区 id 查询处理器。
 *
 * <p>
 * 按 anchorRowId 中的 serverId 从 {@link DsNodeContext} 找到对应节点的
 * {@link DsPartitionManager}，再按低 8 位 partitionId 定位具体分区，返回该分区 ids 集合按
 * offset/limit 截取后的结果。</p>
 *
 * <p>
 * P50 OQ12-㉘②：当 payload.query 含有效谓词时，先过滤命中 id 再施加 offset/limit（先过滤后分页）； query 为
 * null 或无条件时保持全量透传（兼容旧行为）。过滤只读、0 新线程。</p>
 *
 * <p>
 * P52 OQ12-㉚①：过滤段由 ORM「逐 id 全行物化」升级为列扫描下推
 * {@link ColumnScanPushdown#scan}（列级等值索引候选求交 + 列值残差校验），与 P50 现行为 R12 逐条等价，且输出恒
 * rowId 升序。</p>
 */
public class DbNodeQueryIdsServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.DB_NODE_QUERY_IDS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.DB_NODE_QUERY_IDS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof DbNodeQueryIdsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            if (payload.entityClassName == null || payload.entityClassName.isBlank()) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "missing entityClassName");
            }
            if (payload.anchorRowId <= 0L) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid anchorRowId");
            }

            DsPartitionManager manager = DsNodeContext.getManager(DsIdLayout.serverId(payload.anchorRowId));
            if (manager == null) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "server not registered");
            }

            ColumnarStore store = manager.getStore(payload.anchorRowId);
            File dbRoot = store.getDbRoot();

            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? 100 : payload.limit;

            // P52 列扫描下推（Q1=A）：列索引候选 + 列值残差 → 先过滤后分页；无谓词则全量透传。
            List<Long> list = ColumnScanPushdown.scan(
                    dbRoot, payload.entityClassName, payload.query, offset, limit);

            long[] out = new long[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = list.get(i);
            }
            byte[] idsBytes = SerializationUtil.serialize(out);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_DB_NODE_QUERY_IDS, new DbRowQueryIdsResponse(idsBytes));
        } catch (IllegalArgumentException e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, e.toString());
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
