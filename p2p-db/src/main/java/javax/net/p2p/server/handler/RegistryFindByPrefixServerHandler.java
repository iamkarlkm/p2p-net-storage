package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;
import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryFindByPrefixAck;
import javax.net.p2p.model.RegistryFindByPrefixRequest;
import javax.net.p2p.model.SimpRegistryEntry;

public class RegistryFindByPrefixServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.REGISTRY_FIND_BY_PREFIX;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.REGISTRY_FIND_BY_PREFIX) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof RegistryFindByPrefixRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            List<RegistryEntry> entries = r.findByPrefix(payload.dirPrefix, payload.maxDepth, payload.limit, null);
            List<SimpRegistryEntry> out = new ArrayList<>(entries.size());
            for (RegistryEntry e : entries) {
                out.add(new SimpRegistryEntry(
                        e.key(), e.isDirectory(), e.dataType().getCode(),
                        e.inlineValueRaw().orElse(0L),
                        e.stringValue().orElse(null),
                        e.bytesValue().orElse(null),
                        e.nodeId()));
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_REGISTRY_FIND_BY_PREFIX_ACK,
                    new RegistryFindByPrefixAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], out, null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
