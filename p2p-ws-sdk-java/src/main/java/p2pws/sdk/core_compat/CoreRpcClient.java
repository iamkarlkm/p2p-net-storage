package p2pws.sdk.core_compat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PStdError;
import javax.net.p2p.rpc.echo.proto.EchoRequest;
import javax.net.p2p.rpc.echo.proto.EchoResponse;
import javax.net.p2p.rpc.proto.DiscoverRequest;
import javax.net.p2p.rpc.proto.DiscoverResponse;
import javax.net.p2p.rpc.proto.HealthCheckRequest;
import javax.net.p2p.rpc.proto.HealthCheckResponse;
import javax.net.p2p.rpc.proto.RpcCallType;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatusCode;

public final class CoreRpcClient {
    private final CoreWsClient ws;

    public CoreRpcClient(CoreWsClient ws) {
        this.ws = ws;
    }

    public CompletableFuture<DiscoverResponse> discover(String service, boolean includeMethods) {
        DiscoverRequest req = DiscoverRequest.newBuilder()
                .setService(service == null ? "" : service)
                .setIncludeMethods(includeMethods)
                .build();
        return unary(P2PCommand.RPC_DISCOVER, service, "Discover", req.toByteArray(), Duration.ofSeconds(10))
                .thenApply(r -> {
                    try {
                        return DiscoverResponse.parseFrom(r.getPayload());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public CompletableFuture<HealthCheckResponse> health(String service) {
        HealthCheckRequest req = HealthCheckRequest.newBuilder()
                .setService(service == null ? "" : service)
                .build();
        return unary(P2PCommand.RPC_HEALTH, service, "Check", req.toByteArray(), Duration.ofSeconds(10))
                .thenApply(r -> {
                    try {
                        return HealthCheckResponse.parseFrom(r.getPayload());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public CompletableFuture<EchoResponse> echo(String message) {
        EchoRequest req = EchoRequest.newBuilder().setMessage(message == null ? "" : message).build();
        return unary(
                P2PCommand.RPC_UNARY,
                "p2p.rpc.echo.v1.EchoService",
                "Echo",
                req.toByteArray(),
                Duration.ofSeconds(10))
                .thenApply(r -> {
                    try {
                        return EchoResponse.parseFrom(r.getPayload());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private CompletableFuture<RpcFrame> unary(P2PCommand command, String service, String method, byte[] requestPayload, Duration timeout) {
        RpcMeta meta = RpcMeta.newBuilder()
                .setRequestId(System.nanoTime())
                .setService(service == null ? "" : service)
                .setMethod(method == null ? "" : method)
                .setServiceVersion("")
                .setCallType(RpcCallType.UNARY)
                .setDeadlineEpochMs(0)
                .setCodec("protobuf")
                .setIdempotent(false)
                .setMethodHash(0)
                .build();
        RpcFrame frame = RpcFrame.newBuilder()
                .setMeta(meta)
                .setFrameType(RpcFrameType.OPEN)
                .setPayload(com.google.protobuf.ByteString.copyFrom(requestPayload == null ? new byte[0] : requestPayload))
                .setEndOfStream(true)
                .build();
        return ws.request(command, frame.toByteArray(), timeout).thenApply(w -> {
            try {
                if (w.getCommand() == P2PCommand.STD_ERROR) {
                    Object payload = w.getData();
                    if (payload instanceof P2PStdError e) {
                        throw new IllegalStateException(e.getKey() + ": " + e.getMessage());
                    }
                    throw new IllegalStateException(String.valueOf(payload));
                }
                if (w.getCommand() != command) {
                    throw new IllegalStateException("RPC unexpected command: " + w.getCommand());
                }
                Object data = w.getData();
                if (!(data instanceof byte[])) {
                    throw new IllegalStateException("RPC invalid response type");
                }
                RpcFrame resp = RpcFrame.parseFrom((byte[]) data);
                if (!resp.hasStatus()) {
                    throw new IllegalStateException("RPC missing status");
                }
                if (resp.getFrameType() == RpcFrameType.ERROR) {
                    throw new IllegalStateException(resp.getStatus().getMessage());
                }
                if (resp.getStatus().getCode() != RpcStatusCode.OK && resp.getPayload().isEmpty()) {
                    throw new IllegalStateException(resp.getStatus().getMessage());
                }
                return resp;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
