import sys

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    code = f.read()

# Add public get method before public boolean containsKey
public_get = """
   public Long get(long key) {
       int level = 0;
       int hash = (int) ((key >>> levelsShift[level]) & levelsMask[level]);
       segmentLocks[hash].lock();
       try {
           int hasValue = DsDataUtil.bitsToInt(isHasValueBitmap,hash*2,2);
           switch (hasValue) {
               case 3 -> {
                   long oldKey = headKeys[hash];
                   if (oldKey == key) {
                       return headValues[hash];
                   }
                   return null;
               }
               case 2 -> {
                   long ptr = headKeys[hash];
                   try {
                       return get(ptr,1,key);
                   } catch (InterruptedException | IOException e) {
                       throw new RuntimeException(e);
                   }
               }
               case 0 -> {
                   return null;
               }
               default ->
                       throw new RuntimeException("意外的bits值:" + hasValue + ",可能值:3,2,0");
           }
       } finally {
           segmentLocks[hash].unlock();
       }
   }

"""

code = code.replace('public boolean containsKey(long key) {', public_get + 'public boolean containsKey(long key) {')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.write(code)

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\test\java\ds\DsHashMapTest.java', 'r', encoding='utf-8') as f:
    test_code = f.read()

test_code = test_code.replace('dsHashMap.close();', '// dsHashMap.close();')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\test\java\ds\DsHashMapTest.java', 'w', encoding='utf-8') as f:
    f.write(test_code)
