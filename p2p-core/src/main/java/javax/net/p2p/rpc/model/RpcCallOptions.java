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
        headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
        initialStreamPermits = Math.max(0, initialStreamPermits);
        initialMaxInflightFrames = Math.max(0, initialMaxInflightFrames);
        initialMaxFrameBytes = Math.max(0, initialMaxFrameBytes);
        windowUpdateBatch = Math.max(1, windowUpdateBatch);
    }

    public static RpcCallOptions defaultOptions() {
        return new RpcCallOptions("v1", 0L, "", "", Collections.emptyMap(), false, 2, 0, 0, 2);
    }

    public static RpcCallOptions withDeadline(long deadlineEpochMs) {
        return new RpcCallOptions("v1", deadlineEpochMs, "", "", Collections.emptyMap(), false, 2, 0, 0, 2);
    }

    public RpcCallOptions withInitialStreamFlowControl(int permits, int maxInflightFrames, int maxFrameBytes) {
        return new RpcCallOptions(
            serviceVersion,
            deadlineEpochMs,
            traceId,
            spanId,
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
            headers,
            idempotent,
            initialStreamPermits,
            initialMaxInflightFrames,
            initialMaxFrameBytes,
            batch
        );
    }
}
