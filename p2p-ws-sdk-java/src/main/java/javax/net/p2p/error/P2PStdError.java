package javax.net.p2p.error;

import java.util.Collections;
import java.util.Map;

public final class P2PStdError {
    private int code;
    private String key;
    private String message;
    private boolean retriable;
    private Map<String, String> details = Collections.emptyMap();

    public P2PStdError() {
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isRetriable() {
        return retriable;
    }

    public void setRetriable(boolean retriable) {
        this.retriable = retriable;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public void setDetails(Map<String, String> details) {
        this.details = details == null ? Collections.emptyMap() : details;
    }
}

