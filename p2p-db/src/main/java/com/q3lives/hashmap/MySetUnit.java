package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

public class MySetUnit<K> implements Serializable {

    /**
     *
     */
    private static final long serialVersionUID = 1L;
    private static final int LAYER_LIMIT = 3;
    public static int conflictCount;
    private Object[] table = new Object[256];

    public static final int toHash(int code, int index) {
        int hash = 0;
        switch (index) {
            case 0:
                hash = ((code >> 24) & 0xff);
                break;
            case 1:
                hash = ((code >> 16) & 0xff);
                break;
            case 2:
                hash = ((code >> 8) & 0xff);
                break;
            case 3:
                hash = (code & 0xff);
        }

        return hash;
    }

    boolean put(K key) {
        //KeyValue<K, V> pair = new KeyValue<K, V>(key, value);
        int code = key.hashCode();
        //System.out.println("put:"+code);
        int hash = ((code >> 24) & 0xff);

        if (table[hash] == null) {
            table[hash] = key;
            return true;
        } else {

            if (table[hash] instanceof MySetUnit) {
                MySetUnit<K> mmu = (MySetUnit<K>) table[hash];
                return mmu.put(key, code, 1);

            }
            K key1 = (K) table[hash];
            MySetUnit<K> mmu = new MySetUnit<K>();
            mmu.put(key1, key1.hashCode(), 1);
            mmu.put(key, code, 1);
            table[hash] = mmu;
            return true;
        }

    }

    boolean put(K key, int code, int index) {
        if (index >= LAYER_LIMIT) {
            return endPut(key, code);
        }
        //int hash = toHash(code, index);
        int hash = 0;
        switch (index) {
            case 0:
                hash = ((code >> 24) & 0xff);
                break;
            case 1:
                hash = ((code >> 16) & 0xff);
                break;
            case 2:
                hash = ((code >> 8) & 0xff);
                break;
            case 3:
                hash = (code & 0xff);
        }
        if (table[hash] == null) {
            table[hash] = key;
            return true;
        } else {
            //index++;
            if (table[hash] instanceof MySetUnit) {
                //MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
                return ((MySetUnit<K>) table[hash]).put(key, code, index + 1);
            }
            K key1 = (K) table[hash];
            MySetUnit<K> mmu = new MySetUnit<K>();
            mmu.put(key1, key1.hashCode(), index + 1);
            table[hash] = mmu;
            return mmu.put(key, code, index + 1);

        }

    }

    private boolean endPut(K key, int code) {
        int hash = (code & 0xff);
        if (table[hash] == null) {
            table[hash] = key;
            return true;
        } else {

            if (table[hash] instanceof HashMap) {
                conflictCount++;
                K obj = ((HashMap<String, K>) table[hash]).put(key.toString(), key);
                if (obj == null) {
                    return true;
                }
                return false;
            }
            K key1 = (K) table[hash];
            if (key.equals(key1)) {

                return false;
            }
            HashMap<String, K> map = new HashMap<String, K>();
            map.put(key1.toString(), key1);
            map.put(key.toString(), key);
            table[hash] = map;
            return true;
        }
    }

    void listByUnsigned(List<K> list) {
        int index = 0;
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof MySetUnit) {
                    MySetUnit<K> mmu = (MySetUnit<K>) table[i];
                    mmu.keyList(list, index + 1);
                    K key = (K) table[i];
                    list.add(key);
                } else {
                    K key = (K) table[i];
                    list.add(key);
                }
            }
        }

    }

    void keyList(List<K> list) {
        int index = 0;
        for (int i = 128; i < 256; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i] instanceof MySetUnit) {
                    MySetUnit<K> mmu = (MySetUnit<K>) table[i];
                    mmu.keyList(list, index + 1);
                    K key = (K) table[i];
                    list.add(key);
                } else {
                    K key = (K) table[i];
                    list.add(key);
                }
            }
        }
        for (int i = 0; i < 128; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i] instanceof MySetUnit) {
                    MySetUnit<K> mmu = (MySetUnit<K>) table[i];
                    mmu.keyList(list, index + 1);

                } else {
                    K key = (K) table[i];
                    list.add(key);
                }
            }
        }
    }

    private void keyList(List<K> list, int index) {
        if (index >= LAYER_LIMIT) {
            endKeyList(list);
            return;
        }
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof MySetUnit) {
                    MySetUnit<K> mmu = (MySetUnit<K>) table[i];
                    mmu.keyList(list, index + 1);

                } else {
                    K key = (K) table[i];
                    list.add(key);

                }
            }
        }

    }

    private void endKeyList(List<K> result) {
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof HashMap) {
                    HashMap<String, K> map = (HashMap<String, K>) table[i];

                    Set<Entry<String, K>> set = map.entrySet();
                    for (Entry<String, K> e : set) {
                        result.add(e.getValue());
                    }

                } else {
                    K key = (K) table[i];
                    result.add(key);

                }
            }
        }

    }

    boolean remove(Object o) {
        int code = o.hashCode();
        int index = 0;
        int hash = ((code >> 24) & 0xff);
        if (table[hash] != null) {
            if (table[hash] instanceof MySetUnit) {
                return ((MySetUnit<K>) table[hash]).remove(o, code, index + 1);

            } else {
                if (o.equals(table[hash])) {

                    table[hash] = null;
                    return true;
                }
                return false;
            }

        }
        return false;

    }

    private boolean remove(Object o, int code, int index) {

        int hash = 0;
        switch (index) {
            case 0:
                hash = ((code >> 24) & 0xff);
                break;
            case 1:
                hash = ((code >> 16) & 0xff);
                break;
            case 2:
                hash = ((code >> 8) & 0xff);
                break;
            case 3:
                hash = (code & 0xff);
        }

        if (table[hash] != null) {
            if (index >= LAYER_LIMIT) {
                if (table[hash] instanceof MySetUnit) {
                    K obj = ((HashMap<String, K>) table[hash]).remove(o.toString());
                    if (obj == null) {
                        return false;
                    }
                    return true;
                } else {
                    //K key1 = (K) table[hash];
                    if (o.equals(table[hash])) {

                        table[hash] = null;
                        return true;
                    }
                }

            }
            if (table[hash] instanceof MySetUnit) {
                MySetUnit<K> mmu = (MySetUnit<K>) table[hash];
                return mmu.remove(o, code, index + 1);

            } else {
                if (o.equals(table[hash])) {
                    table[hash] = null;
                    return true;
                }
                return false;
            }

        }
        return false;
    }

    boolean get(Object key) {
        int code = key.hashCode();
        int index = 0;

        int hash = ((code >> 24) & 0xff);
        if (table[hash] != null) {
            if (table[hash] instanceof MySetUnit) {
                return ((MySetUnit<K>) table[hash]).get(key, code, index + 1);

            } else {
                return key.equals(table[hash]);
            }

        }
        return false;

    }

    private boolean get(Object key, int code, int index) {
        int hash = 0;
        switch (index) {
            case 0:
                hash = ((code >> 24) & 0xff);
                break;
            case 1:
                hash = ((code >> 16) & 0xff);
                break;
            case 2:
                hash = ((code >> 8) & 0xff);
                break;
            case 3:
                hash = (code & 0xff);
        }

        if (table[hash] != null) {
            if (index >= LAYER_LIMIT) {
                if (table[hash] instanceof MySetUnit) {
                    K obj = ((HashMap<String, K>) table[hash]).get(key.toString());
                    if (obj == null) {
                        return false;
                    }
                    return true;
                } else {
                    return key.equals(table[hash]);
                }

            }
            if (table[hash] instanceof MySetUnit) {
                MySetUnit<K> mmu = (MySetUnit<K>) table[hash];
                return mmu.get(key, code, index + 1);

            } else {
                return key.equals(table[hash]);
            }
        }
        return false;
    }

    void clear() {
        for (int i = 0; i < 256; i++) {
            table[i] = null;
        }
    }

    //自定义序列化操作：
    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
    }

    private void readObject(java.io.ObjectInputStream s) throws IOException,
            ClassNotFoundException {
        s.defaultReadObject();

    }

    public static void main(String[] args) {

    }

}
