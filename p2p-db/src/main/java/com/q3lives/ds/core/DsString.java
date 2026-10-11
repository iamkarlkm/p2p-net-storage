package com.q3lives.ds.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.q3lives.ds.bucket.DsData;
import com.q3lives.ds.bucket.DsFixedBucketStore;

/**
 * 一个简单的可复用字符串存储类。
 *
 * <p>现在复用 {@link DsData}，具备去重、分级索引和引用计数能力。
 * 因为 DsData 内部已经存储了 valueLen，并且处理了定长和 tombstone 等复杂逻辑，
 * 所以这里不再需要手动写入 4 字节的 len 前缀，直接将字符串 UTF-8 编码存入即可。</p>
 */
public class DsString {

    private static final String STORE_NAME = "string_store";
    private final DsData dataStore;

    /**
     * 构造函数。
     *
     * @param rootDir 根目录
     * @throws java.io.IOException
     */
    public DsString(String rootDir) throws IOException {
        this.dataStore = new DsData(rootDir, STORE_NAME, DsFixedBucketStore.DATA_SPACE);
    }

    /**
     * 写入一个字符串并返回对应的 id（indexId）。
     *
     * <p>底层直接调用 {@link DsData#put(byte[])}。
     * 如果字符串已经存在，DsData 会自动累加其引用计数，并返回同一个 indexId。</p>
     *
     * @param value 待写入的字符串
     * @return 该字符串在 DsData 中的 indexId
     * @throws java.io.IOException
     */
    public long add(String value) throws IOException {
        if (value == null) {
            value = "";
        }
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        return dataStore.put(b);
    }

    /**
     * 读取字符串。
     *
     * <p>直接通过 {@link DsData#getValueByIndexId(long)} 读取原始 byte 数组。
     * 如果记录被删除（引用计数归零），DsData 会在底层抛出异常或根据实现返回 null，
     * 这里捕获或处理并返回对应的字符串。</p>
     *
     * @param id indexId
     * @return 字符串内容，如果不存在或已被彻底删除，返回 null
     * @throws java.io.IOException
     */
    public String get(long id) throws IOException {
        try {
            int ref = dataStore.getRefCountByIndexId(id);
            if (ref <= 0) {
                return null;
            }
            byte[] b = dataStore.getValueByIndexId(id);
            if (b == null) {
                return null;
            }
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 如果底层块已被删除，可能会抛出异常，此时视为找不到
            return null;
        }
    }

    /**
     * 获取当前 id 的引用计数。
     *
     * @param id indexId
     * @return 引用计数
     * @throws java.io.IOException
     */
    public int refCount(long id) throws IOException {
        return dataStore.getRefCountByIndexId(id);
    }

    /**
     * 增加一次引用计数。
     * <p>底层直接调用 {@link DsData#retain(long)}，避免读取完整字符串内容。</p>
     *
     * @param id indexId
     * @throws java.io.IOException
     */
    public void retain(long id) throws IOException {
        boolean ok = dataStore.retain(id);
        if (!ok) {
            throw new IllegalStateException("String " + id + " does not exist or has been deleted");
        }
    }

    /**
     * 减少一次引用计数。
     *
     * <p>等价于调用 {@link #remove(long)}。</p>
     * @param id indexId
     * @throws java.io.IOException
     */
    public void release(long id) throws IOException {
        remove(id);
    }

    /**
     * 更新字符串：减少旧 id 的引用计数，再写入新值并返回新 id。
     *
     * @param id 旧的 indexId
     * @param value 新字符串
     * @return 新字符串的 indexId
     * @throws java.io.IOException 
     */
    public long update(long id, String value) throws IOException {
        if (value == null) {
            value = "";
        }
        byte[] newBytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] oldBytes = dataStore.getValueByIndexId(id);
        if (oldBytes != null && Arrays.equals(oldBytes, newBytes)) {
            return id;
        }
        remove(id);
        return dataStore.put(newBytes);
    }

    /**
     * 删除字符串（减少引用计数）。
     *
     * <p>底层调用 {@link DsData#remove(long)}，只有当引用计数减到 0 时，
     * 底层才会真正回收 bucket 空间和索引。</p>
     *
     * @param id indexId
     * @throws java.io.IOException
     */
    public void remove(long id) throws IOException {
        dataStore.remove(id);
    }

    /**
     * 判断字符串是否已在存储中存在（不改变引用计数，不产生写 IO）。
     *
     * @param value 待判断的字符串
     * @return true 表示已有对应条目（refCount >= 1）；false 表示不存在
     * @throws java.io.IOException
     */
    public boolean contains(String value) throws IOException {
        if (value == null) {
            value = "";
        }
        Long indexId = dataStore.getIndexId(value.getBytes(StandardCharsets.UTF_8));
        if (indexId == null) {
            return false;
        }
        return dataStore.getRefCountByIndexId(indexId) > 0;
    }

    /**
     * 查询字符串对应的 indexId（不改变引用计数，不产生写 IO）。
     *
     * <p>如果字符串不存在（或已被删除到 refCount=0），返回 {@code null}。
     * 调用方可通过返回值与 {@code add(String)} 返回值直接比较，判断是否复用了同一条目。</p>
     *
     * @param key 待查询的字符串
     * @return 对应的 indexId；不存在或已被彻底删除时返回 {@code null}
     * @throws java.io.IOException
     */
    public Long getIndexId(String key) throws IOException {
        return getIndexId(key, false);
    }

    /**
     * 查询字符串对应的 indexId；当不存在时可选择自动 {@link #add(String) 写入}。
     *
     * <p>当 {@code createIfAbsent=true} 且字符串不存在（或已被删除到 refCount=0）时，
     * 先写入新条目并返回新 id，效果等价于 {@link #add(String)}；</p>
     * <p>当 {@code createIfAbsent=false} 行为同 {@link #getIndexId(String)}，纯读不产生写 IO。</p>
     *
     * @param key             待查询/写入的字符串
     * @param createIfAbsent  若为 true，key 不存在时自动 add 后再返回 id；若为 false，纯读 miss 返回 null
     * @return 对应的 indexId；createIfAbsent=false 且缺失返回 null；createIfAbsent=true 始终返回非 null 的 indexId
     * @throws java.io.IOException
     */
    public Long getIndexId(String key, boolean createIfAbsent) throws IOException {
        if (key == null) {
            key = "";
        }
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        Long indexId = dataStore.getIndexId(b);
        if (indexId != null && dataStore.getRefCountByIndexId(indexId) > 0) {
            return indexId;
        }
        if (!createIfAbsent) {
            return null;
        }
        return dataStore.put(b);
    }

    /**
     * 关闭底层 {@link DsData} 资源。
     */
    public void close() {
        dataStore.close();
    }
}
