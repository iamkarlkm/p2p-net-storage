package javax.net.p2p.error;

import java.util.Collections;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.P2PWrapper;

public final class P2PErrors {

    private P2PErrors() {
    }

    public static P2PWrapper<P2PStdError> stdError(int seq, P2PErrorCode code) {
        return stdError(seq, code, null, null);
    }

    public static P2PWrapper<P2PStdError> stdError(int seq, P2PErrorCode code, String message) {
        return stdError(seq, code, message, null);
    }

    public static P2PWrapper<P2PStdError> stdError(int seq, P2PErrorCode code, String message, Map<String, String> details) {
        P2PErrorCode safe = code == null ? P2PErrorCode.UNKNOWN : code;
        String msg = (message == null || message.isBlank()) ? safe.defaultMessage() : message;
        Map<String, String> d = details == null ? Collections.emptyMap() : details;
        P2PStdError payload = new P2PStdError(safe.code(), safe.key(), msg, safe.retriable(), d);
        return P2PWrapper.build(seq, P2PCommand.STD_ERROR, payload);
    }

    public static P2PStdError asStdError(Object data) {
        if (data instanceof P2PStdError e) {
            return e;
        }
        String msg = data == null ? "" : String.valueOf(data);
        return new P2PStdError(P2PErrorCode.UNKNOWN.code(), P2PErrorCode.UNKNOWN.key(), msg, P2PErrorCode.UNKNOWN.retriable(), Collections.emptyMap());
    }

    public static RuntimeException asRuntimeException(P2PWrapper response) {
        if (response == null) {
            return new P2PErrorException(new P2PStdError(P2PErrorCode.UNKNOWN.code(), P2PErrorCode.UNKNOWN.key(), "null response", false, Collections.emptyMap()));
        }
        Object data = response.getData();
        if (data instanceof P2PStdError e) {
            return new P2PErrorException(e);
        }
        return new RuntimeException(String.valueOf(data));
    }
}

