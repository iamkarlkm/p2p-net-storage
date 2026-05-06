package javax.net.p2p.error;

public class P2PErrorException extends RuntimeException {
    private final P2PStdError error;

    public P2PErrorException(P2PStdError error) {
        super(error == null ? "" : error.getMessage());
        this.error = error;
    }

    public P2PStdError getError() {
        return error;
    }
}

