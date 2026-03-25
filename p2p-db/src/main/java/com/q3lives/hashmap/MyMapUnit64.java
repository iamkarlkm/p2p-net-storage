package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
//public class MyMapUnit64<K extends HashCode64,V> implements Serializable{

public class MyMapUnit64<K, V> implements Serializable {

    /**
     *
     */
    private static final long serialVersionUID = 1L;
    private static final int LAYER_LIMIT = 7;
    public static int conflictCount;
    private Object[] table = new Object[256];

    public static long hashCode64(Object key) {
        //if(key==null) return 0;
        //key.getClass().getInterfaces();
        if (key instanceof HashCode64) {
            //System.out.println("HashCode64");
            return ((HashCode64) key).hashCode64();

        }
        long hashcode64 = 0;
        if (key instanceof String) {
            byte[] bytes = ((String) key).getBytes();
            long tmp1 = bytes[0];
            hashcode64 |= (tmp1 << 56);
            tmp1 = bytes[1];
            hashcode64 |= (tmp1 << 48);
            tmp1 = bytes[2];
            hashcode64 |= (tmp1 << 40);
            tmp1 = bytes[3];
            hashcode64 |= (tmp1 << 32);
            hashcode64 |= (key.hashCode() & 0x00000000ffffffffL);
        } else if (key instanceof Date) {
            hashcode64 = ((Date) key).getTime();
        } else if (key instanceof Long) {
            //System.out.println("Long");
            hashcode64 = ((Long) key).longValue();
        } else {
            long tmp1 = key.hashCode();
            hashcode64 |= (tmp1 << 32);
            hashcode64 |= ((key.toString().hashCode()) & 0x00000000ffffffffL);
        }
        return hashcode64;

    }

    public static final int toHash(long code, int index) {
        int hash = 0;

        switch (index) {
            case 0:
                hash = (int) ((code >> 56) & 0xff);
                break;
            case 1:
                hash = (int) ((code >> 48) & 0xff);
                break;
            case 2:
                hash = (int) ((code >> 40) & 0xff);
                break;
            case 3:
                hash = (int) ((code >> 32) & 0xff);
                break;
            case 4:
                hash = (int) ((code >> 24) & 0xff);
                break;
            case 5:
                hash = (int) ((code >> 16) & 0xff);
                break;
            case 6:
                hash = (int) ((code >> 8) & 0xff);
                break;
            case 7:
                hash = (int) (code & 0xff);
        }
        return hash;
    }
//	public static final int toHash(String key,int index){
//		int code = key.hashCode();		
//		char sortChar = key.charAt(0); 			
//		int hash = 0;
//		switch(index){
//		case 0:hash = ((code>>24) & 0xff);break;
//		case 1:hash = ((code>>16) & 0xff);break;
//		case 2:hash = ((code>>8) & 0xff);break;
//		case 3:hash = (code & 0xff);
//		}		
//		return hash;
//	}

    public V put(K key, V value) {
        KeyValue64<K, V> pair = new KeyValue64<K, V>(key, value);
        long code = pair.hashCode64();
        //System.out.println("put:"+code);
        int hash = (int) ((code >> 56) & 0xff);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {

            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair1 = (KeyValue64<K, V>) table[hash];
                MyMapUnit64<K, V> mmu = new MyMapUnit64<K, V>();
                mmu.put(pair1, pair1.hashCode64(), 1);
                mmu.put(pair, code, 1);
                table[hash] = mmu;
                return null;

            }

            MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[hash];
            return mmu.put(pair, code, 1);

        }

    }

    V put(KeyValue64<K, V> pair, long code, int index) {
        if (index >= LAYER_LIMIT) {
            return endPut(pair, code);
        }
        //int hash = toHash(code, index);
        int hash = 0;
        switch (index) {
            case 0:
                hash = (int) ((code >> 56) & 0xff);
                break;
            case 1:
                hash = (int) ((code >> 48) & 0xff);
                break;
            case 2:
                hash = (int) ((code >> 40) & 0xff);
                break;
            case 3:
                hash = (int) ((code >> 32) & 0xff);
                break;
            case 4:
                hash = (int) ((code >> 24) & 0xff);
                break;
            case 5:
                hash = (int) ((code >> 16) & 0xff);
                break;
            case 6:
                hash = (int) ((code >> 8) & 0xff);
                break;
            case 7:
                hash = (int) (code & 0xff);
        }
        if (table[hash] == null) {

            table[hash] = pair;
            return null;
        } else {
            //index++;
            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair1 = (KeyValue64<K, V>) table[hash];
                MyMapUnit64<K, V> mmu = new MyMapUnit64<K, V>();
                mmu.put(pair1, pair1.hashCode64(), index + 1);
                table[hash] = mmu;
                return mmu.put(pair, code, index + 1);

            }
            //MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
            return ((MyMapUnit64<K, V>) table[hash]).put(pair, code, index + 1);

        }

    }

    private V endPut(KeyValue64<K, V> pair, long code) {
        //int hash = toHash(code, LAYER_LIMIT);
        int hash = (int) (code & 0xff);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {

            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair1 = (KeyValue64<K, V>) table[hash];
                if (pair.equals(pair1)) {
                    V oldValue = pair1.value;
                    table[hash] = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                HashMap<String, KeyValue64<K, V>> map = new HashMap<String, KeyValue64<K, V>>();
                map.put(pair1.key.toString(), pair1);
                map.put(pair.key.toString(), pair);
                table[hash] = map;
                return null;

            }
            conflictCount++;
            KeyValue64<K, V> obj = ((HashMap<String, KeyValue64<K, V>>) table[hash]).put(pair.key.toString(), pair);
            if (obj == null) {
                return null;
            }
            return obj.value;

        }
    }

    public V get(K key) {
        //int code = KeyValue.getCode(key);
        //System.out.println("get:"+code);
        long code = hashCode64(key);
        int index = 0;

        int hash = (int) ((code >> 56) & 0xff);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair = (KeyValue64<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;

            } else {
                return ((MyMapUnit64<K, V>) table[hash]).get(key, code, index + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,index+1);
            }

        }
        return null;

    }

    private V get(Object key, long code, int index) {
        int hash = 0;
        switch (index) {
            case 0:
                hash = (int) ((code >> 56) & 0xff);
                break;
            case 1:
                hash = (int) ((code >> 48) & 0xff);
                break;
            case 2:
                hash = (int) ((code >> 40) & 0xff);
                break;
            case 3:
                hash = (int) ((code >> 32) & 0xff);
                break;
            case 4:
                hash = (int) ((code >> 24) & 0xff);
                break;
            case 5:
                hash = (int) ((code >> 16) & 0xff);
                break;
            case 6:
                hash = (int) ((code >> 8) & 0xff);
                break;
            case 7:
                hash = (int) (code & 0xff);
        }
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair = (KeyValue64<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;
            } else {

                //return (index >= LAYER_LIMIT)? endGet( key,code) : mmu.get(key,code,index+1);
                if (index >= LAYER_LIMIT) {
                    KeyValue64<K, V> obj = ((HashMap<String, KeyValue64<K, V>>) table[hash]).get(key.toString());
                    if (obj == null) {
                        return null;
                    }
                    return obj.value;
                    //return endGet( key,code);
                }
                MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[hash];
                return mmu.get(key, code, index + 1);
            }
        }
        return null;
    }

    public void listEntry(List<KeyValue64<K, V>> resultEntry) {
        int index = 0;
        for (int i = 128; i < 256; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    resultEntry.add(pair);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.listEntry(resultEntry, index + 1);
                }
            }
        }
        for (int i = 0; i < 128; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    resultEntry.add(pair);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.listEntry(resultEntry, index + 1);
                }
            }
        }

    }

    private void listEntry(List<KeyValue64<K, V>> list, int index) {
        if (index >= LAYER_LIMIT) {
            endListEntry(list);
            return;
        }
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    list.add(pair);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.listEntry(list, index + 1);
                }
            }
        }

    }

    private void endListEntry(List<KeyValue64<K, V>> result) {
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    result.add(pair);
                } else {
                    HashMap<String, KeyValue64<K, V>> map = (HashMap<String, KeyValue64<K, V>>) table[i];

                    Set<Entry<String, KeyValue64<K, V>>> set = map.entrySet();
                    for (Entry<String, KeyValue64<K, V>> e : set) {
                        result.add(e.getValue());
                    }
                }
            }
        }

    }

    public void keyList(List<K> list) {
        int index = 0;
        for (int i = 128; i < 256; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }
        for (int i = 0; i < 128; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }
    }

    public void unsignedKeyList(List<K> list) {
        int index = 0;
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.keyList(list, index + 1);
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
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }

    }

    private void endKeyList(List<K> result) {
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i] instanceof KeyValue64) {
                    KeyValue64<K, V> pair = (KeyValue64<K, V>) table[i];
                    result.add(pair.key);
                } else {
                    HashMap<String, KeyValue64<K, V>> map = (HashMap<String, KeyValue64<K, V>>) table[i];

                    Set<Entry<String, KeyValue64<K, V>>> set = map.entrySet();
                    for (Entry<String, KeyValue64<K, V>> e : set) {
                        result.add(e.getValue().getKey());
                    }
                }
            }
        }

    }

    V remove(K key) {
        long code = hashCode64(key);
        int index = 0;
        int hash = (int) ((code >> 56) & 0xff);
        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair = (KeyValue64<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                return ((MyMapUnit64<K, V>) table[hash]).remove(key, code, index + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,index+1);
            }

        }
        return null;

    }

    private V remove(K key, long code, int index) {

        int hash = toHash(code, index);

        if (table[hash] != null) {
            if (table[hash] instanceof KeyValue64) {
                KeyValue64<K, V> pair = (KeyValue64<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                if (index >= LAYER_LIMIT) {
                    KeyValue64<K, V> obj = ((HashMap<String, KeyValue64<K, V>>) table[hash]).remove(key.toString());
                    if (obj == null) {
                        return null;
                    }
                    return obj.value;
                    //return endGet( key,code);
                }
                MyMapUnit64<K, V> mmu = (MyMapUnit64<K, V>) table[hash];
                return mmu.remove(key, code, index + 1);
            }

        }
        return null;
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
        TestKey64 key64 = new TestKey64("love");
        //System.out.println(key64 instanceof HashCode64);
        System.out.println(hashCode64(key64));

    }

}
