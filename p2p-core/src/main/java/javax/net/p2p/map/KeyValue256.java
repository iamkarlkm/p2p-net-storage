package javax.net.p2p.map;

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
public class KeyValue256<K, V> implements Entry<K, V>, Serializable {

    private static final long serialVersionUID = 20100710L;
    public K key;
    public V value;

    public KeyValue256(K key, V value) {
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
        if (obj instanceof KeyValue256) {
            KeyValue256<K, V> kv = (KeyValue256<K, V>) obj;
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

    public byte[] hashCode256() {
        return HashMapUnit256.hashCode256(key);
    }

    @Override
    public String toString() {
        return "[" + key.toString() + ":" + value.toString() + "]";
    }

}
