import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Replace the entire updateValues method
update_values_new = """
    private void updateValues(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue) throws InterruptedException, IOException {
        int nextLevel = level+1;
        if(nextLevel >= levels.length){//达到最大层级,直接end list存储值        
            storeEndValues(false,base,level,hash,areaSize,headSize/2,key,value,oldKey,oldValue);
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
        
        long limit = base+headSize+areaSize;
        long bufferIndex = base/BLOCK_SIZE;
        long bufferIndexLimit = limit/BLOCK_SIZE;
        boolean isSameBuffer = bufferIndex == bufferIndexLimit;
        int offset = (int) (base%BLOCK_SIZE);
        int offsetData = offset+headSize;//数据区
        
        int positionOld = offsetData+hashOld * ELEMENT_SIZE;
        int positionNew = offsetData+hashNew * ELEMENT_SIZE;
        
        if(isSameBuffer){
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
        }else{//跨页
            MappedByteBuffer buffer = loadBufferForUpdate(bufferIndex);
            MappedByteBuffer bufferNext = loadBufferForUpdate(bufferIndexLimit);
            
            int offsetIsHasValueOld = offset+DsDataUtil.int2BitsMapIndex(hashOld);
            int bitPositionOld = DsDataUtil.int2BitsMapPosition(hashOld);
            int isHasValueOld = readInt(offsetIsHasValueOld, buffer, bufferNext);
            isHasValueOld = setBit(isHasValueOld,bitPositionOld);
            isHasValueOld = setBit(isHasValueOld,bitPositionOld+1);
            writeInt(offsetIsHasValueOld, isHasValueOld, buffer, bufferNext);

            int offsetIsHasValueNew = offset+DsDataUtil.int2BitsMapIndex(hashNew);
            int bitPositionNew = DsDataUtil.int2BitsMapPosition(hashNew);
            int isHasValueNew = readInt(offsetIsHasValueNew, buffer, bufferNext);
            isHasValueNew = setBit(isHasValueNew,bitPositionNew);
            isHasValueNew = setBit(isHasValueNew,bitPositionNew+1);
            writeInt(offsetIsHasValueNew, isHasValueNew, buffer, bufferNext);

            writeLong(positionOld, oldKey, buffer, bufferNext);
            writeLong(positionOld + 8, oldValue, buffer, bufferNext);
            writeLong(positionNew, key, buffer, bufferNext);
            writeLong(positionNew + 8, value, buffer, bufferNext);

            unlockBufferForUpdate(bufferIndex);
            unlockBufferForUpdate(bufferIndexLimit);
        }
        addTotal(1);
    }
"""

code = re.sub(r'private void updateValues\(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue\) throws InterruptedException, IOException \{[\s\S]+?(?=private void storeEndValues)', update_values_new, code)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
