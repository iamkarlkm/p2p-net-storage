package javax.net.p2p.udp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.model.P2PWrapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * UDP可靠性机制测试
 * 
 * 测试范围：
 * 1. 基本消息发送和接收
 * 2. ACK确认机制
 * 3. 超时重传
 * 4. 乱序消息重组
 * 5. 流量控制
 * 6. 拥塞控制
 * 
 * 测试方法：
 * - 使用模拟的Netty Channel和消息
 * - 测试各种网络异常场景
 * - 验证统计信息正确性
 * 
 * @author karl
 */
@Slf4j
public class UdpReliabilityTest {
    
    private UdpReliabilityManager reliabilityManager;
    private TestUdpReliabilityManager testManager;
    private P2PClientUdp client;
    
    @Before
    public void setUp() throws UnknownHostException {
        client = P2PClientUdp.getInstance(P2PClientUdp.class);
        reliabilityManager = new TestUdpReliabilityManager();
        testManager = (TestUdpReliabilityManager) reliabilityManager;
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
     * 测试基本消息发送
     */
    @Test
    public void testBasicMessageSending() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8080);
        P2PWrapper<String> message = P2PWrapper.build(0, P2PCommand.ECHO, "Hello, UDP!");
        
        // 模拟发送消息
        int seq = reliabilityManager.sendReliableMessage(client, message);
        assertTrue("Sequence number should be non-negative", seq >= 0);
        
        // 模拟接收ACK
        reliabilityManager.processAck(seq, remoteAddress);
        
        // 验证统计信息
        String stats = reliabilityManager.getStatistics();
        assertTrue("Statistics should contain sent count", stats.contains("Sent=1"));
        assertTrue("Statistics should contain ACKed count", stats.contains("ACKed=1"));
    }
    
    /**
     * 测试ACK确认机制
     */
    @Test
    public void testAckConfirmation() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8081);
        
        // 发送多个消息
        int seq1 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 1"));
        int seq2 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 2"));
        
        // 确认第一个消息
        reliabilityManager.processAck(seq1, remoteAddress);
        
        // 验证第一个消息已确认，第二个仍在等待
        assertTrue("Message 1 should be acknowledged", 
            testManager.isMessageAcked(seq1));
        assertFalse("Message 2 should not be acknowledged", 
            testManager.isMessageAcked(seq2));
        
        // 确认第二个消息
        reliabilityManager.processAck(seq2, remoteAddress);
        assertTrue("Message 2 should now be acknowledged", 
            testManager.isMessageAcked(seq2));
    }
    
    /**
     * 测试超时重传
     */
    @Test
    public void testTimeoutRetransmission() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8082);
        P2PWrapper<String> message = P2PWrapper.build(0, P2PCommand.ECHO, "Test Retransmission");
        
        int seq = reliabilityManager.sendReliableMessage(client, message);
        
        // 等待超时（设置较短的超时时间以便测试）
        Thread.sleep(1500);
        
        // 验证消息状态
        assertTrue("Message should be in retransmitting or timed out state", 
            testManager.isMessageRetransmitting(seq) || testManager.isMessageTimedOut(seq));
        
        // 确认消息
        reliabilityManager.processAck(seq, remoteAddress);
        
        // 验证重传统计
        String stats = reliabilityManager.getStatistics();
        assertTrue("Statistics should reflect retransmissions", 
            stats.contains("Retransmitted="));
    }
    
    /**
     * 测试乱序消息重组
     */
    @Test
    public void testOutOfOrderReassembly() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8083);
        
        // 重置应用层消息计数器
        testManager.resetMessageCount();
        
        // 模拟接收乱序消息
        ByteBuf data1 = Unpooled.copiedBuffer("Message 3".getBytes());
        ByteBuf data2 = Unpooled.copiedBuffer("Message 1".getBytes());
        ByteBuf data3 = Unpooled.copiedBuffer("Message 2".getBytes());
        
        // 先接收seq=3的消息（乱序）
        reliabilityManager.processDataMessage(3, data1, remoteAddress);
        
        // 再接收seq=1的消息
        reliabilityManager.processDataMessage(1, data2, remoteAddress);
        
        // 检查消息交付：seq=1应该被交付
        assertEquals("Message with seq=1 should be delivered", 1, 
            testManager.getDeliveredMessageCount());
        assertTrue("Message with seq=1 should be delivered", 
            testManager.isMessageDelivered(1));
        
        // 接收seq=2的消息
        reliabilityManager.processDataMessage(2, data3, remoteAddress);
        
        // 检查消息交付：seq=2应该被交付
        assertEquals("Message with seq=2 should be delivered", 3, 
            testManager.getDeliveredMessageCount());
        assertTrue("Message with seq=2 should be delivered", 
            testManager.isMessageDelivered(2));
        
        assertEquals("Message with seq=3 should be delivered after seq=2 arrives", 3,
            testManager.getDeliveredMessageCount());
        assertTrue("Message with seq=3 should be delivered after seq=2 arrives",
            testManager.isMessageDelivered(3));
    }
    
    /**
     * 测试流量控制（发送窗口）
     */
    @Test
    public void testFlowControl() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8084);
        
        // 设置较小的窗口大小
        reliabilityManager.setWindowSize(2);
        
        // 发送第一个消息（应该成功）
        int seq1 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 1"));
        assertTrue("First message should be sent", seq1 >= 0);
        
        // 发送第二个消息（应该成功，窗口未满）
        int seq2 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 2"));
        assertTrue("Second message should be sent", seq2 >= 0);
        
        // 尝试发送第三个消息（应该失败，窗口已满）
        int seq3 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 3"));
        assertEquals("Third message should fail with window full", -1, seq3);
        
        // 确认第一个消息
        reliabilityManager.processAck(seq1, remoteAddress);
        
        // 现在窗口应该有空间了，尝试再次发送第三个消息
        seq3 = reliabilityManager.sendReliableMessage(client, 
            P2PWrapper.build(0, P2PCommand.ECHO, "Message 3"));
        assertTrue("Third message should now be sent after ACK", seq3 >= 0);
    }
    
    /**
     * 测试拥塞控制
     */
    @Test
    public void testCongestionControl() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8085);
        
        // 发送多个消息，模拟网络拥塞
        for (int i = 0; i < 10; i++) {
            int seq = reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Message " + i));
            
            // 模拟一定的丢包率
            if (i % 3 == 0) {
                // 不发送ACK，模拟丢包
                // 拥塞控制应该检测到超时并降低发送速率
            } else {
                reliabilityManager.processAck(seq, remoteAddress);
            }
            
            Thread.sleep(100); // 模拟网络延迟
        }
        
        // 验证拥塞控制信息
        String ccInfo = testManager.getCongestionInfo(remoteAddress);
        log.info("Congestion Control Info: {}", ccInfo);
        
        // 拥塞控制应该根据丢包率调整窗口
        assertTrue("Congestion control should have information", 
            ccInfo.contains("cwnd=") && ccInfo.contains("lossRate="));
    }
    
    /**
     * 测试重复消息处理
     */
    @Test
    public void testDuplicateMessageHandling() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8086);
        
        // 模拟接收重复消息
        ByteBuf data1 = Unpooled.copiedBuffer("Original Message".getBytes());
        ByteBuf data2 = Unpooled.copiedBuffer("Duplicate Message".getBytes()); // 假设是同一消息的重传
        
        // 接收原始消息
        reliabilityManager.processDataMessage(1, data1, remoteAddress);
        
        // 再次接收相同的序列号（重复消息）
        reliabilityManager.processDataMessage(1, data2, remoteAddress);
        
        // 验证重复消息统计
        String stats = reliabilityManager.getStatistics();
        assertTrue("Statistics should reflect duplicate handling", 
            stats.contains("Duplicated="));
    }
    
  
    
    /**
     * 测试性能基准
     */
    @Test(timeout = 10000)
    public void testPerformanceBenchmark() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8089);
        int messageCount = 100;
        CountDownLatch latch = new CountDownLatch(messageCount);
        
        // 设置消息交付回调
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        // 发送多个消息
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < messageCount; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Message " + i));
        }
        
        // 确认所有消息
        for (int i = 0; i < messageCount; i++) {
            reliabilityManager.processAck(i, remoteAddress);
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(5, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("All messages should be delivered within timeout", allDelivered);
        
        long totalTime = endTime - startTime;
        double messagesPerSecond = (messageCount * 1000.0) / totalTime;
        
        log.info("Performance Benchmark: {} messages in {}ms = {:.2f} msg/sec", 
                 messageCount, totalTime, messagesPerSecond);
        
        // 基本性能要求：至少1000 msg/sec
        assertTrue("Performance should be at least 1000 msg/sec", 
                   messagesPerSecond >= 1000.0);
    }
    
    // ==============================================
    // 网络异常测试用例
    // ==============================================
    
    /**
     * 测试轻微网络丢包场景（1%丢包率）
     */
    @Test(timeout = 15000)
    public void testPacketLossScenario_Light() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8090);
        
        // 模拟1%丢包率
        testManager.setPacketLossRate(0.01);
        
        CountDownLatch latch = new CountDownLatch(50);
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        // 发送50个消息
        for (int i = 0; i < 50; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Test packet " + i));
            
            // 随机确认一些消息，模拟丢包
            if (Math.random() > 0.01) {
                reliabilityManager.processAck(i, remoteAddress);
            }
        }
        
        // 等待所有消息被交付（可能需要重传）
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("All messages should be delivered despite 1% packet loss", allDelivered);
        
        String stats = reliabilityManager.getStatistics();
        log.info("Light packet loss test statistics: {}", stats);
        assertTrue("Should have retransmissions due to packet loss", 
                   stats.contains("Retransmissions="));
    }
    
    /**
     * 测试中等网络丢包场景（5%丢包率）
     */
    @Test(timeout = 20000)
    public void testPacketLossScenario_Medium() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8091);
        
        // 模拟5%丢包率
        testManager.setPacketLossRate(0.05);
        
        CountDownLatch latch = new CountDownLatch(30);
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        // 发送30个消息
        for (int i = 0; i < 30; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Test packet " + i));
            
            // 随机确认（95%确认率）
            if (Math.random() > 0.05) {
                reliabilityManager.processAck(i, remoteAddress);
            }
        }
        
        // 等待所有消息被交付（可能需要多次重传）
        boolean allDelivered = latch.await(15, TimeUnit.SECONDS);
        
        assertTrue("All messages should be delivered despite 5% packet loss", allDelivered);
        
        String stats = reliabilityManager.getStatistics();
        log.info("Medium packet loss test statistics: {}", stats);
        assertTrue("Should have more retransmissions due to higher packet loss", 
                   stats.contains("Retransmissions="));
    }
    
    /**
     * 测试网络延迟场景（100ms平均延迟）
     */
    @Test(timeout = 20000)
    public void testNetworkDelayScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8092);
        
        // 模拟100ms平均延迟
        testManager.setAverageDelayMs(100);
        
        CountDownLatch latch = new CountDownLatch(20);
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送20个消息
        for (int i = 0; i < 20; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Delayed packet " + i));
            
            // 延迟确认（模拟网络延迟）
            final int i0 = i;
            new Thread(() -> {
                try {
                    Thread.sleep(100 + (int)(Math.random() * 50)); // 100-150ms延迟
                    reliabilityManager.processAck(i0, remoteAddress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("All messages should be delivered despite network delay", allDelivered);
        
        long totalTime = endTime - startTime;
        log.info("Network delay test completed in {}ms (expected ~2000ms)", totalTime);
        
        // 验证延迟不会导致消息丢失
        String stats = reliabilityManager.getStatistics();
        assertFalse("Should not have timeout failures due to network delay", 
                   stats.contains("TimeoutFailures="));
    }
    
    /**
     * 测试网络抖动场景（延迟变化）
     */
    @Test(timeout = 15000)
    public void testNetworkJitterScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8093);
        
        // 模拟网络抖动：50ms平均延迟，±30ms抖动
        testManager.setAverageDelayMs(50);
        testManager.setJitterEnabled(true);
        testManager.setJitterRangeMs(30);
        
        CountDownLatch latch = new CountDownLatch(25);
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        // 发送25个消息
        for (int i = 0; i < 25; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Jittery packet " + i));
            
            // 随机延迟确认（模拟网络抖动）
            final int seq = i;
            new Thread(() -> {
                try {
                    // 随机延迟：20-80ms
                    int delay = 20 + (int)(Math.random() * 60);
                    Thread.sleep(delay);
                    reliabilityManager.processAck(seq, remoteAddress);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("All messages should be delivered despite network jitter", allDelivered);
        
        String stats = reliabilityManager.getStatistics();
        log.info("Network jitter test statistics: {}", stats);
    }
    
    /**
     * 测试重复包场景
     */
    @Test(timeout = 10000)
    public void testDuplicatePacketsScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8094);
        
        // 模拟重复包
        testManager.setDuplicatePacketEnabled(true);
        
        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger deliveryCount = new AtomicInteger(0);
        
        testManager.setMessageDeliveryCallback((seq, data) -> {
            deliveryCount.incrementAndGet();
            latch.countDown();
        });
        
        // 发送10个消息，每个都发送多次（模拟网络重复）
        for (int i = 0; i < 10; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Duplicate test " + i));
            
            // 立即确认
            reliabilityManager.processAck(i, remoteAddress);
            
            // 模拟重复包（发送额外的相同消息）
            if (i % 3 == 0) { // 每3个消息重复一次
                reliabilityManager.processDataMessage(i, 
                    Unpooled.copiedBuffer(("Duplicate test " + i).getBytes()), 
                    remoteAddress);
            }
        }
        
        // 等待所有原始消息被交付
        boolean allDelivered = latch.await(5, TimeUnit.SECONDS);
        
        assertTrue("All messages should be delivered", allDelivered);
        
        // 验证重复包被正确处理（不会导致重复交付）
        assertEquals("Should have exactly 10 deliveries despite duplicate packets", 
                     10, deliveryCount.get());
        
        String stats = reliabilityManager.getStatistics();
        assertTrue("Should indicate duplicate packets were handled", 
                   stats.contains("Duplicated="));
    }
    
    /**
     * 测试乱序包场景
     */
    @Test(timeout = 15000)
    public void testOutOfOrderPacketsScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8095);
        
        // 启用乱序处理
        testManager.setOutOfOrderEnabled(true);
        
        CountDownLatch latch = new CountDownLatch(15);
        AtomicInteger lastDeliveredSeq = new AtomicInteger(-1);
        
        testManager.setMessageDeliveryCallback((seq, data) -> {
            // 验证消息按顺序交付（即使接收乱序）
            assertTrue("Messages should be delivered in order", 
                      seq > lastDeliveredSeq.get());
            lastDeliveredSeq.set(seq);
            latch.countDown();
        });
        
        List<Integer> seqList = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            int seq = reliabilityManager.sendReliableMessage(client,
                P2PWrapper.build(0, P2PCommand.ECHO, "Out-of-order " + i));
            seqList.add(seq);
        }

        java.util.Collections.shuffle(seqList);
        for (int seq : seqList) {
            reliabilityManager.processAck(seq, remoteAddress);
        }
        
        // 等待所有消息按正确顺序被交付
        boolean allDelivered = latch.await(10, TimeUnit.SECONDS);
        
        assertTrue("All messages should be delivered in correct order", allDelivered);
        
        String stats = reliabilityManager.getStatistics();
        log.info("Out-of-order test statistics: {}", stats);
    }
    
    /**
     * 测试带宽限制场景
     */
    @Test(timeout = 30000)
    public void testBandwidthLimitationScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8096);
        
        // 模拟带宽限制：10KB/s
        testManager.setBandwidthLimitBps(10 * 1024);
        
        CountDownLatch latch = new CountDownLatch(50);
        testManager.setMessageDeliveryCallback((seq, data) -> {
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送50个消息（每个约100字节）
        for (int i = 0; i < 50; i++) {
            String message = String.format("Bandwidth test %03d - %s", i, 
                "x".repeat(80)); // 约100字节的消息
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, message));
            
            // 确认
            reliabilityManager.processAck(i, remoteAddress);
        }
        
        // 等待所有消息被交付（带宽限制下可能需要更长时间）
        boolean allDelivered = latch.await(25, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("All messages should be delivered despite bandwidth limit", allDelivered);
        
        long totalTime = endTime - startTime;
        int totalBytes = 50 * 100; // 约5KB
        double actualBandwidth = (totalBytes * 1000.0) / totalTime;
        
        log.info("Bandwidth test: {} bytes in {}ms = {:.2f} B/s (limit: {} B/s)", 
                 totalBytes, totalTime, actualBandwidth, 10 * 1024);
        
        // 验证流量控制生效
        String stats = reliabilityManager.getStatistics();
        assertTrue("Should indicate traffic control was active", 
                   stats.contains("TrafficControl="));
    }
    
    /**
     * 测试组合网络异常场景（高丢包率 + 高延迟 + 抖动）
     */
    @Test(timeout = 30000)
    public void testCombinedNetworkAnomaliesScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8097);
        
        // 模拟恶劣网络环境
        testManager.setPacketLossRate(0.10); // 10%丢包率
        testManager.setAverageDelayMs(200);  // 200ms平均延迟
        testManager.setJitterEnabled(true);  // 启用抖动
        testManager.setJitterRangeMs(100);   // ±100ms抖动
        
        CountDownLatch latch = new CountDownLatch(20);
        AtomicInteger deliveredCount = new AtomicInteger(0);
        
        testManager.setMessageDeliveryCallback((seq, data) -> {
            deliveredCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 发送20个消息
        for (int i = 0; i < 20; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Combined anomaly test " + i));
            
            // 随机确认（模拟丢包）
            if (Math.random() > 0.10) {
                // 随机延迟确认（模拟网络延迟和抖动）
                final int seq = i;
                new Thread(() -> {
                    try {
                        // 随机延迟：100-400ms
                        int delay = 100 + (int)(Math.random() * 300);
                        Thread.sleep(delay);
                        reliabilityManager.processAck(seq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
        }
        
        // 等待所有消息被交付（可能需要多次重传）
        boolean allDelivered = latch.await(25, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("All messages should be delivered despite combined network anomalies", 
                   allDelivered);
        
        long totalTime = endTime - startTime;
        log.info("Combined anomalies test: 20 messages in {}ms (expected >4000ms)", totalTime);
        
        String stats = reliabilityManager.getStatistics();
        log.info("Combined anomalies test statistics: {}", stats);
        
        // 验证重传机制正常工作
        assertTrue("Should have significant retransmissions due to packet loss", 
                   stats.contains("Retransmissions="));
    }
    
    /**
     * 测试长时间运行稳定性（模拟持续的网络异常）
     */
    @Test(timeout = 45000)
    public void testLongRunningStabilityScenario() throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 8098);
        
        // 中等网络条件：5%丢包，100ms延迟
        testManager.setPacketLossRate(0.05);
        testManager.setAverageDelayMs(100);
        
        int totalMessages = 100;
        CountDownLatch latch = new CountDownLatch(totalMessages);
        AtomicInteger successCount = new AtomicInteger(0);
        
        testManager.setMessageDeliveryCallback((seq, data) -> {
            successCount.incrementAndGet();
            latch.countDown();
        });
        
        long startTime = System.currentTimeMillis();
        
        // 持续发送100个消息
        for (int i = 0; i < totalMessages; i++) {
            reliabilityManager.sendReliableMessage(client, 
                P2PWrapper.build(0, P2PCommand.ECHO, "Long running test " + i));
            
            // 随机确认（95%确认率）
            if (Math.random() > 0.05) {
                final int seq = i;
                new Thread(() -> {
                    try {
                        // 随机延迟：50-200ms
                        Thread.sleep(50 + (int)(Math.random() * 150));
                        reliabilityManager.processAck(seq, remoteAddress);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
            }
            
            // 小延迟避免洪水式发送
            if (i % 10 == 0) {
                Thread.sleep(50);
            }
        }
        
        // 等待所有消息被交付
        boolean allDelivered = latch.await(40, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();
        
        assertTrue("All 100 messages should be delivered in long running test", allDelivered);
        assertEquals("Should have 100 successful deliveries", totalMessages, successCount.get());
        
        long totalTime = endTime - startTime;
        double throughput = (totalMessages * 1000.0) / totalTime;
        
        log.info("Long running stability test: {} messages in {}ms = {:.2f} msg/sec", 
                 totalMessages, totalTime, throughput);
        
        // 验证统计信息
        String stats = reliabilityManager.getStatistics();
        log.info("Long running stability statistics: {}", stats);
        
        // 验证没有资源泄漏（通过PendingCount检查）
        assertEquals("All pending messages should be cleared", 0, 
                     reliabilityManager.getPendingCount());
    }
}
