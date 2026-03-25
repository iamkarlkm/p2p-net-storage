import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashSet.java', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace class name and constructor
content = content.replace('DsHashSet', 'DsHashMap')
content = content.replace('MAGIC_STRING = ".SET"', 'MAGIC_STRING = ".MAP"')
content = content.replace('ELEMENT_SIZE = 8', 'ELEMENT_SIZE = 16')
content = content.replace('super(dataFile, ELEMENT_SIZE);//64位', 'super(dataFile, ELEMENT_SIZE);//128位')

# Replace head arrays
content = content.replace('private final long[]  heads = new long[DEFAULT_SEGMENT_LOCKS_COUNT];//头指针', 
                          'private final long[] headKeys = new long[DEFAULT_SEGMENT_LOCKS_COUNT];\n    private final long[] headValues = new long[DEFAULT_SEGMENT_LOCKS_COUNT];')

# checkHeader
content = re.sub(r'headerBuffer\.asLongBuffer\(\)\.get\(headsOffset>>3,heads\);',
                 r'for (int i = 0; i < DEFAULT_SEGMENT_LOCKS_COUNT; i++) {\n                   headKeys[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE);\n                   headValues[i] = headerBuffer.getLong(headsOffset + i * ELEMENT_SIZE + 8);\n               }', content)

# headerOffset usage for heads array sizing in rootOffset
content = content.replace('heads.length * ELEMENT_SIZE', 'DEFAULT_SEGMENT_LOCKS_COUNT * ELEMENT_SIZE')
content = content.replace('heads.length', 'DEFAULT_SEGMENT_LOCKS_COUNT')
content = content.replace('heads[hash]', 'headKeys[hash]')
content = content.replace('heads[i]', 'headKeys[i]')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(content)
