package javax.net.p2p.interfaces;

import io.netty.channel.ChannelHandlerContext;
import javax.net.p2p.model.P2PWrapper;

public interface P2PChannelAwareCommandHandler extends P2PCommandHandler {
    P2PWrapper process(ChannelHandlerContext ctx, P2PWrapper request);
}

