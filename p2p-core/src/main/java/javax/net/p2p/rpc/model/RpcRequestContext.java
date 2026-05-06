package javax.net.p2p.rpc.model;

import io.netty.channel.Channel;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.rpc.proto.RpcFrame;
import javax.net.p2p.rpc.proto.RpcMeta;

/**
 * RPC 请求上下文，聚合传输层与协议层关键信息。
 */
public final class RpcRequestContext {

    private final int seq;
    private final long requestId;
    private final String service;
    private final String method;
    private final String version;
    private final long deadlineEpochMs;
    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String callerNodeId;
    private final String callerUserId;
    private final Map<String, String> headers;
    private final ConcurrentHashMap<String, String> responseHeaders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> responseTrailers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> responseStatusDetails = new ConcurrentHashMap<>();
    private final AtomicLong handlingStartedAtMs = new AtomicLong(-1L);
    private final Channel channel;

    public RpcRequestContext(
        int seq,
        long requestId,
        String service,
        String method,
        String version,
        long deadlineEpochMs,
        String traceId,
        String spanId,
        String parentSpanId,
        String callerNodeId,
        String callerUserId,
        Map<String, String> headers,
        Channel channel
    ) {
        this.seq = seq;
        this.requestId = requestId;
        this.service = service;
        this.method = method;
        this.version = version;
        this.deadlineEpochMs = deadlineEpochMs;
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId == null ? "" : parentSpanId;
        this.callerNodeId = callerNodeId == null ? "" : callerNodeId;
        this.callerUserId = callerUserId == null ? "" : callerUserId;
        this.headers = headers == null ? Collections.emptyMap() : Collections.unmodifiableMap(headers);
        this.channel = channel;
    }

    public static RpcRequestContext from(P2PWrapper<?> wrapper, RpcFrame frame, Channel channel) {
        RpcMeta meta = frame.getMeta();
        return new RpcRequestContext(
            wrapper.getSeq(),
            meta.getRequestId(),
            meta.getService(),
            meta.getMethod(),
            meta.getServiceVersion(),
            meta.getDeadlineEpochMs(),
            meta.getTraceId(),
            meta.getSpanId(),
            meta.getParentSpanId(),
            meta.getCallerNodeId(),
            meta.getCallerUserId(),
            meta.getHeadersMap(),
            channel
        );
    }

    public boolean isDeadlineExceeded(long nowMs) {
        return deadlineEpochMs > 0 && nowMs > deadlineEpochMs;
    }

    public int seq() {
        return seq;
    }

    public long requestId() {
        return requestId;
    }

    public String service() {
        return service;
    }

    public String method() {
        return method;
    }

    public String version() {
        return version;
    }

    public long deadlineEpochMs() {
        return deadlineEpochMs;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String callerNodeId() {
        return callerNodeId;
    }

    public String callerUserId() {
        return callerUserId;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public RpcRequestContext putResponseHeader(String key, String value) {
        putNonBlank(responseHeaders, key, value);
        return this;
    }

    public RpcRequestContext putAllResponseHeaders(Map<String, String> values) {
        putAllNonBlank(responseHeaders, values);
        return this;
    }

    public Map<String, String> responseHeaders() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(responseHeaders));
    }

    public RpcRequestContext putResponseTrailer(String key, String value) {
        putNonBlank(responseTrailers, key, value);
        return this;
    }

    public RpcRequestContext putAllResponseTrailers(Map<String, String> values) {
        putAllNonBlank(responseTrailers, values);
        return this;
    }

    public Map<String, String> responseTrailers() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(responseTrailers));
    }

    public RpcRequestContext putResponseStatusDetail(String key, String value) {
        putNonBlank(responseStatusDetails, key, value);
        return this;
    }

    public RpcRequestContext putAllResponseStatusDetails(Map<String, String> values) {
        putAllNonBlank(responseStatusDetails, values);
        return this;
    }

    public Map<String, String> responseStatusDetails() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(responseStatusDetails));
    }

    public void markHandlingStarted() {
        handlingStartedAtMs.compareAndSet(-1L, System.currentTimeMillis());
    }

    public long elapsedHandlingMillis() {
        long startedAtMs = handlingStartedAtMs.get();
        if (startedAtMs < 0) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - startedAtMs;
        return Math.max(elapsed, 0L);
    }

    public Channel channel() {
        return channel;
    }

    private static void putAllNonBlank(Map<String, String> target, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> putNonBlank(target, key, value));
    }

    private static void putNonBlank(Map<String, String> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        target.put(key, value);
    }
}
