package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsFreeRing;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.util.DsDataUtil;
import lombok.extern.slf4j.Slf4j;


/**
 * 通用 long->long 映射索引（以 64-bit 哈希值作为 key）。
 *
 * <p>实现上与 {@link DsHash64MasterIndex} 基本一致，都是 256-ary trie（8 层，每层 1 byte）。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>历史/实验性结构：用于在不引入完整 tiered 逻辑时，快速把 hash64 映射到一个 value。</li>
 *   <li>slot payload 仍使用 32B，支持 VALUE、CHILD、VALUE_CHILD 三种状态组合。</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>该类实现 {@link Map} 接口（key/value 均为 {@link Long}）。</li>
 * </ul>
 */
@Slf4j
public class DsHashMap extends DsObject implements Map<Long, Long> {
    private static final byte[] MAGIC = new byte[] {'.', 'M', 'A', 'P'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;
    private static final int HDR_NEXT_ENTRY_ID = 24;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;//存储层升级。

//    private static final int BITMAP_BYTES = 128;
    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 8;
//    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;
     /**
     * Trie 深度（8 层，每层 1 字节）
     */
    private static final int HASH_DEPTH = 8;
    private static final int HASH_END = 7;

    public enum SyncMode {
        SYSTEM_AUTO,
        WRITE_REQUESTS,
        SECONDS,
        STRONG_100MS,
        MANUAL
    }

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;
    private long nextEntryId;
    
    private int hashOffset = 6;
    private int hashLen = 2;
//    private int hashEnd = 1;
    private int ptrSize = 2;
    private final ThreadLocal<byte[]> hashesLocal1 = ThreadLocal.withInitial(() -> new byte[HASH_DEPTH]);
    
    
    private final DsObject entryStore;
    private final DsFreeRing freeEntryRing;
    
    private final DsHashMap nextHashMap;
    private DsHashMap nextHashMap64;
    private final DsHashMap rootMap;

    private final ReentrantReadWriteLock opLock;
    private final Lock opReadLock;
    private final Lock opWriteLock;

    private volatile SyncMode syncMode = SyncMode.MANUAL;
    private volatile int syncEveryWriteRequests;
    private final AtomicLong writeRequestCounter = new AtomicLong();

    private static final int[] ROOT_ASC_SLOTS = buildRootSlots(true);
    private static final int[] ROOT_DESC_SLOTS = buildRootSlots(false);
    private static final int[] ASC_SLOTS = buildLevelSlots(true);
    private static final int[] DESC_SLOTS = buildLevelSlots(false);

    private interface EntryVisitor {
        boolean visit(long key, long value) throws IOException;
    }

    private long[] sortedKeySnapshot() {
        long[] keys = toKeyArray();
        Arrays.sort(keys);
        return keys;
    }



    private static final class TraversalContext {
        final LongCountCache countCache = new LongCountCache();
        final byte[][] bitmaps;
        final long[][] ptrsStack;

        private TraversalContext(int depth) {
            this.bitmaps = new byte[depth][BITMAP_BYTES];
            this.ptrsStack = new long[depth][256];
        }

        byte[] bitmap(int level) {
            return bitmaps[level];
        }

        long[] ptrs(int level) {
            return ptrsStack[level];
        }
    }

    private static final class LongCountCache {
        private static final long EMPTY_KEY = Long.MIN_VALUE;
        private long[] keyTable = new long[64];
        private long[] valueTable = new long[64];
        private int size = 0;

        private LongCountCache() {
            Arrays.fill(keyTable, EMPTY_KEY);
            Arrays.fill(valueTable, -1L);
        }

        long get(long key) {
            int mask = keyTable.length - 1;
            int index = mix(key) & mask;
            while (true) {
                long storedKey = keyTable[index];
                if (storedKey == EMPTY_KEY) {
                    return -1L;
                }
                if (storedKey == key) {
                    return valueTable[index];
                }
                index = (index + 1) & mask;
            }
        }

        void put(long key, long value) {
            if ((size + 1) * 2 >= keyTable.length) {
                rehash();
            }
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

        private void rehash() {
            long[] oldKeys = keyTable;
            long[] oldValues = valueTable;
            keyTable = new long[oldKeys.length * 2];
            valueTable = new long[oldValues.length * 2];
            Arrays.fill(keyTable, EMPTY_KEY);
            Arrays.fill(valueTable, -1L);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                long k = oldKeys[i];
                if (k != EMPTY_KEY) {
                    put(k, oldValues[i]);
                }
            }
        }

        private int mix(long value) {
            long mixed = value ^ (value >>> 33) ^ (value >>> 17);
            return (int) mixed;
        }
    }
    
    

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsHashMap(File file) {
        super(file, HEADER_SIZE, 64 + 256 * 2);//2字节指针 8位哈希 => 256*2-bit(位图)/8 + 256*2-byte(ptr) = 64+512 =576
        zeroNode = new long[this.dataUnitSize / 8];
        rootMap = this;
        opLock = new ReentrantReadWriteLock();
        opReadLock = opLock.readLock();
        opWriteLock = opLock.writeLock();
        initHeader();
        
        //16位哈希第一级。
        File entry16File = new File(file.getAbsolutePath() + ".e16");
        entryStore = new DsObject(entry16File, 16);
        this.freeEntryRing = openFreeRing(entry16File, 1024);
       
        //先创建64位第三级哈希存储,以便关联到上一级-32位
        File nextHashMap64File = new File(file.getAbsolutePath() + ".m64");
        nextHashMap64 = new DsHashMap(nextHashMap64File, 0, HASH_DEPTH, 8, new File(file.getAbsolutePath() + ".e64"), null, this);

        File nextHashMap32File = new File(file.getAbsolutePath() + ".m32");
        nextHashMap = new DsHashMap(nextHashMap32File, 0, HASH_DEPTH, 4, new File(file.getAbsolutePath() + ".e32"), nextHashMap64, this);
    }
    
    public DsHashMap(File file, int hashOffset, int hashLen, int ptrSize, File entryFile, DsHashMap nextHashMap) {
        this(file, hashOffset, hashLen, ptrSize, entryFile, nextHashMap, null);
    }

    private DsHashMap(File file, int hashOffset, int hashLen, int ptrSize, File entryFile, DsHashMap nextHashMap, DsHashMap rootMap) {
        super(file, HEADER_SIZE, 64 + 256 * ptrSize);
        zeroNode = new long[this.dataUnitSize / 8];
        this.hashOffset = hashOffset;
        this.hashLen = hashLen;
        this.ptrSize = ptrSize;
        this.entryStore = new DsObject(entryFile, 16);
        this.freeEntryRing = openFreeRing(entryFile, 1024);
        this.nextHashMap = nextHashMap;
        this.rootMap = rootMap == null ? this : rootMap;
        ReentrantReadWriteLock shared = this.rootMap == this ? new ReentrantReadWriteLock() : this.rootMap.opLock;
        this.opLock = shared;
        this.opReadLock = shared.readLock();
        this.opWriteLock = shared.writeLock();
        //this.nextHashMap64 = nextHashMap;
        initHeader();
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                int storedPtrSize = headerBuffer.getInt(HDR_VALUE_SIZE);
                if (storedPtrSize != ptrSize) {
                    throw new IllegalStateException("ptrSize mismatch: stored=" + storedPtrSize + ", expected=" + ptrSize);
                }
                nextNodeId = headerBuffer.getLong(HDR_NEXT_NODE_ID);
                size = headerBuffer.getLong(HDR_SIZE);
                nextEntryId = headerBuffer.getLong(HDR_NEXT_ENTRY_ID);
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
            headerBuffer.putInt(HDR_VALUE_SIZE, ptrSize);//pointer size
            nextNodeId = 1;
            size = 0;
            nextEntryId = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_SIZE, size);
            headerBuffer.putLong(HDR_NEXT_ENTRY_ID, nextEntryId);
            dirty(0L);
            storeLongOffset(nodeBase(0L), zeroNode);
            loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Long put(long key, long value) throws IOException {
        return putInternal(key, value, true);
    }

    private Long putInternal(long key, long value, boolean countWriteRequest) throws IOException {
        opWriteLock.lock();
        try {
            byte[] b = hashesLocal1.get();
            DsDataUtil.storeLong(b, 0, key);
            Long old;
            if (key < 0) {
                old = nextHashMap64.put(b, key, value);
            } else if (key < 0xFFFFL) {
                old = put(b, key, value);
            } else if (key < 0xFFFFFFFFL) {
                old = nextHashMap.put(b, key, value);
            } else {
                old = nextHashMap64.put(b, key, value);
            }
            boolean changed = old == null || old.longValue() != value;
            if (countWriteRequest && changed) {
                rootMap.afterWriteRequest();
            }
            return old;
        } finally {
            opWriteLock.unlock();
        }
    }
    
    
    // ========================================================================
// 统计和诊断
// ========================================================================
    /**
     * 获取存储空间使用量
     * @return 
     */
    public long getStoreUsed() {
        long used = 0;
        if(nextHashMap64!=null){
            used = nextHashMap64.getInnerStoreUsed();
        }
        return getInnerStoreUsed()+nextHashMap.getInnerStoreUsed()+used;
    }
    
    private long getInnerStoreUsed() {
        return headerSize
            + nextNodeId * dataUnitSize
            + nextEntryId * entryStore.dataUnitSize+freeEntryRing.capacity();
    }

    public Long first() {
        Iterator<Entry<Long, Long>> it = iterator();
        if (!it.hasNext()) {
            return null;
        }
        return it.next().getKey();
    }

    public Long last() {
        Iterator<Entry<Long, Long>> it = iterator();
        Long last = null;
        while (it.hasNext()) {
            last = it.next().getKey();
        }
        return last;
    }

    public long indexOf(long key) {
        long idx = 0;
        Iterator<Entry<Long, Long>> it = iterator();
        while (it.hasNext()) {
            Entry<Long, Long> e = it.next();
            Long k = e.getKey();
            if (k != null && k.longValue() == key) {
                return idx;
            }
            idx++;
        }
        return -1L;
    }

    public long lastIndexOf(long key) {
        return indexOf(key);
    }

    public Long getByIndex(long index) {
        if (index < 0) {
            return null;
        }
        long idx = 0;
        Iterator<Entry<Long, Long>> it = iterator();
        while (it.hasNext()) {
            Entry<Long, Long> e = it.next();
            if (idx == index) {
                return e.getKey();
            }
            idx++;
        }
        return null;
    }

    public Entry<Long, Long> getEntryByIndex(long index) {
        if (index < 0) {
            return null;
        }
        long idx = 0;
        Iterator<Entry<Long, Long>> it = iterator();
        while (it.hasNext()) {
            Entry<Long, Long> e = it.next();
            if (idx == index) {
                return e;
            }
            idx++;
        }
        return null;
    }

    public List<Entry<Long, Long>> range(long start, int count) {
        if (count <= 0 || start < 0) {
            return new ArrayList<>(0);
        }
        List<Entry<Long, Long>> out = new ArrayList<>(count);
        long idx = 0;
        Iterator<Entry<Long, Long>> it = iterator();
        while (it.hasNext() && idx < start) {
            it.next();
            idx++;
        }
        while (it.hasNext() && out.size() < count) {
            out.add(it.next());
        }
        return out;
    }

    public int forEachRange(long start, int count, LongLongConsumer consumer) {
        if (consumer == null) {
            throw new NullPointerException("consumer");
        }
        if (count <= 0 || start < 0) {
            return 0;
        }
        long idx = 0;
        int emitted = 0;
        Iterator<Entry<Long, Long>> it = iterator();
        while (it.hasNext() && idx < start) {
            it.next();
            idx++;
        }
        while (it.hasNext() && emitted < count) {
            Entry<Long, Long> e = it.next();
            Long k = e.getKey();
            Long v = e.getValue();
            if (k == null || v == null) {
                continue;
            }
            consumer.accept(k.longValue(), v.longValue());
            emitted++;
        }
        return emitted;
    }

    public interface LongLongConsumer {
        void accept(long key, long value);
    }

    private static final class EntryView implements Entry<Long, Long> {
        private final DsHashMap map;
        private final long key;

        private EntryView(DsHashMap map, long key) {
            this.map = map;
            this.key = key;
        }

        @Override
        public Long getKey() {
            return key;
        }

        @Override
        public Long getValue() {
            return map.get(Long.valueOf(key));
        }

        @Override
        public Long setValue(Long value) {
            if (value == null) {
                throw new NullPointerException();
            }
            return map.put(Long.valueOf(key), value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Entry<?, ?> e)) {
                return false;
            }
            return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
        }
    }

    private static final class SnapshotEntry implements Entry<Long, Long> {
        private final DsHashMap map;
        private final long key;
        private long value;

        private SnapshotEntry(DsHashMap map, long key, long value) {
            this.map = map;
            this.key = key;
            this.value = value;
        }

        @Override
        public Long getKey() {
            return key;
        }

        @Override
        public Long getValue() {
            return value;
        }

        @Override
        public Long setValue(Long value) {
            if (value == null) {
                throw new NullPointerException();
            }
            Long old = map.put(Long.valueOf(key), value);
            this.value = value.longValue();
            return old;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Entry<?, ?> e)) {
                return false;
            }
            return Objects.equals(getKey(), e.getKey()) && Objects.equals(getValue(), e.getValue());
        }

        @Override
        public String toString() {
            return "Entry{" + "key=" + key + ", value=" + value + '}';
        }
        
    }
    
        public static String hashToString(byte[] hashes) {
        StringBuilder sb = new StringBuilder(hashes.length + " byte hash:");
        for (int i = 0; i < hashes.length; i++) {
            int x = hashes[i] & 0xff;
            sb.append(Integer.toHexString(x));
        }
        return sb.toString();
    }

    /**
     * 写入 hash64(8B) -> value 映射。
     *
     * <p>实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     * @param hashes
     * @param key
     * @param value
     * @return 
     * @throws java.io.IOException
     */
    public Long put(byte[] hashes,long key, long value) throws IOException {
        ensureHashBytes(hashes, key);
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = hashOffset; level < HASH_DEPTH; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_END) {
                    switch (state) {
                        case STATE_CHILD -> {
                            nodeId = loadPtrOffset(ptrPos(nodeId, slot));
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
                            if (cannotStoreNewEntryId()) {
                                if (nextHashMap == null) {
                                    throw new IOException("entryId overflow without nextHashMap");
                                }
                                writeSlotAsNextLevel(nodeId, slot, 0L);
                                return nextHashMap.putInternal(key, value, false);
                            }
                            long entryId = allocateEntryId(key, value);
                            writeSlotAsValue(nodeId, slot, entryId);
                            headerOpLockWrite.lock();
                            try {
                                size++;
                                headerBuffer.putLong(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            return null;
                        }
                        case STATE_VALUE -> {
                            //slot有值,深入下一层
                            long oldEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                            long oldKey = entryStore.readLong(oldEntryId, 0);
                            long oldValue = entryStore.readLong(oldEntryId, 8);
                            if(oldKey == key){
                                if(oldValue != value){// Key同，值不同，更新。
                                    entryStore.writeLong(oldEntryId, 8, value);
                                }
                                return oldValue;
                            }
                            long child = allocateNodeId();
                            writeSlotAsChild(nodeId, slot, child);
                            //深入下一层,分别存储两个值。
                            int nextLevel = level+1;
                        reinsertExistingEntryId(child, hashBytes(oldKey), oldKey, oldEntryId, nextLevel);
                            putInner(child, hashes, key, value, nextLevel, true);
                            return null;
                        }
//                        case STATE_NEXT_LEVEL -> {
//                            if (nextHashMap == null) {
//                                return null;
//                            }
//                            return nextHashMap.put(key, value);
//                        }
                        default -> {//并不存在这样的bitmap state,如果存在是数据可能损坏 TODO
                            log.error(" [STATE_NEXT_LEVEL] hash level={} -> invalid state:{} " ,level, state);
                            // throw new IOException("invalid state: " + state);
                        }
                    }
                   
                }
                //本级最后一层特殊处理:
                switch (state) {
                    case STATE_EMPTY -> {
                        if (cannotStoreNewEntryId()) {
                            if (nextHashMap == null) {
                                throw new IOException("entryId overflow without nextHashMap");
                            }
                            writeSlotAsNextLevel(nodeId, slot, 0L);
                            return nextHashMap.put(hashes,key, value);
                        }
                        long entryId = allocateEntryId(key, value);
                        writeSlotAsValue(nodeId, slot, entryId);
                        headerOpLockWrite.lock();
                        try {
                            size++;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        return null;
                    }
                    case STATE_VALUE -> {
                        long oldEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                        long oldKey = entryStore.readLong(oldEntryId, 0);
                        long oldValue = entryStore.readLong(oldEntryId, 8);
                        if(oldKey == key){
                            if(oldValue != value){// Key同，值不同，更新。
                                entryStore.writeLong(oldEntryId, 8, value);
                            }
                            return oldValue;
                        }
                        throw new IOException("terminal collision at VALUE slot: storedKey=" + oldKey + ", key=" + key);
                    }
                    case STATE_NEXT_LEVEL -> {
                        //16位key store hashmap 升级到32位key store hashmap 或 32位key store hashmap 升级到64位key store hashmap
                        if (nextHashMap == null) {
                            throw new IOException("collision without nextHashMap");
                        }
                        return nextHashMap.put(hashes,key, value);
                    }
                    default -> {//理论上到了这里只有升级。不会有下一层 STATE_CHILD
                        throw new IOException("invalid state: " + state);
                    }
                }
               
                
            }
        } finally {
            unlockBuffer(bufIdx);
        }
        return null;
    }

    private void ensureHashBytes(byte[] hashes, long key) {
        if (hashes == null || hashes.length < HASH_DEPTH) {
            throw new IllegalArgumentException("hashes length must be >= " + HASH_DEPTH);
        }
        long rebuilt = 0L;
        for (int i = 0; i < HASH_DEPTH; i++) {
            rebuilt = (rebuilt << 8) | (hashes[i] & 0xFFL);
        }
        if (rebuilt != key) {
            // 防止复用数组导致 hash 与 key 不一致
            DsDataUtil.storeLong(hashes, 0, key);
        }
    }

    private void reinsertExistingEntryId(long startNodeId, byte[] hashes, long key, long entryId, int currentLevel) throws IOException {
        long nodeId = startNodeId;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = currentLevel; level < HASH_DEPTH; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_END) {
                    if (state == STATE_CHILD) {
                        nodeId = loadPtrOffset(ptrPos(nodeId, slot));
                        long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                        if (nextBuf != bufIdx) {
                            loadBufferForUpdate(nextBuf);
                            unlockBuffer(bufIdx);
                            bufIdx = nextBuf;
                        }
                        continue;
                    }
                    if (state == STATE_EMPTY) {
                        writeSlotAsValue(nodeId, slot, entryId);
                        return;
                    }
                    if (state == STATE_VALUE) {
                        long otherEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                        long otherKey = entryStore.readLong(otherEntryId, 0);
                        if (otherKey == key) {
                            return;
                        }
                        long child = allocateNodeId();
                        writeSlotAsChild(nodeId, slot, child);
                        int nextLevel = level + 1;
                        reinsertExistingEntryId(child, hashBytes(otherKey), otherKey, otherEntryId, nextLevel);
                        reinsertExistingEntryId(child, hashes, key, entryId, nextLevel);
                        return;
                    }
                    throw new IOException("invalid state: " + state);
                }

                if (state == STATE_EMPTY) {
                    writeSlotAsValue(nodeId, slot, entryId);
                    return;
                }
                if (state == STATE_VALUE) {
                    long existingEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                    long existingKey = entryStore.readLong(existingEntryId, 0);
                    if (existingKey == key) {
                        return;
                    }
                    throw new IOException("terminal collision at VALUE slot: storedKey=" + existingKey + ", key=" + key);
                }
                throw new IOException("invalid state: " + state);
            }
        } finally {
            unlockBuffer(bufIdx);
        }
    }
    
    private Long putInner(long startNodeId, byte[] hashes, long key, long value, int currentLevel, boolean countSize) throws IOException {
        long nodeId = startNodeId;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        for (int level = currentLevel; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < HASH_END) {
                switch (state) {
                    case STATE_CHILD -> {
                        nodeId = loadPtrOffset(ptrPos(nodeId, slot));
                        long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                        if (nextBuf != bufIdx) {
                            loadBufferForUpdate(nextBuf);
                            unlockBuffer(bufIdx);
                            bufIdx = nextBuf;
                        }
                        continue;
                    }
                    case STATE_EMPTY -> {
                        if (cannotStoreNewEntryId()) {
                            if (nextHashMap == null) {
                                throw new IOException("entryId overflow without nextHashMap");
                            }
                            writeSlotAsNextLevel(nodeId, slot, 0L);
                            unlockBuffer(bufIdx);
                            return nextHashMap.putInternal(key, value, false);
                        }
                        long entryId = allocateEntryId(key, value);
                        writeSlotAsValue(nodeId, slot, entryId);
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
                        unlockBuffer(bufIdx);
                        return null;
                    }
                    case STATE_VALUE -> {
                        long oldEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                        long oldKey = entryStore.readLong(oldEntryId, 0);
                        long oldValue = entryStore.readLong(oldEntryId, 8);
                        if (oldKey == key) {
                            if (oldValue != value) {
                                entryStore.writeLong(oldEntryId, 8, value);
                            }
                            unlockBuffer(bufIdx);
                            return oldValue;
                        }
                        long child = allocateNodeId();
                        writeSlotAsChild(nodeId, slot, child);
                        int nextLevel = level + 1;
                        reinsertExistingEntryId(child, hashBytes(oldKey), oldKey, oldEntryId, nextLevel);
                        putInner(child, hashes, key, value, nextLevel, countSize);
                        unlockBuffer(bufIdx);
                        return null;
                    }
//                    case STATE_NEXT_LEVEL -> {
//                        unlockBuffer(bufIdx);
//                        if (nextHashMap == null) {
//                            return null;
//                        }
//                        return nextHashMap.put(key, value);
//                    }
                    default -> {
                        //并不存在这样的bitmap state,如果存在是数据可能损坏 TODO
                        log.error(" [STATE_NEXT_LEVEL] hash level={} -> invalid state:{} " ,level, state);
                    }
                }
            }
             //本级最后一层特殊处理:
            switch (state) {
                case STATE_EMPTY -> {
                    if (cannotStoreNewEntryId()) {
                        if (nextHashMap == null) {
                            throw new IOException("entryId overflow without nextHashMap");
                        }
                        writeSlotAsNextLevel(nodeId, slot, 0L);
                        unlockBuffer(bufIdx);
                        return nextHashMap.put(hashes,key, value);
                    }
                    long entryId = allocateEntryId(key, value);
                    writeSlotAsValue(nodeId, slot, entryId);
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
                    unlockBuffer(bufIdx);
                    return null;
                }
                case STATE_VALUE -> {
                    long oldEntryId = loadPtrOffset(ptrPos(nodeId, slot));
                    long oldKey = entryStore.readLong(oldEntryId, 0);
                    long oldValue = entryStore.readLong(oldEntryId, 8);
                    if (oldKey == key) {
                        if (oldValue != value) {
                            entryStore.writeLong(oldEntryId, 8, value);
                        }
                        unlockBuffer(bufIdx);
                        return oldValue;
                    }
                    throw new IOException("terminal collision at VALUE slot: storedKey=" + oldKey + ", key=" + key);
                }
                case STATE_NEXT_LEVEL -> {
                    unlockBuffer(bufIdx);
                    if (nextHashMap == null) {
                        throw new IOException("collision without nextHashMap");
                    }
                    return nextHashMap.put(hashes,key, value);
                }
                default -> throw new IOException("unknown state: " + state);
            }
        }
        unlockBuffer(bufIdx);
        return null;
    }

    private boolean traverseOrdered(long nodeId, int level, boolean ascending, EntryVisitor visitor) throws IOException {
        return traverseOrdered(nodeId, level, ascending, visitor, new TraversalContext(hashLen));
    }

    private boolean traverseOrdered(long nodeId, int level, boolean ascending, EntryVisitor visitor, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] ptrs = ctx.ptrs(level);
        loadNode(nodeId, bitmap, ptrs);
        int[] slots = slotOrderForDepth(level, ascending);
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                long entryId = ptrs[slot];
                long key = entryStore.readLong(entryId, 0);
                long value = entryStore.readLong(entryId, 8);
                if (visitor.visit(key, value)) {
                    return true;
                }
                continue;
            }
            if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                long child = ptrs[slot];
                if (child > 0 && traverseOrdered(child, level + 1, ascending, visitor, ctx)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean collectRangeOrdered(long nodeId, int level, long[] skip, int limit, EntryVisitor visitor, int[] emitted, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] ptrs = ctx.ptrs(level);
        loadNode(nodeId, bitmap, ptrs);
        int[] slots = slotOrderForDepth(level, true);
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
                    long entryId = ptrs[slot];
                    long key = entryStore.readLong(entryId, 0);
                    long value = entryStore.readLong(entryId, 8);
                    emitted[0]++;
                    if (visitor.visit(key, value) || emitted[0] >= limit) {
                        return true;
                    }
                }
                continue;
            }
            if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                long child = ptrs[slot];
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

    private Long getByIndexOrdered(long nodeId, int level, long[] remaining, TraversalContext ctx) throws IOException {
        byte[] bitmap = ctx.bitmap(level);
        long[] ptrs = ctx.ptrs(level);
        loadNode(nodeId, bitmap, ptrs);
        int[] slots = slotOrderForDepth(level, true);
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                continue;
            }
            if (state == STATE_VALUE) {
                long entryId = ptrs[slot];
                long chainCount = countChain(entryId);
                if (remaining[0] < chainCount) {
                    return pickKeyByIndexFromChain(entryId, remaining);
                }
                remaining[0] -= chainCount;
                continue;
            }
            if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                long child = ptrs[slot];
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
        long[] ptrs = ctx.ptrs(level);
        loadNode(nodeId, bitmap, ptrs);
        int[] slots = slotOrderForDepth(level, true);
        int targetSlot = hashes[level] & 0xFF;
        long index = baseIndex;
        for (int slot : slots) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_EMPTY) {
                if (slot == targetSlot) {
                    return -1L;
                }
                continue;
            }
            if (slot == targetSlot) {
                if (state == STATE_VALUE) {
                    long entryId = ptrs[slot];
                    long inChain = indexInChain(entryId, target);
                    if (inChain < 0) {
                        return -1L;
                    }
                    return index + inChain;
                }
                if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                    long child = ptrs[slot];
                    if (child <= 0) {
                        return -1L;
                    }
                    return indexOfOrdered(child, level + 1, target, hashes, ctx, index);
                }
                return -1L;
            }
            if (state == STATE_VALUE) {
                index += countChain(ptrs[slot]);
                continue;
            }
            if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                long child = ptrs[slot];
                if (child > 0) {
                    index += countSubtree(child, level + 1, ctx);
                }
            }
        }
        return -1L;
    }

    private long countSubtree(long nodeId, int level, TraversalContext ctx) throws IOException {
        long cached = ctx.countCache.get(nodeId);
        if (cached >= 0) {
            return cached;
        }
        byte[] bitmap = ctx.bitmap(level);
        long[] ptrs = ctx.ptrs(level);
        loadNode(nodeId, bitmap, ptrs);
        long count = 0;
        for (int slot = 0; slot < 256; slot++) {
            int state = stateFromBitmap(bitmap, slot);
            if (state == STATE_VALUE) {
                count += countChain(ptrs[slot]);
            } else if (state == STATE_CHILD && level + 1 < HASH_DEPTH) {
                long child = ptrs[slot];
                if (child > 0) {
                    count += countSubtree(child, level + 1, ctx);
                }
            }
        }
        ctx.countCache.put(nodeId, count);
        return count;
    }

    private void loadNode(long nodeId, byte[] bitmap, long[] ptrs) throws IOException {
        long base = nodeBase(nodeId);
        loadBytesOffset(base, bitmap);
        long ptrBase = base + BITMAP_BYTES;
        for (int slot = 0; slot < 256; slot++) {
            long pos = ptrBase + (long) slot * ptrSize;
            ptrs[slot] = loadPtrOffset(pos);
        }
    }

    private long stableReadPtr(long nodeId, int slot, int expectedState) throws IOException {
        long pos = ptrPos(nodeId, slot);
        for (int i = 0; i < 3; i++) {
            int s1 = readState(nodeId, slot);
            if (s1 != expectedState) {
                return -1L;
            }
            long p = loadPtrOffset(pos);
            int s2 = readState(nodeId, slot);
            if (s2 != expectedState) {
                continue;
            }
            return p;
        }
        return -1L;
    }

    private long countChain(long headEntryId) throws IOException {
        return headEntryId < 0 ? 0 : 1;
    }

    private Long pickKeyByIndexFromChain(long headEntryId, long[] remaining) throws IOException {
        long r = remaining[0];
        if (r == 0) {
            remaining[0] = 0;
            return entryStore.readLong(headEntryId, 0);
        }
        remaining[0] = r;
        return null;
    }

    private long indexInChain(long headEntryId, long key) throws IOException {
        return entryStore.readLong(headEntryId, 0) == key ? 0L : -1L;
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

    private int[] slotOrderForDepth(int depth, boolean ascending) {
        if (depth == 0 && hashOffset == 0) {
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
     * 查询 hash64 对应的 value。
     * @param key
     * @return 
     * @throws java.io.IOException 
     */
    public Long get(long key) throws IOException {
        opReadLock.lock();
        try {
            byte[] b = hashesLocal1.get();
           // storeHashBytes(b, hashOffset, key);
            DsDataUtil.storeLong(b, 0, key);
            //三段快速寻址: 
            if(key<0 ){//负数直接跳转64位寻址
                return nextHashMap64.getByHash(b,key);
            }else if(key < 0xFFFFL ){//16位寻址
                 return getByHash(b,key);
            }else if(key<0xFFFFFFFFL){//32位寻址
                return nextHashMap.getByHash(b,key);
            }
            // 直接跳转64位寻址
            return nextHashMap64.getByHash(b,key);
        } finally {
            opReadLock.unlock();
        }
    }

    private Long getByHash(byte[] hashes, long key) throws IOException {
        long nodeId = 0;
        for (int level = hashOffset; level < HASH_DEPTH; level++) {
            int slot = hashes[level] & 0xFF;
            int state;
            if (level < HASH_END) {
                    state = readState(nodeId, slot);
                switch (state) {
                    case STATE_CHILD -> {
                        long child = stableReadPtr(nodeId, slot, STATE_CHILD);
                        if (child < 0) {
                            return null;
                        }
                        nodeId = child;
                        continue;//使用下一个哈希 level++ -> slot。
                    }
                    case STATE_VALUE -> {
                        long entryId = stableReadPtr(nodeId, slot, STATE_VALUE);
                        if (entryId < 0) {
                            return null;
                        }
                        long storedKey = entryStore.readLong(entryId, 0);
                        return storedKey == key ? entryStore.readLong(entryId, 8) : null;
                    }
//                    case STATE_NEXT_LEVEL -> {
//                        if (nextHashMap == null) {
//                            return null;
//                        }
//                        return nextHashMap.get(key);
//                    }
                    default -> {
                    }
                }
               
            }

            state = readState(nodeId, slot);
            if (state == STATE_VALUE) {
                long entryId = stableReadPtr(nodeId, slot, STATE_VALUE);
                if (entryId < 0) {
                    return null;
                }
                long storedKey = entryStore.readLong(entryId, 0);
                return storedKey == key ? entryStore.readLong(entryId, 8) : null;
            }
            if (state == STATE_NEXT_LEVEL) {
                if (nextHashMap == null) {//理论上不会发生，此处应该数据异常
                    return null;
                }
                Long v = nextHashMap.getByHash(hashes,key);
                return v;
            }
            return null;
        }
        return null;
    }

    /**
     * 删除 key 对应的映射。
     * @param key
     * @return 
     * @throws java.io.IOException
     */
    public Long remove(long key) throws IOException {
        return removeInternal(key, true);
    }

    private Long removeInternal(long key, boolean countWriteRequest) throws IOException {
        opWriteLock.lock();
        try {
            byte[] b = hashesLocal1.get();
    //        storeHashBytes(b, hashOffset, key);
             DsDataUtil.storeLong(b, 0, key);
            Long removed;
            if (key < 0) {
                removed = nextHashMap64.remove(key, b);
            } else if (key < 0xFFFFL) {
                removed = remove(key, b);
            } else if (key < 0xFFFFFFFFL) {
                removed = nextHashMap.remove(key, b);
            } else {
                removed = nextHashMap64.remove(key, b);
            }
            if (countWriteRequest && removed!=null) {
                rootMap.afterWriteRequest();
            }
            return removed;
        } finally {
            opWriteLock.unlock();
        }
    }

    /**
     * 删除 hash64 对应的映射（hash64 必须为 8 字节）。
     * @param key
     * @param hash64
     * @return 
     * @throws java.io.IOException
     */
    public Long remove(long key,byte[] hash64) throws IOException {
      
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = hashOffset; level < HASH_DEPTH; level++) {
                int slot = hash64[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < HASH_END) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadPtrOffset(ptrPos(nodeId, slot));
                            if (child <= 0) {
                                return null;
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
                            return null;
                        }
                        case STATE_VALUE -> {
                            long entryId = loadPtrOffset(ptrPos(nodeId, slot));
                            long storedKey = entryStore.readLong(entryId, 0);
                            if (storedKey != key) {
                                return null;
                            }
                            long oldValue = entryStore.readLong(entryId, 8);
                            clearSlot(nodeId, slot);
                            recycleEntryId(entryId);
                            headerOpLockWrite.lock();
                            try {
                                size--;
                                headerBuffer.putLong(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            return oldValue;
                        }
                        default -> throw new IOException("invalid state: " + state);
                    }
                }

                switch (state) {
                    case STATE_VALUE -> {
                        long entryId = loadPtrOffset(ptrPos(nodeId, slot));
                        long storedKey = entryStore.readLong(entryId, 0);
                        if (storedKey != key) {
                            return null;
                        }
                        long oldValue = entryStore.readLong(entryId, 8);
                        clearSlot(nodeId, slot);
                        recycleEntryId(entryId);
                        headerOpLockWrite.lock();
                        try {
                            size--;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        return oldValue;
                    }
                    case STATE_NEXT_LEVEL -> {
                        if (nextHashMap == null) {
                            return null;
                        }
                        return nextHashMap.remove(key,hash64);
                    }
                    case STATE_EMPTY -> {
                        return null;
                    }
                    default -> {
                    }
                }
                return null;
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    /**
     * 返回当前映射条目数
     * @return 
     */
    public long sizeLong() {
        opReadLock.lock();
        try {
            headerOpLockRead.lock();
            try {
                long base = headerBuffer.getLong(HDR_SIZE);
                if (nextHashMap == null) {
                    return base;
                }
                return base + nextHashMap.sizeLong();
            } finally {
                headerOpLockRead.unlock();
            }
        } finally {
            opReadLock.unlock();
        }
    }

    public void setSyncModeSystemAuto() {
        ensureRoot();
        applySyncModeRecursive(SyncMode.SYSTEM_AUTO, 0, 0);
    }

    public void setSyncModeWriteRequests(int writeRequests) {
        if (writeRequests <= 0) {
            throw new IllegalArgumentException("writeRequests must be > 0");
        }
        ensureRoot();
        applySyncModeRecursive(SyncMode.WRITE_REQUESTS, writeRequests, 0);
    }

    public void setSyncModeSeconds(long seconds) {
        if (seconds <= 0) {
            throw new IllegalArgumentException("seconds must be > 0");
        }
        ensureRoot();
        long intervalMs = seconds * 1000L;
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("seconds too large");
        }
        applySyncModeRecursive(SyncMode.SECONDS, 0, intervalMs);
    }

    public void setSyncModeStrong100ms() {
        ensureRoot();
        applySyncModeRecursive(SyncMode.STRONG_100MS, 0, 100L);
    }

    public void disableSyncMode() {
        if (rootMap != this) {
            rootMap.disableSyncMode();
            return;
        }
        disableSyncModeRecursive();
    }

    public Map<String, Object> getSyncModeMap() {
        if (rootMap != this) {
            return rootMap.getSyncModeMap();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", syncMode.name());
        out.put("syncEveryWriteRequests", syncEveryWriteRequests);
        out.put("rootBackgroundFlushEnabled", isBackgroundFlushEnabled());
        out.put("enabledObjectCount", countBackgroundFlushEnabledObjects());
        out.put("entryBackgroundFlushEnabled", entryStore.isBackgroundFlushEnabled());
        return out;
    }

    public void syncAll() {
        if (rootMap != this) {
            rootMap.syncAll();
            return;
        }
        syncAllRecursive();
    }

    private void ensureRoot() {
        if (rootMap != this) {
            throw new IllegalStateException("sync mode can only be configured on root map");
        }
    }

    private void disableSyncModeRecursive() {
        if (nextHashMap != null) {
            nextHashMap.disableSyncModeRecursive();
        }
        disableBackgroundFlush();
        entryStore.disableBackgroundFlush();
        syncMode = SyncMode.MANUAL;
        syncEveryWriteRequests = 0;
        writeRequestCounter.set(0);
    }

    private int countBackgroundFlushEnabledObjects() {
        int count = isBackgroundFlushEnabled() ? 1 : 0;
        if (entryStore.isBackgroundFlushEnabled()) {
            count++;
        }
        if (nextHashMap != null) {
            count += nextHashMap.countBackgroundFlushEnabledObjects();
        }
        return count;
    }

    private void applySyncModeRecursive(SyncMode mode, int writeRequests, long intervalMs) {
        if (nextHashMap != null) {
            nextHashMap.applySyncModeRecursive(mode, writeRequests, intervalMs);
        }
        disableBackgroundFlush();
        entryStore.disableBackgroundFlush();
        syncMode = mode;
        syncEveryWriteRequests = writeRequests;
        writeRequestCounter.set(0);
        if (mode == SyncMode.SYSTEM_AUTO) {
            enableAdaptiveBackgroundFlush(200L, 5000L, 64);
            entryStore.enableAdaptiveBackgroundFlush(200L, 5000L, 64);
            return;
        }
        if (mode == SyncMode.SECONDS) {
            enableBackgroundFlush(intervalMs, Integer.MAX_VALUE);
            entryStore.enableBackgroundFlush(intervalMs, Integer.MAX_VALUE);
            return;
        }
        if (mode == SyncMode.STRONG_100MS) {
            enableBackgroundFlush(100L, Integer.MAX_VALUE);
            entryStore.enableBackgroundFlush(100L, Integer.MAX_VALUE);
        }
    }

    private void afterWriteRequest() {
        if (rootMap != this) {
            return;
        }
        if (syncMode != SyncMode.WRITE_REQUESTS) {
            return;
        }
        int every = syncEveryWriteRequests;
        if (every <= 0) {
            return;
        }
        long c = writeRequestCounter.incrementAndGet();
        if (c % every != 0) {
            return;
        }
        syncAllRecursive();
    }

    private void syncAllRecursive() {
        sync();
        entryStore.sync();
        if (nextHashMap != null) {
            nextHashMap.syncAllRecursive();
        }
    }

    /**
     * 同步并关闭索引。
     */
    public void close() {
        if (rootMap != this) {
            rootMap.close();
            return;
        }
        disableSyncModeRecursive();
        syncAllRecursive();
        unloadAllRecursive();
    }

    private void unloadAllRecursive() {
        unloadAllMappedBuffers();
        entryStore.unloadAllMappedBuffers();
        closeEntryIdFilesQuietly();
        if (nextHashMap != null) {
            nextHashMap.unloadAllRecursive();
        }
        if (nextHashMap64 != null) {
            nextHashMap64.unloadAllRecursive();
        }
    }

    @Override
    public int size() {
        long n = sizeLong();
        return n > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) n;
    }

    @Override
    public boolean isEmpty() {
        return sizeLong() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof Long)) {
            return false;
        }
        Long v = (Long) value;
        for (Long cur : values()) {
            if (Objects.equals(cur, v)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Long get(Object key) {
        if (!(key instanceof Long)) {
            return null;
        }
        try {
            return get(((Long) key).longValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Long put(Long key, Long value) {
        if (key == null || value == null) {
            throw new NullPointerException();
        }
        try {
            return put(key.longValue(), value.longValue());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Long remove(Object key) {
        if (!(key instanceof Long)) {
            return null;
        }
        long k = ((Long) key).longValue();
        Long old;
        try {
            return  remove(k);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putAll(Map<? extends Long, ? extends Long> m) {
        for (Entry<? extends Long, ? extends Long> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        try {
            opWriteLock.lock();
            long nodeId = 0;
            long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
            try {
                loadBufferForUpdate(bufIdx);
                for (int slot = 0; slot < 256; slot++) {
                    int state = readState(nodeId, slot);
                    if (state == STATE_EMPTY) {
                        continue;
                    }
                    clearSlot(nodeId, slot);
                }
            } finally {
                unlockBuffer(bufIdx);
            }
            headerOpLockWrite.lock();
            try {
                size = 0;
                nextNodeId = 1;
                nextEntryId = 0;
                headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
                headerBuffer.putLong(HDR_SIZE, size);
                headerBuffer.putLong(HDR_NEXT_ENTRY_ID, nextEntryId);
                dirty(0L);
                resetEntryIdFiles();
            } finally {
                headerOpLockWrite.unlock();
            }
            if (nextHashMap != null) {
                nextHashMap.clear();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            opWriteLock.unlock();
        }
    }

    private boolean cannotStoreNewEntryId() {
        if (ptrSize == 2) {
            return nextEntryId >= 0x1_0000L && freeCountUnsafe() == 0L;
        }
        if (ptrSize == 4) {
            return nextEntryId > 0xFFFF_FFFFL && freeCountUnsafe() == 0L;
        }
        return false;
    }

    @Override
    public Set<Long> keySet() {
        return new AbstractSet<Long>() {
            @Override
            public Iterator<Long> iterator() {
                Iterator<Entry<Long, Long>> it = entrySet().iterator();
                return new Iterator<Long>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Long next() {
                        return it.next().getKey();
                    }

                    @Override
                    public void remove() {
                        it.remove();
                    }
                };
            }

            @Override
            public int size() {
                return DsHashMap.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return DsHashMap.this.containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
                return DsHashMap.this.remove(o) != null;
            }

            @Override
            public void clear() {
                DsHashMap.this.clear();
            }
        };
    }

    @Override
    public Collection<Long> values() {
        return new AbstractCollection<Long>() {
            @Override
            public Iterator<Long> iterator() {
                Iterator<Entry<Long, Long>> it = entrySet().iterator();
                return new Iterator<Long>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Long next() {
                        return it.next().getValue();
                    }

                    @Override
                    public void remove() {
                        it.remove();
                    }
                };
            }

            @Override
            public int size() {
                return DsHashMap.this.size();
            }

            @Override
            public void clear() {
                DsHashMap.this.clear();
            }
        };
    }

    @Override
    public Set<Entry<Long, Long>> entrySet() {
        return new AbstractSet<Entry<Long, Long>>() {
            @Override
            public Iterator<Entry<Long, Long>> iterator() {
                return DsHashMap.this.iterator();
            }

            @Override
            public int size() {
                return DsHashMap.this.size();
            }

            @Override
            public void clear() {
                DsHashMap.this.clear();
            }
        };
    }

    public Iterator<Entry<Long, Long>> iterator() {
        if (nextHashMap64 != null && nextHashMap != null) {
            return new MergedIterator(this);
        }
        return new OrderedIterator(this, 0L, 0, 0L);
    }

    private Iterator<Entry<Long, Long>> iteratorFromPrefix(long prefix, int prefixLen) {
        if (prefixLen <= 0) {
            return iterator();
        }
        if (prefixLen >= hashLen) {
            return new Iterator<Entry<Long, Long>>() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public Entry<Long, Long> next() {
                    throw new NoSuchElementException();
                }
            };
        }
        try {
            long nodeId = 0L;
            for (int level = 0; level < prefixLen; level++) {
                int slot = (int) ((prefix >>> (56 - (level << 3))) & 0xFFL);
                int state = readState(nodeId, slot);
                if (state == STATE_CHILD) {
                    long child = loadPtrOffset(ptrPos(nodeId, slot));
                    nodeId = child;
                    continue;
                }
                if (state == STATE_NEXT_LEVEL) {
                    if (nextHashMap == null) {
                        return new Iterator<Entry<Long, Long>>() {
                            @Override
                            public boolean hasNext() {
                                return false;
                            }

                            @Override
                            public Entry<Long, Long> next() {
                                throw new NoSuchElementException();
                            }
                        };
                    }
                    return nextHashMap.iteratorFromPrefix(prefix, prefixLen);
                }
                if (state == STATE_VALUE && level == hashLen - 1 && prefixLen == hashLen) {
                    long entryId = loadPtrOffset(ptrPos(nodeId, slot));
                    long k = entryStore.readLong(entryId, 0);
                    long v = entryStore.readLong(entryId, 8);
                    return new Iterator<Entry<Long, Long>>() {
                        private boolean emitted = false;

                        @Override
                        public boolean hasNext() {
                            return !emitted;
                        }

                        @Override
                        public Entry<Long, Long> next() {
                            if (emitted) {
                                throw new NoSuchElementException();
                            }
                            emitted = true;
                            return new SnapshotEntry(DsHashMap.this, k, v);
                        }
                    };
                }
                return new Iterator<Entry<Long, Long>>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public Entry<Long, Long> next() {
                        throw new NoSuchElementException();
                    }
                };
            }
            OrderedIterator it = new OrderedIterator(this, nodeId, prefixLen, prefix);
            return it;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class MergedIterator implements Iterator<Entry<Long, Long>> {
        private static final int PHASE_NEG_64 = 0;
        private static final int PHASE_LOCAL_16 = 1;
        private static final int PHASE_32 = 2;
        private static final int PHASE_POS_64 = 3;
        private static final int PHASE_DONE = 4;

        private final DsHashMap root;
        private final Iterator<Entry<Long, Long>> iter64;
        private final Iterator<Entry<Long, Long>> iter16;
        private final Iterator<Entry<Long, Long>> iter32;

        private int phase = PHASE_NEG_64;
        private Entry<Long, Long> buffered64 = null;
        private Entry<Long, Long> next = null;
        private Long lastKey = null;
        private boolean canRemove = false;

        private MergedIterator(DsHashMap root) {
            this.root = root;
            this.iter64 = root.nextHashMap64.iterator();
            this.iter16 = new OrderedIterator(root, 0L, 0, 0L);
            this.iter32 = root.nextHashMap.iterator();
        }

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            next = fetchNext();
            return next != null;
        }

        @Override
        public Entry<Long, Long> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<Long, Long> out = next;
            next = null;
            lastKey = out.getKey();
            canRemove = true;
            return out;
        }

        @Override
        public void remove() {
            if (!canRemove || lastKey == null) {
                throw new IllegalStateException();
            }
            root.remove(lastKey);
            canRemove = false;
        }

        private Entry<Long, Long> fetchNext() {
            while (phase != PHASE_DONE) {
                switch (phase) {
                    case PHASE_NEG_64 -> {
                        if (!iter64.hasNext()) {
                            phase = PHASE_LOCAL_16;
                            continue;
                        }
                        Entry<Long, Long> e = iter64.next();
                        if (e == null || e.getKey() == null) {
                            continue;
                        }
                        long k = e.getKey();
                        if (k < 0) {
                            return e;
                        }
                        buffered64 = e;
                        phase = PHASE_LOCAL_16;
                        continue;
                    }
                    case PHASE_LOCAL_16 -> {
                        if (iter16.hasNext()) {
                            return iter16.next();
                        }
                        phase = PHASE_32;
                        continue;
                    }
                    case PHASE_32 -> {
                        if (iter32.hasNext()) {
                            return iter32.next();
                        }
                        phase = PHASE_POS_64;
                        continue;
                    }
                    case PHASE_POS_64 -> {
                        if (buffered64 != null) {
                            Entry<Long, Long> e = buffered64;
                            buffered64 = null;
                            return e;
                        }
                        if (iter64.hasNext()) {
                            return iter64.next();
                        }
                        phase = PHASE_DONE;
                        return null;
                    }
                    default -> {
                        phase = PHASE_DONE;
                        return null;
                    }
                }
            }
            return null;
        }
    }

    private static final class OrderedIterator implements Iterator<Entry<Long, Long>> {
        private static final int BATCH_SIZE = 64;
        private final DsHashMap map;
        private final long[] nodeStack;
        private final int[] nextSlotIndex;
        private final byte[][] bitmapStack;
        private final long[][] ptrStack;
        private final long[] batchKeys = new long[BATCH_SIZE];
        private final long[] batchValues = new long[BATCH_SIZE];
        private final int baseLevel;
        private final long basePrefix;
        private final long[] prefixStack;
        private int level;
        private int readIndex = 0;
        private int writeIndex = 0;
        private Iterator<Entry<Long, Long>> delegated = null;
        private Long lastKey = null;
        private boolean canRemove = false;

        private OrderedIterator(DsHashMap map, long startNodeId, int startLevel, long basePrefix) {
            this.map = map;
            this.nodeStack = new long[map.hashLen];
            this.nextSlotIndex = new int[map.hashLen];
            this.bitmapStack = new byte[map.hashLen][BITMAP_BYTES];
            this.ptrStack = new long[map.hashLen][256];
            this.baseLevel = startLevel;
            this.basePrefix = basePrefix;
            this.prefixStack = new long[map.hashLen];
            this.level = startLevel;
            this.nodeStack[startLevel] = startNodeId;
            this.nextSlotIndex[startLevel] = 0;
            try {
                map.loadNode(startNodeId, bitmapStack[startLevel], ptrStack[startLevel]);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean hasNext() {
            if (readIndex < writeIndex) {
                return true;
            }
            return refill();
        }

        @Override
        public Entry<Long, Long> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            int i = readIndex;
            readIndex++;
            long k = batchKeys[i];
            lastKey = k;
            canRemove = true;
            return new SnapshotEntry(map, k, batchValues[i]);
        }

        @Override
        public void remove() {
            if (!canRemove || lastKey == null) {
                throw new IllegalStateException();
            }
            map.remove(lastKey);
            canRemove = false;
        }

        private boolean refill() {
            try {
                readIndex = 0;
                writeIndex = 0;
                while (writeIndex < BATCH_SIZE) {
                    if (delegated != null) {
                        while (writeIndex < BATCH_SIZE && delegated.hasNext()) {
                            Entry<Long, Long> e = delegated.next();
                            if (e == null) {
                                continue;
                            }
                            Long kObj = e.getKey();
                            Long vObj = e.getValue();
                            if (kObj == null || vObj == null) {
                                continue;
                            }
                            batchKeys[writeIndex] = kObj.longValue();
                            batchValues[writeIndex] = vObj.longValue();
                            writeIndex++;
                        }
                        if (delegated != null && !delegated.hasNext()) {
                            delegated = null;
                        }
                        if (writeIndex > 0) {
                            return true;
                        }
                    }
                    if (level < baseLevel) {
                        return writeIndex > 0;
                    }
                    if (nextSlotIndex[level] >= 256) {
                        nextSlotIndex[level] = 0;
                        level--;
                        continue;
                    }
                    int slot = map.slotOrderForDepth(level, true)[nextSlotIndex[level]++];
                    int state = map.stateFromBitmap(bitmapStack[level], slot);
                    if (state == STATE_EMPTY) {
                        continue;
                    }
                    long prevPrefix = level == baseLevel ? basePrefix : prefixStack[level - 1];
                    long shift = 56L - ((long) level << 3);
                    prefixStack[level] = (prevPrefix & ~(0xFFL << shift)) | (((long) slot & 0xFFL) << shift);
                    if (state == STATE_VALUE) {
                        long entryId = ptrStack[level][slot];
                        long k = map.entryStore.readLong(entryId, 0);
                        long v = map.entryStore.readLong(entryId, 8);
                        batchKeys[writeIndex] = k;
                        batchValues[writeIndex] = v;
                        writeIndex++;
                        continue;
                    }
                    if (state == STATE_NEXT_LEVEL) {
                        if (map.nextHashMap == null) {
                            continue;
                        }
                        delegated = map.nextHashMap.iteratorFromPrefix(prefixStack[level], level + 1);
                        continue;
                    }
                    if (state == STATE_CHILD && level + 1 < map.hashLen) {
                        long child = ptrStack[level][slot];
                        if (child == 0) {
                            continue;
                        }
                        level++;
                        nodeStack[level] = child;
                        nextSlotIndex[level] = 0;
                        map.loadNode(child, bitmapStack[level], ptrStack[level]);
                    }
                }
                return writeIndex > 0;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class Longs {
        long[] a;
        int n;

        Longs(int cap) {
            a = new long[Math.max(16, cap)];
        }

        void add(long v) {
            if (n >= a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        long[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    private long[] toKeyArray() {
        Longs out = new Longs(size());
        try {
            collectKeys(out, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (nextHashMap != null) {
            long[] more = nextHashMap.toKeyArray();
            for (long v : more) {
                out.add(v);
            }
        }
        return out.toArray();
    }

    private void collectKeys(Longs out, long nodeId, int level) throws IOException {
        for (int slot = 0; slot < 256; slot++) {
            int state = readState(nodeId, slot);
            switch (state) {
               
                case STATE_VALUE -> {
                    long entryId = loadPtrOffset(ptrPos(nodeId, slot));
                    long key = entryStore.readLong(entryId, 0);
                    out.add(key);
                }
                case STATE_CHILD -> {
                    long child = loadPtrOffset(ptrPos(nodeId, slot));
                    if (child > 0 && level + 1 < hashLen) {
                        collectKeys(out, child, level + 1);
                    }
                }
               
            }
        }
    }

    private long allocateEntryId(long key, long value) throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = allocateEntryIdOnly();
            entryStore.writeLong(id, 0, key);
            entryStore.writeLong(id, 8, value);
            return id;
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private void recycleEntryId(long entryId) {
        headerOpLockWrite.lock();
        try {
            freeEntryRing.offer(entryId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private long allocateEntryIdOnly() throws IOException {
        long reused = freeEntryRing.poll();
        if (reused >= 0L) {
            return reused;
        }
        long id = nextEntryId;
        nextEntryId++;
        headerBuffer.putLong(HDR_NEXT_ENTRY_ID, nextEntryId);
        dirty(0L);
        return id;
    }

    private long freeCountUnsafe() {
        try {
            return freeEntryRing.count();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static DsFreeRing openFreeRing(File entryFile, int initialCap) {
        try {
            File freeFile = new File(entryFile.getAbsolutePath() + ".free");
            //File tmp = new File(entryFile.getAbsolutePath() + ".free.tmp");
            return new DsFreeRing(freeFile,  initialCap);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void resetEntryIdFiles() {
        try {
            freeEntryRing.clear();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void closeEntryIdFilesQuietly() {
        try {
            freeEntryRing.close();
        } catch (IOException ignored) {
        }
    }

    private long loadPtrOffset(long position) throws IOException {
        return switch (ptrSize) {
            case 2 -> loadU16ByOffset(position);
            case 4 -> loadU32ByOffset(position);
            case 8 -> loadLongOffset(position);
            default -> throw new RuntimeException("invalid ptrSize -> " + ptrSize);
        };
    }

    private void storePtrOffset(long position, long id) throws IOException {
        switch (ptrSize) {
            case 2 -> storeShortOffset(position, (short) id);
            case 4 -> storeIntOffset(position, (int) id);
            case 8 -> storeLongOffset(position, id);
            default -> throw new RuntimeException("invalid ptrSize -> " + ptrSize);
        }
    }

      
     /**
     * 生成其他用途的哈希slots。
     * @param key
     * @return 
     */
    private byte[] hashBytes(long key) {
       byte[] b = new byte[HASH_DEPTH];
       // storeHashBytes(b, hashOffset, key);
         DsDataUtil.storeLong(b, 0, key);
        return b;
    }

    private long allocateNodeId() throws IOException {
        headerOpLockWrite.lock();
        try {
            if (ptrSize == 2 && nextNodeId >= 0x1_0000L) {
                throw new IOException("nodeId overflow for ptrSize=2: nextNodeId=" + nextNodeId);
            }
            if (ptrSize == 4 && nextNodeId >= 0x1_0000_0000L) {
                throw new IOException("nodeId overflow for ptrSize=4: nextNodeId=" + nextNodeId);
            }
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

    private void writeSlotAsValue(long nodeId, int slot, long entryId) throws IOException {
        storePtrOffset(ptrPos(nodeId, slot), entryId);
        writeState(nodeId, slot, STATE_VALUE);
    }

    private void writeSlotAsChild(long nodeId, int slot, long childNodeId) throws IOException {
        writeState(nodeId, slot, STATE_EMPTY);
        storePtrOffset(ptrPos(nodeId, slot), childNodeId);
        writeState(nodeId, slot, STATE_CHILD);
    }

    private void writeSlotAsNextLevel(long nodeId, int slot, long nextLevelRootNodeId) throws IOException {
        writeState(nodeId, slot, STATE_EMPTY);
        storePtrOffset(ptrPos(nodeId, slot), nextLevelRootNodeId);
        writeState(nodeId, slot, STATE_NEXT_LEVEL);
    }

    private void clearSlot(long nodeId, int slot) throws IOException {
        writeState(nodeId, slot, STATE_EMPTY);
        storePtrOffset(ptrPos(nodeId, slot), 0L);
    }

    private long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) this.dataUnitSize;
    }

    private long bitmapPos(long nodeId, int slot) {
        return nodeBase(nodeId) + (slot/4);
    }

    private long ptrPos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * ptrSize;
    }


    private int readState(long nodeId, int slot) throws IOException {
        long pos = bitmapPos(nodeId, slot);
       
        int stateByte = loadU8ByOffset(pos);
        int stateValue = getStateValue(stateByte, slot%4);
        
        return stateValue;
    }
    
    private static int getStateValue(int data, int index) {
        int shift = 0;
        switch(index){
            case 0 -> shift = 6;
            case 1 -> shift = 4;
            case 2 -> shift = 2;
        }
        return (data >>> shift) & 0x3;
    }
    
    private static int setStateValue(int data, int index, int state) {
        int shift = 0;
        switch(index){
            case 0 -> shift = 6;
            case 1 -> shift = 4;
            case 2 -> shift = 2;
        }
        int mask = ~(0x3 << shift);
        data = data & mask;
        return data | ((state & 0x3) << shift);
    }

//    private void storeHashBytes(byte[] bytes, int hashOffset, long value) {
//        int n = Math.min(hashLen, bytes.length);
//        for (int i = 0; i < n; i++) {
//            bytes[i] = (byte) (value >> ((hashOffset + i) * 8));
//        }
//    }

    private void writeState(long nodeId, int slot, int state) throws IOException {
        long pos = bitmapPos(nodeId, slot);
        int stateByte = loadU8ByOffset(pos);
        byte stateValue = (byte) setStateValue(stateByte, slot%4, state);
        storeByteOffset(pos, stateValue);
    }
}
