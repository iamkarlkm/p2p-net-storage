package javax.net.p2p.cache;

import java.io.File;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import javax.net.p2p.cache.client.P2PQuicCacheClient;
import javax.net.p2p.server.P2PServerQuic;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class P2PDistributedLockQuicTest {

    private P2PServerQuic server;
    private P2PQuicCacheClient c1;
    private P2PQuicCacheClient c2;
    private int port;
    private File rootDir;

    @Before
    public void setUp() throws Exception {
        port = randomUdpPort();
        rootDir = new File("target/test-p2p-lock-quic-" + port);
        if (rootDir.exists()) {
            deleteRecursively(rootDir);
        }
        rootDir.mkdirs();
        System.setProperty("p2p.cache.rootDir", rootDir.getAbsolutePath());
        System.setProperty("p2p.cache.maxEntries", "1024");
        System.setProperty("p2p.cache.defaultTtlMillis", "0");

        server = P2PServerQuic.getInstance(P2PServerQuic.class, port);
        Thread.sleep(800);
        c1 = new P2PQuicCacheClient(new InetSocketAddress("127.0.0.1", port), 4096);
        c2 = new P2PQuicCacheClient(new InetSocketAddress("127.0.0.1", port), 4096);
        Thread.sleep(400);
    }

    @After
    public void tearDown() {
        if (c1 != null) {
            c1.shutdown();
        }
        if (c2 != null) {
            c2.shutdown();
        }
        if (server != null) {
            server.released();
        }
        if (rootDir != null && rootDir.exists()) {
            deleteRecursively(rootDir);
        }
    }

    @Test
    public void testMutualExclusionAndSafeRelease() throws Exception {
        String key = "lock:order:1";
        String t1 = c1.tryLock(key, 1000);
        assertNotNull(t1);

        String t2 = c2.tryLock(key, 1000);
        assertNull(t2);

        assertFalse(c2.unlock(key, "wrong-token"));
        assertTrue(c2.unlock(key, t1));

        assertFalse(c1.unlock(key, t1));

        String t3 = c2.tryLock(key, 1000);
        assertNotNull(t3);
        assertTrue(c2.unlock(key, t3));
    }

    @Test
    public void testTtlExpiry() throws Exception {
        String key = "lock:ttl";
        String t1 = c1.tryLock(key, 200);
        assertNotNull(t1);

        Thread.sleep(350);

        String t2 = c2.tryLock(key, 500);
        assertNotNull(t2);
        assertTrue(c2.unlock(key, t2));
    }

    @Test
    public void testRenew() throws Exception {
        String key = "lock:renew";
        String t1 = c1.tryLock(key, 500);
        assertNotNull(t1);

        Thread.sleep(200);
        assertTrue(c1.renewLock(key, t1, 800));

        Thread.sleep(400);
        assertNull(c2.tryLock(key, 500));

        Thread.sleep(550);
        assertNotNull(c2.tryLock(key, 500));
    }

    private int randomUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursively(f);
                }
            }
        }
        file.delete();
    }
}
