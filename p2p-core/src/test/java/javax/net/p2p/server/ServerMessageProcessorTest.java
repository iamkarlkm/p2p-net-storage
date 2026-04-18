package javax.net.p2p.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.P2PWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ServerMessageProcessorTest {

    @BeforeAll
    public static void initPools() {
        ExecutorServicePool.createServerPools();
    }

    @AfterAll
    public static void shutdownPools() {
        ExecutorServicePool.releaseP2PServerPools();
    }

    @Test
    public void longTimedAcceptedAndCancelable() throws Exception {
        registerTcpHandler(P2PCommand.CACHE_LOCK_COMMAND, new TestLongTimedHandler());

        CaptureOutbound capture = new CaptureOutbound();
        EmbeddedChannel ch = new EmbeddedChannel(new ServerMessageProcessor(0, 16), capture);

        int seq = 9;
        ch.writeInbound(P2PWrapper.build(seq, P2PCommand.CACHE_LOCK_COMMAND, null));
        Assertions.assertEquals(P2PCommand.STD_ACCEPTED, capture.command);

        capture.command = null;
        ch.writeInbound(P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));
        for (int i = 0; i < 200 && capture.command == null; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(P2PCommand.STD_CANCEL, capture.command);
        ch.finishAndReleaseAll();
    }

    @SuppressWarnings("unchecked")
    private static void registerTcpHandler(P2PCommand cmd, Object handler) throws Exception {
        Field f = javax.net.p2p.channel.AbstractTcpMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP");
        f.setAccessible(true);
        ConcurrentHashMap<P2PCommand, Object> map = (ConcurrentHashMap<P2PCommand, Object>) f.get(null);
        map.put(cmd, handler);
    }

    static class CaptureOutbound extends ChannelOutboundHandlerAdapter {
        volatile P2PCommand command;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof P2PWrapper<?> w) {
                command = w.getCommand();
            }
            super.write(ctx, msg, promise);
        }
    }

    static class TestLongTimedHandler extends AbstractLongTimedRequestAdapter {
        @Override
        public P2PCommand getCommand() {
            return P2PCommand.CACHE_LOCK_COMMAND;
        }

        @Override
        public P2PWrapper process(P2PWrapper request) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return P2PWrapper.build(request.getSeq(), P2PCommand.STD_OK, "done");
        }
    }
}
