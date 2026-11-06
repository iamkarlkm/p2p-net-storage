package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryMkdirsRequest;
import javax.net.p2p.model.RegistryWriteAck;

public class RegistryMkdirsServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.REGISTRY_MKDIRS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.REGISTRY_MKDIRS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof RegistryMkdirsRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            long nodeId = r.mkdirs(payload.dirPath);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_REGISTRY_MKDIRS_ACK,
                    new RegistryWriteAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], true, nodeId, null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
