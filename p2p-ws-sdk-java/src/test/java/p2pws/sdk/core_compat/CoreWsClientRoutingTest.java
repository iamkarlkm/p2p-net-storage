package p2pws.sdk.core_compat;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import javax.net.p2p.rpc.pubsub.proto.PubSubPublishResponse;
import org.junit.Test;

import static org.junit.Assert.*;

public class CoreWsClientRoutingTest {

    @Test
    public void doesNotCompletePendingOnStreamDataWithoutHandler() throws Exception {
        int magic = 1234;
        int requestId = 10;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);

        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending(client).put(requestId, fut);

        StreamP2PWrapper data = StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.RPC_EVENT, new byte[] {1, 2, 3}, false);
        byte[] frame = CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(data));
        client.onBinaryMessage(frame);

        assertFalse(fut.isDone());
        assertTrue(pending(client).containsKey(requestId));
    }

    @Test
    public void routesStreamDataToHandlerAndKeepsPending() throws Exception {
        int magic = 1234;
        int requestId = 11;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);

        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending(client).put(requestId, fut);

        List<StreamP2PWrapper> received = new ArrayList<>();
        client.registerStreamHandler(requestId, received::add);

        StreamP2PWrapper data = StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.RPC_STREAM, new byte[] {9}, false);
        byte[] frame = CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(data));
        client.onBinaryMessage(frame);

        assertEquals(1, received.size());
        assertFalse(fut.isDone());
        assertTrue(pending(client).containsKey(requestId));
    }

    @Test
    public void completesPendingOnStreamAck() throws Exception {
        int magic = 1234;
        int requestId = 12;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);

        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending(client).put(requestId, fut);

        StreamP2PWrapper ack = StreamP2PWrapper.buildStream(requestId, 0, P2PCommand.STREAM_ACK, new byte[0], false);
        byte[] frame = CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(ack));
        client.onBinaryMessage(frame);

        assertTrue(fut.isDone());
        assertEquals(P2PCommand.STREAM_ACK, fut.get().getCommand());
        assertFalse(pending(client).containsKey(requestId));
    }

    @Test
    public void sendAndAwaitCompletesWhenResponseArrives() throws Exception {
        int magic = 1234;
        int requestId = 13;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);
        setWebSocket(client, new NoopWebSocket());

        CompletableFuture<P2PWrapper> fut = client.sendAndAwait(P2PWrapper.build(requestId, P2PCommand.RPC_UNARY, new byte[] {7}), true, null);

        StreamP2PWrapper resp = StreamP2PWrapper.buildStream(requestId, 0, P2PCommand.RPC_UNARY, new byte[] {8}, false);
        client.onBinaryMessage(CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(resp)));

        assertTrue(fut.isDone());
        assertEquals(P2PCommand.RPC_UNARY, fut.get().getCommand());
    }

    @Test
    public void pubSubPublishCompletes() throws Exception {
        int magic = 1234;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);
        setWebSocket(client, new NoopWebSocket());
        CoreRpcEventClient pubsub = new CoreRpcEventClient(client);

        CompletableFuture<PubSubPublishResponse> fut = pubsub.publish("t", "m", null);
        int requestId = 1;
        Map<Integer, CompletableFuture<P2PWrapper>> pending = pending(client);
        for (int i = 0; i < 100; i++) {
            if (pending.containsKey(i)) {
                requestId = i;
            }
        }

        RpcFrame ok = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.CLOSE)
            .setMeta(RpcMeta.newBuilder().setRequestId(requestId).build())
            .setStatus(RpcStatus.newBuilder().setCode(RpcStatusCode.OK).build())
            .setPayload(PubSubPublishResponse.newBuilder().setAccepted(true).setSubscriberCount(1).build().toByteString())
            .setEndOfStream(true)
            .build();
        StreamP2PWrapper resp = StreamP2PWrapper.buildStream(requestId, 0, P2PCommand.RPC_UNARY, ok.toByteArray(), false);
        client.onBinaryMessage(CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(resp)));

        assertTrue(fut.isDone());
        assertTrue(fut.get().getAccepted());
    }

    @Test
    public void decodesMultipleFramesInSingleWebSocketMessage() throws Exception {
        int magic = 1234;
        int requestId1 = 21;
        int requestId2 = 22;
        CoreWsClient client = new CoreWsClient(URI.create("ws://localhost"), magic, 16);
        setWebSocket(client, new NoopWebSocket());

        CompletableFuture<P2PWrapper> fut = new CompletableFuture<>();
        pending(client).put(requestId1, fut);

        List<StreamP2PWrapper> received = new ArrayList<>();
        client.registerStreamHandler(requestId2, received::add);

        StreamP2PWrapper unaryResp = StreamP2PWrapper.buildStream(requestId1, 0, P2PCommand.RPC_UNARY, new byte[] {1}, false);
        StreamP2PWrapper eventData = StreamP2PWrapper.buildStream(requestId2, 1, P2PCommand.RPC_EVENT, new byte[] {2}, false);

        byte[] f1 = CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(unaryResp));
        byte[] f2 = CoreFrameCodec.encode(magic, ProtostuffCodec.serialize(eventData));
        byte[] merged = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, merged, 0, f1.length);
        System.arraycopy(f2, 0, merged, f1.length, f2.length);

        client.onBinaryMessage(merged);

        assertTrue(fut.isDone());
        assertEquals(1, received.size());
        assertEquals(P2PCommand.RPC_EVENT, received.get(0).getCommand());
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, CompletableFuture<P2PWrapper>> pending(CoreWsClient client) throws Exception {
        Field f = CoreWsClient.class.getDeclaredField("pending");
        f.setAccessible(true);
        return (Map<Integer, CompletableFuture<P2PWrapper>>) f.get(client);
    }

    private static void setWebSocket(CoreWsClient client, WebSocket webSocket) throws Exception {
        Field f = CoreWsClient.class.getDeclaredField("ws");
        f.setAccessible(true);
        f.set(client, webSocket);
    }

    private static final class NoopWebSocket implements WebSocket {
        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(java.nio.ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(java.nio.ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}
