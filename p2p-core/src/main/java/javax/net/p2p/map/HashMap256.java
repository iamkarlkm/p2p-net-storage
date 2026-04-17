package javax.net.p2p.map;

import java.io.IOException;
import java.io.Serializable;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * @author Karl Jinkai 2010-07-10
 * @param <K>
 * @param <V>
 * @info 一个专为大规模数据的快速存储和检索而设计的分布式哈希表类， 可提供基于32字节长哈希键值对的近似常数时间的寻位存储和定位检索。
 * 寻位和定位算法基于键对象之哈希值的每8个二进制位的状态所构造的多叉（256）分层（最多32层）树。 <br>
 *
 */
public class HashMap256<K, V> implements Map, Serializable {

    private static final long serialVersionUID = 20100710L;
    private int size;

    private final HashMapUnit256<K, V> map = new HashMapUnit256<>();

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

        map.clear();

    }

    @Override
    public boolean containsKey(Object key) {
        if (key == null) {
            return false;
        }
        V v = get(key);
        return v == null ? false : true;
    }

    @Override
    public boolean containsValue(Object value) {

        for (KeyValue256<K, V> kv : this.listEntry()) {
            if ((value == null ? kv.value == null : value.equals(kv.value))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Set entrySet() {
        Set<KeyValue256<K, V>> set = new HashSet<>(size);
        set.addAll(this.listEntry());
        return set;
    }

    public List<KeyValue256<K, V>> listEntry() {
        ArrayList<KeyValue256<K, V>> resultEntry = new ArrayList<KeyValue256<K, V>>(size);
        map.listEntry(resultEntry);
        //map.entryList(resultEntry,0);
        return resultEntry;

    }

    @Override
    public V get(Object key) {
        if (key == null) {
            return null;
        }
        return map.get((K) key);
    }

    @Override
    public boolean isEmpty() {

        return size == 0 ? true : false;
    }

    @Override
    public Set keySet() {
        Set<K> set = new HashSet<K>(size);
        set.addAll(this.listKeys());
        return set;
    }

    public List<K> listKeys() {
        ArrayList<K> resultKey = new ArrayList<K>(size);
        map.keyList(resultKey);
        return resultKey;
    }

    @Override
    public Object put(Object key, Object value) {
        if (key == null) {
            return null;
        }
        V v = map.put((K) key, (V) value);
        if (v == null) {
            size++;
        }
        return v;
    }

    @Override
    public void putAll(Map m) {
        Set<Map.Entry<K, V>> entry = m.entrySet();

        for (Map.Entry<K, V> e : entry) {
            put(e.getKey(), e.getValue());

        }

    }

    @Override
    public Object remove(Object key) {
        if (key == null) {
            return null;
        }
        V v = map.remove((K) key);
        if (v != null) {
            size--;
        }
        return v;

    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Collection values() {
        ArrayList<V> resultValue = new ArrayList<V>(size);
        for (KeyValue256<K, V> kv : listEntry()) {
            resultValue.add(kv.value);
        }
        return resultValue;
    }

    //以下是扩展功能：
//    /**
//     * 获得Map容器自然排序的最小元素
//     *
//     * @return　最小元素
//     */
//    public KeyValue256<K, V> min() {
//        if (size == 0) {
//            return null;
//        }
//        loadCache();
//        return resultEntry.get(0);
//    }
//
//    /**
//     * 获得Map容器自然排序的最大元素
//     *
//     * @return　最大元素
//     */
//    public KeyValue256<K, V> max() {
//        if (size == 0) {
//            return null;
//        }
//        loadCache();
//        return resultEntry.get(resultEntry.size() - 1);
//    }
//
//    /**
//     * 获得Map容器自然排序的有序子集
//     *
//     * @param int fromIndex, int toIndex
//     * @return　子集列表
//     */
//    public List<KeyValue256<K, V>> subList(int begin, int end) {
//        if (size == 0) {
//            return null;
//        }
//        loadCache();
//        return resultEntry.subList(begin, end);
//    }
//
//    /**
//     * 获得Map容器自然排序的从指定位置到最末尾有序子集
//     *
//     * @param int fromIndex
//     * @return　子集列表
//     */
//    public List<KeyValue256<K, V>> subList(int begin) {
//        if (size == 0) {
//            return null;
//        }
//        loadCache();
//        return resultEntry.subList(begin, resultEntry.size() - 1);
//    }
//
//    /**
//     * 获得Map容器指定键的位置顺序号
//     *
//     * @param int fromIndex
//     * @return　子集列表
//     */
//    public int indexOf(K obj) {
//        if (size == 0) {
//            return -1;
//        }
//        loadCache();
//        return resultKey.indexOf(obj);
//    }
//
//    /**
//     * 获得Map容器指定键的位置倒序号
//     *
//     * @param int fromIndex
//     * @return　子集列表
//     */
//    public int lastIndexOf(K obj) {
//        if (size == 0) {
//            return -1;
//        }
//
//        return resultKey.lastIndexOf(obj);
//    }
    /**
     * 获得Map容器内哈希冲突计数
     *
     * @return　冲突计数
     */
    public int getConflictCount() {
        return map.conflictCount;
    }

    public static void main(String[] args) {
        String key = "hjk";
        String value = "lyf";
        Random r = new Random();
        long endTime = 0;
        long times = 0;
        long timeStart = 0;
        timeStart = System.currentTimeMillis();
        Map<String, String> map2 = new HashMap<String, String>();

        for (int i = 0; i < 7000000; i++) {
            map2.put(key + i, value);
        }
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        System.out.println("HashMap add:" + times);
        //Display the amount of free memory in the Java Virtual Machine.

        timeStart = System.currentTimeMillis();
        map2.put("2010hjk", "2010lyf");
        map2.get("2010hjk");
        map2.remove("2010hjk");
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        System.out.println("HashMap get:" + times);
        long freeMem = Runtime.getRuntime().maxMemory();
        DecimalFormat df = new DecimalFormat("0.00");
        System.out.println(df.format(freeMem) + " MB");
        timeStart = System.currentTimeMillis();
        HashMap256<String, String> map = new HashMap256<String, String>();

        for (int i = 0; i < 7000000; i++) {
            map.put(key + i, value);
        }

        //System.out.println(map.size());
        endTime = System.currentTimeMillis();
        times = endTime - timeStart;
        //System.out.println(map.size());
        System.out.println("MyHashMap add:" + times);
        long freeMemEnd = Runtime.getRuntime().maxMemory();
        df = new DecimalFormat("0.00");
        System.out.println(df.format(freeMem - freeMemEnd) + " MB");
//
//        map.put("hjk2010", "lyf2010");
//        map.put("2010hjk", "2010lyf");
//        map.put("中国hjk", "中国");
//        String s1 = map.get("2010hjk");
//        System.out.println("size=" + map.size);
//        System.out.println("2010hjk=" + s1);
//        map.remove("中国hjk");
//        s1 = map.get("中国hjk");
//        System.out.println(s1);
//
//        timeStart = System.currentTimeMillis();
////		List<Integer> li= new ArrayList<Integer>();
////		
////		for(int i=0;i<750000;i++){
////			li.add(r.nextInt());
////		}
////		//System.out.println(map.size());
////		Collections.sort(li);
//        endTime = System.currentTimeMillis();
//        times = endTime - timeStart;
//        //System.out.println(map.size());
//        System.out.println("list add:" + times);
//        System.out.println("哈希冲突计数：" + map.getConflictCount());

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
//
//        timeStart = System.currentTimeMillis();
//        map.put("2010hjk", "2010lyf");
//        map.get("2010hjk");
//        map.remove("2010hjk");
//        endTime = System.currentTimeMillis();
//        times = endTime - timeStart;
//        System.out.println("MyHashMap get:" + times);
//		long endTime = System.currentTimeMillis();
//		long times = endTime - timeStart;
//		//System.out.println(map.size());
//		//System.out.println("MyHashMap find:"+times);
//		//List<KeyValue256<String, String>> list = map.entryList();
        Set<String> keys2 = map2.keySet();
        timeStart = System.nanoTime();
        for (String s : keys2) {
            map2.get(s);
        }
        endTime = System.nanoTime();

        //endTime = System.currentTimeMillis();
        times = endTime - timeStart;

//		System.out.println(map2.size())
        System.out.println("HashMap find:" + (double) times / 7000000);

        List<String> keys = map.listKeys();
        timeStart = System.nanoTime();

        for (String s : keys) {
            map.get(s);
        }
        endTime = System.nanoTime();

        //endTime = System.currentTimeMillis();
        times = endTime - timeStart;

//		System.out.println(map2.size());
        System.out.println("MyHashMap find:" + (double) times / 7000000);

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
//		//List<KeyValue256<String, String>> list = map.entryList();
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
//		//List<KeyValue256<String, String>> list = map.entryList();
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
//		List<KeyValue256<Integer, String>> list3 = map.entryList();
//		
//		for(KeyValue256<Integer, String> kv:list3) {
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
