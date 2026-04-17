package javax.net.p2p.cache.model;

/**
 * 分布式锁响应模型。
 */
public class LockResponse {
    private boolean ok;
    private String error;
    private boolean success;

    public LockResponse() {
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}
