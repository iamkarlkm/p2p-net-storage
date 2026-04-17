package javax.net.p2p.cache.model;

import java.util.List;

/**
 * String KV 的响应模型（包含返回值、状态与错误信息）。
 */
public class CacheStringResponse {
    private boolean ok;
    private String error;
    private String value;
    private boolean exists;
    private long ttlMillis;
    private long number;
    private List<CacheStringEntry> entries;

    public CacheStringResponse() {
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

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isExists() {
        return exists;
    }

    public void setExists(boolean exists) {
        this.exists = exists;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public long getNumber() {
        return number;
    }

    public void setNumber(long number) {
        this.number = number;
    }

    public List<CacheStringEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<CacheStringEntry> entries) {
        this.entries = entries;
    }
}
