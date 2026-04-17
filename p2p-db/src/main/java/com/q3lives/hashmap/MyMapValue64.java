package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;

import java.util.List;
import java.util.Map.Entry;

/**
 *
 * @author KARL Jinkai
 * @Info MyHashMap内部使用的存储单元类
 * @param <K>　键对象
 * @param <V>　值对象
 */
public final class MyMapValue64<K extends HashCode64, V> implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final long[] integer = new long[64];
    private static final int LAYER_LIMIT = 63;

    static {
        int pos = 63;
        for (int i = 0; i < 64; i++) {
            integer[i] = 1 << pos;

            pos--;
        }

    }

    private long leftCount = 0;
    private long rightCount = 0;
    private Object leftObj;
    private Object rightObj;

    public long getLeftCount() {
        return leftCount;
    }

    public long getRightCount() {
        return rightCount;
    }

    //自定义序列化操作：
    private void writeObject(java.io.ObjectOutputStream s)
            throws IOException {
        s.writeLong(leftCount);
        s.writeLong(rightCount);
        s.writeObject(leftObj);
        s.writeObject(rightObj);
    }

    private void readObject(java.io.ObjectInputStream s)
            throws IOException, ClassNotFoundException {
        leftCount = s.readLong();
        rightCount = s.readLong();
        leftObj = s.readObject();
        rightObj = s.readObject();

    }

    public MyMapValue64() {
    }

    void clear() {
        leftCount = 0;
        rightCount = 0;
        leftObj = null;
        rightObj = null;
    }

    boolean containsKey(K key) {
        V v = get(key);
        return v == null ? false : true;

    }

    V remove(K key) {
        long code = key.hashCode64();
        int index = 0;

        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                V oldValue = mmv.remove(key, code, ++index);
                if (oldValue != null) {
                    leftCount--;
                }
                return oldValue;
            } else if (leftCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    leftObj = null;
                    leftCount--;
                    return oldValue;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                V oldValue = mmv.remove(key, code, ++index);
                if (oldValue != null) {
                    rightCount--;
                }
                return oldValue;
            } else if (rightCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    rightObj = null;
                    rightCount--;
                    return oldValue;
                }
            }
            return null;
        }

    }

    private V remove(K key, long code, int index) {
        if (index >= LAYER_LIMIT) {
            return endRemove(key, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                V oldValue = mmv.remove(key, code, ++index);
                if (oldValue != null) {
                    leftCount--;
                }
                return oldValue;
            } else if (leftCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    leftObj = null;
                    leftCount--;
                    return oldValue;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                V oldValue = mmv.remove(key, code, ++index);
                if (oldValue != null) {
                    rightCount--;
                }
                return oldValue;
            } else if (rightCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    rightObj = null;
                    rightCount--;
                    return oldValue;
                }
            }
            return null;
        }

    }

    private V endRemove(K key, long code) {
        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount != 0) {
                List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;
                for (int i = 0; i < list.size(); i++) {
                    KeyValue64<K, V> kv = list.get(i);
                    if ((kv.key).equals(key)) {
                        V oldValue = kv.value;
                        leftCount--;
                        list.remove(i);
                        return oldValue;
                    }
                }

            }
            return null;
        } else if (rightCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
            for (int i = 0; i < list.size(); i++) {
                KeyValue64<K, V> kv = list.get(i);
                if ((kv.key).equals(key)) {
                    V oldValue = kv.value;
                    rightCount--;
                    list.remove(i);
                    return oldValue;
                }
            }

        }
        return null;
    }

    V get(K key) {
        long code = key.hashCode64();
        int index = 0;

        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                return mmv.get(key, code, ++index);
            } else if (leftCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                return mmv.get(key, code, ++index);
            } else if (rightCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        }

    }

    private V get(K key, long code, int index) {
        if (index >= LAYER_LIMIT) {
            return endGet(key, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                return mmv.get(key, code, ++index);
            } else if (leftCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                return mmv.get(key, code, ++index);
            } else if (rightCount == 1) {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        }

    }

    private V endGet(K key, long code) {
        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount != 0) {
                List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;
                for (KeyValue64<K, V> kv : list) {
                    if (key.equals(kv.key)) {
                        return kv.value;
                    }
                }
            }
            return null;
        } else if (rightCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
            for (KeyValue64<K, V> kv : list) {
                if (key.equals(kv.key)) {
                    return kv.value;
                }
            }
        }
        return null;

    }

    public V put(K key, V value) {
        long code = key.hashCode64();
        int index = 0;
        KeyValue64<K, V> pair = new KeyValue64<K, V>(key, value);
        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                V tmp = mmv.put(pair, code, ++index);
                if (tmp == null) {
                    leftCount++;
                }
                return tmp;
            }
            if (leftCount == 0) {
                leftObj = pair;
                leftCount = 1;
                return null;
            } else {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    leftObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue64<K, V> mmv = new MyMapValue64<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, code, ++index);
                leftObj = mmv;
                leftCount++;
                return mmv.put(pair, code, ++index);
            }

        } else {
            if (rightCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                V tmp = mmv.put(pair, code, ++index);
                if (tmp == null) {
                    rightCount++;
                }
                return tmp;
            }
            if (rightCount == 0) {
                rightObj = pair;
                rightCount = 1;
                return null;
            } else {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    rightObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue64<K, V> mmv = new MyMapValue64<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, code, ++index);
                rightObj = mmv;
                rightCount++;
                return mmv.put(pair, code, ++index);
            }
        }

    }

    private V put(KeyValue64<K, V> pair, long code, int index) {
        if (index >= LAYER_LIMIT) {
            endPut(pair, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
                V tmp = mmv.put(pair, code, ++index);
                if (tmp == null) {
                    leftCount++;
                }
                return tmp;
            }
            if (leftCount == 0) {
                leftObj = pair;
                leftCount = 1;
                return null;
            } else {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
                if (pair.equals(obj)) {
                    V oldValue = obj.value;
                    leftObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue64<K, V> mmv = new MyMapValue64<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(pair, code, ++index);
                leftObj = mmv;
                leftCount++;
                return mmv.put(pair, code, ++index);
            }

        } else {
            if (rightCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
                V tmp = mmv.put(pair, code, ++index);
                if (tmp == null) {
                    rightCount++;
                }
                return tmp;
            }
            if (rightCount == 0) {
                rightObj = pair;
                rightCount = 1;
                return null;
            } else {
                KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
                if (pair.equals(obj)) {
                    V oldValue = obj.value;
                    rightObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue64<K, V> mmv = new MyMapValue64<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, code, ++index);
                rightObj = mmv;
                rightCount++;
                return mmv.put(pair, code, ++index);
            }
        }

    }

    private V endPut(KeyValue64<K, V> pair, long code) {
        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount == 0) {
                List<KeyValue64<K, V>> list = new ArrayList<KeyValue64<K, V>>();
                leftCount++;
                list.add(pair);
                leftObj = list;
                return null;
            } else {
                List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;

                for (int i = 0; i < list.size(); i++) {
                    KeyValue64<K, V> kv = list.get(i);
                    if (kv.equals(pair)) {
                        V oldValue = kv.value;
                        list.set(i, pair);
                        return oldValue;// 已有同key元素，执行更新操作
                    }
                }

                leftCount++;
                list.add(pair);
                return null;
            }
        } else {
            if (rightCount == 0) {
                List<KeyValue64<K, V>> list = new ArrayList<KeyValue64<K, V>>();
                rightCount++;
                list.add(pair);
                rightObj = list;
                return null;
            } else {
                List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
                for (int i = 0; i < list.size(); i++) {
                    KeyValue64<K, V> kv = list.get(i);
                    if (kv.equals(pair)) {
                        V oldValue = kv.value;
                        list.set(i, pair);
                        return oldValue;// 已有同key元素，执行更新操作
                    }
                }
                rightCount++;
                list.add(pair);

                return null;
            }

        }

    }

    public void keyList(List<K> list, int index) {
        if (index >= LAYER_LIMIT) {
            endKeyList(list);
            return;
        }
        if (leftCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
            mmv.keyList(list, ++index);
        } else if (leftCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
            list.add(obj.key);
        }
        if (rightCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
            mmv.keyList(list, ++index);
        } else if (rightCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
            list.add(obj.key);
        }

    }

    private void endKeyList(List<K> result) {
        if (leftCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv.key);
            }
        }
        if (rightCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv.key);
            }
        }
    }

    public void valueList(List<V> list, int index) {
        if (index >= LAYER_LIMIT) {
            endValueList(list);
            return;
        }
        if (leftCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
            mmv.valueList(list, ++index);
        } else if (leftCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
            list.add(obj.value);
        }
        if (rightCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
            mmv.valueList(list, ++index);
        } else if (rightCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
            list.add(obj.value);
        }

    }

    private void endValueList(List<V> result) {
        if (leftCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv.value);
            }
        }
        if (rightCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv.value);
            }
        }
    }

    public void entryList(List<KeyValue64<K, V>> list, int index) {
        if (index >= LAYER_LIMIT) {
            endEntryList(list);
            return;
        }
        if (leftCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) leftObj;
            mmv.entryList(list, ++index);
        } else if (leftCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) leftObj);
            list.add(obj);
        }
        if (rightCount > 1) {
            MyMapValue64<K, V> mmv = (MyMapValue64<K, V>) rightObj;
            mmv.entryList(list, ++index);
        } else if (rightCount == 1) {
            KeyValue64<K, V> obj = ((KeyValue64<K, V>) rightObj);
            list.add(obj);
        }

    }

    private void endEntryList(List<KeyValue64<K, V>> result) {
        if (leftCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) leftObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv);
            }
        }
        if (rightCount != 0) {
            List<KeyValue64<K, V>> list = (List<KeyValue64<K, V>>) rightObj;
            for (KeyValue64<K, V> kv : list) {
                result.add(kv);
            }
        }
    }

}
