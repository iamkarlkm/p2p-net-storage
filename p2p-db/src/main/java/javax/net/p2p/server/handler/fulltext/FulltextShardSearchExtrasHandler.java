package javax.net.p2p.server.handler.fulltext;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.ProtoWireConverters;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.RawDocPairMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ScoredDocHitWithExtrasMsg;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.interfaces.P2PCommandHandler;
import javax.net.p2p.model.P2PWrapper;

public class FulltextShardSearchExtrasHandler implements P2PCommandHandler<FulltextShardWire.SearchShardWithExtrasRequest> {
    @Override
    public P2PCommand getCommand() {
        return P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS;
    }

    @Override
    public P2PWrapper process(@Nonnull P2PWrapper<FulltextShardWire.SearchShardWithExtrasRequest> request) {
        try {
            if (request.getCommand() != P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.ROUTING_HANDLER_MISMATCH);
            }
            if (!(request.getData() instanceof FulltextShardWire.SearchShardWithExtrasRequest payload)) {
                return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INVALID_REQUEST, "invalid payload");
            }
            FulltextStore.ShardRouter router = DsServerFulltextRegistry.getOrCreate(
                    payload.getClusterRoot().isEmpty() ? "./p26-handler-shard-default" : payload.getClusterRoot(), 1, "svr_shard");
            FulltextStore.SearchOptions opt = ProtoWireConverters.fromProto(payload.getOptions());
            Map<Long, String> rawDocTexts = new HashMap<>();
            for (RawDocPairMsg p : payload.getRawDocTextsList()) rawDocTexts.put(p.getDocId(), p.getText());
            List<FulltextStore.ScoredDocHitWithExtras> hits = router.searchWithExtras(
                    payload.getShardId(), payload.getQuery(), rawDocTexts, payload.getK(), opt);
            FulltextShardWire.SearchShardWithExtrasResponse.Builder b = FulltextShardWire.SearchShardWithExtrasResponse.newBuilder()
                    .setStatusCode(0);
            for (FulltextStore.ScoredDocHitWithExtras h : hits) {
                ScoredDocHitWithExtrasMsg m = ProtoWireConverters.toProto(h);
                b.addHits(m);
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_EXTRAS_ACK, b.build());
        } catch (IOException e) {
            FulltextShardWire.SearchShardWithExtrasResponse err = FulltextShardWire.SearchShardWithExtrasResponse.newBuilder()
                    .setStatusCode(2).setErrorMessage(e.toString()).build();
            return P2PWrapper.build(request.getSeq(), P2PCommand.R_OK_FULLTEXT_SHARD_SEARCH_EXTRAS_ACK, err);
        } catch (Exception e) {
            return P2PErrors.stdError(request.getSeq(), P2PErrorCode.INTERNAL_ERROR, e.toString());
        }
    }
}
