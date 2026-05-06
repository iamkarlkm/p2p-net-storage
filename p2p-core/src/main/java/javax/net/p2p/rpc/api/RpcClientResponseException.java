package javax.net.p2p.rpc.api;

/**
 * 携带响应上下文的客户端 RPC 异常。
 */
public final class RpcClientResponseException extends IllegalStateException {
    private final RpcClientResponseContext context;

    public RpcClientResponseException(String message, RpcClientResponseContext context) {
        super(message == null ? "" : message);
        this.context = context;
    }

    public RpcClientResponseContext context() {
        return context;
    }
}
