package javax.net.p2p.rpc.server;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.net.p2p.rpc.model.RpcRequestContext;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcMeta;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * RPC 响应帧构造工具。
 */
public final class RpcFrames {

    private RpcFrames() {
    }

    public static RpcFrame ok(RpcFrame request, byte[] payload, boolean endOfStream) {
        return complete(request, payload, RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build(), endOfStream);
    }

    public static RpcFrame complete(RpcFrame request, byte[] payload, RpcStatus status, boolean endOfStream) {
        return complete(request, payload, status, endOfStream, null);
    }

    public static RpcFrame complete(RpcFrame request, byte[] payload, RpcStatus status, boolean endOfStream, RpcRequestContext context) {
        return RpcFrame.newBuilder()
            .setMeta(buildResponseMeta(request, context, endOfStream))
            .setFrameType(endOfStream ? RpcFrameType.CLOSE : RpcFrameType.DATA)
            .setPayload(ByteString.copyFrom(payload == null ? new byte[0] : payload))
            .setStatus(mergeStatusDetails(status, context))
            .setEndOfStream(endOfStream)
            .build();
    }

    public static RpcFrame error(RpcFrame request, RpcStatusCode code, String message, boolean retriable) {
        return error(request, code, message, retriable, null);
    }

    public static RpcFrame error(RpcFrame request, RpcStatusCode code, String message, boolean retriable, RpcRequestContext context) {
        return RpcFrame.newBuilder()
            .setMeta(buildResponseMeta(request, context, true))
            .setFrameType(RpcFrameType.ERROR)
            .setStatus(mergeStatusDetails(RpcStatus.newBuilder()
                .setCode(Objects.requireNonNullElse(code, RpcStatusCode.INTERNAL_ERROR))
                .setMessage(message == null ? "" : message)
                .setRetriable(retriable)
                .build(), context))
            .setEndOfStream(true)
            .build();
    }

    /**
     * 按 maxFrameBytes 拆分单条逻辑消息，客户端依赖 chunk_index/end_of_message 重组。
     */
    public static List<RpcFrame> chunkDataFrames(RpcFrame request, byte[] payload, int maxFrameBytes) {
        return chunkDataFrames(request, payload, maxFrameBytes, null);
    }

    public static List<RpcFrame> chunkDataFrames(RpcFrame request, byte[] payload, int maxFrameBytes, RpcRequestContext context) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        if (maxFrameBytes <= 0 || safePayload.length <= maxFrameBytes) {
            return List.of(data(request, safePayload, 0, true, context));
        }
        List<RpcFrame> frames = new ArrayList<>((safePayload.length + maxFrameBytes - 1) / maxFrameBytes);
        int chunkIndex = 0;
        for (int offset = 0; offset < safePayload.length; offset += maxFrameBytes) {
            int length = Math.min(maxFrameBytes, safePayload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(safePayload, offset, chunk, 0, length);
            frames.add(data(request, chunk, chunkIndex++, offset + length >= safePayload.length, context));
        }
        return frames;
    }

    public static RpcFrame data(RpcFrame request, byte[] payload, int chunkIndex, boolean endOfMessage) {
        return data(request, payload, chunkIndex, endOfMessage, null);
    }

    public static RpcFrame data(RpcFrame request, byte[] payload, int chunkIndex, boolean endOfMessage, RpcRequestContext context) {
        return complete(request, payload, null, false, context).toBuilder()
            .setChunkIndex(chunkIndex)
            .setEndOfMessage(endOfMessage)
            .build();
    }

    private static RpcMeta buildResponseMeta(RpcFrame request, RpcRequestContext context, boolean endOfStream) {
        RpcMeta.Builder builder = request.getMeta().toBuilder();
        if (context == null) {
            return builder.build();
        }
        if (!context.responseHeaders().isEmpty()) {
            builder.putAllResponseHeaders(context.responseHeaders());
        }
        if (endOfStream && !context.responseTrailers().isEmpty()) {
            builder.putAllResponseTrailers(context.responseTrailers());
        }
        return builder.build();
    }

    private static RpcStatus mergeStatusDetails(RpcStatus status, RpcRequestContext context) {
        RpcStatus.Builder builder = (status == null
            ? RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false)
            : status.toBuilder());
        if (context != null && !context.responseStatusDetails().isEmpty()) {
            builder.putAllDetails(context.responseStatusDetails());
        }
        return builder.build();
    }
}
