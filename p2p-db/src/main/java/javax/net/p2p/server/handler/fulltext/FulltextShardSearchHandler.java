package javax.net.p2p.server.handler.fulltext;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.ProtoWireConverters;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ScoredDocHitMsg;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nonnull;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class FulltextShardSearchHandler implements P2PCommandHandler<FulltextShardWire.SearchShardRequest> {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FULLTEXT_SHARD_SEARCH;
    }

    @Override
    public P2PWrapper process(@Nonnull P2PWrapper<FulltextShardWire.SearchShardRequest> request) {
        try {
            if (request.getCommand() != P2PCommand.FULLTEXT_SHARD_SEARCH) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof FulltextShardWire.SearchShardRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            FulltextStore.ShardRouter router = DsServerFulltextRegistry.getOrCreate(
                    payload.getClusterRoot().isEmpty() ? "./p26-handler-shard-default" : payload.getClusterRoot(), 1, "svr_shard");
            FulltextStore.SearchOptions opt = ProtoWireConverters.fromProto(payload.getOptions());
            List<FulltextStore.ScoredDocHit> hits = router.search(payload.getShardId(), payload.getQuery(), payload.getK(), opt);
            FulltextShardWire.SearchShardResponse.Builder b = FulltextShardWire.SearchShardResponse.newBuilder()
                    .setStatusCode(0);
            for (FulltextStore.ScoredDocHit h : hits) {
                ScoredDocHitMsg m = ProtoWireConverters.toProto(h);
                b.addHits(m);
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_ACK, b.build());
        } catch (IOException e) {
            FulltextShardWire.SearchShardResponse err = FulltextShardWire.SearchShardResponse.newBuilder()
                    .setStatusCode(2).setErrorMessage(e.toString()).build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_ACK, err);
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
