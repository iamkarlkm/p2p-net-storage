package javax.net.p2p.config;

import javax.net.p2p.common.pool.HybridObjectPool;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 *
 * 生产环境对象池配置
 *
 * @author iamkarl@163.com
 */
@Slf4j
public class ProductionPoolConfig {

    private static final ScheduledExecutorService scheduler
        = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pool-monitor");
            t.setDaemon(true);
            return t;
        });

    /**
     *
     * 初始化对象池配置
     */
    public static void initialize() {
        log.info("初始化对象池配置...");

// 1. 启用降级策略
        HybridObjectPool.setFallbackEnabled(true);

// 2. 启动监控任务（每分钟输出一次）
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String metrics = HybridObjectPool.getMetrics();
                log.info("对象池监控报告:\n{}", metrics);

                // 可以将指标上报到监控系统
                // reportToMonitoring(metrics);
            } catch (Exception ex) {
                log.error("监控任务失败: {}", ex.getMessage(), ex);
            }
        }, 1, 1, TimeUnit.MINUTES);

// 3. 注册JVM关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("应用关闭，输出最终对象池指标:");
            log.info("\n{}", HybridObjectPool.getMetrics());
            scheduler.shutdown();
        }));
        log.info("对象池配置初始化完成");
    }

    /**
     *
     * 动态调整策略（根据监控指标）
     */
    public static void autoTune() {
// 示例：如果降级率过高，可以考虑调整策略
// 实际实现需要根据具体监控指标
        log.info("执行对象池自动调优...");

// TODO: 实现自动调优逻辑}
    }
}
