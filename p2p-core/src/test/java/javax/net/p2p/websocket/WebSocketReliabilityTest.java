package javax.net.p2p.websocket;

import java.net.DatagramSocket;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientWebSocket;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerWebSocket;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

@Slf4j
public class WebSocketReliabilityTest {

    P2PServerWebSocket server;
    P2PClientWebSocket client;
    int port;

    @Before
    public void setUp() throws UnknownHostException {
        System.setProperty("p2p.connection.fail.wait.ms", "1000");
        System.setProperty("p2p.ws.reliability.dropRate", "0.3");
        System.setProperty("p2p.ws.reliability.ackTimeoutMs", "50");
        System.setProperty("p2p.ws.reliability.tickMs", "20");
        System.setProperty("p2p.ws.reliability.maxRetries", "100");
        try (DatagramSocket socket = new DatagramSocket(0)) {
            port = socket.getLocalPort();
        } catch (Exception e) {
            port = 18089;
        }
        server = P2PServerWebSocket.getInstance(P2PServerWebSocket.class, port);
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
        }
        client = P2PClientWebSocket.getInstance(P2PClientWebSocket.class, "127.0.0.1", port, 4096);
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

    @Test
    public void testBasicMessageSending() throws Exception {
        P2PWrapper<String> message = P2PWrapper.build(1, P2PCommand.HEART_PING);
        P2PWrapper response = client.excute(message);
        assertNotNull(response);
        assertEquals(P2PCommand.HEART_PONG, response.getCommand());
    }

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
        assertTrue(allDelivered);
    }
}
