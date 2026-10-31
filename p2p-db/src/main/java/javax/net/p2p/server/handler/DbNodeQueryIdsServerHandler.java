package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.schema.DynamicIndexUtil;
import java.io.File;
import java.util.ArrayList;
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
 * P50 OQ12-㉘②：当 payload.query 含有效谓词时，先经 {@link DbQueryToQueryWrapper} 复用 ORM
 * 谓词引擎过滤命中 id，再施加 offset/limit（先过滤后分页）；query 为 null 或 无条件时保持全量透传（兼容旧行为）。过滤只读、0
 * 新线程。</p>
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
            DsHashSet ids = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, payload.entityClassName));

            int offset = Math.max(0, payload.offset);
            int limit = payload.limit <= 0 ? 100 : payload.limit;

            ArrayList<Long> list = new ArrayList<>();
            for (Long idObj : ids) {
                if (idObj == null || idObj <= 0L) {
                    continue;
                }
                list.add(idObj);
            }
            list.sort(null);

            // P50 谓词过滤下推（Q1=A）：先过滤后分页；无谓词则全量透传。
            list = new ArrayList<>(DbQueryToQueryWrapper.filterIdsByPredicate(
                    payload.entityClassName, payload.query, list));

            int from = Math.min(offset, list.size());
            int to = Math.min(from + limit, list.size());
            long[] out = new long[to - from];
            for (int i = from; i < to; i++) {
                out[i - from] = list.get(i);
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
