package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.Spliterator;

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

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;//存储层升级。

    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 8;

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;

    private final int hashOffset = 0;
    private final int hashLen = 8;
    private final int hashEnd = 7;

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsMemorySet(File file) {
        super(file, HEADER_SIZE,2112);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*8-byte(value) = 64+2048 =2112
        zeroNode = new long[this.dataUnitSize / 8];
        initHeader();
       
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0);
            
            byte[] m = new byte[4];
            headerBuffer.put(HDR_MAGIC, m, 0, 4);
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
            loadBuffer( HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
 /**
     * 线程局部变量
     */
    private static final ThreadLocal<byte[]> HASHBYTES = new ThreadLocal();
    private static final ThreadLocal<byte[]> HASHBYTES2 = new ThreadLocal();
    
    public boolean add(long key) throws IOException {
        byte[] b = HASHBYTES.get();
        if (b != null) {
            DsDataUtil.storeLong(b, hashOffset, key);
            return put(b, key);

        } else {
            b = new byte[hashLen];
            HASHBYTES.set(b);
            DsDataUtil.storeLong(b, hashOffset, key);
            return put(b, key);
        }
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
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
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
                            
                        } finally {
                            headerOpLockWrite.unlock();
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
        for (int level = currentLevel; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < hashEnd) {
                switch (state) {
                    case STATE_CHILD -> {
                        nodeId = loadLongOffset(valuePos(nodeId, slot));
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
                        unlockBuffer(bufIdx);
                        return true;
                    }
                    case STATE_VALUE -> {
                        long oldKey = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            unlockBuffer(bufIdx);
                            return false;
                        }
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_CHILD);
                        storeLongOffset(valuePos(nodeId, slot), child);
                        int nextLevel = level + 1;
                        putInner(child, hashBytes(oldKey), oldKey, nextLevel, false);
                        putInner(child, hashes, key, nextLevel, countSize);
                        unlockBuffer(bufIdx);
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
                    unlockBuffer(bufIdx);
                    return true;
                }
                case STATE_VALUE -> {
                    long oldKey = loadLongOffset(valuePos(nodeId, slot));
                     unlockBuffer(bufIdx);
                    if (oldKey == key) {
                        return false;
                    }
                   
                }
//                default ->
//                    throw new IOException("invalid state: " + state);
            }
        }
        unlockBuffer(bufIdx);
        return false;
    }

    private byte[] hashBytes(long key) {
        byte[] b = HASHBYTES2.get();
        if (b != null) {
            DsDataUtil.storeLong(b, hashOffset, key);
            return b;

        } else {
            b = new byte[hashLen];
            HASHBYTES2.set(b);
            DsDataUtil.storeLong(b, hashOffset, key);
            return b;
        }
        
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
        if (!(o instanceof Long)) {
            return false;
        }
        try {
            return get(((Long) o).longValue()) != null;
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
            if (!(o instanceof Long)) {
                return false;
            }
            return remove(((Long) o).longValue());
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
    public void clear() {
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
            
        
    }

  

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
        int index = 0;
        headerOpLockRead.lock();
        try {
            Iterator<Long> it = this.iterator();
            while (it.hasNext()) {
                Long v = it.next();
                //if(index<size){
                vs[index] = v;
                //}
                index++;
            }

        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            headerOpLockRead.unlock();
        }
       
    }
    
   

    public long[] toArrayLong() {
        if (size > Integer.MAX_VALUE) {
            throw new RuntimeException("total elements = " + size + ",over Integer.MAX_VALUE!");
        }
        long[] out = new long[(int) size];
        int index = 0;
        headerOpLockRead.lock();
        try {
          Iterator<Long> it = this.iterator();
            while (it.hasNext()) {
                Long v = it.next();
                //if(index<size){
                out[index] = v;
                //}
                index++;
            }
           
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            headerOpLockRead.unlock();
        }
        return out;

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
                for (int i = 255; i >127; i--) {
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
                DsMemorySet.this.remove(next);
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
