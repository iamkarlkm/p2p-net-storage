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
public final class MyMapValue<K, V> implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final int[] integer = new int[32];

    static {
        int pos = 31;
        for (int i = 0; i < 32; i++) {
            integer[i] = 1 << pos;
            // System.out.println(integer32[i]);
            pos--;
        }

    }
    private static final int LAYER_LIMIT = 31;

    private int leftCount = 0;
    private int rightCount = 0;
    private Object leftObj;
    private Object rightObj;

    public int getLeftCount() {
        return leftCount;
    }

    public int getRightCount() {
        return rightCount;
    }

    //自定义序列化操作：
    private void writeObject(java.io.ObjectOutputStream s)
            throws IOException {
        s.writeInt(leftCount);
        s.writeInt(rightCount);
        s.writeObject(leftObj);
        s.writeObject(rightObj);
    }

    private void readObject(java.io.ObjectInputStream s)
            throws IOException, ClassNotFoundException {
        leftCount = s.readInt();
        rightCount = s.readInt();
        leftObj = s.readObject();
        rightObj = s.readObject();

    }

    public MyMapValue() {
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
        int code = key.hashCode();
        int index = 0;

        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                V oldValue = mmv.remove(key, code, index + 1);
                if (oldValue != null) {
                    leftCount--;
                }
                return oldValue;
            } else if (leftCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
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
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                V oldValue = mmv.remove(key, code, index + 1);
                if (oldValue != null) {
                    rightCount--;
                }
                return oldValue;
            } else if (rightCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
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

    private V remove(K key, int code, int index) {
        if (index >= LAYER_LIMIT) {
            return endRemove(key, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                V oldValue = mmv.remove(key, code, index + 1);
                if (oldValue != null) {
                    leftCount--;
                }
                return oldValue;
            } else if (leftCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
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
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                V oldValue = mmv.remove(key, code, index + 1);
                if (oldValue != null) {
                    rightCount--;
                }
                return oldValue;
            } else if (rightCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
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

    private V endRemove(K key, int code) {
        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount != 0) {
                List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) leftObj;
                for (int i = 0; i < list.size(); i++) {
                    KeyValue<K, V> kv = list.get(i);
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
            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
            for (int i = 0; i < list.size(); i++) {
                KeyValue<K, V> kv = list.get(i);
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

    V get(Object key) {
        int code = key.hashCode();
        int index = 0;

        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                return mmv.get(key, code, index + 1);
            } else if (leftCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                return mmv.get(key, code, index + 1);
            } else if (rightCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        }

    }

    private V get(Object key, int code, int index) {
        if (index >= LAYER_LIMIT) {
            return endGet(key, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                return mmv.get(key, code, index + 1);
            } else if (leftCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        } else {
            if (rightCount > 1) {
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                return mmv.get(key, code, index + 1);
            } else if (rightCount == 1) {
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    return obj.value;
                }
            }
            return null;
        }

    }

    private V endGet(Object key, int code) {

        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount != 0) {
                List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) leftObj;
                //List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
                for (KeyValue<K, V> kv : list) {

                    if (key.equals(kv.key)) {

                        return kv.value;
                    }
                }
            }
            return null;
        } else if (rightCount != 0) {

            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
            for (KeyValue<K, V> kv : list) {

                if (key.equals(kv.key)) {

                    return kv.value;
                }
            }
        }

        return null;

    }

    public V put(K key, V value) {
        int code = key.hashCode();
        int index = 0;
        KeyValue<K, V> pair = new KeyValue<K, V>(key, value);
        if ((code & integer[index]) != 0) {//负数进入左分支
            if (leftCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                V tmp = mmv.put(pair, code, index + 1);
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
                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    leftObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue<K, V> mmv = new MyMapValue<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, (obj.key).hashCode(), index + 1);
                leftObj = mmv;
                leftCount++;
                return mmv.put(pair, code, index + 1);
            }

        } else {
            if (rightCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                V tmp = mmv.put(pair, code, index + 1);
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
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
                if (key.equals(obj.key)) {
                    V oldValue = obj.value;
                    rightObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue<K, V> mmv = new MyMapValue<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, (obj.key).hashCode(), index + 1);
                rightObj = mmv;
                rightCount++;
                return mmv.put(pair, code, index + 1);
            }
        }

    }

    private V put(KeyValue<K, V> pair, int code, int index) {

        if (index >= LAYER_LIMIT) {

            return endPut(pair, code);
        }
        if ((code & integer[index]) == 0) {
            if (leftCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
                V tmp = mmv.put(pair, code, index + 1);
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

                KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
                if (pair.equals(obj)) {
                    V oldValue = obj.value;
                    leftObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue<K, V> mmv = new MyMapValue<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, (obj.key).hashCode(), index + 1);
                leftObj = mmv;
                leftCount++;
                return mmv.put(pair, code, index + 1);
            }

        } else {
            if (rightCount > 1) {
                // 递归寻找位置以存储值对象
                MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
                V tmp = mmv.put(pair, code, index + 1);
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
                KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
                if (pair.equals(obj)) {
                    V oldValue = obj.value;
                    rightObj = pair;
                    return oldValue;// 已有同key元素，执行更新操作
                }
                MyMapValue<K, V> mmv = new MyMapValue<K, V>();
                // 递归寻找位置以存储值对象
                mmv.put(obj, (obj.key).hashCode(), index + 1);
                rightObj = mmv;
                rightCount++;
                return mmv.put(pair, code, index + 1);
            }

        }

    }

    private V endPut(KeyValue<K, V> pair, int code) {

        if ((code & integer[LAYER_LIMIT]) == 0) {
            if (leftCount == 0) {
                List<KeyValue<K, V>> list = new ArrayList<KeyValue<K, V>>();
                leftCount++;
                list.add(pair);
                leftObj = list;

                return null;
            } else {
                List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) leftObj;

                for (int i = 0; i < list.size(); i++) {
                    KeyValue<K, V> kv = list.get(i);
                    if (kv.equals(pair)) {
                        V oldValue = kv.value;
                        list.set(i, pair);
                        return oldValue;// 已有同key元素，执行更新操作
                    }
                }

                leftCount++;
                list.add(pair);
                //System.out.println("left2:"+leftCount);
                return null;
            }
        } else {
            if (rightCount == 0) {
                List<KeyValue<K, V>> list = new ArrayList<KeyValue<K, V>>();
                rightCount++;
                list.add(pair);
                rightObj = list;
                return null;
            } else {
                List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
                for (int i = 0; i < list.size(); i++) {
                    KeyValue<K, V> kv = list.get(i);
                    if (kv.equals(pair)) {
                        V oldValue = kv.value;
                        list.set(i, pair);
                        return oldValue;// 已有同key元素，执行更新操作
                    }
                }
                rightCount++;
                list.add(pair);
                //System.out.println("rihgt2:"+rightCount);
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
            MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
            mmv.keyList(list, index + 1);
        } else if (leftCount == 1) {
            KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
            list.add(obj.key);
        }
        if (rightCount > 1) {
            MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
            mmv.keyList(list, index + 1);
        } else if (rightCount == 1) {
            KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
            list.add(obj.key);
        }

    }

    private void endKeyList(List<K> result) {
        //System.out.println(i);
        if (leftCount != 0) {
            //System.out.println("left:"+leftCount);
            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) leftObj;
            for (KeyValue<K, V> kv : list) {
                result.add(kv.key);
            }
        }
        if (rightCount != 0) {
            //System.out.println("right:"+rightCount);
            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
            for (KeyValue<K, V> kv : list) {
                result.add(kv.key);
            }
        }
    }

    public void entryList(List<KeyValue<K, V>> list, int index) {
        if (index >= LAYER_LIMIT) {
            endEntryList(list);
            return;
        }
        if (leftCount > 1) {
            MyMapValue<K, V> mmv = (MyMapValue<K, V>) leftObj;
            mmv.entryList(list, index + 1);
        } else if (leftCount == 1) {
            KeyValue<K, V> obj = ((KeyValue<K, V>) leftObj);
            list.add(obj);
        }
        if (rightCount > 1) {
            MyMapValue<K, V> mmv = (MyMapValue<K, V>) rightObj;
            mmv.entryList(list, index + 1);
        } else if (rightCount == 1) {
            KeyValue<K, V> obj = ((KeyValue<K, V>) rightObj);
            list.add(obj);
        }

    }

    private void endEntryList(List<KeyValue<K, V>> result) {
        if (leftCount != 0) {
            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) leftObj;
            for (KeyValue<K, V> kv : list) {
                result.add(kv);
            }
        }
        if (rightCount != 0) {
            List<KeyValue<K, V>> list = (List<KeyValue<K, V>>) rightObj;
            for (KeyValue<K, V> kv : list) {
                result.add(kv);
            }
        }
    }

}
