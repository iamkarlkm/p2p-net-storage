package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsDataUtil;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 *
 * 基于 256-ary Trie 的持久化 Long 集合
 *
 * 主要改进：
 *
 * 修复并发安全问题 修复哈希冲突处理 Bug 优化内存使用和性能 增强错误处理和边界检查 改进迭代器实现 数据结构：
 *
 * 8 层 256-ary Trie（每层 1 字节哈希） 每个节点：64 字节位图 + 2048 字节数据区 位图：256 个 slot，每个 2-bit
 * 状态 数据区：256 个 8-byte long 值
 *
 * @author Fixed Version
 *
 * @version 2.0
 */
public class DsHashSetI64_Fixed extends DsObject implements Set<Long> {

// ========================================================================
// 常量定义
// ========================================================================
    /**
     * 文件头大小
     */
    private static final int HEADER_SIZE = 64;

    /**
     * 节点数据单元大小：64B 位图 + 2048B 数据 = 2112B
     */
    private static final int NODE_SIZE = 2112;

    /**
     * 位图大小：256 slots * 2 bits / 8 = 64 bytes
     */
    private static final int BITMAP_BYTES = 64;

    /**
     * 每个 slot 的数据大小
     */
    private static final int SLOT_PAYLOAD_BYTES = 8;

    /**
     * Trie 深度（8 层，每层 1 字节）
     */
    private static final int HASH_DEPTH = 8;

    /**
     * 最后一层索引
     */
    private static final int HASH_END = HASH_DEPTH - 1;

    /**
     * 哈希偏移量
     */
    private static final int HASH_OFFSET = 0;

    /**
     * Slot 状态常量
     */
    private static final int STATE_EMPTY = 0;        // 00: 空
    private static final int STATE_VALUE = 1;        // 01: 有值
    private static final int STATE_CHILD = 2;        // 10: 子节点
    private static final int STATE_VALUE_CHILD = 3;  // 11: 值+子节点（保留）

    /**
     * 文件头字段偏移
     */
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;

    private final int hashOffset = 0;
    private final int hashLen = 8;
    private final int hashEnd = 7;
    /**
     * 魔数标识
     */
    private static final byte[] MAGIC = {0x44, 0x53, 0x48, 0x53}; // "DSHS"

    /**
     * 哈希掩码（用于快速缓存匹配）
     */
    private static final long[] HASH_MASKS = {
        0xffffffffffffff00L, // level 0: 保留前 7 字节
        0xffffffffffff0000L, // level 1: 保留前 6 字节
        0xffffffffff000000L, // level 2: 保留前 5 字节
        0xffffffff00000000L, // level 3: 保留前 4 字节
        0xffffff0000000000L, // level 4: 保留前 3 字节
        0xffff000000000000L, // level 5: 保留前 2 字节
        0xff00000000000000L, // level 6: 保留前 1 字节
        -1L // level 7: 全部保留
    };

// ========================================================================
// 实例变量
// ========================================================================
    /**
     * 文件头缓冲区
     */
    private MappedByteBuffer headerBuffer;

    /**
     * 下一个可用节点 ID
     */
    private final AtomicLong nextNodeId;

    /**
     * 集合大小
     */
    private  long size;

    /**
     * 零节点（用于快速初始化）
     */
    private final long[] zeroNode;

    /**
     * 全局读写锁
     */
    private final ReentrantReadWriteLock globalLock;
    private final ReentrantReadWriteLock.ReadLock readLock;
    private final ReentrantReadWriteLock.WriteLock writeLock;

    /**
     * 文件头操作锁
     */
    private final ReentrantReadWriteLock headerLock;
    private final ReentrantReadWriteLock.WriteLock headerWriteLock;

    /**
     * 线程局部哈希缓冲区
     */
    private static final ThreadLocal<byte[]> HASH_BUFFER = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);
    private static final ThreadLocal<byte[]> HASH_BUFFER2 = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);

    /**
     * 最近访问缓存（减少重复查找）
     */
    private volatile long lastNodeId = 0;
    private volatile long lastNodeHash = 0;
    private volatile int lastNodeLevel = 0;

// ========================================================================
// 构造函数
// ========================================================================
    /**
     *
     * 创建或打开持久化集合
     *
     * @param file 存储文件
     *
     * @throws IOException 如果文件操作失败
     */
    public DsHashSetI64_Fixed(File file) throws IOException {
        super(file, HEADER_SIZE, NODE_SIZE);

        this.zeroNode = new long[NODE_SIZE / 8];
        this.nextNodeId = new AtomicLong(1);
        this.size = 0;

        this.globalLock = new ReentrantReadWriteLock();
        this.readLock = globalLock.readLock();
        this.writeLock = globalLock.writeLock();

        this.headerLock = new ReentrantReadWriteLock();
        this.headerWriteLock = headerLock.writeLock();

        initHeader();
    }

    /**
     *
     * 初始化文件头
     */
    private void initHeader() throws IOException {
        headerBuffer = loadBuffer(0L);

        byte[] magic = new byte[4];
        headerBuffer.get(HDR_MAGIC, magic, 0, 4);

        if (Arrays.equals(magic, MAGIC)) {
// 已存在的文件，加载元数据
            nextNodeId.set(headerBuffer.getLong(HDR_NEXT_NODE_ID));
            size = 0;
        } else {
// 新文件，初始化
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);
            headerBuffer.putLong(HDR_NEXT_NODE_ID, 1);
            headerBuffer.putLong(HDR_SIZE, 0);

            nextNodeId.set(1);
            size = 0;

            // 初始化根节点（ID = 0）
            initializeNode(0);
        }
    }

    /**
     *
     * 初始化节点（清零）
     */
    private void initializeNode(long nodeId) throws IOException {
        long position = nodeId * dataUnitSize + headerSize;

// 清零位图
        byte[] zeroBitmap = new byte[BITMAP_BYTES];
        writeBytesOffset(position, zeroBitmap, 0, BITMAP_BYTES);

// 清零数据区
        long dataStart = position + BITMAP_BYTES;
        for (int i = 0; i < 256; i++) {
            storeLongOffset(dataStart + (long) i * 8, 0L);
        }
    }

// ========================================================================
// 公共 API - 基本操作
// ========================================================================
    /**
     *
     * 返回集合大小（兼容方法）
     */
    public long total() {
        return size;
    }

    /**
     *
     * 检查集合是否为空
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     *
     * 添加元素到集合
     *
     * @param key 要添加的值
     *
     * @return true 如果元素是新添加的，false 如果已存在
     */
    public boolean add(long key) {
        byte[] hashBytes = HASH_BUFFER.get();
        DsDataUtil.storeLong(hashBytes, HASH_OFFSET, key);

        writeLock.lock();
        try {
            return put(hashBytes, key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to add key: " + key, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     *
     * 检查元素是否存在
     *
     * @param key 要检查的值
     *
     * @return true 如果存在
     */
    public boolean contains(long key) {
        byte[] hashBytes = HASH_BUFFER.get();
        DsDataUtil.storeLong(hashBytes, HASH_OFFSET, key);

        readLock.lock();
        try {
            return containsInner(hashBytes, key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to check key: " + key, e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     *
     * 从集合中移除元素
     *
     * @param key 要移除的值
     *
     * @return true 如果元素存在并被移除
     */
    public boolean remove(long key) {
        byte[] hashBytes = HASH_BUFFER.get();
        DsDataUtil.storeLong(hashBytes, HASH_OFFSET, key);

        writeLock.lock();
        try {
            return removeInner(hashBytes, key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove key: " + key, e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     *
     * 清空集合
     */
    public void clear() {
        writeLock.lock();
        try {
// 重新初始化根节点
            initializeNode(0);

            // 重置计数器
            size = 0;
            nextNodeId.set(1);

            // 更新文件头
            updateHeader();

            // 清除缓存
            clearCache();
        } catch (IOException e) {
            throw new RuntimeException("Failed to clear set", e);
        } finally {
            writeLock.unlock();
        }
    }

// ========================================================================
// 内部实现 - 核心算法
// ========================================================================
    
    
     /**
     * 写入 hash64(8B) -> value 映射。
     *
     * <p>
     * 实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     *
     * @param hashes
     * @param key
     * @return
     * @throws java.io.IOException
     */
    public boolean put(byte[] hashes, long key) throws IOException {
//        if (hashes == null || hashes.length != hashLen) {
//            throw new IllegalArgumentException("hashes length must be " + hashLen);
//        }
        if (key == -50000) {
            System.out.println("----");
        }
        if (lastNodeLevel > 0) {//缓存命中，快速跳转。
            long cache = key & HASH_MASKS[lastNodeLevel - 1];
            if (cache == lastNodeHash) {
                return putInner(lastNodeId, hashes, key, lastNodeLevel, true);
            }
        }
        long nodeId = 0;
        for (int level = 0; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_END) {
                switch (state) {
                    case STATE_CHILD -> {
                        nodeId = loadLongOffset(valuePos(nodeId, slot));
                        //继续处理下一个哈希。
                        continue;
                    }
                    case STATE_EMPTY -> {
                        //slot 空
                        headerOpLockWrite.lock();
                        try {
                            size++;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        writeState(nodeId, slot, STATE_VALUE);
                        storeLongOffset(valuePos(nodeId, slot), key);
                        if (level > 2) {
//                            lastNodeId = nodeId;
//                            lastNodeLevel = level;
//                            lastNodeHash = key & HASH_MASKS[lastNodeLevel - 1];
//                                System.out.println("");
                        }
                        return true;
                    }
                    case STATE_VALUE -> {
                        //slot有值,深入下一层
                        long oldKey = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            return false;
                        }
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_CHILD);
                        storeLongOffset(valuePos(nodeId, slot), child);
                        //深入下一层,分别存储两个值。
                        int nextLevel = level + 1;
                        putInner(child, hashBytes(oldKey), oldKey, nextLevel, false);
                        putInner(child, hashes, key, nextLevel, true);
                        return true;
                    }
                }

            }

            switch (state) {
                case STATE_EMPTY -> {
                    headerOpLockWrite.lock();
                    try {
                        size++;
                        headerBuffer.putLong(HDR_SIZE, size);
                        dirty(0L);
                    } finally {
                        headerOpLockWrite.unlock();
                    }
                    writeState(nodeId, slot, STATE_VALUE);
                    storeLongOffset(valuePos(nodeId, slot), key);
                    return true;
                }
                case STATE_VALUE -> {

                    long oldKey = loadLongOffset(valuePos(nodeId, slot));
                    if (key == -50000) {
                        System.out.println("***" + key + " ->" + nodeId + ":" + slot);
                    }
                    if (oldKey == key) {

                        return false;
                    }
                    System.out.println("invalid state: " + state+" *** " + key + " ->" + nodeId + ":" + slot);
                    //这是最后一层,理论上不会达到这里
                    //   throw new IOException("invalid state: " + state);

                }
//                    default -> {
//                        throw new IOException("invalid state: " + state);
//                    }
                }

        }

        return false;
    }

  /**
     * 写入 hash64(8B) -> value 映射。
     *
     * <p>
     * 实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     *
     * @param key
     * @throws java.io.IOException
     */
    private boolean putInner(long startNodeId, byte[] hashes, long key, int currentLevel, boolean countSize) throws IOException {
        long nodeId = startNodeId;
        for (int level = currentLevel; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_END) {
                switch (state) {
                    case STATE_CHILD -> {
                        nodeId = loadLongOffset(valuePos(nodeId, slot));
                        continue;
                    }
                    case STATE_EMPTY -> {
                        if (countSize) {
                            headerOpLockWrite.lock();
                            try {
                                size++;
                                headerBuffer.putLong(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                        }
                        writeState(nodeId, slot, STATE_VALUE);
                        storeLongOffset(valuePos(nodeId, slot), key);
                        return true;
                    }
                    case STATE_VALUE -> {
                        long oldKey = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            return false;
                        }
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_CHILD);
                        storeLongOffset(valuePos(nodeId, slot), child);
                        int nextLevel = level + 1;
                        putInner(child, hashBytes(oldKey), oldKey, nextLevel, false);
                        putInner(child, hashes, key, nextLevel, countSize);
                        return true;
                    }

                }
            }

            switch (state) {
                case STATE_EMPTY -> {
                    if (countSize) {
                        headerOpLockWrite.lock();
                        try {
                            size++;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                    }
                    if (key == -50000) {
                        System.out.println("***" + key + " ->" + nodeId + ":" + slot);
                    }
                    writeState(nodeId, slot, STATE_VALUE);
                    storeLongOffset(valuePos(nodeId, slot), key);
                    return true;
                }
                case STATE_VALUE -> {
                    long oldKey = loadLongOffset(valuePos(nodeId, slot));

                    if (oldKey == key) {
                        return false;
                    }

                }
//                default ->
//                    throw new IOException("invalid state: " + state);
            }
        }
        return false;
    }


    // ========================================================================
// 缓存管理
// ========================================================================
    private void updateCacheInfo(long nodeId, int level, long key) {
        if (level > 0 && level < HASH_DEPTH) {
            lastNodeId = nodeId;
            lastNodeLevel = level;
            lastNodeHash = key & HASH_MASKS[level - 1];
        }
    }

    private void clearCache() {
        lastNodeId = 0;
        lastNodeHash = 0;
        lastNodeLevel = 0;
    }

    public String hsahToString(byte[] hashes) {
        StringBuilder sb = new StringBuilder(hashes.length + " byte hash:");
        for (int i = 0; i < hashes.length; i++) {
            sb.append(Integer.toHexString(i & 0xff));
        }
        return sb.toString();
    }

    /**
     *
     * 内部查找方法
     */
    private boolean containsInner(byte[] hashes, long key) throws IOException {
        long nodeId = 0;

        for (int level = 0; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);

            switch (state) {
                case STATE_EMPTY:
                    return false;

                case STATE_VALUE:
                    long storedKey = loadLongOffset(valuePos(nodeId, slot));
                    return storedKey == key;

                case STATE_CHILD:
                    if (level == HASH_END) {
                        // 最后一层不应该有子节点
                        return false;
                    }
                    nodeId = loadLongOffset(valuePos(nodeId, slot));
                    break;

                default:
                    return false;
            }
        }

        return false;
    }

    /**
     *
     * 内部删除方法
     */
    private boolean removeInner(byte[] hashes, long key) throws IOException {
// 记录路径以便回溯
        long[] path = new long[HASH_DEPTH];
        int[] slots = new int[HASH_DEPTH];

        long nodeId = 0;
        int level = 0;

// 向下查找
        for (; level < HASH_DEPTH; level++) {
            path[level] = nodeId;
            int slot = hashes[level] & 0xFF;
            slots[level] = slot;

            int state = readState(nodeId, slot);

            switch (state) {
                case STATE_EMPTY:
                    return false; // 不存在

                case STATE_VALUE:
                    long storedKey = loadLongOffset(valuePos(nodeId, slot));

                    if (storedKey != key) {
                        return false; // 不匹配
                    }

                    // 找到了，删除
                    writeState(nodeId, slot, STATE_EMPTY);
                    storeLongOffset(valuePos(nodeId, slot), 0L);
                    decrementSize();

                    // 清理空节点（从下往上）
                    cleanupEmptyNodes(path, slots, level);

                    return true;

                case STATE_CHILD:
                    if (level == HASH_END) {
                        return false; // 最后一层不应该有子节点
                    }
                    nodeId = loadLongOffset(valuePos(nodeId, slot));
                    break;

                default:
                    return false;
            }
        }

        return false;
    }

    /**
     *
     * 清理空节点
     */
    private void cleanupEmptyNodes(long[] path, int[] slots, int startLevel) throws IOException {
        for (int level = startLevel; level > 0; level--) {
            long nodeId = path[level];

            // 检查节点是否为空
            if (!isNodeEmpty(nodeId)) {
                break; // 节点不为空，停止清理
            }

            // 删除父节点中的引用
            long parentId = path[level - 1];
            int parentSlot = slots[level - 1];

            writeState(parentId, parentSlot, STATE_EMPTY);
            storeLongOffset(valuePos(parentId, parentSlot), 0L);
        }
    }

    /**
     *
     * 检查节点是否为空
     */
    private boolean isNodeEmpty(long nodeId) throws IOException {
        long bitmapPos = nodeId * dataUnitSize + headerSize;

        for (int i = 0; i < BITMAP_BYTES; i++) {
            byte b = loadByteOffset(bitmapPos + i);
            if (b != 0) {
                return false;
            }
        }

        return true;
    }

// ========================================================================
// 状态和位置计算
// ========================================================================
    /**
     *
     * 读取 slot 状态
     */
    public int readState(long nodeId, int slot) {
        try {
            //        if (slot < 0 || slot >= 256) {
//            throw new IllegalArgumentException("Invalid slot: " + slot);
//        }

long bitmapPos = nodeId * dataUnitSize + headerSize;
int byteIndex = slot / 4;  // 每字节 4 个 slot
int bitOffset = (slot % 4) * 2;  // 每个 slot 2 bits

byte b = loadByteOffset(bitmapPos + byteIndex);
return (b >> bitOffset) & 0x03;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     *
     * 写入 slot 状态
     */
    private void writeState(long nodeId, int slot, int state) throws IOException {
        if (slot < 0 || slot >= 256) {
            throw new IllegalArgumentException("Invalid slot: " + slot);
        }

        if (state < 0 || state > 3) {
            throw new IllegalArgumentException("Invalid state: " + state);
        }

        long bitmapPos = nodeId * dataUnitSize + headerSize;
        int byteIndex = slot / 4;
        int bitOffset = (slot % 4) * 2;

        byte b = loadByteOffset(bitmapPos + byteIndex);

// 清除旧状态
        b &= ~(0x03 << bitOffset);

// 设置新状态
        b |= (state << bitOffset);

        storeByteOffset(bitmapPos + byteIndex, b);
    }

    /**
     *
     * 计算 slot 的值位置
     */
    private long valuePos(long nodeId, int slot) {
        if (slot < 0 || slot >= 256) {
            throw new IllegalArgumentException("Invalid slot: " + slot);
        }

        long nodePos = nodeId * dataUnitSize + headerSize;
        return nodePos + BITMAP_BYTES + (long) slot * SLOT_PAYLOAD_BYTES;
    }

    /**
     *
     * 计算键的哈希字节数组
     */
    private byte[] hashBytes(long key) {
        byte[] b = HASH_BUFFER2.get();
        DsDataUtil.storeLong(b, 0, key);
        return b;
    }
// ========================================================================
// 缓存管理
// ========================================================================

    /**
     *
     * 更新访问缓存
     */
    private void updateCache(long nodeId, int level, long key) {
        if (level > 0 && level < HASH_DEPTH) {
            lastNodeId = nodeId;
            lastNodeLevel = level;
            lastNodeHash = key & HASH_MASKS[level - 1];
        }
    }

   
// ========================================================================
// 节点管理
// ========================================================================

    /**
     *
     * 分配新节点 ID
     */
    private long allocateNodeId() throws IOException {
        long id = nextNodeId.getAndIncrement();

        headerWriteLock.lock();
        try {
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId.get());
            dirty(0L);
        } finally {
            headerWriteLock.unlock();
        }

        return id;
    }

    /**
     *
     * 增加大小计数
     */
    private void incrementSize() throws IOException {
        size ++ ;

        headerWriteLock.lock();
        try {
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
        } finally {
            headerWriteLock.unlock();
        }
    }

    /**
     *
     * 减少大小计数
     */
    private void decrementSize() throws IOException {
        size -- ;

        headerWriteLock.lock();
        try {
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
        } finally {
            headerWriteLock.unlock();
        }
    }

    /**
     *
     * 更新文件头
     */
    private void updateHeader() throws IOException {
        headerWriteLock.lock();
        try {
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId.get());
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
        } finally {
            headerWriteLock.unlock();
        }
    }

    public Long getByNodeId(long nodeId, int slot) {
        return loadLongOffset(valuePos(nodeId, slot));
    }
// ========================================================================
// I/O 辅助方法
// ========================================================================

    /**
     *
     * 读取字节
     */
    private byte loadByteOffset(long position) throws IOException {
        long bufferIndex = bufferIndexFromPosition(position);
        int offset = bufferOffsetFromPosition(position);

        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        return buffer.get(offset);
    }

// ========================================================================
// 迭代器实现
// ========================================================================
   // ========================================================================
// 排序遍历（关键：保持负数->0->正数的顺序）
// ========================================================================

/**

迭代器 - 按整数值排序 遍历顺序： 先遍历 slot 128-255（负数，从小到大） 再遍历 slot 0-127（0和正数，从小到大）
     */
    @Override
    public Iterator<Long> iterator() {
        return new Iterator<Long>() {
            private final int[] rootSlots;
            private final long[] nodeStack;
            private final int[] nextSlotIndex;
            private int level;
            private final ArrayDeque<Long> buffer;
            private final byte[] bitmap;
            private final long[] values;

            private Long next;

            {
                rootSlots = new int[256];
                int p = 0;
                for (int i = 255; i > 127; i--) {
                    rootSlots[p++] = i;
                }
                for (int i = 0; i < 128; i++) {
                    rootSlots[p++] = i;
                }
                nodeStack = new long[hashLen];
                nextSlotIndex = new int[hashLen];
                level = 0;
                nodeStack[0] = 0;
                nextSlotIndex[0] = 0;
                buffer = new ArrayDeque<>(256);
                bitmap = new byte[BITMAP_BYTES];
                values = new long[256];
            }

            @Override
            public boolean hasNext() {
                for (;;) {
                    while (buffer.isEmpty()) {
                        if (!refill()) {
                            return false;
                        }
                    }
                    next = buffer.pollFirst();
                    return next != null;
//                    if (next == null) {
//                        continue;
//                    }
//                    if (contains(next)) {//防止其他线程已删除。
//                        return true;
//                    }
                }
            }

            @Override
            public Long next() {
                if (next == null) {
                    throw new NoSuchElementException();
                }
                return next;
            }

            @Override
            public void remove() {
                if (next == null) {
                    throw new IllegalStateException();
                }
                DsHashSetI64_Fixed.this.remove(next);
            }

            private boolean refill() {
                try {
                    int batch = 64;
                    while (buffer.size() < batch) {
                        if (level < 0) {
                            return !buffer.isEmpty();
                        }
                        if (nextSlotIndex[level] >= 256) {
                            if (level == 0) {
                                level = -1;
                                break;
                            }
                            nextSlotIndex[level] = 0;
                            level--;
                            continue;
                        }

                        long nodeId = nodeStack[level];
                        loadNode(nodeId);

                        for (;;) {
                            int i = nextSlotIndex[level];
                            if (i >= 256) {
                                break;
                            }
                            int slot = level == 0 ? rootSlots[i] : i;
                            nextSlotIndex[level] = i + 1;

                            int state = stateFromBitmap(slot);
                            if (state == STATE_EMPTY) {
                                continue;
                            }
                            if (state == STATE_VALUE) {
                                long v = values[slot];
                                buffer.addLast(v);
                                if (buffer.size() >= batch) {
                                    break;
                                }
                                continue;
                            }
                            if (state == STATE_CHILD) {
                                long child = values[slot];
                                if (child > 0 && level + 1 < hashLen) {
                                    level++;
                                    nodeStack[level] = child;
                                    nextSlotIndex[level] = 0;
                                }
                                break;
                            }
                            throw new IOException("invalid state: " + state);
                        }
                    }
                    return !buffer.isEmpty();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                } finally {
                }
            }

            private void loadNode(long nodeId) throws IOException {
                long base = nodeBase(nodeId);
                loadBytesOffset(base, bitmap);
                loadLongOffset(base + BITMAP_BYTES, values);
            }

            private int stateFromBitmap(int slot) {
                int b = bitmap[slot >>> 2] & 0xFF;
                int index = slot & 3;
                int shift = 0;
                switch (index) {
                    case 0 ->
                        shift = 6;
                    case 1 ->
                        shift = 4;
                    case 2 ->
                        shift = 2;
                }
                return (b >>> shift) & 0x3;
            }
        };
    }

    
    private long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) this.dataUnitSize;
    }

// ========================================================================
// 范围查询（保持原有逻辑）
// ========================================================================

///**
//
//获取第一个元素（最小值） */ public Long first() throws IOException { readLock.lock(); try { Iterator it = iterator(); return it.hasNext() ? it.next() : null; } finally { readLock.unlock(); } }
///**
//
//获取最后一个元素（最大值） */ public Long last() throws IOException { readLock.lock(); try { Long lastValue = null; for (Long value : this) { lastValue = value; } return lastValue; } finally { readLock.unlock(); } }
///**

//按索引获取元素
//*/
public Long getByIndex(long index) throws IOException {
if (index < 0 || index >= size) {
return null;
}

readLock.lock();
try {
long i = 0;
for (Long value : this) {
if (i == index) {
return value;
}
i++;
}
return null;
} finally {
readLock.unlock();
}
}

/**

获取元素的索引 */ public long indexOf(long value) throws IOException { readLock.lock(); try { long index = 0; for (Long v : this) { if (v == value) { return index; } index++; } return -1; } finally { readLock.unlock(); } }
/**

分页查询
*/
public List getPage(long start, int size) throws IOException {
if (start < 0 || size <= 0) {
return Collections.emptyList();
}

readLock.lock();
try {
List result = new ArrayList<>();
long index = 0;
int count = 0;

 for (Long value : this) {
     if (index >= start && count < size) {
         result.add(value);
         count++;
         
         if (count >= size) {
             break;
         }
     }
     index++;
 }
 
 return result;
} finally {
readLock.unlock();
}
}

    @Override
    public boolean contains(Object o) {
        return contains(((Long) o).longValue());
    }

    @Override
    public Object[] toArray() {
        readLock.lock();
        try {
            long currentSize = size;

            if (currentSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("Size exceeds Integer.MAX_VALUE");
            }

            Long[] array = new Long[(int) currentSize];
            int index = 0;
            long nodeId = 0;
            for (int level = 0; level < hashLen; level++) {
                for (int slot = 128; slot < 256; slot++) {
                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            array[index] = loadLongOffset(valuePos(nodeId, slot));
                            index++;
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

            for (int level = 0; level < hashLen; level++) {
                for (int slot = 0; slot < 128; slot++) {
                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            array[index] = loadLongOffset(valuePos(nodeId, slot));
                            index++;
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

            // 如果实际元素少于预期，截断数组
            if (index < array.length) {
                return Arrays.copyOf(array, index);
            }

            return array;

        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <T> T[] toArray(T[] a) {
        readLock.lock();
        try {
            long currentSize = size;

            if (currentSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("Size exceeds Integer.MAX_VALUE");
            }

            Long[] array = (Long[]) a;
            int index = 0;

            for (Long value : this) {
                if (index >= array.length) {
                    break; // 防止并发修改导致越界
                }
                array[index++] = value;
            }

            // 如果实际元素少于预期，截断数组
            if (index < array.length) {
                return (T[]) Arrays.copyOf(array, index);
            }

            return (T[]) array;

        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean add(Long o) {
        return add(((Long) o).longValue());
    }

    @Override
    public boolean remove(Object o) {
        return remove(((Long) o).longValue());
    }

    @Override
    public boolean containsAll(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return false;
        }
        writeLock.lock();
        try {
            for (Object value : values) {
                if (!contains(value)) {
                    return false;
                }
            }
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    public void close() {
        this.sync();
    }

    /**
     *
     * 迭代器实现
     */
    private class HashSetIterator implements Iterator {

        private final long[] nodeStack;
        private final int[] slotStack;
        private final int[] levelStack;
        private int stackTop;

        private final Deque<Long> buffer;
        private Long next;
        private Long lastReturned;

        private final long expectedSize;

        HashSetIterator() {
            this.nodeStack = new long[HASH_DEPTH];
            this.slotStack = new int[HASH_DEPTH];
            this.levelStack = new int[HASH_DEPTH];
            this.stackTop = 0;

            this.buffer = new ArrayDeque<>(256);
            this.expectedSize = size;

            // 初始化：从根节点开始
            nodeStack[0] = 0;
            slotStack[0] = 0;
            levelStack[0] = 0;
            stackTop = 1;

            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Long next() {
            if (next == null) {
                throw new NoSuchElementException();
            }

            lastReturned = next;
            advance();
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }

            checkForComodification();
            DsHashSetI64_Fixed.this.remove(lastReturned);
            lastReturned = null;
        }

        /**
         *
         * 前进到下一个元素
         */
        private void advance() {
            readLock.lock();
            try {
// 先从缓冲区取
                if (!buffer.isEmpty()) {
                    next = buffer.poll();
                    return;
                }

                // 深度优先遍历
                while (stackTop > 0) {
                    stackTop--;
                    long nodeId = nodeStack[stackTop];
                    int startSlot = slotStack[stackTop];
                    int level = levelStack[stackTop];

                    // 遍历当前节点的 slots
                    for (int slot = startSlot; slot < 256; slot++) {
                        int state = readState(nodeId, slot);

                        if (state == STATE_EMPTY) {
                            continue;
                        }

                        if (state == STATE_VALUE) {
                            long value = loadLongOffset(valuePos(nodeId, slot));
                            buffer.add(value);

                            // 如果缓冲区足够，保存状态并返回
                            if (buffer.size() >= 64) {
                                if (slot + 1 < 256) {
                                    nodeStack[stackTop] = nodeId;
                                    slotStack[stackTop] = slot + 1;
                                    levelStack[stackTop] = level;
                                    stackTop++;
                                }

                                next = buffer.poll();
                                return;
                            }
                        } else if (state == STATE_CHILD) {
                            // 保存当前状态
                            if (slot + 1 < 256) {
                                nodeStack[stackTop] = nodeId;
                                slotStack[stackTop] = slot + 1;
                                levelStack[stackTop] = level;
                                stackTop++;
                            }

                            // 压入子节点
                            long childId = loadLongOffset(valuePos(nodeId, slot));
                            nodeStack[stackTop] = childId;
                            slotStack[stackTop] = 0;
                            levelStack[stackTop] = level + 1;
                            stackTop++;

                            break; // 跳出 slot 循环，处理子节点
                        }
                    }

                    // 如果缓冲区有数据，返回
                    if (!buffer.isEmpty()) {
                        next = buffer.poll();
                        return;
                    }
                }

                // 遍历完成
                next = null;

            } catch (Exception e) {
                throw new RuntimeException("Iterator failed", e);
            } finally {
                readLock.unlock();
            }
        }

        private void checkForComodification() {
            if (size != expectedSize) {
                throw new ConcurrentModificationException();
            }
        }
    }

// ========================================================================
// 批量操作
// ========================================================================
    /**
     * 批量添加
     *
     * @param values
     */
    @Override
    public boolean addAll(Collection values) {
        if (values == null || values.isEmpty()) {
            return false;
        }

        writeLock.lock();
        try {
            for (Object value : values) {
                if (value != null) {
                    add((Long) value);
                }
            }
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 批量添加（数组）
     */
    public void addArray(long[] values) {
        if (values == null || values.length == 0) {
            return;
        }

        writeLock.lock();
        try {
            for (long value : values) {
                add(value);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 批
     *
     * @param values
     */
    @Override
    public boolean removeAll(Collection values) {
        if (values == null || values.isEmpty()) {
            return false;
        }

        writeLock.lock();
        try {
            for (Object value : values) {
                if (value != null) {
                    remove(value);
                }
            }
            return true;
        } finally {
            writeLock.unlock();
        }

    }

    /**
     * 保留指定集合中的元素
     *
     * @param values
     */
    @Override
    public boolean retainAll(Collection values) {
        if (values == null) {
            return false;
        }

        writeLock.lock();
        try {
            Set<Long> retainSet = new HashSet<>(values);
            List<Long> toRemove = new ArrayList<>();

            // 收集要删除的元素
            for (Long value : this) {
                if (!retainSet.contains(value)) {
                    toRemove.add(value);
                }
            }

            // 删除
            for (Long value : toRemove) {
                remove(value);
            }

        } finally {
            writeLock.unlock();
        }
        return true;
    }

    /**
     * 转换为数组
     */
    public long[] toArrayLong() {
        readLock.lock();
        try {
            long currentSize = size;

            if (currentSize > Integer.MAX_VALUE) {
                throw new IllegalStateException("Size exceeds Integer.MAX_VALUE");
            }

            long[] array = new long[(int) currentSize];
            int index = 0;

            for (Long value : this) {
                if (index >= array.length) {
                    break; // 防止并发修改导致越界
                }
                array[index++] = value;
            }

            // 如果实际元素少于预期，截断数组
            if (index < array.length) {
                return Arrays.copyOf(array, index);
            }

            return array;

        } finally {
            readLock.unlock();
        }
    }

    /**
     * 转换为 List
     */
    public List<Long> toList() {
        readLock.lock();
        try {
            List<Long> list = new ArrayList<>();

            for (Long value : this) {
                list.add(value);
            }

            return list;

        } finally {
            readLock.unlock();
        }
    }

// ========================================================================
// 范围查询（基于哈希顺序）
// ========================================================================
    /**
     * 获取前 N 个元素（按哈希顺序）
     */
    public List<Long> head(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        readLock.lock();
        try {
            List<Long> result = new ArrayList<>(Math.min(count, (int) size));

            int collected = 0;
            for (Long value : this) {
                if (collected >= count) {
                    break;
                }
                result.add(value);
                collected++;
            }

            return result;

        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取后 N 个元素（按哈希顺序）
     */
    public List<Long> tail(int count) {
        if (count <= 0) {
            return Collections.emptyList();
        }

        readLock.lock();
        try {
            long currentSize = size;

            if (count >= currentSize) {
                return toList();
            }

            // 使用循环缓冲区
            long[] buffer = new long[count];
            int index = 0;
            int total = 0;

            for (Long value : this) {
                buffer[index] = value;
                index = (index + 1) % count;
                total++;
            }

            // 提取最后 count 个元素
            List<Long> result = new ArrayList<>(count);
            int start = total >= count ? index : 0;
            int actualCount = Math.min(total, count);

            for (int i = 0; i < actualCount; i++) {
                result.add(buffer[(start + i) % count]);
            }

            return result;

        } finally {
            readLock.unlock();
        }
    }

    /**
     * 获取指定范围的元素
     */
    public List<Long> range(int start, int count) {
        if (start < 0 || count <= 0) {
            return Collections.emptyList();
        }

        readLock.lock();
        try {
            List<Long> result = new ArrayList<>();
            int index = 0;
            int collected = 0;

            for (Long value : this) {
                if (index >= start && collected < count) {
                    result.add(value);
                    collected++;

                    if (collected >= count) {
                        break;
                    }
                }
                index++;
            }

            return result;

        } finally {
            readLock.unlock();
        }
    }

// ========================================================================
// 统计和诊断
// ========================================================================
    /**
     * 获取存储空间使用量
     */
    public long getStoreUsed() {
        return nextNodeId.get() * dataUnitSize + headerSize;
    }

    /**
     * 获取节点数量
     */
    public long getNodeCount() {
        return nextNodeId.get();
    }

    /**
     * 计算平均深度
     */
    public double getAverageDepth() {
        readLock.lock();
        try {
            if (size == 0) {
                return 0.0;
            }

            long totalDepth = 0;
            long count = 0;

            for (Long value : this) {
                totalDepth += calculateDepth(value);
                count++;
            }

            return (double) totalDepth / count;

        } catch (IOException e) {
            throw new RuntimeException("Failed to calculate average depth", e);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 计算单个元素的深度
     */
    private int calculateDepth(long key) throws IOException {
        byte[] hashes = hashBytes(key);
        long nodeId = 0;

        for (int level = 0; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);

            if (state == STATE_VALUE) {
                return level + 1;
            } else if (state == STATE_CHILD) {
                nodeId = loadLongOffset(valuePos(nodeId, slot));
            } else {
                return -1; // 不存在
            }
        }

        return HASH_DEPTH;
    }

    /**
     * 验证数据结构完整性
     */
    public boolean validate() {
        readLock.lock();
        try {
            // 验证大小
            long calculatedSize = 0;
            for (Long value : this) {
                calculatedSize++;
            }

            if (calculatedSize != size) {
                System.err.println("Size mismatch: expected=" + size
                    + ", actual=" + calculatedSize);
                return false;
            }

            // 验证每个元素都能找到
            for (Long value : this) {
                if (!contains(value)) {
                    System.err.println("Element not found: " + value);
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            System.err.println("Validation failed: " + e.getMessage());
            return false;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 打印调试信息
     */
    public void printDebugInfo() {
        readLock.lock();
        try {
            System.out.println("=== DsHashSetI64 Debug Info ===");
            System.out.println("Size: " + size);
            System.out.println("Node count: " + nextNodeId.get());
            System.out.println("Storage used: " + getStoreUsed() + " bytes");
            System.out.println("Average depth: " + String.format("%.2f", getAverageDepth()));

            // 统计每层的节点数
            long[] levelCounts = new long[HASH_DEPTH];
            countNodesPerLevel(0, 0, levelCounts);

            System.out.println("\nNodes per level:");
            for (int i = 0; i < HASH_DEPTH; i++) {
                System.out.printf("  Level %d: %d nodes%n", i, levelCounts[i]);
            }

            System.out.println("================================");

        } catch (Exception e) {
            System.err.println("Failed to print debug info: " + e.getMessage());
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 递归统计每层节点数
     */
    private void countNodesPerLevel(long nodeId, int level, long[] counts) throws IOException {
        if (level >= HASH_DEPTH) {
            return;
        }

        counts[level]++;

        // 遍历所有 slots
        for (int slot = 0; slot < 256; slot++) {
            int state = readState(nodeId, slot);

            if (state == STATE_CHILD) {
                long childId = loadLongOffset(valuePos(nodeId, slot));
                countNodesPerLevel(childId, level + 1, counts);
            }
        }
    }

    /**
     * 获取统计信息
     */
    public Map<String, Object> getStatistics() {
        readLock.lock();
        try {
            Map<String, Object> stats = new HashMap<>();

            stats.put("size", size);
            stats.put("nodeCount", nextNodeId.get());
            stats.put("storageUsed", getStoreUsed());
            stats.put("averageDepth", getAverageDepth());

            // 计算负载因子
            long maxCapacity = nextNodeId.get() * 256;
            double loadFactor = maxCapacity > 0 ? (double) size / maxCapacity : 0.0;
            stats.put("loadFactor", loadFactor);

            return stats;

        } finally {
            readLock.unlock();
        }
    }

// ========================================================================
// 资源管理
// ========================================================================
// ========================================================================
// 兼容性方法（保持向后兼容）
// ========================================================================
    /**
     * @deprecated 使用 size() 或 total()
     */
    @Deprecated
    public int size() {
        long s = size;
        if (s > Integer.MAX_VALUE) {
            throw new IllegalStateException("Size exceeds Integer.MAX_VALUE");
        }
        return (int) s;
    }

    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @return
     */
    public Long first() {
        long nodeId = 0;
        try {
            for (int level = 0; level < hashLen; level++) {
                for (int slot = 128; slot < 256; slot++) {
                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            return loadLongOffset(valuePos(nodeId, slot));
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

            for (int level = 0; level < hashLen; level++) {
                for (int slot = 0; slot < 128; slot++) {
                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            return loadLongOffset(valuePos(nodeId, slot));
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 获取Key哈希值自然排序的最后1个元素。
     *
     * @return
     */
    public Long last() {
        long nodeId = 0;
        try {
            for (int level = 0; level < hashLen; level++) {
                for (int slot = 127; slot >= 0; slot--) {

                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            return loadLongOffset(valuePos(nodeId, slot));
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

            for (int level = 0; level < hashLen; level++) {
                for (int slot = 128; slot < 256; slot++) {
                    int state = readState(nodeId, slot);
                    switch (state) {

                        case STATE_VALUE -> {
                            return loadLongOffset(valuePos(nodeId, slot));
                        }
                        case STATE_CHILD -> {
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            break;//使用下一个哈希 level++ -> slot。
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            return nexth
//                        }

                    }

                }
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    @Override
    public String toString() {
        return String.format("DsHashSetI64[size=%d, nodes=%d]",
            size, nextNodeId.get());
    }
}
