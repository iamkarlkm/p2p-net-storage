package javax.net.p2p.cache;

import java.io.File;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.p2p.cache.client.P2PQuicCacheClient;
import javax.net.p2p.cache.model.CacheBytesEntry;
import javax.net.p2p.cache.model.CacheStringEntry;
import javax.net.p2p.server.P2PServerQuic;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class P2PCacheQuicTest {

    private P2PServerQuic server;
    private P2PQuicCacheClient client;
    private int port;
    private File rootDir;

    @Before
    public void setUp() throws Exception {
        port = randomUdpPort();
        rootDir = new File("target/test-p2p-cache-quic-" + port);
        if (rootDir.exists()) {
            deleteRecursively(rootDir);
        }
        rootDir.mkdirs();
        System.setProperty("p2p.cache.rootDir", rootDir.getAbsolutePath());
        System.setProperty("p2p.cache.maxEntries", "1024");
        System.setProperty("p2p.cache.defaultTtlMillis", "0");

        server = P2PServerQuic.getInstance(P2PServerQuic.class, port);
        Thread.sleep(600);
        client = new P2PQuicCacheClient("127.0.0.1", port, 4096);
        Thread.sleep(300);
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        if (server != null) {
            server.released();
        }
        if (rootDir != null && rootDir.exists()) {
            deleteRecursively(rootDir);
        }
    }

    @Test
    public void testStringOps() throws Exception {
        client.setString("k1", "v1", 0);
        assertEquals("v1", client.getString("k1"));
        assertTrue(client.existsString("k1"));

        assertEquals(1, client.incrByString("counter", 1));
        assertEquals(6, client.incrByString("counter", 5));
        assertEquals(4, client.decrByString("counter", 2));

        client.msetString(new ArrayList<>(Arrays.asList(new CacheStringEntry("mk1", "mv1"), new CacheStringEntry("mk2", "mv2"))), 0);
        List<CacheStringEntry> entries = client.mgetString(new ArrayList<>(Arrays.asList("mk1", "mk2", "mk3")));
        assertEquals(3, entries.size());
        assertEquals("mv1", entries.get(0).getValue());
        assertEquals("mv2", entries.get(1).getValue());
        assertNull(entries.get(2).getValue());
    }

    @Test
    public void testTtlAndDel() throws Exception {
        client.setString("ttl1", "v", 120);
        assertEquals("v", client.getString("ttl1"));
        assertTrue(client.ttlString("ttl1") > 0);
        Thread.sleep(180);
        assertNull(client.getString("ttl1"));
        assertEquals(-2, client.ttlString("ttl1"));

        client.setString("kdel", "v", 0);
        client.delString("kdel");
        assertFalse(client.existsString("kdel"));
    }

    @Test
    public void testBytesOps() throws Exception {
        client.setBytes("b1", new byte[]{1, 2, 3}, 0);
        assertArrayEquals(new byte[]{1, 2, 3}, client.getBytes("b1"));

        assertEquals(1, client.incrByBytes("bcounter", 1));
        assertEquals(3, client.incrByBytes("bcounter", 2));

        client.msetBytes(new ArrayList<>(Arrays.asList(
                new CacheBytesEntry("mb1", new byte[]{9}),
                new CacheBytesEntry("mb2", "hello".getBytes(StandardCharsets.UTF_8))
        )), 0);
        List<CacheBytesEntry> entries = client.mgetBytes(new ArrayList<>(Arrays.asList("mb1", "mb2", "mb3")));
        assertEquals(3, entries.size());
        assertArrayEquals(new byte[]{9}, entries.get(0).getValue());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), entries.get(1).getValue());
        assertNull(entries.get(2).getValue());
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
