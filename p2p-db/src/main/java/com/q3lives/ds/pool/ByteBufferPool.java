package com.q3lives.ds.pool;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * ByteBuffer对象池
 * 
 * 复用ByteBuffer对象，减少GC压力
 */
public class ByteBufferPool {
    
    private final BlockingQueue<ByteBuffer> pool;
    private final int bufferSize;
    private final int maxPoolSize;
    
    public ByteBufferPool(int bufferSize, int maxPoolSize) {
        this.bufferSize = bufferSize;
        this.maxPoolSize = maxPoolSize;
        this.pool = new ArrayBlockingQueue<>(maxPoolSize);
        
        // 预创建一些缓冲区
        for (int i = 0; i < maxPoolSize / 2; i++) {
            pool.offer(ByteBuffer.allocate(bufferSize));
        }
    }
    
    /**
     * 获取ByteBuffer
     */
    public ByteBuffer acquire() {
        ByteBuffer buffer = pool.poll();
        if (buffer == null) {
            buffer = ByteBuffer.allocate(bufferSize);
        } else {
            buffer.clear();
        }
        return buffer;
    }
    
    /**
     * 归还ByteBuffer
     */
    public void release(ByteBuffer buffer) {
        if (buffer != null && buffer.capacity() == bufferSize) {
            buffer.clear();
            pool.offer(buffer);
        }
    }
    
    /**
     * 获取池状态
     */
    public PoolStats getStats() {
        return new PoolStats(pool.size(), maxPoolSize, bufferSize);
    }
    
    /**
     * 池统计
     */
    public static class PoolStats {
        private final int available;
        private final int maxSize;
        private final int bufferSize;
        
        PoolStats(int available, int maxSize, int bufferSize) {
            this.available = available;
            this.maxSize = maxSize;
            this.bufferSize = bufferSize;
        }
        
        public int getAvailable() {
            return available;
        }
        
        public int getMaxSize() {
            return maxSize;
        }
        
        public int getBufferSize() {
            return bufferSize;
        }
        
        @Override
        public String toString() {
            return String.format(
                "PoolStats{available=%d/%d, bufferSize=%d}",
                available, maxSize, bufferSize
            );
        }
    }
}
