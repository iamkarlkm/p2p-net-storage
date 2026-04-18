package javax.net.p2p.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.P2PPubSubMessage;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.server.pubsub.PubSubBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class PubSubTcpTest {

    @BeforeAll
    public static void initPools() {
        ExecutorServicePool.createServerPools();
    }

    @AfterAll
    public static void shutdownPools() {
        ExecutorServicePool.releaseP2PServerPools();
    }

    @Test
    public void subscribePublishCancel() throws Exception {
        ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new ConcurrentLinkedQueue<>();
        CaptureOutbound capture = new CaptureOutbound(outgoing);
        EmbeddedChannel ch = new EmbeddedChannel(new TestProcessor(16, outgoing), capture);

        int subSeq = 7;
        String topic = "tcp_t1";
        ch.writeInbound(StreamP2PWrapper.buildStream(subSeq, 0, P2PCommand.PUBSUB_STREAM, new P2PPubSubMessage(topic, ""), false));
        Assertions.assertEquals(P2PCommand.STREAM_ACK, waitFor(outgoing, subSeq, P2PCommand.STREAM_ACK).getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) == 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(1, PubSubBroker.subscriberCount(topic));

        int pubSeq = 100;
        ch.writeInbound(P2PWrapper.build(pubSeq, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m1")));
        Assertions.assertEquals(P2PCommand.STD_OK, waitFor(outgoing, pubSeq, P2PCommand.STD_OK).getCommand());
        Assertions.assertNotNull(waitFor(outgoing, subSeq, P2PCommand.PUBSUB_STREAM));

        ch.writeInbound(P2PWrapper.build(subSeq, P2PCommand.STD_CANCEL, null));
        Assertions.assertEquals(P2PCommand.STD_CANCEL, waitFor(outgoing, subSeq, P2PCommand.STD_CANCEL).getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) != 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(0, PubSubBroker.subscriberCount(topic));

        int pubSeq2 = 101;
        ch.writeInbound(P2PWrapper.build(pubSeq2, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m2")));
        Assertions.assertEquals(P2PCommand.STD_OK, waitFor(outgoing, pubSeq2, P2PCommand.STD_OK).getCommand());

        P2PWrapper<?> shouldNot = pollFor(outgoing, subSeq, P2PCommand.PUBSUB_STREAM, 50);
        Assertions.assertNull(shouldNot);
        ch.finishAndReleaseAll();
    }

    private static P2PWrapper<?> waitFor(ConcurrentLinkedQueue<P2PWrapper<?>> q, int seq, P2PCommand cmd) throws Exception {
        for (int i = 0; i < 200; i++) {
            P2PWrapper<?> w = poll(q, seq, cmd);
            if (w != null) return w;
            Thread.sleep(5);
        }
        Assertions.fail("missing " + cmd + " seq=" + seq);
        return null;
    }

    private static P2PWrapper<?> pollFor(ConcurrentLinkedQueue<P2PWrapper<?>> q, int seq, P2PCommand cmd, int loops) throws Exception {
        for (int i = 0; i < loops; i++) {
            P2PWrapper<?> w = poll(q, seq, cmd);
            if (w != null) return w;
            Thread.sleep(5);
        }
        return null;
    }

    private static P2PWrapper<?> poll(ConcurrentLinkedQueue<P2PWrapper<?>> q, int seq, P2PCommand cmd) {
        int n = q.size();
        for (int i = 0; i < n; i++) {
            P2PWrapper<?> w = q.poll();
            if (w == null) {
                return null;
            }
            if (w.getSeq() == seq && w.getCommand() == cmd) {
                return w;
            }
            q.add(w);
        }
        return null;
    }

    static class TestProcessor extends ServerMessageProcessor {
        private final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing;

        TestProcessor(int queueSize, ConcurrentLinkedQueue<P2PWrapper<?>> outgoing) {
            super(0, queueSize);
            this.outgoing = outgoing;
        }

        @Override
        protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx) {
            return new FakeExecutor(outgoing);
        }
    }

    static class FakeExecutor extends AbstractSendMesageExecutor {
        private final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing;

        FakeExecutor(ConcurrentLinkedQueue<P2PWrapper<?>> outgoing) {
            super(16);
            this.outgoing = outgoing;
            this.connected = true;
        }

        @Override
        public void connect(io.netty.channel.EventLoopGroup io_work_group, io.netty.bootstrap.Bootstrap bootstrap) {
        }

        @Override
        public void recycle() {
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void sendResponse(P2PWrapper response) {
            outgoing.add(response);
        }
    }

    static class CaptureOutbound extends ChannelOutboundHandlerAdapter {
        final ConcurrentLinkedQueue<P2PWrapper<?>> q;

        CaptureOutbound(ConcurrentLinkedQueue<P2PWrapper<?>> q) {
            this.q = q;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof P2PWrapper<?> w) {
                q.add(w);
            }
            super.write(ctx, msg, promise);
        }
    }
}
