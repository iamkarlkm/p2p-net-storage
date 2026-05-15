package p2pws.sdk.core_compat;

public final class CoreRpcResponseException extends RuntimeException {
    private final CoreRpcResponseContext context;

    public CoreRpcResponseException(String message, CoreRpcResponseContext context) {
        super(message);
        this.context = context;
    }

    public CoreRpcResponseContext context() {
        return context;
    }
}

