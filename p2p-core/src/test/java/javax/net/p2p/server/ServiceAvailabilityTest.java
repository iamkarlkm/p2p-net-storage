package javax.net.p2p.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ServiceAvailabilityTest {

    @AfterEach
    public void cleanup() {
        P2PServiceManager.enable(javax.net.p2p.api.P2PServiceCategory.FILE);
    }

    @Test
    public void disabledServiceReturnsUnavailable() {
        P2PServiceManager.disable(javax.net.p2p.api.P2PServiceCategory.FILE);
        CaptureOutbound capture = new CaptureOutbound();
        EmbeddedChannel ch = new EmbeddedChannel(new ServerMessageProcessor(0, 16), capture);
        P2PWrapper req = P2PWrapper.build(1, P2PCommand.GET_FILE, null);
        ch.writeInbound(req);
        Assertions.assertEquals(P2PCommand.STD_ERROR, capture.command);
        Assertions.assertTrue(capture.data.contains("service unavailable"), capture.data);
        ch.finishAndReleaseAll();
    }

    static class CaptureOutbound extends ChannelOutboundHandlerAdapter {
        P2PCommand command;
        String data = "";

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof P2PWrapper<?> w) {
                command = w.getCommand();
                data = String.valueOf(w.getData());
            }
            super.write(ctx, msg, promise);
        }
    }
}
