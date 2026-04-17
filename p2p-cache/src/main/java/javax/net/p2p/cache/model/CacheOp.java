package javax.net.p2p.cache.model;

/**
 * 缓存 KV 支持的操作类型（String/Bytes 共用）。
 */
public enum CacheOp {
    GET,
    SET,
    DEL,
    EXISTS,
    EXPIRE,
    TTL,
    MGET,
    MSET,
    INCRBY,
    DECRBY
}
