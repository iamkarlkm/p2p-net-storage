package ds;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantLock;

public class DsHashMap extends DsObject{
    public static final String MAGIC_STRING = ".MAP";
    public static final int ELEMENT_SIZE = 16;
    public static final int MAGIC_STRING_LENGTH = MAGIC_STRING.getBytes().length;
    public static final int DEFAULT_SEGMENT_LOCKS_COUNT = 16; //必须是16,对应level 0
    public static final int TOTAL_OFFSET = 4;
    public static final int NEXT_OFFSET_POSITION = 8;
    
    private final ReentrantLock[] segmentLocks = new ReentrantLock[DEFAULT_SEGMENT_LOCKS_COUNT];
    private final ReentrantLock headerOpLock = new ReentrantLock();
    
    private final long[] headKeys = new long[DEFAULT_SEGMENT_LOCKS_COUNT];
    private final long[] headValues = new long[DEFAULT_SEGMENT_LOCKS_COUNT];
    
    private int rootHeadSize = 20;
    private int headsOffset;
    private int isHasValueBitmap = 0;
    private long total = 0;
    private long nextOffset;
    
    // Level configurations
    private int[] levels = new int[]{4, 10, 10, 10, 10, 10, 10};
    private int[] levelsShift = new int[levels.length];
    private int[] levelsMask = new int[levels.length];
    private int[] levelsDataCount = new int[levels.length];
    private int[] levelsAreaSize = new int[levels.length];
    private int[] levelsHeadSize = new int[levels.length];

    public DsHashMap(File dataFile) {
        super(dataFile, ELEMENT_SIZE);//128位
        headsOffset = rootHeadSize + MAGIC_STRING_LENGTH;
        
        int shift = 0;
        for (int i = 0; i < levels.length; i++) {
            levelsShift[i] = shift;
            int bits = levels[i];
            if(i==0){
                levelsMask[i] = (1 << bits) - 1;
            }else{
                if(i==1) levelsMask[i] = 0xffc;
                else levelsMask[i] = (1 << bits) - 1;
            }
            levelsDataCount[i] = 1 << bits;
            levelsAreaSize[i] = calcDataAreaSize(bits);
            levelsHeadSize[i] = calcHeadSize(bits);
            shift += bits;
        }
        
        // Initial nextOffset for new file
        nextOffset = headsOffset + DEFAULT_SEGMENT_LOCKS_COUNT * ELEMENT_SIZE;

        for (int i = 0; i < DEFAULT_SEGMENT_LOCKS_COUNT; i++) {
            segmentLocks[i] = new ReentrantLock();
        }
        
        try {
            checkHeader();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final int calcHeadSize(int bits) {
        return ((1<<bits)*2/32)*4;
    }
    private static final int calcDataAreaSize(int bits) {
        return (1<<bits) * ELEMENT_SIZE;
    }
    
    private long allocateNextOffset(int size) {
        long base = nextOffset;
        nextOffset += size;
        // No explicit grow needed, loadBuffer will handle it
        headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
        return base;
    }

    private void checkHeader() throws IOException {
        // Just load buffer 0, it will grow file if needed
        headerBuffer = this.loadBufferForUpdate(0L);
        byte[] magicBytes = new byte[MAGIC_STRING_LENGTH];
        headerBuffer.get(magicBytes, 0, MAGIC_STRING_LENGTH);
        String magic = MAGIC_STRING;
        if(java.util.Arrays.compare(magicBytes, magic.getBytes())==0){
            total = headerBuffer.getInt(TOTAL_OFFSET);
            nextOffset = headerBuffer.getLong(NEXT_OFFSET_POSITION);
            isHasValueBitmap = headerBuffer.getInt(rootHeadSize);
            for (int i = 0; i < DEFAULT_SEGMENT_LOCKS_COUNT; i++) {
               headKeys[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE);
               headValues[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE + 8);
           }
        }else{
            headerBuffer.position(0);
            headerBuffer.put(magic.getBytes());
            headerBuffer.putInt(rootHeadSize, 0);
            for(int i=0;i<DEFAULT_SEGMENT_LOCKS_COUNT;i++){
                headerBuffer.putLong(headsOffset + i * ELEMENT_SIZE, 0);
                headerBuffer.putLong(headsOffset + i * ELEMENT_SIZE + 8, 0);
            }
            total = 0;
            headerBuffer.putInt(TOTAL_OFFSET, 0);
            nextOffset = headsOffset + DEFAULT_SEGMENT_LOCKS_COUNT * ELEMENT_SIZE;
            headerBuffer.putLong(NEXT_OFFSET_POSITION, nextOffset);
            dirty(0L);
        }
    }

    private void addTotal(int count){
        headerOpLock.lock();
        try {
            total += count;
            headerBuffer.putInt(TOTAL_OFFSET, (int)total);
            dirty(0L);
        }finally {
            headerOpLock.unlock();
        }
    }

    public Long put(long key, long value) throws IOException, InterruptedException {
        int level = 0;
        int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
        segmentLocks[hash].lock();
        try {
            int hasValue = getHasValue(isHasValueBitmap,hash*2,2);
            switch (hasValue) {
                case 3 -> {
                    long oldKey = headKeys[hash];
                    long oldValue = headValues[hash];
                    if (oldKey == key) {
                        headValues[hash] = value;
                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH + hash * ELEMENT_SIZE + 8, value);
                        return oldValue;
                    }
                    int nextLvl = level + 1;
                    long base = updateHeaderWithOldValue(level, 1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl], hash, key, value);
                    updateValues(base, level, hash, levelsAreaSize[nextLvl], levelsHeadSize[nextLvl], key, value, oldKey, oldValue);
                    return null;
                }
                case 2 -> {
                    long oldPtr = headKeys[hash];
                    return put(oldPtr, 1, key, value);
                }
                case 0 -> {
                    updateHeaderByValue(1, hash, key, value);
                    return null;
                }
                default -> throw new RuntimeException("Unexpected bits: " + hasValue);
            }
        } finally {
            segmentLocks[hash].unlock();
        }
    }

    private Long put(long base, int level, long key, long value) throws InterruptedException, IOException {
        int areaSize = levelsAreaSize[level];
        int headSize = levelsHeadSize[level];
        int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        long bufferIndex = base/BLOCK_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        int isHasValue = buffer.getInt(offsetIsHasValue);
        int hasValue = getHasValue(isHasValue, bitPosition, 2);
        
        switch (hasValue) {
            case 3 -> {
                long oldKey = buffer.getLong(positionValue);
                long oldValue = buffer.getLong(positionValue+8);
                if (oldKey == key) {
                    buffer.putLong(positionValue+8, value);
                    return oldValue;
                }
                int nextLvl = level + 1;
                long baseNew = updateHeaderWithOldValue(level,1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl],hash,key,value);
                buffer.putLong(positionValue,baseNew);
                unlockBufferForUpdate(bufferIndex);
                updateValues(baseNew,level,hash,levelsAreaSize[nextLvl],levelsHeadSize[nextLvl],key,value,oldKey,oldValue);
                return null;
            }
            case 2 -> {
                long basePtr = buffer.getLong(positionValue);
                return put(basePtr, level+1, key, value);
            }
            case 0 -> {
                isHasValue = setBit(isHasValue, bitPosition);
                isHasValue = setBit(isHasValue, bitPosition+1);
                buffer.putInt(offsetIsHasValue,isHasValue);
                buffer.putLong(positionValue,key); 
                buffer.putLong(positionValue+8,value);
                unlockBufferForUpdate(bufferIndex);
                addTotal(1);
                return null;
            }
            default -> throw new RuntimeException("Unexpected bits: " + hasValue);
        }
    }

    public Long get(long key) {
       int level = 0;
       int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
       segmentLocks[hash].lock();
       try {
           int hasValue = getHasValue(isHasValueBitmap,hash*2,2);
           switch (hasValue) {
               case 3 -> {
                   long oldKey = headKeys[hash];
                   if (oldKey == key) {
                       return headValues[hash];
                   }
                   return null;
               }
               case 2 -> {
                   long ptr = headKeys[hash];
                   try {
                       return get(ptr,1,key);
                   } catch (InterruptedException | IOException e) {
                       throw new RuntimeException(e);
                   }
               }
               case 0 -> {
                   return null;
               }
               default -> throw new RuntimeException("Unexpected bits: " + hasValue);
           }
       } finally {
           segmentLocks[hash].unlock();
       }
   }

    Long get(long base,int level,long key) throws InterruptedException, IOException {
        int headSize = levelsHeadSize[level];
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;
        int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        long bufferIndex = base/BLOCK_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        
        MappedByteBuffer buffer = loadBuffer(bufferIndex);
        int isHasValue = buffer.getInt(offsetIsHasValue);
        int hasValue = getHasValue(isHasValue, bitPosition, 2);
        
        switch (hasValue) {
            case 3 -> {
                long oldKey = buffer.getLong(positionValue);
                long oldValue = buffer.getLong(positionValue+8);
                if(oldKey == key){
                    return oldValue;
                }
                return null;
            }
            case 2 -> {
                long basePtr = buffer.getLong(positionValue);
                return get(basePtr, level+1, key);
            }
            default -> { return null; }
        }
    }

    public boolean remove(long key){
       int level = 0;
       int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
       segmentLocks[hash].lock();
       try {
           int hasValue = getHasValue(isHasValueBitmap,hash*2,2);
           if(hasValue == 3){
               long oldKey = headKeys[hash];
               if (oldKey == key) {
                   minusTotalByHash(1, hash);
                   return true;
               }
               return false;
           }else if(hasValue == 2){
               long ptr = headKeys[hash];
               try {
                   return remove(ptr,1,key);
               } catch (InterruptedException | IOException e) {
                   throw new RuntimeException(e);
               }
           }
           return false;
       } finally {
           segmentLocks[hash].unlock();
       }
    }

    boolean remove(long base,int level,long key) throws InterruptedException, IOException {
        int headSize = levelsHeadSize[level];
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;
        int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
        int positionValue = offsetData+hash * ELEMENT_SIZE;
        long bufferIndex = base/BLOCK_SIZE;
        int offsetIsHasValue = offset+DsDataUtil.int2BitsMapIndex(hash);
        int bitPosition = DsDataUtil.int2BitsMapPosition(hash);
        
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        int isHasValue = buffer.getInt(offsetIsHasValue);
        int hasValue = getHasValue(isHasValue, bitPosition, 2);
        
        if(hasValue == 3){
            long oldKey = buffer.getLong(positionValue);
            if(oldKey == key){
                isHasValue = clearBit(isHasValue, bitPosition);
                isHasValue = clearBit(isHasValue, bitPosition+1);
                buffer.putInt(offsetIsHasValue, isHasValue);
                buffer.putLong(positionValue, 0);
                buffer.putLong(positionValue+8, 0);
                unlockBufferForUpdate(bufferIndex);
                addTotal(-1);
                return true;
            }
            return false;
        }else if(hasValue == 2){
            long basePtr = buffer.getLong(positionValue);
            return remove(basePtr, level+1, key);
        }
        return false;
    }

    public boolean containsKey(long key) {
       return get(key) != null;
    }

    private void updateValues(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue) throws InterruptedException, IOException {
        int nextLevel = level+1;
        if(nextLevel >= levels.length){
            return;
        }

        int hashOld = (int) ((oldKey >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);
        int hashNew = (int) ((key >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);

        if(hashOld == hashNew){
            int nextLvl2 = nextLevel + 1;
            long baseNext2 = updateHeaderWithOldValue(nextLevel, 0, levelsAreaSize[nextLvl2]+levelsHeadSize[nextLvl2], hash, key, value);
            put(baseNext2, nextLevel, oldKey, oldValue);
            put(baseNext2, nextLevel, key, value);
            return;
        }
        
        long bufferIndex = base/BLOCK_SIZE;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;
        
        int positionOld = offsetData+hashOld * ELEMENT_SIZE;
        int positionNew = offsetData+hashNew * ELEMENT_SIZE;
        
        MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
        
        int offsetIsHasValueOld = offset+DsDataUtil.int2BitsMapIndex(hashOld);
        int bitPositionOld = DsDataUtil.int2BitsMapPosition(hashOld);
        int isHasValueOld = buffer.getInt(offsetIsHasValueOld);
        isHasValueOld = setBit(isHasValueOld,bitPositionOld);
        isHasValueOld = setBit(isHasValueOld,bitPositionOld+1);
        buffer.putInt(offsetIsHasValueOld,isHasValueOld);

        int offsetIsHasValueNew = offset+DsDataUtil.int2BitsMapIndex(hashNew);
        int bitPositionNew = DsDataUtil.int2BitsMapPosition(hashNew);
        int isHasValueNew = buffer.getInt(offsetIsHasValueNew);
        isHasValueNew = setBit(isHasValueNew,bitPositionNew);
        isHasValueNew = setBit(isHasValueNew,bitPositionNew+1);
        buffer.putInt(offsetIsHasValueNew,isHasValueNew);

        buffer.putLong(positionOld, oldKey);
        buffer.putLong(positionOld + 8, oldValue);
        buffer.putLong(positionNew, key);
        buffer.putLong(positionNew + 8, value);
        unlockBufferForUpdate(bufferIndex);
        addTotal(1);
    }

    public final void updateHeaderByValue(int count,int hash,long key, long value)  {
        headerOpLock.lock();
        try {
            total += count;
            isHasValueBitmap = setBit(isHasValueBitmap,hash*2);
            isHasValueBitmap = setBit(isHasValueBitmap,hash*2+1);
            long offset = rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE;
            headKeys[hash] = key;
            headValues[hash] = value;
            headerBuffer.putInt(TOTAL_OFFSET, (int)total).putInt(rootHeadSize,isHasValueBitmap)
                    .putLong((int) offset, key).putLong((int) offset + 8, value);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }
    }

    public final long updateHeaderWithOldValue(int level,int count,int size, int hash, long key, long value)  {
        headerOpLock.lock();
        try {
            long base = allocateNextOffset(size);
            if (level == 0) {
                total += count;
                isHasValueBitmap = setBit(isHasValueBitmap,hash*2);
                isHasValueBitmap = clearBit(isHasValueBitmap,hash*2+1);
                headKeys[hash] = base;
                headValues[hash] = 0;
                headerBuffer.putInt(TOTAL_OFFSET, (int)total).putInt(rootHeadSize,isHasValueBitmap);
                headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, base)
                            .putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);
                dirty(0l);
            }
            return base;
        }finally {
            headerOpLock.unlock();
        }
    }
    
    private void minusTotalByHash(int count, int hash) {
        headerOpLock.lock();
        try {
            total -= count;
            isHasValueBitmap = clearBit(isHasValueBitmap,hash*2);
            isHasValueBitmap = clearBit(isHasValueBitmap,hash*2+1);
            headKeys[hash] = 0;
            headValues[hash] = 0;
            headerBuffer.putInt(TOTAL_OFFSET, (int)total).putInt(rootHeadSize,isHasValueBitmap);
            headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0)
                        .putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);
            dirty(0l);
        }finally {
            headerOpLock.unlock();
        }
    }

    private static int setBit(int data, int n) {
        return data | (1 << (31 - (n & 31)));
    }

    private static int clearBit(int data, int n) {
        return data & ~(1 << (31 - (n & 31)));
    }
    
    private static int getHasValue(int data, int start, int count) {
        int shift = 32 - start - count;
        return (data >>> shift) & ((1 << count) - 1);
    }
}
