package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

import javax.annotation.PreDestroy;

//import com.sun.org.apache.bcel.internal.generic.NEWARRAY;

/*
 *
 * Copyright (c) 2010
 * Karl Jinkai
 *
 * Permission to use, copy, modify, distribute and sell this software
 * and its documentation for any purpose is hereby granted without fee,
 * provided that the above copyright notice appear in all copies and
 * that both that copyright notice and this permission notice appear
 * in supporting documentation.  Karl Jinkai makes no representations
 * about the suitability of this software for any purpose.  It is 
 * provided "as is" without express or implied warranty.
 * 
 */

 /*
 * 以上版权申明完全参照惠普STL许可证。本人相当崇拜惠普STL的开发者们，STL里面
 * 有着最惊人的编程效率以及最伟大的编程思想，真让人佩服得五体投地。
 * 本哈希表类以及相关类属于作者智能内核项目的一个组成部分。为了实现计算机智能化，
 * 需要在最快速地存储、检索、匹配和修改超巨量的数据空间，传统的哈希表算法根本不适用，
 * 而且在很多应用场合需要一种按整数方式自然排序的哈希树容器，但可惜数据结构理论和
 * 实践中都没有。因此作者一直在思索构造适用于这一目的的算法和容器。
 * 我相信，人的大脑神经网络中一定存在一种类似的容器，不同的是基于某种多进制分叉运算的，
 * 而不是基于二进制分叉运算的。
 * 此实现版本是基于java语言的，稍后有时间的话，将实现一个c++语言版本的。若有读者花时间
 * 实现了的话，可将之共享出来，请发邮件给我：iamkarl@163.com
 * 
 */
/**
 * @author Karl Jinkai 2010-07-10
 * @version MyHashMap64位版本
 * @Info 一个专为巨量数据的快速存储和检索而设计的分布式哈希表类， 可提供基于不同哈希键值对的近似常数时间的寻位存储和定位检索。
 * 寻位和定位算法基于键对象之哈希值的每一个二进制位的状态所构造 的二叉树。 32位长最多执行32次寻位或定位运算（近似常数时间）。
 * 64位长哈希表最多执行8次寻位或定位运算（近似常数时间）。
 * @TODO 无限多位长哈希表（基于byte[]的扩展哈希值类和接口）：待实现。
 * @Advantage 相对于传统哈希表算法的改进： 1.近似常数时间的寻位或定位算法（相对于非同一的哈希值）,
 * 算法充分利用了哈希值的每一位；算法的存储和检索时间与数据容量大小无关联。<br>
 * 2.整个哈希表是一棵基于哈希值二进制位的二叉排序树；<br>
 * 3.只要键对象的哈希算法足够唯一，就可实现巨量数据的近似常数时间存储和检索。<br>
 *
 * @Required 哈希键类必须重写hashCode(),equals(),toString()方法，<br>
 * 建议实现HashCode64接口，以精细控制64位哈希值的生成细节。<br>
 * hashCode(),toString()方法的定义应该依赖于稳定不变的属性， 并且二者之间应有所不同，因为当哈希等值之时，算法依赖于toString()
 * 的值调用传统哈希表来存储和检索数据。
 */
//public class MyHashMap64<K extends HashCode64, V> implements Map,Serializable {
public class MyHashMap64<K, V> implements Map, Serializable {

    private static final long serialVersionUID = 20100710L;
    int size;
    V valueOfNullKey;
    List<K> keysOfnullValue;
    transient List<K> resultKey;
    transient List<V> resultValue;

    transient List<KeyValue64<K, V>> resultEntry;
    MyMapUnit64<K, V> map = new MyMapUnit64<K, V>();

    public V getValueOfNullKey() {
        return valueOfNullKey;
    }

    public List<K> getKeysOfNullValue() {
        return keysOfnullValue;
    }

    public boolean containsKeyOfNullValue(Object key) {
        if (keysOfnullValue == null) {
            return false;
        }
        return false;

    }

    //自定义序列化操作：
    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
        s.defaultWriteObject();
    }

    private void readObject(java.io.ObjectInputStream s) throws IOException,
            ClassNotFoundException {
        s.defaultReadObject();

    }

    @Override
    public void clear() {
        size = 0;
        valueOfNullKey = null;
        keysOfnullValue = null;
        resultKey = null;
        resultValue = null;
        resultEntry = null;
        map.clear();

    }

    @Override
    public boolean containsKey(Object key) {
        if (key == null) {
            return valueOfNullKey != null ? true : false;
        }
        V v = get(key);
        return v == null ? false : true;
        //return map.containsKey((K) key);
    }

    /**
     * 不建议使用,应用contains(Object value)替代。
     */
    @Deprecated
    @Override
    public boolean containsValue(Object value) {
        if (value == null) {
            return keysOfnullValue != null ? true : false;
        }
        if (resultEntry == null) {
            listEntry();
        }
        for (KeyValue64<K, V> kv : resultEntry) {
            if (kv.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public K contains(Object value) {
        if (value == null) {
            return keysOfnullValue == null ? null : keysOfnullValue.get(0);
        }
        if (resultEntry == null) {
            listEntry();
        }
        for (KeyValue64<K, V> kv : resultEntry) {
            if (kv.value.equals(value)) {
                return kv.key;
            }
        }
        return null;
    }

    /**
     * 不建议使用,应用entryList()替代。
     */
    @Deprecated
    @Override
    public Set entrySet() {
        if (resultEntry == null) {
            listEntry();
        }
        Set<KeyValue64<K, V>> set = new HashSet<KeyValue64<K, V>>(size);
        set.addAll(resultEntry);

        return set;
    }

    public List<KeyValue64<K, V>> listEntry() {
        if (resultEntry != null) {
            return resultEntry;
        }
        resultEntry = new ArrayList<KeyValue64<K, V>>(size);
        map.listEntry(resultEntry);
        //map.entryList(resultEntry,0);
        return resultEntry;

    }

    @Override
    public V get(Object key) {
        if (key == null) {
            return valueOfNullKey;
        }
        return map.get((K) key);
    }

    @Override
    public boolean isEmpty() {

        return size == 0 ? true : false;
    }

    /**
     * 不建议使用,应用listKeys()替代。
     */
    @Deprecated
    @Override
    public Set keySet() {
        if (resultKey == null) {
            listKeys();
        }
        Set<K> set = new HashSet<K>(size);
        set.addAll(resultKey);
        return set;
    }

    public List<K> listKeys() {
        if (resultKey == null) {
            resultKey = new ArrayList<K>(size);
            //map.keyList(resultKey,0);
            map.keyList(resultKey);
        }
        return resultKey;
    }

    public List<K> listUnsignedKeys() {
        ArrayList<K> list = new ArrayList<K>(size);
        map.unsignedKeyList(list);
        return list;
    }

    @Override
    public Object put(Object key, Object value) {
        if (key == null) {
            if (valueOfNullKey != null) {
                if (value == null) {
                    return null;// 双null值，无意义操作，直接返回
                }
                V oldValue = valueOfNullKey;
                valueOfNullKey = (V) value;//更新操作
                return oldValue;
            } else {
                valueOfNullKey = (V) value;
                size++;
                return null;
            }
        } else if (value == null) {//加入空值键列表
            if (keysOfnullValue == null) {
                keysOfnullValue = new ArrayList<K>();
            }
            keysOfnullValue.add((K) key);
            size++;
        }
        V v = map.put((K) key, (V) value);
        if (v == null) {
            size++;
            clearCache();
        }
        return v;
    }

    @Override
    public void putAll(Map m) {
        Set<Entry<K, V>> entry = m.entrySet();

        for (Entry<K, V> e : entry) {
            put(e.getKey(), e.getValue());

        }

    }

    @Override
    public Object remove(Object key) {
        if (key == null) {
            if (valueOfNullKey != null) {
                V oldValue = valueOfNullKey;
                valueOfNullKey = null;
                size--;
                return oldValue;
            }

            return null;

        }
        V v = map.remove((K) key);
        if (v != null) {
            size--;
            clearCache();
        }
        return v;

    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Collection values() {
        if (resultValue == null) {
            listEntry();
            resultValue = new ArrayList<V>(size);
            for (KeyValue64<K, V> kv : resultEntry) {
                resultValue.add(kv.value);
            }
        }

        return resultValue;
    }

    private void clearCache() {
        resultKey = null;

        resultValue = null;

        if (resultEntry != null) {
            resultEntry.clear();
        }

    }

    private void loadCache() {

        if (resultEntry == null) {
            resultEntry = new ArrayList<KeyValue64<K, V>>(size);
            map.listEntry(resultEntry);
        } else if (resultEntry.size() == 0) {
            map.listEntry(resultEntry);
        }
    }
    //以下是扩展功能：

    /**
     * 获得Map容器自然排序的最小元素
     *
     * @return　最小元素
     */
    public KeyValue64<K, V> min() {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultEntry.get(0);
    }

    /**
     * 获得Map容器自然排序的最大元素
     *
     * @return　最大元素
     */
    public KeyValue64<K, V> max() {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultEntry.get(resultEntry.size() - 1);
    }

    /**
     * 获得Map容器自然排序的有序子集
     *
     * @param int fromIndex, int toIndex
     * @return　子集列表
     */
    public List<KeyValue64<K, V>> subList(int begin, int end) {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultEntry.subList(begin, end);
    }

    /**
     * 获得Map容器自然排序的从指定位置到最末尾有序子集
     *
     * @param int fromIndex
     * @return　子集列表
     */
    public List<KeyValue64<K, V>> subList(int begin) {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultEntry.subList(begin, resultEntry.size() - 1);
    }

    /**
     * 获得Map容器指定键的位置顺序号
     *
     * @param int fromIndex
     * @return　子集列表
     */
    public int indexOf(K obj) {
        if (size == 0) {
            return -1;
        }
        loadCache();
        return resultKey.indexOf(obj);
    }

    /**
     * 获得Map容器指定键的位置倒序号
     *
     * @param int fromIndex
     * @return　子集列表
     */
    public int lastIndexOf(K obj) {
        if (size == 0) {
            return -1;
        }

        return resultKey.lastIndexOf(obj);
    }

    /**
     * 获得Map容器内哈希冲突计数
     *
     * @return　冲突计数
     */
    public int getConflictCount() {
        return map.conflictCount;
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) {
        String key = "hjk";
        String value = "karl";
        Random r = new Random();
        long endTime = 0;
        long times = 0;
        long timeStart = 0;
//		timeStart = System.currentTimeMillis();
//		Map<String, String> map2 = new HashMap<String, String>();
//		
//		for(int i=0;i<7;i++){
//			map2.put(key+i, value);
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("HashMap add:"+times);

        timeStart = System.currentTimeMillis();
        MyHashMap64<String, String> map = new MyHashMap64<String, String>();
//		TreeMap<String, String> map = new TreeMap<String, String>();
//		
//		for(int i=0;i<200000;i++){
//			map.put(new TestKey64(key+i), value);
//		}
        //测试一下自然排序
        map.put("中90100909hjk", "中国");
        map.put("办20100909hjk", "中国");
        map.put("国20100909hjk", "中国");
        map.put("啊70100909hjk", "中国");
        map.put("jjk2010", "hjk2010");
        map.put("hhjk2010", "hjk2010");
        map.put("kjk2010", "hjk2010");
        map.put("Z2010hjk", "2010hjk");
        map.put("20100909hjk", "中国");
        map.put("a20100909hjk", "中国");
        System.out.println(map.size());
        String s1 = map.get("2010hjk");
        System.out.println(s1);
        //map.remove("20100909hjk"));
        //System.out.println(map.size);
        s1 = map.get("20100909hjk");
        System.out.println(s1);
        //System.out.println(map.size());
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        //System.out.println(map.size());
        System.out.println("MyHashMap add:" + times);
        timeStart = System.currentTimeMillis();
//		for(Object obj:map.listKeys()){
//			System.out.println(obj);
//		}
//		
//		for(Object obj:map.keySet()){
//			System.out.println(obj);
//		}
        for (Object obj : map.subList(0, 9)) {
            System.out.println(obj);
        }
        Class clazz = String.class;
        System.out.println(key.getClass() == clazz);
        System.out.println(value.getClass() == clazz);
//		List<Integer> li= new ArrayList<Integer>();
//		
//		for(int i=0;i<750000;i++){
//			li.add(r.nextInt());
//		}
//		//System.out.println(map.size());
//		Collections.sort(li);
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        //System.out.println(map.size());
        System.out.println("list add:" + times);
        System.out.println("min:" + map.min());
        System.out.println("max:" + map.max());
        //System.out.println("哈希冲突计数："+map.getConflictCount());

//		List<String> list = map.keyList();
//		timeStart = System.currentTimeMillis();
//		
//		
//		for(String s:list){
//			String val = map.get(s);
//			if(val==null){
//				System.out.println("TODO:debug... "+s+":");
//				//val = map.get(kv.key);
//				//System.out.println(val);
//			}
//		}
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        System.out.println("MyHashMap get:" + times);
//		long endTime = System.currentTimeMillis();
//		long times = endTime - timeStart;
//		//System.out.println(map.size());
//		//System.out.println("MyHashMap find:"+times);

//		//List<KeyValue<String, String>> list = map.entryList();
//		timeStart = System.currentTimeMillis();
//		Set<String> keys2 = map2.keySet();
//		for(String s:keys2){
//			String val = map2.get(s);
//			if(val==null){
//				System.out.println("TODO:debug...");
//			}
//		}
//		endTime = System.currentTimeMillis();
//		
//		//endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		
////		System.out.println(map2.size());
//		System.out.println("HashMap find:"+times);
//		
//		timeStart = System.currentTimeMillis();
//		MyHashMap<Integer, String> map3 = new MyHashMap<Integer, String>();
//		
//		for(int i=0;i<400000;i++){
//			map3.put(i, value);
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("MyHashMap3 add:"+times);
//		timeStart = System.currentTimeMillis();
//		//List<KeyValue<String, String>> list = map.entryList();
//		List<String> list3 = map.listKeys();
//		
//		
//		for(String s:list3){
//			String val = map.get(s);
//			if(val==null){
//				System.out.println("TODO:debug... "+s+":");
//				//val = map.get(kv.key);
//				//System.out.println(val);
//			}
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("MyHashMap3 find:"+times);
//		System.out.println(map3.size());
//		
//		timeStart = System.currentTimeMillis();
//		Set<Integer> keys3 = map2.keySet();
//		for(Integer s:keys2){
//			String val = map2.get(s);
//			if(val==null){
//				System.out.println("TODO:debug...");
//			}
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("HashMap find2:"+times);
//		
//		timeStart = System.currentTimeMillis();
//		//List<KeyValue<String, String>> list = map.entryList();
//		List<Integer> list5 = map3.keyList();
//		
//		
//		for(Integer s:list3){
//			String val = map3.get(s);
//			if(val==null){
//				System.out.println("TODO:debug... "+s+":");
//				//val = map.get(kv.key);
//				//System.out.println(val);
//			}
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("MyHashMap3 find2:"+times);
//		
//		timeStart = System.currentTimeMillis();
//		Set<Entry<Integer, String>> set = map2.entrySet();
//		for(Entry<Integer, String> e:set){
//			Integer i = e.getKey();
//			//System.out.println(i);
//		}
//
//		
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("HashMap entrySet():"+times);
        //System.out.println(map.size());
//		timeStart = System.currentTimeMillis();
//		List<Integer> list2 = map.keyList();
//		//System.out.println(list.size());
////		
////		
//		for(Integer s:list){
//			String val = map.get(s);
//			if(val==null){
//				System.out.println("TODO:debug... "+s+":");
//				//val = map.get(kv.key);
//				//System.out.println(val);
//			}
//		}
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("MyHashMap find:"+times);
//		timeStart = System.currentTimeMillis();
//		List<KeyValue<Integer, String>> list3 = map.entryList();
//		
//		for(KeyValue<Integer, String> kv:list3) {
//			Integer i = kv.key;
//			//System.out.println(i);
//		}
//		
//		endTime = System.currentTimeMillis();
//		times = endTime - timeStart;
//		System.out.println("MyHashMap map.entryList():"+times);
//		//System.out.println(list==list2);
    }
}
