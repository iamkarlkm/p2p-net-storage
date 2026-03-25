import sys

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'r', encoding='utf-8') as f:
    lines = f.readlines()

def replace_line(line_num, old_str, new_str):
    idx = line_num - 1
    if old_str in lines[idx]:
        lines[idx] = lines[idx].replace(old_str, new_str)
    else:
        print(f"Warning: '{old_str}' not found on line {line_num}. Found: {lines[idx]}")

replace_line(325, 'return true;', 'return null;')
replace_line(688, 'return;', 'return false;')
replace_line(740, 'return;', 'return false;')
replace_line(876, 'return;', 'return null;')
replace_line(879, 'updateHeaderWithOldValue(level,1, areaSize+headSize,hash,value);', 'updateHeaderWithOldValue(level,1, areaSize+headSize,hash,key,value);')
replace_line(934, 'return;', 'return null;')
replace_line(937, 'updateHeaderWithOldValue(level,1, areaSize+headSize,hash,value);', 'updateHeaderWithOldValue(level,1, areaSize+headSize,hash,key,value);')
replace_line(1041, 'return;', 'return false;')
replace_line(1056, 'return;', 'return false;')
replace_line(1101, 'return;', 'return false;')
replace_line(1117, 'return;', 'return false;')
replace_line(1248, 'return;', 'return false;')
replace_line(1252, 'heads[hash]', 'headKeys[hash]')
replace_line(1262, 'return;', 'return false;')
replace_line(1296, 'return;', 'return false;')
replace_line(1300, 'heads[hash]', 'headKeys[hash]')
replace_line(1309, 'return;', 'return false;')
replace_line(1321, 'return;', 'return false;')

with open(r'C:\2025\code\ImageFileServer\p2p-db\src\main\java\ds\DsHashMap.java', 'w', encoding='utf-8') as f:
    f.writelines(lines)
