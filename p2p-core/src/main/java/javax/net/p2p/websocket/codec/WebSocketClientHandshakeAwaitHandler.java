package javax.net.p2p.websocket.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.util.concurrent.Promise;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.websocket.reliability.WebSocketReliabilityHandler;

public final class WebSocketClientHandshakeAwaitHandler extends ChannelInboundHandlerAdapter {

    private final Promise<Void> promise;

    public WebSocketClientHandshakeAwaitHandler(Promise<Void> promise) {
        this.promise = promise;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            ctx.channel().attr(WebSocketAttributes.HANDSHAKED).set(Boolean.TRUE);
            String sid = ctx.channel().attr(WebSocketReliabilityHandler.SESSION_ID).get();
            if (sid != null && !sid.isBlank()) {
                int lastDelivered = WebSocketReliabilityHandler.getLastDelivered(sid);
                ctx.writeAndFlush(P2PWrapper.build(0, P2PCommand.WS_SESSION_HELLO, sid + "|" + lastDelivered));
            }
            promise.trySuccess(null);
        } else if (evt == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT) {
            promise.tryFailure(new IllegalStateException("websocket handshake timeout"));
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        promise.tryFailure(cause);
        ctx.close();
    }
}
