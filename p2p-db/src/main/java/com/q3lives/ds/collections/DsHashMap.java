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
 * 通用 int->int 映射索引（以 32-bit 哈希值作为 key）。
 *
 * <p>实现上与 {@link DsHash64MasterIndex} 基本一致，都是 256-ary trie（8 层，每层 1 byte）。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>历史/实验性结构：用于在不引入完整 tiered 逻辑时，快速把 hash32 映射到一个 value。</li>
 *   <li>slot payload 仍使用 32B，支持 VALUE、CHILD、VALUE_CHILD 三种状态组合。</li>
 * </ul>
 *
 * <p>注意：</p>
 * <ul>
 *   <li>该类名为 DsHashMap，但并不是 Java 集合意义上的 HashMap（没有 put/remove 返回旧值语义，也不支持遍历键值）。</li>
 *   <li>它更接近“固定结构的哈希 trie 索引”，只负责 hashKey -> longValue 的映射。</li>
 * </ul>
 */
public class DsHashMap extends DsObject implements Map<Integer,Integer>{
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

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;
    private long nextKeyId;
    
    private int hashOffset = 0;
    private int hashLen = 2;
    private int keyStoreLen = 2;
    
    
    private final DsObject keyStore;
    
    private final DsHashMap nextHashMap;
    
    

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsHashMap(File file) {
        super(file, HEADER_SIZE, 1600);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*2-byte(key) + 256*4-byte(value) = 64+512+1024 =1600
        zeroNode = new long[this.dataUnitSize / 8];
        initHeader();
        
        File key16File = new File(file.getAbsolutePath()+".k16");
        keyStore = new DsObject(key16File,8);//2^15个key(8字节64位)索引
 
        
        File key32File = new File(file.getAbsolutePath()+".k32");
        DsObject keyStore32 = new DsObject(key32File,8);//2^31个key(8字节64位)索引
        
        File nextHashMapFile = new File(file.getAbsolutePath()+".m32");//4字节索引 8位哈希 => 256*2bit(位图)/8 + 256*4byte(key) + 256*4byte(value) = 64+512+2048 =2112
        nextHashMap = new DsHashMap(nextHashMapFile,2,2,4,2112,keyStore32,null);//32位哈希是目前实现最后一级
    }
    
    
    /**
     * 创建一个 hash32->value 的 trie 映射文件。
     *
     * @param file
     * @param hashOffset
     * @param hashLen
     * @param dataSize
     * @param keyStoreLen
     * @param keyStore32
     * @param nextHashMap
     */
    public DsHashMap(File file,int hashOffset,int hashLen,int keyStoreLen,int dataSize,DsObject keyStore32,DsHashMap nextHashMap) {
        super(file, HEADER_SIZE, dataSize);
        zeroNode = new long[this.dataUnitSize / 8];
        initHeader();
        this.hashOffset = hashOffset;
        this.hashLen = hashLen;
        this.keyStoreLen = keyStoreLen;
        this.keyStore = keyStore32;
        this.nextHashMap = nextHashMap;
    }

    private void initHeader() {
        try {
            headerBuffer = loadBuffer(0L);
            byte[] m = new byte[4];
            headerBuffer.get(HDR_MAGIC, m, 0, 4);
            if (Arrays.equals(m, MAGIC)) {
                nextNodeId = headerBuffer.getLong(HDR_NEXT_NODE_ID);
                size = headerBuffer.getLong(HDR_SIZE);
                nextKeyId = headerBuffer.getLong(HDR_NEXT_KEY_ID);
                return;
            }
            headerBuffer.put(HDR_MAGIC, MAGIC, 0, 4);//4字节
            headerBuffer.putInt(HDR_VALUE_SIZE, SLOT_PAYLOAD_BYTES);//value size
            nextNodeId = 1;
            size = 0;
            nextKeyId = 0;
            headerBuffer.putLong(HDR_NEXT_NODE_ID, nextNodeId);
            headerBuffer.putLong(HDR_SIZE, size);
            headerBuffer.putLong(HDR_NEXT_KEY_ID, nextKeyId);
            dirty(0L);
            loadBuffer((long) HEADER_SIZE / BLOCK_SIZE);//标准64字节头
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Integer put(int key, int value) throws IOException {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key & 0xFFFFFFFFL);
        return put(b, key,value);
    }

    /**
     * 写入 hash32(8B) -> value 映射。
     *
     * <p>实现为 8 层 256-ary trie；该版本在某些中间层遇到 STATE_VALUE_CHILD 时会抛异常（不完全支持）。</p>
     * @param hashes
     * @param key
     * @param value
     * @return 
     * @throws java.io.IOException
     */
    public Integer put(byte[] hashes,int key, int value) throws IOException {
        if (hashes == null || hashes.length != hashLen) {
            throw new IllegalArgumentException("hashes length must be "+hashLen);
        }
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            nodeId = loadU32ByOffset(valuePos(nodeId, slot));
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
                            long keyId = allocateKeyId(key);
                            storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                            storeIntOffset(valuePos(nodeId, slot), value);
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
                            long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                            int oldKey = keyStore.readInt(oldKeyId);
                            int oldValue = loadIntOffset(valuePos(nodeId, slot));
                            if (oldKey == key) {
                                if (oldValue != value) {
                                    storeIntOffset(valuePos(nodeId, slot), value);
                                }
                                return oldValue;
                            }
                            int child = (int) allocateNodeId();
                            writeState(nodeId, slot, STATE_CHILD);
                            storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                            storeIntOffset(valuePos(nodeId, slot), child);
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
                        long keyId = allocateKeyId(key);
                        storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                        storeIntOffset(valuePos(nodeId, slot), value);
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
                        long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        int oldKey = keyStore.readInt(oldKeyId);
                        int oldValue = loadIntOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            if (oldValue != value) {
                                storeIntOffset(valuePos(nodeId, slot), value);
                            }
                            return oldValue;
                        }
                        nextHashMap.put(oldKey, oldValue);
                        nextHashMap.put(key, value);
                        writeState(nodeId, slot, STATE_NEXT_LEVEL);
                        storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        return null;
                    }
                    case STATE_NEXT_LEVEL -> {
                        return nextHashMap.put(key, value);
                    }
                    default -> throw new IOException("invalid state: " + state);
                }
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }
    
    private Integer putInner(long startNodeId, byte[] hashes, int key, int value, int currentLevel, boolean countSize) throws IOException {
        long nodeId = startNodeId;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = currentLevel; level < hashLen; level++) {
                int slot = hashes[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadU32ByOffset(valuePos(nodeId, slot));
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
                            long keyId = allocateKeyId(key);
                            storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                            storeIntOffset(valuePos(nodeId, slot), value);
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
                            return null;
                        }
                        case STATE_VALUE -> {
                            long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                            int oldKey = keyStore.readInt(oldKeyId);
                            int oldValue = loadIntOffset(valuePos(nodeId, slot));
                            if (oldKey == key) {
                                if (oldValue != value) {
                                    storeIntOffset(valuePos(nodeId, slot), value);
                                }
                                return oldValue;
                            }
                            int child = (int) allocateNodeId();
                            writeState(nodeId, slot, STATE_CHILD);
                            storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                            storeIntOffset(valuePos(nodeId, slot), child);
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
                        long keyId = allocateKeyId(key);
                        storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                        storeIntOffset(valuePos(nodeId, slot), value);
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
                        return null;
                    }
                    case STATE_VALUE -> {
                        long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        int oldKey = keyStore.readInt(oldKeyId);
                        int oldValue = loadIntOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            if (oldValue != value) {
                                storeIntOffset(valuePos(nodeId, slot), value);
                            }
                            return oldValue;
                        }
                        nextHashMap.put(oldKey, oldValue);
                        nextHashMap.put(key, value);
                        writeState(nodeId, slot, STATE_NEXT_LEVEL);
                        storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        return null;
                    }
                    case STATE_NEXT_LEVEL -> {
                        return nextHashMap.put(key, value);
                    }
                    default -> throw new IOException("unknown state: " + state);
                }
            }
            return null;
        } finally {
            unlockBuffer(bufIdx);
        }
    }

    private byte[] hashBytes(int key) {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key & 0xFFFFFFFFL);
        return b;
    }

    private void storeHashBytes(byte[] bytes, int hashOffset, long value) {
        int n = Math.min(hashLen, bytes.length);
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (value >> ((hashOffset + i) * 8));
        }
    }

    /**
     * 查询 hash32 对应的 value。
     * @param key
     * @return 
     * @throws java.io.IOException 
     */
    public Integer get(int key) throws IOException {
        return getByHash(key, hashBytes(key));
    }

    /**
     * 查询 hash32 对应的 value（hash32 必须为 8 字节）。
     *
     * @param key
     * @param hash32
     * @return value，不存在返回 null
     * @throws java.io.IOException
     */
    public Integer get(int key,byte[] hash32) throws IOException {
        if (hash32 == null || hash32.length != hashLen) {
            return null;
        }
        return getByHash(key, hash32);
    }

    private Integer getByHash(int key, byte[] hashes) throws IOException {
        long nodeId = 0;
        for (int level = 0; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state;
            if (level < hashLen - 1) {
                    state = readState(nodeId, slot);
                    if (state == STATE_CHILD) {
                        long child = loadU32ByOffset(valuePos(nodeId, slot));
                       
                        if (child <= 0) {
                            return null;
                        }
                        nodeId = child;
                        continue;//使用下一个哈希 level++ -> slot。
                    }
                    if (state == STATE_VALUE) {
                        long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        
                        int storedKey = keyStore.readInt(keyId);
                        if (storedKey != key) {
                            return null;
                        }
                        int v = loadIntOffset(valuePos(nodeId, slot));
                        
                        return v;
                    }
                    return null;
               
            }

                state = readState(nodeId, slot);
                if (state == STATE_VALUE) {
                    long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                    
                    int storedKey = keyStore.readInt(keyId);
                    if (storedKey != key) {
                        return null;
                    }
                    int v = loadIntOffset(valuePos(nodeId, slot));
                   
                    return v;
                }
                if (state == STATE_NEXT_LEVEL) {
                    Integer v = nextHashMap == null ? null : nextHashMap.get(key);
                    
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
    public Integer remove(int key) throws IOException {
        return remove(key, hashBytes(key));
    }

    /**
     * 删除 hash32 对应的映射（hash32 必须为 8 字节）。
     * @param key
     * @param hash32
     * @return 
     * @throws java.io.IOException
     */
    public Integer remove(int key,byte[] hash32) throws IOException {
        if (hash32 == null || hash32.length != hashLen) {
            return null;
        }
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        try {
            for (int level = 0; level < hashLen; level++) {
                int slot = hash32[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    switch (state) {
                        case STATE_CHILD -> {
                            long child = loadU32ByOffset(valuePos(nodeId, slot));
                            
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
                            long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                            int storedKey = keyStore.readInt(keyId);
                            if (storedKey != key) {
                                return null;
                            }
                            int v = loadIntOffset(valuePos(nodeId, slot));
                            writeState(nodeId, slot, STATE_EMPTY);
                            storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                            storeIntOffset(valuePos(nodeId, slot), 0);
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
                        long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        int storedKey = keyStore.readInt(keyId);
                        if (storedKey != key) {
                            return null;
                        }
                        int v = loadIntOffset(valuePos(nodeId, slot));
                        writeState(nodeId, slot, STATE_EMPTY);
                        storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        storeIntOffset(valuePos(nodeId, slot), 0);
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
                    case STATE_NEXT_LEVEL -> {
                        return  nextHashMap.remove(key);
                    }
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
     * 返回当前映射条目数
     * @return 
     */
    public long sizeLong() {
        headerOpLockWrite.lock();
        try {
            return size;
        } finally {
            headerOpLockWrite.unlock();
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

    private long allocateKeyId(int key) throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = nextKeyId;
            nextKeyId++;
            headerBuffer.putLong(HDR_NEXT_KEY_ID, nextKeyId);
            dirty(0L);
            keyStore.writeInt(id, 0, key);
            return id;
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private long loadKeyIndexOffset(long position) throws IOException {
        return switch (keyStoreLen) {
            case 2 -> loadU16ByOffset(position);
            case 4 -> loadU32ByOffset(position);
            case 8 -> loadLongOffset(position);
            default -> throw new RuntimeException("invalid keyStoreLen -> " + keyStoreLen);
        };
    }

    private void storeKeyIndexOffset(long position, long keyId) throws IOException {
        switch (keyStoreLen) {
            case 2 -> storeShortOffset(position, (short) keyId);
            case 4 -> storeIntOffset(position, (int) keyId);
            case 8 -> storeLongOffset(position, keyId);
            default -> throw new RuntimeException("invalid keyStoreLen -> " + keyStoreLen);
        }
    }

    private long nodeBase(long nodeId) {
        return HEADER_SIZE + nodeId * (long) this.dataUnitSize;
    }

    private long bitmapPos(long nodeId, int slot) {
        return nodeBase(nodeId) + (slot/4);
    }

    private long valuePos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + keyStoreLen*256 + (long) slot * SLOT_PAYLOAD_BYTES;
    }
    
    private long keyPos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * keyStoreLen;
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

    private void writeState(long nodeId, int slot, int state) throws IOException {
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
        return get((Integer)key) != null;
    }

    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof Long)) {
            return false;
        }
        Long v = (Long) value;
        for (Integer cur : values()) {
            if (Objects.equals(cur, v)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Integer get(Object key) {
        try {
            return get(((Integer)key).intValue());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public Integer put(Integer key, Integer value) {
        try {
            return put(key.intValue(),value.intValue());
        } catch (IOException ex) {
             throw new RuntimeException(ex);
        }
    }

    @Override
    public Integer remove(Object key) {
        try {
            return remove(((Integer)key).intValue());
        } catch (IOException ex) {
           throw new RuntimeException(ex);
        }
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends Integer> m) {
        for (Entry<? extends Integer, ? extends Integer> e : m.entrySet()) {
            put(e.getKey(), e.getValue());
        }
    }

    @Override
    public void clear() {
        long nodeId = 0;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        try {
            loadBufferForUpdate(bufIdx);
            for (int slot = 0; slot < 256; slot++) {
                int state = readState(nodeId, slot);
                if (state == STATE_EMPTY) {
                    continue;
                }
                writeState(nodeId, slot, STATE_EMPTY);
                storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                storeLongOffset(valuePos(nodeId, slot), 0L);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            unlockBuffer(bufIdx);
        }
        headerOpLockWrite.lock();
        try {
            size = 0;
            headerBuffer.putLong(HDR_SIZE, size);
            dirty(0L);
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    @Override
    public Set<Integer> keySet() {
        return new AbstractSet<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                Iterator<Entry<Integer, Integer>> it = entrySet().iterator();
                return new Iterator<Integer>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Integer next() {
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
    public Collection<Integer> values() {
        return new AbstractCollection<Integer>() {
            @Override
            public Iterator<Integer> iterator() {
                Iterator<Entry<Integer, Integer>> it = entrySet().iterator();
                return new Iterator<Integer>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public Integer next() {
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
    public Set<Entry<Integer, Integer>> entrySet() {
        return new AbstractSet<Entry<Integer, Integer>>() {
            @Override
            public Iterator<Entry<Integer, Integer>> iterator() {
                final int[] keys = toKeyArray();
                return new Iterator<Entry<Integer, Integer>>() {
                    private int i = 0;
                    private Integer lastKey = null;
                    private boolean canRemove = false;

                    @Override
                    public boolean hasNext() {
                        return i < keys.length;
                    }

                    @Override
                    public Entry<Integer, Integer> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        int k = keys[i++];
                        final Integer entryKey = k;
                        lastKey = entryKey;
                        canRemove = true;
                        return new Entry<Integer, Integer>() {
                            @Override
                            public Integer getKey() {
                                return entryKey;
                            }

                            @Override
                            public Integer getValue() {
                                return DsHashMap.this.get(entryKey);
                            }

                            @Override
                            public Integer setValue(Integer value) {
                                return DsHashMap.this.put(entryKey, value);
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
                        DsHashMap.this.remove(lastKey);
                        canRemove = false;
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

    private static final class Integers {
        int[] a;
        int n;

        Integers(int cap) {
            a = new int[Math.max(16, cap)];
        }

        void add(int v) {
            if (n >= a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    private int[] toKeyArray() {
        Integers out = new Integers(size());
        try {
            collectKeys(out, 0, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (nextHashMap != null) {
            int[] more = nextHashMap.toKeyArray();
            for (int v : more) {
                out.add(v);
            }
        }
        return out.toArray();
    }

    private void collectKeys(Integers out, long nodeId, int level) throws IOException {
        for (int slot = 0; slot < 256; slot++) {
            int state = readState(nodeId, slot);
            switch (state) {
                case STATE_EMPTY -> {
                }
                case STATE_VALUE -> {
                    long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                    int key = keyStore.readInt(keyId);
                    out.add(key);
                }
                case STATE_CHILD -> {
                    long child = loadLongOffset(valuePos(nodeId, slot));
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
