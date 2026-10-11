package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.core.DsFreeRing;
import com.q3lives.ds.core.DsObject;

/**
 * 每个 DsHashMap 的 companion 定长 valueStore。
 *
 * <p>文件名为 {@code <mapFile>.values}，创建时确定 {@code recordSize}，
 * 内部按 recordId 索引固定长度字节块。recordId 为纯本地地址，不嵌入 serverId/partitionId。</p>
 *
 * <p>适用场景：>64 位且已知固定长度的单列数据，或固定长度列组数据。</p>
 */
public class DsFixedValueStore extends DsObject implements AutoCloseable {

    private static final byte[] MAGIC = new byte[] { '.', 'F', 'V', 'S' };
    private static final int HEADER_SIZE = 64;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_RECORD_SIZE = 4;
    private static final int HDR_NEXT_RECORD_ID = 8;

    private final int recordSize;
    private final DsFreeRing freeRing;
    private final ReentrantLock allocateLock = new ReentrantLock();

    private long nextRecordId;

    /**
     * 打开或创建一个定长 valueStore。
     *
     * @param valuesFile 存储文件，建议命名为 {@code <mapFile>.values}
     * @param recordSize 每条记录的字节长度，必须 > 0
     * @throws IOException 若文件初始化失败
     */
    public DsFixedValueStore(File valuesFile, int recordSize) throws IOException {
        super(valuesFile, HEADER_SIZE, recordSize);
        if (recordSize <= 0) {
            throw new IllegalArgumentException("recordSize must be > 0");
        }
        this.recordSize = recordSize;
        initHeader();
        this.freeRing = new DsFreeRing(new File(valuesFile.getAbsolutePath() + ".free"), 1024);
    }

    private void initHeader() throws IOException {
        headerBuffer = loadBuffer(0L);
        byte[] m = new byte[4];
        headerBuffer.get(HDR_MAGIC, m, 0, 4);
        if (Arrays.equals(m, MAGIC)) {
            int storedRecordSize = headerBuffer.getInt(HDR_RECORD_SIZE);
            if (storedRecordSize != recordSize) {
                throw new IllegalStateException(
                        "recordSize mismatch: stored=" + storedRecordSize + ", expected=" + recordSize);
            }
            nextRecordId = headerBuffer.getLong(HDR_NEXT_RECORD_ID);
        } else {
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);
            headerBuffer.putInt(HDR_RECORD_SIZE, recordSize);
            nextRecordId = 1L;
            headerBuffer.putLong(HDR_NEXT_RECORD_ID, nextRecordId);
            dirty(0L);
        }
    }

    /**
     * 分配一条新记录，返回 recordId。
     */
    public long allocateRecord() throws IOException {
        allocateLock.lock();
        try {
            long reused = freeRing.poll();
            if (reused >= 0L) {
                return reused;
            }
            long id = nextRecordId;
            nextRecordId++;
            headerBuffer.putLong(HDR_NEXT_RECORD_ID, nextRecordId);
            dirty(0L);
            return id;
        } finally {
            allocateLock.unlock();
        }
    }

    /**
     * 回收 recordId，供后续分配复用。
     */
    public void recycle(long recordId) throws IOException {
        allocateLock.lock();
        try {
            freeRing.offer(recordId);
        } finally {
            allocateLock.unlock();
        }
    }

    /**
     * 写入数据到指定 recordId。
     *
     * @param recordId 记录 id
     * @param data     长度必须等于 recordSize 的字节数组
     * @throws IOException              若写入失败
     * @throws IllegalArgumentException 若 data 长度不等于 recordSize
     */
    public void write(long recordId, byte[] data) throws IOException {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (data.length != recordSize) {
            throw new IllegalArgumentException(
                    "data length " + data.length + " != recordSize " + recordSize);
        }
        writeBytes(recordId, 0, data, 0, recordSize);
    }

    /**
     * 读取指定 recordId 的数据。
     *
     * @param recordId 记录 id
     * @return 长度为 recordSize 的字节数组
     * @throws IOException 若读取失败
     */
    public byte[] read(long recordId) throws IOException {
        byte[] out = new byte[recordSize];
        readBytes(recordId, 0, out, 0, recordSize);
        return out;
    }

    public int getRecordSize() {
        return recordSize;
    }

    /**
     * 返回下一个未分配 recordId（仅用于测试或诊断）。
     */
    public long getNextRecordId() {
        allocateLock.lock();
        try {
            return nextRecordId;
        } finally {
            allocateLock.unlock();
        }
    }

    /**
     * 同步并关闭 valueStore，释放文件句柄。
     */
    public void close() throws IOException {
        IOException ex = null;
        try {
            sync();
            unloadAllMappedBuffers();
        } catch (RuntimeException e) {
            ex = new IOException("sync/unload failed", e);
        }
        try {
            freeRing.close();
        } catch (IOException e) {
            if (ex != null) {
                e.addSuppressed(ex);
            }
            ex = e;
        }
        if (ex != null) {
            throw ex;
        }
    }
}
