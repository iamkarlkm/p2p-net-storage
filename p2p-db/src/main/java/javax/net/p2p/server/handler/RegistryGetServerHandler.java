package javax.net.p2p.server.handler;

import com.q3lives.ds.enums.Category;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryGetAck;
import javax.net.p2p.model.RegistryGetRequest;

public class RegistryGetServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.REGISTRY_GET;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.REGISTRY_GET) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof RegistryGetRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            RegistryEntry e = r.get(payload.keyPath);
            boolean present = e.isPresent();
            boolean isDir = present && e.isDirectory();
            int dtCode = present ? e.dataType().getCode() : 0;
            long inline = e.inlineValueRaw().orElse(0L);
            String sv = e.stringValue().orElse(null);
            byte[] bv = e.bytesValue().orElse(null);
            long nid = present ? e.nodeId() : 0L;
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_REGISTRY_GET_ACK,
                    new RegistryGetAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1],
                            present, isDir, dtCode, inline, sv, bv, nid, null));
        } catch (Exception ex) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, ex.toString());
        }
    }
}
