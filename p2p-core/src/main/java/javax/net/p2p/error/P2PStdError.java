package javax.net.p2p.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class P2PStdError {
    private int code;
    private String key;
    private String message;
    private boolean retriable;
    private Map<String, String> details;

    public P2PStdError() {
    }

    public P2PStdError(int code, String key, String message, boolean retriable, Map<String, String> details) {
        this.code = code;
        this.key = key == null ? "" : key;
        this.message = message == null ? "" : message;
        this.retriable = retriable;
        if (details == null || details.isEmpty()) {
            this.details = Collections.emptyMap();
        } else {
            this.details = new LinkedHashMap<>(details);
        }
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
        this.key = key == null ? "" : key;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message == null ? "" : message;
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
        if (details == null || details.isEmpty()) {
            this.details = Collections.emptyMap();
        } else {
            this.details = new LinkedHashMap<>(details);
        }
    }

    @Override
    public String toString() {
        return "P2PStdError{code=" + code + ",key=" + key + ",message=" + message + ",retriable=" + retriable + ",details=" + details + "}";
    }
}

