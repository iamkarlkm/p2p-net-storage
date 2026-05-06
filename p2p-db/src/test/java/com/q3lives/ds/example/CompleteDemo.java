package com.q3lives.ds.example;

import com.q3lives.ds.benchmark.DsBenchmark;
import com.q3lives.ds.util.BatchSerializer;
import com.q3lives.ds.util.SerializationEnhancer;

/**
 * 完整功能演示
 */
public class CompleteDemo {
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║   数据库ORM自动序列化/反序列化适配器 - 完整演示        ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // 1. 基础功能演示
        System.out.println("【1】基础序列化/反序列化功能");
        System.out.println("─".repeat(60));
        AdapterUsageExample.main(null);
        System.out.println();
        
        // 2. 复杂实体演示
        System.out.println("【2】复杂实体（订单）序列化");
        System.out.println("─".repeat(60));
        OrderEntityTest.main(null);
        System.out.println();
        
        // 3. 批量序列化演示
        System.out.println("【3】批量序列化性能测试");
        System.out.println("─".repeat(60));
        DsBenchmark.performanceBenchmark();
        System.out.println();
        
        // 4. 压缩功能演示
        System.out.println("【4】压缩和校验和功能");
        System.out.println("─".repeat(60));
        DsBenchmark.compressionBenchmark();
        System.out.println();
        
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║                    演示完成                            ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
