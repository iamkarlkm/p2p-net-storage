package javax.net.p2p.server.handler;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.RegistryPutStringRequest;
import javax.net.p2p.model.RegistryWriteAck;

public class RegistryPutStringServerHandler implements P2PCommandHandler {

    @Override
    public P2PCommand getCommand() {
        return P2PCommand.REGISTRY_PUT_STRING;
    }

    @Override
    public P2PWrapper process(P2PWrapper request) {
        try {
            if (request.getCommand() != P2PCommand.REGISTRY_PUT_STRING) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof RegistryPutStringRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            int[] tgt = Tgt3RegistryResolver.resolveTarget(payload.targetServerId, payload.targetPartitionId, payload.keyGlobalNodeId);
            DsRegistry r = Tgt3RegistryResolver.resolve(tgt[0], tgt[1]);
            DataType dt = DataType.getByCode(payload.dataTypeCode).orElse(DataType.REG_SZ);
            long nodeId = r.putString(payload.keyPath, payload.value, dt);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_REGISTRY_PUT_STRING_ACK,
                    new RegistryWriteAck(payload.sourceServerId, payload.sourcePartitionId, tgt[0], tgt[1], true, nodeId, null));
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
