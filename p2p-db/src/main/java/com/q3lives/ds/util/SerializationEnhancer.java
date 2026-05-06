package com.q3lives.ds.util;

import com.q3lives.ds.database.adapter.DsTableAdapter;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 序列化增强工具
 * 
 * 提供压缩、校验和等功能
 */
public class SerializationEnhancer {
    
    /**
     * 带压缩的序列化
     */
    public static ByteBuffer serializeWithCompression(DsTableAdapter entity) {
        // 原始序列化
        ByteBuffer original = entity.toBytes();
        byte[] originalData = new byte[original.limit()];
        original.get(originalData);
        
        // 压缩
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(originalData);
        deflater.finish();
        
        byte[] compressedData = new byte[originalData.length];
        int compressedSize = deflater.deflate(compressedData);
        deflater.end();
        
        // 创建结果缓冲区：原始大小(4) + 压缩大小(4) + 压缩数据
        ByteBuffer result = ByteBuffer.allocate(8 + compressedSize);
        result.putInt(originalData.length);
        result.putInt(compressedSize);
        result.put(compressedData, 0, compressedSize);
        
        result.flip();
        return result;
    }
    
    /**
     * 带压缩的反序列化
     */
    public static void deserializeWithCompression(
            ByteBuffer compressed, 
            DsTableAdapter entity) throws Exception {
        
        compressed.rewind();
        
        // 读取大小信息
        int originalSize = compressed.getInt();
        int compressedSize = compressed.getInt();
        
        // 读取压缩数据
        byte[] compressedData = new byte[compressedSize];
        compressed.get(compressedData);
        
        // 解压缩
        Inflater inflater = new Inflater();
        inflater.setInput(compressedData);
        
        byte[] originalData = new byte[originalSize];
        inflater.inflate(originalData);
        inflater.end();
        
        // 反序列化
        ByteBuffer original = ByteBuffer.wrap(originalData);
        entity.load(original);
    }
    
    /**
     * 带校验和的序列化
     */
    public static ByteBuffer serializeWithChecksum(DsTableAdapter entity) {
        // 原始序列化
        ByteBuffer original = entity.toBytes();
        byte[] originalData = new byte[original.limit()];
        original.get(originalData);
        
        // 计算CRC32校验和
        CRC32 crc32 = new CRC32();
        crc32.update(originalData);
        long checksum = crc32.getValue();
        
        // 创建结果缓冲区：校验和(8) + 数据
        ByteBuffer result = ByteBuffer.allocate(8 + originalData.length);
        result.putLong(checksum);
        result.put(originalData);
        
        result.flip();
        return result;
    }
    
    /**
     * 带校验和的反序列化
     */
    public static void deserializeWithChecksum(
            ByteBuffer data, 
            DsTableAdapter entity) throws Exception {
        
        data.rewind();
        
        // 读取校验和
        long expectedChecksum = data.getLong();
        
        // 读取数据
        byte[] originalData = new byte[data.remaining()];
        data.get(originalData);
        
        // 验证校验和
        CRC32 crc32 = new CRC32();
        crc32.update(originalData);
        long actualChecksum = crc32.getValue();
        
        if (expectedChecksum != actualChecksum) {
            throw new IllegalStateException(
                "校验和不匹配: 期望=" + expectedChecksum + ", 实际=" + actualChecksum);
        }
        
        // 反序列化
        ByteBuffer original = ByteBuffer.wrap(originalData);
        entity.load(original);
    }
    
  
}
