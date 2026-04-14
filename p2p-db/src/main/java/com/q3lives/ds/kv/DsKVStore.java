package com.q3lives.ds.kv;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.index.master.DsTieredMasterIndex;
import com.q3lives.ds.index.value.DsMiniValueIndex;
import com.q3lives.ds.util.DsDataUtil;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 通用 KV 存储（byte[] -> byte[]），以 indexId 作为稳定句柄。
 *
 * <p>组成：</p>
 * <ul>
 *   <li>value：原始 valueBytes，存放在 {@link DsFixedBucketStore} 的 value bucket 中（变长按 power 分级）。</li>
 *   <li>key：原始 keyBytes，存放在 {@link DsFixedBucketStore} 的 key bucket 中。</li>
 *   <li>index record（32B 定长）：存放 key/value 的长度、hash32 与对应的 keyId/valueId。</li>
 *   <li>masterIndex：{@link DsTieredMasterIndex}，以 keyBytes 为 key 映射到 indexId。</li>
 *   <li>valueIndex：{@link DsMiniValueIndex}，以 valueBytes 为 key 映射到 indexId 列表（分页返回）。</li>
 * </ul>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>{@link #put(byte[], byte[])} 返回 indexId；调用层可保存 indexId，并通过 indexId 直接读写。</li>
 *   <li>key->indexId 的冲突由 {@link DsTieredMasterIndex#promoteOnCollision(byte[], byte[], long)} 驱动升级。</li>
 *   <li>value->indexId 是一对多关系，通过 {@link DsMiniValueIndex.Page} 分页读取。</li>
 * </ul>
 */
public class DsKVStore {
    public static final int INDEX_RECORD_SIZE = 32;

    private final DsFixedBucketStore bucketStore;
    private final DsTieredMasterIndex masterIndex;
    private final DsMiniValueIndex valueIndex;

    /**
     * 创建一个 KVStore 实例。
     *
     * <p>storeName 会作为子目录名，且必须是单段名称（见 {@link DsPathUtil#validateSegment(String, String)}）。</p>
     *
     * @param dirPath 根目录
     * @param storeName 存储实例名
     */
    public DsKVStore(String dirPath, String storeName) throws IOException {
        DsPathUtil.validateSegment(storeName, "storeName");
        File dir = new File(dirPath, storeName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(dir.getAbsolutePath());
        File masterDir = new File(dir, DsFixedBucketStore.INDEPENDENT_SPACE + File.separator + "master");
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        this.masterIndex = new DsTieredMasterIndex(masterDir);
        File valueIndexDir = new File(dir, DsFixedBucketStore.INDEPENDENT_SPACE + File.separator + "value_index");
        this.valueIndex = new DsMiniValueIndex(valueIndexDir);
    }

    /**
     * UTF-8 便捷接口：写入一条 KV 并返回 indexId。
     */
    public long put(String key, String value) throws IOException {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Key and Value cannot be null");
        }
        return put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 写入/更新一条 KV 并返回 indexId。
     *
     * <p>如果 key 已存在，会删除旧的 index record 与旧 value，并同步维护 valueIndex（按 value 反查）。</p>
     */
    public long put(byte[] keyBytes, byte[] valueBytes) throws IOException {
        if (keyBytes == null) {
            throw new IllegalArgumentException("keyBytes cannot be null");
        }
        if (valueBytes == null) {
            valueBytes = new byte[0];
        }
        Long oldIndexId = masterIndex.get(keyBytes);
        long oldKeyId = 0;
        int oldKeyLen = 0;
        long oldValueId = 0;
        int oldValueLen = 0;
        int oldValueHash32 = 0;
        boolean existed = false;
        if (oldIndexId != null) {
            byte[] indexRecord = bucketStore.get("index", oldIndexId, INDEX_RECORD_SIZE);
            ParsedIndex parsed = parseIndex(indexRecord);
            byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
            if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
                masterIndex.promoteOnCollision(keyBytes, storedKey, oldIndexId);
                oldIndexId = masterIndex.get(keyBytes);
                if (oldIndexId != null) {
                    indexRecord = bucketStore.get("index", oldIndexId, INDEX_RECORD_SIZE);
                    parsed = parseIndex(indexRecord);
                    storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
                    if (parsed.keyHash32 == DsDataUtil.hash32(storedKey) && Arrays.equals(storedKey, keyBytes)) {
                        existed = true;
                        oldKeyId = parsed.keyId;
                        oldKeyLen = parsed.keyLen;
                        oldValueId = parsed.valueId;
                        oldValueLen = parsed.valueLen;
                        oldValueHash32 = parsed.valueHash32;
                    } else {
                        oldIndexId = null;
                    }
                } else {
                    oldIndexId = null;
                }
            } else {
                existed = true;
                oldKeyId = parsed.keyId;
                oldKeyLen = parsed.keyLen;
                oldValueId = parsed.valueId;
                oldValueLen = parsed.valueLen;
                oldValueHash32 = parsed.valueHash32;
            }
        }

        long keyId = oldKeyId;
        int keyLen = oldKeyLen;
        int keyHash32 = DsDataUtil.hash32(keyBytes);
        if (keyId == 0) {
            keyId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "key", keyBytes);
            keyLen = keyBytes.length;
        }

        long valueId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "value", valueBytes);
        int valueHash32 = DsDataUtil.hash32(valueBytes);
        byte[] record = buildIndexRecord(keyLen, keyHash32, keyId, valueBytes.length, valueHash32, valueId);
        long indexId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "index", record);
        masterIndex.put(keyBytes, indexId, existed);
        valueIndex.add(valueBytes, indexId);

        if (oldIndexId != null) {
            byte[] oldValueBytes = null;
            if (oldValueLen > 0) {
                oldValueBytes = bucketStore.get("value", oldValueId, oldValueLen);
                if (oldValueHash32 != DsDataUtil.hash32(oldValueBytes) || oldValueBytes.length != oldValueLen) {
                    oldValueBytes = null;
                }
            }
            bucketStore.remove("index", oldIndexId);
            bucketStore.remove("value", oldValueId);
            if (oldValueBytes != null) {
                valueIndex.remove(oldValueBytes, oldIndexId);
            }
        }
        return indexId;
    }

    /**
     * UTF-8 便捷接口：按 key 读取 value。
     */
    public String get(String key) throws IOException {
        if (key == null) return null;
        byte[] v = get(key.getBytes(StandardCharsets.UTF_8));
        if (v == null) {
            return null;
        }
        return new String(v, StandardCharsets.UTF_8);
    }

    /**
     * 按 keyBytes 读取 valueBytes。
     *
     * <p>内部通过 masterIndex 定位 indexId，再读取 index record 并返回 value。</p>
     */
    public byte[] get(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return null;
        }
        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return null;
        }
        return getValueByIndexId(keyBytes, indexId);
    }

    /**
     * 获取 keyBytes 对应的 indexId（如果存在）。
     */
    public Long getIndexId(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return null;
        }
        return masterIndex.get(keyBytes);
    }

    /**
     * {@link #getIndexId(byte[])} 的别名（按你的 API 命名习惯保留）。
     */
    public Long getIndexByKey(byte[] keyBytes) throws IOException {
        return getIndexId(keyBytes);
    }

    /**
     * 通过 valueBytes 获取 indexId 列表第一页（分页对象）。
     */
    public DsMiniValueIndex.Page getIndexByValue(byte[] valueBytes) throws IOException {
        return valueIndex.getPage(valueBytes);
    }

    /**
     * 通过 valueBytes 获取下一页 indexId 列表（使用上一页 Page 作为游标）。
     */
    public DsMiniValueIndex.Page getIndexByValue(byte[] valueBytes, DsMiniValueIndex.Page previous) throws IOException {
        return valueIndex.getPage(valueBytes, previous);
    }

    /**
     * 通过 valueBytes 按指定 index/size 分页读取 indexId 列表。
     */
    public DsMiniValueIndex.Page getIndexByValue(byte[] valueBytes, long index, int size) throws IOException {
        return valueIndex.getPage(valueBytes, index, size);
    }

    /**
     * 通过 valueBytes 获取其中一个 keyBytes（取分页第一页的第一个 indexId）。
     *
     * <p>由于 value->indexId 是一对多关系，该方法返回的是“某一个”匹配项。</p>
     */
    public byte[] getKeyByValue(byte[] valueBytes) throws IOException {
        DsMiniValueIndex.Page p = getIndexByValue(valueBytes);
        if (p.ids.length == 0) {
            return null;
        }
        return getKeyByIndexId(p.ids[0]);
    }

    /**
     * 通过 indexId 直接读取 valueBytes。
     */
    public byte[] getValueByIndexId(long indexId) throws IOException {
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        return bucketStore.get("value", parsed.valueId, parsed.valueLen);
    }

    /**
     * 通过 indexId 直接读取 keyBytes。
     */
    public byte[] getKeyByIndexId(long indexId) throws IOException {
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        return bucketStore.get("key", parsed.keyId, parsed.keyLen);
    }

    /**
     * 通过 indexId 原地更新 value（indexId 不变）。
     *
     * <p>该方法会：</p>
     * <ul>
     *   <li>写入新的 value 并更新 index record</li>
     *   <li>回收旧 valueId</li>
     *   <li>同步维护 valueIndex（从旧 value 移除并加入新 value）</li>
     * </ul>
     */
    public long updateValueByIndexId(long indexId, byte[] valueBytes) throws IOException {
        if (valueBytes == null) {
            valueBytes = new byte[0];
        }
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        byte[] oldValueBytes = null;
        if (parsed.valueLen > 0) {
            oldValueBytes = bucketStore.get("value", parsed.valueId, parsed.valueLen);
        }
        long newValueId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "value", valueBytes);
        int newValueHash32 = DsDataUtil.hash32(valueBytes);
        byte[] record = buildIndexRecord(parsed.keyLen, parsed.keyHash32, parsed.keyId, valueBytes.length, newValueHash32, newValueId);
        bucketStore.overwrite(DsFixedBucketStore.INDEPENDENT_SPACE, "index", indexId, record);
        bucketStore.remove("value", parsed.valueId);
        if (oldValueBytes != null) {
            valueIndex.remove(oldValueBytes, indexId);
        }
        valueIndex.add(valueBytes, indexId);
        return indexId;
    }

    /**
     * UTF-8 便捷接口：删除一条 KV。
     *
     * <p>删除会回收 key/value/index 对应的 bucket id，并同步维护 valueIndex。</p>
     */
    public boolean remove(String key) throws IOException {
        if (key == null) return false;
        return remove(key.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 删除一条 KV。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>masterIndex.get(keyBytes) 定位 indexId</li>
     *   <li>校验 storedKey（不匹配则触发碰撞升级并重试）</li>
     *   <li>masterIndex.remove(keyBytes)</li>
     *   <li>回收 index/value/key 的 bucket id，并从 valueIndex 移除对应关系</li>
     * </ol>
     */
    public boolean remove(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return false;
        }
        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return false;
        }
        byte[] indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
        ParsedIndex parsed = parseIndex(indexRecord);
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
            masterIndex.promoteOnCollision(keyBytes, storedKey, indexId);
            indexId = masterIndex.get(keyBytes);
            if (indexId == null) {
                return false;
            }
            indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
            parsed = parseIndex(indexRecord);
            storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
            if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
                return false;
            }
        }
        if (!masterIndex.remove(keyBytes)) {
            return false;
        }
        byte[] valueBytes = bucketStore.get("value", parsed.valueId, parsed.valueLen);
        bucketStore.remove("index", indexId);
        bucketStore.remove("value", parsed.valueId);
        bucketStore.remove("key", parsed.keyId);
        valueIndex.remove(valueBytes, indexId);
        return true;
    }

    /**
     * 判断是否包含指定的键。
     * @param key 键
     * @return 是否包含
     */
    public boolean containsKey(String key) {
        try {
            return get(key) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取当前存储的键值对数量。
     * @return 数量
     */
    public int size() {
        return masterIndex.size();
    }

    /**
     * 关闭底层索引与 bucket 资源。
     */
    public void close() {
        masterIndex.close();
        try {
            valueIndex.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            bucketStore.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] buildIndexRecord(int keyLen, int keyHash32, long keyId, int valueLen, int valueHash32, long valueId) {
        byte[] b = new byte[INDEX_RECORD_SIZE];
        DsDataUtil.storeInt(b, 0, keyLen);
        DsDataUtil.storeInt(b, 4, keyHash32);
        DsDataUtil.storeLong(b, 8, keyId);
        DsDataUtil.storeInt(b, 16, valueLen);
        DsDataUtil.storeInt(b, 20, valueHash32);
        DsDataUtil.storeLong(b, 24, valueId);
        return b;
    }

    private static ParsedIndex parseIndex(byte[] b) {
        ParsedIndex p = new ParsedIndex();
        p.keyLen = DsDataUtil.loadInt(b, 0);
        p.keyHash32 = DsDataUtil.loadInt(b, 4);
        p.keyId = DsDataUtil.loadLong(b, 8);
        p.valueLen = DsDataUtil.loadInt(b, 16);
        p.valueHash32 = DsDataUtil.loadInt(b, 20);
        p.valueId = DsDataUtil.loadLong(b, 24);
        return p;
    }

    private byte[] getValueByIndexId(byte[] keyBytes, long indexId) throws IOException {
        byte[] indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
        ParsedIndex parsed = parseIndex(indexRecord);
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
            masterIndex.promoteOnCollision(keyBytes, storedKey, indexId);
            Long id2 = masterIndex.get(keyBytes);
            if (id2 == null) {
                return null;
            }
            indexRecord = bucketStore.get("index", id2, INDEX_RECORD_SIZE);
            parsed = parseIndex(indexRecord);
            storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
            if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
                return null;
            }
        }
        byte[] valueBytes = bucketStore.get("value", parsed.valueId, parsed.valueLen);
        if (parsed.valueHash32 != DsDataUtil.hash32(valueBytes)) {
            return null;
        }
        return valueBytes;
    }

    private static final class ParsedIndex {
        int keyLen;
        int keyHash32;
        long keyId;
        int valueLen;
        int valueHash32;
        long valueId;
    }
}
