package com.q3lives.ds.cache;

import com.q3lives.ds.legacy.db.DbString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * 一个“类 Redis”的 String KV（String key -> String value），带 TTL 与 LRU 内存缓存。
 *
 * <p>存储层：</p>
 * <ul>
 *   <li>底层持久化使用 {@link DbString}。</li>
 *   <li>为了存储 TTL/删除标记，value 会被编码成带版本头的 record（字符串形式）。</li>
 * </ul>
 *
 * <p>缓存层：</p>
 * <ul>
 *   <li>内存使用 {@link LruCache}；命中时直接返回，过期则触发 del。</li>
 * </ul>
 *
 * <p>并发：</p>
 * <ul>
 *   <li>条带锁（256）按 key hash 分段，降低并发 set/get/del 的冲突概率。</li>
 * </ul>
 */
public final class RedisStringCache {

    private static final int VERSION_1 = 1;
    private static final int FLAG_DELETED = 1;

    private final DbString store;
    private final LruCache<String, Entry> memory;
    private final long defaultTtlMillis;
    private final Object[] locks = new Object[256];

    /**
     * 创建 String 缓存（默认 defaultTtlMillis=0）。
     *
     * @param rootDir 落盘目录
     * @param maxEntries 内存 LRU 最大条目数
     */
    public RedisStringCache(String rootDir, int maxEntries) throws IOException {
        this(rootDir, maxEntries, 0);
    }

    /**
     * 创建 String 缓存。
     *
     * @param rootDir 落盘目录
     * @param maxEntries 内存 LRU 最大条目数
     * @param defaultTtlMillis 默认 TTL（<=0 表示不过期）
     */
    public RedisStringCache(String rootDir, int maxEntries, long defaultTtlMillis) throws IOException {
        this.store = new DbString(rootDir);
        this.memory = new LruCache<>(maxEntries);
        this.defaultTtlMillis = Math.max(0, defaultTtlMillis);
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    private Object getLock(String key) {
        return locks[Math.abs(key.hashCode()) % locks.length];
    }

    /**
     * 设置 key 的 value（使用默认 TTL）。
     */
    public void set(String key, String value) throws Exception {
        set(key, value, defaultTtlMillis);
    }

    /**
     * 设置 key 的 value，并指定 TTL。
     *
     * @param ttlMillis TTL（<=0 表示不过期）
     */
    public void set(String key, String value, long ttlMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long expireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0;
        String record = encodeRecord(0, expireAt, value);
        store.update(key, record);
        long memExpireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0;
        memory.put(key, new Entry(value, memExpireAt, false));
    }

    /**
     * 获取 key 的 value。
     *
     * <p>会优先命中内存缓存；如已过期则执行 del 并返回 null。</p>
     */
    public String get(String key) throws Exception {
        Objects.requireNonNull(key, "key");

        Entry cached = memory.get(key);
        if (cached != null) {
            if (cached.deleted) {
                return null;
            }
            if (isExpired(cached.expireAt)) {
                del(key);
                return null;
            }
            return cached.value;
        }

        String record = store.get(key);
        if (record == null) {
            return null;
        }

        Decoded decoded = decodeRecord(record);
        if (decoded == null) {
            memory.put(key, new Entry(record, 0, false));
            return record;
        }
        if (decoded.deleted) {
            memory.put(key, new Entry("", 0, true));
            return null;
        }
        if (isExpired(decoded.expireAt)) {
            del(key);
            return null;
        }

        memory.put(key, new Entry(decoded.value, decoded.expireAt, false));
        return decoded.value;
    }

    /**
     * 判断 key 是否存在（等价于 get(key)!=null）。
     */
    public boolean exists(String key) throws Exception {
        return get(key) != null;
    }

    /**
     * 删除 key（写入 deleted 标记并清理内存缓存）。
     */
    public void del(String key) throws Exception {
        Objects.requireNonNull(key, "key");
        String record = encodeRecord(FLAG_DELETED, 0, "");
        store.update(key, record);
        memory.remove(key);
    }

    /**
     * 设定 key 的 TTL。
     *
     * <p>返回 false 表示 key 不存在；ttlMillis<=0 等价于 del。</p>
     */
    public boolean expire(String key, long ttlMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        if (ttlMillis <= 0) {
            del(key);
            return true;
        }
        String current = get(key);
        if (current == null) {
            return false;
        }
        set(key, current, ttlMillis);
        return true;
    }

    /**
     * 获取 key 的剩余 TTL（毫秒）。
     *
     * <p>兼容 Redis 语义：</p>
     * <ul>
     *   <li>-2：key 不存在或已过期</li>
     *   <li>-1：key 存在但没有设置过期时间</li>
     *   <li>其他：剩余毫秒数</li>
     * </ul>
     */
    public long ttlMillis(String key) throws Exception {
        Objects.requireNonNull(key, "key");
        Entry cached = memory.get(key);
        if (cached != null) {
            if (cached.deleted) {
                return -2;
            }
            if (cached.expireAt <= 0) {
                return -1;
            }
            long left = cached.expireAt - System.currentTimeMillis();
            if (left <= 0) {
                del(key);
                return -2;
            }
            return left;
        }

        String record = store.get(key);
        if (record == null) {
            return -2;
        }
        Decoded decoded = decodeRecord(record);
        if (decoded == null || decoded.deleted) {
            return decoded == null ? -1 : -2;
        }
        if (decoded.expireAt <= 0) {
            return -1;
        }
        long left = decoded.expireAt - System.currentTimeMillis();
        if (left <= 0) {
            del(key);
            return -2;
        }
        return left;
    }

    /**
     * 将 key 对应的整数值加 1（不存在视为 0）。
     */
    public long incr(String key) throws Exception {
        return incrBy(key, 1);
    }

    /**
     * 将 key 对应的整数值加 delta（不存在视为 0）。
     *
     * <p>如果 value 不是整数，会抛出 IllegalStateException（模拟 Redis ERR）。</p>
     */
    public long incrBy(String key, long delta) throws Exception {
        Objects.requireNonNull(key, "key");
        synchronized (getLock(key)) {
            String current = get(key);
            long val = 0;
            if (current != null) {
                try {
                    val = Long.parseLong(current);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("ERR value is not an integer or out of range");
                }
            }
            val += delta;
            // Retain TTL if exists, otherwise use default
            long ttl = ttlMillis(key);
            if (ttl == -2) {
                set(key, String.valueOf(val));
            } else if (ttl == -1) {
                set(key, String.valueOf(val), 0);
            } else {
                set(key, String.valueOf(val), ttl);
            }
            return val;
        }
    }

    /**
     * 将 key 对应的整数值减 1（不存在视为 0）。
     */
    public long decr(String key) throws Exception {
        return incrBy(key, -1);
    }

    /**
     * 将 key 对应的整数值减 delta（不存在视为 0）。
     */
    public long decrBy(String key, long delta) throws Exception {
        return incrBy(key, -delta);
    }

    /**
     * 批量获取多个 key（保持入参顺序）。
     */
    public java.util.Map<String, String> mget(String... keys) throws Exception {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, get(key));
        }
        return result;
    }

    /**
     * 批量设置多个 key（使用默认 TTL）。
     */
    public void mset(java.util.Map<String, String> keyValues) throws Exception {
        mset(keyValues, defaultTtlMillis);
    }

    /**
     * 批量设置多个 key，并指定 TTL。
     */
    public void mset(java.util.Map<String, String> keyValues, long ttlMillis) throws Exception {
        if (keyValues == null) return;
        for (java.util.Map.Entry<String, String> entry : keyValues.entrySet()) {
            set(entry.getKey(), entry.getValue(), ttlMillis);
        }
    }

    /**
     * 尝试获取一个基于 key 的互斥锁（SET NX PX 的简化版）。
     *
     * @param token 锁持有者标识
     * @param ttlMillis 锁超时时间（必须 >0）
     */
    public boolean tryLock(String key, String token, long ttlMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        synchronized (getLock(key)) {
            String current = get(key);
            if (current != null) {
                return false;
            }
            set(key, token, ttlMillis);
            return true;
        }
    }

    /**
     * 释放锁：仅当当前 value 与 token 相同才删除。
     */
    public boolean unlock(String key, String token) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
        synchronized (getLock(key)) {
            String current = get(key);
            if (current == null) {
                return false;
            }
            if (!token.equals(current)) {
                return false;
            }
            del(key);
            return true;
        }
    }

    /**
     * 续期锁：仅当当前 value 与 token 相同才更新 TTL。
     */
    public boolean renewLock(String key, String token, long ttlMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(token, "token");
        if (ttlMillis <= 0) {
            return false;
        }
        synchronized (getLock(key)) {
            String current = get(key);
            if (current == null) {
                return false;
            }
            if (!token.equals(current)) {
                return false;
            }
            return expire(key, ttlMillis);
        }
    }

    private static boolean isExpired(long expireAt) {
        return expireAt > 0 && System.currentTimeMillis() > expireAt;
    }

    private static String encodeRecord(int flags, long expireAt, String value) {
        byte[] payload = value.getBytes(StandardCharsets.UTF_8);
        String b64 = Base64.getEncoder().encodeToString(payload);
        return VERSION_1 + "|" + flags + "|" + expireAt + "|" + b64;
    }

    private static Decoded decodeRecord(String record) {
        String[] parts = record.split("\\|", 4);
        if (parts.length != 4) {
            return null;
        }
        int version;
        int flags;
        long expireAt;
        try {
            version = Integer.parseInt(parts[0]);
            flags = Integer.parseInt(parts[1]);
            expireAt = Long.parseLong(parts[2]);
        } catch (Exception e) {
            return null;
        }
        if (version != VERSION_1) {
            return null;
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(parts[3]);
        } catch (Exception e) {
            return null;
        }
        boolean deleted = (flags & FLAG_DELETED) != 0;
        String value = new String(payload, StandardCharsets.UTF_8);
        return new Decoded(value, expireAt, deleted);
    }

    private static final class Entry {
        final String value;
        final long expireAt;
        final boolean deleted;

        Entry(String value, long expireAt, boolean deleted) {
            this.value = value;
            this.expireAt = expireAt;
            this.deleted = deleted;
        }
    }

    private static final class Decoded {
        final String value;
        final long expireAt;
        final boolean deleted;

        Decoded(String value, long expireAt, boolean deleted) {
            this.value = value;
            this.expireAt = expireAt;
            this.deleted = deleted;
        }
    }
}
