package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;
import java.util.function.LongConsumer;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsMemory;
import com.q3lives.ds.index.master.DsHash64MasterIndex;
import com.q3lives.ds.util.DsDataUtil;

/**
 * 通用 long->long 映射索引（以 64-bit 哈希值作为 key）。
 *
 * <p>
 * 实现上与 {@link DsHash64MasterIndex} 基本一致，都是 256-ary trie（8 层，每层 1 byte）。</p>
 *
 * <p>
 * 使用场景：</p>
 * <ul>
 * <li>历史/实验性结构：用于在不引入完整 tiered 逻辑时，快速把 hash64 映射到一个 value。</li>
 * <li>slot payload 仍使用 32B，支持 VALUE、CHILD、VALUE_CHILD 三种状态组合。</li>
 * </ul>
 *
 * <p>
 * 注意：</p>
 * <ul>
 * <li>该类名为 DsHashMap，但并不是 Java 集合意义上的 HashMap（没有 put/remove
 * 返回旧值语义，也不支持遍历键值）。</li>
 * <li>它更接近“固定结构的哈希 trie 索引”，只负责 hashKey -> longValue 的映射。</li>
 * </ul>
 */
public class DsMemorySet extends DsMemory implements Set<Long> {

    private static final byte[] MAGIC = new byte[]{'.', 'S', 'E', 'T'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;
    private static final int HDR_CPU_ENDIAN = 24;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;//存储层升级。

    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 8;
    private static final int DEFAULT_QUICK_HASH_CACHE_SIZE = Math.max(8, Integer.getInteger("ds.hashset.quickCacheSize", 256));
    private static final int HASH_DEPTH = 8;
    private static final int CPU_ENDIAN_BIG = 1;
    private static final int CPU_ENDIAN_LITTLE = 2;
    private static final long[] HASH_MASKS = {
        0xffffffffffffff00L,
        0xffffffffffff0000L,
        0xffffffffff000000L,
        0xffffffff00000000L,
        0xffffff0000000000L,
        0xffff000000000000L,
        0xff00000000000000L,
        -1L
    };

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;

    private final int hashOffset = 0;
    private final int hashLen = 8;
    private final int hashEnd = 7;
    private int quickHashCacheCapacity = DEFAULT_QUICK_HASH_CACHE_SIZE;
    private long[] quickHashMasks = new long[DEFAULT_QUICK_HASH_CACHE_SIZE];
    private long[] quickHashNodes = new long[DEFAULT_QUICK_HASH_CACHE_SIZE];
    private int[] quickHashLevels = new int[DEFAULT_QUICK_HASH_CACHE_SIZE];
    private int quickHashSize = 0;
    private int quickHashHead = 0;
    private static final int[] ROOT_ASC_SLOTS = buildRootSlots(true);
    private static final int[] ROOT_DESC_SLOTS = buildRootSlots(false);
    private static final int[] ASC_SLOTS = buildLevelSlots(true);
    private static final int[] DESC_SLOTS = buildLevelSlots(false);
    private FastPutCache lastPutCache;
    private long fastPutLastHitCount = 0;
    private long fastPutQuickHitCount = 0;
    private long fastPutMissCount = 0;
    private long fastPutRejectedCount = 0;
    private long fastPutInvalidatedCount = 0;
    private final Map<Long, DsHashPath> debugInfoMap = new HashMap<>();
    private final boolean debugPaths = Boolean.getBoolean("ds.hashset.debugPaths");

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsMemorySet(File file) {
        this(file, DEFAULT_QUICK_HASH_CACHE_SIZE);
    }

    public DsMemorySet(File file, int quickCacheSize) {
        super(file, HEADER_SIZE,2112);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*8-byte(value) = 64+2048 =2112
        zeroNode = new long[this.dataUnitSize / 8];
        setQuickCacheSizeInternal(quickCacheSize, false);
        if (file != null && file.exists() && file.length() > 0) {
            syncLoad();
        }
        initHeader();
    }

    public void setQuickCacheSize(int quickCacheSize) {
        setQuickCacheSizeInternal(quickCacheSize, true);
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                validateCpuEndian(headerBuffer.getInt(HDR_CPU_ENDIAN));
                nextNodeId = headerBuffer.getLong(HDR_NEXT_NODE_ID);
                size = headerBuffer.getLong(HDR_SIZE);
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);//value size
            headerBuffer.putInt(HDR_CPU_ENDIAN, currentCpuEndianCode());
            nextNodeId = 1;
            size = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_SIZE, size);
            loadBuffer( HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
 /**
     * 线程局部变量
     */
    private static final ThreadLocal<byte[]> HASHBYTES = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);
    private static final ThreadLocal<byte[]> HASHBYTES2 = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);

    public static record DsHashPath(int level, byte[] path) {
        public DsHashPath {
            if (level < 0 || level >= HASH_DEPTH) {
                throw new IllegalArgumentException("invalid hash level");
            }
            if (path == null || path.length != HASH_DEPTH) {
                throw new IllegalArgumentException("invalid hash path");
            }
        }
    }

    public static record FastPutStats(
        long lastHitCount,
        long quickHitCount,
        long missCount,
        long rejectedCount,
        long invalidatedCount,
        int quickCacheSize,
        int quickCacheCapacity
    ) {
    }

    private static final class FastPutCache {
        final long nodeId;
        final DsHashPath path;

        FastPutCache(long nodeId, DsHashPath path) {
            this.nodeId = nodeId;
            this.path = path;
        }

        boolean matches(byte[] hashes) {
            if (nodeId <= 0 || path == null || hashes == null || hashes.length != HASH_DEPTH) {
                return false;
            }
            for (int i = 0; i < path.level(); i++) {
                if (path.path()[i] != hashes[i]) {
                    return false;
                }
            }
            return true;
        }
    }
    
    public boolean add(long key) throws IOException {
        boolean added = put(hashPutBytes(key), key);
        return added;
    }

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
        FastPutCache fastCache = findFastPutCache(hashes);
        if (fastCache != null) {
            return putInner(fastCache.nodeId, hashes, key, fastCache.path.level(), true);
        }
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashEnd) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadLongOffset(valuePos(nodeId, slot));
                            recordPath(child, level + 1, hashes);
                            nodeId = child;
                            long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                            if (nextBuf != bufIdx) {
                                loadBufferForUpdate(nextBuf);
                                unlockBuffer(bufIdx);
                                bufIdx = nextBuf;
                            }
                            //继续处理下一个哈希。
                            continue;
                        }
                        case STATE_EMPTY -> {
                            //slot 空
                            headerOpLockWrite.lock();
                            try {
                                size++;
                                headerBuffer.putLong(HDR_SIZE, size);
                                
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            writeState(nodeId, slot, STATE_VALUE);
                            storeLongOffset(valuePos(nodeId, slot), key);
                            updateLastPutCache(nodeId, level, hashes);
                            validateReachable(key);
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
                            DsHashPath oldPath = fullHashPath(oldKey, nextLevel);
                            debugInfoMap.put(child, oldPath);
                            putInner(child, oldPath.path(), oldKey, nextLevel, false);
                            putInner(child, hashes, key, nextLevel, true);
                            validateReachable(key);
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
                            
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        writeState(nodeId, slot, STATE_VALUE);
                        storeLongOffset(valuePos(nodeId, slot), key);
                        updateLastPutCache(nodeId, level, hashes);
                        validateReachable(key);
                        return true;
                    }
                    case STATE_VALUE -> {

                        long oldKey = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            return false;
                        }
                        //这是最后一层,理论上不会达到这里
                     //   throw new IOException("invalid state: " + state);

                    }
//                    default -> {
//                        throw new IOException("invalid state: " + state);
//                    }
                }

            }
        } finally {
            unlockBuffer(bufIdx);
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
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = currentLevel; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashEnd) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadLongOffset(valuePos(nodeId, slot));
                            recordPath(child, level + 1, hashes);
                            nodeId = child;
                            long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                            if (nextBuf != bufIdx) {
                                loadBufferForUpdate(nextBuf);
                                unlockBuffer(bufIdx);
                                bufIdx = nextBuf;
                            }
                            continue;
                        }
                        case STATE_EMPTY -> {
                            if (countSize) {
                                headerOpLockWrite.lock();
                                try {
                                    size++;
                                    headerBuffer.putLong(HDR_SIZE, size);
                                } finally {
                                    headerOpLockWrite.unlock();
                                }
                            }
                            writeState(nodeId, slot, STATE_VALUE);
                            storeLongOffset(valuePos(nodeId, slot), key);
                            updateLastPutCache(nodeId, level, hashes);
                            validateReachable(key);
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
                            DsHashPath oldPath = fullHashPath(oldKey, nextLevel);
                            debugInfoMap.put(child, oldPath);
                            putInner(child, oldPath.path(), oldKey, nextLevel, false);
                            putInner(child, hashes, key, nextLevel, countSize);
                            validateReachable(key);
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
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                        }
                        writeState(nodeId, slot, STATE_VALUE);
                        storeLongOffset(valuePos(nodeId, slot), key);
                        updateLastPutCache(nodeId, level, hashes);
                        validateReachable(key);
                        return true;
                    }
                    case STATE_VALUE -> {
                        long oldKey = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            return false;
                        }
                    }
                }
            }
            return false;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    private byte[] hashPutBytes(long key) {
        byte[] b = HASHBYTES.get();
        DsDataUtil.storeLong(b, hashOffset, key);
        return b;
    }

    private byte[] hashBytes(long key) {
        byte[] b = HASHBYTES2.get();
        DsDataUtil.storeLong(b, hashOffset, key);
        return b;
    }

    private DsHashPath fullHashPath(long key, int level) {
        byte[] b = new byte[HASH_DEPTH];
        DsDataUtil.storeLong(b, hashOffset, key);
        return new DsHashPath(level, b);
    }

    private void updateLastPutCache(long nodeId, int level, byte[] hashes) {
        if (nodeId <= 0 || level <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            lastPutCache = null;
            return;
        }
        byte[] copy = Arrays.copyOf(hashes, HASH_DEPTH);
        lastPutCache = new FastPutCache(nodeId, new DsHashPath(level, copy));
        updateQuickHashCache(nodeId, level, copy);
    }

    private FastPutCache findFastPutCache(byte[] hashes) {
        if (lastPutCache != null && lastPutCache.matches(hashes)) {
            if (isFastPutNodeReachable(lastPutCache.nodeId, lastPutCache.path.level(), hashes)) {
                fastPutLastHitCount++;
                return lastPutCache;
            }
            fastPutRejectedCount++;
            lastPutCache = null;
        }
        if (quickHashSize <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            fastPutMissCount++;
            return null;
        }
        long hash64 = hashPrefixValue(hashes);
        int bestLevel = -1;
        FastPutCache best = null;
        for (int i = 0; i < quickHashSize; i++) {
            int idx = (quickHashHead - 1 - i + quickHashCacheCapacity) % quickHashCacheCapacity;
            int level = quickHashLevels[idx];
            if (level <= 0 || level <= bestLevel) {
                continue;
            }
            long mask = prefixMaskForLevel(level);
            if ((hash64 & mask) == quickHashMasks[idx]) {
                long nodeId = quickHashNodes[idx];
                if (nodeId > 0 && isFastPutNodeReachable(nodeId, level, hashes)) {
                    bestLevel = level;
                    best = new FastPutCache(nodeId, new DsHashPath(level, Arrays.copyOf(hashes, HASH_DEPTH)));
                    if (level >= hashEnd) {
                        break;
                    }
                } else {
                    fastPutRejectedCount++;
                    invalidateQuickHashEntry(idx);
                }
            }
        }
        if (best == null) {
            fastPutMissCount++;
            return null;
        }
        fastPutQuickHitCount++;
        lastPutCache = best;
        return lastPutCache;
    }

    private void updateQuickHashCache(long nodeId, int level, byte[] hashes) {
        if (nodeId <= 0 || level <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            return;
        }
        long hash64 = hashPrefixValue(hashes);
        long prefix = hash64 & prefixMaskForLevel(level);
        for (int i = 0; i < quickHashSize; i++) {
            if (quickHashLevels[i] == level && quickHashMasks[i] == prefix) {
                quickHashNodes[i] = nodeId;
                return;
            }
        }
        quickHashMasks[quickHashHead] = prefix;
        quickHashNodes[quickHashHead] = nodeId;
        quickHashLevels[quickHashHead] = level;
        quickHashHead = (quickHashHead + 1) % quickHashCacheCapacity;
        if (quickHashSize < quickHashCacheCapacity) {
            quickHashSize++;
        }
    }

    private void invalidateQuickHashEntry(int idx) {
        if (idx < 0 || idx >= quickHashCacheCapacity || quickHashLevels[idx] <= 0) {
            return;
        }
        fastPutInvalidatedCount++;
        quickHashMasks[idx] = 0L;
        quickHashNodes[idx] = 0L;
        quickHashLevels[idx] = 0;
        while (quickHashSize > 0) {
            int tail = (quickHashHead - 1 + quickHashCacheCapacity) % quickHashCacheCapacity;
            if (quickHashLevels[tail] > 0) {
                break;
            }
            quickHashHead = tail;
            quickHashSize--;
        }
    }

    private long prefixMaskForLevel(int level) {
        if (level <= 0) {
            return 0L;
        }
        if (level >= HASH_DEPTH) {
            return -1L;
        }
        return HASH_MASKS[HASH_DEPTH - 1 - level];
    }

    private long hashPrefixValue(byte[] hashes) {
        long value = 0L;
        for (int i = 0; i < HASH_DEPTH; i++) {
            value |= ((long) hashes[i] & 0xFFL) << ((HASH_DEPTH - 1 - i) * 8);
        }
        return value;
    }

    private int currentCpuEndianCode() {
        return ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? CPU_ENDIAN_BIG : CPU_ENDIAN_LITTLE;
    }

    private void validateCpuEndian(int storedEndian) {
        int current = currentCpuEndianCode();
        if (storedEndian != current) {
            throw new IllegalStateException(
                "CPU byte order mismatch: file=" + cpuEndianName(storedEndian) + ", current=" + cpuEndianName(current)
            );
        }
    }

    private String cpuEndianName(int code) {
        return switch (code) {
            case CPU_ENDIAN_BIG -> "BIG_ENDIAN";
            case CPU_ENDIAN_LITTLE -> "LITTLE_ENDIAN";
            default -> "UNKNOWN(" + code + ")";
        };
    }

    public FastPutStats getFastPutStats() {
        return new FastPutStats(
            fastPutLastHitCount,
            fastPutQuickHitCount,
            fastPutMissCount,
            fastPutRejectedCount,
            fastPutInvalidatedCount,
            quickHashSize,
            quickHashCacheCapacity
        );
    }

    public Map<String, Object> getFastPutStatsMap() {
        FastPutStats stats = getFastPutStats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastHitCount", stats.lastHitCount());
        out.put("quickHitCount", stats.quickHitCount());
        out.put("missCount", stats.missCount());
        out.put("rejectedCount", stats.rejectedCount());
        out.put("invalidatedCount", stats.invalidatedCount());
        out.put("quickCacheSize", stats.quickCacheSize());
        out.put("quickCacheCapacity", stats.quickCacheCapacity());
        return out;
    }

    private void setQuickCacheSizeInternal(int quickCacheSize, boolean resetStats) {
        int capacity = Math.max(8, quickCacheSize);
        quickHashCacheCapacity = capacity;
        quickHashMasks = new long[capacity];
        quickHashNodes = new long[capacity];
        quickHashLevels = new int[capacity];
        resetQuickHashCache(resetStats);
    }

    private void resetQuickHashCache(boolean resetStats) {
        lastPutCache = null;
        quickHashSize = 0;
        quickHashHead = 0;
        if (resetStats) {
            fastPutLastHitCount = 0;
            fastPutQuickHitCount = 0;
            fastPutMissCount = 0;
            fastPutRejectedCount = 0;
            fastPutInvalidatedCount = 0;
        }
    }

    private boolean isFastPutNodeReachable(long nodeId, int level, byte[] hashes) {
        if (nodeId <= 0 || level <= 0 || level >= hashLen || hashes == null || hashes.length != HASH_DEPTH) {
            return false;
        }
        long current = 0;
        for (int i = 0; i < level; i++) {
            int slot = hashes[i] & 0xFF;
            if (readState(current, slot) != STATE_CHILD) {
                return false;
            }
            current = loadLongOffset(valuePos(current, slot));
            if (current <= 0) {
                return false;
            }
        }
        return current == nodeId;
    }

    private void recordPath(long nodeId, int level, byte[] hashes) {
        if (!debugPaths || nodeId <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            return;
        }
        debugInfoMap.putIfAbsent(nodeId, new DsHashPath(level, Arrays.copyOf(hashes, HASH_DEPTH)));
    }

    private void validateReachable(long key) throws IOException {
        if (!debugPaths) {
            return;
        }
        Long v = get(key);
        if (v == null || v.longValue() != key) {
            throw new IllegalStateException(debugDump(key));
        }
    }

    public String debugDump(long key) throws IOException {
        byte[] hash64 = fullHashPath(key, 0).path();
        StringBuilder sb = new StringBuilder();
        long nodeId = 0;
        sb.append("key=").append(key).append('\n');
        for (int level = 0; level < hashLen; level++) {
            int slot = hash64[level] & 0xFF;
            int state = readState(nodeId, slot);
            sb.append("level=").append(level).append(" node=").append(nodeId).append(" slot=").append(slot).append(" state=").append(state).append('\n');
            if (level < hashEnd && state == STATE_CHILD) {
                long child = loadLongOffset(valuePos(nodeId, slot));
                DsHashPath p = debugInfoMap.get(child);
                if (p != null) {
                    sb.append("child=").append(child).append(" pathLevel=").append(p.level()).append(" pathSlots=");
                    for (int i = 0; i < HASH_DEPTH; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(p.path()[i] & 0xFF);
                    }
                    sb.append('\n');
                }
                nodeId = child;
                continue;
            }
            if (state == STATE_VALUE) {
                sb.append("value=").append(loadLongOffset(valuePos(nodeId, slot))).append('\n');
            }
            break;
        }
        return sb.toString();
    }

    @Override
    protected long syncByteSize() {
        return headerSize + nextNodeId * (long) dataUnitSize;
    }

    @Override
    public void syncLoad() {
        super.syncLoad();
        resetQuickHashCache(true);
        debugInfoMap.clear();
    }

    /**
     * 查询 hash64 对应的 value。
     *
     * @param key
     * @return
     * @throws java.io.IOException
     */
    public Long get(long key) throws IOException {
        return get(hashBytes(key));
    }

    /**
     * 查询 hash64 对应的 value（hash64 必须为 8 字节）。
     *
     * @param hash64
     * @return value，不存在返回 null
     * @throws java.io.IOException
     */
    public Long get(byte[] hash64) throws IOException {
       
        long nodeId = 0;
        for (int level = 0; level < hashLen; level++) {
            int slot = hash64[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < hashEnd) {
                if (state == STATE_CHILD) {
                    long child = loadLongOffset(valuePos(nodeId, slot));
                    if (child <= 0) {
                        return null;
                    }
                    nodeId = child;
                    continue;//使用下一个哈希 level++ -> slot。
                }

                if (state == STATE_VALUE) {
                    return loadLongOffset(valuePos(nodeId, slot));
                }
                return null;
            }
            if(STATE_VALUE==state){
                return loadLongOffset(valuePos(nodeId, slot));
            }
//            switch (state) {
//                case STATE_VALUE -> {
//                    long v = loadLongOffset(valuePos(nodeId, slot));
//                    return v;
//                }
//                case STATE_NEXT_LEVEL -> {
//                    throw new IOException("invalid state: " + state);
//                }
//                case STATE_CHILD ->
//                    throw new IOException("invalid state: " + state);
//                default -> {
//                }
//            }

        }
        return null;
    }

    /**
     * 删除 key 对应的映射。
     *
     * @param key
     * @return
     * @throws java.io.IOException
     */
    public boolean remove(long key) throws IOException {
        byte[] b = new byte[hashLen];
        DsDataUtil.storeLong(b, 0, key);
        return remove(key, b);
    }

    /**
     * 删除 hash64 对应的映射（hash64 必须为 8 字节）。
     *
     * @param key
     * @param hash64
     * @return
     * @throws java.io.IOException
     */
    public boolean remove(long key, byte[] hash64) throws IOException {
        if (hash64 == null || hash64.length != hashLen) {
            return false;
        }
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hash64[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashEnd) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadLongOffset(valuePos(nodeId, slot));
                            if (child <= 0) {
                                return false;
                            }
                            nodeId = child;
                            long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                            if (nextBuf != bufIdx) {
                                loadBufferForUpdate(nextBuf);
                                unlockBuffer(bufIdx);
                                bufIdx = nextBuf;
                            }
                            continue;
                        }
                        case STATE_EMPTY -> {
                            return false;
                        }
                        case STATE_VALUE -> {
                            headerOpLockWrite.lock();
                            try {
                                size--;
                                headerBuffer.putLong(HDR_SIZE, size);
                                
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            writeState(nodeId, slot, STATE_EMPTY);
                            storeLongOffset(valuePos(nodeId, slot), 0L);
                            return true;
                        }
                      
                    }
                }

                switch (state) {
                    case STATE_VALUE -> {
                        headerOpLockWrite.lock();
                        try {
                            size--;
                            headerBuffer.putLong(HDR_SIZE, size);
                            
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        writeState(nodeId, slot, STATE_EMPTY);
                        storeLongOffset(valuePos(nodeId, slot), 0L);
                        return true;
                    }
                   
                    case STATE_EMPTY -> {
                        return false;
                    }
                    default -> {
                    }
                }
                return false;
            }
            return false;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    /**
     * 返回当前映射条目数 use total()
     *
     * @return
     */
    @Override
    @Deprecated
    public int size() {
        if (size > Integer.MAX_VALUE) {
            throw new RuntimeException("total elements = " + size + ",over Integer.MAX_VALUE! please use total()");
        }
        return (int) size;
    }

    public long total() {
        try {
            return size;
        } finally {
        }
    }

    
    private long allocateNodeId() throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = nextNodeId;
            nextNodeId++;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            
            storeLongOffset(nodeBase(id), zeroNode);
            return id;
        } finally {
            headerOpLockWrite.unlock();
        }

    }

    private long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) this.dataUnitSize;
    }

    private long bitmapPos(long nodeId, int slot) {
        return nodeBase(nodeId) + (slot / 4);
    }

    private long valuePos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * SLOT_PAYLOAD_BYTES;
    }

    private int readState(long nodeId, int slot) {
        
            long pos = bitmapPos(nodeId, slot);
            
            int stateByte = loadU8ByOffset(pos);
            int stateValue = getStateValue(stateByte, slot % 4);
            
            return stateValue;
        
    }

    private static int getStateValue(int data, int index) {
        int shift = 0;
        switch (index) {
            case 0 ->
                shift = 6;
            case 1 ->
                shift = 4;
            case 2 ->
                shift = 2;
        }
        return (data >>> shift) & 0x3;
    }

    private static int setStateValue(int data, int index, int state) {
        int shift = 0;
        switch (index) {
            case 0 ->
                shift = 6;
            case 1 ->
                shift = 4;
            case 2 ->
                shift = 2;
        }
        int mask = ~(0x3 << shift);
        data = data & mask;
        return data | ((state & 0x3) << shift);
    }

    private void writeState(long nodeId, int slot, int state) {
        
            long pos = bitmapPos(nodeId, slot);
            int stateByte = loadU8ByOffset(pos);
            byte stateValue = (byte) setStateValue(stateByte, slot % 4, state);
            storeByteOffset(pos, stateValue);
       
    }

    @Override
    public boolean isEmpty() {
        try {
            return size == 0;
        } finally {
        }
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof Number)) {
            return false;
        }
        try {
            return get(((Number) o).longValue()) != null;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean add(Long e) {
        try {
            if (e == null) {
                throw new NullPointerException();
            }
            return add(e.longValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean remove(Object o) {
        try {
            if (!(o instanceof Number)) {
                return false;
            }
            return remove(((Number) o).longValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

    }

    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @return
     */
    public Long first() {
        try {
            final long[] out = new long[1];
            boolean found = traverseOrdered(0, 0, true, value -> {
                out[0] = value;
                return true;
            });
            return found ? out[0] : null;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 获取Key哈希值自然排序的最后1个元素。
     *
     * @return
     */
    public Long last() {
        try {
            final long[] out = new long[1];
            boolean found = traverseOrdered(0, 0, false, value -> {
                out[0] = value;
                return true;
            });
            return found ? out[0] : null;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void clear() {
        long nodeId = 0;
        for (int slot = 0; slot < 256; slot++) {
            writeState(nodeId, slot, STATE_EMPTY);
        }
        nextNodeId = 1;
        size = 0;
        headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
        headerBuffer.putLong(HDR_SIZE, size);
        debugInfoMap.clear();
        resetQuickHashCache(true);
    }

  

    /**
     * @deprecated Full snapshot output is memory-expensive for large datasets.
     * Prefer `range(start, count)` for paged reads.
     */
    @Deprecated
    @Override
    public Object[] toArray() {
        if (size > Integer.MAX_VALUE) {
            throw new RuntimeException("total elements = " + size + ",over Integer.MAX_VALUE!");
        }
        Long[] vs = new Long[(int) size];
        fillArray(vs);
        return vs;
    }
    
    private void fillArray(Long[] vs){
        if (size > vs.length) {
            throw new RuntimeException("total elements = " + size + ",over var[0].length -> "+vs.length);
        }
        headerOpLockRead.lock();
        try {
            final int[] index = new int[1];
            traverseOrdered(0, 0, true, value -> {
                vs[index[0]++] = value;
                return false;
            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            headerOpLockRead.unlock();
        }
    }
    
   

    /**
     * @deprecated Full snapshot output is memory-expensive for large datasets.
     * Prefer `range(start, count)` for paged reads.
     */
    @Deprecated
    public long[] toArrayLong() {
        if (size > Integer.MAX_VALUE) {
            throw new RuntimeException("total elements = " + size + ",over Integer.MAX_VALUE!");
        }
        long[] out = new long[(int) size];
        headerOpLockRead.lock();
        try {
            final int[] index = new int[1];
            traverseOrdered(0, 0, true, value -> {
                out[index[0]++] = value;
                return false;
            });
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            headerOpLockRead.unlock();
        }
        return out;

    }

    public List<Long> range(long start, int count) {
        List<Long> out = new ArrayList<>(Math.max(0, count));
        forEachRange(start, count, out::add);
        return out;
    }

    public int forEachRange(long start, int count, LongConsumer consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }
        if (count <= 0 || start < 0) {
            return 0;
        }
        try {
            final long[] skipped = new long[]{start};
            final int[] emitted = new int[1];
            headerOpLockRead.lock();
            try {
                traverseOrdered(0, 0, true, value -> {
                    if (skipped[0] > 0) {
                        skipped[0]--;
                        return false;
                    }
                    consumer.accept(value);
                    emitted[0]++;
                    return emitted[0] >= count;
                });
            } finally {
                headerOpLockRead.unlock();
            }
            return emitted[0];
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 获取存储空间大小
     * @return 
     */
    public long getStoreUsed(){
        
        return (nextNodeId-1)*dataUnitSize+HEADER_SIZE;
        
    }
    @Override
    public Iterator<Long> iterator() {
        return new Iterator<Long>() {
            private static final int BATCH_SIZE = 64;
            private final long[] nodeStack = new long[hashLen];
            private final int[] nextSlotIndex = new int[hashLen];
            private final long[] batchBuffer = new long[BATCH_SIZE];
            private int level = 0;
            private int batchReadIndex = 0;
            private int batchWriteIndex = 0;
            private Long current;
            private Long next;

            {
                nodeStack[0] = 0;
                nextSlotIndex[0] = 0;
            }

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                while (batchReadIndex >= batchWriteIndex) {
                    if (!refill()) {
                        return false;
                    }
                }
                next = batchBuffer[batchReadIndex++];
                return next != null;
            }

            @Override
            public Long next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                current = next;
                next = null;
                return current;
            }

            @Override
            public void remove() {
                if (current == null) {
                    throw new IllegalStateException();
                }
                DsMemorySet.this.remove(current);
                current = null;
            }

            private boolean refill() {
                try {
                    batchReadIndex = 0;
                    batchWriteIndex = 0;
                    while (batchWriteIndex < BATCH_SIZE) {
                        if (level < 0) {
                            return batchWriteIndex > 0;
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
                        int[] slots = slotOrder(level, true);
                        for (;;) {
                            int i = nextSlotIndex[level];
                            if (i >= 256) {
                                break;
                            }
                            int slot = slots[i];
                            nextSlotIndex[level] = i + 1;
                            int state = readState(nodeId, slot);
                            if (state == STATE_EMPTY) {
                                continue;
                            }
                            if (state == STATE_VALUE) {
                                batchBuffer[batchWriteIndex++] = loadLongOffset(valuePos(nodeId, slot));
                                if (batchWriteIndex >= BATCH_SIZE) {
                                    break;
                                }
                                continue;
                            }
                            if (state == STATE_CHILD) {
                                long child = loadLongOffset(valuePos(nodeId, slot));
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
                    return batchWriteIndex > 0;
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
    }

    private interface LongVisitor {
        boolean visit(long value) throws IOException;
    }

    private boolean traverseOrdered(long nodeId, int level, boolean ascending, LongVisitor visitor) throws IOException {
        int[] slots = slotOrder(level, ascending);
        for (int slot : slots) {
            int state = readState(nodeId, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                if (visitor.visit(loadLongOffset(valuePos(nodeId, slot)))) {
                    return true;
                }
                continue;
            }
            if (state == STATE_CHILD && level < hashEnd) {
                long child = loadLongOffset(valuePos(nodeId, slot));
                if (child > 0 && traverseOrdered(child, level + 1, ascending, visitor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void loadNode(long nodeId, byte[] bitmap, long[] values) throws IOException {
        long base = nodeBase(nodeId);
        loadBytesOffset(base, bitmap);
        loadLongOffset(base + BITMAP_BYTES, values);
    }

    private int stateFromBitmap(byte[] bitmap, int slot) {
        int b = bitmap[slot >>> 2] & 0xFF;
        int shift = switch (slot & 3) {
            case 0 -> 6;
            case 1 -> 4;
            case 2 -> 2;
            default -> 0;
        };
        return (b >>> shift) & 0x3;
    }

    private static int[] slotOrder(int level, boolean ascending) {
        if (level == 0) {
            return ascending ? ROOT_ASC_SLOTS : ROOT_DESC_SLOTS;
        }
        return ascending ? ASC_SLOTS : DESC_SLOTS;
    }

    private static int[] buildRootSlots(boolean ascending) {
        int[] out = new int[256];
        int p = 0;
        if (ascending) {
            for (int i = 128; i < 256; i++) {
                out[p++] = i;
            }
            for (int i = 0; i < 128; i++) {
                out[p++] = i;
            }
        } else {
            for (int i = 127; i >= 0; i--) {
                out[p++] = i;
            }
            for (int i = 255; i >= 128; i--) {
                out[p++] = i;
            }
        }
        return out;
    }

    private static int[] buildLevelSlots(boolean ascending) {
        int[] out = new int[256];
        if (ascending) {
            for (int i = 0; i < 256; i++) {
                out[i] = i;
            }
        } else {
            for (int i = 0; i < 256; i++) {
                out[i] = 255 - i;
            }
        }
        return out;
    }

    /**
     * @deprecated Full snapshot output is memory-expensive for large datasets.
     * Prefer `range(start, count)` for paged reads.
     */
    @Deprecated
    @Override
    public <T> T[] toArray(T[] out) {
        fillArray((Long[])out);
        return (T[]) out;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends Long> c) {
        boolean changed = false;
        for (Long v : c) {
            if (add(v)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        HashSet<Object> keep = new HashSet<>(c);
        boolean changed = false;
        long[] vs = toArrayLong();
        for (long v : vs) {
            if (!keep.contains(v)) {
                if (remove(Long.valueOf(v))) {
                    changed = true;
                }
            }
        }
        return changed;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        boolean changed = false;
        for (Object o : c) {
            if (remove(o)) {
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public Spliterator<Long> spliterator() {
        return Set.super.spliterator();
    }
    

}
