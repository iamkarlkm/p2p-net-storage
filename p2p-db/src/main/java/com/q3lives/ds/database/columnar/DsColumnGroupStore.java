package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.IOException;

import com.q3lives.ds.collections.DsFixedValueStore;
import com.q3lives.ds.collections.DsHashMap;

/**
 * ORM 定义的固定长度列组存储。
 *
 * <p>每个列组独立维护一个 DsHashMap（key = rowId，value = groupRecordId）
 * 和一个 companion {@link DsFixedValueStore}（recordId -> groupSize 字节）。</p>
 *
 * <p>列组内各字段按 {@code @DsFieldGroup#groupIndex()} 排序后顺序拼接，
 * 偏移在组创建时固定；只要新增/删除/重排列不超出组总尺寸，就不需要调整 schema。</p>
 */
public final class DsColumnGroupStore implements AutoCloseable {

    private final String groupName;
    private final int groupSize;
    private final DsHashMap index;
    private final DsFixedValueStore valueStore;

    /**
     * 打开或创建一个列组存储。
     *
     * @param groupMapFile 列组索引文件，companion 的 .values 文件会自动创建
     * @param groupSize    列组总字节长度，必须 > 0
     * @throws IOException 若初始化失败
     */
    public DsColumnGroupStore(File groupMapFile, int groupSize) throws IOException {
        if (groupMapFile == null) {
            throw new NullPointerException("groupMapFile");
        }
        if (groupSize <= 0) {
            throw new IllegalArgumentException("groupSize must be > 0");
        }
        this.groupName = groupMapFile.getName();
        this.groupSize = groupSize;
        this.index = new DsHashMap(groupMapFile);
        this.valueStore = new DsFixedValueStore(new File(groupMapFile.getAbsolutePath() + ".values"), groupSize);
    }

    /**
     * 写入整组字节。
     *
     * @param rowId      聚合层 rowId
     * @param groupBytes 长度必须等于 groupSize；不足请在调用前填充
     * @return 本次写入的 recordId（复用已有 recordId 时不变）
     * @throws IOException 若写入失败
     */
    public long put(long rowId, byte[] groupBytes) throws IOException {
        validateGroupBytes(groupBytes);
        long oldRecordId = index.getOrDefault(rowId, 0L);
        long recordId = oldRecordId == 0L ? valueStore.allocateRecord() : oldRecordId;
        valueStore.write(recordId, groupBytes);
        if (recordId != oldRecordId) {
            index.put(rowId, recordId);
        }
        return recordId;
    }

    /**
     * 读取整组字节。
     *
     * @param rowId 聚合层 rowId
     * @return 组字节，若不存在返回 null
     * @throws IOException 若读取失败
     */
    public byte[] get(long rowId) throws IOException {
        long recordId = index.getOrDefault(rowId, 0L);
        if (recordId == 0L) {
            return null;
        }
        return valueStore.read(recordId);
    }

    /**
     * 按偏移读取组内某一列。
     *
     * @param rowId  聚合层 rowId
     * @param offset 组内字节偏移
     * @param length 列字节长度
     * @return 列字节，若组不存在返回 null
     * @throws IOException 若越界或读取失败
     */
    public byte[] getColumn(long rowId, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > groupSize) {
            throw new IllegalArgumentException("column range out of group bounds: offset=" + offset
                    + ", length=" + length + ", groupSize=" + groupSize);
        }
        byte[] group = get(rowId);
        if (group == null) {
            return null;
        }
        byte[] out = new byte[length];
        System.arraycopy(group, offset, out, 0, length);
        return out;
    }

    /**
     * 删除指定 rowId 的列组数据并回收 recordId。
     *
     * @param rowId 聚合层 rowId
     * @return 是否确实删除了数据
     * @throws IOException 若删除失败
     */
    public boolean remove(long rowId) throws IOException {
        long recordId = index.getOrDefault(rowId, 0L);
        if (recordId == 0L) {
            return false;
        }
        valueStore.recycle(recordId);
        index.remove(rowId);
        return true;
    }

    public int getGroupSize() {
        return groupSize;
    }

    public String getGroupName() {
        return groupName;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        try {
            index.close();
        } catch (RuntimeException e) {
            first = new IOException(e);
        }
        try {
            valueStore.close();
        } catch (IOException e) {
            if (first != null) {
                e.addSuppressed(first);
            }
            first = e;
        }
        if (first != null) {
            throw first;
        }
    }

    private void validateGroupBytes(byte[] groupBytes) {
        if (groupBytes == null) {
            throw new NullPointerException("groupBytes");
        }
        if (groupBytes.length != groupSize) {
            throw new IllegalArgumentException("groupBytes length " + groupBytes.length
                    + " != groupSize " + groupSize);
        }
    }
}
