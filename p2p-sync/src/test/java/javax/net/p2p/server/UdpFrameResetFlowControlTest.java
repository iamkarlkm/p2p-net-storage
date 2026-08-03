package javax.net.p2p.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import org.junit.Assert;
import org.junit.Test;

public class UdpFrameResetFlowControlTest {

    @Test
    public void shouldThrottleAndScheduleUdpFrameResetResend() throws Exception {
        ThrottlingProcessor p = new ThrottlingProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 21010);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        p.setFrameLastTransportSpeed(1);
        p.sendResponse(ch, sender, P2PWrapper.build(7, P2PCommand.STD_OK, new byte[64]), 0x1234);
        int base = p.sendCount.get();
        long delay = Math.max(5, (long) p.cachedBytes(sender) / 1) + 10;

        p.processMessage(ctx, pkt, P2PWrapper.build(7, P2PCommand.UDP_FRAME_RESET, null));
        p.processMessage(ctx, pkt, P2PWrapper.build(7, P2PCommand.UDP_FRAME_RESET, null));

        Assert.assertEquals(base + 1, p.sendCount.get());
        ch.runPendingTasks();
        ch.runScheduledPendingTasks();
        Assert.assertEquals(base + 1, p.sendCount.get());

        Thread.sleep(delay);
        ch.runScheduledPendingTasks();
        Assert.assertTrue(p.sendCount.get() >= base + 2);
        ch.finishAndReleaseAll();
    }

    @Test
    public void shouldDelegateToAsyncExecutorWhenPresent() throws Exception {
        ExecutorAwareProcessor p = new ExecutorAwareProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);
        InetSocketAddress sender = new InetSocketAddress("127.0.0.1", 21011);
        DatagramPacket pkt = new DatagramPacket(Unpooled.EMPTY_BUFFER, new InetSocketAddress("127.0.0.1", 0), sender);

        AtomicBoolean called = new AtomicBoolean(false);
        FakeServerSendUdpMesageExecutor exec = new FakeServerSendUdpMesageExecutor(called);
        exec.nextFrameSeed = 42;
        p.installExecutor(sender, exec);
        p.setLastSeq(sender, 9);

        p.processMessage(ctx, pkt, P2PWrapper.build(9, P2PCommand.UDP_FRAME_RESET, null));
        Assert.assertTrue(called.get());
        Assert.assertEquals(0, p.sendCount.get());
        ch.finishAndReleaseAll();
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

