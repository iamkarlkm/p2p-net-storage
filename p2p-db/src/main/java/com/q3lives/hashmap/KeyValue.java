package com.q3lives.hashmap;

import java.io.Serializable;
import java.util.Map.Entry;

/**
 *
 * @author KARL Jinkai 2010-07-10
 * @Info MyHashMap使用的存储键值对类
 * @param <K>　键对象
 * @param <V>　值对象
 */
public class KeyValue<K, V> implements Entry<K, V>, Serializable {

    private static final long serialVersionUID = 20100710L;
    public K key;
    public V value;
    //transient int hash;

    public KeyValue() {

    }

    public KeyValue(K key, V value) {
        this.key = key;
        this.value = value;
        //hash = getCode(key);
    }

    public void setKey(K key) {
        this.key = key;
    }

    @Override
    public K getKey() {
        return key;
    }

    public static int getCode(Object key) {
        int hash = 0;
        if (key instanceof String) {//按首字符简单排序
            int code1 = (key.toString().charAt(0) << 16) & 0xffff0000;
            int code2 = key.hashCode() & 0xffff;
            hash = code1 | code2;
        } else {
            hash = key.hashCode();
        }
        return hash;
    }

    @Override
    public V getValue() {
        return value;
    }

    @Override
    public V setValue(V value) {
        V oldValue = this.value;
        this.value = value;
        return oldValue;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof KeyValue) {
            KeyValue<K, V> kv = (KeyValue<K, V>) obj;
            return key.equals(kv.key);
        }
        return false;

    }

    @Override
    public int hashCode() {
        return (key == null) ? 0 : key.hashCode();
        //return hash;
    }

    @Override
    public String toString() {
        return "{" + key.toString() + ":" + value + "}";
    }

}
