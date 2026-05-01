package javax.net.p2p.server;

import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.common.AbstractSendMesageExecutor;
import javax.net.p2p.common.ExecutorServicePool;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.stream.proto.StreamChatRequest;
import javax.net.p2p.rpc.stream.proto.StreamChatResponse;
import javax.net.p2p.rpc.stream.proto.StreamCollectRequest;
import javax.net.p2p.rpc.stream.proto.StreamCollectResponse;
import javax.net.p2p.rpc.server.RpcStreamingBuiltinServices;
import javax.net.p2p.server.handler.RpcStreamCommandServerHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ServerQuicMessageProcessorTest {
    // 处理器级全量回归共享线程池，极端情况下异步响应会明显慢于单测单跑。
    private static final int WAIT_RETRY_COUNT = 4000;
    private static final int WAIT_SLEEP_MILLIS = 5;

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
        registerQuicHandler(P2PCommand.DATA_TRANSFER, new TestStreamHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        StreamP2PWrapper<String> req = StreamP2PWrapper.buildStream(7, 0, P2PCommand.DATA_TRANSFER, "x", false);
        p.processMessage(ctx, req);

        P2PWrapper<?> ack = p.outgoing.poll();
        Assertions.assertNotNull(ack);
        Assertions.assertEquals(7, ack.getSeq());
        Assertions.assertEquals(P2PCommand.STREAM_ACK, ack.getCommand());
        ch.finishAndReleaseAll();
    }

    @Test
    public void streamCanBeCanceledBySameSeq() throws Exception {
        registerQuicHandler(P2PCommand.DATA_TRANSFER, new TestStreamHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 11;
        StreamP2PWrapper<String> req = StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DATA_TRANSFER, "x", false);
        p.processMessage(ctx, req);
        Assertions.assertEquals(P2PCommand.STREAM_ACK, p.outgoing.poll().getCommand());

        p.processMessage(ctx, P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));

        P2PWrapper<?> cancel = p.outgoing.poll();
        Assertions.assertNotNull(cancel);
        Assertions.assertEquals(seq, cancel.getSeq());
        Assertions.assertEquals(P2PCommand.STD_CANCEL, cancel.getCommand());
        ch.finishAndReleaseAll();
    }

    @Test
    public void streamMessagesQueuedBeforeAwaitAreStillProcessed() throws Exception {
        registerQuicHandler(P2PCommand.DATA_TRANSFER, new RespondingStreamHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 31;
        p.processMessage(ctx, StreamP2PWrapper.buildStream(seq, 0, P2PCommand.DATA_TRANSFER, "m1", false));
        Assertions.assertNotNull(waitFor(p, seq, P2PCommand.STREAM_ACK));
        // 连续快速投递第二帧，覆盖“处理线程尚未 await，后续消息已到达”的真实竞态。
        p.processMessage(ctx, StreamP2PWrapper.buildStream(seq, 1, P2PCommand.DATA_TRANSFER, "m2", true));

        P2PWrapper<?> responseWrapper = waitFor(p, seq, P2PCommand.DATA_TRANSFER);
        Assertions.assertEquals("m1,m2", responseWrapper.getData());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamCanFlowThroughQuicProcessor() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 51;
        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            0,
            buildRpcFrame(51L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, seq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            1,
            buildRpcFrame(51L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("a").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            2,
            buildRpcFrame(51L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("b").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            3,
            buildRpcFrame(51L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));

        RpcFrame responseFrame = waitForRpcFrame(p, seq, RpcFrameType.CLOSE);
        StreamCollectResponse response = StreamCollectResponse.parseFrom(responseFrame.getPayload());
        Assertions.assertEquals(2, response.getCount());
        Assertions.assertEquals("a,b", response.getJoined());
        Assertions.assertEquals(List.of("a", "b"), response.getMessagesList());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamEmitsWindowUpdateThroughQuicProcessor() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 52;
        RpcFrame openFrame = buildRpcFrame(
            52L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_COLLECT,
            RpcCallType.CLIENT_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        p.processMessage(ctx, buildRpcStreamMessage(seq, 0, openFrame, false));
        Assertions.assertNotNull(waitFor(p, seq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            1,
            buildRpcFrame(52L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("u1").build(), false),
            false
        ));

        RpcFrame windowUpdateFrame = waitForRpcFrame(p, seq, RpcFrameType.WINDOW_UPDATE);
        Assertions.assertEquals(1, windowUpdateFrame.getFlowControl().getPermits());

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            2,
            buildRpcFrame(52L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, seq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamsStayIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int leftSeq = 71;
        int rightSeq = 72;
        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            0,
            buildRpcFrame(71L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            0,
            buildRpcFrame(72L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, leftSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, rightSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            1,
            buildRpcFrame(71L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("left-1").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            1,
            buildRpcFrame(72L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("right-1").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            2,
            buildRpcFrame(71L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("left-2").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            2,
            buildRpcFrame(72L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            3,
            buildRpcFrame(71L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));

        StreamCollectResponse rightResponse = StreamCollectResponse.parseFrom(waitForRpcFrame(p, rightSeq, RpcFrameType.CLOSE).getPayload());
        StreamCollectResponse leftResponse = StreamCollectResponse.parseFrom(waitForRpcFrame(p, leftSeq, RpcFrameType.CLOSE).getPayload());
        Assertions.assertEquals(List.of("right-1"), rightResponse.getMessagesList());
        Assertions.assertEquals("right-1", rightResponse.getJoined());
        Assertions.assertEquals(List.of("left-1", "left-2"), leftResponse.getMessagesList());
        Assertions.assertEquals("left-1,left-2", leftResponse.getJoined());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamWindowUpdatesStayIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int leftSeq = 73;
        int rightSeq = 74;
        RpcFrame leftOpenFrame = buildRpcFrame(
            73L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_COLLECT,
            RpcCallType.CLIENT_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        RpcFrame rightOpenFrame = buildRpcFrame(
            74L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_COLLECT,
            RpcCallType.CLIENT_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        p.processMessage(ctx, buildRpcStreamMessage(leftSeq, 0, leftOpenFrame, false));
        p.processMessage(ctx, buildRpcStreamMessage(rightSeq, 0, rightOpenFrame, false));
        Assertions.assertNotNull(waitFor(p, leftSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, rightSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            1,
            buildRpcFrame(73L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("left-window").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            1,
            buildRpcFrame(74L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("right-window").build(), false),
            false
        ));

        Assertions.assertEquals(1, waitForRpcFrame(p, leftSeq, RpcFrameType.WINDOW_UPDATE).getFlowControl().getPermits());
        Assertions.assertEquals(1, waitForRpcFrame(p, rightSeq, RpcFrameType.WINDOW_UPDATE).getFlowControl().getPermits());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamCancelStaysIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int canceledSeq = 75;
        int continuedSeq = 76;
        p.processMessage(ctx, buildRpcStreamMessage(
            canceledSeq,
            0,
            buildRpcFrame(75L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            0,
            buildRpcFrame(76L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, canceledSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, continuedSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            canceledSeq,
            1,
            buildRpcFrame(75L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("drop-me").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            1,
            buildRpcFrame(76L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("keep-1").build(), false),
            false
        ));

        p.processMessage(ctx, P2PWrapper.build(canceledSeq, P2PCommand.STD_CANCEL, null));
        Assertions.assertEquals(P2PCommand.STD_CANCEL, waitFor(p, canceledSeq, P2PCommand.STD_CANCEL).getCommand());

        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            2,
            buildRpcFrame(76L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("keep-2").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            3,
            buildRpcFrame(76L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));

        StreamCollectResponse continuedResponse = StreamCollectResponse.parseFrom(waitForRpcFrame(p, continuedSeq, RpcFrameType.CLOSE).getPayload());
        Assertions.assertEquals(List.of("keep-1", "keep-2"), continuedResponse.getMessagesList());
        Assertions.assertEquals("keep-1,keep-2", continuedResponse.getJoined());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcClientStreamLowTrafficSeqIsNotStarvedByBusySeq() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int busySeq = 77;
        int quietSeq = 78;
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            0,
            buildRpcFrame(77L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            0,
            buildRpcFrame(78L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, busySeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, quietSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            1,
            buildRpcFrame(77L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("busy-1").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            2,
            buildRpcFrame(77L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("busy-2").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            3,
            buildRpcFrame(77L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("busy-3").build(), false),
            false
        ));

        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            1,
            buildRpcFrame(78L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.DATA,
                StreamCollectRequest.newBuilder().setMessage("quiet-1").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            2,
            buildRpcFrame(78L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));

        StreamCollectResponse quietResponse = StreamCollectResponse.parseFrom(waitForRpcFrame(p, quietSeq, RpcFrameType.CLOSE).getPayload());
        Assertions.assertEquals(List.of("quiet-1"), quietResponse.getMessagesList());
        Assertions.assertEquals("quiet-1", quietResponse.getJoined());

        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            4,
            buildRpcFrame(77L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_COLLECT, RpcCallType.CLIENT_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        StreamCollectResponse busyResponse = StreamCollectResponse.parseFrom(waitForRpcFrame(p, busySeq, RpcFrameType.CLOSE).getPayload());
        Assertions.assertEquals(List.of("busy-1", "busy-2", "busy-3"), busyResponse.getMessagesList());
        Assertions.assertEquals("busy-1,busy-2,busy-3", busyResponse.getJoined());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiStreamCanFlowThroughQuicProcessor() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 61;
        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            0,
            buildRpcFrame(61L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, seq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            1,
            buildRpcFrame(61L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("left").build(), false),
            false
        ));
        RpcFrame firstResponseFrame = waitForRpcFrame(p, seq, RpcFrameType.DATA);
        StreamChatResponse firstResponse = StreamChatResponse.parseFrom(firstResponseFrame.getPayload());
        Assertions.assertEquals(1, firstResponse.getIndex());
        Assertions.assertEquals("ack:left", firstResponse.getMessage());

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            2,
            buildRpcFrame(61L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("right").build(), false),
            false
        ));
        RpcFrame secondResponseFrame = waitForRpcFrame(p, seq, RpcFrameType.DATA);
        StreamChatResponse secondResponse = StreamChatResponse.parseFrom(secondResponseFrame.getPayload());
        Assertions.assertEquals(2, secondResponse.getIndex());
        Assertions.assertEquals("ack:right", secondResponse.getMessage());

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            3,
            buildRpcFrame(61L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, seq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiStreamEmitsWindowUpdateThroughQuicProcessor() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 62;
        RpcFrame openFrame = buildRpcFrame(
            62L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_CHAT,
            RpcCallType.BIDI_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        p.processMessage(ctx, buildRpcStreamMessage(seq, 0, openFrame, false));
        Assertions.assertNotNull(waitFor(p, seq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            1,
            buildRpcFrame(62L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("u1").build(), false),
            false
        ));

        RpcFrame responseFrame = waitForRpcFrame(p, seq, RpcFrameType.DATA);
        Assertions.assertEquals("ack:u1", StreamChatResponse.parseFrom(responseFrame.getPayload()).getMessage());
        RpcFrame windowUpdateFrame = waitForRpcFrame(p, seq, RpcFrameType.WINDOW_UPDATE);
        Assertions.assertEquals(1, windowUpdateFrame.getFlowControl().getPermits());

        p.processMessage(ctx, buildRpcStreamMessage(
            seq,
            2,
            buildRpcFrame(62L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, seq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiStreamsStayIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int leftSeq = 81;
        int rightSeq = 82;
        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            0,
            buildRpcFrame(81L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            0,
            buildRpcFrame(82L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, leftSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, rightSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            1,
            buildRpcFrame(81L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("left").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            1,
            buildRpcFrame(82L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("right").build(), false),
            false
        ));

        StreamChatResponse leftResponse = StreamChatResponse.parseFrom(waitForRpcFrame(p, leftSeq, RpcFrameType.DATA).getPayload());
        StreamChatResponse rightResponse = StreamChatResponse.parseFrom(waitForRpcFrame(p, rightSeq, RpcFrameType.DATA).getPayload());
        Assertions.assertEquals("ack:left", leftResponse.getMessage());
        Assertions.assertEquals("ack:right", rightResponse.getMessage());

        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            2,
            buildRpcFrame(81L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            2,
            buildRpcFrame(82L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, leftSeq, RpcFrameType.CLOSE).getFrameType());
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, rightSeq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiStreamWindowUpdatesStayIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int leftSeq = 83;
        int rightSeq = 84;
        RpcFrame leftOpenFrame = buildRpcFrame(
            83L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_CHAT,
            RpcCallType.BIDI_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        RpcFrame rightOpenFrame = buildRpcFrame(
            84L,
            RpcStreamingBuiltinServices.SERVICE,
            RpcStreamingBuiltinServices.METHOD_CHAT,
            RpcCallType.BIDI_STREAM,
            RpcFrameType.OPEN,
            null,
            false
        ).toBuilder()
            .setFlowControl(javax.net.p2p.rpc.proto.RpcFlowControl.newBuilder().setPermits(1).build())
            .build();
        p.processMessage(ctx, buildRpcStreamMessage(leftSeq, 0, leftOpenFrame, false));
        p.processMessage(ctx, buildRpcStreamMessage(rightSeq, 0, rightOpenFrame, false));
        Assertions.assertNotNull(waitFor(p, leftSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, rightSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            leftSeq,
            1,
            buildRpcFrame(83L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("left-window").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            rightSeq,
            1,
            buildRpcFrame(84L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("right-window").build(), false),
            false
        ));

        Assertions.assertEquals("ack:left-window", StreamChatResponse.parseFrom(waitForRpcFrame(p, leftSeq, RpcFrameType.DATA).getPayload()).getMessage());
        Assertions.assertEquals("ack:right-window", StreamChatResponse.parseFrom(waitForRpcFrame(p, rightSeq, RpcFrameType.DATA).getPayload()).getMessage());
        Assertions.assertEquals(1, waitForRpcFrame(p, leftSeq, RpcFrameType.WINDOW_UPDATE).getFlowControl().getPermits());
        Assertions.assertEquals(1, waitForRpcFrame(p, rightSeq, RpcFrameType.WINDOW_UPDATE).getFlowControl().getPermits());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiStreamCancelStaysIsolatedAcrossConcurrentSeqs() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int canceledSeq = 85;
        int continuedSeq = 86;
        p.processMessage(ctx, buildRpcStreamMessage(
            canceledSeq,
            0,
            buildRpcFrame(85L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            0,
            buildRpcFrame(86L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, canceledSeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, continuedSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            canceledSeq,
            1,
            buildRpcFrame(85L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("drop-me").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            1,
            buildRpcFrame(86L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("keep-me").build(), false),
            false
        ));
        Assertions.assertEquals("ack:drop-me", StreamChatResponse.parseFrom(waitForRpcFrame(p, canceledSeq, RpcFrameType.DATA).getPayload()).getMessage());
        Assertions.assertEquals("ack:keep-me", StreamChatResponse.parseFrom(waitForRpcFrame(p, continuedSeq, RpcFrameType.DATA).getPayload()).getMessage());

        p.processMessage(ctx, P2PWrapper.build(canceledSeq, P2PCommand.STD_CANCEL, null));
        Assertions.assertEquals(P2PCommand.STD_CANCEL, waitFor(p, canceledSeq, P2PCommand.STD_CANCEL).getCommand());

        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            2,
            buildRpcFrame(86L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("keep-going").build(), false),
            false
        ));
        Assertions.assertEquals("ack:keep-going", StreamChatResponse.parseFrom(waitForRpcFrame(p, continuedSeq, RpcFrameType.DATA).getPayload()).getMessage());

        p.processMessage(ctx, buildRpcStreamMessage(
            continuedSeq,
            3,
            buildRpcFrame(86L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, continuedSeq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void rpcBidiLowTrafficSeqIsNotStarvedByBusySeq() throws Exception {
        registerQuicHandler(P2PCommand.RPC_STREAM, new RpcStreamCommandServerHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int busySeq = 91;
        int quietSeq = 92;
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            0,
            buildRpcFrame(91L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            0,
            buildRpcFrame(92L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.OPEN, null, false),
            false
        ));
        Assertions.assertNotNull(waitFor(p, busySeq, P2PCommand.STREAM_ACK));
        Assertions.assertNotNull(waitFor(p, quietSeq, P2PCommand.STREAM_ACK));

        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            1,
            buildRpcFrame(91L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("busy-1").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            2,
            buildRpcFrame(91L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("busy-2").build(), false),
            false
        ));
        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            3,
            buildRpcFrame(91L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("busy-3").build(), false),
            false
        ));

        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            1,
            buildRpcFrame(92L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.DATA,
                StreamChatRequest.newBuilder().setMessage("quiet-1").build(), false),
            false
        ));

        StreamChatResponse quietResponse = StreamChatResponse.parseFrom(waitForRpcFrame(p, quietSeq, RpcFrameType.DATA).getPayload());
        Assertions.assertEquals("ack:quiet-1", quietResponse.getMessage());

        p.processMessage(ctx, buildRpcStreamMessage(
            quietSeq,
            2,
            buildRpcFrame(92L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, quietSeq, RpcFrameType.CLOSE).getFrameType());

        p.processMessage(ctx, buildRpcStreamMessage(
            busySeq,
            4,
            buildRpcFrame(91L, RpcStreamingBuiltinServices.SERVICE, RpcStreamingBuiltinServices.METHOD_CHAT, RpcCallType.BIDI_STREAM, RpcFrameType.CLOSE, null, true),
            true
        ));
        Assertions.assertEquals(RpcFrameType.CLOSE, waitForRpcFrame(p, busySeq, RpcFrameType.CLOSE).getFrameType());
        ch.finishAndReleaseAll();
    }

    @Test
    public void longTimedCanBeCanceledBySameSeq() throws Exception {
        registerQuicHandler(P2PCommand.CACHE_LOCK_COMMAND, new TestLongTimedHandler());

        TestProcessor p = new TestProcessor(0x1234, 16);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast(p);
        ChannelHandlerContext ctx = ch.pipeline().context(p);

        int seq = 9;
        p.processMessage(ctx, P2PWrapper.build(seq, P2PCommand.CACHE_LOCK_COMMAND, null));
        Assertions.assertEquals(P2PCommand.STD_ACCEPTED, p.outgoing.poll().getCommand());

        p.processMessage(ctx, P2PWrapper.build(seq, P2PCommand.STD_CANCEL, null));

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

    @SuppressWarnings("unchecked")
    private static void registerQuicHandler(P2PCommand cmd, Object handler) throws Exception {
        Field f = javax.net.p2p.channel.AbstractQuicMessageProcessor.class.getDeclaredField("HANDLER_REGISTRY_MAP");
        f.setAccessible(true);
        ConcurrentHashMap<P2PCommand, Object> map = (ConcurrentHashMap<P2PCommand, Object>) f.get(null);
        map.put(cmd, handler);
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
            int size = outgoing.size();
            for (int i = 0; i < size; i++) {
                P2PWrapper<?> wrapper = outgoing.poll();
                if (wrapper == null) {
                    return null;
                }
                if (wrapper.getSeq() == seq && wrapper.getCommand() == cmd) {
                    return wrapper;
                }
                outgoing.add(wrapper);
            }
            return null;
        }

        RpcFrame pollRpcFrame(int seq, RpcFrameType frameType) {
            int size = outgoing.size();
            for (int i = 0; i < size; i++) {
                P2PWrapper<?> wrapper = outgoing.poll();
                if (wrapper == null) {
                    return null;
                }
                if (wrapper.getSeq() != seq || wrapper.getCommand() != P2PCommand.RPC_STREAM) {
                    outgoing.add(wrapper);
                    continue;
                }
                try {
                    RpcFrame frame = RpcFrame.parseFrom((byte[]) wrapper.getData());
                    if (frame.getFrameType() == frameType) {
                        return frame;
                    }
                } catch (Exception ignored) {
                }
                // 保留未命中的帧，避免等待特定 DATA/CLOSE 时把其他响应吃掉。
                outgoing.add(wrapper);
            }
            return null;
        }
    }

    private static P2PWrapper<?> waitFor(TestProcessor p, int seq, P2PCommand cmd) throws Exception {
        for (int i = 0; i < WAIT_RETRY_COUNT; i++) {
            P2PWrapper<?> wrapper = p.poll(seq, cmd);
            if (wrapper != null) {
                return wrapper;
            }
            Thread.sleep(WAIT_SLEEP_MILLIS);
        }
        String snapshot = p.outgoing.stream().map(wrapper -> {
            return wrapper.getCommand() + "#" + wrapper.getSeq() + ":" + wrapper.getData();
        }).toList().toString();
        Assertions.fail("missing " + cmd + " seq=" + seq + " outgoing=" + snapshot);
        return null;
    }

    private static RpcFrame waitForRpcFrame(TestProcessor p, int seq, RpcFrameType frameType) throws Exception {
        for (int i = 0; i < WAIT_RETRY_COUNT; i++) {
            RpcFrame frame = p.pollRpcFrame(seq, frameType);
            if (frame != null) {
                return frame;
            }
            Thread.sleep(WAIT_SLEEP_MILLIS);
        }
        String snapshot = p.outgoing.stream().map(wrapper -> {
            return wrapper.getCommand() + "#" + wrapper.getSeq() + ":" + wrapper.getData();
        }).toList().toString();
        Assertions.fail("missing rpc frame " + frameType + " seq=" + seq + " outgoing=" + snapshot);
        return null;
    }

    private static RpcFrame buildRpcFrame(
        long requestId,
        String service,
        String method,
        RpcCallType callType,
        RpcFrameType frameType,
        Message payload,
        boolean endOfStream
    ) {
        RpcFrame.Builder builder = RpcFrame.newBuilder()
            .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder()
                .setRequestId(requestId)
                .setService(service)
                .setMethod(method)
                .setServiceVersion("v1")
                .setCallType(callType)
                .build())
            .setFrameType(frameType)
            .setEndOfStream(endOfStream);
        if (payload != null) {
            builder.setPayload(payload.toByteString());
        }
        return builder.build();
    }

    private static StreamP2PWrapper<byte[]> buildRpcStreamMessage(int seq, int index, RpcFrame frame, boolean completed) {
        return StreamP2PWrapper.buildStream(seq, index, P2PCommand.RPC_STREAM, frame.toByteArray(), completed);
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
            // 测试桩不绑定真实 Netty channel，直接按连接标记模拟活跃态。
            return connected;
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

    static class RespondingStreamHandler extends AbstractStreamRequestAdapter implements javax.net.p2p.interfaces.StreamRequest {
        private final List<String> messages = new ArrayList<>();

        @Override
        public P2PCommand getCommand() {
            return P2PCommand.DATA_TRANSFER;
        }

        @Override
        public StreamP2PWrapper request(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
            if (message.getData() != null) {
                messages.add(String.valueOf(message.getData()));
            }
            if (!message.isCompleted()) {
                return null;
            }
            return StreamP2PWrapper.buildStream(message.getSeq(), message.getIndex(), P2PCommand.DATA_TRANSFER, String.join(",", messages), true);
        }

        @Override
        public void cancel(AbstractSendMesageExecutor executor, StreamP2PWrapper message) {
        }

        @Override
        public void processStream(AbstractSendMesageExecutor executor, P2PWrapper request) {
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
