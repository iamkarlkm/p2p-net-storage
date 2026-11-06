package javax.net.p2p.server.handler;

import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;
import java.util.ArrayList;
import java.util.function.Predicate;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.ExtAttrsListStringAck;
import javax.net.p2p.model.ExtaListTagsFilteredRequest;
import javax.net.p2p.model.P2PWrapper;

public class ExtaListTagsFilteredServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.EXTA_LIST_TAGS_FILTERED;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.EXTA_LIST_TAGS_FILTERED) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof ExtaListTagsFilteredRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            int limit = payload.limit <= 0 ? Integer.MAX_VALUE : payload.limit;
            Predicate<String> filter = null;
            if (payload.tagPrefix != null && !payload.tagPrefix.isEmpty()) {
                filter = s -> s.startsWith(payload.tagPrefix);
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_EXTA_LIST_TAGS_FILTERED_ACK,
                    new ExtAttrsListStringAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], new ArrayList<>(DsRegistryExtAttrsHelper.listTags(r, payload.entryKey, limit, filter)), null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
