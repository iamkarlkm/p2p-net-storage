import re

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
code = code.replace('public final long updateHeaderWithOldValue(int level,int count,int size, int hash,long value)',
                    'public final long updateHeaderWithOldValue(int level,int count,int size, int hash)')
code = code.replace('updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, value)',
                    'updateHeaderWithOldValue(level, 1, areaSize+headSize, hash)')
code = code.replace('updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, value)',
                    'updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash)')
code = code.replace('updateHeaderWithOldValue(level,0, areaSize+headSize,hash,value)',
                    'updateHeaderWithOldValue(level,0, areaSize+headSize,hash)')

# In updateHeaderByValue
code = code.replace('public final void updateHeaderByValue(int count,int hash,long value)',
                    'public final void updateHeaderByValue(int count,int hash,long key, long value)')
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
code = code.replace('long oldValue = heads[hash];', 'long oldKey = headKeys[hash];\n                    long oldValue = headValues[hash];')
code = code.replace('if (oldValue == value) {\n                        return false;\n                    }',
                    'if (oldKey == key) {\n                        headValues[hash] = value;\n                        headerBuffer.putLong(rootHeadSize + MAGIC_STRING_LENGTH + hash * ELEMENT_SIZE + 8, value);\n                        return oldValue;\n                    }')
code = code.replace('return updateValues(base, level, hash, areaSize, headSize, value, oldValue);',
                    'updateValues(base, level, hash, areaSize, headSize, key, value, oldKey, oldValue);\n                    return null;')
code = code.replace('long oldPtr = heads[hash];\n                    // 递归地将值放入下一层\n                    return put(oldPtr, 1, value);',
                    'long oldPtr = headKeys[hash];\n                    return put(oldPtr, 1, key, value);')
code = code.replace('updateHeaderByValue(1, hash, value);\n                    return true;',
                    'updateHeaderByValue(1, hash, key, value);\n                    return null;')

# Replace internal put
code = code.replace('boolean put(long base,int level,long value)', 'Long put(long base,int level,long key, long value)')
code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                    'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

# Replace get
code = code.replace('Long get(long base,int level,long value)', 'Long get(long base,int level,long key)')
code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                    'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

# Replace contains
code = code.replace('public boolean contains(long value)', 'public boolean containsKey(long key)')
code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                    'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')
code = code.replace('long oldValue = heads[hash];\n                   //如果旧值和新值相同，则返回false\n                   if (oldValue == value) {\n                       return true;\n                   }',
                    'long oldKey = headKeys[hash];\n                   if (oldKey == key) {\n                       return true;\n                   }')
code = code.replace('Long oldValue = get(ptr,1,value);', 'Long oldValue = get(ptr,1,key);')
code = code.replace('return oldValue == value;', 'return true;')

# Replace remove
code = code.replace('public boolean remove(long value)', 'public boolean remove(long key)')
code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                    'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')
code = code.replace('long oldValue = heads[hash];\n                   // 如果匹配，移除它\n                   if (oldValue == value) {',
                    'long oldKey = headKeys[hash];\n                   if (oldKey == key) {')
code = code.replace('return remove(ptr,1,value);', 'return remove(ptr,1,key);')

# Replace internal remove
code = code.replace('boolean remove(long base,int level,long value)', 'boolean remove(long base,int level,long key)')
code = code.replace('int hash = (int) ((value >>> levelsShift[level]) & levelsMask[level]);',
                    'int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

# Write output to test
with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
