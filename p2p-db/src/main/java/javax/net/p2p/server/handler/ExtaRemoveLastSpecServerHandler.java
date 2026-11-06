package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.ExtAttrsWriteAck;
import javax.net.p2p.model.ExtaRemoveLastSpecRequest;
import javax.net.p2p.model.P2PWrapper;

public class ExtaRemoveLastSpecServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.EXTA_REMOVE_LAST_SPEC;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.EXTA_REMOVE_LAST_SPEC) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof ExtaRemoveLastSpecRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            boolean ok = DsRegistryExtAttrsHelper.removeLastSpec(r, payload.entryKey);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_EXTA_REMOVE_LAST_SPEC_ACK,
                    new ExtAttrsWriteAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], ok, ok ? 1L : 0L, null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
