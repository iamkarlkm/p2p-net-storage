package com.q3lives.ds.example;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * 使用示例和测试
 */
public class AdapterUsageExample {
    
    public static void main(String[] args) {
        System.out.println("=== 数据库ORM适配器使用示例 ===");
        
        // 1. 创建实体
        UserEntity user = new UserEntity();
        user.setId(1001L);
        user.setUsername("张三");
        user.setEmail("zhangsan@example.com");
        user.setAge(28);
        user.setBalance(new BigDecimal("12345.67"));
        user.setCreateTime(new Date());
        user.setLastLoginTime(new Date());
        user.setLoginCount(156L);
        user.setScore(98.5);
        
        // 设置复合字段
        user.setActive(true);
        user.setVerified(true);
        user.setUserLevel(5);
        user.setUserType(2);
        
        System.out.println("原始实体:");
        System.out.println(user);
        System.out.println();
        
        // 2. 显示映射信息
        System.out.println(user.getMappingInfo());
        System.out.println();
        
        // 3. 序列化
        System.out.println("序列化...");
        ByteBuffer buffer = user.toBytes();
        System.out.println("序列化后大小: " + buffer.limit() + " 字节");
        System.out.println("字节内容: " + bytesToHex(buffer.array()));
        System.out.println();
        
        // 4. 反序列化
        System.out.println("反序列化...");
        UserEntity loadedUser = new UserEntity();
        loadedUser.load(buffer);
        System.out.println("反序列化后实体:");
        System.out.println(loadedUser);
        System.out.println();
        
        // 5. 验证数据一致性
        System.out.println("数据一致性验证:");
        System.out.println("ID一致: " + user.getId().equals(loadedUser.getId()));
        System.out.println("用户名一致: " + user.getUsername().equals(loadedUser.getUsername()));
        System.out.println("邮箱一致: " + user.getEmail().equals(loadedUser.getEmail()));
        System.out.println("年龄一致: " + user.getAge().equals(loadedUser.getAge()));
        System.out.println("余额一致: " + user.getBalance().equals(loadedUser.getBalance()));
        System.out.println("活跃状态一致: " + (user.isActive() == loadedUser.isActive()));
        System.out.println("验证状态一致: " + (user.isVerified() == loadedUser.isVerified()));
        System.out.println("用户等级一致: " + (user.getUserLevel() == loadedUser.getUserLevel()));
        System.out.println("用户类型一致: " + (user.getUserType() == loadedUser.getUserType()));
        System.out.println();
        
        // 6. 性能测试
        performanceTest();
    }
    
    /**
     * 性能测试
     */
    private static void performanceTest() {
        System.out.println("=== 性能测试 ===");
        
        int iterations = 100000;
        
        // 创建测试实体
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("测试用户");
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
        
        // 序列化性能测试
        long startTime = System.nanoTime();
        ByteBuffer buffer = null;
        for (int i = 0; i < iterations; i++) {
            buffer = user.toBytes();
        }
        long serializeTime = System.nanoTime() - startTime;
        
        System.out.println("序列化性能:");
        System.out.println("  迭代次数: " + iterations);
        System.out.println("  总耗时: " + (serializeTime / 1_000_000) + " ms");
        System.out.println("  平均耗时: " + (serializeTime / iterations) + " ns");
        System.out.println("  吞吐量: " + (iterations * 1_000_000_000L / serializeTime) + " ops/s");
        System.out.println();
        
        // 反序列化性能测试
        startTime = System.nanoTime();
        UserEntity loadedUser = new UserEntity();
        for (int i = 0; i < iterations; i++) {
            buffer.rewind();
            loadedUser.load(buffer);
        }
        long deserializeTime = System.nanoTime() - startTime;
        
                System.out.println("反序列化性能:");
        System.out.println("  迭代次数: " + iterations);
        System.out.println("  总耗时: " + (deserializeTime / 1_000_000) + " ms");
        System.out.println("  平均耗时: " + (deserializeTime / iterations) + " ns");
        System.out.println("  吞吐量: " + (iterations * 1_000_000_000L / deserializeTime) + " ops/s");
        System.out.println();
        
        // 完整往返性能测试
        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            ByteBuffer buf = user.toBytes();
            buf.rewind();
            loadedUser.load(buf);
        }
        long roundTripTime = System.nanoTime() - startTime;
        
        System.out.println("完整往返性能:");
        System.out.println("  迭代次数: " + iterations);
        System.out.println("  总耗时: " + (roundTripTime / 1_000_000) + " ms");
        System.out.println("  平均耗时: " + (roundTripTime / iterations) + " ns");
        System.out.println("  吞吐量: " + (iterations * 1_000_000_000L / roundTripTime) + " ops/s");
    }
    
    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 64); i++) {
            sb.append(String.format("%02X ", bytes[i]));
            if ((i + 1) % 16 == 0) {
                sb.append("\n");
            }
        }
        if (bytes.length > 64) {
            sb.append("... (共 ").append(bytes.length).append(" 字节)");
        }
        return sb.toString();
    }
}
