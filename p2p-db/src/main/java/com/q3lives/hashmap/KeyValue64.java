package com.q3lives.hashmap;

import java.io.Serializable;
import java.util.Map.Entry;

/**
 *
 * @author KARL Jinkai 2010-07-10
 * @Info MyHashMap64位版本使用的存储键值对类
 * @param <K>　键对象
 * @param <V>　值对象
 */
//public class KeyValue64<K extends HashCode64, V> implements Entry<K, V>,Serializable{
public class KeyValue64<K, V> implements Entry<K, V>, Serializable {

    private static final long serialVersionUID = 20100710L;
    public K key;
    public V value;

    public KeyValue64(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public void setKey(K key) {
        this.key = key;
    }

    @Override
    public K getKey() {

        return key;
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
        if (key == null) {
            return 0;
        }
        return key.hashCode();
    }

    public long hashCode64() {
        if (key == null) {
            return 0;
        }
        return MyMapUnit64.hashCode64(key);
    }

    @Override
    public String toString() {
        return "[" + key.toString() + ":" + value.toString() + "]";
    }

}
