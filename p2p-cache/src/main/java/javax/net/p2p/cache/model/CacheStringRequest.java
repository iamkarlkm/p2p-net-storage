package javax.net.p2p.cache.model;

import java.util.List;

/**
 * String KV 的请求模型（包含操作类型与必要参数）。
 */
public class CacheStringRequest {
    private CacheOp op;
    private String key;
    private String value;
    private long ttlMillis;
    private long delta;
    private List<String> keys;
    private List<CacheStringEntry> entries;

    public CacheStringRequest() {
    }

    public CacheOp getOp() {
        return op;
    }

    public void setOp(CacheOp op) {
        this.op = op;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getTtlMillis() {
        return ttlMillis;
    }

    public void setTtlMillis(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public long getDelta() {
        return delta;
    }

    public void setDelta(long delta) {
        this.delta = delta;
    }

    public List<String> getKeys() {
        return keys;
    }

    public void setKeys(List<String> keys) {
        this.keys = keys;
    }

    public List<CacheStringEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<CacheStringEntry> entries) {
        this.entries = entries;
    }
}
