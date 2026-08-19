package com.q3lives.ds.binlog;

import com.q3lives.ds.bucket.DsData;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * DsBytes 动态列存储（轻封装 {@link DsData}，indexId 8B 存在 binlog dyn 区，真正的 bytes 在独立 data_store 里）。
 *
 * <p>语义：binlog 帧内 dyn 区只写 8B indexId，真正的 value 内容由 DsData 做内容去重 + refCount；
 * 相同 bytes（例如大列重复值）跨帧只写一次物理，降低 binlog 膨胀。</p>
 */
public final class DsBytes implements Closeable {

    private final DsData dataStore;

    public DsBytes(String rootDir) throws IOException {
        this.dataStore = new DsData(rootDir, DsBinlogLayout.DSBYTES_STORE_NAME);
    }

    public DsBytes(File rootDir) throws IOException {
        this(rootDir.getAbsolutePath());
    }

    /**
     * 将 bytes 放入字典，返回 indexId（相同 bytes 返回同一 id 并累加 refCount）。
     * null 视为空数组。
     */
    public long put(byte[] value) throws IOException {
        return dataStore.put(value);
    }

    /**
     * 按 indexId 取 bytes。
     * refCount<=0 或被删除时返回 null。
     */
    public byte[] get(long indexId) throws IOException {
        try {
            int ref = dataStore.getRefCountByIndexId(indexId);
            if (ref <= 0) return null;
            return dataStore.getValueByIndexId(indexId);
        } catch (Throwable ignore) {
            return null;
        }
    }

    public int refCount(long indexId) throws IOException {
        return dataStore.getRefCountByIndexId(indexId);
    }

    public void retain(long indexId) throws IOException {
        boolean ok = dataStore.retain(indexId);
        if (!ok) throw new IllegalStateException("DsBytes id=" + indexId + " not exist or deleted");
    }

    public void release(long indexId) throws IOException {
        dataStore.remove(indexId);
    }

    @Override
    public void close() throws IOException {
        try {
            dataStore.close();
        } catch (Throwable ignore) {
        }
    }
}
