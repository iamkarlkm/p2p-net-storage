package com.q3lives.ds.cache;

import com.q3lives.ds.database.adapter.DsTableAdapter;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 序列化缓存管理器
 * 
 * 缓存序列化后的ByteBuffer，避免重复序列化
 */
public class SerializationCache {
    
    private final ConcurrentMap<CacheKey, ByteBuffer> cache;
    private final int maxSize;
    private volatile long hits = 0;
    private volatile long misses = 0;
    
    public SerializationCache(int maxSize) {
        this.cache = new ConcurrentHashMap<>();
        this.maxSize = maxSize;
    }
    
    /**
     * 获取或序列化实体
     */
    public ByteBuffer getOrSerialize(DsTableAdapter entity) {
        CacheKey key = new CacheKey(entity.getClass(), entity.getId());
        
        ByteBuffer cached = cache.get(key);
        if (cached != null) {
            hits++;
            // 返回副本，避免并发修改
            ByteBuffer copy = ByteBuffer.allocate(cached.capacity());
            cached.rewind();
            copy.put(cached);
            copy.flip();
            return copy;
        }
        
        misses++;
        ByteBuffer serialized = entity.toBytes();
        
        // 检查缓存大小
        if (cache.size() < maxSize) {
            // 存储副本到缓存
            ByteBuffer toCache = ByteBuffer.allocate(serialized.capacity());
            serialized.rewind();
            toCache.put(serialized);
            toCache.flip();
            cache.put(key, toCache);
        }
        
        serialized.rewind();
        return serialized;
    }
    
    /**
     * 使缓存失效
     */
    public void invalidate(Class<? extends DsTableAdapter> clazz, Long id) {
        cache.remove(new CacheKey(clazz, id));
    }
    
    /**
     * 清空缓存
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * 获取缓存统计
     */
    public CacheStats getStats() {
        return new CacheStats(hits, misses, cache.size());
    }
    
    /**
     * 缓存键
     */
    private static class CacheKey {
        private final Class<?> clazz;
        private final Long id;
        
        CacheKey(Class<?> clazz, Long id) {
            this.clazz = clazz;
            this.id = id;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return clazz.equals(cacheKey.clazz) && id.equals(cacheKey.id);
        }
        
        @Override
        public int hashCode() {
            return 31 * clazz.hashCode() + id.hashCode();
        }
    }
    
    /**
     * 缓存统计
     */
    public static class CacheStats {
        private final long hits;
        private final long misses;
        private final int size;
        
        CacheStats(long hits, long misses, int size) {
            this.hits = hits;
            this.misses = misses;
            this.size = size;
        }
        
        public long getHits() {
            return hits;
        }
        
        public long getMisses() {
            return misses;
        }
        
        public int getSize() {
            return size;
        }
        
        public double getHitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
        
        @Override
        public String toString() {
            return String.format(
                "CacheStats{hits=%d, misses=%d, size=%d, hitRate=%.2f%%}",
                hits, misses, size, getHitRate() * 100
            );
        }
    }
}
