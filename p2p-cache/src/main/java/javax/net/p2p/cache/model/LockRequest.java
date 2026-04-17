package javax.net.p2p.cache.model;

/**
 * 分布式锁请求模型（acquire/release/renew）。
 */
public class LockRequest {
    private LockOp op;
    private String key;
    private String token;
    private long ttlMillis;

    public LockRequest() {
    }

    public LockOp getOp() {
        return op;
    }

    public void setOp(LockOp op) {
        this.op = op;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }
}
