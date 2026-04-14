package ds.cache;

import java.io.IOException;

/**
 * RedisCacheSystem：同时提供 String 与 Bytes 两类“类 Redis”缓存接口的组合入口。
 *
 * <p>它只是一个装配器：</p>
 * <ul>
 *   <li>{@link #strings()} 返回 {@link RedisStringCache}</li>
 *   <li>{@link #bytes()} 返回 {@link RedisBytesCache}</li>
 * </ul>
 *
 * <p>两类缓存共享相同的 maxEntries 与 defaultTtlMillis 配置，但底层落盘目录分离。</p>
 */
public final class RedisCacheSystem {

    private final RedisStringCache strings;
    private final RedisBytesCache bytes;

    /**
     * 创建缓存系统（默认 defaultTtlMillis=0，表示不设置默认 TTL）。
     */
    public RedisCacheSystem(String rootDir, int maxEntries) throws IOException {
        this(rootDir, maxEntries, 0);
    }

    /**
     * 创建缓存系统。
     *
     * @param rootDir 根目录
     * @param maxEntries 内存 LRU 最大条目数
     * @param defaultTtlMillis 默认 TTL（<=0 表示不过期，除非单次 set 指定）
     */
    public RedisCacheSystem(String rootDir, int maxEntries, long defaultTtlMillis) throws IOException {
        this.strings = new RedisStringCache(rootDir + "/strings", maxEntries, defaultTtlMillis);
        this.bytes = new RedisBytesCache(rootDir + "/bytes", maxEntries, defaultTtlMillis);
    }

    /**
     * 获取 String KV 缓存实例。
     */
    public RedisStringCache strings() {
        return strings;
    }

    /**
     * 获取 Bytes KV 缓存实例。
     */
    public RedisBytesCache bytes() {
        return bytes;
    }
}
