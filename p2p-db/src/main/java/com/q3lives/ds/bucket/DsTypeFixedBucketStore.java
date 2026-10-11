package com.q3lives.ds.bucket;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.q3lives.ds.util.MultiTypeDataId;

/**
 * 带 TypeID 分层的定长 bucket 存储。
 *
 * <p>在 {@link DsFixedBucketStore}（8-bit power + 56-bit baseId）之上叠加
 * 16-bit TypeID，形成 {@link MultiTypeDataId} 三段子 ID：
 * <pre>
 *  [63:48] 16-bit TypeID
 *  [47:40]  8-bit power   (与 DsFixedBucketStore 完全一致)
 *  [39: 0] 40-bit indexId (= DsFixedBucketStore baseId 的低 40 位)
 * </pre>
 *
 * <p><b>物理隔离规则（未来不同类型跑不同搜索/处理算法的前提）</b>：
 * 每个 TypeID 拥有独立的 {@code <rootDir>/type_{typeId}/} 子目录作为
 * 底层 {@link DsFixedBucketStore} 的 rootDir。因此：
 * <ul>
 *   <li>TypeID A 的 data_{size}.dat 与 TypeID B 的 data_{size}.dat 是两份不同的物理文件；</li>
 *   <li>不同 TypeID 的 free-ring、master_index 完全不可见；</li>
 *   <li>同一 rootDir 下 DsTypeFixedBucketStore(typeId=X) 与 plain DsFixedBucketStore
 *       依然互不干扰（plain 类根目录直接用 rootDir，DsType* 用 rootDir/type_X）。</li>
 * </ul>
 */
public class DsTypeFixedBucketStore implements AutoCloseable {

    public static final int ID_OFFSET_BITS = DsFixedBucketStore.ID_OFFSET_BITS; // 56
    public static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;        // 64
    public static final int MAX_POWER = DsFixedBucketStore.MAX_POWER;            // 27
    public static final String META_SPACE = DsFixedBucketStore.META_SPACE;
    public static final String DATA_SPACE = DsFixedBucketStore.DATA_SPACE;

    public static String typeDirFor(String rootDir, int typeId) {
        return new File(rootDir, "type_" + Integer.toHexString(clampTypeId(typeId)).toUpperCase()).getAbsolutePath();
    }

    private final DsFixedBucketStore bucketStore;
    private final int defaultTypeId;
    private final String typeDir;

    public DsTypeFixedBucketStore(String rootDir) {
        this(rootDir, 0);
    }

    public DsTypeFixedBucketStore(String rootDir, int defaultTypeId) {
        this.defaultTypeId = clampTypeId(defaultTypeId);
        this.typeDir = typeDirFor(rootDir, this.defaultTypeId);
        File dir = new File(this.typeDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(this.typeDir);
    }

    public int getDefaultTypeId() {
        return defaultTypeId;
    }

    public String getTypeDir() {
        return typeDir;
    }

    private static int clampTypeId(int typeId) {
        if (typeId < 0) return 0;
        if (typeId > MultiTypeDataId.MAX_TYPE) return MultiTypeDataId.MAX_TYPE;
        return typeId;
    }

    private static long toInternalId(long typedId) {
        int power = MultiTypeDataId.decodePower(typedId);
        long baseId = MultiTypeDataId.decodeIndexId(typedId);
        return ((long) power << ID_OFFSET_BITS) | (baseId & ((1L << ID_OFFSET_BITS) - 1));
    }

    private static long toTypedId(int typeId, long internalId) {
        int power = DsFixedBucketStore.decodePower(internalId);
        long baseId = internalId & ((1L << ID_OFFSET_BITS) - 1);
        return MultiTypeDataId.encode(typeId, power, baseId);
    }

    private boolean matchesDefault(long typedId) {
        return MultiTypeDataId.decodeTypeId(typedId) == defaultTypeId;
    }

    public long getNewId(int typeId, String space, String type, int length) throws IOException {
        long internal = bucketStore.getNewId(space, type, length);
        return toTypedId(typeId, internal);
    }

    public long getNewId(String space, String type, int length) throws IOException {
        return getNewId(defaultTypeId, space, type, length);
    }

    public long getNewIdByPower(int typeId, String space, String type, int power) throws IOException {
        long internal = bucketStore.getNewIdByPower(space, type, power);
        return toTypedId(typeId, internal);
    }

    public long getNewIdByPower(String space, String type, int power) throws IOException {
        return getNewIdByPower(defaultTypeId, space, type, power);
    }

    public long[] getNewIdsByPower(int typeId, String space, String type, int power, int size) throws IOException {
        long[] internals = bucketStore.getNewIdsByPower(space, type, power, size);
        long[] outs = new long[internals.length];
        for (int i = 0; i < internals.length; i++) {
            outs[i] = toTypedId(typeId, internals[i]);
        }
        return outs;
    }

    public long[] getNewIdsByPower(String space, String type, int power, int size) throws IOException {
        return getNewIdsByPower(defaultTypeId, space, type, power, size);
    }

    public long put(int typeId, String space, String type, byte[] data) throws IOException {
        long internal = bucketStore.put(space, type, data);
        return toTypedId(typeId, internal);
    }

    public long put(String space, String type, byte[] data) throws IOException {
        return put(defaultTypeId, space, type, data);
    }

    public long put(int typeId, String space, String type, InputStream in, int length) throws IOException {
        long internal = bucketStore.put(space, type, in, length);
        return toTypedId(typeId, internal);
    }

    public long put(String space, String type, InputStream in, int length) throws IOException {
        return put(defaultTypeId, space, type, in, length);
    }

    public long put(int typeId, String space, String type, byte[] data, int offset, int count) throws IOException {
        long internal = bucketStore.put(space, type, data, offset, count);
        return toTypedId(typeId, internal);
    }

    public long put(String space, String type, byte[] data, int offset, int count) throws IOException {
        return put(defaultTypeId, space, type, data, offset, count);
    }

    public byte[] get(String space, String type, long typedId, int length) throws IOException {
        if (!matchesDefault(typedId)) return bucketStore.get(space, type, toInternalId(typedId), length); // 按底层空/零/行为返回
        return bucketStore.get(space, type, toInternalId(typedId), length);
    }

    public byte[] get(String space, String type, long typedId, int offset, int length) throws IOException {
        return bucketStore.get(space, type, toInternalId(typedId), offset, length);
    }

    public void get(String space, String type, long typedId, int offset, int length, byte[] out, int outOffset) throws IOException {
        bucketStore.get(space, type, toInternalId(typedId), offset, length, out, outOffset);
    }

    public byte[] get(String type, long typedId, int length) throws IOException {
        return get(DATA_SPACE, type, typedId, length);
    }

    public void get(String type, long typedId, int offset, int length, byte[] out, int outOffset) throws IOException {
        get(DATA_SPACE, type, typedId, offset, length, out, outOffset);
    }

    public void remove(String space, String type, long typedId) throws IOException {
        if (!matchesDefault(typedId)) return;
        bucketStore.remove(space, type, toInternalId(typedId));
    }

    public void remove(String type, long typedId) throws IOException {
        if (!matchesDefault(typedId)) return;
        bucketStore.remove(DATA_SPACE, type, toInternalId(typedId));
    }

    public void overwrite(String space, String type, long typedId, byte[] data) throws IOException {
        bucketStore.overwrite(space, type, toInternalId(typedId), data);
    }

    public void overwrite(String space, String type, long typedId, InputStream in, int length) throws IOException {
        bucketStore.overwrite(space, type, toInternalId(typedId), in, length);
    }

    public void overwrite(String space, String type, long typedId, byte[] data, int offset, int count) throws IOException {
        bucketStore.overwrite(space, type, toInternalId(typedId), data, offset, count);
    }

    public long update(String space, String type, long oldTypedId, byte[] data, DsFixedBucketStore.UpdatePolicy policy, int newTypeId) throws IOException {
        if (!matchesDefault(oldTypedId)) {
            long internal = bucketStore.getNewId(space, type, data == null ? 0 : data.length);
            bucketStore.overwrite(space, type, internal, data);
            return toTypedId(newTypeId, internal);
        }
        long internal = bucketStore.update(space, type, toInternalId(oldTypedId), data, policy);
        return toTypedId(newTypeId, internal);
    }

    public long update(String space, String type, long oldTypedId, byte[] data, DsFixedBucketStore.UpdatePolicy policy) throws IOException {
        int typeId = matchesDefault(oldTypedId) ? defaultTypeId : MultiTypeDataId.decodeTypeId(oldTypedId);
        return update(space, type, oldTypedId, data, policy, typeId);
    }

    public long update(String space, String type, long oldTypedId, int offset, byte[] data) throws IOException {
        if (matchesDefault(oldTypedId)) {
            bucketStore.update(space, type, toInternalId(oldTypedId), offset, data);
        }
        return oldTypedId;
    }

    public long update(String space, String type, long oldTypedId, int offset, byte[] data, int dataOffset, int length) throws IOException {
        if (matchesDefault(oldTypedId)) {
            bucketStore.update(space, type, toInternalId(oldTypedId), offset, data, dataOffset, length);
        }
        return oldTypedId;
    }

    public long putMeta(int typeId, String type, byte[] data) throws IOException {
        long internal = bucketStore.putMeta(type, data);
        return toTypedId(typeId, internal);
    }

    public long putMeta(String type, byte[] data) throws IOException {
        return putMeta(defaultTypeId, type, data);
    }

    public byte[] getMeta(String type, long typedId, int length) throws IOException {
        return bucketStore.getMeta(type, toInternalId(typedId), length);
    }

    public void removeMeta(String type, long typedId) throws IOException {
        bucketStore.removeMeta(type, toInternalId(typedId));
    }

    public InputStream openInputStream(String space, String type, long typedId, int offset, int length) throws IOException {
        return bucketStore.openInputStream(space, type, toInternalId(typedId), offset, length);
    }

    public InputStream openInputStream(String space, String type, long typedId, int length) throws IOException {
        return bucketStore.openInputStream(space, type, toInternalId(typedId), length);
    }

    public void readTo(String space, String type, long typedId, int offset, int length, OutputStream out) throws IOException {
        bucketStore.readTo(space, type, toInternalId(typedId), offset, length, out);
    }

    public DsFixedBucketStore.MappedWindow openMappedWindow(String space, String type, long typedId, int offset, int length, boolean write) throws IOException {
        return bucketStore.openMappedWindow(space, type, toInternalId(typedId), offset, length, write);
    }

    public DsFixedBucketStore.MappedWindow openMappedWindow(String type, long typedId, int offset, int length, boolean write) throws IOException {
        return openMappedWindow(DATA_SPACE, type, typedId, offset, length, write);
    }

    public void close() throws IOException {
        bucketStore.close();
    }

    public void sync() {
        bucketStore.sync();
    }
}
