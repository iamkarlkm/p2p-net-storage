package javax.net.p2p.udp;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientUdp;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerUdp;
import org.junit.Test;

import static org.junit.Assert.*;

public class UdpReliabilityEchoTest {

    @Test
    public void testEchoOverUdpWithReliability() throws Exception {
        P2PServerUdp server = P2PServerUdp.getInstance(P2PServerUdp.class, 0);
        int port = waitForPort(server, 5000);
        P2PClientUdp client = new P2PClientUdp(new InetSocketAddress("127.0.0.1", port));
        try {
            for (int i = 0; i < 10; i++) {
                String payload = "udp-reliability-" + i;
                P2PWrapper<String> req = P2PWrapper.build(P2PCommand.ECHO, payload);
                P2PWrapper resp = client.excute(req, 5, TimeUnit.SECONDS);
                assertNotNull(resp);
                assertEquals(P2PCommand.ECHO, resp.getCommand());
                assertEquals("Server echo -> " + payload, String.valueOf(resp.getData()));
            }
        } finally {
            try {
                client.shutdown();
            } finally {
                server.released();
            }
        }
    }

    private static int waitForPort(P2PServerUdp server, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int port = server.getPort();
            if (port > 0) {
                return port;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("UDP server not bound");
    }
}
