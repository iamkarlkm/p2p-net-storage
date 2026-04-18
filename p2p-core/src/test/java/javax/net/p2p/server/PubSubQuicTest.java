package javax.net.p2p.server;

import io.netty.channel.ChannelHandlerContext;
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

public class PubSubQuicTest {

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
        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int subSeq = 7;
        String topic = "quic_t1";
        p.processMessage(ctx, StreamP2PWrapper.buildStream(subSeq, 0, P2PCommand.PUBSUB_STREAM, new P2PPubSubMessage(topic, ""), false));
        Assertions.assertEquals(P2PCommand.STREAM_ACK, waitFor(p, subSeq, P2PCommand.STREAM_ACK).getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) == 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(1, PubSubBroker.subscriberCount(topic));

        int pubSeq = 100;
        p.processMessage(ctx, P2PWrapper.build(pubSeq, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m1")));
        Assertions.assertEquals(P2PCommand.STD_OK, waitFor(p, pubSeq, P2PCommand.STD_OK).getCommand());
        Assertions.assertNotNull(waitFor(p, subSeq, P2PCommand.PUBSUB_STREAM));

        p.processMessage(ctx, P2PWrapper.build(subSeq, P2PCommand.STD_CANCEL, null));
        Assertions.assertEquals(P2PCommand.STD_CANCEL, waitFor(p, subSeq, P2PCommand.STD_CANCEL).getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) != 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(0, PubSubBroker.subscriberCount(topic));

        int pubSeq2 = 101;
        p.processMessage(ctx, P2PWrapper.build(pubSeq2, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m2")));
        Assertions.assertEquals(P2PCommand.STD_OK, waitFor(p, pubSeq2, P2PCommand.STD_OK).getCommand());

        P2PWrapper<?> shouldNot = pollFor(p, subSeq, P2PCommand.PUBSUB_STREAM, 50);
        Assertions.assertNull(shouldNot);
        ch.finishAndReleaseAll();
    }

    private static P2PWrapper<?> waitFor(TestProcessor p, int seq, P2PCommand cmd) throws Exception {
        for (int i = 0; i < 200; i++) {
            P2PWrapper<?> w = p.poll(seq, cmd);
            if (w != null) return w;
            Thread.sleep(5);
        }
        Assertions.fail("missing " + cmd + " seq=" + seq);
        return null;
    }

    private static P2PWrapper<?> pollFor(TestProcessor p, int seq, P2PCommand cmd, int loops) throws Exception {
        for (int i = 0; i < loops; i++) {
            P2PWrapper<?> w = p.poll(seq, cmd);
            if (w != null) return w;
            Thread.sleep(5);
        }
        return null;
    }

    static class TestProcessor extends ServerQuicMessageProcessor {
        final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new ConcurrentLinkedQueue<>();

        TestProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        @Override
        protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx) {
            return new FakeExecutor(outgoing);
        }

        @Override
        public void sendResponse(ChannelHandlerContext ctx, P2PWrapper response) {
            outgoing.add(response);
        }

        P2PWrapper<?> poll(int seq, P2PCommand cmd) {
            int n = outgoing.size();
            for (int i = 0; i < n; i++) {
                P2PWrapper<?> w = outgoing.poll();
                if (w == null) {
                    return null;
                }
                if (w.getSeq() == seq && w.getCommand() == cmd) {
                    return w;
                }
                outgoing.add(w);
            }
            return null;
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
}
