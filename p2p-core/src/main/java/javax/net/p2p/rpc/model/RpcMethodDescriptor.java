package javax.net.p2p.rpc.model;

import com.google.protobuf.Message;
import javax.net.p2p.rpc.api.RpcResponseStatusResolver;
import javax.net.p2p.rpc.proto.RpcCallType;

/**
 * RPC 方法描述，绑定类型信息与执行器。
 */
public final class RpcMethodDescriptor {

    private final RpcMethodKey key;
    private final RpcCallType callType;
    private final boolean idempotent;
    private final Class<? extends Message> requestType;
    private final Class<? extends Message> responseType;
    private final Object invoker;
    private final RpcResponseStatusResolver<? extends Message> responseStatusResolver;

    public RpcMethodDescriptor(
        String service,
        String method,
        String version,
        RpcCallType callType,
        boolean idempotent,
        Class<? extends Message> requestType,
        Class<? extends Message> responseType,
        Object invoker,
        RpcResponseStatusResolver<? extends Message> responseStatusResolver
    ) {
        if (callType == null) {
            throw new IllegalArgumentException("callType 不能为空");
        }
        if (requestType == null || responseType == null) {
            throw new IllegalArgumentException("请求/响应类型不能为空");
        }
        if (invoker == null) {
            throw new IllegalArgumentException("invoker 不能为空");
        }
        this.key = new RpcMethodKey(service, method, version);
        this.callType = callType;
        this.idempotent = idempotent;
        this.requestType = requestType;
        this.responseType = responseType;
        this.invoker = invoker;
        this.responseStatusResolver = responseStatusResolver;
    }

    public RpcMethodKey key() {
        return key;
    }

    public RpcCallType callType() {
        return callType;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public Class<? extends Message> requestType() {
        return requestType;
    }

    public Class<? extends Message> responseType() {
        return responseType;
    }

    public Object invoker() {
        return invoker;
    }

    public RpcResponseStatusResolver<? extends Message> responseStatusResolver() {
        return responseStatusResolver;
    }
}
