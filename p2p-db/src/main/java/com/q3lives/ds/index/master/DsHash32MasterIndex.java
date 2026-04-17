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
 * 32-bit 哈希主控索引（hash32 -> indexId），按 4 个 byte 构建 4 层 256-ary trie。
 *
 * <p>数据结构：</p>
 * <ul>
 *   <li>索引文件由多个 node 组成，nodeId 从 0 开始，0 为根节点。</li>
 *   <li>每个 node 固定大小 {@link #NODE_SIZE}：</li>
 * </ul>
 * <pre>
 *   [bitmap 128B] + 256 * [slotPayload 32B]
 * </pre>
 *
 * <p>slot 状态机（写在 bitmap 中）：</p>
 * <ul>
 *   <li>STATE_EMPTY：空</li>
 *   <li>STATE_VALUE：payload[0..7] 为 value(indexId)</li>
 *   <li>STATE_CHILD：payload[0..7] 为 childNodeId（指向下一层 node）</li>
 *   <li>STATE_VALUE_CHILD：payload[0..7] 为 value(indexId)，payload[8..15] 为 child（通常是 childNodeId 或 childLevel）</li>
 * </ul>
 *
 * <p>为什么会出现 STATE_VALUE_CHILD：</p>
 * <ul>
 *   <li>trie 前缀扩展：中间层 slot 已是 VALUE，但插入/查找仍需继续深入（共享前缀），此时把 VALUE 升级成 VALUE_CHILD，保留旧值并挂子节点。</li>
 *   <li>TieredMasterIndex 升级标记：在叶子层，VALUE_CHILD 的 payload[8..15] 会被复用为 childLevel（升级到 hash64/md5/sha256）。</li>
 * </ul>
 *
 * <p>并发：</p>
 * <ul>
 *   <li>所有写操作在 {@link #opLock} 下串行化，保证 node 分配与 bitmap/payload 一致。</li>
 * </ul>
 */
public class DsHash32MasterIndex extends DsObject {
    private static final byte[] MAGIC = new byte[] {'.', 'H', '3', '2'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_VALUE_CHILD = 3;

    private static final int BITMAP_BYTES = 128;
    private static final int SLOT_PAYLOAD_BYTES = 32;
    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;
    private static final int HASH_LEN = 4;

    private final ReentrantLock opLock = new ReentrantLock();

    private long nextNodeId;
    private long size;

    /**
     * 创建一个 hash32 主控索引。
     *
     * <p>file 为索引文件路径；不存在时会初始化 header 与 root node。</p>
     */
    public DsHash32MasterIndex(File file) {
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

    public void put(int hash32, long indexId) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeInt(b, 0, hash32);
        put(b, indexId);
    }

    /**
     * 写入 hash32 -> indexId 映射。
     *
     * <p>hash32 使用 4 层 trie（每层 1 byte）；必要时会创建子节点并进行 VALUE->VALUE_CHILD 的前缀扩展。</p>
     */
    public void put(byte[] hash32, long indexId) throws IOException {
        if (hash32 == null || hash32.length != HASH_LEN) {
            throw new IllegalArgumentException("hash32 length must be 4");
        }
        if (indexId <= 0) {
            throw new IllegalArgumentException("indexId must be positive");
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = hash32[level] & 0xFF;
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
     * 查询 hash32 对应的 indexId。
     */
    public Long get(int hash32) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeInt(b, 0, hash32);
        return get(b);
    }

    /**
     * 查询 hash32 对应的 indexId（hash32 必须为 4 字节）。
     *
     * @return indexId，不存在返回 null
     */
    public Long get(byte[] hash32) throws IOException {
        if (hash32 == null || hash32.length != HASH_LEN) {
            return null;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN; level++) {
            int slot = hash32[level] & 0xFF;
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
     * 删除 hash32 对应的映射。
     */
    public boolean remove(int hash32) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeInt(b, 0, hash32);
        return remove(b);
    }

    /**
     * 删除 hash32 对应的映射（hash32 必须为 4 字节）。
     *
     * <p>当叶子是 STATE_VALUE_CHILD 时，删除只清空 value 并把状态退化为 STATE_CHILD（保留 child 信息）。</p>
     */
    public boolean remove(byte[] hash32) throws IOException {
        if (hash32 == null || hash32.length != HASH_LEN) {
            return false;
        }
        opLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < HASH_LEN; level++) {
                int slot = hash32[level] & 0xFF;
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
     * 读取 hash32 叶子 slot 的 childLevel。
     *
     * <p>约定：childLevel 仅在叶子 slot 处通过 STATE_VALUE_CHILD 的 payload[8..15] 保存。</p>
     * <ul>
     *   <li>childLevel==0：表示未升级（仍然使用本层 VALUE 存储）。</li>
     *   <li>childLevel>0：由 {@link DsTieredMasterIndex} 写入，表示该 hash 桶需要路由到更强的哈希层。</li>
     * </ul>
     *
     * <p>注意：同一个 STATE_VALUE_CHILD 在非叶子层的语义是“value+childNodeId”（trie 前缀扩展），
     * 只有 locateLeaf 定位到叶子后，这里的 payload[8..15] 才被解释为 childLevel。</p>
     */
    public int getChildLevel(int hash32) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeInt(b, 0, hash32);
        return getChildLevel(b);
    }

    public int getChildLevel(byte[] hash32) throws IOException {
        // locateLeaf 返回 (nodeId<<8)|slot 的编码位置；找不到则返回负数
        long pos = locateLeaf(hash32, false);
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
     * 在 hash32 的叶子 slot 上写入 childLevel（用于 tiered 升级路由）。
     *
     * <p>如果叶子原本是 STATE_VALUE，会被提升为 STATE_VALUE_CHILD，并把 childLevel 写入 payload[8..15]。</p>
     */
    public void setChildLevel(int hash32, int childLevel) throws IOException {
        byte[] b = new byte[HASH_LEN];
        DsDataUtil.storeInt(b, 0, hash32);
        setChildLevel(b, childLevel);
    }

    public void setChildLevel(byte[] hash32, int childLevel) throws IOException {
        if (hash32 == null || hash32.length != HASH_LEN) {
            throw new IllegalArgumentException("hash32 length must be 4");
        }
        opLock.lock();
        try {
            // mustCreate=true：如果中间层节点不存在则创建，以确保叶子 slot 可写
            long pos = locateLeaf(hash32, true);
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
            throw new IOException("no value at hash32 leaf");
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

    private long locateLeaf(byte[] hash32, boolean createPath) throws IOException {
        if (hash32 == null || hash32.length != HASH_LEN) {
            return -1;
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_LEN - 1; level++) {
            int slot = hash32[level] & 0xFF;
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
        int leafSlot = hash32[HASH_LEN - 1] & 0xFF;
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
