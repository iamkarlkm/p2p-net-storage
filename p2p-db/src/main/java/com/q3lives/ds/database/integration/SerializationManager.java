

package com.q3lives.ds.database.integration;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.cache.SerializationCache;
import com.q3lives.ds.pool.ByteBufferPool;
import com.q3lives.ds.validator.EntityValidator;

import java.nio.ByteBuffer;

/**
 * 集成管理器
 * 
 * 整合缓存、对象池、验证等功能
 */
public class SerializationManager {
    
    private final SerializationCache cache;
    private final ByteBufferPool pool;
    private final boolean enableValidation;
    
    public SerializationManager(
            int cacheSize, 
            int poolSize, 
            int bufferSize,
            boolean enableValidation) {
        
        this.cache = new SerializationCache(cacheSize);
        this.pool = new ByteBufferPool(bufferSize, poolSize);
        this.enableValidation = enableValidation;
    }
    
    /**
     * 序列化实体
     */
    public ByteBuffer serialize(DsTableAdapter entity) {
        // 验证
        if (enableValidation) {
            EntityValidator.ValidationResult result = EntityValidator.validate(entity);
            if (!result.isValid()) {
                throw new IllegalArgumentException("实体验证失败: " + result);
            }
        }
        
        // 使用缓存
        return cache.getOrSerialize(entity);
    }
    
    /**
     * 反序列化实体
     */
    public void deserialize(ByteBuffer data, DsTableAdapter entity) {
        entity.load(data);
        
        // 验证
        if (enableValidation) {
            EntityValidator.ValidationResult result = EntityValidator.validate(entity);
            if (!result.isValid()) {
                throw new IllegalArgumentException("反序列化后验证失败: " + result);
            }
        }
    }
    
    /**
     * 获取ByteBuffer
     */
    public ByteBuffer acquireBuffer() {
        return pool.acquire();
    }
    
    /**
     * 归还ByteBuffer
     */
    public void releaseBuffer(ByteBuffer buffer) {
        pool.release(buffer);
    }
    
    /**
     * 使缓存失效
     */
    public void invalidateCache(Class<? extends DsTableAdapter> clazz, Long id) {
        cache.invalidate(clazz, id);
    }
    
    /**
     * 清空缓存
     */
    public void clearCache() {
        cache.clear();
    }
    
    /**
     * 获取统计信息
     */
    public String getStats() {
        return "SerializationManager Stats:" +
               "  Cache: " + cache.getStats() + "" +
               "  Pool: " + pool.getStats();
    }
}
