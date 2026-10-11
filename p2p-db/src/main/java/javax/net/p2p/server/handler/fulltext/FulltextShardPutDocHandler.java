package javax.net.p2p.server.handler.fulltext;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class FulltextShardPutDocHandler implements P2PCommandHandler<FulltextShardWire.PutDocShardRequest> {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FULLTEXT_SHARD_PUT_DOC;
    }

    @Override
    public P2PWrapper process(@Nonnull P2PWrapper<FulltextShardWire.PutDocShardRequest> request) {
        try {
            if (request.getCommand() != P2PCommand.FULLTEXT_SHARD_PUT_DOC) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof FulltextShardWire.PutDocShardRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            FulltextStore.ShardRouter router = DsServerFulltextRegistry.getOrCreate(
                    payload.getClusterRoot().isEmpty() ? "./p26-handler-shard-default" : payload.getClusterRoot(), 1, "svr_shard");
            FulltextStore.IngestStat stat = router.putDoc(payload.getShardId(), payload.getDocId(), payload.getText());
            FulltextShardWire.PutDocShardResponse.Builder b = FulltextShardWire.PutDocShardResponse.newBuilder()
                    .setStatusCode(0).setDocId(payload.getDocId()).setPairsTotal(stat.pairsTotal);
            for (int i = 0; i < stat.rowsPerLevel.length; i++) b.addRowsPerLevel(stat.rowsPerLevel[i]);
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_PUT_DOC_ACK, b.build());
        } catch (IOException e) {
            FulltextShardWire.PutDocShardResponse err = FulltextShardWire.PutDocShardResponse.newBuilder()
                    .setStatusCode(2).setErrorMessage(e.toString()).build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_PUT_DOC_ACK, err);
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
