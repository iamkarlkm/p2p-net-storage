package com.q3lives.ds.util;

import com.q3lives.ds.database.adapter.DsTableAdapter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量序列化工具
 */
public class BatchSerializer {
    
    /**
     * 批量序列化实体列表
     */
    public static <T extends DsTableAdapter> ByteBuffer serializeBatch(List<T> entities) {
        if (entities == null || entities.isEmpty()) {
            return ByteBuffer.allocate(4); // 只包含数量字段
        }
        
        // 计算总大小
        int totalSize = 4; // 实体数量（4字节）
        List<ByteBuffer> buffers = new ArrayList<>();
        
        for (T entity : entities) {
            ByteBuffer buffer = entity.toBytes();
            buffers.add(buffer);
            totalSize += 4 + buffer.limit(); // 长度前缀（4字节）+ 实体数据
        }
        
        // 创建批量缓冲区
        ByteBuffer batchBuffer = ByteBuffer.allocate(totalSize);
        
        // 写入实体数量
        batchBuffer.putInt(entities.size());
        
        // 写入每个实体
        for (ByteBuffer buffer : buffers) {
            batchBuffer.putInt(buffer.limit()); // 写入长度
            buffer.rewind();
            batchBuffer.put(buffer); // 写入数据
        }
        
        batchBuffer.flip();
        return batchBuffer;
    }
    
    /**
     * 批量反序列化实体列表
     */
    public static <T extends DsTableAdapter> List<T> deserializeBatch(
            ByteBuffer batchBuffer, 
            Class<T> entityClass) throws Exception {
        
        batchBuffer.rewind();
        
        // 读取实体数量
        int count = batchBuffer.getInt();
        List<T> entities = new ArrayList<>(count);
        
        // 读取每个实体
        for (int i = 0; i < count; i++) {
            int length = batchBuffer.getInt();
            
            // 创建实体数据缓冲区
            byte[] entityData = new byte[length];
            batchBuffer.get(entityData);
            ByteBuffer entityBuffer = ByteBuffer.wrap(entityData);
            
            // 创建实体并反序列化
            T entity = entityClass.getDeclaredConstructor().newInstance();
            entity.load(entityBuffer);
            entities.add(entity);
        }
        
        return entities;
    }
    
   
}
