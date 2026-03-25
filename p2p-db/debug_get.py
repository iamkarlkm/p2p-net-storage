import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# In get, add a print statement
code = code.replace(
    'int isHasValue = buffer.getInt(offsetIsHasValue);\n            //获取当前level,hash的hasValue值\n            int hasValue = getHasValue(isHasValue, bitPosition, 2);',
    'int isHasValue = buffer.getInt(offsetIsHasValue);\n            int hasValue = getHasValue(isHasValue, bitPosition, 2);\n            if (key == 1L || key == 65L) System.out.println("GET key=" + key + " level=" + level + " hash=" + hash + " isHasValue=" + Integer.toBinaryString(isHasValue) + " bitPos=" + bitPosition + " hasValue=" + hasValue);'
)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)

