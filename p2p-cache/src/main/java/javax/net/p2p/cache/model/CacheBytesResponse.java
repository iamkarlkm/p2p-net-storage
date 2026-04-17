package javax.net.p2p.cache.model;

import java.util.List;

/**
 * bytes KV 的响应模型（包含返回值、状态与错误信息）。
 */
public class CacheBytesResponse {
    private boolean ok;
    private String error;
    private byte[] value;
    private boolean exists;
    private long ttlMillis;
    private long number;
    private List<CacheBytesEntry> entries;

    public CacheBytesResponse() {
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

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
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

    public List<CacheBytesEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<CacheBytesEntry> entries) {
        this.entries = entries;
    }
}
