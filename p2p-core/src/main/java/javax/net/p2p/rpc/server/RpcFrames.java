package javax.net.p2p.rpc.server;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
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
        return RpcFrame.newBuilder()
            .setMeta(request.getMeta())
            .setFrameType(endOfStream ? RpcFrameType.CLOSE : RpcFrameType.DATA)
            .setPayload(ByteString.copyFrom(payload == null ? new byte[0] : payload))
            .setStatus(status == null ? RpcStatus.newBuilder().setCode(RpcStatusCode.OK).setRetriable(false).build() : status)
            .setEndOfStream(endOfStream)
            .build();
    }

    public static RpcFrame error(RpcFrame request, RpcStatusCode code, String message, boolean retriable) {
        return RpcFrame.newBuilder()
            .setMeta(request.getMeta())
            .setFrameType(RpcFrameType.ERROR)
            .setStatus(RpcStatus.newBuilder()
                .setCode(Objects.requireNonNullElse(code, RpcStatusCode.INTERNAL_ERROR))
                .setMessage(message == null ? "" : message)
                .setRetriable(retriable)
                .build())
            .setEndOfStream(true)
            .build();
    }

    /**
     * 按 maxFrameBytes 拆分单条逻辑消息，客户端依赖 chunk_index/end_of_message 重组。
     */
    public static List<RpcFrame> chunkDataFrames(RpcFrame request, byte[] payload, int maxFrameBytes) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        if (maxFrameBytes <= 0 || safePayload.length <= maxFrameBytes) {
            return List.of(data(request, safePayload, 0, true));
        }
        List<RpcFrame> frames = new ArrayList<>((safePayload.length + maxFrameBytes - 1) / maxFrameBytes);
        int chunkIndex = 0;
        for (int offset = 0; offset < safePayload.length; offset += maxFrameBytes) {
            int length = Math.min(maxFrameBytes, safePayload.length - offset);
            byte[] chunk = new byte[length];
            System.arraycopy(safePayload, offset, chunk, 0, length);
            frames.add(data(request, chunk, chunkIndex++, offset + length >= safePayload.length));
        }
        return frames;
    }

    public static RpcFrame data(RpcFrame request, byte[] payload, int chunkIndex, boolean endOfMessage) {
        return ok(request, payload, false).toBuilder()
            .setChunkIndex(chunkIndex)
            .setEndOfMessage(endOfMessage)
            .build();
    }
}
