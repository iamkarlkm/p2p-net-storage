import re
import sys

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashSet.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Replace class name
code = code.replace('DsHashSet', 'DsHashMap')
code = code.replace('MAGIC_STRING = ".SET"', 'MAGIC_STRING = ".MAP"')
code = code.replace('ELEMENT_SIZE = 8', 'ELEMENT_SIZE = 16')
code = code.replace('super(dataFile, ELEMENT_SIZE);//64位', 'super(dataFile, ELEMENT_SIZE);//128位')

# Replace root heads
code = code.replace('private final long[]  heads = new long[DEFAULT_SEGMENT_LOCKS_COUNT];//头指针',
                    'private final long[] headKeys = new long[DEFAULT_SEGMENT_LOCKS_COUNT];\n    private final long[] headValues = new long[DEFAULT_SEGMENT_LOCKS_COUNT];')

# Replace heads usage in checkHeader
code = re.sub(r'headerBuffer\.asLongBuffer\(\)\.get\(headsOffset>>3,heads\);',
              r'for (int i = 0; i < DEFAULT_SEGMENT_LOCKS_COUNT; i++) {\n                   headKeys[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE);\n                   headValues[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE + 8);\n               }', code)
code = code.replace('heads.length', 'DEFAULT_SEGMENT_LOCKS_COUNT')

# In updateHeaderForNewOffset
code = code.replace('heads[hash] = base;', 'headKeys[hash] = base;\n            headValues[hash] = 0;')

# In updateHeaderWithOldValue
code = re.sub(r'public final long updateHeaderWithOldValue\(int level,int count,int size, int hash,long value\)',
              r'public final long updateHeaderWithOldValue(int level,int count,int size, int hash)', code)
code = code.replace('updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, value)',
                    'updateHeaderWithOldValue(level, 1, areaSize+headSize, hash)')
code = code.replace('updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, value)',
                    'updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash)')
code = code.replace('updateHeaderWithOldValue(level,0, areaSize+headSize,hash,value)',
                    'updateHeaderWithOldValue(level,0, areaSize+headSize,hash)')

# In updateHeaderByValue
code = re.sub(r'public final void updateHeaderByValue\(int count,int hash,long value\)',
              r'public final void updateHeaderByValue(int count,int hash,long key, long value)', code)
code = code.replace('heads[hash] = value;', 'headKeys[hash] = key;\n            headValues[hash] = value;')
code = code.replace('.putLong((int) offset,value)', '.putLong((int) offset, key).putLong((int) offset + 8, value)')

# In minusTotalByHash
code = code.replace('heads[hash] = 0;', 'headKeys[hash] = 0;\n            headValues[hash] = 0;')
code = code.replace('headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0);',
                    'headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE, 0);\n            headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);')

# Replace add with put
code = code.replace('public boolean add(long value)', 'public Long put(long key, long value)')
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
              r'private boolean updateValues(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue)', code)
code = code.replace('int hashOld = (int) ((oldValue >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                    'int hashOld = (int) ((oldKey >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')
code = code.replace('int hashNew = (int) ((value >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                    'int hashNew = (int) ((key >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')
code = code.replace('put(baseNext, nextLevel,  oldValue);\n            return put(baseNext, nextLevel,  value);',
                    'put(baseNext, nextLevel, oldKey, oldValue);\n            put(baseNext, nextLevel, key, value);\n            return true;')
code = code.replace('return storeEndValues(false,base,level,hash,areaSize,headSize/2,value,oldValue);',
                    'return storeEndValues(false,base,level,hash,areaSize,headSize/2,key,value,oldKey,oldValue);')

# putLong in updateValues
code = re.sub(r'buffer\.putLong\(positionOld,oldValue\);\s+buffer\.putLong\(positionNew,value\);',
              r'buffer.putLong(positionOld, oldKey);\n            buffer.putLong(positionOld + 8, oldValue);\n            buffer.putLong(positionNew, key);\n            buffer.putLong(positionNew + 8, value);', code)
code = re.sub(r'buffer\.putLong\(positionOld,oldValue\);\s+unlockBufferForUpdate\(bufferIndexOld\);\s+buffer = loadBufferForUpdate\(bufferIndexNew\);\s+positionNew = positionNew%BLOCK_SIZE;\s+buffer\.putLong\(positionNew,value\);',
              r'buffer.putLong(positionOld, oldKey);\n                buffer.putLong(positionOld + 8, oldValue);\n                unlockBufferForUpdate(bufferIndexOld);\n                buffer = loadBufferForUpdate(bufferIndexNew);\n                positionNew = positionNew%BLOCK_SIZE;\n                buffer.putLong(positionNew, key);\n                buffer.putLong(positionNew + 8, value);', code)


# storeEndValues
code = re.sub(r'private boolean storeEndValues\(boolean isOldAdded,long base,int level,int hash,int areaSize,int headSize,long value,long oldValue\)',
              r'private boolean storeEndValues(boolean isOldAdded,long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue)', code)
# we also need to fix occurrences inside storeEndValues, this might be tricky with regex, let's just leave it for now and fix errors

# Replace internal put
code = re.sub(r'boolean put\(long base,int level,long value\)', r'Long put(long base,int level,long key, long value)', code)
code = code.replace('return put(basePtr,nextLevel,value);', 'return put(basePtr,nextLevel,key, value);')
code = code.replace('return put(basePtr,level+1,value);', 'return put(basePtr,level+1,key, value);')

# Replace get
code = re.sub(r'Long get\(long base,int level,long value\)', r'Long get(long base,int level,long key)', code)
code = code.replace('return get(basePtr,nextLevel,value);', 'return get(basePtr,nextLevel,key);')

# Replace contains
code = code.replace('public boolean contains(long value)', 'public boolean containsKey(long key)')
code = code.replace('long oldValue = heads[hash];\n                   //如果旧值和新值相同，则返回false\n                   if (oldValue == value) {\n                       return true;\n                   }',
                    'long oldKey = headKeys[hash];\n                   if (oldKey == key) {\n                       return true;\n                   }')
code = code.replace('Long oldValue = get(ptr,1,value);', 'Long oldValue = get(ptr,1,key);')
code = code.replace('return oldValue == value;', 'return true;')

# Replace remove
code = code.replace('public boolean remove(long value)', 'public boolean remove(long key)')
code = code.replace('long oldValue = heads[hash];\n                   // 如果匹配，移除它\n                   if (oldValue == value) {',
                    'long oldKey = headKeys[hash];\n                   if (oldKey == key) {')
code = code.replace('return remove(ptr,1,value);', 'return remove(ptr,1,key);')

# Replace internal remove
code = re.sub(r'boolean remove\(long base,int level,long value\)', r'boolean remove(long base,int level,long key)', code)
code = code.replace('return remove(basePtr,nextLevel,value);', 'return remove(basePtr,nextLevel,key);')

# Replace getEndValue
code = re.sub(r'private Long getEndValue\(long base,int level,int hash,int areaSize,int headSize,long value\)',
              r'private Long getEndValue(long base,int level,int hash,int areaSize,int headSize,long key)', code)
code = code.replace('return getEndValue(basePtrNext,level,hash,areaSize,headSize,value);',
                    'return getEndValue(basePtrNext,level,hash,areaSize,headSize,key);')

# Replace removeEnd
code = re.sub(r'private boolean removeEnd\(long base,int level,int hash,int areaSize,int headSize,long value\)',
              r'private boolean removeEnd(long base,int level,int hash,int areaSize,int headSize,long key)', code)
code = code.replace('return removeEnd(basePtrNext,level,hash,areaSize,headSize,value);',
                    'return removeEnd(basePtrNext,level,hash,areaSize,headSize,key);')


# Finally, heads -> headKeys/headValues in the rest
code = code.replace('heads[i]', 'headKeys[i]')
code = code.replace('heads[hash]', 'headKeys[hash]')

# Write output to test
with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
