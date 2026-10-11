package javax.net.p2p.server.fulltext;

import com.google.protobuf.GeneratedMessageV3;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import javax.annotation.Nonnull;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;

public class ProtoCodecDuplexHandler extends ChannelDuplexHandler {

    private static P2PWrapper<Object> wrapProto(Object payload, P2PWrapper<?> src) {
        int seq = src.getSeq();
        P2PCommand cmd = src.getCommand();
        if (src instanceof StreamP2PWrapper) {
            int idx = ((StreamP2PWrapper<?>) src).getIndex();
            boolean completed = ((StreamP2PWrapper<?>) src).isCompleted();
            return StreamP2PWrapper.buildStream(seq, idx, cmd, payload, completed);
        }
        return P2PWrapper.build(seq, cmd, payload);
    }

    @Override
    public void channelRead(@Nonnull ChannelHandlerContext ctx, @Nonnull Object msg) throws Exception {
        if (msg instanceof P2PWrapper<?>) {
            P2PWrapper<?> wrapper = (P2PWrapper<?>) msg;
            Object data = wrapper.getData();
            if (data instanceof byte[]) {
                byte[] bytes = (byte[]) data;
                if (bytes.length > 0) {
                    P2PCommand cmd = wrapper.getCommand();
                    Object parsed = null;
                    try {
                        if (cmd == P2PCommand.FULLTEXT_SHARD_PUT_DOC) {
                            parsed = FulltextShardWire.PutDocShardRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_CLEAR_DOC) {
                            parsed = FulltextShardWire.ClearDocShardRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR) {
                            parsed = FulltextShardWire.IndexTermVectorShardRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR) {
                            parsed = FulltextShardWire.SearchWithVectorShardRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_SEARCH) {
                            parsed = FulltextShardWire.SearchShardRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS) {
                            parsed = FulltextShardWire.SearchShardWithExtrasRequest.parseFrom(bytes);
                        } else if (cmd == P2PCommand.FULLTEXT_SHARD_CLOSE) {
                            parsed = FulltextShardWire.CloseShardRequest.parseFrom(bytes);
                        }
                    } catch (Exception ignore) {
                        parsed = null;
                    }
                    if (parsed != null) {
                        ctx.fireChannelRead(wrapProto(parsed, wrapper));
                        return;
                    }
                }
            }
        }
        ctx.fireChannelRead(msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof P2PWrapper<?>) {
            P2PWrapper<?> wrapper = (P2PWrapper<?>) msg;
            Object data = wrapper.getData();
            if (data instanceof GeneratedMessageV3) {
                byte[] bytes = ((GeneratedMessageV3) data).toByteArray();
                ctx.write(wrapProto(bytes, wrapper), promise);
                return;
            }
        }
        ctx.write(msg, promise);
    }
}
