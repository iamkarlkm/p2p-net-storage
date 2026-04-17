package javax.net.p2p.cache.model;

/**
 * bytes KV 的单条键值对（用于批量 mget/mset 场景）。
 */
public class CacheBytesEntry {
    private String key;
    private byte[] value;

    public CacheBytesEntry() {
    }

    public CacheBytesEntry(String key, byte[] value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }
}
