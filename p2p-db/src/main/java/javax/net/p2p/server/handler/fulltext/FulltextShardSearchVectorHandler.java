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

public class FulltextShardSearchVectorHandler implements P2PCommandHandler<FulltextShardWire.SearchWithVectorShardRequest> {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR;
    }

    @Override
    public P2PWrapper process(@Nonnull P2PWrapper<FulltextShardWire.SearchWithVectorShardRequest> request) {
        try {
            if (request.getCommand() != P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof FulltextShardWire.SearchWithVectorShardRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            FulltextStore.ShardRouter router = DsServerFulltextRegistry.getOrCreate(
                    payload.getClusterRoot().isEmpty() ? "./p26-handler-shard-default" : payload.getClusterRoot(), 1, "svr_shard");
            List<Float> v = payload.getVectorList();
            float[] arr = new float[v.size()];
            for (int i = 0; i < v.size(); i++) arr[i] = v.get(i);
            String q = payload.getQuery();
            FulltextStore.SearchOptions opt = ProtoWireConverters.fromProto(payload.getOptions());
            List<FulltextStore.ScoredDocHit> hits;
            if (q != null && !q.isEmpty()) {
                hits = router.search(payload.getShardId(), q, payload.getK(), opt);
            } else {
                hits = router.searchWithVector(payload.getShardId(), arr, payload.getK());
            }
            FulltextShardWire.SearchWithVectorShardResponse.Builder b = FulltextShardWire.SearchWithVectorShardResponse.newBuilder()
                    .setStatusCode(0);
            for (FulltextStore.ScoredDocHit h : hits) {
                ScoredDocHitMsg m = ProtoWireConverters.toProto(h);
                b.addHits(m);
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_VECTOR_ACK, b.build());
        } catch (IOException e) {
            FulltextShardWire.SearchWithVectorShardResponse err = FulltextShardWire.SearchWithVectorShardResponse.newBuilder()
                    .setStatusCode(2).setErrorMessage(e.toString()).build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_VECTOR_ACK, err);
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
