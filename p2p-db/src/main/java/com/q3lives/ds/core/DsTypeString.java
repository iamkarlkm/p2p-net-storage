package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.q3lives.ds.bucket.DsData;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.bucket.DsTypeFixedBucketStore;
import com.q3lives.ds.util.MultiTypeDataId;

/**
 * 带 TypeID 分层的字符串存储，使用独立子目录与其他 TypeID 物理隔离。
 *
 * <p>对外 id 是 {@link MultiTypeDataId} 三段子结构：
 * <pre>
 *   [63:48] 16-bit TypeID
 *   [47:40]  8-bit power
 *   [39: 0] 40-bit indexId
 * </pre>
 *
 * <p><b>物理隔离规则（未来不同类型可跑不同搜索/处理算法）</b>：
 * 每个 TypeID 的存储实例使用
 * {@code <rootDir>/type_{typeId}/string_store/}
 * 作为独立的 {@link DsData} 根目录；不同 TypeID 之间：
 * <ul>
 *   <li>master_index 完全独立（不会去重到对方的 key）；</li>
 *   <li>data_value / data_index 两类 bucket 文件在不同子目录下；</li>
 *   <li>free-ring 完全隔离，不会跨 TypeID 复用 indexId。</li>
 * </ul>
 *
 * <p><b>TypeID 不变式</b>：任何读取方法（get/refCount/retain/remove/contains）传入的 typedId
 * 若最外 16-bit TypeID 与 {@link #getTypeId()} 不符，直接语义视为不存在（get 返回 null、
 * refCount=0、retain 返回 false、contains=false），避免用 type A 的 id 误命中 type B 的内容。
 */
public class DsTypeString implements AutoCloseable {

    private static final String STORE_NAME = "string_store";
    private static final int INTERNAL_OFFSET_BITS = DsFixedBucketStore.ID_OFFSET_BITS; // 56

    private final DsData dataStore;
    private final int typeId;
    private final String typeDir;

    public DsTypeString(String rootDir, int typeId) throws IOException {
        this.typeId = clamp(typeId);
        this.typeDir = DsTypeFixedBucketStore.typeDirFor(rootDir, this.typeId);
        File td = new File(this.typeDir);
        if (!td.exists()) td.mkdirs();
        this.dataStore = new DsData(this.typeDir, STORE_NAME, DsFixedBucketStore.DATA_SPACE);
    }

    public int getTypeId() {
        return typeId;
    }

    public String getTypeDir() {
        return typeDir;
    }

    private static int clamp(int t) {
        if (t < 0) return 0;
        if (t > MultiTypeDataId.MAX_TYPE) return MultiTypeDataId.MAX_TYPE;
        return t;
    }

    private static long toInternal(long typedId) {
        int power = MultiTypeDataId.decodePower(typedId);
        long baseId = MultiTypeDataId.decodeIndexId(typedId);
        return ((long) power << INTERNAL_OFFSET_BITS) | (baseId & ((1L << INTERNAL_OFFSET_BITS) - 1));
    }

    private long toTyped(long internalId) {
        int power = DsFixedBucketStore.decodePower(internalId);
        long baseId = internalId & ((1L << INTERNAL_OFFSET_BITS) - 1);
        return MultiTypeDataId.encode(typeId, power, baseId);
    }

    private boolean matchesThis(long typedId) {
        return MultiTypeDataId.decodeTypeId(typedId) == typeId;
    }

    public long add(String value) throws IOException {
        if (value == null) value = "";
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        long internal = dataStore.put(b);
        return toTyped(internal);
    }

    public String get(long id) throws IOException {
        if (!matchesThis(id)) return null;
        try {
            long internal = toInternal(id);
            int ref = dataStore.getRefCountByIndexId(internal);
            if (ref <= 0) return null;
            byte[] b = dataStore.getValueByIndexId(internal);
            if (b == null) return null;
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public int refCount(long id) throws IOException {
        if (!matchesThis(id)) return 0;
        return dataStore.getRefCountByIndexId(toInternal(id));
    }

    public void retain(long id) throws IOException {
        if (!matchesThis(id)) {
            throw new IllegalStateException("String id type mismatch for id=" + id + ": expects typeId=" + typeId);
        }
        boolean ok = dataStore.retain(toInternal(id));
        if (!ok) {
            throw new IllegalStateException("String id=" + id + " does not exist or has been deleted");
        }
    }

    public void release(long id) throws IOException {
        remove(id);
    }

    public long update(long id, String value) throws IOException {
        if (value == null) value = "";
        byte[] newBytes = value.getBytes(StandardCharsets.UTF_8);
        if (matchesThis(id)) {
            byte[] oldBytes = dataStore.getValueByIndexId(toInternal(id));
            if (oldBytes != null && Arrays.equals(oldBytes, newBytes)) {
                return id;
            }
            remove(id);
        }
        long internal = dataStore.put(newBytes);
        return toTyped(internal);
    }

    public void remove(long id) throws IOException {
        if (!matchesThis(id)) return;
        dataStore.remove(toInternal(id));
    }

    public boolean contains(String value) throws IOException {
        if (value == null) value = "";
        Long indexId = dataStore.getIndexId(value.getBytes(StandardCharsets.UTF_8));
        if (indexId == null) return false;
        return dataStore.getRefCountByIndexId(indexId) > 0;
    }

    public Long getIndexId(String key) throws IOException {
        return getIndexId(key, false);
    }

    public Long getIndexId(String key, boolean createIfAbsent) throws IOException {
        if (key == null) key = "";
        byte[] b = key.getBytes(StandardCharsets.UTF_8);
        Long internal = dataStore.getIndexId(b);
        if (internal != null && dataStore.getRefCountByIndexId(internal) > 0) {
            return toTyped(internal);
        }
        if (!createIfAbsent) {
            return null;
        }
        long newInternal = dataStore.put(b);
        return toTyped(newInternal);
    }

    public void close() {
        dataStore.close();
    }
}
