package javax.net.p2p.websocket.codec;

import io.netty.util.AttributeKey;

public final class WebSocketAttributes {

    public static final AttributeKey<Boolean> HANDSHAKED = AttributeKey.valueOf("p2p.ws.handshaked");

    private WebSocketAttributes() {
    }
}

