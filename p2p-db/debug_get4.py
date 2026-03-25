import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

code = code.replace(
    'buffer.putLong(positionNew + 8, value);',
    'buffer.putLong(positionNew + 8, value);\n            System.out.println("VERIFY WRITE: oldKey=" + buffer.getLong(positionOld) + " at " + positionOld);'
)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
