package p2pws.sdk.core_compat;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Consumer;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PStdError;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

final class CoreRpcServerStreamSession<Resp extends Message> implements Consumer<StreamP2PWrapper> {
    private final int requestId;
    private final P2PCommand streamCommand;
    private final String streamCommandName;
    private final Class<Resp> responseType;
    private final CoreRpcStreamObserver<Resp> observer;
    private final Consumer<RpcFrame> controlSender;
    private final int windowUpdateBatch;

    private final ByteArrayOutputStream chunkBuffer = new ByteArrayOutputStream();
    private int expectedChunkIndex;
    private int consumedSinceWindowUpdate;
    private volatile boolean closed;

    void close() {
        closed = true;
    }

    CoreRpcServerStreamSession(
        int requestId,
        P2PCommand streamCommand,
        Class<Resp> responseType,
        CoreRpcStreamObserver<Resp> observer,
        Consumer<RpcFrame> controlSender,
        int windowUpdateBatch
    ) {
        this.requestId = requestId;
        this.streamCommand = Objects.requireNonNull(streamCommand, "streamCommand");
        this.streamCommandName = streamCommand.name();
        this.responseType = Objects.requireNonNull(responseType, "responseType");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.controlSender = Objects.requireNonNull(controlSender, "controlSender");
        this.windowUpdateBatch = windowUpdateBatch <= 0 ? 2 : windowUpdateBatch;
    }

    @Override
    public void accept(StreamP2PWrapper wrapper) {
        if (closed || wrapper == null) {
            return;
        }
        if (wrapper.getCommand() == P2PCommand.STD_ERROR) {
            Object data = wrapper.getData();
            P2PStdError stdError = data instanceof P2PStdError e ? e : null;
            RpcStatus status = mapStdError(stdError);
            CoreRpcResponseContext context = new CoreRpcResponseContext(
                RpcMeta.getDefaultInstance(),
                status,
                RpcFrameType.ERROR,
                true,
                Collections.emptyMap(),
                Collections.emptyMap()
            );
            observer.onResponseContext(context);
            observer.onError(new CoreRpcResponseException(status.getMessage(), context));
            closed = true;
            return;
        }
        if (wrapper.getCommand() != streamCommand) {
            return;
        }
        Object data = wrapper.getData();
        if (!(data instanceof byte[] bytes)) {
            observer.onError(new IllegalStateException(streamCommandName + " invalid payload type"));
            closed = true;
            return;
        }
        RpcFrame frame;
        try {
            frame = RpcFrame.parseFrom(bytes);
        } catch (Exception e) {
            RpcStatus status = RpcStatus.newBuilder()
                .setCode(RpcStatusCode.INTERNAL_ERROR)
                .setMessage(e.getMessage() == null ? "" : e.getMessage())
                .setRetriable(false)
                .build();
            CoreRpcResponseContext context = new CoreRpcResponseContext(
                RpcMeta.getDefaultInstance(),
                status,
                RpcFrameType.ERROR,
                true,
                Collections.emptyMap(),
                Collections.emptyMap()
            );
            observer.onResponseContext(context);
            observer.onError(new CoreRpcResponseException(status.getMessage(), context));
            closed = true;
            return;
        }

        if (frame.getFrameType() == RpcFrameType.DATA) {
            byte[] payload = tryAssemblePayload(frame);
            if (payload == null) {
                return;
            }
            Resp msg;
            try {
                msg = parseMessage(responseType, payload);
            } catch (Exception e) {
                observer.onError(e);
                closed = true;
                return;
            }
            observer.onNext(msg);
            consumedSinceWindowUpdate++;
            if (consumedSinceWindowUpdate >= windowUpdateBatch) {
                consumedSinceWindowUpdate = 0;
                RpcFrame update = RpcFrame.newBuilder()
                    .setFrameType(RpcFrameType.WINDOW_UPDATE)
                    .setMeta(RpcFrame.getDefaultInstance().getMeta().toBuilder().setRequestId(requestId).build())
                    .setFlowControl(RpcFrame.getDefaultInstance().getFlowControl().toBuilder().setPermits(windowUpdateBatch).build())
                    .setEndOfStream(true)
                    .build();
                controlSender.accept(update);
            }
            return;
        }
        if (frame.getFrameType() == RpcFrameType.CLOSE) {
            closed = true;
            observer.onResponseContext(toContext(frame));
            observer.onCompleted();
            return;
        }
        if (frame.getFrameType() == RpcFrameType.ERROR) {
            closed = true;
            CoreRpcResponseContext context = toContext(frame);
            observer.onResponseContext(context);
            if (frame.getStatus().getCode() == RpcStatusCode.OK) {
                observer.onCompleted();
            } else {
                observer.onError(new CoreRpcResponseException(frame.getStatus().getMessage(), context));
            }
        }
    }

    private static CoreRpcResponseContext toContext(RpcFrame frame) {
        return new CoreRpcResponseContext(
            frame.getMeta(),
            frame.getStatus(),
            frame.getFrameType(),
            frame.getEndOfStream(),
            frame.getMeta().getResponseHeadersMap(),
            frame.getMeta().getResponseTrailersMap()
        );
    }

    private static RpcStatus mapStdError(P2PStdError stdError) {
        if (stdError == null) {
            return RpcStatus.newBuilder()
                .setCode(RpcStatusCode.INTERNAL_ERROR)
                .setMessage("unknown error")
                .setRetriable(false)
                .build();
        }
        String key = stdError.getKey() == null ? "" : stdError.getKey();
        RpcStatusCode code;
        if ("auth.handshake_required".equals(key) || "auth.login_required".equals(key) || "auth.missing_user_id".equals(key)) {
            code = RpcStatusCode.UNAUTHORIZED;
        } else if ("auth.permission_denied".equals(key)) {
            code = RpcStatusCode.FORBIDDEN;
        } else if ("common.invalid_request".equals(key)) {
            code = RpcStatusCode.BAD_REQUEST;
        } else if ("common.deadline_exceeded".equals(key)) {
            code = RpcStatusCode.DEADLINE_EXCEEDED;
        } else if ("common.not_found".equals(key)) {
            code = RpcStatusCode.NOT_FOUND;
        } else if ("service.unavailable".equals(key) || "service.backend_not_registered".equals(key)) {
            code = RpcStatusCode.SERVICE_UNAVAILABLE;
        } else {
            code = RpcStatusCode.INTERNAL_ERROR;
        }
        RpcStatus.Builder builder = RpcStatus.newBuilder()
            .setCode(code)
            .setMessage(stdError.getMessage() == null ? "" : stdError.getMessage())
            .setRetriable(stdError.isRetriable())
            .putDetails("p2p.std_error_key", key)
            .putDetails("p2p.std_error_code", String.valueOf(stdError.getCode()));
        if (stdError.getDetails() != null && !stdError.getDetails().isEmpty()) {
            builder.putAllDetails(stdError.getDetails());
        }
        return builder.build();
    }

    private byte[] tryAssemblePayload(RpcFrame frame) {
        ByteString part = frame.getPayload();
        if (frame.getEndOfMessage()) {
            if (chunkBuffer.size() == 0 && frame.getChunkIndex() == 0) {
                return part.toByteArray();
            }
            appendChunk(frame, part);
            byte[] out = chunkBuffer.toByteArray();
            resetChunks();
            return out;
        }
        if (frame.getChunkIndex() == 0 && chunkBuffer.size() > 0) {
            resetChunks();
        }
        appendChunk(frame, part);
        return null;
    }

    private void appendChunk(RpcFrame frame, ByteString part) {
        if (frame.getChunkIndex() != expectedChunkIndex) {
            throw new IllegalStateException("RPC chunk index mismatch");
        }
        chunkBuffer.writeBytes(part.toByteArray());
        expectedChunkIndex++;
    }

    private void resetChunks() {
        chunkBuffer.reset();
        expectedChunkIndex = 0;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Message> T parseMessage(Class<T> clazz, byte[] data) throws Exception {
        Method m = clazz.getMethod("parseFrom", byte[].class);
        return (T) m.invoke(null, data);
    }
}
