package javax.net.p2p.server.handler.fulltext;

import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import javax.annotation.Nonnull;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class FulltextShardCloseHandler implements P2PCommandHandler<FulltextShardWire.CloseShardRequest> {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FULLTEXT_SHARD_CLOSE;
    }

    @Override
    public P2PWrapper process(@Nonnull P2PWrapper<FulltextShardWire.CloseShardRequest> request) {
        try {
            if (request.getCommand() != P2PCommand.FULLTEXT_SHARD_CLOSE) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof FulltextShardWire.CloseShardRequest)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            DsServerFulltextRegistry.clearAll();
            FulltextShardWire.CloseShardResponse ok = FulltextShardWire.CloseShardResponse.newBuilder()
                    .setStatusCode(0).build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_CLOSE_ACK, ok);
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
