
package javax.net.p2p.quic;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.net.DatagramSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerQuic;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * QUIC协议可靠性机制测试
 *
 * 测试范围：
 * 1. 基本消息发送和接收
 * 2. 大并发消息测试
 * 3. 异步回调测试
 *
 * @author karl
 */
@Slf4j
public class QuicReliabilityTest {

    P2PServerQuic server;
    P2PClientQuic client;
    int port;

    @Before
    public void setUp() throws UnknownHostException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        } catch (Exception e) {
            port = 10087;
        }
        server = P2PServerQuic.getInstance(P2PServerQuic.class, port);
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
        }
        client = P2PClientQuic.getInstance(P2PClientQuic.class, "127.0.0.1", port, 4096);
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
        }
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.released();
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
        P2PWrapper<String> message = P2PWrapper.build(1, P2PCommand.HEART_PING);

        // 模拟发送消息
        P2PWrapper response = client.excute(message);
        assertNotNull("Response should not be null", response);
        assertEquals("Response command should be HEART_PONG", P2PCommand.HEART_PONG, response.getCommand());
    }

    /**
     * 测试大并发消息发送
     */
    @Test
    public void testConcurrentMessageSending() throws Exception {
        int messageCount = 100;
        CountDownLatch latch = new CountDownLatch(messageCount);

        for (int i = 0; i < messageCount; i++) {
            int seq = i;
            new Thread(() -> {
                try {
                    P2PWrapper<String> message = P2PWrapper.build(seq, P2PCommand.HEART_PING, "Ping " + seq);
                    P2PWrapper response = client.excute(message);
                    if (response != null && response.getCommand() == P2PCommand.HEART_PONG) {
                        latch.countDown();
                    }
                } catch (Exception e) {
                    log.error("Error sending message", e);
                }
            }).start();
        }

        boolean allDelivered = latch.await(30, TimeUnit.SECONDS);
        assertTrue("All messages should be processed successfully", allDelivered);
    }

}
