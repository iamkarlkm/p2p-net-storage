package javax.net.p2p.websocket.codec;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;

public final class ByteBufToWebSocketFrameEncoder extends MessageToMessageEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        Boolean ok = ctx.channel().attr(WebSocketAttributes.HANDSHAKED).get();
        if (ok != null && ok.booleanValue()) {
            out.add(new BinaryWebSocketFrame(msg.retain()));
        } else {
            out.add(msg.retain());
        }
    }
}
