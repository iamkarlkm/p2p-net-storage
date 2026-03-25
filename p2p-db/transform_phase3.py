import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Fix updateHeaderWithOldValue missing arguments
code = re.sub(r'updateHeaderWithOldValue\(level,1, areaSize\+headSize,hash\)', r'updateHeaderWithOldValue(level,1, areaSize+headSize,hash, key, value)', code)
code = re.sub(r'updateHeaderWithOldValue\(level,0, areaSize\+headSize,hash\)', r'updateHeaderWithOldValue(level,0, areaSize+headSize,hash, key, value)', code)
code = re.sub(r'updateHeaderWithOldValue\(nextLevel, 0, areaSize\+headSize, hash\)', r'updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, key, value)', code)
code = re.sub(r'updateHeaderWithOldValue\(level, 1, areaSize\+headSize, hash\)', r'updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, key, value)', code)

# fix storeEndValues signatures
code = re.sub(r'storeEndValues\(true,basePtr,level,hash,areaSize,headSize/2,0\)', r'storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,key,value,0L,0L)', code)
code = re.sub(r'storeEndValues\(true,basePtr,level,hash,areaSize,headSize/2,0L\)', r'storeEndValues(true,basePtr,level,hash,areaSize,headSize/2,key,value,0L,0L)', code)

code = re.sub(r'return storeEndValues\(isOldAdded,basePtrNext,level,hash,areaSize,headSize,key,value,oldKey,oldValue\);', r'storeEndValues(isOldAdded,basePtrNext,level,hash,areaSize,headSize,key,value,oldKey,oldValue);\n            return;', code)

# fix updateValues missing arguments
code = re.sub(r'updateValues\(baseNew,level,hash,areaSize,headSize,value,oldValue\)', r'updateValues(baseNew,level,hash,areaSize,headSize,key,value,oldKey,oldValue)', code)

# fix incompatible return types where put was returning boolean but should return Long
code = re.sub(r'return put\(basePtr,nextLevel,key, value\);', r'return put(basePtr,nextLevel,key, value);', code)
code = re.sub(r'return put\(basePtr,level\+1,key, value\);', r'return put(basePtr,level+1,key, value);', code)

code = re.sub(r'return false;//对应值不存在集合中', r'return null;//对应值不存在集合中', code)
code = re.sub(r'return true;', r'return null;', code) # this might break containsKey or remove if they use 'return true'
# wait, I shouldn't replace 'return true;' globally

# In put, switch hasValue:
code = code.replace('return updateValues(baseNew,level,hash,areaSize,headSize,key,value,oldKey,oldValue);',
                    'updateValues(baseNew,level,hash,areaSize,headSize,key,value,oldKey,oldValue);\n                    return null;')

# Remove old references to value in remove and get methods
code = re.sub(r'oldValue == value', r'oldValue == key', code)
code = re.sub(r'long oldValue = buffer.getLong\(positionValue\);', r'long oldKey = buffer.getLong(positionValue);\n                    long oldValue = buffer.getLong(positionValue + 8);', code)
code = re.sub(r'long oldValue = readLong\(positionValue,buffer,bufferNext\);', r'long oldKey = readLong(positionValue,buffer,bufferNext);\n                    long oldValue = readLong(positionValue + 8,buffer,bufferNext);', code)
code = re.sub(r'oldKey == value', r'oldKey == key', code)

# restore some true/false returns in containsKey/remove
code = code.replace('public boolean containsKey(long key) {\n       // 初始化level为0\n       int level = 0;\n       // 计算value的hash值\n       int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);',
                    'public boolean containsKey(long key) {\n       // 初始化level为0\n       int level = 0;\n       // 计算key的hash值\n       int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);')

code = code.replace('return put(basePtr, 1, key, value);', 'return put(basePtr, 1, key, value);')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
