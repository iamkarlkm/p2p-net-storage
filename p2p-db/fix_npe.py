import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

code = code.replace(
    'MappedByteBuffer headerBuffer = this.loadBufferForUpdate(0L);',
    'headerBuffer = this.loadBufferForUpdate(0L);'
)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
