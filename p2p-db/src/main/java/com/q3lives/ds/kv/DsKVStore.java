package com.q3lives.ds.kv;

import java.io.File;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
    static final int MAX_VALUE_HASH32_BYTES_DEFAULT = 1 << DsFixedBucketStore.MAX_POWER;
    static volatile int maxValueHash32Bytes = MAX_VALUE_HASH32_BYTES_DEFAULT;
    static final int MAX_VALUE_CHUNK_BYTES_DEFAULT = 1 << DsFixedBucketStore.MAX_POWER;
    static volatile int maxValueChunkBytes = MAX_VALUE_CHUNK_BYTES_DEFAULT;
    private static final int VALUE_CHUNK_SEGMENT_BYTES = 64 * 1024;
    private static final int VALUE_CHUNK_SEGMENT_CAP = (VALUE_CHUNK_SEGMENT_BYTES - 8) / 8;
    private static final String VALUE_CHUNK_TYPE = "value_chunks";
    private static final int ZERO_MAP_BLOCK_BYTES = 4 * 1024 * 1024;
    private static final Object ZERO_MAP_LOCK = new Object();
    private static volatile MappedByteBuffer ZERO_RO_MAP;
    private final Object chunkListLock = new Object();

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
        File masterDir = new File(dir, DsFixedBucketStore.DATA_SPACE + File.separator + "master");
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        this.masterIndex = new DsTieredMasterIndex(masterDir);
        File valueIndexDir = new File(dir, DsFixedBucketStore.DATA_SPACE + File.separator + "value_index");
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
        ExistingEntry old = findExisting(keyBytes);
        Long oldIndexId = old == null ? null : old.indexId;
        long oldKeyId = old == null ? 0 : old.keyId;
        int oldKeyLen = old == null ? 0 : old.keyLen;
        long oldValueId = old == null ? 0 : old.valueId;
        int oldValueLen = old == null ? 0 : old.valueLen;
        int oldValueHash32 = old == null ? 0 : old.valueHash32;
        boolean existed = old != null;

        long keyId = oldKeyId;
        int keyLen = oldKeyLen;
        int keyHash32 = DsDataUtil.hash32(keyBytes);
        if (keyId == 0) {
            keyId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "key", keyBytes);
            keyLen = keyBytes.length;
        }

        ValueRef ref;
        if (isChunkedValue(valueBytes.length)) {
            ref = storeChunkedValueBytes(valueBytes);
        } else {
            long valueId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "value", valueBytes);
            int valueHash32 = valueHash32(valueBytes);
            ref = new ValueRef(valueBytes.length, valueHash32, valueId);
        }
        byte[] record = buildIndexRecord(keyLen, keyHash32, keyId, ref.valueLen, ref.valueHash32, ref.valueId);
        long indexId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "index", record);
        masterIndex.put(keyBytes, indexId, existed);
        valueIndex.add(valueIndexKey(valueBytes), indexId);

        if (oldIndexId != null) {
            byte[] oldValueKey = null;
            if (oldValueLen > 0) {
                oldValueKey = readValueIndexKey(oldValueId, oldValueLen);
            }
            bucketStore.remove("index", oldIndexId);
            deleteValue(oldValueLen, oldValueId);
            if (oldValueKey != null) {
                if (oldValueHash32 == 0 || oldValueHash32 == DsDataUtil.hash32(oldValueKey, 0, oldValueKey.length)) {
                    valueIndex.remove(oldValueKey, oldIndexId);
                }
            }
        }
        return indexId;
    }

    /**
     * 流式写入/更新一条 KV，并返回 indexId。
     *
     * <p>该接口会把 valueHash32 存为 0，表示不做 value 校验。</p>
     * <p>valueIndex 的索引键使用 value 的前 N 字节（N=128MiB 或测试阈值），以避免把整段 value 加载进内存。</p>
     */
    public long putStream(byte[] keyBytes, InputStream valueStream, int valueLen) throws IOException {
        if (keyBytes == null) {
            throw new IllegalArgumentException("keyBytes cannot be null");
        }
        if (valueStream == null) {
            throw new IllegalArgumentException("valueStream cannot be null");
        }
        if (valueLen < 0) {
            throw new IllegalArgumentException("valueLen must be >= 0");
        }

        ExistingEntry old = findExisting(keyBytes);
        Long oldIndexId = old == null ? null : old.indexId;
        long oldKeyId = old == null ? 0 : old.keyId;
        int oldKeyLen = old == null ? 0 : old.keyLen;
        long oldValueId = old == null ? 0 : old.valueId;
        int oldValueLen = old == null ? 0 : old.valueLen;
        int oldValueHash32 = old == null ? 0 : old.valueHash32;
        boolean existed = old != null;

        long keyId = oldKeyId;
        int keyLen = oldKeyLen;
        int keyHash32 = DsDataUtil.hash32(keyBytes);
        if (keyId == 0) {
            keyId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "key", keyBytes);
            keyLen = keyBytes.length;
        }

        int max = effectiveMaxValueHash32Bytes();
        int headLen = Math.min(valueLen, max);
        byte[] head = new byte[headLen];
        readFully(valueStream, head, 0, headLen);
        int valueHash32 = 0;

        InputStream combined = new SequenceInputStream(new ByteArrayInputStream(head), valueStream);
        long valueId;
        if (isChunkedValue(valueLen)) {
            ValueRef ref = storeChunkedValueStream(combined, valueLen, head);
            valueId = ref.valueId;
        } else {
            valueId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "value", combined, valueLen);
        }
        byte[] record = buildIndexRecord(keyLen, keyHash32, keyId, valueLen, valueHash32, valueId);
        long indexId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "index", record);
        masterIndex.put(keyBytes, indexId, existed);
        valueIndex.add(head, indexId);

        if (oldIndexId != null) {
            byte[] oldValueKey = null;
            if (oldValueLen > 0) {
                oldValueKey = readValueIndexKey(oldValueId, oldValueLen);
            }
            bucketStore.remove("index", oldIndexId);
            deleteValue(oldValueLen, oldValueId);
            if (oldValueKey != null) {
                if (oldValueHash32 == 0 || oldValueHash32 == DsDataUtil.hash32(oldValueKey, 0, oldValueKey.length)) {
                    valueIndex.remove(oldValueKey, oldIndexId);
                }
            }
        }
        return indexId;
    }

    public long updateValuePartialByIndexId(long indexId, int offset, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            return indexId;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        int newLen = Math.max(parsed.valueLen, offset + data.length);
        byte[] oldValueKey = readValueIndexKey(parsed.valueId, parsed.valueLen);

        long valueId = parsed.valueId;
        int valueLen = parsed.valueLen;

        if (!isChunkedValue(parsed.valueLen) && !isChunkedValue(newLen)) {
            if (newLen > parsed.valueLen) {
                long newValueId = bucketStore.getNewId(DsFixedBucketStore.DATA_SPACE, "value", newLen);
                if (parsed.valueLen > 0) {
                    try (InputStream in = bucketStore.openInputStream("value", parsed.valueId, parsed.valueLen)) {
                        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", newValueId, in, parsed.valueLen);
                    }
                }
                if (offset > parsed.valueLen) {
                    zeroFillSingleValue(newValueId, parsed.valueLen, offset - parsed.valueLen);
                }
                bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", newValueId, offset, data);
                bucketStore.remove("value", parsed.valueId);
                valueId = newValueId;
                valueLen = newLen;
            } else {
                bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", parsed.valueId, offset, data);
            }
        } else if (!isChunkedValue(parsed.valueLen) && isChunkedValue(newLen)) {
            long headSegId = allocateChunkList();
            if (parsed.valueLen > 0) {
                copySingleToChunked(parsed.valueId, parsed.valueLen, headSegId);
            }
            writeChunkedBytes(headSegId, newLen, offset, data);
            bucketStore.remove("value", parsed.valueId);
            valueId = headSegId;
            valueLen = newLen;
        } else {
            long headSegId = parsed.valueId;
            int required = requiredChunks(newLen);
            writeChunkedBytes(headSegId, newLen, offset, data);
            valueId = headSegId;
            valueLen = newLen;
        }
        int newHash32 = 0;
        byte[] record = buildIndexRecord(parsed.keyLen, parsed.keyHash32, parsed.keyId, valueLen, newHash32, valueId);
        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "index", indexId, record);

        valueIndex.remove(oldValueKey, indexId);
        byte[] newValueKey = readValueIndexKey(valueId, valueLen);
        valueIndex.add(newValueKey, indexId);
        return indexId;
    }

    public long updateValuePartialByIndexIdStream(long indexId, int offset, InputStream in, int length) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("in cannot be null");
        }
        if (length <= 0) {
            return indexId;
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        int newLen = Math.max(parsed.valueLen, offset + length);
        byte[] oldValueKey = readValueIndexKey(parsed.valueId, parsed.valueLen);

        long valueId = parsed.valueId;
        int valueLen = parsed.valueLen;

        if (!isChunkedValue(parsed.valueLen) && !isChunkedValue(newLen)) {
            if (newLen > parsed.valueLen) {
                long newValueId = bucketStore.getNewId(DsFixedBucketStore.DATA_SPACE, "value", newLen);
                if (parsed.valueLen > 0) {
                    try (InputStream in2 = bucketStore.openInputStream("value", parsed.valueId, parsed.valueLen)) {
                        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", newValueId, in2, parsed.valueLen);
                    }
                }
                if (offset > parsed.valueLen) {
                    zeroFillSingleValue(newValueId, parsed.valueLen, offset - parsed.valueLen);
                }
                bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", newValueId, offset, in, length);
                bucketStore.remove("value", parsed.valueId);
                valueId = newValueId;
                valueLen = newLen;
            } else {
                bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", parsed.valueId, offset, in, length);
            }
        } else if (!isChunkedValue(parsed.valueLen) && isChunkedValue(newLen)) {
            long headSegId = allocateChunkList();
            if (parsed.valueLen > 0) {
                copySingleToChunked(parsed.valueId, parsed.valueLen, headSegId);
            }
            writeChunkedStream(headSegId, newLen, offset, in, length);
            bucketStore.remove("value", parsed.valueId);
            valueId = headSegId;
            valueLen = newLen;
        } else {
            long headSegId = parsed.valueId;
            int required = requiredChunks(newLen);
            writeChunkedStream(headSegId, newLen, offset, in, length);
            valueId = headSegId;
            valueLen = newLen;
        }
        byte[] record = buildIndexRecord(parsed.keyLen, parsed.keyHash32, parsed.keyId, valueLen, 0, valueId);
        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "index", indexId, record);

        valueIndex.remove(oldValueKey, indexId);
        byte[] newValueKey = readValueIndexKey(valueId, valueLen);
        valueIndex.add(newValueKey, indexId);
        return indexId;
    }

    public ValueRandomAccess openValueRandomAccessByIndexId(long indexId) throws IOException {
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        if (isChunkedValue(parsed.valueLen)) {
            throw new IllegalArgumentException("random access requires non-chunked value");
        }
        DsFixedBucketStore.RecordRandomAccess ra = bucketStore.openRandomAccess("value", parsed.valueId);
        return new ValueRandomAccess(indexId, parsed, ra);
    }

    public ValueMappedAccess openValueMappedAccessByIndexId(long indexId) throws IOException {
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        return new ValueMappedAccess(indexId, parsed);
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
     * 流式读取 value。
     *
     * <p>当 valueHash32 为 0 时不做校验。</p>
     */
    public InputStream getStream(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return null;
        }
        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return null;
        }
        return getValueStreamByIndexId(keyBytes, indexId);
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
        if (parsed.valueLen <= 0) {
            return new byte[0];
        }
        if (!isChunkedValue(parsed.valueLen)) {
            return bucketStore.get("value", parsed.valueId, parsed.valueLen);
        }
        return readChunkedValueBytes(parsed.valueId, parsed.valueLen);
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
        byte[] oldValueKey = null;
        if (parsed.valueLen > 0) {
            oldValueKey = readValueIndexKey(parsed.valueId, parsed.valueLen);
        }
        ValueRef ref;
        if (isChunkedValue(valueBytes.length)) {
            ref = storeChunkedValueBytes(valueBytes);
        } else {
            long newValueId = bucketStore.put(DsFixedBucketStore.DATA_SPACE, "value", valueBytes);
            int newValueHash32 = valueHash32(valueBytes);
            ref = new ValueRef(valueBytes.length, newValueHash32, newValueId);
        }
        byte[] record = buildIndexRecord(parsed.keyLen, parsed.keyHash32, parsed.keyId, ref.valueLen, ref.valueHash32, ref.valueId);
        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "index", indexId, record);
        deleteValue(parsed.valueLen, parsed.valueId);
        if (oldValueKey != null) {
            if (parsed.valueHash32 == 0 || parsed.valueHash32 == DsDataUtil.hash32(oldValueKey, 0, oldValueKey.length)) {
                valueIndex.remove(oldValueKey, indexId);
            }
        }
        valueIndex.add(valueIndexKey(valueBytes), indexId);
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
        byte[] valueKey = null;
        if (parsed.valueLen > 0) {
            valueKey = readValueIndexKey(parsed.valueId, parsed.valueLen);
        }
        bucketStore.remove("index", indexId);
        deleteValue(parsed.valueLen, parsed.valueId);
        bucketStore.remove("key", parsed.keyId);
        if (valueKey != null) {
            if (parsed.valueHash32 == 0 || parsed.valueHash32 == DsDataUtil.hash32(valueKey, 0, valueKey.length)) {
                valueIndex.remove(valueKey, indexId);
            }
        }
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

    byte[] getIndexRecordForTest(long indexId) throws IOException {
        return bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
    }

    private byte[] getValueByIndexId(byte[] keyBytes, long indexId) throws IOException {
        ParsedIndex parsed = resolveParsedIndexForKey(keyBytes, indexId);
        if (parsed == null) {
            return null;
        }
        if (parsed.valueLen <= 0) {
            return new byte[0];
        }
        if (parsed.valueHash32 == 0) {
            if (!isChunkedValue(parsed.valueLen)) {
                return bucketStore.get("value", parsed.valueId, parsed.valueLen);
            }
            return readChunkedValueBytes(parsed.valueId, parsed.valueLen);
        }
        int headLen = Math.min(parsed.valueLen, effectiveMaxValueHash32Bytes());
        byte[] head = readValuePrefix(parsed.valueId, parsed.valueLen, headLen);
        if (parsed.valueHash32 != DsDataUtil.hash32(head, 0, headLen)) {
            return null;
        }
        if (parsed.valueLen == headLen) {
            return head;
        }
        if (!isChunkedValue(parsed.valueLen)) {
            byte[] valueBytes = new byte[parsed.valueLen];
            System.arraycopy(head, 0, valueBytes, 0, headLen);
            bucketStore.get("value", parsed.valueId, headLen, parsed.valueLen - headLen, valueBytes, headLen);
            return valueBytes;
        }
        return readChunkedValueBytes(parsed.valueId, parsed.valueLen);
    }

    private InputStream getValueStreamByIndexId(byte[] keyBytes, long indexId) throws IOException {
        ParsedIndex parsed = resolveParsedIndexForKey(keyBytes, indexId);
        if (parsed == null) {
            return null;
        }
        if (parsed.valueLen <= 0) {
            return new ByteArrayInputStream(new byte[0]);
        }
        if (parsed.valueHash32 == 0) {
            if (!isChunkedValue(parsed.valueLen)) {
                return bucketStore.openInputStream("value", parsed.valueId, parsed.valueLen);
            }
            return openChunkedValueStream(parsed.valueId, parsed.valueLen);
        }
        int headLen = Math.min(parsed.valueLen, effectiveMaxValueHash32Bytes());
        byte[] head = readValuePrefix(parsed.valueId, parsed.valueLen, headLen);
        if (parsed.valueHash32 != DsDataUtil.hash32(head, 0, headLen)) {
            return null;
        }
        if (parsed.valueLen == headLen) {
            return new ByteArrayInputStream(head);
        }
        if (!isChunkedValue(parsed.valueLen)) {
            InputStream tail = bucketStore.openInputStream("value", parsed.valueId, headLen, parsed.valueLen - headLen);
            return new SequenceInputStream(new ByteArrayInputStream(head), tail);
        }
        InputStream full = openChunkedValueStream(parsed.valueId, parsed.valueLen);
        if (headLen <= 0) {
            return full;
        }
        return new SequenceInputStream(new ByteArrayInputStream(head), new SkipInputStream(full, headLen));
    }

    private ParsedIndex resolveParsedIndexForKey(byte[] keyBytes, long indexId) throws IOException {
        byte[] indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
        ParsedIndex parsed = parseIndex(indexRecord);
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 == DsDataUtil.hash32(storedKey) && Arrays.equals(storedKey, keyBytes)) {
            return parsed;
        }
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
        return parsed;
    }

    private ExistingEntry findExisting(byte[] keyBytes) throws IOException {
        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return null;
        }
        byte[] indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
        ParsedIndex parsed = parseIndex(indexRecord);
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 == DsDataUtil.hash32(storedKey) && Arrays.equals(storedKey, keyBytes)) {
            return new ExistingEntry(indexId, parsed);
        }
        masterIndex.promoteOnCollision(keyBytes, storedKey, indexId);
        indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return null;
        }
        indexRecord = bucketStore.get("index", indexId, INDEX_RECORD_SIZE);
        parsed = parseIndex(indexRecord);
        storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
            return null;
        }
        return new ExistingEntry(indexId, parsed);
    }

    private int effectiveMaxValueHash32Bytes() {
        int max = maxValueHash32Bytes;
        if (max > 0) {
            return max;
        }
        return MAX_VALUE_HASH32_BYTES_DEFAULT;
    }

    private byte[] readValueHead(long valueId, int valueLen) throws IOException {
        if (valueLen <= 0) {
            return new byte[0];
        }
        int headLen = Math.min(valueLen, effectiveMaxValueHash32Bytes());
        return bucketStore.get("value", valueId, headLen);
    }

    private byte[] readValueIndexKey(long valueId, int valueLen) throws IOException {
        if (valueLen <= 0) {
            return new byte[0];
        }
        int headLen = Math.min(valueLen, effectiveMaxValueHash32Bytes());
        return readValuePrefix(valueId, valueLen, headLen);
    }

    private byte[] valueIndexKey(byte[] valueBytes) {
        if (valueBytes == null || valueBytes.length == 0) {
            return new byte[0];
        }
        int max = effectiveMaxValueHash32Bytes();
        if (valueBytes.length <= max) {
            return valueBytes;
        }
        return Arrays.copyOf(valueBytes, max);
    }

    public final class ValueRandomAccess implements AutoCloseable {
        private final long indexId;
        private final ParsedIndex parsed;
        private final DsFixedBucketStore.RecordRandomAccess ra;
        private boolean dirty;
        private boolean closed;

        private ValueRandomAccess(long indexId, ParsedIndex parsed, DsFixedBucketStore.RecordRandomAccess ra) {
            this.indexId = indexId;
            this.parsed = parsed;
            this.ra = ra;
        }

        public long size() {
            return parsed.valueLen;
        }

        public long position() {
            return ra.position();
        }

        public void position(long newPosition) {
            if (newPosition < 0 || newPosition > (long) parsed.valueLen) {
                throw new IllegalArgumentException("position out of range: " + newPosition);
            }
            ra.position(newPosition);
        }

        public int readAt(long pos, byte[] dst, int dstOffset, int len) throws IOException {
            if (pos < 0) {
                throw new IllegalArgumentException("pos must be >= 0");
            }
            if (pos >= (long) parsed.valueLen) {
                return -1;
            }
            int maxRead = (int) Math.min((long) len, (long) parsed.valueLen - pos);
            return ra.readAt(pos, dst, dstOffset, maxRead);
        }

        public void writeAt(long pos, byte[] src, int srcOffset, int len) throws IOException {
            if (pos < 0) {
                throw new IllegalArgumentException("pos must be >= 0");
            }
            if (pos + (long) len > (long) parsed.valueLen) {
                throw new IllegalArgumentException("write overflow valueLen");
            }
            ra.writeAt(pos, src, srcOffset, len);
            dirty = true;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            ra.close();
            if (!dirty) {
                return;
            }
            if (parsed.valueHash32 == 0) {
                return;
            }
            byte[] record = buildIndexRecord(parsed.keyLen, parsed.keyHash32, parsed.keyId, parsed.valueLen, 0, parsed.valueId);
            bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "index", indexId, record);
        }
    }

    public final class ValueMappedAccess implements AutoCloseable {
        private final long indexId;
        private final ParsedIndex original;
        private final byte[] oldValueKey;
        private long valueId;
        private int valueLen;
        private boolean dirty;
        private boolean closed;

        private ValueMappedAccess(long indexId, ParsedIndex parsed) throws IOException {
            this.indexId = indexId;
            this.original = parsed;
            this.valueId = parsed.valueId;
            this.valueLen = parsed.valueLen;
            this.oldValueKey = readValueIndexKey(parsed.valueId, parsed.valueLen);
        }

        public int size() {
            return valueLen;
        }

        public List<ValueMappedWindow> mapWindows(int offset, int length, boolean write) throws IOException {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must be >= 0");
            }
            if (length <= 0) {
                throw new IllegalArgumentException("length must be > 0");
            }
            long end = (long) offset + (long) length;
            if (!write && end > (long) valueLen) {
                throw new IllegalArgumentException("read overflow valueLen");
            }
            if (write && end > (long) valueLen) {
                ensureLengthForWrite((int) end);
            }

            if (!isChunkedValue(valueLen)) {
                DsFixedBucketStore.MappedWindow w = bucketStore.openMappedWindow("value", valueId, offset, length, write);
                dirty |= write;
                ArrayList<ValueMappedWindow> windows = new ArrayList<>(1);
                windows.add(new ValueMappedWindow(offset, w, write));
                return windows;
            }

            int chunkSize = effectiveMaxValueChunkBytes();
            int remain = length;
            int abs = offset;
            ArrayList<ValueMappedWindow> windows = new ArrayList<>();
            while (remain > 0) {
                int chunkIndex = abs / chunkSize;
                int within = abs % chunkSize;
                int can = Math.min(chunkSize - within, remain);
                long chunkId = getChunkId(valueId, chunkIndex);
                if (chunkId == 0) {
                    if (write) {
                        chunkId = materializeChunk(valueId, chunkIndex, false);
                        DsFixedBucketStore.MappedWindow w = bucketStore.openMappedWindow("value", chunkId, within, can, true);
                        windows.add(new ValueMappedWindow(abs, w, true));
                    } else {
                        int remainZero = can;
                        int zeroAbs = abs;
                        while (remainZero > 0) {
                            int z = Math.min(ZERO_MAP_BLOCK_BYTES, remainZero);
                            windows.add(new ValueMappedWindow(zeroAbs, zeroReadOnlyBuffer(z)));
                            remainZero -= z;
                            zeroAbs += z;
                        }
                    }
                } else {
                    DsFixedBucketStore.MappedWindow w = bucketStore.openMappedWindow("value", chunkId, within, can, write);
                    windows.add(new ValueMappedWindow(abs, w, write));
                }
                dirty |= write;
                abs += can;
                remain -= can;
            }
            return windows;
        }

        private void ensureLengthForWrite(int newLen) throws IOException {
            if (newLen <= valueLen) {
                return;
            }
            if (!isChunkedValue(valueLen) && !isChunkedValue(newLen)) {
                long newValueId = bucketStore.getNewId(DsFixedBucketStore.DATA_SPACE, "value", newLen);
                if (valueLen > 0) {
                    try (InputStream in = bucketStore.openInputStream("value", valueId, valueLen)) {
                        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", newValueId, in, valueLen);
                    }
                }
                if (newLen > valueLen) {
                    zeroFillSingleValue(newValueId, valueLen, newLen - valueLen);
                }
                bucketStore.remove("value", valueId);
                valueId = newValueId;
                valueLen = newLen;
                dirty = true;
                return;
            }

            if (!isChunkedValue(valueLen) && isChunkedValue(newLen)) {
                long headSegId = allocateChunkList();
                if (valueLen > 0) {
                    copySingleToChunked(valueId, valueLen, headSegId);
                }
                bucketStore.remove("value", valueId);
                valueId = headSegId;
                valueLen = newLen;
                dirty = true;
                return;
            }

            valueLen = newLen;
            dirty = true;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            if (!dirty) {
                return;
            }
            byte[] record = buildIndexRecord(original.keyLen, original.keyHash32, original.keyId, valueLen, 0, valueId);
            bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "index", indexId, record);

            valueIndex.remove(oldValueKey, indexId);
            byte[] newValueKey = readValueIndexKey(valueId, valueLen);
            valueIndex.add(newValueKey, indexId);
        }
    }

    public static final class ValueMappedWindow implements AutoCloseable {
        private final int offset;
        private final DsFixedBucketStore.MappedWindow mapped;
        private final ByteBuffer buffer;
        private final boolean write;

        private ValueMappedWindow(int offset, DsFixedBucketStore.MappedWindow mapped, boolean write) {
            this.offset = offset;
            this.mapped = mapped;
            this.buffer = mapped.buffer();
            this.write = write;
        }

        private ValueMappedWindow(int offset, ByteBuffer buffer) {
            this.offset = offset;
            this.mapped = null;
            this.buffer = buffer;
            this.write = false;
        }

        public int offset() {
            return offset;
        }

        public ByteBuffer buffer() {
            return buffer;
        }

        public boolean isMapped() {
            return mapped != null;
        }

        public void force() {
            if (mapped != null) {
                mapped.force();
            }
        }

        @Override
        public void close() throws IOException {
            if (mapped != null) {
                mapped.close();
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int remain = len;
        while (remain > 0) {
            int n = in.read(buf, off, remain);
            if (n < 0) {
                throw new EOFException("unexpected EOF");
            }
            if (n == 0) {
                continue;
            }
            off += n;
            remain -= n;
        }
    }

    private static final class ExistingEntry {
        final long indexId;
        final long keyId;
        final int keyLen;
        final long valueId;
        final int valueLen;
        final int valueHash32;

        private ExistingEntry(long indexId, ParsedIndex parsed) {
            this.indexId = indexId;
            this.keyId = parsed.keyId;
            this.keyLen = parsed.keyLen;
            this.valueId = parsed.valueId;
            this.valueLen = parsed.valueLen;
            this.valueHash32 = parsed.valueHash32;
        }
    }

    private static final class ValueRef {
        final int valueLen;
        final int valueHash32;
        final long valueId;

        private ValueRef(int valueLen, int valueHash32, long valueId) {
            this.valueLen = valueLen;
            this.valueHash32 = valueHash32;
            this.valueId = valueId;
        }
    }

    static int valueHash32(byte[] valueBytes) {
        if (valueBytes == null) {
            return DsDataUtil.hash32(new byte[0]);
        }
        int max = maxValueHash32Bytes;
        if (max <= 0) {
            max = MAX_VALUE_HASH32_BYTES_DEFAULT;
        }
        int len = Math.min(valueBytes.length, max);
        return DsDataUtil.hash32(valueBytes, 0, len);
    }

    private static final class ParsedIndex {
        int keyLen;
        int keyHash32;
        long keyId;
        int valueLen;
        int valueHash32;
        long valueId;
    }

    private static final class SkipInputStream extends InputStream {
        private final InputStream in;
        private long remain;

        private SkipInputStream(InputStream in, long remain) {
            this.in = in;
            this.remain = remain;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            if (n <= 0) {
                return -1;
            }
            return b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            while (remain > 0) {
                long s = in.skip(remain);
                if (s > 0) {
                    remain -= s;
                    continue;
                }
                int x = in.read();
                if (x < 0) {
                    remain = 0;
                    return -1;
                }
                remain--;
            }
            return in.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static ByteBuffer zeroReadOnlyBuffer(int length) throws IOException {
        if (length <= 0) {
            return ByteBuffer.allocate(0).asReadOnlyBuffer();
        }
        if (length > ZERO_MAP_BLOCK_BYTES) {
            throw new IllegalArgumentException("length overflow zero map block");
        }
        MappedByteBuffer m = ZERO_RO_MAP;
        if (m == null) {
            synchronized (ZERO_MAP_LOCK) {
                m = ZERO_RO_MAP;
                if (m == null) {
                    File f = new File(System.getProperty("java.io.tmpdir"), "p2p-db-zero-map.dat");
                    try (RandomAccessFile raf = new RandomAccessFile(f, "rw"); FileChannel ch = raf.getChannel()) {
                        if (raf.length() < ZERO_MAP_BLOCK_BYTES) {
                            raf.setLength(ZERO_MAP_BLOCK_BYTES);
                        }
                        m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ZERO_MAP_BLOCK_BYTES);
                    }
                    ZERO_RO_MAP = m;
                }
            }
        }
        MappedByteBuffer dup = m.duplicate();
        dup.position(0);
        dup.limit(length);
        return dup.slice().asReadOnlyBuffer();
    }

    private static final class ZeroInputStream extends InputStream {
        private long remain;
        private final byte[] singleByte = new byte[1];

        private ZeroInputStream(long remain) {
            this.remain = Math.max(0, remain);
        }

        @Override
        public int read() throws IOException {
            int n = read(singleByte, 0, 1);
            if (n <= 0) {
                return -1;
            }
            return 0;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len <= 0) {
                return 0;
            }
            if (remain <= 0) {
                return -1;
            }
            int n = (int) Math.min((long) len, remain);
            Arrays.fill(b, off, off + n, (byte) 0);
            remain -= n;
            return n;
        }
    }

    private boolean isChunkedValue(int valueLen) {
        return valueLen > effectiveMaxValueChunkBytes();
    }

    private int effectiveMaxValueChunkBytes() {
        int v = maxValueChunkBytes;
        if (v > 0) {
            return v;
        }
        return MAX_VALUE_CHUNK_BYTES_DEFAULT;
    }

    private ValueRef storeChunkedValueBytes(byte[] valueBytes) throws IOException {
        int valueLen = valueBytes.length;
        int chunkSize = effectiveMaxValueChunkBytes();
        int numChunks = (valueLen + chunkSize - 1) / chunkSize;
        long headSegId = allocateChunkList();
        for (int i = 0; i < numChunks; i++) {
            int offset = i * chunkSize;
            int len = Math.min(chunkSize, valueLen - offset);
            long chunkId = allocateValueChunkId();
            if (len < chunkSize) {
                zeroFillChunk(chunkId);
            }
            bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", chunkId, valueBytes, offset, len);
            setChunkId(headSegId, i, chunkId);
        }
        int valueHash32 = valueHash32(valueBytes);
        return new ValueRef(valueLen, valueHash32, headSegId);
    }

    private ValueRef storeChunkedValueStream(InputStream in, int valueLen, byte[] headPrefix) throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        int numChunks = (valueLen + chunkSize - 1) / chunkSize;
        long headSegId = allocateChunkList();
        int remain = valueLen;
        for (int i = 0; i < numChunks; i++) {
            int len = Math.min(chunkSize, remain);
            long chunkId = allocateValueChunkId();
            if (len < chunkSize) {
                zeroFillChunk(chunkId);
            }
            bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", chunkId, in, len);
            setChunkId(headSegId, i, chunkId);
            remain -= len;
        }
        int valueHash32 = 0;
        if (headPrefix != null && headPrefix.length > 0) {
            valueHash32 = 0;
        }
        return new ValueRef(valueLen, valueHash32, headSegId);
    }

    private long allocateValueChunkId() throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        return bucketStore.getNewId(DsFixedBucketStore.DATA_SPACE, "value", chunkSize);
    }

    private long allocateChunkList() throws IOException {
        long segId = bucketStore.getNewId(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, VALUE_CHUNK_SEGMENT_BYTES);
        bucketStore.overwrite(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, new byte[VALUE_CHUNK_SEGMENT_BYTES]);
        return segId;
    }

    private long getChunkId(long headSegId, int chunkIndex) throws IOException {
        int segIndex = chunkIndex / VALUE_CHUNK_SEGMENT_CAP;
        int slot = chunkIndex % VALUE_CHUNK_SEGMENT_CAP;
        long segId = headSegId;
        for (int i = 0; i < segIndex; i++) {
            byte[] b = bucketStore.get(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 0, 8);
            segId = DsDataUtil.loadLong(b, 0);
            if (segId == 0) {
                return 0;
            }
        }
        byte[] slotBytes = bucketStore.get(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 8 + slot * 8, 8);
        return DsDataUtil.loadLong(slotBytes, 0);
    }

    private void setChunkId(long headSegId, int chunkIndex, long chunkId) throws IOException {
        int segIndex = chunkIndex / VALUE_CHUNK_SEGMENT_CAP;
        int slot = chunkIndex % VALUE_CHUNK_SEGMENT_CAP;
        long segId = ensureChunkListSegment(headSegId, segIndex);
        byte[] slotBytes = new byte[8];
        DsDataUtil.storeLong(slotBytes, 0, chunkId);
        bucketStore.update(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 8 + slot * 8, slotBytes);
    }

    private long ensureChunkListSegment(long headSegId, int segIndex) throws IOException {
        if (segIndex <= 0) {
            return headSegId;
        }
        synchronized (chunkListLock) {
            long segId = headSegId;
            for (int i = 0; i < segIndex; i++) {
                byte[] next = bucketStore.get(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 0, 8);
                long nextId = DsDataUtil.loadLong(next, 0);
                if (nextId != 0) {
                    segId = nextId;
                    continue;
                }
                long newSegId = bucketStore.getNewId(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, VALUE_CHUNK_SEGMENT_BYTES);
                bucketStore.overwrite(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, newSegId, new byte[VALUE_CHUNK_SEGMENT_BYTES]);
                byte[] nextBuf = new byte[8];
                DsDataUtil.storeLong(nextBuf, 0, newSegId);
                bucketStore.update(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 0, nextBuf);
                segId = newSegId;
            }
            return segId;
        }
    }

    private int requiredChunks(int valueLen) {
        if (valueLen <= 0) {
            return 0;
        }
        int chunkSize = effectiveMaxValueChunkBytes();
        return (valueLen + chunkSize - 1) / chunkSize;
    }

    private void ensureChunkListCapacity(long headSegId, int requiredChunks) throws IOException {
        if (headSegId == 0) {
            throw new IllegalArgumentException("headSegId must be non-zero");
        }
        int requiredSegs = (requiredChunks + VALUE_CHUNK_SEGMENT_CAP - 1) / VALUE_CHUNK_SEGMENT_CAP;
        long segId = headSegId;
        long last = headSegId;
        int segCount = 1;
        while (true) {
            byte[] next = bucketStore.get(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 0, 8);
            long nextId = DsDataUtil.loadLong(next, 0);
            if (nextId == 0) {
                last = segId;
                break;
            }
            segId = nextId;
            segCount++;
        }
        int missing = requiredSegs - segCount;
        if (missing <= 0) {
            return;
        }
        long prev = last;
        for (int i = 0; i < missing; i++) {
            long newSegId = bucketStore.getNewId(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, VALUE_CHUNK_SEGMENT_BYTES);
            bucketStore.overwrite(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, newSegId, new byte[VALUE_CHUNK_SEGMENT_BYTES]);
            byte[] nextBuf = new byte[8];
            DsDataUtil.storeLong(nextBuf, 0, newSegId);
            bucketStore.update(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, prev, 0, nextBuf);
            prev = newSegId;
        }
    }

    private void copySingleToChunked(long oldValueId, int oldValueLen, long headSegId) throws IOException {
        if (oldValueLen <= 0) {
            return;
        }
        int chunkSize = effectiveMaxValueChunkBytes();
        int remain = oldValueLen;
        int chunkIndex = 0;
        try (InputStream in = bucketStore.openInputStream("value", oldValueId, oldValueLen)) {
            while (remain > 0) {
                int len = Math.min(chunkSize, remain);
                long chunkId = materializeChunk(headSegId, chunkIndex, len >= chunkSize);
                bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", chunkId, in, len);
                remain -= len;
                chunkIndex++;
            }
        }
    }

    private void writeChunkedBytes(long headSegId, int totalLen, int offset, byte[] data) throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        int remain = data.length;
        int src = 0;
        int abs = offset;
        while (remain > 0) {
            int chunkIndex = abs / chunkSize;
            int within = abs % chunkSize;
            int can = Math.min(chunkSize - within, remain);
            long chunkId = materializeChunk(headSegId, chunkIndex, false);
            bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", chunkId, within, data, src, can);
            remain -= can;
            src += can;
            abs += can;
        }
    }

    private void writeChunkedStream(long headSegId, int totalLen, int offset, InputStream in, int length) throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        int remain = length;
        int abs = offset;
        while (remain > 0) {
            int chunkIndex = abs / chunkSize;
            int within = abs % chunkSize;
            int can = Math.min(chunkSize - within, remain);
            long chunkId = materializeChunk(headSegId, chunkIndex, false);
            bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", chunkId, within, in, can);
            remain -= can;
            abs += can;
        }
    }

    private long materializeChunk(long headSegId, int chunkIndex, boolean fullWrite) throws IOException {
        long chunkId = getChunkId(headSegId, chunkIndex);
        if (chunkId != 0) {
            return chunkId;
        }
        chunkId = allocateValueChunkId();
        if (!fullWrite) {
            zeroFillChunk(chunkId);
        }
        setChunkId(headSegId, chunkIndex, chunkId);
        return chunkId;
    }

    private void zeroFillChunk(long chunkId) throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        bucketStore.overwrite(DsFixedBucketStore.DATA_SPACE, "value", chunkId, new ZeroInputStream(chunkSize), chunkSize);
    }

    private void zeroFillSingleValue(long valueId, int offset, int length) throws IOException {
        if (length <= 0) {
            return;
        }
        byte[] zeros = new byte[64 * 1024];
        int remain = length;
        int pos = offset;
        while (remain > 0) {
            int n = Math.min(zeros.length, remain);
            bucketStore.update(DsFixedBucketStore.DATA_SPACE, "value", valueId, pos, zeros, 0, n);
            pos += n;
            remain -= n;
        }
    }

    private byte[] readValuePrefix(long valueId, int valueLen, int headLen) throws IOException {
        if (headLen <= 0) {
            return new byte[0];
        }
        if (!isChunkedValue(valueLen)) {
            return bucketStore.get("value", valueId, headLen);
        }
        byte[] out = new byte[headLen];
        int chunkSize = effectiveMaxValueChunkBytes();
        int remain = headLen;
        int dst = 0;
        int idx = 0;
        while (remain > 0) {
            long chunkId = getChunkId(valueId, idx);
            int can = Math.min(chunkSize, remain);
            if (chunkId != 0) {
                bucketStore.get("value", chunkId, can, out, dst);
            }
            remain -= can;
            dst += can;
            idx++;
        }
        return out;
    }

    private byte[] readChunkedValueBytes(long headSegId, int valueLen) throws IOException {
        int chunkSize = effectiveMaxValueChunkBytes();
        int numChunks = (valueLen + chunkSize - 1) / chunkSize;
        byte[] out = new byte[valueLen];
        int dst = 0;
        for (int i = 0; i < numChunks; i++) {
            int len = Math.min(chunkSize, valueLen - dst);
            long chunkId = getChunkId(headSegId, i);
            if (chunkId != 0) {
                bucketStore.get("value", chunkId, len, out, dst);
            }
            dst += len;
        }
        return out;
    }

    private InputStream openChunkedValueStream(long headSegId, int valueLen) throws IOException {
        return new ChunkedValueInputStream(headSegId, valueLen);
    }

    private void deleteValue(int valueLen, long valueId) throws IOException {
        if (valueLen <= 0) {
            return;
        }
        if (!isChunkedValue(valueLen)) {
            bucketStore.remove("value", valueId);
            return;
        }
        int chunkSize = effectiveMaxValueChunkBytes();
        int numChunks = (valueLen + chunkSize - 1) / chunkSize;
        for (int i = 0; i < numChunks; i++) {
            long chunkId = getChunkId(valueId, i);
            if (chunkId != 0) {
                bucketStore.remove("value", chunkId);
            }
        }
        long segId = valueId;
        while (segId != 0) {
            byte[] next = bucketStore.get(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId, 0, 8);
            long nextId = DsDataUtil.loadLong(next, 0);
            bucketStore.remove(DsFixedBucketStore.META_SPACE, VALUE_CHUNK_TYPE, segId);
            segId = nextId;
        }
    }

    private final class ChunkedValueInputStream extends InputStream {
        private final int chunkSize;
        private final int totalLen;
        private final int numChunks;
        private final byte[] singleByte = new byte[1];
        private long headSegId;
        private int chunkIndex;
        private int chunkOffset;
        private InputStream current;
        private int remain;

        private ChunkedValueInputStream(long headSegId, int totalLen) throws IOException {
            this.chunkSize = effectiveMaxValueChunkBytes();
            this.totalLen = totalLen;
            this.numChunks = (totalLen + chunkSize - 1) / chunkSize;
            this.headSegId = headSegId;
            openNextChunk();
        }

        @Override
        public int read() throws IOException {
            int n = read(singleByte, 0, 1);
            if (n <= 0) {
                return -1;
            }
            return singleByte[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }
            if (current == null) {
                return -1;
            }
            int n = current.read(b, off, Math.min(len, remain));
            if (n > 0) {
                remain -= n;
                chunkOffset += n;
                if (remain == 0) {
                    openNextChunk();
                }
                return n;
            }
            openNextChunk();
            return read(b, off, len);
        }

        private void openNextChunk() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            if (chunkIndex >= numChunks) {
                return;
            }
            long chunkId = getChunkId(headSegId, chunkIndex);
            int expectedLen = Math.min(chunkSize, totalLen - chunkIndex * chunkSize);
            remain = expectedLen;
            chunkOffset = 0;
            if (chunkId == 0) {
                current = new ZeroInputStream(expectedLen);
            } else {
                current = bucketStore.openInputStream("value", chunkId, expectedLen);
            }
            chunkIndex++;
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
        }
    }
}
