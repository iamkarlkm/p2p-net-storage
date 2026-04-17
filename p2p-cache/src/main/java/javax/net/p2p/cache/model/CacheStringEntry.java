package javax.net.p2p.cache.model;

/**
 * String KV 的单条键值对（用于批量 mget/mset 场景）。
 */
public class CacheStringEntry {
    private String key;
    private String value;

    public CacheStringEntry() {
    }

    public CacheStringEntry(String key, String value) {
        this.key = key;
        this.value = value;
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
}
