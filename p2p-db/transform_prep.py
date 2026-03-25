import re
import sys

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashSet.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Class definition and constants
content = content.replace('DsHashSet', 'DsHashMap')
content = content.replace('MAGIC_STRING = ".SET"', 'MAGIC_STRING = ".MAP"')
content = content.replace('ELEMENT_SIZE = 8', 'ELEMENT_SIZE = 16')
content = content.replace('super(dataFile, ELEMENT_SIZE);//64位', 'super(dataFile, ELEMENT_SIZE);//128位')

# Head arrays
content = content.replace('private final long[]  heads = new long[DEFAULT_SEGMENT_LOCKS_COUNT];//头指针', 
                          'private final long[] headKeys = new long[DEFAULT_SEGMENT_LOCKS_COUNT];\n    private final long[] headValues = new long[DEFAULT_SEGMENT_LOCKS_COUNT];')

# checkHeader adjustments
content = re.sub(r'headerBuffer\.asLongBuffer\(\)\.get\(headsOffset>>3,heads\);',
                 r'for (int i = 0; i < DEFAULT_SEGMENT_LOCKS_COUNT; i++) {\n                   headKeys[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE);\n                   headValues[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE + 8);\n               }', content)

content = content.replace('heads.length * ELEMENT_SIZE', 'DEFAULT_SEGMENT_LOCKS_COUNT * ELEMENT_SIZE')
content = content.replace('heads.length', 'DEFAULT_SEGMENT_LOCKS_COUNT')

# Update header methods
content = re.sub(r'updateHeaderWithOldValue\(int level,int count,int size, int hash,long value\)',
                 r'updateHeaderWithOldValue(int level,int count,int size, int hash,long key, long value)', content)
content = re.sub(r'heads\[hash\] = base;', r'headKeys[hash] = base; headValues[hash] = 0;', content)
content = re.sub(r'updateHeaderByValue\(int count,int hash,long value\)',
                 r'updateHeaderByValue(int count,int hash,long key, long value)', content)
content = re.sub(r'heads\[hash\] = value;', r'headKeys[hash] = key;\n            headValues[hash] = value;', content)
content = re.sub(r'\.putLong\(\(int\) offset,value\);', r'.putLong((int) offset, key).putLong((int) offset + 8, value);', content)

content = re.sub(r'heads\[hash\] = 0;', r'headKeys[hash] = 0;\n            headValues[hash] = 0;', content)
content = re.sub(r'\+hash \* ELEMENT_SIZE, 0\);', r'+hash * ELEMENT_SIZE, 0);\n            headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+hash * ELEMENT_SIZE + 8, 0);', content)

content = re.sub(r'heads\[i\] = 0L;', r'headKeys[i] = 0L;\n                        headValues[i] = 0L;', content)
content = re.sub(r'\+i \* ELEMENT_SIZE,0L\);', r'+i * ELEMENT_SIZE,0L);\n                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH+i * ELEMENT_SIZE + 8,0L);', content)

# public boolean add(long value) -> public Long put(long key, long value)
content = content.replace('public boolean add(long value) throws IOException, InterruptedException {',
                          'public Long put(long key, long value) throws IOException, InterruptedException {')
content = content.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                          'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

# replace OLD VALUE matching logic in add (now put)
content = content.replace('long oldValue = heads[hash];\n                    // 如果旧值等于新值，返回 false (元素已存在)\n                    if (oldValue == value) {\n                        return false;\n                    }',
                          'long oldKey = headKeys[hash];\n                    long oldValue = headValues[hash];\n                    if (oldKey == key) {\n                        headValues[hash] = value;\n                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH + hash * ELEMENT_SIZE + 8, value);\n                        return oldValue;\n                    }')

content = content.replace('long base = updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, value);',
                          'long base = updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, key, value);')
content = content.replace('return updateValues(base, level, hash, areaSize, headSize, value, oldValue);',
                          'updateValues(base, level, hash, areaSize, headSize, key, value, oldKey, oldValue);\n                    return null;')

content = content.replace('long oldPtr = heads[hash];\n                    // 递归地将值放入下一层\n                    return put(oldPtr, 1, value);',
                          'long oldPtr = headKeys[hash];\n                    return put(oldPtr, 1, key, value);')

content = content.replace('updateHeaderByValue(1, hash, value);\n                    return true;',
                          'updateHeaderByValue(1, hash, key, value);\n                    return null;')

# private boolean updateValues(...) -> private void updateValues(...)
content = content.replace('private boolean updateValues(long base,int level,int hash,int areaSize,int headSize,long value,long oldValue) throws InterruptedException, IOException {',
                          'private void updateValues(long base,int level,int hash,int areaSize,int headSize,long key, long value, long oldKey, long oldValue) throws InterruptedException, IOException {')

content = content.replace('int hashOld = (int) ((oldValue >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                          'int hashOld = (int) ((oldKey >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')
content = content.replace('int hashNew = (int) ((value >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);',
                          'int hashNew = (int) ((key >>> levelsShift[nextLevel]) & levelsMask[nextLevel]);')

content = content.replace('long baseNext = updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, value);',
                          'long baseNext = updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, key, value);')
content = content.replace('put(baseNext, nextLevel,  oldValue);\n            return put(baseNext, nextLevel,  value);',
                          'put(baseNext, nextLevel, oldKey, oldValue);\n            put(baseNext, nextLevel, key, value);\n            return;')

# putLong
content = re.sub(r'buffer\.putLong\(positionOld,oldValue\);\s+buffer\.putLong\(positionNew,value\);',
                 r'buffer.putLong(positionOld, oldKey);\n            buffer.putLong(positionOld + 8, oldValue);\n            buffer.putLong(positionNew, key);\n            buffer.putLong(positionNew + 8, value);', content)

content = re.sub(r'buffer\.putLong\(positionOld,oldValue\);(\s+)unlockBufferForUpdate\(bufferIndexOld\);(\s+)buffer = loadBufferForUpdate\(bufferIndexNew\);(\s+)positionNew = positionNew%BLOCK_SIZE;(\s+)buffer\.putLong\(positionNew,value\);',
                 r'buffer.putLong(positionOld, oldKey);\n                buffer.putLong(positionOld + 8, oldValue);\1unlockBufferForUpdate(bufferIndexOld);\2buffer = loadBufferForUpdate(bufferIndexNew);\3positionNew = positionNew%BLOCK_SIZE;\4buffer.putLong(positionNew, key);\n                buffer.putLong(positionNew + 8, value);', content)

content = content.replace('return storeEndValues(false,base,level,hash,areaSize,headSize/2,value,oldValue);',
                          'storeEndValues(false,base,level,hash,areaSize,headSize/2,key,value,oldKey,oldValue);\n            return;')

content = content.replace('addTotal(1);\n        return true;', 'addTotal(1);')

# internal put
content = content.replace('boolean put(long base,int level,long value) throws InterruptedException, IOException {',
                          'Long put(long base,int level,long key, long value) throws InterruptedException, IOException {')

# This regex replacement requires care. Let's output the script and test.
with open(r'C:\2025\code\ImageFileServer\p2p-db\transform2.py', 'w', encoding='utf-8') as f:
    f.write(content)
