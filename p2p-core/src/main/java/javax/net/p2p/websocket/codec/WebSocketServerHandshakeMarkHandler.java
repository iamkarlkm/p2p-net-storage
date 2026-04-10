package javax.net.p2p.websocket.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

public final class WebSocketServerHandshakeMarkHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            ctx.channel().attr(WebSocketAttributes.HANDSHAKED).set(Boolean.TRUE);
        }
        super.userEventTriggered(ctx, evt);
    }
}

