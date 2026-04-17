package com.q3lives.ds.cache;

import com.q3lives.ds.legacy.db.DbBytes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * 一个“类 Redis”的 bytes KV（String key -> byte[] value），带 TTL 与 LRU 内存缓存。
 *
 * <p>存储层：</p>
 * <ul>
 *   <li>底层持久化使用 {@link DbBytes}（key/value 都以 byte[] 形式落盘）。</li>
 *   <li>内存层使用 {@link LruCache} 做最近使用缓存，减少频繁 IO。</li>
 * </ul>
 *
 * <p>记录格式（持久化 valueBytes 的编码）：</p>
 * <ul>
 *   <li>固定头部 {@link #HEADER_SIZE}：版本、flag（删除标记）、expireAt 等</li>
 *   <li>随后是原始 value bytes</li>
 * </ul>
 *
 * <p>并发：</p>
 * <ul>
 *   <li>使用 256 个条带锁（按 key.hashCode() 取模）降低锁争用。</li>
 * </ul>
 */
public final class RedisBytesCache {

    private static final byte VERSION_1 = 1;
    private static final byte FLAG_DELETED = 1;
    private static final int HEADER_SIZE = 16;

    private final DbBytes store;
    private final LruCache<String, Entry> memory;
    private final long defaultTtlMillis;
    private final Object[] locks = new Object[256];

    /**
     * 创建 Bytes 缓存（默认 defaultTtlMillis=0）。
     *
     * @param rootDir 落盘目录
     * @param maxEntries 内存 LRU 最大条目数
     */
    public RedisBytesCache(String rootDir, int maxEntries) throws IOException {
        this(rootDir, maxEntries, 0);
    }

    /**
     * 创建 Bytes 缓存。
     *
     * @param rootDir 落盘目录
     * @param maxEntries 内存 LRU 最大条目数
     * @param defaultTtlMillis 默认 TTL（<=0 表示不过期）
     */
    public RedisBytesCache(String rootDir, int maxEntries, long defaultTtlMillis) throws IOException {
        this.store = new DbBytes(rootDir);
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
    public void set(String key, byte[] value) throws Exception {
        set(key, value, defaultTtlMillis);
    }

    /**
     * 设置 key 的 value，并指定 TTL。
     *
     * @param ttlMillis TTL（<=0 表示不过期）
     */
    public void set(String key, byte[] value, long ttlMillis) throws Exception {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long expireAt = ttlMillis > 0 ? System.currentTimeMillis() + ttlMillis : 0;
        byte[] record = encodeRecord((byte) 0, expireAt, value);
        store.update(keyBytes(key), record);
        memory.put(key, new Entry(value, expireAt, false));
    }

    /**
     * 获取 key 的 value。
     *
     * <p>会优先命中内存缓存；如已过期则执行 del 并返回 null。返回值会复制一份，避免调用方修改缓存内容。</p>
     */
    public byte[] get(String key) throws Exception {
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
            return Arrays.copyOf(cached.value, cached.value.length);
        }

        byte[] record = store.get(keyBytes(key));
        if (record == null) {
            return null;
        }

        Decoded decoded = decodeRecord(record);
        if (decoded == null || decoded.deleted) {
            memory.put(key, new Entry(new byte[0], 0, true));
            return null;
        }
        if (isExpired(decoded.expireAt)) {
            del(key);
            return null;
        }

        memory.put(key, new Entry(decoded.value, decoded.expireAt, false));
        return Arrays.copyOf(decoded.value, decoded.value.length);
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
        byte[] record = encodeRecord(FLAG_DELETED, 0, new byte[0]);
        store.update(keyBytes(key), record);
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

        byte[] current = get(key);
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

        byte[] record = store.get(keyBytes(key));
        if (record == null) {
            return -2;
        }
        Decoded decoded = decodeRecord(record);
        if (decoded == null || decoded.deleted) {
            return -2;
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
     * <p>valueBytes 按 UTF-8 解码为整数；非整数会抛出 IllegalStateException（模拟 Redis ERR）。</p>
     */
    public long incrBy(String key, long delta) throws Exception {
        Objects.requireNonNull(key, "key");
        synchronized (getLock(key)) {
            byte[] current = get(key);
            long val = 0;
            if (current != null) {
                try {
                    String strVal = new String(current, StandardCharsets.UTF_8);
                    val = Long.parseLong(strVal);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("ERR value is not an integer or out of range");
                }
            }
            val += delta;
            byte[] newVal = String.valueOf(val).getBytes(StandardCharsets.UTF_8);
            
            long ttl = ttlMillis(key);
            if (ttl == -2) {
                set(key, newVal);
            } else if (ttl == -1) {
                set(key, newVal, 0);
            } else {
                set(key, newVal, ttl);
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
    public java.util.Map<String, byte[]> mget(String... keys) throws Exception {
        java.util.Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, get(key));
        }
        return result;
    }

    /**
     * 批量设置多个 key（使用默认 TTL）。
     */
    public void mset(java.util.Map<String, byte[]> keyValues) throws Exception {
        mset(keyValues, defaultTtlMillis);
    }

    /**
     * 批量设置多个 key，并指定 TTL。
     */
    public void mset(java.util.Map<String, byte[]> keyValues, long ttlMillis) throws Exception {
        if (keyValues == null) return;
        for (java.util.Map.Entry<String, byte[]> entry : keyValues.entrySet()) {
            set(entry.getKey(), entry.getValue(), ttlMillis);
        }
    }

    private static boolean isExpired(long expireAt) {
        return expireAt > 0 && System.currentTimeMillis() > expireAt;
    }

    private static byte[] keyBytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encodeRecord(int flags, long expireAt, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payload.length);
        buf.put(VERSION_1);
        buf.put((byte) flags);
        buf.putShort((short) 0);
        buf.putLong(expireAt);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    private static Decoded decodeRecord(byte[] record) {
        if (record.length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(record);
        byte version = buf.get();
        if (version != VERSION_1) {
            return null;
        }
        byte flags = buf.get();
        buf.getShort();
        long expireAt = buf.getLong();
        int len = buf.getInt();
        if (len < 0 || HEADER_SIZE + len > record.length) {
            return null;
        }
        byte[] payload = new byte[len];
        buf.get(payload);
        boolean deleted = (flags & FLAG_DELETED) != 0;
        return new Decoded(payload, expireAt, deleted);
    }

    private static final class Entry {
        final byte[] value;
        final long expireAt;
        final boolean deleted;

        Entry(byte[] value, long expireAt, boolean deleted) {
            this.value = value;
            this.expireAt = expireAt;
            this.deleted = deleted;
        }
    }

    private static final class Decoded {
        final byte[] value;
        final long expireAt;
        final boolean deleted;

        Decoded(byte[] value, long expireAt, boolean deleted) {
            this.value = value;
            this.expireAt = expireAt;
            this.deleted = deleted;
        }
    }
}
