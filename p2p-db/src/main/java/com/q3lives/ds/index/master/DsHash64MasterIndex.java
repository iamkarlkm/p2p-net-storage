package com.q3lives.ds.index.master;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 64-bit 哈希主控索引（hash64 -> indexId），按 8 个 byte 构建 8 层 256-ary trie。
 *
 * <p>与 {@link DsHash32MasterIndex} 同构，差别仅在：</p>
 * <ul>
 *   <li>HASH_LEN=8（使用 64-bit 哈希值）</li>
 *   <li>对外 put/get/remove 接口以 long hash64 或 8 字节数组表示</li>
 * </ul>
 *
 * <p>slot payload 与状态位含义：</p>
 * <ul>
 *   <li>STATE_VALUE：payload[0..7] 保存 indexId</li>
 *   <li>STATE_CHILD：payload[0..7] 保存 childNodeId</li>
 *   <li>STATE_VALUE_CHILD：payload[0..7] 保存 indexId，payload[8..15] 保存 child（子节点或升级信息）</li>
 * </ul>
 *
 * <p>该索引常被 {@link DsTieredMasterIndex} 用作 hash32 碰撞后的下一层索引。</p>
 */
public class DsHash64MasterIndex extends DsObject {
    private static final byte[] MAGIC = new byte[] {'.', 'H', '6', '4'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_VALUE_CHILD = 3;

    private static final int BITMAP_BYTES = 128;
    private static final int SLOT_PAYLOAD_BYTES = 32;
    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;
    private static final int HASH_LEN = 8;

    private final ReentrantLock opLock = new ReentrantLock();

    private long nextNodeId;
    private long size;

    /**
     * 创建一个 hash64 主控索引。
     *
     * <p>file 为索引文件路径；不存在时会初始化 header 与 root node。</p>
     */
    public DsHash64MasterIndex(File file) {
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

    public void put(long hash64, long indexId) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeLong(b, 0, hash64);
        put(b, indexId);
    }

    /**
     * 写入 hash64 -> indexId 映射。
     *
     * <p>hash64 使用 8 层 trie（每层 1 byte）；必要时会创建子节点并进行 VALUE->VALUE_CHILD 的前缀扩展。</p>
     */
    public void put(byte[] hash64, long indexId) throws IOException {
        if (hash64 == null || hash64.length != HASH_LEN) {
            throw new IllegalArgumentException("hash64 length must be 8");
        }
        if (indexId <= 0) {
            throw new IllegalArgumentException("indexId must be positive");
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = hash64[level] & 0xFF;
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
     * 查询 hash64 对应的 indexId。
     */
    public Long get(long hash64) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeLong(b, 0, hash64);
        return get(b);
    }

    /**
     * 查询 hash64 对应的 indexId（hash64 必须为 8 字节）。
     *
     * @return indexId，不存在返回 null
     */
    public Long get(byte[] hash64) throws IOException {
        if (hash64 == null || hash64.length != HASH_LEN) {
            return null;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN; level++) {
            int slot = hash64[level] & 0xFF;
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
     * 删除 hash64 对应的映射。
     */
    public boolean remove(long hash64) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeLong(b, 0, hash64);
        return remove(b);
    }

    /**
     * 删除 hash64 对应的映射（hash64 必须为 8 字节）。
     *
     * <p>当叶子是 STATE_VALUE_CHILD 时，删除只清空 value 并把状态退化为 STATE_CHILD（保留 child 信息）。</p>
     */
    public boolean remove(byte[] hash64) throws IOException {
        if (hash64 == null || hash64.length != HASH_LEN) {
            return false;
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = hash64[level] & 0xFF;
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
     * 读取 hash64 叶子 slot 的 childLevel（用于 tiered 升级路由）。
     */
    public int getChildLevel(long hash64) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeLong(b, 0, hash64);
        return getChildLevel(b);
    }

    /**
     * 读取 hash64 叶子 slot 的 childLevel（用于 tiered 升级路由）。
     */
    public int getChildLevel(byte[] hash64) throws IOException {
        long pos = locateLeaf(hash64, false);
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
     * 在 hash64 的叶子 slot 上写入 childLevel（用于 tiered 升级路由）。
     */
    public void setChildLevel(long hash64, int childLevel) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeLong(b, 0, hash64);
        setChildLevel(b, childLevel);
    }

    /**
     * 在 hash64 的叶子 slot 上写入 childLevel（用于 tiered 升级路由）。
     *
     * <p>如果叶子原本是 STATE_VALUE，会被提升为 STATE_VALUE_CHILD，并把 childLevel 写入 payload[8..15]。</p>
     */
    public void setChildLevel(byte[] hash64, int childLevel) throws IOException {
        if (hash64 == null || hash64.length != HASH_LEN) {
            throw new IllegalArgumentException("hash64 length must be 8");
        }
        opLock.lock();
        try {
            long pos = locateLeaf(hash64, true);
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
            throw new IOException("no value at hash64 leaf");
        } finally {
            opLock.unlock();
        }
    }

    private long allocateNodeId() throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = nextNodeId;
            nextNodeId++;
            headerBuffer.putLong(8, nextNodeId);
            dirty(0L);
            return id;
        } finally {
            headerOpLockWrite.unlock();
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

    private long locateLeaf(byte[] hash64, boolean createPath) throws IOException {
        if (hash64 == null || hash64.length != HASH_LEN) {
            return -1;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN - 1; level++) {
            int slot = hash64[level] & 0xFF;
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
        int leafSlot = hash64[HASH_LEN - 1] & 0xFF;
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
