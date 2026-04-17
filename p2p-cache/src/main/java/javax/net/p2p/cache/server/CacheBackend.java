package javax.net.p2p.cache.server;

import ds.cache.RedisCacheSystem;
import ds.cache.RedisBytesCache;
import ds.cache.RedisStringCache;
import java.io.IOException;

/**
 * p2p-cache 服务端后端：管理持久化缓存实例（RedisCacheSystem）。
 *
 * <p>通过系统属性读取 rootDir/maxEntries/defaultTtlMillis，并以单例方式延迟初始化。</p>
 */
public final class CacheBackend {

    private static volatile RedisCacheSystem INSTANCE;

    private CacheBackend() {
    }

    public static RedisCacheSystem get() throws IOException {
        RedisCacheSystem local = INSTANCE;
        if (local != null) {
            return local;
        }
        synchronized (CacheBackend.class) {
            local = INSTANCE;
            if (local != null) {
                return local;
            }
            String rootDir = System.getProperty("p2p.cache.rootDir", "data/p2p-cache");
            int maxEntries = Integer.parseInt(System.getProperty("p2p.cache.maxEntries", "100000"));
            long defaultTtlMillis = Long.parseLong(System.getProperty("p2p.cache.defaultTtlMillis", "0"));
            local = new RedisCacheSystem(rootDir, maxEntries, defaultTtlMillis);
            INSTANCE = local;
            return local;
        }
    }

    public static RedisStringCache strings() throws IOException {
        return get().strings();
    }

    public static RedisBytesCache bytes() throws IOException {
        return get().bytes();
    }
}
