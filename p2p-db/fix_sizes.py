import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# In put (public):
code = code.replace(
    'long base = updateHeaderWithOldValue(level, 1, areaSize+headSize, hash, key, value);\n                    updateValues(base, level, hash, areaSize, headSize, key, value, oldKey, oldValue);',
    'int nextLvl = level + 1;\n                    long base = updateHeaderWithOldValue(level, 1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl], hash, key, value);\n                    updateValues(base, level, hash, levelsAreaSize[nextLvl], levelsHeadSize[nextLvl], key, value, oldKey, oldValue);'
)

# In updateValues:
code = code.replace(
    'long baseNext2 = updateHeaderWithOldValue(nextLevel, 0, areaSize+headSize, hash, key, value);',
    'int nextLvl2 = nextLevel + 1;\n            long baseNext2 = updateHeaderWithOldValue(nextLevel, 0, levelsAreaSize[nextLvl2]+levelsHeadSize[nextLvl2], hash, key, value);'
)

# In put (internal):
code = code.replace(
    'long baseNew = updateHeaderWithOldValue(level,1, areaSize+headSize,hash,key,value);\n                    updateValues(baseNew,level,hash,areaSize,headSize,key,value,oldKey,oldValue);',
    'int nextLvl = level + 1;\n                    long baseNew = updateHeaderWithOldValue(level,1, levelsAreaSize[nextLvl]+levelsHeadSize[nextLvl],hash,key,value);\n                    updateValues(baseNew,level,hash,levelsAreaSize[nextLvl],levelsHeadSize[nextLvl],key,value,oldKey,oldValue);'
)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
