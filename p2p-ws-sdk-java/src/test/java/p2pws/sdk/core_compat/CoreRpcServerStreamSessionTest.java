package p2pws.sdk.core_compat;

import java.util.ArrayList;
import java.util.List;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PStdError;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.echo.proto.EchoResponse;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoreRpcServerStreamSessionTest {

    @Test
    public void reassemblesChunkedDataAndSendsWindowUpdate() {
        int requestId = 7;
        List<EchoResponse> received = new ArrayList<>();
        List<RpcFrame> controls = new ArrayList<>();
        CoreRpcStreamObserver<EchoResponse> observer = new CoreRpcStreamObserver<>() {
            @Override
            public void onNext(EchoResponse value) {
                received.add(value);
            }
        };
        CoreRpcServerStreamSession<EchoResponse> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_STREAM,
            EchoResponse.class,
            observer,
            controls::add,
            1
        );

        EchoResponse response = EchoResponse.newBuilder().setMessage("hello").setServerTime(1L).build();
        byte[] payload = response.toByteArray();
        int mid = Math.max(1, payload.length / 2);
        byte[] p1 = new byte[mid];
        byte[] p2 = new byte[payload.length - mid];
        System.arraycopy(payload, 0, p1, 0, p1.length);
        System.arraycopy(payload, mid, p2, 0, p2.length);

        RpcFrame f1 = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.DATA)
            .setPayload(com.google.protobuf.ByteString.copyFrom(p1))
            .setChunkIndex(0)
            .setEndOfMessage(false)
            .build();
        RpcFrame f2 = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.DATA)
            .setPayload(com.google.protobuf.ByteString.copyFrom(p2))
            .setChunkIndex(1)
            .setEndOfMessage(true)
            .build();

        session.accept(StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.RPC_STREAM, f1.toByteArray(), false));
        session.accept(StreamP2PWrapper.buildStream(requestId, 2, P2PCommand.RPC_STREAM, f2.toByteArray(), false));

        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).getMessage());
        assertEquals(1, controls.size());
        assertEquals(RpcFrameType.WINDOW_UPDATE, controls.get(0).getFrameType());
        assertEquals(requestId, controls.get(0).getMeta().getRequestId());
        assertEquals(1, controls.get(0).getFlowControl().getPermits());
    }

    @Test
    public void errorFrameIsReportedAsResponseExceptionWithContext() {
        int requestId = 8;
        List<CoreRpcResponseContext> contexts = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        CoreRpcStreamObserver<EchoResponse> observer = new CoreRpcStreamObserver<>() {
            @Override
            public void onNext(EchoResponse value) {
            }

            @Override
            public void onResponseContext(CoreRpcResponseContext context) {
                contexts.add(context);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        };
        CoreRpcServerStreamSession<EchoResponse> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_STREAM,
            EchoResponse.class,
            observer,
            f -> {},
            1
        );
        RpcFrame error = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.ERROR)
            .setStatus(RpcStatus.newBuilder().setCode(RpcStatusCode.INTERNAL_ERROR).setMessage("boom").build())
            .setEndOfStream(true)
            .build();

        session.accept(StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.RPC_STREAM, error.toByteArray(), true));

        assertEquals(1, contexts.size());
        assertEquals(RpcFrameType.ERROR, contexts.get(0).frameType());
        assertEquals(RpcStatusCode.INTERNAL_ERROR, contexts.get(0).status().getCode());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof CoreRpcResponseException);
        assertEquals(RpcStatusCode.INTERNAL_ERROR, ((CoreRpcResponseException) errors.get(0)).context().status().getCode());
    }

    @Test
    public void stdErrorIsMappedToResponseExceptionWithContext() {
        int requestId = 9;
        List<CoreRpcResponseContext> contexts = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        CoreRpcStreamObserver<EchoResponse> observer = new CoreRpcStreamObserver<>() {
            @Override
            public void onNext(EchoResponse value) {
            }

            @Override
            public void onResponseContext(CoreRpcResponseContext context) {
                contexts.add(context);
            }

            @Override
            public void onError(Throwable error) {
                errors.add(error);
            }
        };
        CoreRpcServerStreamSession<EchoResponse> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_EVENT,
            EchoResponse.class,
            observer,
            f -> {},
            1
        );
        P2PStdError err = new P2PStdError();
        err.setKey("auth.permission_denied");
        err.setMessage("denied");
        err.setCode(-1);

        session.accept(StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.STD_ERROR, err, true));

        assertEquals(1, contexts.size());
        assertEquals(RpcFrameType.ERROR, contexts.get(0).frameType());
        assertEquals(RpcStatusCode.FORBIDDEN, contexts.get(0).status().getCode());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0) instanceof CoreRpcResponseException);
        assertEquals(RpcStatusCode.FORBIDDEN, ((CoreRpcResponseException) errors.get(0)).context().status().getCode());
    }

    @Test
    public void closeStopsProcessingAndWindowUpdate() {
        int requestId = 10;
        List<RpcFrame> controls = new ArrayList<>();
        List<EchoResponse> items = new ArrayList<>();
        CoreRpcStreamObserver<EchoResponse> observer = items::add;
        CoreRpcServerStreamSession<EchoResponse> session = new CoreRpcServerStreamSession<>(
            requestId,
            P2PCommand.RPC_STREAM,
            EchoResponse.class,
            observer,
            controls::add,
            1
        );
        session.close();

        RpcFrame data = RpcFrame.newBuilder()
            .setFrameType(RpcFrameType.DATA)
            .setPayload(EchoResponse.newBuilder().setMessage("x").build().toByteString())
            .setChunkIndex(0)
            .setEndOfMessage(true)
            .setEndOfStream(false)
            .build();
        session.accept(StreamP2PWrapper.buildStream(requestId, 1, P2PCommand.RPC_STREAM, data.toByteArray(), false));

        assertEquals(0, items.size());
        assertEquals(0, controls.size());
    }
}
