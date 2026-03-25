import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

helpers = """
    private static int setBit(int data, int n) {
        return data | (1 << (31 - (n & 31)));
    }

    private static int clearBit(int data, int n) {
        return data & ~(1 << (31 - (n & 31)));
    }
"""

code = code.replace('public class DsHashMap extends DsObject{', 'public class DsHashMap extends DsObject{\n' + helpers)

# Replace all DsDataUtil.setBit(X, Y) with X = setBit(X, Y)
code = re.sub(r'DsDataUtil\.setBit\(([^,]+),\s*([^)]+)\);', r'\1 = setBit(\1, \2);', code)

# Replace all DsDataUtil.clearBit(X, Y) with X = clearBit(X, Y)
code = re.sub(r'DsDataUtil\.clearBit\(([^,]+),\s*([^)]+)\);', r'\1 = clearBit(\1, \2);', code)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
