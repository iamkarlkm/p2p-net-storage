package com.q3lives.ds.benchmark;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.cache.SerializationCache;
import com.q3lives.ds.example.UserEntity;
import com.q3lives.ds.pool.ByteBufferPool;
import static com.q3lives.ds.util.BatchSerializer.deserializeBatch;
import static com.q3lives.ds.util.BatchSerializer.serializeBatch;
import static com.q3lives.ds.util.SerializationEnhancer.deserializeWithChecksum;
import static com.q3lives.ds.util.SerializationEnhancer.deserializeWithCompression;
import static com.q3lives.ds.util.SerializationEnhancer.serializeWithChecksum;
import static com.q3lives.ds.util.SerializationEnhancer.serializeWithCompression;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 综合性能基准测试
 */
public class DsBenchmark {
    
    private static final int WARMUP_ITERATIONS = 10000;
    private static final int TEST_ITERATIONS = 100000;
    
    public static void main(String[] args) {
         // 强制设置 System.out 为 UTF-8
    System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║           综合性能基准测试                              ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // 1. 基础序列化性能
        testBasicSerialization();
        
        // 2. 缓存性能
        testCachedSerialization();
        
        // 3. 对象池性能
        testPooledSerialization();
        
        // 4. 内存占用分析
        testMemoryFootprint();
        
        System.out.println("\n测试完成！");
    }
    
    /**
     * 测试基础序列化性能
     */
    private static void testBasicSerialization() {
        System.out.println("【1】基础序列化性能测试");
        System.out.println("─".repeat(60));
        
        UserEntity user = createTestUser();
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ByteBuffer buffer = user.toBytes();
        }
        
        // 测试序列化
        long startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuffer buffer = user.toBytes();
        }
        long serializeTime = System.nanoTime() - startTime;
        
        // 测试反序列化
        ByteBuffer buffer = user.toBytes();
        UserEntity loadedUser = new UserEntity();
        
        startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            buffer.rewind();
            loadedUser.load(buffer);
        }
        long deserializeTime = System.nanoTime() - startTime;
        
        printResults("基础序列化", serializeTime, deserializeTime);
    }
    
    /**
     * 测试缓存序列化性能
     */
    private static void testCachedSerialization() {
        System.out.println("\n【2】缓存序列化性能测试");
        System.out.println("─".repeat(60));
        
        SerializationCache cache = new SerializationCache(1000);
        UserEntity user = createTestUser();
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.getOrSerialize(user);
        }
        
        // 测试
        long startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuffer buffer = cache.getOrSerialize(user);
        }
        long cachedTime = System.nanoTime() - startTime;
        
        System.out.println("缓存序列化:");
        System.out.println("  总耗时: " + (cachedTime / 1_000_000) + " ms");
        System.out.println("  平均耗时: " + (cachedTime / TEST_ITERATIONS) + " ns");
        System.out.println("  吞吐量: " + 
            String.format("%,d", TEST_ITERATIONS * 1_000_000_000L / cachedTime) + " ops/s");
        System.out.println("  " + cache.getStats());
    }
    
    /**
     * 测试对象池序列化性能
     */
    private static void testPooledSerialization() {
        System.out.println("【3】对象池序列化性能测试");
        System.out.println("─".repeat(60));
        
        ByteBufferPool pool = new ByteBufferPool(512, 100);
        UserEntity user = createTestUser();
        
        // 预热
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            ByteBuffer buffer = pool.acquire();
            pool.release(buffer);
        }
        
        // 测试
        long startTime = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            ByteBuffer buffer = pool.acquire();
            // 模拟使用
            pool.release(buffer);
        }
        long pooledTime = System.nanoTime() - startTime;
        
        System.out.println("对象池性能:");
        System.out.println("  总耗时: " + (pooledTime / 1_000_000) + " ms");
        System.out.println("  平均耗时: " + (pooledTime / TEST_ITERATIONS) + " ns");
        System.out.println("  吞吐量: " + 
            String.format("%,d", TEST_ITERATIONS * 1_000_000_000L / pooledTime) + " ops/s");
        System.out.println("  " + pool.getStats());
    }
    
    /**
     * 测试内存占用
     */
    private static void testMemoryFootprint() {
        System.out.println("【4】内存占用分析");
        System.out.println("─".repeat(60));
        
        Runtime runtime = Runtime.getRuntime();
        
        // 强制GC
        System.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        long beforeMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // 创建大量实体
        int entityCount = 10000;
        UserEntity[] entities = new UserEntity[entityCount];
        for (int i = 0; i < entityCount; i++) {
            entities[i] = createTestUser();
            entities[i].setId((long) i);
        }
        
        long afterMemory = runtime.totalMemory() - runtime.freeMemory();
        long usedMemory = afterMemory - beforeMemory;
        
        System.out.println("实体数量: " + String.format("%,d", entityCount));
        System.out.println("总内存占用: " + String.format("%,d", usedMemory) + " 字节");
        System.out.println("平均每个实体: " + (usedMemory / entityCount) + " 字节");
        
        // 序列化后大小
        ByteBuffer buffer = entities[0].toBytes();
        System.out.println("序列化后大小: " + buffer.limit() + " 字节");
        System.out.println("压缩率: " + String.format("%.2f%%", 
            (1.0 - (double) buffer.limit() / (usedMemory / entityCount)) * 100));
    }
    
    /**
     * 创建测试用户
     */
    private static UserEntity createTestUser() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setAge(25);
        user.setBalance(new BigDecimal("1000.00"));
        user.setCreateTime(new Date());
        user.setLastLoginTime(new Date());
        user.setLoginCount(100L);
        user.setScore(85.5);
        user.setActive(true);
        user.setVerified(true);
        user.setUserLevel(3);
        user.setUserType(1);
        return user;
    }
    
    /**
     * 打印结果
     */
    private static void printResults(String name, long serializeTime, long deserializeTime) {
        System.out.println(name + ":");
        System.out.println("  序列化:");
        System.out.println("    总耗时: " + (serializeTime / 1_000_000) + " ms");
        System.out.println("    平均耗时: " + (serializeTime / TEST_ITERATIONS) + " ns");
        System.out.println("    吞吐量: " + 
            String.format("%,d", TEST_ITERATIONS * 1_000_000_000L / serializeTime) + " ops/s");
        
        System.out.println("  反序列化:");
        System.out.println("    总耗时: " + (deserializeTime / 1_000_000) + " ms");
        System.out.println("    平均耗时: " + (deserializeTime / TEST_ITERATIONS) + " ns");
        System.out.println("    吞吐量: " + 
            String.format("%,d", TEST_ITERATIONS * 1_000_000_000L / deserializeTime) + " ops/s");
    }
    
     /**
     * 批量序列化性能测试
     */
    public static void performanceBenchmark() {
        System.out.println("=== 批量序列化性能测试 ===\n");
        
        int[] batchSizes = {10, 100, 1000, 10000};
        
        for (int batchSize : batchSizes) {
            // 创建测试数据
            List<UserEntity> users = new ArrayList<>();
            for (int i = 0; i < batchSize; i++) {
                UserEntity user = new UserEntity();
                user.setId((long) i);
                user.setUsername("user" + i);
                user.setEmail("user" + i + "@example.com");
                user.setAge(20 + (i % 50));
                user.setActive(i % 2 == 0);
                user.setVerified(i % 3 == 0);
                user.setUserLevel(i % 10);
                user.setUserType(i % 5);
                users.add(user);
            }
            
            // 序列化测试
            long startTime = System.nanoTime();
            ByteBuffer buffer = serializeBatch(users);
            long serializeTime = System.nanoTime() - startTime;
            
            // 反序列化测试
            startTime = System.nanoTime();
            try {
                List<UserEntity> loadedUsers = deserializeBatch(buffer, UserEntity.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
            long deserializeTime = System.nanoTime() - startTime;
            
            System.out.println("批量大小: " + batchSize);
            System.out.println("  序列化耗时: " + (serializeTime / 1_000_000) + " ms");
            System.out.println("  反序列化耗时: " + (deserializeTime / 1_000_000) + " ms");
            System.out.println("  总大小: " + buffer.limit() + " 字节");
            System.out.println("  平均每条: " + (buffer.limit() / batchSize) + " 字节");
            System.out.println();
        }
    }
    
      /**
     * 压缩率测试
     */
    public static void compressionBenchmark() {
        System.out.println("=== 压缩效果测试 ===");
        
        // 创建测试实体
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("测试用户名称");
        user.setEmail("test@example.com");
        user.setAge(25);
        user.setActive(true);
        user.setVerified(true);
        
        // 原始序列化
        ByteBuffer original = user.toBytes();
        int originalSize = original.limit();
        
        // 压缩序列化
        ByteBuffer compressed = serializeWithCompression(user);
        int compressedSize = compressed.limit();
        
        // 带校验和序列化
        ByteBuffer withChecksum = serializeWithChecksum(user);
        int checksumSize = withChecksum.limit();
        
        System.out.println("原始大小: " + originalSize + " 字节");
        System.out.println("压缩后大小: " + compressedSize + " 字节");
        System.out.println("压缩率: " + String.format("%.2f%%", 
            (1 - (double) compressedSize / originalSize) * 100));
        System.out.println("带校验和大小: " + checksumSize + " 字节");
        System.out.println("校验和开销: " + (checksumSize - originalSize) + " 字节");
        
        // 验证正确性
        try {
            UserEntity decompressed = new UserEntity();
            deserializeWithCompression(compressed, decompressed);
            System.out.println("\n压缩/解压缩验证: " + 
                (user.getUsername().equals(decompressed.getUsername()) ? "通过" : "失败"));
            
            UserEntity withChecksumEntity = new UserEntity();
            deserializeWithChecksum(withChecksum, withChecksumEntity);
            System.out.println("校验和验证: " + 
                (user.getUsername().equals(withChecksumEntity.getUsername()) ? "通过" : "失败"));
                
        } catch (Exception e) {
            System.out.println("验证失败: " + e.getMessage());
        }
    }
}
