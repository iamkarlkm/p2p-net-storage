package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryFindByPrefixKeysAck;
import javax.net.p2p.model.RegistryFindByPrefixKeysRequest;

public class RegistryFindByPrefixKeysServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.REGISTRY_FIND_BY_PREFIX_KEYS;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.REGISTRY_FIND_BY_PREFIX_KEYS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof RegistryFindByPrefixKeysRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            List<String> keys = r.findByPrefixKeys(payload.dirPrefix, payload.maxDepth, payload.limit, null);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_REGISTRY_FIND_BY_PREFIX_KEYS_ACK,
                    new RegistryFindByPrefixKeysAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], keys, null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
