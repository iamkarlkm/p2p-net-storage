package com.q3lives.ds.collections;

import java.io.File;
import java.io.IOException;
import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.core.DsObject;
import com.q3lives.ds.index.master.DsHash64MasterIndex;

/**
 * 通用 short->short 映射索引（以 16-bit 哈希值作为 key）。
 *
 * <p>实现上与 {@link DsHash64MasterIndex} 基本一致，都是 256-ary trie（8 层，每层 1 byte）。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>历史/实验性结构：用于在不引入完整 tiered 逻辑时，快速把 hash16 映射到一个 value。</li>
 *   <li>slot payload 仍使用 32B，支持 VALUE、CHILD、VALUE_CHILD 三种状态组合。</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>该类名为 DsHashMap，但并不是 Java 集合意义上的 HashMap（没有 put/remove 返回旧值语义，也不支持遍历键值）。</li>
 *   <li>它更接近“固定结构的哈希 trie 索引”，只负责 hashKey -> longValue 的映射。</li>
 * </ul>
 */
public class DsHashMapI16 extends DsObject implements Map<Short,Short>{
    private static final byte[] MAGIC = new byte[] {'.', 'M', 'A', 'P'};
    private static final int HEADER_SIZE = DsFixedBucketStore.HEADER_SIZE;
    private static final int HDR_MAGIC = 0;
    private static final int HDR_VALUE_SIZE = 4;
    private static final int HDR_NEXT_NODE_ID = 8;
    private static final int HDR_SIZE = 16;
    private static final int HDR_NEXT_KEY_ID = 24;

    private static final int STATE_EMPTY = 0;
    private static final int STATE_VALUE = 1;
    private static final int STATE_CHILD = 2;
    private static final int STATE_NEXT_LEVEL = 3;//存储层升级。

    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 4;

    private int nextNodeId;
    private int size;
    private final long[] zeroNode;
    private int nextKeyId;
    
    private int hashOffset = 0;
    private final int hashLen = 2;
    private final int keyStoreLen = 2;
    
  
    
      

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsHashMapI16(File file) {
        super(file, HEADER_SIZE, 1088);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*2-byte(key) + 256*2-byte(value) = 64+512+512 = 1088
        zeroNode = new long[this.dataUnitSize / 8];
        initHeader();
        
    }
    
//    /**
//     * 创建一个 key->value 的 trie 映射文件。
//     *
//     * @param file
//     * @param hashOffset
//     * @param dataSize
//     * @param keyStore
//     */
//    public DsHashMapI16(File file,int hashOffset,int dataSize,DsObject keyStore) {
//        super(file, HEADER_SIZE, dataSize);
//        zeroNode = new long[this.dataUnitSize / 8];
//        initHeader();
//        this.hashOffset = hashOffset;
//    }
    


    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                nextNodeId = headerBuffer.getInt(HDR_NEXT_NODE_ID);
                size = headerBuffer.getInt(HDR_SIZE);
                nextKeyId = headerBuffer.getInt(HDR_NEXT_KEY_ID);
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);//value size
            nextNodeId = 1;
            size = 0;
            nextKeyId = 0;
            headerBuffer.putInt(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putInt(HDR_SIZE, size);
            headerBuffer.putInt(HDR_NEXT_KEY_ID, nextKeyId);
            dirty(0L);
            loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Short put(short key, short value) throws IOException {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key & 0xFFFFL);
        return put(b, key,value);
    }

    /**
     * 写入 hash16(8B) -> value 映射。
     *
     * <p>实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     * @param hashes
     * @param key
     * @param value
     * @return 
     * @throws java.io.IOException
     */
    public Short put(byte[] hashes,short key, short value) throws IOException {
        if (hashes == null || hashes.length != hashLen) {
            throw new IllegalArgumentException("hashes length must be "+hashLen);
        }
        int nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            nodeId = loadU16ByOffset(valuePos(nodeId, slot));
                            long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                            if (nextBuf != bufIdx) {
                                loadBufferForUpdate(nextBuf);
                                unlockBuffer(bufIdx);
                                bufIdx = nextBuf;
                            }
                            continue;
                        }
                        case STATE_EMPTY -> {
                            writeState(nodeId, slot, STATE_VALUE);
                            storeShortOffset(keyPos(nodeId, slot),key);
                            storeShortOffset(valuePos(nodeId, slot),value);
                            headerOpLockWrite.lock();
                            try {
                                size++;
                                headerBuffer.putInt(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            return null;
                        }
                        case STATE_VALUE -> {
                            short oldKey = loadShortOffset(keyPos(nodeId, slot));
                        short oldValue = loadShortOffset(valuePos(nodeId, slot));
                            if (oldKey == key) {
                                if (oldValue != value) {
                                    storeIntOffset(valuePos(nodeId, slot), (int) value);
                                }
                                return oldValue;
                            }
                            int child = (int) allocateNodeId();
                            writeState(nodeId, slot, STATE_CHILD);
                            storeShortOffset(valuePos(nodeId, slot), (short) child);
                            int nextLevel = level + 1;
                            putInner(child, hashBytes(oldKey), oldKey, oldValue, nextLevel, false);
                            putInner(child, hashes, key, value, nextLevel, true);
                            return null;
                        }
                        default -> throw new IOException("invalid state: " + state);
                    }
                }

                switch (state) {
                    case STATE_EMPTY -> {
                        writeState(nodeId, slot, STATE_VALUE);
                        storeShortOffset(keyPos(nodeId, slot),key);
                        storeShortOffset(valuePos(nodeId, slot),value);
                        headerOpLockWrite.lock();
                        try {
                            size++;
                            headerBuffer.putInt(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        return null;
                    }
                    case STATE_VALUE -> {
                        short oldKey = loadShortOffset(keyPos(nodeId, slot));
                        short oldValue = loadShortOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            if (oldValue != value) {
                                storeShortOffset(valuePos(nodeId, slot),value);
                            }
                            return oldValue;
                        }
                         //理论上Key最后一层应该相等。
                        throw new IOException("invalid state: " + state);
                    }
                    default -> throw new IOException("invalid state: " + state);
                }
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }
    
    private Short putInner(int startNodeId, byte[] hashes, short key, short value, int currentLevel, boolean countSize) throws IOException {
        int nodeId = startNodeId;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = currentLevel; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            int child = loadU16ByOffset(valuePos(nodeId, slot));
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
                            writeState(nodeId, slot, STATE_VALUE);
                            storeShortOffset(keyPos(nodeId, slot),key);
                            storeShortOffset(valuePos(nodeId, slot),value);
                            if (countSize) {
                                headerOpLockWrite.lock();
                                try {
                                    size++;
                                    headerBuffer.putInt(HDR_SIZE, size);
                                    dirty(0L);
                                } finally {
                                    headerOpLockWrite.unlock();
                                }
                            }
                            return null;
                        }
                        case STATE_VALUE -> {
                            short oldKey = loadShortOffset(keyPos(nodeId, slot));
                            short oldValue = loadShortOffset(valuePos(nodeId, slot));
                            if (oldKey == key) {
                                if (oldValue != value) {
                                    storeShortOffset(valuePos(nodeId, slot), value);
                                }
                                return oldValue;
                            }
                            int child = (int) allocateNodeId();
                            writeState(nodeId, slot, STATE_CHILD);
                            //storeShortOffset(keyPos(nodeId, slot),key);
                            storeShortOffset(valuePos(nodeId, slot), (short) child);
                            int nextLevel = level + 1;
                            putInner(child, hashBytes(oldKey), oldKey, oldValue, nextLevel, false);
                            putInner(child, hashes, key, value, nextLevel, countSize);
                            return null;
                        }
                        default -> throw new IOException("invalid state: " + state);
                    }
                }

                switch (state) {
                    case STATE_EMPTY -> {
                        writeState(nodeId, slot, STATE_VALUE);
                         storeShortOffset(keyPos(nodeId, slot),key);
                         storeShortOffset(valuePos(nodeId, slot),value);
                        if (countSize) {
                            headerOpLockWrite.lock();
                            try {
                                size++;
                                headerBuffer.putInt(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                        }
                        return null;
                    }
                    case STATE_VALUE -> {
                        short oldKey = loadShortOffset(keyPos(nodeId, slot));
                        short oldValue = loadShortOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            if (oldValue != value) {
                                storeShortOffset(valuePos(nodeId, slot),value);
                            }
                            return oldValue;
                        }
                        //理论上Key最后一层应该相等。
                        throw new IOException("invalid state: " + state);
                    }
                    default -> throw new IOException("invalid state: " + state);
                }
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    private byte[] hashBytes(short key) {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key & 0xFFFFL);
        return b;
    }

    private void storeHashBytes(byte[] bytes, int hashOffset, long value) {
        int n = Math.min(hashLen, bytes.length);
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (value >> ((hashOffset + i) * 8));
        }
    }

    /**
     * 查询 hash16 对应的 value。
     * @param key
     * @return 
     * @throws java.io.IOException 
     */
    public Short get(short key) throws IOException {
        return getByHash(key, hashBytes(key));
    }

    /**
     * 查询 hash16 对应的 value（hash16 必须为 8 字节）。
     *
     * @param key
     * @param hash16
     * @return value，不存在返回 null
     * @throws java.io.IOException
     */
    public Short get(short key,byte[] hash16) throws IOException {
        if (hash16 == null || hash16.length != hashLen) {
            return null;
        }
        return getByHash(key, hash16);
    }

    private Short getByHash(short key, byte[] hashes) throws IOException {
        int nodeId = 0;
        for (int level = 0; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state;
            if (level < hashLen - 1) {
                    state = readState(nodeId, slot);
                    if (state == STATE_CHILD) {
                        int child = loadU16ByOffset(valuePos(nodeId, slot));
                       
                        if (child <= 0) {
                            return null;
                        }
                        nodeId = child;
                        continue;//使用下一个哈希 level++ -> slot。
                    }else if (state == STATE_VALUE) {
                        
                        short storedKey = loadShortOffset(keyPos(nodeId, slot));
                        if (storedKey != key) {
                            return null;
                        }
                        short v = loadShortOffset(valuePos(nodeId, slot));
                        return (short) v;
                    }
                    return null;
               
            }
            state = readState(nodeId, slot);
            if (state == STATE_VALUE) {

                short storedKey = loadShortOffset(keyPos(nodeId, slot));
                if (storedKey != key) {
                    return null;
                }
                short v = loadShortOffset(valuePos(nodeId, slot));
                return (short) v;
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
    public Short remove(short key) throws IOException {
        return remove(key, hashBytes(key));
    }

    /**
     * 删除 hash16 对应的映射（hash16 必须为 8 字节）。
     * @param key
     * @param hash16
     * @return 
     * @throws java.io.IOException
     */
    public Short remove(short key,byte[] hash16) throws IOException {
        if (hash16 == null || hash16.length != hashLen) {
            return null;
        }
        int nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hash16[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            int child = loadU16ByOffset(valuePos(nodeId, slot));
                           
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
                            short storedKey = loadShortOffset(keyPos(nodeId, slot));
                            if (storedKey != key) {
                                return null;
                            }
                            short v = loadShortOffset(valuePos(nodeId, slot));
                            writeState(nodeId, slot, STATE_EMPTY);
                            //storeShortOffset(keyPos(nodeId, slot), 0);
                            //storeShortOffset(valuePos(nodeId, slot), 0);
                            headerOpLockWrite.lock();
                            try {
                                size--;
                                headerBuffer.putLong(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            return v;
                        }
                        default -> throw new IOException("invalid state: " + state);
                    }
                }

                switch (state) {
                    case STATE_VALUE -> {
                        short storedKey = loadShortOffset(keyPos(nodeId, slot));
                        if (storedKey != key) {
                            return null;
                        }
                        short v = loadShortOffset(valuePos(nodeId, slot));
                        writeState(nodeId, slot, STATE_EMPTY);
                        //storeShortOffset(keyPos(nodeId, slot), 0);
                            //storeShortOffset(valuePos(nodeId, slot), 0);
                        headerOpLockWrite.lock();
                        try {
                            size--;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        return v;
                    }
                    case STATE_NEXT_LEVEL -> throw new IOException("invalid state: " + state);
                    case STATE_EMPTY -> {
                        return null;
                    }
                    default -> {
                        return null;
                    }
                }
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    /**
     * 返回当前映射条目数（上限截断到 int）。
     * @return 
     */
    @Override
    public int size()  {
        headerOpLockWrite.lock();
        try {
            if (size > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) size;
        } finally {
            headerOpLockWrite.unlock();
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
            int id = nextNodeId;
            nextNodeId++;
            headerBuffer.putInt(HDR_NEXT_NODE_ID, nextNodeId);
            dirty(0L);
            storeLongOffset(nodeBase(id), zeroNode);
            return id;
        } finally {
            headerOpLockWrite.unlock();
        }
        
    }


    private int nodeBase(int nodeId) {
        return HEADER_SIZE + nodeId *  this.dataUnitSize;
    }

    private long bitmapPos(int nodeId, int slot) {
        return nodeBase(nodeId) + (slot/4);
    }

    private long valuePos(int nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + keyStoreLen*256 + (long) slot * SLOT_PAYLOAD_BYTES;
    }
    
    private int keyPos(int nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + slot * keyStoreLen;
    }

    
    private int readState(int nodeId, int slot) throws IOException {
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

    private void writeState(int nodeId, int slot, int state) throws IOException {
        long pos = bitmapPos(nodeId, slot);
        int stateByte = loadU8ByOffset(pos);
        byte stateValue = (byte) setStateValue(stateByte, slot%4, state);
        storeByteOffset(pos, stateValue);
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return get((Short)key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof Long)) {
            return false;
        }
        Long v = (Long) value;
        for (Short cur : values()) {
            if (Objects.equals(cur, v)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Short get(Object key) {
        try {
            return get(((Short)key).shortValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Short put(Short key, Short value) {
        try {
            return put(key.shortValue(),value.shortValue());
        } catch (IOException ex) {
             throw new RuntimeException(ex);
        }
    }

    @Override
    public Short remove(Object key) {
        try {
            return remove(((Short)key).shortValue());
        } catch (IOException ex) {
           throw new RuntimeException(ex);
        }
    }

    @Override
    public void putAll(Map<? extends Short, ? extends Short> m) {
        for (Entry<? extends Short, ? extends Short> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        int nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        try {
            loadBufferForUpdate(bufIdx);
            for (int slot = 0; slot < 256; slot++) {
                int state = readState(nodeId, slot);
                if (state == STATE_EMPTY) {
                    continue;
                }
                writeState(nodeId, slot, STATE_EMPTY);
               
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            unlockBuffer(bufIdx);
        }
        headerOpLockWrite.lock();
        try {
            size = 0;
            headerBuffer.putInt(HDR_SIZE, size);
            dirty(0L);
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    @Override
    public Set<Short> keySet() {
        return new AbstractSet<Short>() {
            @Override
            public Iterator<Short> iterator() {
                Iterator<Entry<Short, Short>> it = entrySet().iterator();
                return new Iterator<Short>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Short next() {
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
                return DsHashMapI16.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return DsHashMapI16.this.containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
                return DsHashMapI16.this.remove(o) != null;
            }

            @Override
            public void clear() {
                DsHashMapI16.this.clear();
            }
        };
    }

    @Override
    public Collection<Short> values() {
        return new AbstractCollection<Short>() {
            @Override
            public Iterator<Short> iterator() {
                Iterator<Entry<Short, Short>> it = entrySet().iterator();
                return new Iterator<Short>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Short next() {
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
                return DsHashMapI16.this.size();
            }

            @Override
            public void clear() {
                DsHashMapI16.this.clear();
            }
        };
    }

    @Override
    public Set<Entry<Short, Short>> entrySet() {
        return new AbstractSet<Entry<Short, Short>>() {
            @Override
            public Iterator<Entry<Short, Short>> iterator() {
                final short[] keys = toKeyArray();
                return new Iterator<Entry<Short, Short>>() {
                    private int i = 0;
                    private Short lastKey = null;
                    private boolean canRemove = false;

                    @Override
                    public boolean hasNext() {
                        return i < keys.length;
                    }

                    @Override
                    public Entry<Short, Short> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        short k = keys[i++];
                        final Short entryKey = k;
                        lastKey = entryKey;
                        canRemove = true;
                        return new Entry<Short, Short>() {
                            @Override
                            public Short getKey() {
                                return entryKey;
                            }

                            @Override
                            public Short getValue() {
                                return DsHashMapI16.this.get(entryKey);
                            }

                            @Override
                            public Short setValue(Short value) {
                                return DsHashMapI16.this.put(entryKey, value);
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
                        };
                    }

                    @Override
                    public void remove() {
                        if (!canRemove || lastKey == null) {
                            throw new IllegalStateException();
                        }
                        DsHashMapI16.this.remove(lastKey);
                        canRemove = false;
                    }
                };
            }

            @Override
            public int size() {
                return DsHashMapI16.this.size();
            }

            @Override
            public void clear() {
                DsHashMapI16.this.clear();
            }
        };
    }

    private static final class Shorts {
        short[] a;
        int n;

        Shorts(int cap) {
            a = new short[Math.max(16, cap)];
        }

        void add(short v) {
            if (n >= a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        short[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    private short[] toKeyArray() {
        Shorts out = new Shorts(size());
        try {
            collectKeys(out, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
       
        return out.toArray();
    }

    private void collectKeys(Shorts out, int nodeId, int level) throws IOException {
        for (int slot = 0; slot < 256; slot++) {
            int state = readState(nodeId, slot);
            switch (state) {
                case STATE_EMPTY -> {
                }
                case STATE_VALUE -> {
                    short key = loadShortOffset(keyPos(nodeId, slot));
                    out.add(key);
                }
                case STATE_CHILD -> {
                    int child = loadU16ByOffset(valuePos(nodeId, slot));
                    if (child > 0 && level + 1 < hashLen) {
                        collectKeys(out, child, level + 1);
                    }
                }
                case STATE_NEXT_LEVEL -> {
                }
                default -> throw new IOException("invalid state: " + state);
            }
        }
    }
 
}
