package javax.net.p2p.map;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import javax.net.p2p.utils.SecurityUtils;
//public class MyMapUnit64<K extends HashCode64,V> implements Serializable{

public class HashMapUnit256<K, V> implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Charset UTF_8 = Charset.forName("utf-8");
    private static final int LAYER_LIMIT = 31;
    public static int conflictCount;
    private final Object[] table = new Object[256];

    public static byte[] hashCode256(Object key) {
        if (key instanceof HashCode256) {
            return ((HashCode256) key).hashCode256();
        }
        final byte[] array = new byte[32];
        if (key.getClass() == String.class) {
            byte[] bytes = ((String) key).getBytes(UTF_8);
            if (bytes.length >= 28) {
                System.arraycopy(bytes, 0, array, 0, 28);
                byte[] hashcode = ByteBuffer.allocate(4).putInt(key.hashCode()).array();
                for (int i = 28, j = 0; i < 32; i++, j++) {
                    array[i] = hashcode[j];
                }
            } else {
//                final byte[] sha256 = SecurityUtils.sha256((String) key);
//                System.arraycopy(bytes, 0, array, 0, bytes.length);
//                System.arraycopy(sha256, bytes.length, array, bytes.length, 32 - bytes.length);
            }
        }
        return array;
    }

    public V put(K key, V value) {
        KeyValue256<K, V> pair = new KeyValue256<>(key, value);
        byte[] code = pair.hashCode256();
        //System.out.println("put:"+code);
        int hash = (int) (code[0]);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {

            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair1 = (KeyValue256<K, V>) table[hash];
                HashMapUnit256<K, V> mmu = new HashMapUnit256<>();
                mmu.put(pair1, pair1.hashCode256(), 1);
                mmu.put(pair, code, 1);
                table[hash] = mmu;
                return null;

            }

            HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[hash];
            return mmu.put(pair, code, 1);

        }

    }

    V put(KeyValue256<K, V> pair, byte[] code, int index) {
        if (index >= LAYER_LIMIT) {
            return endPut(pair, code);
        }
        int hash = (int) (code[index]);
        if (table[hash] == null) {

            table[hash] = pair;
            return null;
        } else {
            //index++;
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair1 = (KeyValue256<K, V>) table[hash];
                HashMapUnit256<K, V> mmu = new HashMapUnit256<>();
                mmu.put(pair1, pair1.hashCode256(), index + 1);
                table[hash] = mmu;
                return mmu.put(pair, code, index + 1);

            }
            //MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
            return ((HashMapUnit256<K, V>) table[hash]).put(pair, code, index + 1);

        }

    }

    private V endPut(KeyValue256<K, V> pair, byte[] code) {
        int hash = (int) (code[LAYER_LIMIT]);

        if (table[hash] == null) {
            table[hash] = pair;
            return null;
        } else {
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pairOld = (KeyValue256<K, V>) table[hash];
                if (pair.equals(pairOld)) {
                    V oldValue = pairOld.value;
                    table[hash] = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                //处理首次哈希冲突
                LinkedList<KeyValue256<K, V>> list = new LinkedList();
                list.add(pairOld);
                list.add(pair);
                table[hash] = list;
                return null;

            }
            //处理多重哈希冲突
            conflictCount++;
            LinkedList<KeyValue256<K, V>> list = ((LinkedList<KeyValue256<K, V>>) table[hash]);
            Iterator<KeyValue256<K, V>> it = list.iterator();
            while (it.hasNext()) {
                KeyValue256<K, V> kv = it.next();
                if (pair.equals(kv)) {
                    V oldValue = kv.value;
                    table[hash] = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
            }
            // 没找到同key元素，执行添加操作
            list.add(pair);
            return null;
        }
    }

    public V get(K key) {
        //int code = KeyValue.getCode(key);
        //System.out.println("get:"+code);
        byte[] code = hashCode256(key);
        int index = 0;

        int hash = (int) (code[0]);
        if (table[hash] != null) {
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair = (KeyValue256<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;

            } else {
                return ((HashMapUnit256<K, V>) table[hash]).get(key, code, index + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,index+1);
            }

        }
        return null;

    }

    private V get(Object key, byte[] code, int index) {
        int hash = (int) (code[LAYER_LIMIT]);
        if (table[hash] != null) {
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair = (KeyValue256<K, V>) table[hash];
                return (key.equals(pair.key)) ? pair.value : null;
            } else {

                //return (index >= LAYER_LIMIT)? endGet( key,code) : mmu.get(key,code,index+1);
                if (index >= LAYER_LIMIT) {//最底层特殊处理
                    //处理多重哈希冲突
                    LinkedList<KeyValue256<K, V>> list = ((LinkedList<KeyValue256<K, V>>) table[hash]);
                    Iterator<KeyValue256<K, V>> it = list.iterator();
                    while (it.hasNext()) {
                        KeyValue256<K, V> kv = it.next();
                        if (key.equals(kv.key)) {
                            return kv.value;// 找到同key元素
                        }
                    }
                    return null;// 没找到同key元素
                    //return endGet( key,code);
                }
                HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[hash];
                return mmu.get(key, code, index + 1);
            }
        }
        return null;
    }

    public void listEntry(List<KeyValue256<K, V>> resultEntry) {
        int index = 0;
        for (int i = 128; i < 256; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    resultEntry.add(pair);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.listEntry(resultEntry, index + 1);
                }
            }
        }
        for (int i = 0; i < 128; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    resultEntry.add(pair);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.listEntry(resultEntry, index + 1);
                }
            }
        }

    }

    private void listEntry(List<KeyValue256<K, V>> list, int index) {
        if (index >= LAYER_LIMIT) {
            endListEntry(list);
            return;
        }
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    list.add(pair);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.listEntry(list, index + 1);
                }
            }
        }

    }

    private void endListEntry(List<KeyValue256<K, V>> result) {
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    result.add(pair);
                } else {//最底层特殊处理
                    //处理多重哈希冲突
                    LinkedList<KeyValue256<K, V>> list = ((LinkedList<KeyValue256<K, V>>) table[i]);
//                    list.forEach((kv) -> {
//                        result.add(kv);
//                    });
                }
            }
        }

    }

    public void keyList(List<K> list) {
        int index = 0;
        for (int i = 128; i < 256; i++) {//首先处理负整数集
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }
        for (int i = 0; i < 128; i++) {//其次处理0、正整数集
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }
    }

    public void unsignedKeyList(List<K> list) {
        int index = 0;
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
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
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    list.add(pair.key);
                } else {
                    HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[i];
                    mmu.keyList(list, index + 1);
                }
            }
        }

    }

    private void endKeyList(List<K> result) {
        for (int i = 0; i < 256; i++) {
            if (table[i] != null) {
                if (table[i].getClass() == KeyValue256.class) {
                    KeyValue256<K, V> pair = (KeyValue256<K, V>) table[i];
                    result.add(pair.key);
                } else {//最底层特殊处理
                    //处理多重哈希冲突
                    LinkedList<KeyValue256<K, V>> list = ((LinkedList<KeyValue256<K, V>>) table[i]);
//                    list.forEach((kv) -> {
//                        result.add(kv.key);
//                    });
                }
            }
        }

    }

    V remove(K key) {
        byte[] code = hashCode256(key);
        int index = 0;
        int hash = (int) (code[LAYER_LIMIT]);
        if (table[hash] != null) {
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair = (KeyValue256<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                return ((HashMapUnit256<K, V>) table[hash]).remove(key, code, index + 1);
//				MyMapUnit<K, V> mmu = (MyMapUnit<K, V>)table[hash];
//				
//				return mmu.get(key,bytes,index+1);
            }

        }
        return null;

    }

    private V remove(K key, byte[] code, int index) {

        int hash = (int) (code[LAYER_LIMIT]);

        if (table[hash] != null) {
            if (table[hash].getClass() == KeyValue256.class) {
                KeyValue256<K, V> pair = (KeyValue256<K, V>) table[hash];
                if (key.equals(pair.key)) {
                    V oldValue = pair.value;
                    table[hash] = null;
                    return oldValue;
                }
                return null;

            } else {
                if (index >= LAYER_LIMIT) {//最底层特殊处理
                    //处理多重哈希冲突
                    LinkedList<KeyValue256<K, V>> list = ((LinkedList<KeyValue256<K, V>>) table[hash]);
                    Iterator<KeyValue256<K, V>> it = list.iterator();
                    while (it.hasNext()) {
                        KeyValue256<K, V> kv = it.next();
                        if (key.equals(kv.key)) {
                            it.remove();
                            return kv.value;// 找到同key元素
                        }
                    }
                    return null;// 没找到同key元素
                    //return endGet( key,code);
                }
                HashMapUnit256<K, V> mmu = (HashMapUnit256<K, V>) table[hash];
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

    }

}
