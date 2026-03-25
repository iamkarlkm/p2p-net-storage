import re

def process():
    with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashSet.java', 'r', encoding='utf-8') as f:
        code = f.read()

    # Rename class
    code = code.replace('DsHashSet', 'DsHashMap')
    code = code.replace('MAGIC_STRING = ".SET"', 'MAGIC_STRING = ".MAP"')
    code = code.replace('ELEMENT_SIZE = 8', 'ELEMENT_SIZE = 16')
    code = code.replace('super(dataFile, ELEMENT_SIZE);//64位', 'super(dataFile, ELEMENT_SIZE);//128位')

    # Replace heads array
    code = code.replace('private final long[]  heads = new long[DEFAULT_SEGMENT_LOCKS_COUNT];//头指针',
                        'private final long[] headKeys = new long[DEFAULT_SEGMENT_LOCKS_COUNT];\n    private final long[] headValues = new long[DEFAULT_SEGMENT_LOCKS_COUNT];')

    code = re.sub(r'headerBuffer\.asLongBuffer\(\)\.get\(headsOffset>>3,heads\);',
                  r'for(int i=0;i<DEFAULT_SEGMENT_LOCKS_COUNT;i++){ headKeys[i]=headerBuffer.getLong(headsOffset+i*ELEMENT_SIZE); headValues[i]=headerBuffer.getLong(headsOffset+i*ELEMENT_SIZE+8); }', code)
    code = code.replace('heads.length', 'DEFAULT_SEGMENT_LOCKS_COUNT')

    # Replace header update methods
    code = code.replace('heads[hash] = base;', 'headKeys[hash] = base;\n            headValues[hash] = 0;')
    code = code.replace('headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, base);',
                        'headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, base).putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);')

    # updateHeaderWithOldValue
    code = re.sub(r'public final long updateHeaderWithOldValue\(int level,int count,int size, int hash,long value\)',
                  r'public final long updateHeaderWithOldValue(int level,int count,int size, int hash, long key, long value)', code)
    code = code.replace('updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, value)',
                        'updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, key, value)')
    code = code.replace('updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, value)',
                        'updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, key, value)')
    code = code.replace('updateHeaderWithOldValue(level,0, areaSize+headSize,hash,value)',
                        'updateHeaderWithOldValue(level,0, areaSize+headSize,hash, key, value)')

    # updateHeaderByValue
    code = re.sub(r'public final void updateHeaderByValue\(int count,int hash,long value\)',
                  r'public final void updateHeaderByValue(int count,int hash,long key, long value)', code)
    code = code.replace('heads[hash] = value;', 'headKeys[hash] = key;\n            headValues[hash] = value;')
    code = code.replace('.putLong((int) offset,value)', '.putLong((int) offset, key).putLong((int) offset + 8, value)')
    code = code.replace('updateHeaderByValue(1, hash, value)', 'updateHeaderByValue(1, hash, key, value)')

    # minusTotalByHash
    code = code.replace('heads[hash] = 0;', 'headKeys[hash] = 0;\n            headValues[hash] = 0;')
    code = code.replace('headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0);',
                        'headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0).putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);')

    # clear
    code = code.replace('heads[i] = 0L;', 'headKeys[i] = 0L;\n                        headValues[i] = 0L;')
    code = code.replace('headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE,0L);',
                        'headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE,0L).putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE + 8,0L);')
    code = code.replace('heads[i]', 'headKeys[i]')

    # add -> put
    code = code.replace('public boolean add(long value) throws IOException, InterruptedException {',
                        'public Long put(long key, long value) throws IOException, InterruptedException {')
    code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                        'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

    code = code.replace('long oldValue = heads[hash];\n                    // 如果旧值等于新值，返回 false (元素已存在)\n                    if (oldValue == value) {\n                        return false;\n                    }',
                        'long oldKey = headKeys[hash];\n                    long oldValue = headValues[hash];\n                    if (oldKey == key) {\n                        headValues[hash] = value;\n                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH + hash * ELEMENT_SIZE + 8, value);\n                        return oldValue;\n                    }')
    
    code = code.replace('return updateValues(base, level, hash, areaSize, headSize, value, oldValue);',
                        'updateValues(base, level, hash, areaSize, headSize, key, value, oldKey, oldValue);\n                    return null;')

    code = code.replace('long oldPtr = heads[hash];\n                    // 递归地将值放入下一层\n                    return put(oldPtr, 1, value);',
                        'long oldPtr = headKeys[hash];\n                    return put(oldPtr, 1, key, value);')

    code = code.replace('updateHeaderByValue(1, hash, value);\n                    return true;',
                        'updateHeaderByValue(1, hash, key, value);\n                    return null;')

    # updateValues
    code = re.sub(r'private boolean updateValues\(long base,int level,int hash,int areaSize,int headSize,long value,long oldValue\)',
                  r'private void updateValues(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue)', code)
    code = code.replace('int hashOld = (int) ((oldValue >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                        'int hashOld = (int) ((oldKey >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')
    code = code.replace('int hashNew = (int) ((value >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                        'int hashNew = (int) ((key >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')
    code = code.replace('put(baseNext, nextLevel,  oldValue);\n            return put(baseNext, nextLevel,  value);',
                        'put(baseNext, nextLevel, oldKey, oldValue);\n            put(baseNext, nextLevel, key, value);\n            return;')
    
    code = re.sub(r'buffer\.putLong\(positionOld,oldValue\);\s+buffer\.putLong\(positionNew,value\);',
                  r'buffer.putLong(positionOld, oldKey); buffer.putLong(positionOld + 8, oldValue); buffer.putLong(positionNew, key); buffer.putLong(positionNew + 8, value);', code)
    code = re.sub(r'buffer\.putLong\(positionOld,oldValue\);\s+unlockBufferForUpdate\(bufferIndexOld\);\s+buffer = loadBufferForUpdate\(bufferIndexNew\);\s+positionNew = positionNew%BLOCK_SIZE;\s+buffer\.putLong\(positionNew,value\);',
                  r'buffer.putLong(positionOld, oldKey); buffer.putLong(positionOld + 8, oldValue); unlockBufferForUpdate(bufferIndexOld); buffer = loadBufferForUpdate(bufferIndexNew); positionNew = positionNew%BLOCK_SIZE; buffer.putLong(positionNew, key); buffer.putLong(positionNew + 8, value);', code)
    
    code = code.replace('return storeEndValues(false,base,level,hash,areaSize,headSize/2,value,oldValue);',
                        'storeEndValues(false,base,level,hash,areaSize,headSize/2,key,value,oldKey,oldValue);\n            return;')
    code = code.replace('addTotal(1);\n        return true;', 'addTotal(1);')

    # storeEndValues
    code = re.sub(r'private boolean storeEndValues\(boolean isOldAdded,long base,int level,int hash,int areaSize,int headSize,long value,long oldValue\)',
                  r'private void storeEndValues(boolean isOldAdded,long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue)', code)
    
    code = code.replace('buffer.putLong(positionValue,value);', 'buffer.putLong(positionValue,key); buffer.putLong(positionValue+8,value);')
    code = code.replace('buffer.putLong(positionValue,oldValue);', 'buffer.putLong(positionValue,oldKey); buffer.putLong(positionValue+8,oldValue);')
    
    code = code.replace('long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == value)',
                        'long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == key)')
    
    code = code.replace('int j = writeLong(positionValue,value,buffer,bufferNext);',
                        'int j = writeLong(positionValue,key,buffer,bufferNext);\n                        writeLong(positionValue+8,value,buffer,bufferNext);')
    code = code.replace('int j = writeLong(positionValue,oldValue,buffer,bufferNext);',
                        'int j = writeLong(positionValue,oldKey,buffer,bufferNext);\n                        writeLong(positionValue+8,oldValue,buffer,bufferNext);')
    
    code = code.replace('long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == value)',
                        'long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == key)')
    
    code = code.replace('return storeEndValues(isOldAdded,basePtrNext,level,hash,areaSize,headSize,value,oldValue);',
                        'storeEndValues(isOldAdded,basePtrNext,level,hash,areaSize,headSize,key,value,oldKey,oldValue);\n            return;')
    code = code.replace('addTotal(1);\n                        return true;', 'addTotal(1);\n                        return;')
    code = code.replace('addTotal(1);\n                        return true;', 'addTotal(1);\n                        return;')
    code = code.replace('return false;', 'return;') # storeEndValues now returns void
    code = code.replace('return false;', 'return;')

    # internal put
    code = re.sub(r'boolean put\(long base,int level,long value\)', r'Long put(long base,int level,long key, long value)', code)
    code = code.replace('long oldValue = buffer.getLong(positionValue);\n                    if(oldValue == value)',
                        'long oldKey = buffer.getLong(positionValue);\n                    long oldValue = buffer.getLong(positionValue+8);\n                    if(oldKey == key)')
    code = code.replace('return false;\n                    }',
                        'buffer.putLong(positionValue+8, value);\n                        return oldValue;\n                    }')
    
    code = code.replace('return updateValues(baseNew,level,hash,areaSize,headSize,value,oldValue);',
                        'updateValues(baseNew,level,hash,areaSize,headSize,key,value,oldKey,oldValue);\n                    return null;')
    
    code = code.replace('return storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,value,0);',
                        'storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,key,value,0L,0L);\n                        return null;')
    code = code.replace('return storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,value,0L);',
                        'storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,key,value,0L,0L);\n                        return null;')
    
    code = code.replace('return put(basePtr,nextLevel,value);', 'return put(basePtr,nextLevel,key,value);')
    code = code.replace('return put(basePtr,level+1,value);', 'return put(basePtr,level+1,key,value);')
    
    code = code.replace('addTotal(1);\n                    return true;', 'addTotal(1);\n                    return null;')
    
    code = code.replace('long oldValue = readLong(positionValue,buffer,bufferNext);\n                    if(oldValue == value)',
                        'long oldKey = readLong(positionValue,buffer,bufferNext);\n                    long oldValue = readLong(positionValue+8,buffer,bufferNext);\n                    if(oldKey == key)')
    
    # get
    code = re.sub(r'Long get\(long base,int level,long value\)', r'Long get(long base,int level,long key)', code)
    code = code.replace('long oldValue = buffer.getLong(positionValue);\n                    if(oldValue == value){\n                        return oldValue;\n                    }',
                        'long oldKey = buffer.getLong(positionValue);\n                    if(oldKey == key){\n                        return buffer.getLong(positionValue+8);\n                    }')
    code = code.replace('return getEndValue(basePtr,level,hash,areaSize,headSize/2,value);',
                        'return getEndValue(basePtr,level,hash,areaSize,headSize/2,key);')
    code = code.replace('return get(basePtr,nextLevel,value);', 'return get(basePtr,nextLevel,key);')
    
    code = code.replace('long oldValue = readLong(positionValue,buffer,bufferNext);\n                    if(oldValue == value){\n                        return oldValue;\n                    }',
                        'long oldKey = readLong(positionValue,buffer,bufferNext);\n                    if(oldKey == key){\n                        return readLong(positionValue+8,buffer,bufferNext);\n                    }')

    # getEndValue
    code = re.sub(r'private Long getEndValue\(long base,int level,int hash,int areaSize,int headSize,long value\)',
                  r'private Long getEndValue(long base,int level,int hash,int areaSize,int headSize,long key)', code)
    code = code.replace('long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == value)',
                        'long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == key)')
    code = code.replace('return null;\n                    }', 'return buffer.getLong(positionValue+8);\n                    }')
    code = code.replace('return getEndValue(basePtrNext,level,hash,areaSize,headSize,value);',
                        'return getEndValue(basePtrNext,level,hash,areaSize,headSize,key);')
    
    code = code.replace('long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == value)',
                        'long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == key)')
    code = code.replace('return null;\n                    }', 'return readLong(positionValue+8,buffer,bufferNext);\n                    }')

    # containsKey
    code = code.replace('public boolean contains(long value) {', 'public boolean containsKey(long key) {')
    code = code.replace('long oldValue = heads[hash];\n                   //如果旧值和新值相同，则返回false\n                   if (oldValue == value) {',
                        'long oldKey = headKeys[hash];\n                   if (oldKey == key) {')
    code = code.replace('Long oldValue = get(ptr,1,value);', 'Long oldValue = get(ptr,1,key);')
    code = code.replace('return oldValue == value;', 'return true;')

    # remove
    code = code.replace('public boolean remove(long value){', 'public boolean remove(long key){')
    code = code.replace('long oldValue = heads[hash];\n                   // 如果匹配，移除它\n                   if (oldValue == value) {',
                        'long oldKey = headKeys[hash];\n                   if (oldKey == key) {')
    code = code.replace('return remove(ptr,1,value);', 'return remove(ptr,1,key);')

    # remove internal
    code = re.sub(r'boolean remove\(long base,int level,long value\)', r'boolean remove(long base,int level,long key)', code)
    code = code.replace('long oldValue = buffer.getLong(positionValue);\n                    if(oldValue == value)',
                        'long oldKey = buffer.getLong(positionValue);\n                    if(oldKey == key)')
    code = code.replace('return removeEnd(basePtr,level,hash,areaSize,headSize/2,value);',
                        'return removeEnd(basePtr,level,hash,areaSize,headSize/2,key);')
    code = code.replace('return remove(basePtr,nextLevel,value);', 'return remove(basePtr,nextLevel,key);')
    
    code = code.replace('long oldValue = readLong(positionValue,buffer,bufferNext);\n                    if(oldValue == value)',
                        'long oldKey = readLong(positionValue,buffer,bufferNext);\n                    if(oldKey == key)')

    # removeEnd
    code = re.sub(r'private boolean removeEnd\(long base,int level,int hash,int areaSize,int headSize,long value\)',
                  r'private boolean removeEnd(long base,int level,int hash,int areaSize,int headSize,long key)', code)
    code = code.replace('long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == value)',
                        'long valueOld = buffer.getLong(positionValue);\n                    if(valueOld == key)')
    code = code.replace('return removeEnd(basePtrNext,level,hash,areaSize,headSize,value);',
                        'return removeEnd(basePtrNext,level,hash,areaSize,headSize,key);')
    code = code.replace('long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == value)',
                        'long valueOld =  readLong(positionValue,buffer,bufferNext);\n                    if(valueOld == key)')

    # Remove iterator and stuff below it to avoid compile errors on unused methods
    it_start = code.find('public final Iterator<Long> iterator() {')
    if it_start != -1:
        code = code[:it_start] + '\n}\n'

    with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
        f.write(code)

process()
