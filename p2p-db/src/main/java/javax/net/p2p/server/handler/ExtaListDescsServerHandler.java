package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.ExtAttrsListStringAck;
import javax.net.p2p.model.ExtaListDescsRequest;
import javax.net.p2p.model.P2PWrapper;

public class ExtaListDescsServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.EXTA_LIST_DESCS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.EXTA_LIST_DESCS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof ExtaListDescsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_EXTA_LIST_DESCS_ACK,
                    new ExtAttrsListStringAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], DsRegistryExtAttrsHelper.listDescs(r, payload.entryKey), null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
