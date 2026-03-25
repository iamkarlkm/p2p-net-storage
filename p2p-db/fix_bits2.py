import re

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

helpers = """
    private static int getHasValue(int data, int start, int count) {
        int shift = 32 - start - count;
        return (data >>> shift) & ((1 << count) - 1);
    }
"""

code = code.replace('private static int setBit(int data, int n) {', helpers + '\n    private static int setBit(int data, int n) {')

# Replace DsDataUtil.bitsToInt(var, pos, count) with getHasValue(var, pos, count)
code = re.sub(r'DsDataUtil\.bitsToInt\(([^,]+),\s*([^,]+),\s*([^)]+)\)', r'getHasValue(\1, \2, \3)', code)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)
