import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Add print to updateValues
code = code.replace(
    'buffer.putLong(positionOld, oldKey);',
    'if (key == 65L) System.out.println("UPDATE base=" + base + " posOld=" + positionOld + " posNew=" + positionNew);\n            buffer.putLong(positionOld, oldKey);'
)

# Add print to get
code = code.replace(
    'if (key == 1L) System.out.println("GET key=" + key + " level=" + level + " hash=" + hash + " isHasValue=" + Integer.toBinaryString(isHasValue) + " bitPos=" + bitPosition + " hasValue=" + hasValue);',
    'if (key == 1L) System.out.println("GET key=" + key + " level=" + level + " hash=" + hash + " isHasValue=" + Integer.toBinaryString(isHasValue) + " bitPos=" + bitPosition + " hasValue=" + hasValue + " base=" + base + " pos=" + positionValue);'
)

code = code.replace(
    'case 3 -> {//有值-原始值',
    'case 3 -> {//有值-原始值\n                    if (key == 1L) System.out.println("GET case 3: oldKey=" + buffer.getLong(positionValue) + " oldValue=" + buffer.getLong(positionValue+8));'
)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
