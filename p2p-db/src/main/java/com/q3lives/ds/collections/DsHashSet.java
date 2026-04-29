package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsDataUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.LongConsumer;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class DsHashSet extends DsObject implements Set<Long> {

    private static final byte[] MAGIC = new byte[]{'.', 'S', 'E', 'T'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;//存储层升级。

    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 8;

    private static final int DEFAULT_QUICK_HASH_CACHE_SIZE = Math.max(8, Integer.getInteger("ds.hashset.quickCacheSize", 256));
    
    /**
     * Trie 深度（8 层，每层 1 字节）
     */
    private static final int HASH_DEPTH = 8;

    /**
     * 最后一层索引
     */
    private static final int HASH_END = HASH_DEPTH - 1;

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;
/**
 * 动态哈希调整。
 */
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
    
    //nodeid 路径映射,保存每一个nodeID的路径信息,用于路径空洞和意外断链的bug
    private final Map<Long, DsHashPath> debugInfoMap = new HashMap<>();
    private final boolean debugPaths = Boolean.getBoolean("ds.hashset.debugPaths");
   
   

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
            for (int i = 0; i < path.level(); i++) {
                if (path.path()[i] != hashes[i]) {
                    return false;
                }
            }
            return true;
        }
    }
    
  
    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsHashSet(File file) {
        this(file, DEFAULT_QUICK_HASH_CACHE_SIZE);
    }

    public DsHashSet(File file, int quickCacheSize) {
        super(file, HEADER_SIZE, 2112);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*8-byte(value) = 64+2048 =2112
        zeroNode = new long[this.dataUnitSize / 8];
        setQuickCacheSizeInternal(quickCacheSize, false);
        initHeader();
    }

    public void setQuickCacheSize(int quickCacheSize) {
        setQuickCacheSizeInternal(quickCacheSize, true);
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                nextNodeId = headerBuffer.getLong(HDR_NEXT_NODE_ID);
                size = headerBuffer.getLong(HDR_SIZE);
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);//value size
            nextNodeId = 1;
            size = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
            loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * 线程局部变量
     */
    private static final ThreadLocal<byte[]> HASHBYTES = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);
    //private static final ThreadLocal<byte[]> HASHBYTES2 = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);

    public boolean add(long key) throws IOException {
        return put(hashPutBytes(key), key);
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
        writeLock.lock();
        try {
            FastPutCache fastCache = findFastPutCache(hashes);
            if (fastCache != null) {//缓存命中，快速跳转。
                return putInner(fastCache.nodeId, hashes, key, fastCache.path.level(), true);
            }
            long nodeId = 0;
            for (int level = 0; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_END) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadLongOffset(valuePos(nodeId, slot));
                            recordPath(child, level + 1, hashes);
                            nodeId = child;
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
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        writeState(nodeId, slot, STATE_VALUE);
                        storeLongOffset(valuePos(nodeId, slot), key);
                        validateReachable(key);
                        return true;
                    }
                    case STATE_VALUE -> {

                        long oldKey = loadLongOffset(valuePos(nodeId, slot));

                        if (oldKey == key) {

                            return false;
                        }
                        log.error("put invalid state: " + state + " *** " + key + " ->" + nodeId + ":" + slot);
                        //这是最后一层,理论上不会达到这里
                        //   throw new IOException("invalid state: " + state);

                    }
                    default -> {
                        log.error("put invalid state: " + state + " *** " + key + " ->" + nodeId + ":" + slot);
                        //throw new IOException("invalid state: " + state);
                    }
                }

            }

            return false;
        } finally {
            writeLock.unlock();
        }
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
        for (int level = currentLevel; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < hashEnd) {
                switch (state) {
                    case STATE_CHILD -> {
                        long child = loadLongOffset(valuePos(nodeId, slot));
                        recordPath(child, level + 1, hashes);
                        nodeId = child;
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
                            dirty(0L);
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
                default ->
                     log.error("putinner invalid state: " + state+" *** " + key + " ->" + nodeId + ":" + slot);
                    //throw new IOException("invalid state: " + state);
            }
        }
        return false;
    }
    /**
     * 生成put专用的哈希slots。
     * @param key
     * @return 
     */
    private byte[] hashPutBytes(long key) {
        byte[] b = HASHBYTES.get();
        DsDataUtil.storeLong(b, hashOffset, key);
        return b;
    }

    /**
     * 生成递归用途的哈希slots。
     * @param key
     * @return 
     */
    private byte[] hashBytes(long key) {
        byte[] b = new byte[HASH_DEPTH];
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
        FastPutCache cache = lastPutCache;
        if (cache != null && cache.matches(hashes)) {
            if (isFastPutNodeReachable(cache.nodeId, cache.path.level(), hashes)) {
                fastPutLastHitCount++;
                return cache;
            }
            fastPutRejectedCount++;
            if (lastPutCache == cache) {
                lastPutCache = null;
            }
        }
        if (quickHashSize <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            fastPutMissCount++;
            return null;
        }
        long hash64 = DsDataUtil.loadLong(hashes, hashOffset);
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
        return best;
    }

    private void updateQuickHashCache(long nodeId, int level, byte[] hashes) {
        if (nodeId <= 0 || level <= 0 || hashes == null || hashes.length != HASH_DEPTH) {
            return;
        }
        long hash64 = DsDataUtil.loadLong(hashes, hashOffset);
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

    public Map<String, Object> debugDumpMap(long key) throws IOException {
        byte[] hash64 = fullHashPath(key, 0).path();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", key);
        List<Map<String, Object>> levels = new ArrayList<>();
        long nodeId = 0;
        for (int level = 0; level < hashLen; level++) {
            int slot = hash64[level] & 0xFF;
            int state = readState(nodeId, slot);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("node", nodeId);
            row.put("slot", slot);
            row.put("state", state);
            if (level < hashEnd && state == STATE_CHILD) {
                long child = loadLongOffset(valuePos(nodeId, slot));
                row.put("child", child);
                DsHashPath p = debugInfoMap.get(child);
                if (p != null) {
                    row.put("pathLevel", p.level());
                    int[] slots = new int[HASH_DEPTH];
                    for (int i = 0; i < HASH_DEPTH; i++) {
                        slots[i] = p.path()[i] & 0xFF;
                    }
                    //row.put("pathSlots", slots);
                }
                levels.add(row);
                nodeId = child;
                continue;
            }
            if (state == STATE_VALUE) {
                row.put("value", loadLongOffset(valuePos(nodeId, slot)));
            }
            levels.add(row);
            break;
        }
        out.put("levels", levels);
        return out;
    }

    public String debugDumpJson(long key) throws IOException {
        return toJson(debugDumpMap(key));
    }

    private static String toJson(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof int[] a) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < a.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a[i]);
            }
            sb.append(']');
            return sb.toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String)) {
                    continue;
                }
                if (!first) sb.append(',');
                first = false;
                sb.append(toJson(e.getKey()));
                sb.append(':');
                sb.append(toJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toJson(list.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
    
    public static String hashToString(byte[] hashes) {
        StringBuilder sb = new StringBuilder(hashes.length + " byte hash:");
        for (int i = 0; i < hashes.length; i++) {
            int x = hashes[i] & 0xff;
            sb.append(Integer.toHexString(x));
        }
        return sb.toString();
    }
    
    public static void main(String[] args) {
        byte[] b = new byte[HASH_DEPTH];
        DsDataUtil.storeLong(b, 0, 1);
        System.out.println(hashToString(b));
        b = new byte[HASH_DEPTH];
        DsDataUtil.storeLong(b, 0, 257);
        System.out.println(hashToString(b));
    }

    /**
     * 查询 hash64 对应的 value。
     *
     * @param key
     * @return
     * @throws java.io.IOException
     */
    public Long get(long key) throws IOException {
        byte[] hashes = hashBytes(key);
        FastPutCache fastCache = findFastPutCache(hashes);
        if (fastCache != null) {//缓存命中，快速跳转。
            return getInner(fastCache.nodeId, hashes, key, fastCache.path.level());
        }
        return get(hashes);
    }

    /**
     * 查询 hash64 对应的 value（hash64 必须为 8 字节）。
     *
     * @param hashes
     * @return value，不存在返回 null
     * @throws java.io.IOException
     */
    public Long get(byte[] hashes) throws IOException {
        readLock.lock();
        try {
            long nodeId = 0;
            for (int level = 0; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashEnd) {
                    if (state == STATE_CHILD) {
                        nodeId = loadLongOffset(valuePos(nodeId, slot));
                        continue;//使用下一个哈希 level++ -> slot。
                    } else if (state == STATE_VALUE) {
                        return loadLongOffset(valuePos(nodeId, slot));
                    }
                    return null;
                } else if (STATE_VALUE == state) {//最后一层。
                    return loadLongOffset(valuePos(nodeId, slot));
                }
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }
    
    private Long getInner(long startNodeId, byte[] hashes, long key, int currentLevel) throws IOException {
        long nodeId = startNodeId;
        for (int level = currentLevel; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_END) {
                if (state == STATE_CHILD) {
                    nodeId = loadLongOffset(valuePos(nodeId, slot));
                    continue;//使用下一个哈希 level++ -> slot。
                } else if (state == STATE_VALUE) {
                    long oldKey = loadLongOffset(valuePos(nodeId, slot));
                    if (oldKey == key) {
                        return key;
                    }
                }
                return null;
            } else if (STATE_VALUE == state) {//最后一层。
                long oldKey = loadLongOffset(valuePos(nodeId, slot));
                if (oldKey == key) {
                    return key;
                }
            }
        }
        return null;
    }

    public Long getByNodeId(long nodeId, int slot) {
        return loadLongOffset(valuePos(nodeId, slot));
    }

    /**
     * 删除 key 对应的映射。
     *
     * @param key
     * @return
     * @throws java.io.IOException
     */
    public boolean remove(long key) throws IOException {
        byte[] hashes = hashBytes(key);
        FastPutCache fastCache = findFastPutCache(hashes);
        if (fastCache != null) {//缓存命中，快速跳转。
            return removeInner(fastCache.nodeId, hashes, key, fastCache.path.level());
        }
        return remove(key, hashes);
    }
    
    private boolean removeInner(long startNodeId, byte[] hashes, long key, int currentLevel) throws IOException {
        long nodeId = startNodeId;
        for (int level = currentLevel; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_END) {
                if (state == STATE_CHILD) {
                    nodeId = loadLongOffset(valuePos(nodeId, slot));
                    continue;//使用下一个哈希 level++ -> slot。
                } else if (state == STATE_VALUE) {
                    long oldKey = loadLongOffset(valuePos(nodeId, slot));
                    if(oldKey == key){
                        return true;
                    }
                }
                return false;
            } else if (STATE_VALUE == state) {//最后一层。
                long oldKey = loadLongOffset(valuePos(nodeId, slot));
                    if(oldKey == key){
                        return true;
                    }
            }
        }
        return false;
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
        writeLock.lock();
        try {
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
                                    dirty(0L);
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
                                dirty(0L);
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
                            log.error("removing invalid state: " + state + " *** " + key + " ->" + nodeId + ":" + slot);
                        }
                    }
                    return false;
                }
                return false;
            } finally {
                unlockBuffer(bufIdx);
            }
        } finally {
            writeLock.unlock();
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
        readLock.lock();
        try {
            if (size > Integer.MAX_VALUE) {
                throw new RuntimeException("total elements = " + size + ",over Integer.MAX_VALUE! please use total()");
            }
            return (int) size;
        } finally {
            readLock.unlock();
        }
    }

    public long total() {
        readLock.lock();
        try {
            return size;
        } finally {
            readLock.unlock();
        }
    }

    /**
     * 同步并关闭索引。
     */
    public void close() {
        sync();
    }

    private long allocateNodeId() throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = nextNodeId;
            nextNodeId++;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            dirty(0L);
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

    public int readState(long nodeId, int slot) {
        try {
            long pos = bitmapPos(nodeId, slot);

            int stateByte = loadU8ByOffset(pos);
            int stateValue = getStateValue(stateByte, slot % 4);

            return stateValue;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
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
        try {
            long pos = bitmapPos(nodeId, slot);
            int stateByte = loadU8ByOffset(pos);
            byte stateValue = (byte) setStateValue(stateByte, slot % 4, state);
            storeByteOffset(pos, stateValue);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public boolean isEmpty() {
        readLock.lock();
        try {
            return size == 0;
        } finally {
            readLock.unlock();
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
     * 写入 hash64(8B) -> value 映射。
     *
     * <p>
     * 实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     *
     * @param key
     * @throws java.io.IOException
     */
    private Long scanInner(long startNodeId, int currentLevel) throws IOException {
        final long[] out = new long[1];
        boolean found = traverseOrdered(startNodeId, currentLevel, true, value -> {
            out[0] = value;
            return true;
        });
        return found ? out[0] : null;
    }
    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @param value
     * @return
     */
    public long indexOf(long value) {
        try {
            byte[] hashes = fullHashPath(value, 0).path();
            TraversalContext ctx = new TraversalContext();
            return indexOfOrdered(0, 0, value, hashes, ctx, 0L);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @param value
     * @return
     */
    public long lastIndexOf(long value) {
        return indexOf(value);
    }
    
    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @param index
     * @return
     */
    public Long getByIndex(long index) {
        try {
            if (index < 0) {
                return null;
            }
            TraversalContext ctx = new TraversalContext();
            long[] remaining = new long[]{index};
            return getByIndexOrdered(0, 0, remaining, ctx);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
    
    /**
     * 获取Key哈希值自然排序的第1个元素。
     *
     * @param start
     * @param count
     * @return
     */
    public List<Long> range(long start,int count) {
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
            TraversalContext ctx = new TraversalContext();
            long[] skip = new long[]{start};
            int[] emitted = new int[1];
            collectRangeOrdered(0, 0, skip, count, value -> {
                consumer.accept(value);
                return false;
            }, emitted, ctx);
            return emitted[0];
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
        writeLock.lock();
        try {
            long nodeId = 0;
            for (int slot = 0; slot < 256; slot++) {
                //int state = readState(nodeId, slot);
                //if (state == STATE_EMPTY) {
                //    continue;
                //}
                writeState(nodeId, slot, STATE_EMPTY);
                //storeLongOffset(valuePos(nodeId, slot), 0L);
            }
            nextNodeId = 1;
            size = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
            debugInfoMap.clear();
            resetQuickHashCache(true);
        } finally {
            writeLock.unlock();
        }

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

    private void fillArray(Long[] vs) {
        if (size > vs.length) {
            throw new RuntimeException("total elements = " + size + ",over var[0].length -> " + vs.length);
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

    

    @Override
    public Iterator<Long> iterator() {
        return new Iterator<Long>() {
            private static final int BATCH_SIZE = 64;
            private final long[] nodeStack = new long[hashLen];
            private final int[] nextSlotIndex = new int[hashLen];
            private final long[] batchBuffer = new long[BATCH_SIZE];
            private final byte[] bitmap = new byte[BITMAP_BYTES];
            private final long[] values = new long[256];
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
                DsHashSet.this.remove(current);
                current = null;
            }

            private boolean refill() {
                readLock.lock();
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
                        DsHashSet.this.loadNode(nodeId, bitmap, values);
                        int[] slots = slotOrder(level, true);

                        for (;;) {
                            int i = nextSlotIndex[level];
                            if (i >= 256) {
                                break;
                            }
                            int slot = slots[i];
                            nextSlotIndex[level] = i + 1;

                            int state = DsHashSet.this.stateFromBitmap(bitmap, slot);
                            if (state == STATE_EMPTY) {
                                continue;
                            }
                            if (state == STATE_VALUE) {
                                batchBuffer[batchWriteIndex++] = values[slot];
                                if (batchWriteIndex >= BATCH_SIZE) {
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
                    return batchWriteIndex > 0;
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    readLock.unlock();
                }
            }
        };
    }

    private interface LongVisitor {
        boolean visit(long value) throws IOException;
    }

    /**
     * 有序访问会重复统计同一子树，使用原生 long 缓存避免装箱和 HashMap 节点开销。
     */
    private static final class LongCountCache {
        private static final long EMPTY_KEY = 0L;
        private static final long MISSING_VALUE = -1L;
        private long[] keys = new long[64];
        private long[] values = new long[64];
        private int size = 0;
        private int resizeThreshold = 32;

        long get(long key) {
            int mask = keys.length - 1;
            int index = mix(key) & mask;
            while (true) {
                long storedKey = keys[index];
                if (storedKey == EMPTY_KEY) {
                    return MISSING_VALUE;
                }
                if (storedKey == key) {
                    return values[index];
                }
                index = (index + 1) & mask;
            }
        }

        void put(long key, long value) {
            if (key <= 0) {
                throw new IllegalArgumentException("cache key must be positive");
            }
            if (size >= resizeThreshold) {
                resize();
            }
            insert(key, value, keys, values);
        }

        private void resize() {
            long[] oldKeys = keys;
            long[] oldValues = values;
            keys = new long[oldKeys.length << 1];
            values = new long[oldValues.length << 1];
            resizeThreshold = keys.length >>> 1;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                long key = oldKeys[i];
                if (key != EMPTY_KEY) {
                    insert(key, oldValues[i], keys, values);
                }
            }
        }

        private void insert(long key, long value, long[] keyTable, long[] valueTable) {
            int mask = keyTable.length - 1;
            int index = mix(key) & mask;
            while (true) {
                long storedKey = keyTable[index];
                if (storedKey == EMPTY_KEY) {
                    keyTable[index] = key;
                    valueTable[index] = value;
                    size++;
                    return;
                }
                if (storedKey == key) {
                    valueTable[index] = value;
                    return;
                }
                index = (index + 1) & mask;
            }
        }

        private int mix(long value) {
            long mixed = value ^ (value >>> 33) ^ (value >>> 17);
            return (int) mixed;
        }
    }

    private final class TraversalContext {
        final LongCountCache countCache = new LongCountCache();
        final byte[][] bitmaps = new byte[hashLen][BITMAP_BYTES];
        final long[][] valuesStack = new long[hashLen][256];

        byte[] bitmap(int level) {
            return bitmaps[level];
        }

        long[] values(int level) {
            return valuesStack[level];
        }
    }

    private boolean traverseOrdered(long nodeId, int level, boolean ascending, LongVisitor visitor) throws IOException {
        return traverseOrdered(nodeId, level, ascending, visitor, new TraversalContext());
    }

    private boolean traverseOrdered(long nodeId, int level, boolean ascending, LongVisitor visitor, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] values = ctx.values(level);
        loadNode(nodeId, bitmap, values);
        int[] slots = slotOrder(level, ascending);
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                if (visitor.visit(values[slot])) {
                    return true;
                }
                continue;
            }
            if (state == STATE_CHILD && level < hashEnd) {
                long child = values[slot];
                if (child > 0 && traverseOrdered(child, level + 1, ascending, visitor, ctx)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Long getByIndexOrdered(long nodeId, int level, long[] remaining, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] values = ctx.values(level);
        loadNode(nodeId, bitmap, values);
        int[] slots = slotOrder(level, true);
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                if (remaining[0] == 0) {
                    return values[slot];
                }
                remaining[0]--;
                continue;
            }
            if (state == STATE_CHILD && level < hashEnd) {
                long child = values[slot];
                if (child <= 0) {
                    continue;
                }
                long subtreeCount = countSubtree(child, level + 1, ctx);
                if (remaining[0] >= subtreeCount) {
                    remaining[0] -= subtreeCount;
                    continue;
                }
                return getByIndexOrdered(child, level + 1, remaining, ctx);
            }
        }
        return null;
    }

    private long indexOfOrdered(long nodeId, int level, long target, byte[] hashes, TraversalContext ctx, long baseIndex) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] values = ctx.values(level);
        loadNode(nodeId, bitmap, values);
        int[] slots = slotOrder(level, true);
        int targetSlot = hashes[level] & 0xFF;
        long index = baseIndex;
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                if (slot == targetSlot) {
                    return -1;
                }
                continue;
            }
            if (slot == targetSlot) {
                if (state == STATE_VALUE) {
                    return values[slot] == target ? index : -1;
                }
                if (state == STATE_CHILD && level < hashEnd) {
                    long child = values[slot];
                    if (child <= 0) {
                        return -1;
                    }
                    return indexOfOrdered(child, level + 1, target, hashes, ctx, index);
                }
                return -1;
            }
            if (state == STATE_VALUE) {
                index++;
                continue;
            }
            if (state == STATE_CHILD && level < hashEnd) {
                long child = values[slot];
                if (child > 0) {
                    index += countSubtree(child, level + 1, ctx);
                }
            }
        }
        return -1;
    }

    private boolean collectRangeOrdered(long nodeId, int level, long[] skip, int limit, LongVisitor visitor, int[] emitted, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] values = ctx.values(level);
        loadNode(nodeId, bitmap, values);
        int[] slots = slotOrder(level, true);
        for (int slot : slots) {
            if (emitted[0] >= limit) {
                return true;
            }
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                if (skip[0] > 0) {
                    skip[0]--;
                } else {
                    emitted[0]++;
                    if (visitor.visit(values[slot]) || emitted[0] >= limit) {
                        return true;
                    }
                }
                continue;
            }
            if (state == STATE_CHILD && level < hashEnd) {
                long child = values[slot];
                if (child <= 0) {
                    continue;
                }
                long subtreeCount = countSubtree(child, level + 1, ctx);
                if (skip[0] >= subtreeCount) {
                    skip[0] -= subtreeCount;
                    continue;
                }
                if (collectRangeOrdered(child, level + 1, skip, limit, visitor, emitted, ctx)) {
                    return true;
                }
            }
        }
        return emitted[0] >= limit;
    }

    private long countSubtree(long nodeId, int level, TraversalContext ctx) throws IOException {
        long cached = ctx.countCache.get(nodeId);
        if (cached >= 0) {
            return cached;
        }
        byte[] bitmap = ctx.bitmap(level);
        long[] values = ctx.values(level);
        loadNode(nodeId, bitmap, values);
        long count = 0;
        for (int slot = 0; slot < 256; slot++) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_VALUE) {
                count++;
            } else if (state == STATE_CHILD && level < hashEnd) {
                long child = values[slot];
                if (child > 0) {
                    count += countSubtree(child, level + 1, ctx);
                }
            }
        }
        ctx.countCache.put(nodeId, count);
        return count;
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
        fillArray((Long[]) out);
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

    
    // ========================================================================
// 统计和诊断
// ========================================================================
    /**
     * 获取存储空间使用量
     */
    public long getStoreUsed() {
        return nextNodeId * dataUnitSize + headerSize;
    }

    /**
     * 获取节点数量
     */
    public long getNodeCount() {
        return nextNodeId;
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
            System.out.println("Node count: " + nextNodeId);
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
            stats.put("nodeCount", nextNodeId);
            stats.put("storageUsed", getStoreUsed());
            stats.put("averageDepth", getAverageDepth());

            // 计算负载因子
            long maxCapacity = nextNodeId * 256;
            double loadFactor = maxCapacity > 0 ? (double) size / maxCapacity : 0.0;
            stats.put("loadFactor", loadFactor);

            return stats;

        } finally {
            readLock.unlock();
        }
    }

    
    

}
