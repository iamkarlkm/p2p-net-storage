package javax.net.p2p.server;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;

public class ServerUdpMessageProcessorTest {

    @BeforeAll
    public static void initPools() {
        ExecutorServicePool.createServerPools();
    }

    @AfterAll
    public static void shutdownPools() {
        ExecutorServicePool.releaseP2PServerPools();
    }

    @Test
    public void streamFirstRequestReturnsStreamAck() throws Exception {
        registerUdpHandler(P2PCommand.DATA_TRANSFER, new TestStreamHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20001);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        StreamP2PWrapper<String> req = StreamP2PWrapper.buildStream(7, 0, P2PCommand.DATA_TRANSFER, "x", false);
        p.processMessage(ctx, pkt, req);

        P2PWrapper<?> ack = p.outgoing.poll();
        Assertions.assertNotNull(ack);
        Assertions.assertEquals(7, ack.getSeq());
        Assertions.assertEquals(P2PCommand.STREAM_ACK, ack.getCommand());
        ch.finishAndReleaseAll();
    }

    @Test
    public void streamCanBeCanceledBySameSeq() throws Exception {
        registerUdpHandler(P2PCommand.DATA_TRANSFER, new TestStreamHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20003);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        int seq = 11;
        StreamP2PWrapper<String> req = StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DATA_TRANSFER, "x", false);
        p.processMessage(ctx, pkt, req);
        Assertions.assertEquals(P2PCommand.STREAM_ACK, p.outgoing.poll().getCommand());

        p.processMessage(ctx, pkt, P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));

        P2PWrapper<?> cancel = null;
        for (int i = 0; i < 200; i++) {
            cancel = p.outgoing.poll();
            if (cancel != null) break;
            Thread.sleep(5);
        }
        Assertions.assertNotNull(cancel);
        Assertions.assertEquals(seq, cancel.getSeq());
        Assertions.assertEquals(P2PCommand.STD_CANCEL, cancel.getCommand());
        ch.finishAndReleaseAll();
    }

    @Test
    public void longTimedCanBeCanceledBySameSeq() throws Exception {
        registerUdpHandler(P2PCommand.CACHE_LOCK_COMMAND, new TestLongTimedHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20002);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        int seq = 9;
        p.processMessage(ctx, pkt, P2PWrapper.build(seq, P2PCommand.CACHE_LOCK_COMMAND, null));
        Assertions.assertEquals(P2PCommand.STD_ACCEPTED, p.outgoing.poll().getCommand());

        p.processMessage(ctx, pkt, P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));

        P2PWrapper<?> decoded = null;
        for (int i = 0; i < 200; i++) {
            decoded = p.outgoing.poll();
            if (decoded != null) break;
            Thread.sleep(5);
        }
        Assertions.assertNotNull(decoded);
        Assertions.assertEquals(seq, decoded.getSeq());
        Assertions.assertEquals(P2PCommand.STD_CANCEL, decoded.getCommand());
        ch.finishAndReleaseAll();
    }

    @Test
    public void udpFrameResetShouldBeThrottledAndScheduled() throws Exception {
        ThrottlingProcessor p = new ThrottlingProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20010);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        p.setFrameLastTransportSpeed(1);
        p.sendResponse(ch, sender, P2PWrapper.build(7, P2PCommand.STD_OK, new byte[64]), 0x1234);
        int base = p.sendCount.get();
        long delay = Math.max(5, (long) p.cachedBytes(sender) / 1) + 10;

        p.processMessage(ctx, pkt, P2PWrapper.build(7, P2PCommand.UDP_FRAME_RESET, null));
        p.processMessage(ctx, pkt, P2PWrapper.build(7, P2PCommand.UDP_FRAME_RESET, null));

        Assertions.assertEquals(base + 1, p.sendCount.get());
        ch.runPendingTasks();
        ch.runScheduledPendingTasks();
        Assertions.assertEquals(base + 1, p.sendCount.get());

        Thread.sleep(delay);
        ch.runScheduledPendingTasks();
        Assertions.assertTrue(p.sendCount.get() >= base + 2);
        ch.finishAndReleaseAll();
    }

    @Test
    public void udpFrameResetShouldDelegateToAsyncExecutorWhenPresent() throws Exception {
        ExecutorAwareProcessor p = new ExecutorAwareProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 20011);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        AtomicBoolean called = new AtomicBoolean(false);
        FakeServerSendUdpMesageExecutor exec = new FakeServerSendUdpMesageExecutor(called);
        exec.nextFrameSeed = 42;
        p.installExecutor(sender, exec);
        p.setLastSeq(sender, 9);

        p.processMessage(ctx, pkt, P2PWrapper.build(9, P2PCommand.UDP_FRAME_RESET, null));
        Assertions.assertTrue(called.get());
        Assertions.assertEquals(0, p.sendCount.get());
        ch.finishAndReleaseAll();
    }

    @SuppressWarnings("unchecked")
    private static void registerUdpHandler(P2PCommand cmd, Object handler) throws Exception {
        Field f = javax.net.p2p.channel.AbstractUdpMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP");
        f.setAccessible(true);
        ConcurrentHashMap<P2PCommand, Object> map = (ConcurrentHashMap<P2PCommand, Object>) f.get(null);
        map.put(cmd, handler);
    }

    static class TestProcessor extends ServerUdpMessageProcessor {
        final ConcurrentLinkedQueue<P2PWrapper<?>> outgoing = new ConcurrentLinkedQueue<>();

        TestProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        @Override
        public void sendResponse(Channel channel, InetSocketAddress remoteAddess, P2PWrapper response, int magic) {
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
        public void sendResponse(P2PWrapper response) {
            outgoing.add(response);
        }
    }

    static class TestStreamHandler extends AbstractStreamRequestAdapter implements javax.net.p2p.interfaces.StreamRequest {
        @Override
        public P2PCommand getCommand() {
            return P2PCommand.DATA_TRANSFER;
        }

        @Override
        public StreamP2PWrapper request(javax.net.p2p.common.AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
            return null;
        }

        @Override
        public void cancel(javax.net.p2p.common.AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        }

        @Override
        public void processStream(javax.net.p2p.common.AbstractSendMesageExecutor executor, P2PWrapper request) {
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

    static class ThrottlingProcessor extends ServerUdpMessageProcessor {
        final AtomicInteger sendCount = new AtomicInteger();

        ThrottlingProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        void setFrameLastTransportSpeed(long speed) {
            this.frameLastTransportSpeed = speed;
        }

        int cachedBytes(InetSocketAddress remote) {
            io.netty.buffer.ByteBuf buf = lastMessageMap.get(remote);
            return buf == null ? 0 : buf.readableBytes();
        }

        @Override
        public void sendResponse(Channel channel, InetSocketAddress remoteAddess, int seq, io.netty.buffer.ByteBuf buffer) {
            sendCount.incrementAndGet();
            buffer.release();
        }
    }

    static class ExecutorAwareProcessor extends ServerUdpMessageProcessor {
        final AtomicInteger sendCount = new AtomicInteger();

        ExecutorAwareProcessor(int magic, int queueSize) {
            super(null, magic, queueSize);
        }

        void installExecutor(InetSocketAddress remote, ServerSendUdpMesageExecutor exec) {
            asyncSendUdpMesageExecutorMap.put(remote, exec);
        }

        void setLastSeq(InetSocketAddress remote, int seq) {
            lastMessageSeqMap.put(remote, Integer.valueOf(seq));
        }

        @Override
        public void sendResponse(Channel channel, InetSocketAddress remoteAddess, int seq, io.netty.buffer.ByteBuf buffer) {
            sendCount.incrementAndGet();
            buffer.release();
        }
    }

    static class FakeServerSendUdpMesageExecutor extends ServerSendUdpMesageExecutor {
        private final AtomicBoolean called;

        FakeServerSendUdpMesageExecutor(AtomicBoolean called) {
            super(16);
            this.called = called;
        }

        @Override
        public boolean retrieveLastMessage(int seq, int nextSeed) {
            called.set(true);
            return true;
        }
    }
}
