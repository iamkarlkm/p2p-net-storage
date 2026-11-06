package javax.net.p2p.server.handler;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.remote.DbEntityRelationsCodec;
import com.q3lives.utils.SerializationUtil;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.OrmGetOneAck;
import javax.net.p2p.model.OrmGetOneRequest;
import javax.net.p2p.model.P2PWrapper;
import java.nio.ByteBuffer;
import java.util.*;

public class OrmGetOneServerHandler implements P2PCommandHandler {

    public OrmGetOneServerHandler() {}

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.ORM_GET_ONE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public P2PWrapper process(P2PWrapper req) {
        try {
            P2PCommand cmd = req.getCommand();
            if (cmd != P2PCommand.ORM_GET_ONE) {
                return P2PErrors.stdError(req.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            Object data = req.getData();
            if (!(data instanceof OrmGetOneRequest)) {
                return P2PErrors.stdError(req.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            OrmGetOneRequest payload = (OrmGetOneRequest) data;
            int[] tgt = Tgt3RegistryResolver.resolveOrmTarget(payload.targetServerId, payload.targetPartitionId, payload.globalRowId);
            DsDatabaseLocal localDb = Tgt3RegistryResolver.resolveOrm(tgt[0], tgt[1]);
            Class<? extends DsTableAdapter> clazz = Class.forName(payload.className).asSubclass(DsTableAdapter.class);
            OrmWarmUpHelper.warmUp(localDb, clazz);
            QueryWrapper<?> w = payload.queryWrapperBytes == null ? new QueryWrapper() :
                    SerializationUtil.deserialize(QueryWrapper.class, payload.queryWrapperBytes);
            GenericManager<? extends DsTableAdapter> gm = new GenericManager(clazz, localDb, tgt[0], tgt[1]);
            DsTableAdapter e = gm.getOne(w);
            byte[] eb = e == null ? null : e.toBytes().array();
            byte[] rb = e == null ? null : DbEntityRelationsCodec.encode(e);
            OrmGetOneAck ack = new OrmGetOneAck(payload.sourceServerId, payload.sourcePartitionId,
                    payload.targetServerId, payload.targetPartitionId, e != null, eb, rb, null);
            return P2PWrapper.build(req.getSeq(), P2PCommand.R_OK_ORM_GET_ONE_ACK, ack);
        } catch (Exception ex) {
            return P2PErrors.stdError(req.getSeq(), P2PErrorCode.INTERNAL_ERROR, ex.toString());
        }
    }
}
