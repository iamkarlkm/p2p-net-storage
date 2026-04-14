package com.q3lives.ds.index.master;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;

/**
 * MD5 主控索引（md5(16B) -> indexId），按 16 个 byte 构建 16 层 256-ary trie。
 *
 * <p>该索引用于 {@link DsTieredMasterIndex} 的第 3 层（hash64 碰撞后升级到 md5）。</p>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>md5 是 16 字节定长 key，因此 HASH_LEN=16。</li>
 *   <li>slot 状态机与 {@link DsHash32MasterIndex}/{@link DsHash64MasterIndex} 一致。</li>
 * </ul>
 */
public class DsShaMd5MasterIndex extends DsObject {
    private static final byte[] MAGIC = new byte[] { '.','M', 'D', '5'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_VALUE_CHILD = 3;

    private static final int BITMAP_BYTES = 128;
    private static final int SLOT_PAYLOAD_BYTES = 32;
    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;
    private static final int HASH_LEN = 16;

    private final ReentrantLock opLock = new ReentrantLock();

    private long nextNodeId;
    private long size;

    /**
     * 创建一个 MD5 主控索引。
     *
     * <p>file 为索引文件路径；不存在时会初始化 header 与 root node。</p>
     */
    public DsShaMd5MasterIndex(File file) {
        super(file, 1);
        initHeader();
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(0, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                nextNodeId = headerBuffer.getLong(8);
                size = headerBuffer.getLong(16);
                return;
            }
            headerBuffer.put(0, MAGIC, 0, 4);
            nextNodeId = 1;
            size = 0;
            headerBuffer.putLong(8, nextNodeId);
            headerBuffer.putLong(16, size);
            dirty(0L);
            loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 写入 md5(16B) -> indexId 映射。
     *
     * <p>md5 使用 16 层 trie（每层 1 byte）；必要时会创建子节点并进行 VALUE->VALUE_CHILD 的前缀扩展。</p>
     */
    public void put(byte[] md5, long indexId) throws IOException {
        if (md5 == null || md5.length != HASH_LEN) {
            throw new IllegalArgumentException("md5 length must be 16");
        }
        if (indexId <= 0) {
            throw new IllegalArgumentException("indexId must be positive");
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = md5[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_LEN - 1) {
                    if (state == STATE_CHILD) {
                        nodeId = loadLongOffset(payloadPos(nodeId, slot));
                        continue;
                    }
                    if (state == STATE_VALUE_CHILD) {
                        nodeId = loadLongOffset(payloadPos(nodeId, slot) + 8);
                        continue;
                    }
                    if (state == STATE_EMPTY) {
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_CHILD);
                        storeLongOffset(payloadPos(nodeId, slot), child);
                        nodeId = child;
                        continue;
                    }
                    if (state == STATE_VALUE) {
                        long oldValue = loadLongOffset(payloadPos(nodeId, slot));
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_VALUE_CHILD);
                        storeLongOffset(payloadPos(nodeId, slot), oldValue);
                        storeLongOffset(payloadPos(nodeId, slot) + 8, child);
                        nodeId = child;
                        continue;
                    }
                    throw new IOException("unknown state: " + state);
                }

                if (state == STATE_EMPTY) {
                    writeState(nodeId, slot, STATE_VALUE);
                    storeLongOffset(payloadPos(nodeId, slot), indexId);
                    size++;
                    headerBuffer.putLong(16, size);
                    dirty(0L);
                    return;
                }
                if (state == STATE_VALUE || state == STATE_VALUE_CHILD) {
                    storeLongOffset(payloadPos(nodeId, slot), indexId);
                    return;
                }
                if (state == STATE_CHILD) {
                    throw new IOException("invalid leaf state");
                }
                throw new IOException("unknown state: " + state);
            }
        } finally {
            opLock.unlock();
        }
    }

    /**
     * 查询 md5 对应的 indexId。
     */
    public Long get(byte[] md5) throws IOException {
        if (md5 == null || md5.length != HASH_LEN) {
            return null;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN; level++) {
            int slot = md5[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_LEN - 1) {
                if (state == STATE_CHILD) {
                    long child = loadLongOffset(payloadPos(nodeId, slot));
                    if (child <= 0) {
                        return null;
                    }
                    nodeId = child;
                    continue;
                }
                if (state == STATE_VALUE_CHILD) {
                    long child = loadLongOffset(payloadPos(nodeId, slot) + 8);
                    if (child <= 0) {
                        return null;
                    }
                    nodeId = child;
                    continue;
                }
                return null;
            }

            if (state == STATE_VALUE || state == STATE_VALUE_CHILD) {
                long v = loadLongOffset(payloadPos(nodeId, slot));
                return v == 0 ? null : v;
            }
            return null;
        }
        return null;
    }

    /**
     * 删除 md5 对应的映射。
     *
     * <p>当叶子是 STATE_VALUE_CHILD 时，删除只清空 value 并把状态退化为 STATE_CHILD（保留 child 信息）。</p>
     */
    public boolean remove(byte[] md5) throws IOException {
        if (md5 == null || md5.length != HASH_LEN) {
            return false;
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = md5[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_LEN - 1) {
                    if (state == STATE_CHILD) {
                        long child = loadLongOffset(payloadPos(nodeId, slot));
                        if (child <= 0) {
                            return false;
                        }
                        nodeId = child;
                        continue;
                    }
                    if (state == STATE_VALUE_CHILD) {
                        long child = loadLongOffset(payloadPos(nodeId, slot) + 8);
                        if (child <= 0) {
                            return false;
                        }
                        nodeId = child;
                        continue;
                    }
                    return false;
                }

                if (state == STATE_VALUE) {
                    writeState(nodeId, slot, STATE_EMPTY);
                    storeLongOffset(payloadPos(nodeId, slot), 0L);
                    size--;
                    headerBuffer.putLong(16, size);
                    dirty(0L);
                    return true;
                }
                if (state == STATE_VALUE_CHILD) {
                    storeLongOffset(payloadPos(nodeId, slot), 0L);
                    writeState(nodeId, slot, STATE_CHILD);
                    size--;
                    headerBuffer.putLong(16, size);
                    dirty(0L);
                    return true;
                }
                return false;
            }
            return false;
        } finally {
            opLock.unlock();
        }
    }

    /**
     * 返回当前映射条目数（上限截断到 int）。
     */
    public int size() throws IOException {
        if (size > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) size;
    }

    /**
     * 同步并关闭索引。
     */
    public void close() {
        sync();
    }

    /**
     * 读取 md5 叶子 slot 的 childLevel（用于 tiered 升级路由）。
     */
    public int getChildLevel(byte[] md5) throws IOException {
        long pos = locateLeaf(md5, false);
        if (pos < 0) {
            return 0;
        }
        long nodeId = pos >>> 8;
        int slot = (int) (pos & 0xFF);
        int state = readState(nodeId, slot);
        if (state != STATE_VALUE_CHILD) {
            return 0;
        }
        long v = loadLongOffset(payloadPos(nodeId, slot) + 8);
        return (int) v;
    }

    /**
     * 在 md5 的叶子 slot 上写入 childLevel（用于 tiered 升级路由）。
     *
     * <p>如果叶子原本是 STATE_VALUE，会被提升为 STATE_VALUE_CHILD，并把 childLevel 写入 payload[8..15]。</p>
     */
    public void setChildLevel(byte[] md5, int childLevel) throws IOException {
        if (md5 == null || md5.length != HASH_LEN) {
            throw new IllegalArgumentException("md5 length must be 16");
        }
        opLock.lock();
        try {
            long pos = locateLeaf(md5, true);
            long nodeId = pos >>> 8;
            int slot = (int) (pos & 0xFF);
            int state = readState(nodeId, slot);
            if (state == STATE_VALUE) {
                writeState(nodeId, slot, STATE_VALUE_CHILD);
                storeLongOffset(payloadPos(nodeId, slot) + 8, (long) childLevel);
                return;
            }
            if (state == STATE_VALUE_CHILD) {
                storeLongOffset(payloadPos(nodeId, slot) + 8, (long) childLevel);
                return;
            }
            throw new IOException("no value at md5 leaf");
        } finally {
            opLock.unlock();
        }
    }

    private long allocateNodeId() throws IOException {
        headerOpLock.lock();
        try {
            long id = nextNodeId;
            nextNodeId++;
            headerBuffer.putLong(8, nextNodeId);
            dirty(0L);
            return id;
        } finally {
            headerOpLock.unlock();
        }
    }

    private static long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) NODE_SIZE;
    }

    private static long bitmapPos(long nodeId, int slot) {
        return nodeBase(nodeId) + (slot >>> 1);
    }

    private static long payloadPos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * SLOT_PAYLOAD_BYTES;
    }

    private long locateLeaf(byte[] md5, boolean createPath) throws IOException {
        if (md5 == null || md5.length != HASH_LEN) {
            return -1;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN - 1; level++) {
            int slot = md5[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (state == STATE_CHILD) {
                long child = loadLongOffset(payloadPos(nodeId, slot));
                if (child <= 0) {
                    return -1;
                }
                nodeId = child;
                continue;
            }
            if (state == STATE_VALUE_CHILD) {
                long child = loadLongOffset(payloadPos(nodeId, slot) + 8);
                if (child <= 0) {
                    return -1;
                }
                nodeId = child;
                continue;
            }
            if (state == STATE_EMPTY && createPath) {
                long child = allocateNodeId();
                writeState(nodeId, slot, STATE_CHILD);
                storeLongOffset(payloadPos(nodeId, slot), child);
                nodeId = child;
                continue;
            }
            return -1;
        }
        int leafSlot = md5[HASH_LEN - 1] & 0xFF;
        return (nodeId << 8) | (long) leafSlot;
    }

    private int readState(long nodeId, int slot) throws IOException {
        long pos = bitmapPos(nodeId, slot);
        Long bufferIndex = pos / BLOCK_SIZE;
        int bufferOffset = (int) (pos % BLOCK_SIZE);
        byte b = loadBuffer(bufferIndex).get(bufferOffset);
        if ((slot & 1) == 0) {
            return b & 0x0F;
        }
        return (b >>> 4) & 0x0F;
    }

    private void writeState(long nodeId, int slot, int state) throws IOException {
        long pos = bitmapPos(nodeId, slot);
        Long bufferIndex = pos / BLOCK_SIZE;
        int bufferOffset = (int) (pos % BLOCK_SIZE);
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        byte b = buffer.get(bufferOffset);
        int s = state & 0x0F;
        if ((slot & 1) == 0) {
            b = (byte) ((b & 0xF0) | s);
        } else {
            b = (byte) ((b & 0x0F) | (s << 4));
        }
        buffer.put(bufferOffset, b);
        dirty(bufferIndex);
    }
}
