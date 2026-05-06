
package com.q3lives.ds.test;

import com.q3lives.ds.example.*;
import com.q3lives.ds.benchmark.DsBenchmark;
import com.q3lives.ds.util.BatchSerializer;
import com.q3lives.ds.util.SerializationEnhancer;

/**
 * 完整测试套件
 */
public class FullTestSuite {
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║     数据库ORM适配器 - 完整测试套件                     ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        
        try {
            // 1. 基础功能测试
            System.out.println("【测试1】基础序列化功能");
            System.out.println("═".repeat(60));
            AdapterUsageExample.main(null);
            System.out.println("✓ 测试通过\n");
            
            // 2. 复杂实体测试
            System.out.println("【测试2】复杂实体序列化");
            System.out.println("═".repeat(60));
            OrderEntityTest.main(null);
            System.out.println("✓ 测试通过");
            
            // 3. 批量操作测试
            System.out.println("【测试3】批量序列化");
            System.out.println("═".repeat(60));
            DsBenchmark.performanceBenchmark();
            System.out.println("✓ 测试通过");
            
            // 4. 压缩功能测试
            System.out.println("【测试4】压缩和校验");
            System.out.println("═".repeat(60));
            DsBenchmark.compressionBenchmark();
            System.out.println("✓ 测试通过");
            
            // 5. 集成测试
            System.out.println("【测试5】集成管理器");
            System.out.println("═".repeat(60));
            IntegrationExample.main(null);
            System.out.println("✓ 测试通过");
            
            // 6. 性能基准测试
            System.out.println("【测试6】性能基准");
            System.out.println("═".repeat(60));
            DsBenchmark.main(null);
            System.out.println("✓ 测试通过");
            
            // 测试总结
            System.out.println("╔════════════════════════════════════════════════════════╗");
            System.out.println("║              所有测试通过！                             ║");
            System.out.println("╚════════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            System.err.println("✗ 测试失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
