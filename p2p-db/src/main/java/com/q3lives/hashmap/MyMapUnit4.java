package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

public class MyMapUnit4<K, V> implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int LAYER_LIMIT = 7;
    private static final int[] LAYER_BITS_OP = new int[]{28, 24, 20, 16, 12, 8, 4, 0};
    public static int conflictCount;
    private Object[] table = new Object[16];

    public static final int toHash(int code, int layer) {
        return ((code >> LAYER_BITS_OP[layer]) & 0x0f);
    }

    public static final int toHash(String key, int layer) {
        return ((key.hashCode() >> LAYER_BITS_OP[layer]) & 0x0f);
    }

    V put(K key, V value) {
        KeyValue<K, V> pair = new KeyValue<K, V>(key, value);
        int code = pair.hashCode();
        int hash = ((code >> 28) & 0x0f);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {

            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair1 = (KeyValue<K, V>) table[hash];
                MyMapUnit4<K, V> mmu = new MyMapUnit4<K, V>();
                mmu.put(pair1, pair1.hashCode(), 1);
                mmu.put(pair, code, 1);
                table[hash] = mmu;
                return null;

            }

            MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[hash];
            return mmu.put(pair, code, 1);

        }

    }

    V put(KeyValue<K, V> pair, int code, int layer) {
        if (layer >= LAYER_LIMIT) {
            return endPut(pair, code);
        }
        int hash = ((code >> LAYER_BITS_OP[layer]) & 0x0f);
        if (table[hash] == null) {

            table[hash] = pair;
            return null;
        } else {
            //layer++;
            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair1 = (KeyValue<K, V>) table[hash];
                MyMapUnit4<K, V> mmu = new MyMapUnit4<K, V>();
                mmu.put(pair1, pair1.hashCode(), layer + 1);
                table[hash] = mmu;
                return mmu.put(pair, code, layer + 1);

            }
            //MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
            return ((MyMapUnit4<K, V>) table[hash]).put(pair, code, layer + 1);

        }

    }

    private V endPut(KeyValue<K, V> pair, int code) {
        //int hash = toHash(code, LAYER_LIMIT);
        int hash = (code & 0x0f);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {

            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair1 = (KeyValue<K, V>) table[hash];
                if (pair.equals(pair1)) {
                    V oldValue = pair1.value;
                    table[hash] = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                HashMap<String, KeyValue<K, V>> map = new HashMap<String, KeyValue<K, V>>();
                map.put(pair1.key.toString(), pair1);
                map.put(pair.key.toString(), pair);
                table[hash] = map;
                return null;

            }
            conflictCount++;
            KeyValue<K, V> obj = ((HashMap<String, KeyValue<K, V>>) table[hash]).put(pair.key.toString(), pair);
            if (obj == null) {
                return null;
            }
            return obj.value;

        }
    }

    V get(Object key) {
        //int code = KeyValue.getCode(key);
        //System.out.println("get:"+code);
        int code = key.hashCode();
        int layer = 0;
        int hash = ((code >> 28) & 0x0f);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair = (KeyValue<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;

            } else {
                return ((MyMapUnit4<K, V>) table[hash]).get(key, code, layer + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,layer+1);
            }

        }
        return null;

    }

    private V get(Object key, int code, int layer) {
        int hash = ((code >> LAYER_BITS_OP[layer]) & 0x0f);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair = (KeyValue<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;
            } else {

                //return (layer >= LAYER_LIMIT)? endGet( key,code) : mmu.get(key,code,layer+1);
                if (layer >= LAYER_LIMIT) {
                    KeyValue<K, V> obj = ((HashMap<String, KeyValue<K, V>>) table[hash]).get(key.toString());
                    if (obj == null) {
                        return null;
                    }
                    return obj.value;
                    //return endGet( key,code);
                }
                MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[hash];
                return mmu.get(key, code, layer + 1);
            }
        }
        return null;
    }

//	private V endGet(Object key, int code) {
//		
//		int  hash = (code & 0xff);
//		
////		if(table[hash]!=null){
////			if(table[hash] instanceof KeyValue){
////				KeyValue<K, V> pair = (KeyValue<K, V>) table[hash];
////				return (key.equals(pair.key))? pair.value : null;
////
////			}else{
//
////			
////		}
//		return null;
//	}
    void listEntry(List<KeyValue<K, V>> list) {
        int layer = 0;
        for (int i = 8; i < 16; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listEntry(list, layer + 1);
                }
            }
        }
        for (int i = 0; i < 8; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listEntry(list, layer + 1);
                }
            }
        }

    }

    private void listEntry(List<KeyValue<K, V>> list, int layer) {
        if (layer >= LAYER_LIMIT) {
            endListEntry(list);
            return;
        }
        for (int i = 0; i < 16; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listEntry(list, layer + 1);
                }
            }
        }

    }

    private void endListEntry(List<KeyValue<K, V>> result) {
        for (int i = 0; i < 16; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    result.add(pair);
                } else {
                    HashMap<String, KeyValue<K, V>> map = (HashMap<String, KeyValue<K, V>>) table[i];

                    Set<Entry<String, KeyValue<K, V>>> set = map.entrySet();
                    for (Entry<String, KeyValue<K, V>> e : set) {
                        result.add(e.getValue());
                    }
                }
            }
        }

    }

    void listKeys(List<K> list) {
        int layer = 0;
        for (int i = 8; i < 16; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listKeys(list, layer + 1);
                }
            }
        }
        for (int i = 0; i < 8; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listKeys(list, layer + 1);
                }
            }
        }
    }

    private void listKeys(List<K> list, int layer) {
        if (layer >= LAYER_LIMIT) {
            endListKeys(list);
            return;
        }
        for (int i = 0; i < 16; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[i];
                    mmu.listKeys(list, layer + 1);
                }
            }
        }

    }

    private void endListKeys(List<K> result) {
        for (int i = 0; i < 16; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue) {
                    KeyValue<K, V> pair = (KeyValue<K, V>) table[i];
                    result.add(pair.key);
                } else {
                    HashMap<String, KeyValue<K, V>> map = (HashMap<String, KeyValue<K, V>>) table[i];

                    Set<Entry<String, KeyValue<K, V>>> set = map.entrySet();
                    for (Entry<String, KeyValue<K, V>> e : set) {
                        result.add(e.getValue().getKey());
                    }
                }
            }
        }

    }

    V remove(K key) {
        int code = key.hashCode();
        int layer = 0;
        int hash = ((code >> 28) & 0x0f);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair = (KeyValue<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                return ((MyMapUnit4<K, V>) table[hash]).remove(key, code, layer + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,layer+1);
            }

        }
        return null;

    }

    private V remove(K key, int code, int layer) {
        int hash = ((code >> LAYER_BITS_OP[layer]) & 0x0f);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue) {
                KeyValue<K, V> pair = (KeyValue<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                if (layer >= LAYER_LIMIT) {
                    KeyValue<K, V> obj = ((HashMap<String, KeyValue<K, V>>) table[hash]).remove(key.toString());
                    if (obj == null) {
                        return null;
                    }
                    return obj.value;
                    //return endGet( key,code);
                }
                MyMapUnit4<K, V> mmu = (MyMapUnit4<K, V>) table[hash];
                return mmu.remove(key, code, layer + 1);
            }

        }
        return null;
    }

    void clear() {
        for (int i = 0; i < 16; i++) {
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
        String s = "9中";
        char[] chars = s.toCharArray();
        int code1 = (s.charAt(0) << 16) & 0xffff0000;
        int code2 = s.hashCode() & 0xffff;
        int code = code1 | code2;
        System.out.println(code1);

    }

}
