package javax.net.p2p.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
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

public class PubSubUdpTest {

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
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20010);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        int subSeq = 7;
        String topic = "t1";
        p.processMessage(ctx, pkt, StreamP2PWrapper.buildStream(subSeq, 0, P2PCommand.PUBSUB_STREAM, new P2PPubSubMessage(topic, ""), false));
        Assertions.assertEquals(P2PCommand.STREAM_ACK, p.outgoing.poll().getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) == 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(1, PubSubBroker.subscriberCount(topic));

        int pubSeq = 100;
        p.processMessage(ctx, pkt, P2PWrapper.build(pubSeq, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m1")));
        P2PWrapper<?> ok = null;
        List<P2PWrapper<?>> buffered = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            P2PWrapper<?> w = p.outgoing.poll();
            if (w != null) {
                if (w.getCommand() == P2PCommand.STD_OK && w.getSeq() == pubSeq) {
                    ok = w;
                    break;
                }
                buffered.add(w);
            }
            Thread.sleep(5);
        }
        for (P2PWrapper<?> w : buffered) {
            p.outgoing.add(w);
        }
        Assertions.assertNotNull(ok);

        P2PWrapper<?> pushed = null;
        for (int i = 0; i < 200; i++) {
            P2PWrapper<?> w = p.outgoing.poll();
            if (w != null && w.getCommand() == P2PCommand.PUBSUB_STREAM && w.getSeq() == subSeq) {
                pushed = w;
                break;
            }
            Thread.sleep(5);
        }
        Assertions.assertNotNull(pushed);

        p.processMessage(ctx, pkt, P2PWrapper.build(subSeq, P2PCommand.STD_CANCEL, null));
        Assertions.assertEquals(P2PCommand.STD_CANCEL, p.outgoing.poll().getCommand());

        for (int i = 0; i < 1000 && PubSubBroker.subscriberCount(topic) != 0; i++) {
            Thread.sleep(5);
        }
        Assertions.assertEquals(0, PubSubBroker.subscriberCount(topic));

        p.processMessage(ctx, pkt, P2PWrapper.build(pubSeq + 1, P2PCommand.PUBSUB_PUBLISH, new P2PPubSubMessage(topic, "m2")));
        P2PWrapper<?> ok2 = null;
        buffered.clear();
        for (int i = 0; i < 200; i++) {
            P2PWrapper<?> w = p.outgoing.poll();
            if (w != null) {
                if (w.getCommand() == P2PCommand.STD_OK && w.getSeq() == pubSeq + 1) {
                    ok2 = w;
                    break;
                }
                buffered.add(w);
            }
            Thread.sleep(5);
        }
        for (P2PWrapper<?> w : buffered) {
            p.outgoing.add(w);
        }
        Assertions.assertNotNull(ok2);

        P2PWrapper<?> shouldNot = null;
        for (int i = 0; i < 50; i++) {
            P2PWrapper<?> w = p.outgoing.poll();
            if (w != null && w.getCommand() == P2PCommand.PUBSUB_STREAM && w.getSeq() == subSeq) {
                shouldNot = w;
                break;
            }
            Thread.sleep(5);
        }
        Assertions.assertNull(shouldNot);
        ch.finishAndReleaseAll();
    }

    static class TestProcessor extends ServerUdpMessageProcessor {
        final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new ConcurrentLinkedQueue<>();

        TestProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        @Override
        public void sendResponse(io.netty.channel.Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
            outgoing.add(response);
        }

        @Override
        protected AbstractSendMesageExecutor createExecutor(ChannelHandlerContext ctx, InetSocketAddress remote, int magic) {
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
}
