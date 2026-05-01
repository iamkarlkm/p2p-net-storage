package javax.net.p2p.rpc.model;

import io.netty.channel.Channel;
import java.util.Collections;
import java.util.Map;
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
    private final Map<String, String> headers;
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

    public Map<String, String> headers() {
        return headers;
    }

    public Channel channel() {
        return channel;
    }
}
