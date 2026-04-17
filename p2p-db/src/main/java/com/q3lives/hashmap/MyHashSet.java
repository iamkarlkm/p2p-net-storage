package com.q3lives.hashmap;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.PreDestroy;

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
/**
 * @author Karl Jinkai 2010-07-18
 * @info 类似于MyHashMap的Set容器
 *
 * @Advantage 按照元素的hashCode自然排序的Set容器
 */
public class MyHashSet<K> implements Set, Serializable {

    private static final long serialVersionUID = 20100718L;
    private int size;
    private transient List<K> resultKey;

    private MySetUnit<K> set = new MySetUnit<K>();

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

        resultKey = null;

        set.clear();

    }

    @Override
    public boolean isEmpty() {

        return size == 0 ? true : false;
    }

    public List<K> list() {
        if (resultKey == null) {
            resultKey = new ArrayList<K>(size);
            //map.keyList(resultKey,0);
            set.keyList(resultKey);
        } else if (resultKey.size() == 0) {
            set.keyList(resultKey);
        }
        return resultKey;
    }

    public List<K> listByUnsigned() {
        ArrayList<K> list = new ArrayList<K>(size);

        set.listByUnsigned(list);
        return list;
    }

    private void clearCache() {
        if (resultKey != null) {
            resultKey.clear();
        }

    }

    private void loadCache() {

        if (resultKey == null) {
            resultKey = new ArrayList<K>(size);
            //map.keyList(resultKey,0);
            set.keyList(resultKey);
        } else if (resultKey.size() == 0) {
            set.keyList(resultKey);
        }
    }

    @Override
    public int size() {
        return size;
    }

    public K min() {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultKey.get(0);
    }

    public K max() {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultKey.get(resultKey.size() - 1);
    }

    public List<K> subList(int begin, int end) {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultKey.subList(begin, end);
    }

    public List<K> subList(int begin) {
        if (size == 0) {
            return null;
        }
        loadCache();
        return resultKey.subList(begin, resultKey.size() - 1);
    }

    public int indexOf(K obj) {
        if (size == 0) {
            return -1;
        }
        loadCache();
        return resultKey.indexOf(obj);
    }

    public int lastIndexOf(K obj) {
        if (size == 0) {
            return -1;
        }

        return resultKey.lastIndexOf(obj);
    }

    public int getConflictCount() {
        return set.conflictCount;
    }

    @Override
    public boolean add(Object key) {
        if (key == null) {
            return false;
        }
        if (set.put((K) key)) {
            size++;
            clearCache();
            return true;
        }
        return false;
    }

    @Override
    public boolean addAll(Collection c) {
        Iterator<K> it = c.iterator();
        boolean flag = false;
        while (it.hasNext()) {
            Object obj = it.next();
            if (set.put((K) obj)) {
                size++;
                clearCache();
                flag = true;
            }

        }
        return flag;
    }

    @Override
    public boolean containsAll(Collection c) {
        Iterator<K> it = c.iterator();
        while (it.hasNext()) {
            Object obj = it.next();
            if (!contains(obj)) {
                return false;
            }

        }
        return true;
    }

    class MyHashSetIterator implements Iterator<K> {

        List<K> list;
        int end;
        int current;

        MyHashSetIterator(List<K> list) {
            this.list = list;
            end = list.size();
            current = 0;
        }

        @Override
        public boolean hasNext() {
            return current < end;
        }

        @Override
        public K next() {
            return list.get(current++);
        }

        @Override
        public void remove() {
            set.remove(list.get(current - 1));
            size--;
            clearCache();
        }

    }

    @Override
    public Iterator<K> iterator() {
        if (resultKey == null) {
            resultKey = new ArrayList<K>();
            set.keyList(resultKey);
        }
        return resultKey.iterator();
        //return new MyHashSetIterator(resultKey);		
    }

    @Override
    public boolean removeAll(Collection c) {

        Iterator<K> it = c.iterator();
        int count = 0;
        while (it.hasNext()) {
            Object obj = it.next();
            if (remove(obj)) {
                count++;
            }

        }
        return count != 0;

    }

    @Override
    public boolean retainAll(Collection c) {
        //clear();
        MySetUnit<K> set2 = new MySetUnit<K>();
        Iterator<K> it = c.iterator();
        int count = 0;
        while (it.hasNext()) {
            Object obj = it.next();
            if (contains(obj)) {
                set2.put((K) obj);
                count++;
            }

        }
        if (size == count) {
            return false;
        } else {
            size = count;
            clearCache();
            set = set2;
            return true;
        }

    }

    @Override
    public K[] toArray() {
        loadCache();
        return (K[]) resultKey.toArray();
    }

    @Override
    public K[] toArray(Object[] a) {
        loadCache();
        return (K[]) resultKey.toArray(a);
    }

    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        return set.get(o);
    }

    @Override
    public boolean remove(Object o) {
        if (o == null) {
            return false;
        }

        if (set.remove(o)) {
            size--;
            clearCache();
            return true;
        }
        return false;
    }

    public static void main(String[] args) {
        MyHashSet<Integer> set = new MyHashSet<Integer>();
        //测试一下自然排序
        set.add(8);
        set.add(2);
        set.add(1);
        set.add(109);
        set.add(32);
        set.add(87);
        set.add(0x8000);
        set.add(999);
        set.add(82);
        for (Integer s : set.list()) {
            System.out.println(s);
        }
    }
}
