package javax.net.p2p.rpc.server;

import java.util.concurrent.ConcurrentMap;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.channel.AbstractLongTimedRequestAdapter;
import javax.net.p2p.channel.AbstractStreamRequestAdapter;
import javax.net.p2p.model.CancelP2PWrapper;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.model.StreamP2PWrapper;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcFrameType;
import javax.net.p2p.rpc.proto.RpcFlowControl;
import javax.net.p2p.rpc.proto.RpcStatus;
import javax.net.p2p.rpc.proto.RpcStatusCode;

/**
 * RPC_CONTROL 复用现有取消链路。
 */
public final class RpcControlSupport {

    private RpcControlSupport() {
    }

    public static P2PWrapper<byte[]> handleControl(
        P2PWrapper<byte[]> request,
        ConcurrentMap<Integer, AbstractLongTimedRequestAdapter> longTimedMap,
        ConcurrentMap<Integer, AbstractStreamRequestAdapter> streamMap
    ) {
        RpcFrame requestFrame;
        try {
            requestFrame = RpcFrame.parseFrom(request.getData());
        } catch (Exception ex) {
            return buildResponse(request, RpcFrame.getDefaultInstance(), RpcStatusCode.BAD_REQUEST, ex.toString());
        }
        if (requestFrame.getFrameType() == RpcFrameType.HEARTBEAT) {
            return buildResponse(request, requestFrame, RpcStatusCode.OK, "alive");
        }
        if (requestFrame.getFrameType() == RpcFrameType.WINDOW_UPDATE) {
            return handleWindowUpdate(request, requestFrame);
        }
        if (requestFrame.getFrameType() != RpcFrameType.CANCEL) {
            return buildResponse(request, requestFrame, RpcStatusCode.METHOD_NOT_ALLOWED, "only cancel, heartbeat, and window_update are supported");
        }
        long targetRequestId = requestFrame.getMeta().getRequestId();
        if (targetRequestId <= 0L || targetRequestId > Integer.MAX_VALUE) {
            return buildResponse(request, requestFrame, RpcStatusCode.BAD_REQUEST, "invalid target request id");
        }
        int requestId = (int) targetRequestId;

        AbstractLongTimedRequestAdapter longTimed = longTimedMap.remove(requestId);
        if (longTimed != null) {
            longTimed.asyncProcess(new CancelP2PWrapper(requestId));
            return buildResponse(request, requestFrame, RpcStatusCode.CANCELED, "canceled");
        }

        AbstractStreamRequestAdapter stream = streamMap.remove(requestId);
        if (stream != null) {
            RpcServerStreamControlRegistry.remove(requestId);
            stream.asyncProcess(stream, StreamP2PWrapper.buildStream(requestId, true));
            return buildResponse(request, requestFrame, RpcStatusCode.CANCELED, "canceled");
        }

        return buildResponse(request, requestFrame, RpcStatusCode.NOT_FOUND, "task not found");
    }

    private static P2PWrapper<byte[]> handleWindowUpdate(P2PWrapper<byte[]> request, RpcFrame requestFrame) {
        long targetRequestId = requestFrame.getMeta().getRequestId();
        if (targetRequestId <= 0L || targetRequestId > Integer.MAX_VALUE) {
            return buildResponse(request, requestFrame, RpcStatusCode.BAD_REQUEST, "invalid target request id");
        }
        if (!requestFrame.hasFlowControl()) {
            return buildResponse(request, requestFrame, RpcStatusCode.BAD_REQUEST, "missing flow control");
        }
        RpcFlowControl flowControl = requestFrame.getFlowControl();
        if (flowControl.getPermits() == 0 && flowControl.getMaxInflightFrames() == 0 && flowControl.getMaxFrameBytes() == 0) {
            return buildResponse(request, requestFrame, RpcStatusCode.BAD_REQUEST, "empty flow control");
        }
        try {
            boolean updated = RpcServerStreamControlRegistry.applyWindowUpdate((int) targetRequestId, flowControl);
            if (!updated) {
                return buildResponse(request, requestFrame, RpcStatusCode.NOT_FOUND, "task not found");
            }
        } catch (Exception ex) {
            return buildResponse(request, requestFrame, RpcStatusCode.INTERNAL_ERROR, ex.toString());
        }
        return buildResponse(request, requestFrame, RpcStatusCode.OK, "window updated");
    }

    private static P2PWrapper<byte[]> buildResponse(
        P2PWrapper<byte[]> request,
        RpcFrame requestFrame,
        RpcStatusCode code,
        String message
    ) {
        RpcFrame responseFrame = RpcFrames.complete(
            requestFrame == null ? RpcFrame.getDefaultInstance() : requestFrame,
            new byte[0],
            RpcStatus.newBuilder()
                .setCode(code)
                .setMessage(message == null ? "" : message)
                .setRetriable(false)
                .build(),
            true
        );
        return P2PWrapper.build(request.getSeq(), P2PCommand.RPC_CONTROL, responseFrame.toByteArray());
    }
}
