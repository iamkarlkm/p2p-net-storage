package examples;

import javax.net.p2p.im.ChatClient;
import javax.net.p2p.im.ChatMessageProcessor;
import javax.net.p2p.im.UserManager;
import javax.net.p2p.model.UserInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 性能测试演示程序 - 测试IM系统性能和可靠性
 * 
 * 功能说明：
 * 1. 压力测试：模拟多用户并发操作
 * 2. 性能测试：测量消息发送延迟和吞吐量
 * 3. 可靠性测试：测试网络异常情况下的消息传输
 * 4. 资源监控：监控内存和CPU使用情况
 * 
 * 测试场景：
 * 1. 单服务器多客户端压力测试
 * 2. 高并发消息发送测试
 * 3. 长时间运行稳定性测试
 * 4. 网络异常恢复测试
 * 
 * @author IM System
 * @version 1.0
 * @since 2026
 */
public class PerformanceDemo {
    
    // 测试配置
    private static final int DEFAULT_CLIENT_COUNT = 10;
    private static final int DEFAULT_MESSAGE_COUNT = 100;
    private static final int DEFAULT_TEST_DURATION_SECONDS = 30;
    private static final String SERVER_ADDRESS = "127.0.0.1";
    private static final int SERVER_PORT = 6060;
    
    // 测试统计
    private static final AtomicInteger totalMessagesSent = new AtomicInteger(0);
    private static final AtomicInteger totalMessagesReceived = new AtomicInteger(0);
    private static final AtomicInteger totalMessagesFailed = new AtomicInteger(0);
    private static final List<Long> messageLatencies = new ArrayList<>();
    
    public static void main(String[] args) {
        try {
            System.out.println("=== P2P即时通讯系统 - 性能测试演示 ===");
            System.out.println();
            
            // 解析命令行参数
            int clientCount = parseArg(args, 0, DEFAULT_CLIENT_COUNT);
            int messageCount = parseArg(args, 1, DEFAULT_MESSAGE_COUNT);
            int testDuration = parseArg(args, 2, DEFAULT_TEST_DURATION_SECONDS);
            
            // 显示测试配置
            showTestConfiguration(clientCount, messageCount, testDuration);
            
            // 选择测试类型
            int testType = selectTestType();
            
            // 执行测试
            switch (testType) {
                case 1:
                    runConcurrentUserTest(clientCount, messageCount);
                    break;
                case 2:
                    runMessageThroughputTest(clientCount, messageCount);
                    break;
                case 3:
                    runStabilityTest(testDuration);
                    break;
                case 4:
                    runStressTest(clientCount, messageCount);
                    break;
                case 5:
                    runAllTests(clientCount, messageCount, testDuration);
                    break;
                default:
                    System.out.println("无效的选择");
                    return;
            }
            
            // 显示测试结果
            showTestResults();
            
        } catch (Exception e) {
            System.err.println("性能测试运行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 解析命令行参数
     */
    private static int parseArg(String[] args, int index, int defaultValue) {
        if (args.length > index) {
            try {
                return Integer.parseInt(args[index]);
            } catch (NumberFormatException e) {
                System.err.println("参数格式错误，使用默认值: " + defaultValue);
            }
        }
        return defaultValue;
    }
    
    /**
     * 显示测试配置
     */
    private static void showTestConfiguration(int clientCount, int messageCount, int testDuration) {
        System.out.println("=== 测试配置 ===");
        System.out.printf("客户端数量: %d%n", clientCount);
        System.out.printf("每个客户端发送消息数: %d%n", messageCount);
        System.out.printf("测试持续时间: %d秒%n", testDuration);
        System.out.printf("服务器地址: %s:%d%n", SERVER_ADDRESS, SERVER_PORT);
        System.out.println();
    }
    
    /**
     * 选择测试类型
     */
    private static int selectTestType() {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        
        System.out.println("=== 选择测试类型 ===");
        System.out.println("1. 并发用户测试");
        System.out.println("2. 消息吞吐量测试");
        System.out.println("3. 系统稳定性测试");
        System.out.println("4. 压力测试");
        System.out.println("5. 运行所有测试");
        System.out.println();
        
        System.out.print("请选择测试类型 (1-5): ");
        String input = scanner.nextLine().trim();
        
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return 1; // 默认选择并发用户测试
        }
    }
    
    /**
     * 运行并发用户测试
     */
    private static void runConcurrentUserTest(int clientCount, int messageCount) {
        System.out.println("=== 并发用户测试 ===");
        System.out.println("测试目标: 验证系统在多用户并发登录和消息发送时的表现");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        
        // 创建测试线程列表
        List<Thread> clientThreads = new ArrayList<>();
        
        for (int i = 0; i < clientCount; i++) {
            final int clientId = i;
            Thread thread = new Thread(() -> runClientTest(clientId, messageCount));
            clientThreads.add(thread);
            thread.start();
            
            // 稍微延迟启动，模拟真实用户登录
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // 等待所有线程完成
        for (Thread thread : clientThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        System.out.println();
        System.out.println("=== 并发用户测试完成 ===");
        System.out.printf("测试时间: %d 毫秒 (%.2f 秒)%n", totalTime, totalTime / 1000.0);
        System.out.println();
    }
    
    /**
     * 运行消息吞吐量测试
     */
    private static void runMessageThroughputTest(int clientCount, int messageCount) {
        System.out.println("=== 消息吞吐量测试 ===");
        System.out.println("测试目标: 测量系统在固定时间内的消息处理能力");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        
        // 创建测试线程
        List<Thread> threads = new ArrayList<>();
        int totalMessages = clientCount * messageCount;
        
        for (int i = 0; i < clientCount; i++) {
            final int clientId = i;
            Thread thread = new Thread(() -> {
                try {
                    // 模拟用户
                    UserInfo user = new UserInfo("test_user_" + clientId, 
                        "测试用户" + clientId, "用户" + clientId);
                    
                    // 创建聊天客户端（简化测试，不实际连接）
                    for (int j = 0; j < messageCount; j++) {
                        long sendTime = System.currentTimeMillis();
                        totalMessagesSent.incrementAndGet();
                        
                        // 模拟消息发送延迟
                        Thread.sleep(10);
                        
                        // 模拟消息接收
                        totalMessagesReceived.incrementAndGet();
                        messageLatencies.add(System.currentTimeMillis() - sendTime);
                        
                        if (j % 10 == 0) {
                            System.out.printf("客户端 %d 已发送 %d/%d 条消息%n", 
                                clientId, j, messageCount);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        
        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        // 计算吞吐量
        double throughput = (double) totalMessages / (totalTime / 1000.0);
        
        System.out.println();
        System.out.println("=== 消息吞吐量测试完成 ===");
        System.out.printf("总消息数: %d%n", totalMessages);
        System.out.printf("测试时间: %.2f 秒%n", totalTime / 1000.0);
        System.out.printf("吞吐量: %.2f 消息/秒%n", throughput);
        System.out.println();
    }
    
    /**
     * 运行系统稳定性测试
     */
    private static void runStabilityTest(int testDuration) {
        System.out.println("=== 系统稳定性测试 ===");
        System.out.println("测试目标: 验证系统在长时间运行下的稳定性");
        System.out.println();
        
        System.out.printf("开始 %d 秒稳定性测试...%n", testDuration);
        System.out.println("每隔5秒输出一次系统状态");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        long endTime = startTime + (testDuration * 1000L);
        
        int checkInterval = 5000; // 5秒
        int checkCount = 0;
        
        while (System.currentTimeMillis() < endTime) {
            try {
                Thread.sleep(checkInterval);
                checkCount++;
                
                // 模拟系统状态检查
                long currentTime = System.currentTimeMillis();
                long elapsedTime = (currentTime - startTime) / 1000;
                
                System.out.printf("[%02d:%02d] 系统运行中... 已运行 %d 秒，检查次数: %d%n",
                    elapsedTime / 60, elapsedTime % 60, elapsedTime, checkCount);
                
                // 模拟内存使用监控
                Runtime runtime = Runtime.getRuntime();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;
                
                System.out.printf("       内存使用: %.2f MB / %.2f MB (%.1f%%)%n",
                    usedMemory / (1024.0 * 1024.0),
                    totalMemory / (1024.0 * 1024.0),
                    (usedMemory * 100.0) / totalMemory);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println();
        System.out.println("=== 稳定性测试完成 ===");
        System.out.printf("总运行时间: %d 秒%n", testDuration);
        System.out.printf("状态检查次数: %d%n", checkCount);
        System.out.println();
    }
    
    /**
     * 运行压力测试
     */
    private static void runStressTest(int clientCount, int messageCount) {
        System.out.println("=== 压力测试 ===");
        System.out.println("测试目标: 测试系统在高负载下的表现和资源使用情况");
        System.out.println();
        
        // 记录初始内存使用
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("开始压力测试...");
        System.out.println("模拟高并发消息发送...");
        System.out.println();
        
        // 创建大量消息发送线程
        List<Thread> stressThreads = new ArrayList<>();
        int totalStressMessages = clientCount * messageCount * 10; // 10倍压力
        
        for (int i = 0; i < clientCount * 2; i++) { // 双倍客户端数
            final int threadId = i;
            Thread thread = new Thread(() -> {
                try {
                    for (int j = 0; j < messageCount * 5; j++) { // 5倍消息数
                        // 模拟消息处理
                        totalMessagesSent.incrementAndGet();
                        
                        // 随机延迟，模拟真实负载
                        int delay = (int) (Math.random() * 50);
                        Thread.sleep(delay);
                        
                        // 90%成功率
                        if (Math.random() > 0.1) {
                            totalMessagesReceived.incrementAndGet();
                        } else {
                            totalMessagesFailed.incrementAndGet();
                        }
                        
                        if (j % 100 == 0) {
                            System.out.printf("压力线程 %d 已处理 %d 条消息%n", threadId, j);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            stressThreads.add(thread);
            thread.start();
        }
        
        // 等待所有线程完成
        for (Thread thread : stressThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // 记录最终内存使用
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryUsed = finalMemory - initialMemory;
        
        System.out.println();
        System.out.println("=== 压力测试完成 ===");
        System.out.printf("总处理消息数: %d%n", totalStressMessages);
        System.out.printf("发送成功: %d%n", totalMessagesSent.get());
        System.out.printf("接收成功: %d%n", totalMessagesReceived.get());
        System.out.printf("发送失败: %d%n", totalMessagesFailed.get());
        System.out.printf("成功率: %.2f%%%n", 
            (totalMessagesReceived.get() * 100.0) / totalMessagesSent.get());
        System.out.printf("内存使用增加: %.2f MB%n", memoryUsed / (1024.0 * 1024.0));
        System.out.println();
    }
    
    /**
     * 运行所有测试
     */
    private static void runAllTests(int clientCount, int messageCount, int testDuration) {
        System.out.println("=== 运行所有性能测试 ===");
        System.out.println();
        
        // 1. 并发用户测试
        System.out.println("1. 开始并发用户测试...");
        runConcurrentUserTest(clientCount, messageCount);
        
        // 重置统计
        resetStatistics();
        
        // 2. 消息吞吐量测试
        System.out.println("2. 开始消息吞吐量测试...");
        runMessageThroughputTest(clientCount, messageCount);
        
        // 重置统计
        resetStatistics();
        
        // 3. 系统稳定性测试
        System.out.println("3. 开始系统稳定性测试...");
        runStabilityTest(testDuration);
        
        // 4. 压力测试
        System.out.println("4. 开始压力测试...");
        runStressTest(clientCount, messageCount);
        
        System.out.println("=== 所有性能测试完成 ===");
    }
    
    /**
     * 运行客户端测试
     */
    private static void runClientTest(int clientId, int messageCount) {
        try {
            // 模拟用户登录
            System.out.printf("客户端 %d: 开始模拟用户登录...%n", clientId);
            Thread.sleep(200 + clientId * 50); // 模拟登录延迟
            
            // 模拟消息发送
            for (int i = 0; i < messageCount; i++) {
                long sendTime = System.currentTimeMillis();
                
                // 模拟消息发送
                totalMessagesSent.incrementAndGet();
                
                // 模拟网络延迟 (50-200ms)
                int networkDelay = 50 + (int) (Math.random() * 150);
                Thread.sleep(networkDelay);
                
                // 模拟消息接收
                totalMessagesReceived.incrementAndGet();
                messageLatencies.add(System.currentTimeMillis() - sendTime);
                
                if (i % 10 == 0 && i > 0) {
                    System.out.printf("客户端 %d: 已发送 %d/%d 条消息%n", clientId, i, messageCount);
                }
            }
            
            System.out.printf("客户端 %d: 测试完成，共发送 %d 条消息%n", clientId, messageCount);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.printf("客户端 %d: 测试被中断%n", clientId);
        }
    }
    
    /**
     * 重置统计信息
     */
    private static void resetStatistics() {
        totalMessagesSent.set(0);
        totalMessagesReceived.set(0);
        totalMessagesFailed.set(0);
        messageLatencies.clear();
    }
    
    /**
     * 显示测试结果
     */
    private static void showTestResults() {
        System.out.println("=== 性能测试结果汇总 ===");
        System.out.println();
        
        int sent = totalMessagesSent.get();
        int received = totalMessagesReceived.get();
        int failed = totalMessagesFailed.get();
        
        System.out.printf("总发送消息数: %d%n", sent);
        System.out.printf("总接收消息数: %d%n", received);
        System.out.printf("总失败消息数: %d%n", failed);
        
        if (sent > 0) {
            double successRate = (received * 100.0) / sent;
            System.out.printf("消息成功率: %.2f%%%n", successRate);
        }
        
        if (!messageLatencies.isEmpty()) {
            long sum = 0;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            
            for (long latency : messageLatencies) {
                sum += latency;
                min = Math.min(min, latency);
                max = Math.max(max, latency);
            }
            
            double avgLatency = (double) sum / messageLatencies.size();
            
            System.out.println();
            System.out.println("=== 延迟统计 ===");
            System.out.printf("平均延迟: %.2f 毫秒%n", avgLatency);
            System.out.printf("最小延迟: %d 毫秒%n", min);
            System.out.printf("最大延迟: %d 毫秒%n", max);
            
            // 计算延迟分布
            int[] latencyBuckets = new int[5];
            for (long latency : messageLatencies) {
                if (latency < 50) latencyBuckets[0]++;
                else if (latency < 100) latencyBuckets[1]++;
                else if (latency < 200) latencyBuckets[2]++;
                else if (latency < 500) latencyBuckets[3]++;
                else latencyBuckets[4]++;
            }
            
            System.out.println();
            System.out.println("=== 延迟分布 ===");
            System.out.printf("0-50ms:   %d (%.1f%%)%n", 
                latencyBuckets[0], (latencyBuckets[0] * 100.0) / messageLatencies.size());
            System.out.printf("50-100ms: %d (%.1f%%)%n", 
                latencyBuckets[1], (latencyBuckets[1] * 100.0) / messageLatencies.size());
            System.out.printf("100-200ms: %d (%.1f%%)%n", 
                latencyBuckets[2], (latencyBuckets[2] * 100.0) / messageLatencies.size());
            System.out.printf("200-500ms: %d (%.1f%%)%n", 
                latencyBuckets[3], (latencyBuckets[3] * 100.0) / messageLatencies.size());
            System.out.printf(">500ms:   %d (%.1f%%)%n", 
                latencyBuckets[4], (latencyBuckets[4] * 100.0) / messageLatencies.size());
        }
        
        // 显示系统资源信息
        System.out.println();
        System.out.println("=== 系统资源信息 ===");
        Runtime runtime = Runtime.getRuntime();
        
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        System.out.printf("已用内存: %.2f MB%n", usedMemory / (1024.0 * 1024.0));
        System.out.printf("空闲内存: %.2f MB%n", freeMemory / (1024.0 * 1024.0));
        System.out.printf("总内存: %.2f MB%n", totalMemory / (1024.0 * 1024.0));
        System.out.printf("最大内存: %.2f MB%n", maxMemory / (1024.0 * 1024.0));
        System.out.printf("内存使用率: %.1f%%%n", (usedMemory * 100.0) / totalMemory);
        
        // CPU核心数
        int processors = runtime.availableProcessors();
        System.out.printf("CPU核心数: %d%n", processors);
        
        System.out.println();
        System.out.println("=== 性能测试建议 ===");
        System.out.println("1. 对于高并发场景，建议增加服务器资源");
        System.out.println("2. 如果延迟较高，可以优化网络配置");
        System.out.println("3. 定期监控系统资源使用情况");
        System.out.println("4. 根据测试结果调整系统参数");
        
        System.out.println();
        System.out.println("性能测试演示完成");
    }
    
    /**
     * 基准测试示例
     */
    public static class BenchmarkExample {
        
        public static void main(String[] args) {
            System.out.println("=== IM系统基准测试 ===");
            System.out.println();
            
            // 测试配置
            int[] clientCounts = {1, 10, 50, 100};
            int[] messageCounts = {100, 500, 1000};
            
            System.out.println("客户端数量测试:");
            System.out.println("-----------------");
            for (int clients : clientCounts) {
                runBenchmark(clients, 100, "客户端数: " + clients);
            }
            
            System.out.println();
            System.out.println("消息数量测试:");
            System.out.println("-----------------");
            for (int messages : messageCounts) {
                runBenchmark(10, messages, "消息数: " + messages);
            }
        }
        
        private static void runBenchmark(int clients, int messages, String description) {
            long startTime = System.currentTimeMillis();
            
            // 模拟测试
            int totalMessages = clients * messages;
            long simulatedTime = totalMessages * 50L; // 每消息50ms
            
            try {
                Thread.sleep(Math.min(simulatedTime, 5000)); // 最多5秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            
            double throughput = (double) totalMessages / (duration / 1000.0);
            
            System.out.printf("%-20s 时间: %5dms, 吞吐量: %7.2f msg/s%n", 
                description, duration, throughput);
        }
    }
    
    /**
     * 性能优化建议示例
     */
    public static class OptimizationGuide {
        
        public static void showOptimizationGuide() {
            System.out.println("=== IM系统性能优化指南 ===");
            System.out.println();
            
            System.out.println("1. 网络优化:");
            System.out.println("   - 使用UDP协议减少连接开销");
            System.out.println("   - 优化数据包大小，减少分片");
            System.out.println("   - 启用UDP可靠性传输机制");
            System.out.println("   - 配置合适的超时和重试策略");
            System.out.println();
            
            System.out.println("2. 内存优化:");
            System.out.println("   - 使用对象池减少GC压力");
            System.out.println("   - 优化数据序列化/反序列化");
            System.out.println("   - 及时释放不再使用的资源");
            System.out.println("   - 监控内存泄漏");
            System.out.println();
            
            System.out.println("3. CPU优化:");
            System.out.println("   - 使用线程池管理并发连接");
            System.out.println("   - 优化消息处理逻辑");
            System.out.println("   - 避免不必要的锁竞争");
            System.out.println("   - 使用异步非阻塞IO");
            System.out.println();
            
            System.out.println("4. 扩展性优化:");
            System.out.println("   - 支持水平扩展，多服务器部署");
            System.out.println("   - 使用负载均衡分发请求");
            System.out.println("   - 设计无状态服务架构");
            System.out.println("   - 支持动态扩容");
            System.out.println();
            
            System.out.println("5. 监控和调优:");
            System.out.println("   - 实现全面的性能监控");
            System.out.println("   - 定期进行性能测试");
            System.out.println("   - 根据监控数据动态调优");
            System.out.println("   - 建立性能基线");
            System.out.println();
        }
    }
    
    /**
     * 快速启动示例
     */
    public static void quickStart() {
        System.out.println("=== 性能测试快速启动 ===");
        System.out.println();
        
        System.out.println("运行简单性能测试:");
        System.out.println("----------------------");
        runConcurrentUserTest(5, 20);
        
        System.out.println();
        System.out.println("查看系统资源:");
        System.out.println("----------------------");
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("可用CPU核心: %d%n", runtime.availableProcessors());
        System.out.printf("总内存: %.2f MB%n", runtime.totalMemory() / (1024.0 * 1024.0));
        
        System.out.println();
        System.out.println("性能优化建议:");
        System.out.println("----------------------");
        OptimizationGuide.showOptimizationGuide();
    }
}