package com.q3lives.ds.cache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小实现的 LRU 内存缓存（按访问顺序淘汰）。
 *
 * <p>实现方式：</p>
 * <ul>
 *   <li>基于 {@link LinkedHashMap} 的 accessOrder=true。</li>
 *   <li>在 {@link LinkedHashMap#removeEldestEntry(Map.Entry)} 中按 maxEntries 做硬上限淘汰。</li>
 * </ul>
 *
 * <p>并发：</p>
 * <ul>
 *   <li>通过 synchronized 方法保证线程安全；适用于小规模缓存与低争用场景。</li>
 * </ul>
 */
final class LruCache<K, V> {

    private final int maxEntries;
    private final LinkedHashMap<K, V> map;

    LruCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.maxEntries = maxEntries;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > LruCache.this.maxEntries;
            }
        };
    }

    synchronized V get(K key) {
        return map.get(key);
    }

    synchronized void put(K key, V value) {
        map.put(key, value);
    }

    synchronized V remove(K key) {
        return map.remove(key);
    }

    synchronized void clear() {
        map.clear();
    }
}
