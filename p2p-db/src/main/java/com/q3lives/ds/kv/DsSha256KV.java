package com.q3lives.ds.kv;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.index.master.DsTieredMasterIndex;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 基于 bucket 层的通用 KV（byte[] key -> byte[] value），并提供“按内容寻址”的超大 value 支持。
 *
 * <p>特点：</p>
 * <ul>
 *   <li>keyBytes 可以是任意长度（常见用法：直接传 sha256(key) 或业务自定义二进制 key）。</li>
 *   <li>valueBytes 也可变长；当 value 很大时，会按固定大小分片/分层存入不同 bucket（见 {@link #storeValue(byte[])}）。</li>
 *   <li>通过 {@link DsTieredMasterIndex} 做 key->indexId 映射，并在发生碰撞时驱动升级（hash32->hash64->md5->sha256）。</li>
 * </ul>
 *
 * <p>index record（32B）布局：</p>
 * <ul>
 *   <li>int keyLen</li>
 *   <li>int keyHash32</li>
 *   <li>long keyId</li>
 *   <li>int valueLen</li>
 *   <li>int valueHash32</li>
 *   <li>long valueId</li>
 * </ul>
 *
 * <p>value 存储策略：</p>
 * <ul>
 *   <li>小 value：直接存入 value bucket（type="value"），valueId 指向该记录。</li>
 *   <li>大 value：可能拆分成多个块并通过 valueId 指向块链/索引（实现细节在本类内部）。</li>
 * </ul>
 *
 * <p>与 {@link DsKVStore} 的关系：</p>
 * <ul>
 *   <li>DsKVStore 是“key/value 都落 bucket + 额外 valueIndex 反查”的 KV。</li>
 *   <li>本类更偏向“通用 KV + 大 value 支持”，常被上层（如 DsFile 的 content/meta）用作基础组件。</li>
 * </ul>
 */
public class DsSha256KV {
    public static final int INDEX_RECORD_SIZE = 32;
    public static final int MAX_VALUE_SIZE = 1 << DsFixedBucketStore.MAX_POWER;

    private final DsFixedBucketStore bucketStore;
    private final DsTieredMasterIndex masterIndex;

    /**
     * 创建一个 KV 存储实例。
     *
     * <p>rootDir 下会创建 bucket 文件与 master 索引目录。</p>
     */
    public DsSha256KV(String rootDir) throws IOException {
        File dir = new File(rootDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(dir.getAbsolutePath());
        File masterDir = new File(dir, DsFixedBucketStore.INDEPENDENT_SPACE + File.separator + "master");
        if (!masterDir.exists()) {
            masterDir.mkdirs();
        }
        this.masterIndex = new DsTieredMasterIndex(masterDir);
    }

    /**
     * 写入/更新一条 KV 并返回 indexId。
     *
     * <p>语义：</p>
     * <ul>
     *   <li>如果 key 不存在：新建 key/value/index record，并把 key->indexId 写入 masterIndex。</li>
     *   <li>如果 key 已存在：写入新的 index record，并删除旧的 index record 与旧的 value（不会清零旧数据，只回收 id）。</li>
     * </ul>
     *
     * <p>一致性策略：</p>
     * <ul>
     *   <li>读取旧记录时会校验 keyHash32 + keyBytes；不匹配视为碰撞，触发 {@link DsTieredMasterIndex#promoteOnCollision(byte[], byte[], long)} 再重试一次定位。</li>
     *   <li>value 写入后会存储 valueHash32，读取时会二次校验，防止读到损坏/错位数据。</li>
     * </ul>
     */
    public long update(byte[] keyBytes, byte[] valueBytes) throws IOException {
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
            }
        }

        long keyId = oldKeyId;
        int keyLen = oldKeyLen;
        int keyHash32 = DsDataUtil.hash32(keyBytes);
        if (keyId == 0) {
            keyId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "key", keyBytes);
            keyLen = keyBytes.length;
        }

        ValueRef valueRef = storeValue(valueBytes);
        byte[] record = buildIndexRecord(keyLen, keyHash32, keyId, valueRef.valueLen, valueRef.valueHash32, valueRef.valueId);
        long indexId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "index", record);
        masterIndex.put(keyBytes, indexId, existed);

        if (oldIndexId != null) {
            bucketStore.remove("index", oldIndexId);
            deleteValue(oldValueLen, oldValueId);
        }

        return indexId;
    }

    /**
     * 根据 key 读取 value。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>masterIndex.get(key) -> indexId</li>
     *   <li>读取 index record，取出 keyId/valueId 与长度/hash</li>
     *   <li>校验 keyBytes（如不匹配则触发碰撞升级并重查）</li>
     *   <li>读取 value，并校验 valueHash32</li>
     * </ol>
     */
    public byte[] get(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return null;
        }

        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return null;
        }

        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
            masterIndex.promoteOnCollision(keyBytes, storedKey, indexId);
            indexId = masterIndex.get(keyBytes);
            if (indexId == null) {
                return null;
            }
            parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
            storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
            if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
                return null;
            }
        }

        byte[] valueBytes = readValue(parsed.valueLen, parsed.valueId);
        if (parsed.valueHash32 != DsDataUtil.hash32(valueBytes)) {
            return null;
        }
        return valueBytes;
    }

    /**
     * 通过 indexId 直接读取 value（跳过 key 校验与 masterIndex 查找）。
     *
     * <p>该方法通常用于上层已经保存了 indexId 句柄的场景。</p>
     */
    public byte[] getValueByIndexId(long indexId) throws IOException {
        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        return readValue(parsed.valueLen, parsed.valueId);
    }

    /**
     * 删除 key 对应的 KV。
     *
     * <p>注意：删除会回收 index/value/key 的 id（加入 free-ring），但不清零历史数据。</p>
     */
    public boolean remove(byte[] keyBytes) throws IOException {
        if (keyBytes == null) {
            return false;
        }

        Long indexId = masterIndex.get(keyBytes);
        if (indexId == null) {
            return false;
        }

        ParsedIndex parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
        byte[] storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
        if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
            masterIndex.promoteOnCollision(keyBytes, storedKey, indexId);
            indexId = masterIndex.get(keyBytes);
            if (indexId == null) {
                return false;
            }
            parsed = parseIndex(bucketStore.get("index", indexId, INDEX_RECORD_SIZE));
            storedKey = bucketStore.get("key", parsed.keyId, parsed.keyLen);
            if (parsed.keyHash32 != DsDataUtil.hash32(storedKey) || !Arrays.equals(storedKey, keyBytes)) {
                return false;
            }
        }

        if (!masterIndex.remove(keyBytes)) {
            return false;
        }

        bucketStore.remove("index", indexId);
        deleteValue(parsed.valueLen, parsed.valueId);
        bucketStore.remove("key", parsed.keyId);
        return true;
    }

    /**
     * 关闭底层 masterIndex 与 bucketStore。
     */
    public void close() {
        masterIndex.close();
        try {
            bucketStore.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private ValueRef storeValue(byte[] valueBytes) throws IOException {
        int valueLen = valueBytes.length;
        int valueHash32 = DsDataUtil.hash32(valueBytes);
        if (valueLen <= MAX_VALUE_SIZE) {
            long valueId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "value", valueBytes);
            return new ValueRef(valueLen, valueHash32, valueId);
        }

        int numChunks = (valueLen + MAX_VALUE_SIZE - 1) / MAX_VALUE_SIZE;
        long[] chunkIds = new long[numChunks];
        for (int i = 0; i < numChunks; i++) {
            int offset = i * MAX_VALUE_SIZE;
            int len = Math.min(MAX_VALUE_SIZE, valueLen - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(valueBytes, offset, chunk, 0, len);
            chunkIds[i] = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "value", chunk);
        }

        byte[] listData = new byte[numChunks * 8];
        for (int i = 0; i < numChunks; i++) {
            DsDataUtil.storeLong(listData, i * 8, chunkIds[i]);
        }
        long listId = bucketStore.put(DsFixedBucketStore.INDEPENDENT_SPACE, "value", listData);
        return new ValueRef(valueLen, valueHash32, listId);
    }

    private byte[] readValue(int valueLen, long valueId) throws IOException {
        if (valueLen <= 0) {
            return new byte[0];
        }
        if (valueLen <= MAX_VALUE_SIZE) {
            return bucketStore.get("value", valueId, valueLen);
        }

        int numChunks = (valueLen + MAX_VALUE_SIZE - 1) / MAX_VALUE_SIZE;
        byte[] listData = bucketStore.get("value", valueId, numChunks * 8);
        byte[] out = new byte[valueLen];
        int dst = 0;
        for (int i = 0; i < numChunks; i++) {
            long chunkId = DsDataUtil.loadLong(listData, i * 8);
            int len = Math.min(MAX_VALUE_SIZE, valueLen - dst);
            byte[] chunk = bucketStore.get("value", chunkId, len);
            System.arraycopy(chunk, 0, out, dst, len);
            dst += len;
        }
        return out;
    }

    private void deleteValue(int valueLen, long valueId) throws IOException {
        if (valueLen <= 0) {
            return;
        }
        if (valueLen <= MAX_VALUE_SIZE) {
            bucketStore.remove("value", valueId);
            return;
        }

        int numChunks = (valueLen + MAX_VALUE_SIZE - 1) / MAX_VALUE_SIZE;
        byte[] listData = bucketStore.get("value", valueId, numChunks * 8);
        for (int i = 0; i < numChunks; i++) {
            long chunkId = DsDataUtil.loadLong(listData, i * 8);
            bucketStore.remove("value", chunkId);
        }
        bucketStore.remove("value", valueId);
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

    private static final class ParsedIndex {
        int keyLen;
        int keyHash32;
        long keyId;
        int valueLen;
        int valueHash32;
        long valueId;
    }

    private static final class ValueRef {
        final int valueLen;
        final int valueHash32;
        final long valueId;

        ValueRef(int valueLen, int valueHash32, long valueId) {
            this.valueLen = valueLen;
            this.valueHash32 = valueHash32;
            this.valueId = valueId;
        }
    }
}
