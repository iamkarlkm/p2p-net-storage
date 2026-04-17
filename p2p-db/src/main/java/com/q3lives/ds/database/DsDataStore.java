package com.q3lives.ds.database;

import com.q3lives.ds.collections.*;
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
import com.q3lives.ds.core.DsBytes;
import com.q3lives.ds.core.DsObject;


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
public class DsDataStore extends DsObject implements Map<Long, Long> {
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

//    private static final int BITMAP_BYTES = 128;
    private static final int BITMAP_BYTES = 64;//256*2bit(位图)/8   00->empty,01->value,10->sub layer,11->next hashmap
    private static final int SLOT_PAYLOAD_BYTES = 8;
//    private static final int NODE_SIZE = BITMAP_BYTES + 256 * SLOT_PAYLOAD_BYTES;

    private long nextNodeId;
    private long size;
    private final long[] zeroNode;
    private long nextKeyId;
    
    private int hashOffset = 0;
    private int hashLen = 2;
    private int idStoreSize = 2;
    
    
    private final DsObject keyIdStore;
    private final DsObject valueIdStore;
    
    private final DsBytes dataStore;
    
    private final DsDataStore nextHashMap;
    
    

    /**
     * 创建一个 key->value 的 trie 映射文件。
     *
     * @param file
     */
    public DsDataStore(File file) {
        super(file, 2624);//2字节索引 8位哈希 => 256*2-bit(位图)/8 + 256*2-byte(key) + 256*8-byte(value) = 64+512+2048 =2624
        zeroNode = new long[this.dataUnitSize / 8];
        initHeader();
        dataStore = new DsBytes(file.getParent(),"DsDataStore");//数据分离存储到底层buckets
        
        //16位哈希第一级。
        File keyId16File = new File(file.getAbsolutePath()+".k16");
        keyIdStore = new DsObject(keyId16File,8);//2^15个key(8字节64位)索引
        File valueId16File = new File(file.getAbsolutePath()+".v16");
        valueIdStore = new DsObject(valueId16File,8);//2^15个key(8字节64位)索引
        
       //先创建64位第三级哈希存储,以便关联到上一级-32位
        File keyId64File = new File(file.getAbsolutePath()+".k64");
        DsObject keyIdStore64 = new DsObject(keyId64File,8);//2^64个key(8字节64位)索引
        File valueId64File = new File(file.getAbsolutePath()+".v64");
        DsObject valueIdStore64 = new DsObject(valueId64File,8);//2^64个key(8字节64位)索引
        
        File nextHashMap64File = new File(file.getAbsolutePath()+".m64");//8字节索引 8位哈希 => 256*2bit(位图)/8 + 256*8byte(key) + 256*8byte(value) = 64+512+2048 =4160
        DsDataStore nextHashMap64 = new DsDataStore(nextHashMap64File,4,4,4160,8,keyIdStore64,valueIdStore64,dataStore,null);//64位哈希是目前实现最后一级
        
        
        File keyId32File = new File(file.getAbsolutePath()+".k32");
        DsObject keyIdStore32 = new DsObject(keyId32File,8);//2^31个key(8字节64位)索引
        File valueId32File = new File(file.getAbsolutePath()+".v32");
        DsObject valueIdStore32 = new DsObject(valueId32File,8);//2^31个key(8字节64位)索引
        //16 -> 32 32位哈希第二级。
        File nextHashMap32File = new File(file.getAbsolutePath()+".m32");//4字节索引 8位哈希 => 256*2bit(位图)/8 + 256*4byte(key) + 256*8byte(value) = 64+512+2048 =3136
        nextHashMap = new DsDataStore(nextHashMap32File,2,2,3136,4,keyIdStore32,valueIdStore32,dataStore,nextHashMap64);
    }
    


    /**
     * 创建下一级哈希存储
     * @param file
     * @param hashOffset
     * @param hashLen
     * @param dataSize
     * @param idStoreSize
     * @param keyIdStore
     * @param valueIdStore
     * @param nextHashMap 
     */
    private DsDataStore(File file,int hashOffset,int hashLen,int dataSize,int idStoreSize,DsObject keyIdStore,DsObject valueIdStore,DsBytes dataStore,DsDataStore nextHashMap) {
        super(file, dataSize);
        zeroNode = new long[dataSize/ 8];
        initHeader();
        this.hashOffset = hashOffset;
        this.hashLen = hashLen;
        this.idStoreSize = idStoreSize;
        this.keyIdStore = keyIdStore;
        this.valueIdStore = valueIdStore;
        this.dataStore = dataStore;
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

    public Long put(long key, long value) throws IOException {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key);
        return put(b, key,value);
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
                            nodeId = loadLongOffset(valuePos(nodeId, slot));
                            long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                            if (nextBuf != bufIdx) {
                                loadBufferForUpdate(nextBuf);
                                unlockBuffer(bufIdx);
                                bufIdx = nextBuf;
                            }
                            //继续处理下一个哈希。
                           continue;//使用下一个哈希 level++ -> slot。
                        }
                        case STATE_EMPTY -> {
                            //slot 空
                            writeState(nodeId, slot, STATE_VALUE);
                            long keyId = allocateKeyId(key);
                            storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                            storeLongOffset(valuePos(nodeId, slot), value);
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
                            long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                            long oldKey =  keyIdStore.readLong(oldKeyId);
                            long oldValue = loadLongOffset(valuePos(nodeId, slot));
                            if(oldKey == key){
                                if(oldValue != value){// Key同，值不同，更新。
                                    storeLongOffset(valuePos(nodeId, slot), value);
                                }
                                return oldValue;
                            }
                            long child = allocateNodeId();
                            writeState(nodeId, slot, STATE_CHILD);
                            //storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                            storeLongOffset(valuePos(nodeId, slot), child);
                            //深入下一层,分别存储两个值。
                            int nextLevel = level+1;
                            putInner(child, hashBytes(oldKey), oldKey, oldValue, nextLevel, false);
                            putInner(child, hashes, key, value, nextLevel, true);
                            return null;
                        }
                        default -> {
                             throw new IOException("invalid state: " + state);
                        }
                    }
                   
                }

                switch (state) {
                    case STATE_EMPTY -> {
                        writeState(nodeId, slot, STATE_VALUE);
                        long keyId = allocateKeyId(key);
                        storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                        storeLongOffset(valuePos(nodeId, slot), value);
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
                        long oldKey =  keyIdStore.readLong(oldKeyId);
                        long oldValue = loadLongOffset(valuePos(nodeId, slot));
                        if(oldKey == key){
                            if(oldValue != value){// Key同，值不同，更新。
                                storeLongOffset(valuePos(nodeId, slot), value);
                            }
                            return oldValue;
                        }
                        
                        //16位key store hashmap 升级到32位key store hashmap 或 32位key store hashmap 升级到64位key store hashmap
                        nextHashMap.put(oldKey, oldValue);
                        nextHashMap.put(key, value);
                        writeState(nodeId, slot, STATE_NEXT_LEVEL);
                        //storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        return null;
                    }
                    case STATE_NEXT_LEVEL -> {
                        return nextHashMap.put(key, value);
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
    
   
    private Long putInner(long startNodeId, byte[] hashes, long key, long value, int currentLevel, boolean countSize) throws IOException {
        long nodeId = startNodeId;
        long bufIdx = nodeBase(nodeId) / BLOCK_SIZE;
        loadBufferForUpdate(bufIdx);
        for (int level = currentLevel; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state = readState(nodeId, slot);
            if (level < hashLen - 1) {
                switch (state) {
                    case STATE_CHILD -> {
                        nodeId = loadLongOffset(valuePos(nodeId, slot));
                        long nextBuf = nodeBase(nodeId) / BLOCK_SIZE;
                        if (nextBuf != bufIdx) {
                            loadBufferForUpdate(nextBuf);
                            unlockBuffer(bufIdx);
                            bufIdx = nextBuf;
                        }
                        continue;//使用下一个哈希 level++ -> slot。
                    }
                    case STATE_EMPTY -> {
                        writeState(nodeId, slot, STATE_VALUE);
                        long keyId = allocateKeyId(key);
                        storeKeyIndexOffset(keyPos(nodeId, slot), keyId);
                        storeLongOffset(valuePos(nodeId, slot), value);
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
                        long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        long oldKey = keyIdStore.readLong(oldKeyId);
                        long oldValue = loadLongOffset(valuePos(nodeId, slot));
                        if (oldKey == key) {
                            if (oldValue != value) {
                                storeLongOffset(valuePos(nodeId, slot), value);
                            }
                            unlockBuffer(bufIdx);
                            return oldValue;
                        }
                        long child = allocateNodeId();
                        writeState(nodeId, slot, STATE_CHILD);
                        //storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        storeLongOffset(valuePos(nodeId, slot), child);
                        int nextLevel = level + 1;
                        putInner(child, hashBytes(oldKey), oldKey, oldValue, nextLevel, false);
                        putInner(child, hashes, key, value, nextLevel, countSize);
                        unlockBuffer(bufIdx);
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
                    storeLongOffset(valuePos(nodeId, slot), value);
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
                    long oldKeyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                    long oldKey = keyIdStore.readLong(oldKeyId);
                    long oldValue = loadLongOffset(valuePos(nodeId, slot));
                    if (oldKey == key) {
                        if (oldValue != value) {
                            storeLongOffset(valuePos(nodeId, slot), value);
                        }
                        unlockBuffer(bufIdx);
                        return oldValue;
                    }
                    nextHashMap.put(oldKey, oldValue);
                    nextHashMap.put(key, value);
                    writeState(nodeId, slot, STATE_NEXT_LEVEL);
                    //storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                    unlockBuffer(bufIdx);
                    return null;
                }
                case STATE_NEXT_LEVEL -> {
                    unlockBuffer(bufIdx);
                    return nextHashMap.put(key, value);
                }
                default -> throw new IOException("unknown state: " + state);
            }
        }
        unlockBuffer(bufIdx);
        return null;
    }

    /**
     * 查询 hash64 对应的 value。
     * @param hash64
     * @return 
     * @throws java.io.IOException 
     */
    public Long get(long hash64) throws IOException {
        byte[] b = hashBytes(hash64);
        return getByHash(b, hash64);
    }

    /**
     * 查询 hash64 对应的 value（hash64 必须为 8 字节）。
     *
     * @param hash64
     * @return value，不存在返回 null
     * @throws java.io.IOException
     */
    public Long get(byte[] hash64) throws IOException {
        if (hash64 == null || hash64.length != hashLen) {
            return null;
        }
        try {
            long nodeId = 0;
            for (int level = 0; level < hashLen; level++) {
                int slot = hash64[level] & 0xFF;
                int state = readState(nodeId, slot);
                if (level < hashLen - 1) {
                    if (state == STATE_CHILD) {
                        long child = loadLongOffset(valuePos(nodeId, slot));
                        if (child <= 0) {
                            return null;
                        }
                        nodeId = child;
                        continue;
                    }

                    if (state == STATE_VALUE) {
                        long v = loadLongOffset(valuePos(nodeId, slot));
                        return v ;
                    }
                    return null;
                }

                switch (state) {
                    case STATE_VALUE -> {
                        long v = loadLongOffset(valuePos(nodeId, slot));
                        return v;
                    }
                    case STATE_NEXT_LEVEL -> {
                        if (nextHashMap == null) {
                            return null;
                        }
                        if (nextHashMap.hashLen != hash64.length) {
                            return null;
                        }
                        return nextHashMap.get(hash64);
                    }
                    case STATE_CHILD -> throw new IOException("invalid state: " + state);
                    default -> {
                    }
                }

                return null;
            }
            return null;
        } finally {
        }
    }

    private Long getByHash(byte[] hashes, long key) throws IOException {
        long nodeId = 0;
        for (int level = 0; level < hashLen; level++) {
            int slot = hashes[level] & 0xFF;
            int state;
            if (level < hashLen - 1) {
                while (true) {
                    state = readState(nodeId, slot);
                    if (state == STATE_CHILD) {
                        long child = loadLongOffset(valuePos(nodeId, slot));
                        int state2 = readState(nodeId, slot);
                        if (state2 != state) {
                            continue;
                        }
                        if (child <= 0) {
                            return null;
                        }
                        nodeId = child;
                        break;
                    }
                    if (state == STATE_VALUE) {
                        long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        long v = loadLongOffset(valuePos(nodeId, slot));
                        int state2 = readState(nodeId, slot);
                        if (state2 != state) {
                            continue;
                        }
                        long storedKey = keyIdStore.readLong(keyId);
                        if (storedKey != key) {
                            return null;
                        }
                        return v;
                    }
                    return null;
                }
                continue;
            }

            while (true) {
                state = readState(nodeId, slot);
                if (state == STATE_VALUE) {
                    long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                    long v = loadLongOffset(valuePos(nodeId, slot));
                    int state2 = readState(nodeId, slot);
                    if (state2 != state) {
                        continue;
                    }
                    long storedKey = keyIdStore.readLong(keyId);
                    if (storedKey != key) {
                        return null;
                    }
                    return v;
                }
                if (state == STATE_NEXT_LEVEL) {
                    if (nextHashMap == null) {
                        return null;
                    }
                    Long v = nextHashMap.get(key);
                    int state2 = readState(nodeId, slot);
                    if (state2 != state) {
                        continue;
                    }
                    return v;
                }
                return null;
            }
        }
        return null;
    }

    /**
     * 删除 key 对应的映射。
     * @param key
     * @return 
     * @throws java.io.IOException
     */
    public boolean remove(long key) throws IOException {
        return remove(key, hashBytes(key));
    }

    /**
     * 删除 hash64 对应的映射（hash64 必须为 8 字节）。
     * @param key
     * @param hash64
     * @return 
     * @throws java.io.IOException
     */
    public boolean remove(long key,byte[] hash64) throws IOException {
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
                if (level < hashLen - 1) {
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
                            long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                            long storedKey = keyIdStore.readLong(keyId);
                            if (storedKey != key) {
                                return false;
                            }
                            writeState(nodeId, slot, STATE_EMPTY);
                            storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                            storeLongOffset(valuePos(nodeId, slot), 0L);
                            headerOpLockWrite.lock();
                            try {
                                size--;
                                headerBuffer.putLong(HDR_SIZE, size);
                                dirty(0L);
                            } finally {
                                headerOpLockWrite.unlock();
                            }
                            return true;
                        }
                        default -> throw new IOException("invalid state: " + state);
                    }
                }

                switch (state) {
                    case STATE_VALUE -> {
                        long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                        long storedKey = keyIdStore.readLong(keyId);
                        if (storedKey != key) {
                            return false;
                        }
                        writeState(nodeId, slot, STATE_EMPTY);
                        storeKeyIndexOffset(keyPos(nodeId, slot), 0);
                        storeLongOffset(valuePos(nodeId, slot), 0L);
                        headerOpLockWrite.lock();
                        try {
                            size--;
                            headerBuffer.putLong(HDR_SIZE, size);
                            dirty(0L);
                        } finally {
                            headerOpLockWrite.unlock();
                        }
                        return true;
                    }
                    case STATE_NEXT_LEVEL -> {
                        if (nextHashMap == null) {
                            return false;
                        }
                        return nextHashMap.remove(key);
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
     * 返回当前映射条目数
     * @return 
     * @throws java.io.IOException
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
     * 同步并关闭索引。
     */
    public void close() {
        sync();
    }

    @Override
    public int size() {
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

    @Override
    public boolean isEmpty() {
        return size() == 0;
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
            old = get(k);
            if (old == null) {
                return null;
            }
            boolean removed = remove(k);
            return removed ? old : null;
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
        if (nextHashMap != null) {
            nextHashMap.clear();
        }
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
                return DsDataStore.this.size();
            }

            @Override
            public boolean contains(Object o) {
                return DsDataStore.this.containsKey(o);
            }

            @Override
            public boolean remove(Object o) {
                return DsDataStore.this.remove(o) != null;
            }

            @Override
            public void clear() {
                DsDataStore.this.clear();
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
                return DsDataStore.this.size();
            }

            @Override
            public void clear() {
                DsDataStore.this.clear();
            }
        };
    }

    @Override
    public Set<Entry<Long, Long>> entrySet() {
        return new AbstractSet<Entry<Long, Long>>() {
            @Override
            public Iterator<Entry<Long, Long>> iterator() {
                final long[] keys = toKeyArray();
                return new Iterator<Entry<Long, Long>>() {
                    private int i = 0;
                    private Long lastKey = null;
                    private boolean canRemove = false;

                    @Override
                    public boolean hasNext() {
                        return i < keys.length;
                    }

                    @Override
                    public Entry<Long, Long> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        long k = keys[i++];
                        final Long entryKey = k;
                        lastKey = entryKey;
                        canRemove = true;
                        return new Entry<Long, Long>() {
                            @Override
                            public Long getKey() {
                                return entryKey;
                            }

                            @Override
                            public Long getValue() {
                                return DsDataStore.this.get(entryKey);
                            }

                            @Override
                            public Long setValue(Long value) {
                                return DsDataStore.this.put(entryKey, value);
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
                        DsDataStore.this.remove(lastKey);
                        canRemove = false;
                    }
                };
            }

            @Override
            public int size() {
                return DsDataStore.this.size();
            }

            @Override
            public void clear() {
                DsDataStore.this.clear();
            }
        };
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
                case STATE_EMPTY -> {
                }
                case STATE_VALUE -> {
                    long keyId = loadKeyIndexOffset(keyPos(nodeId, slot));
                    long key = keyIdStore.readLong(keyId);
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

    private long allocateKeyId(long key) throws IOException {
        headerOpLockWrite.lock();
        try {
            long id = nextKeyId;
            nextKeyId++;
            headerBuffer.putLong(HDR_NEXT_KEY_ID, nextKeyId);
            dirty(0L);
            keyIdStore.writeLong(id, key);
            return id;
        } finally {
            headerOpLockWrite.unlock();
        }
    }

    private long loadKeyIndexOffset(long position) throws IOException {
        return switch (idStoreSize) {
            case 2 -> loadU16ByOffset(position);
            case 4 -> loadU32ByOffset(position);
            case 8 -> loadLongOffset(position);
            default -> throw new RuntimeException("invalid idStoreSize -> " + idStoreSize);
        };
    }

    private void storeKeyIndexOffset(long position, long keyId) throws IOException {
        switch (idStoreSize) {
            case 2 -> storeShortOffset(position, (short) keyId);
            case 4 -> storeIntOffset(position, (int) keyId);
            case 8 -> storeLongOffset(position, keyId);
            default -> throw new RuntimeException("invalid idStoreSize -> " + idStoreSize);
        }
    }

    private byte[] hashBytes(long key) {
        byte[] b = new byte[hashLen];
        storeHashBytes(b, hashOffset, key);
        return b;
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
        return nodeBase(nodeId) + (slot/4);
    }

    private long valuePos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + idStoreSize*256 + (long) slot * SLOT_PAYLOAD_BYTES;
    }
    
    private long keyPos(long nodeId, int slot) {
        return nodeBase(nodeId) + BITMAP_BYTES + (long) slot * idStoreSize;
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

    private void storeHashBytes(byte[] bytes, int hashOffset, long value) {
        int n = Math.min(hashLen, bytes.length);
        for (int i = 0; i < n; i++) {
            bytes[i] = (byte) (value >> ((hashOffset + i) * 8));
        }
    }

    private void writeState(long nodeId, int slot, int state) throws IOException {
        long pos = bitmapPos(nodeId, slot);
        int stateByte = loadU8ByOffset(pos);
        byte stateValue = (byte) setStateValue(stateByte, slot%4, state);
        storeByteOffset(pos, stateValue);
    }
}
