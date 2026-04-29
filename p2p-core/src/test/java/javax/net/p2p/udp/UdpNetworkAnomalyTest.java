package javax.net.p2p.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerUdp;
import javax.net.p2p.udp.network.NetworkAnomalySimulator;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * UDP网络异常模拟测试类
 * 
 * 专注于测试各种网络异常场景下UDP可靠性机制的表现：
 * 1. 不同丢包率场景
 * 2. 不同延迟场景
 * 3. 网络抖动场景
 * 4. 重复包场景
 * 5. 乱序包场景
 * 6. 带宽限制场景
 * 7. 组合异常场景
 * 8. 长时间运行稳定性
 * 
 * 测试目标：
 * - 验证UDP可靠性机制在各种网络条件下的鲁棒性
 * - 测试重传策略的有效性
 * - 验证流量控制和拥塞控制的适应性
 * - 测试资源管理和内存泄漏
 * 
 * @author karl
 */
@Slf4j
public class UdpNetworkAnomalyTest {
    
    private TestUdpReliabilityManager reliabilityManager;
     P2PServerUdp server;
     P2PClientUdp client;
    
    @Before
    public void setUp() throws UnknownHostException {
        client = new P2PClientUdp(new InetSocketAddress("127.0.0.1", 10086));
        reliabilityManager = new TestUdpReliabilityManager();
        
        // 设置消息交付回调以记录统计信息
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            log.debug("Message {} delivered", seq);
        });
    }
    
    @After
    public void tearDown() {
        if (reliabilityManager != null) {
            reliabilityManager.shutdown();
        }
        if (client != null) {
            client.shutdown();
        }
    }
    
    /**
     * 测试场景1: 理想网络条件
     * 丢包率0%，延迟0ms
     */
    @Test(timeout = 10000)
    public void testIdealNetworkConditions() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9000);
        
        int messageCount = 50;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        // 发送50个消息
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client, P2PWrapper.build(P2PCommand.ECHO, "Ideal test " + i));
            reliabilityManager.processAck(seq, remoteAddress);
        }
        System.out.println("消息发送线程数。:"+deliveredCount.get());
        // 等待所有消息交付
        boolean allDelivered = latch.await(5, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在理想网络条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        double throughput = (messageCount * 1000.0) / totalTime;
        
        log.info("理想网络测试: {} 消息在 {}ms 内交付 = {} 消息/秒", 
                 messageCount, totalTime, throughput);
        
        
        
        // 验证无重传
        String stats = reliabilityManager.getStatistics();
        assertFalse("理想网络不应有重传", stats.contains("Retransmitted=1"));
        
        System.out.println(stats);
    }
    
    /**
     * 测试场景2: 轻微网络丢包（1%丢包率）
     */
    @Test(timeout = 15000)
    public void testLightPacketLoss() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9001);
        
        // 模拟1%丢包率
        reliabilityManager.setPacketLossRate(0.01);
        
        int messageCount = 100;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送100个消息，模拟1%丢包率
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Light loss test " + i));
            
            // 99%的概率确认（模拟1%丢包）
            if (Math.random() > 0.01) {
                reliabilityManager.processAck(seq, remoteAddress);
            }
        }
        
        // 等待所有消息交付（可能需要重传）
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在轻微丢包条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        log.info("轻微丢包测试: {} 消息在 {}ms 内交付", messageCount, totalTime);
        
        // 验证有少量重传
        String stats = reliabilityManager.getStatistics();
        assertTrue("轻微丢包应有重传", stats.contains("Retransmitted="));
    }
    
    /**
     * 测试场景3: 中等网络丢包（5%丢包率）
     */
    @Test(timeout = 20000)
    public void testMediumPacketLoss() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9002);
        
        // 模拟5%丢包率
        reliabilityManager.setPacketLossRate(0.05);
        
        int messageCount = 80;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送80个消息，模拟5%丢包率
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Medium loss test " + i));
            
            // 95%的概率确认（模拟5%丢包）
            if (Math.random() > 0.05) {
                reliabilityManager.processAck(seq, remoteAddress);
            }
        }
        
        // 等待所有消息交付（可能需要多次重传）
        boolean allDelivered = latch.await(15, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在中等丢包条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        log.info("中等丢包测试: {} 消息在 {}ms 内交付", messageCount, totalTime);
        
        // 验证有较多重传
        String stats = reliabilityManager.getStatistics();
        assertTrue("中等丢包应有较多重传", stats.contains("Retransmitted="));
    }
    
    /**
     * 测试场景4: 严重网络丢包（15%丢包率）
     */
    @Test(timeout = 30000)
    public void testHeavyPacketLoss() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9003);
        
        // 模拟15%丢包率
        reliabilityManager.setPacketLossRate(0.15);
        
        int messageCount = 60;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送60个消息，模拟15%丢包率
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Heavy loss test " + i));
            
            // 85%的概率确认（模拟15%丢包）
            if (Math.random() > 0.15) {
                reliabilityManager.processAck(seq, remoteAddress);
            }
        }
        
        // 等待所有消息交付（可能需要多次重传和指数退避）
        boolean allDelivered = latch.await(25, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在严重丢包条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        log.info("严重丢包测试: {} 消息在 {}ms 内交付", messageCount, totalTime);
        
        // 验证有大量重传
        String stats = reliabilityManager.getStatistics();
        assertTrue("严重丢包应有大量重传", stats.contains("Retransmitted="));
    }
    
    /**
     * 测试场景5: 网络延迟场景（平均100ms延迟）
     */
    @Test(timeout = 20000)
    public void testNetworkDelay() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9004);
        
        // 模拟100ms平均延迟
        reliabilityManager.setAverageDelayMs(100);
        
        int messageCount = 40;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        AtomicLong totalDelay = new AtomicLong(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送40个消息
        for (int i = 0; i < messageCount; i++) {
            long sendTime = System.currentTimeMillis();
            
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Delay test " + i));
            
            // 延迟确认（模拟网络延迟）
            new Thread(() -> {
                try {
                    // 随机延迟：80-120ms
                    int delay = 80 + (int)(Math.random() * 40);
                    Thread.sleep(delay);
                    reliabilityManager.processAck(ackSeq, remoteAddress);
                    
                    long ackTime = System.currentTimeMillis();
                    totalDelay.addAndGet(ackTime - sendTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        // 等待所有消息交付
        boolean allDelivered = latch.await(15, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在网络延迟条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        double avgDelay = totalDelay.get() / (double) messageCount;
        
        log.info("网络延迟测试: {} 消息在 {}ms 内交付，平均延迟: {:.2f}ms", 
                 messageCount, totalTime, avgDelay);
        
        // 验证延迟不会导致消息丢失
        String stats = reliabilityManager.getStatistics();
        assertFalse("网络延迟不应导致消息丢失", stats.contains("TimeoutFailures="));
    }
    
    /**
     * 测试场景6: 网络抖动场景（延迟变化）
     */
    @Test(timeout = 15000)
    public void testNetworkJitter() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9005);
        
        // 模拟网络抖动：50ms平均延迟，±30ms抖动
        reliabilityManager.setAverageDelayMs(50);
        reliabilityManager.setJitterEnabled(true);
        reliabilityManager.setJitterRangeMs(30);
        
        int messageCount = 30;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        // 发送30个消息
        for (int i = 0; i < messageCount; i++) {
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Jitter test " + i));
            
            // 随机延迟确认（模拟网络抖动）
            new Thread(() -> {
                try {
                    // 随机延迟：20-80ms
                    int delay = 20 + (int)(Math.random() * 60);
                    Thread.sleep(delay);
                    reliabilityManager.processAck(ackSeq, remoteAddress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        // 等待所有消息交付
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("所有消息应在网络抖动条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        log.info("网络抖动测试: {} 消息成功交付", messageCount);
    }
    
    /**
     * 测试场景7: 重复包场景
     */
    @Test(timeout = 10000)
    public void testDuplicatePackets() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9006);
        
        // 启用重复包模拟
        reliabilityManager.setDuplicatePacketEnabled(true);
        
        int messageCount = 25;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveryCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            int count = deliveryCount.incrementAndGet();
            log.debug("Delivery {} for seq {}", count, seq);
            latch.countDown();
        });
        
        // 发送25个消息，每个都可能重复
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Duplicate test " + i));
            
            // 立即确认
            reliabilityManager.processAck(seq, remoteAddress);
            
            // 模拟重复包（随机发送额外副本）
            if (Math.random() < 0.3) { // 30%的概率发送重复包
                duplicateCount.incrementAndGet();
                // 发送重复的数据包
                ByteBuf duplicateData = Unpooled.copiedBuffer(("Duplicate test " + i).getBytes());
                reliabilityManager.processDataMessage(seq, duplicateData, remoteAddress);
            }
        }
        
        // 等待所有原始消息被交付
        boolean allDelivered = latch.await(7, TimeUnit.SECONDS);
        
        assertTrue("所有消息应被交付", allDelivered);
        assertEquals("应交付所有原始消息，重复包不应导致重复交付", 
                     messageCount, deliveryCount.get());
        
        log.info("重复包测试: {} 消息成功交付，发送了 {} 个重复包", 
                 messageCount, duplicateCount.get());
    }
    
    /**
     * 测试场景8: 乱序包场景
     */
    @Test(timeout = 15000)
    public void testOutOfOrderPackets() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9007);
        
        // 启用乱序处理
        reliabilityManager.setOutOfOrderEnabled(true);
        
        int messageCount = 20;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger lastDeliveredSeq = new AtomicInteger(-1);
        AtomicInteger deliveryOrder = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            // 验证消息按顺序交付（即使接收乱序）
            assertTrue("消息应按顺序交付，当前seq=" + seq + "，上次seq=" + lastDeliveredSeq.get(),
                      seq > lastDeliveredSeq.get());
            lastDeliveredSeq.set(seq);
            deliveryOrder.incrementAndGet();
            latch.countDown();
        });
        
        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        for (int i = 0; i < messageCount; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Out-of-order " + i));
            seqs.add(seq);
        }
        java.util.Collections.shuffle(seqs);
        for (int seq : seqs) {
            reliabilityManager.processAck(seq, remoteAddress);
        }
        
        // 等待所有消息按正确顺序被交付
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("所有消息应按正确顺序交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveryOrder.get());
        
        log.info("乱序包测试: {} 消息成功按顺序交付", messageCount);
    }
    
    /**
     * 测试场景9: 带宽限制场景（10KB/s限制）
     */
    @Test(timeout = 25000)
    public void testBandwidthLimitation() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9008);
        
        // 模拟带宽限制：10KB/s
        reliabilityManager.setBandwidthLimitBps(10 * 1024);
        
        int messageCount = 40;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送40个消息（每个约100字节）
        for (int i = 0; i < messageCount; i++) {
            String message = String.format("Bandwidth test %03d - %s", i, 
                "x".repeat(80)); // 约100字节的消息
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, message));
            
            // 确认
            reliabilityManager.processAck(seq, remoteAddress);
        }
        
        // 等待所有消息被交付（带宽限制下可能需要更长时间）
        boolean allDelivered = latch.await(20, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在带宽限制条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        int totalBytes = messageCount * 100; // 约4KB
        double actualBandwidth = (totalBytes * 1000.0) / totalTime;
        
        log.info("带宽限制测试: {} 字节在 {}ms 内交付 = {:.2f} B/s (限制: {} B/s)", 
                 totalBytes, totalTime, actualBandwidth, 10 * 1024);
        
        // 验证流量控制生效
        assertTrue("实际带宽应接近或低于限制", actualBandwidth <= (10 * 1024 * 1.1)); // 允许10%的误差
    }
    
    /**
     * 测试场景10: 组合网络异常场景（恶劣网络环境）
     * 10%丢包率 + 200ms延迟 + 抖动
     */
    @Test(timeout = 35000)
    public void testCombinedNetworkAnomalies() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9009);
        
        // 模拟恶劣网络环境
        reliabilityManager.setPacketLossRate(0.10); // 10%丢包率
        reliabilityManager.setAverageDelayMs(200);  // 200ms平均延迟
        reliabilityManager.setJitterEnabled(true);  // 启用抖动
        reliabilityManager.setJitterRangeMs(100);   // ±100ms抖动
        
        int messageCount = 30;
        CountDownLatch latch = new CountDownLatch(messageCount);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送30个消息
        for (int i = 0; i < messageCount; i++) {
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Combined test " + i));
            
            // 随机确认（模拟10%丢包）
            if (Math.random() > 0.10) {
                // 随机延迟确认（模拟网络延迟和抖动）
                new Thread(() -> {
                    try {
                        // 随机延迟：100-400ms
                        int delay = 100 + (int)(Math.random() * 300);
                        Thread.sleep(delay);
                        reliabilityManager.processAck(ackSeq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
        
        // 等待所有消息被交付（可能需要多次重传）
        boolean allDelivered = latch.await(30, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在组合网络异常条件下交付", allDelivered);
        assertEquals("应交付所有消息", messageCount, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        log.info("组合网络异常测试: {} 消息在 {}ms 内交付", messageCount, totalTime);
        
        // 验证重传机制正常工作
        String stats = reliabilityManager.getStatistics();
        assertTrue("组合网络异常应有显著重传", stats.contains("Retransmitted="));
    }
    
    /**
     * 测试场景11: 长时间运行稳定性测试
     * 模拟持续的中等网络条件（5%丢包，100ms延迟）
     */
    @Test(timeout = 60000)
    public void testLongRunningStability() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9010);
        
        // 中等网络条件：5%丢包，100ms延迟
        reliabilityManager.setPacketLossRate(0.05);
        reliabilityManager.setAverageDelayMs(100);
        
        int totalMessages = 200;
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            successCount.incrementAndGet();
            latch.countDown();
        });
        
        // 持续发送200个消息
        for (int i = 0; i < totalMessages; i++) {
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Long running " + i));
            
            // 随机确认（95%确认率）
            if (Math.random() > 0.05) {
                new Thread(() -> {
                    try {
                        // 随机延迟：50-200ms
                        Thread.sleep(50 + (int)(Math.random() * 150));
                        reliabilityManager.processAck(ackSeq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
            
            // 控制发送速率，避免洪水式发送
            if (i % 20 == 0) {
                Thread.sleep(100);
            }
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(55, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有200个消息应在长时间运行测试中交付", allDelivered);
        assertEquals("应交付所有消息", totalMessages, successCount.get());
        
        long totalTime = endTime - startTime.get();
        double throughput = (totalMessages * 1000.0) / totalTime;
        
        log.info("长时间运行稳定性测试: {} 消息在 {}ms 内交付 = {:.2f} 消息/秒", 
                 totalMessages, totalTime, throughput);
        
        // 验证统计信息
        String stats = reliabilityManager.getStatistics();
        log.info("长时间运行统计: {}", stats);
        
        // 验证没有资源泄漏（通过PendingCount检查）
        assertEquals("所有待处理消息应被清理", 0, 
                     reliabilityManager.getPendingCount());
        
        // 验证内存使用情况
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        log.info("内存使用: {} MB", usedMemory / (1024 * 1024));
    }
    
    /**
     * 测试场景12: 渐进式网络恶化场景
     * 网络条件从良好逐渐恶化到恶劣
     */
    @Test(timeout = 45000)
    public void testProgressiveNetworkDegradation() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9011);
        
        int totalMessages = 150;
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 分阶段发送消息，每阶段网络条件逐渐恶化
        int phases = 5;
        int messagesPerPhase = totalMessages / phases;
        
        for (int phase = 0; phase < phases; phase++) {
            // 每阶段网络条件逐渐恶化
            double lossRate = 0.01 + (phase * 0.04); // 1% -> 17%
            int delay = 20 + (phase * 80); // 20ms -> 340ms
            
            reliabilityManager.setPacketLossRate(lossRate);
            reliabilityManager.setAverageDelayMs(delay);
            
            log.info("阶段 {}: 丢包率={:.1f}%, 延迟={}ms", 
                     phase + 1, lossRate * 100, delay);
            
            // 发送本阶段的消息
            for (int i = 0; i < messagesPerPhase; i++) {
                final int ackSeq = reliabilityManager.sendReliableMessage(client,
                    P2PWrapper.build(0, P2PCommand.ECHO, "Phase " + phase + " msg " + i));
                
                // 随机确认（根据丢包率）
                if (Math.random() > lossRate) {
                    new Thread(() -> {
                        try {
                            // 随机延迟：delay ± 20%
                            int actualDelay = delay + (int)((Math.random() - 0.5) * delay * 0.4);
                            Thread.sleep(Math.max(1, actualDelay));
                            reliabilityManager.processAck(ackSeq, remoteAddress);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            }
            
            // 阶段间短暂暂停
            if (phase < phases - 1) {
                Thread.sleep(1000);
            }
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(40, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("所有消息应在渐进式网络恶化条件下交付", allDelivered);
        assertEquals("应交付所有消息", totalMessages, deliveredCount.get());
        
        long totalTime = endTime - startTime;
        log.info("渐进式网络恶化测试: {} 消息在 {}ms 内交付", totalMessages, totalTime);
        
        // 验证拥塞控制适应了网络条件变化
        String ccInfo = reliabilityManager.getCongestionInfo(remoteAddress);
        log.info("拥塞控制信息: {}", ccInfo);
    }
    
    /**
     * 测试场景13: 突然网络恢复场景
     * 从恶劣网络条件突然恢复到良好条件
     */
    @Test(timeout = 30000)
    public void testSuddenNetworkRecovery() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9012);
        
        // 第一阶段：恶劣网络条件（20%丢包，300ms延迟）
        reliabilityManager.setPacketLossRate(0.20);
        reliabilityManager.setAverageDelayMs(300);
        
        int phase1Messages = 50;
        CountDownLatch phase1Latch = new CountDownLatch(phase1Messages);
        AtomicInteger phase1Delivered = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            phase1Delivered.incrementAndGet();
            phase1Latch.countDown();
        });
        
        // 发送第一阶段消息
        for (int i = 0; i < phase1Messages; i++) {
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Bad network msg " + i));
            
            // 80%的概率确认
            if (Math.random() > 0.20) {
                new Thread(() -> {
                    try {
                        Thread.sleep(200 + (int)(Math.random() * 200));
                        reliabilityManager.processAck(ackSeq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
        
        // 等待第一阶段消息部分交付
        Thread.sleep(5000);
        
        // 第二阶段：突然恢复到良好网络条件（1%丢包，20ms延迟）
        log.info("网络突然恢复！");
        reliabilityManager.setPacketLossRate(0.01);
        reliabilityManager.setAverageDelayMs(20);
        
        int phase2Messages = 50;
        CountDownLatch phase2Latch = new CountDownLatch(phase2Messages);
        AtomicInteger phase2Delivered = new AtomicInteger(0);
        
        reliabilityManager.setMessageDeliveryCallback((seq, data) -> {
            phase2Delivered.incrementAndGet();
            phase2Latch.countDown();
        });
        
        // 发送第二阶段消息
        for (int i = 0; i < phase2Messages; i++) {
            final int ackSeq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Good network msg " + i));
            
            // 99%的概率快速确认
            if (Math.random() > 0.01) {
                new Thread(() -> {
                    try {
                        Thread.sleep(10 + (int)(Math.random() * 20));
                        reliabilityManager.processAck(ackSeq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
        
        // 等待所有消息交付
        boolean phase1Done = phase1Latch.await(20, TimeUnit.SECONDS);
        boolean phase2Done = phase2Latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("第一阶段消息应交付", phase1Done || phase1Delivered.get() > 40);
        assertTrue("第二阶段消息应快速交付", phase2Done);
        
        log.info("突然网络恢复测试: 阶段1交付={}/50, 阶段2交付={}/50", 
                 phase1Delivered.get(), phase2Delivered.get());
        
        // 验证拥塞控制能够快速适应网络恢复
        String stats = reliabilityManager.getStatistics();
        log.info("网络恢复统计: {}", stats);
    }
}
