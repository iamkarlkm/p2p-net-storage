

package com.q3lives.ds.example;

import com.q3lives.ds.database.integration.SerializationManager;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Date;

/**
 * 集成使用示例
 */
public class IntegrationExample {
    
    public static void main(String[] args) {
         // 强制设置 System.out 为 UTF-8
    System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("=== 集成管理器使用示例 ===\n");
        
        // 创建管理器
        SerializationManager manager = new SerializationManager(
            1000,  // 缓存大小
            100,   // 对象池大小
            512,   // 缓冲区大小
            true   // 启用验证
        );
        
        // 创建实体
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("张三");
        user.setEmail("zhangsan@example.com");
        user.setAge(28);
        user.setBalance(new BigDecimal("12345.67"));
        user.setCreateTime(new Date());
        user.setLastLoginTime(new Date());
        user.setLoginCount(156L);
        user.setScore(98.5);
        user.setActive(true);
        user.setVerified(true);
        user.setUserLevel(5);
        user.setUserType(2);
        
        System.out.println("原始实体: " + user);
        System.out.println();
        
        // 序列化（使用缓存）
        System.out.println("第一次序列化（缓存未命中）...");
        ByteBuffer buffer1 = manager.serialize(user);
        System.out.println("序列化完成，大小: " + buffer1.limit() + " 字节");
        System.out.println();
        
        // 再次序列化（缓存命中）
        System.out.println("第二次序列化（缓存命中）...");
        ByteBuffer buffer2 = manager.serialize(user);
        System.out.println("序列化完成，大小: " + buffer2.limit() + " 字节");
        System.out.println();
        
        // 反序列化
        System.out.println("反序列化...");
        UserEntity loadedUser = new UserEntity();
        manager.deserialize(buffer1, loadedUser);
        System.out.println("反序列化后实体: " + loadedUser);
        System.out.println();
        
        // 显示统计
        System.out.println(manager.getStats());
    }
}
