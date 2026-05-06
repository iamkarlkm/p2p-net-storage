package javax.net.p2p.rpc.model;

import java.util.Collections;
import java.util.Map;

/**
 * RPC 调用选项。
 */
public record RpcCallOptions(
    String serviceVersion,
    long deadlineEpochMs,
    String traceId,
    String spanId,
    String parentSpanId,
    String callerNodeId,
    String callerUserId,
    Map<String, String> headers,
    boolean idempotent,
    int initialStreamPermits,
    int initialMaxInflightFrames,
    int initialMaxFrameBytes,
    int windowUpdateBatch
) {

    public RpcCallOptions {
        serviceVersion = serviceVersion == null || serviceVersion.isBlank() ? "v1" : serviceVersion.trim();
        traceId = traceId == null ? "" : traceId;
        spanId = spanId == null ? "" : spanId;
        parentSpanId = parentSpanId == null ? "" : parentSpanId;
        callerNodeId = callerNodeId == null ? "" : callerNodeId;
        callerUserId = callerUserId == null ? "" : callerUserId;
        headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
        initialStreamPermits = Math.max(0, initialStreamPermits);
        initialMaxInflightFrames = Math.max(0, initialMaxInflightFrames);
        initialMaxFrameBytes = Math.max(0, initialMaxFrameBytes);
        windowUpdateBatch = Math.max(1, windowUpdateBatch);
    }

    public static RpcCallOptions defaultOptions() {
        return new RpcCallOptions("v1", 0L, "", "", "", "", "", Collections.emptyMap(), false, 2, 0, 0, 2);
    }

    public static RpcCallOptions withDeadline(long deadlineEpochMs) {
        return new RpcCallOptions("v1", deadlineEpochMs, "", "", "", "", "", Collections.emptyMap(), false, 2, 0, 0, 2);
    }

    public RpcCallOptions withServiceVersion(String version) {
        return new RpcCallOptions(
            version,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            callerNodeId,
            callerUserId,
            headers,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withTracing(String trace, String span, String parentSpan) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            trace,
            span,
            parentSpan,
            callerNodeId,
            callerUserId,
            headers,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withCaller(String nodeId, String userId) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            nodeId,
            userId,
            headers,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withHeaders(Map<String, String> requestHeaders) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            callerNodeId,
            callerUserId,
            requestHeaders,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withIdempotent(boolean requestIdempotent) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            callerNodeId,
            callerUserId,
            headers,
            requestIdempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withInitialStreamFlowControl(int permits, int maxInflightFrames, int maxFrameBytes) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            callerNodeId,
            callerUserId,
            headers,
            idempotent,
            permits,
            maxInflightFrames,
            maxFrameBytes,
            windowUpdateBatch
        );
    }

    public RpcCallOptions withWindowUpdateBatch(int batch) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
            parentSpanId,
            callerNodeId,
            callerUserId,
            headers,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            batch
        );
    }
}
