package javax.net.p2p.auth;

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientQuic;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerQuic;
import javax.net.p2p.utils.RSAUtils;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthHandshakeQuicTest {

    private P2PServerQuic server;
    private P2PClientQuic client;
    private int port;
    private File yamlFile;
    private File keyDir;

    @Before
    public void setUp() throws Exception {
        port = randomUdpPort();

        Map<String, Object> serverKeys = RSAUtils.initKey();
        String serverPub = RSAUtils.getPublicKey(serverKeys);
        String serverPri = RSAUtils.getPrivateKey(serverKeys);

        Map<String, Object> clientKeys = RSAUtils.initKey();
        String clientPub = RSAUtils.getPublicKey(clientKeys);
        String clientPri = RSAUtils.getPrivateKey(clientKeys);

        String userId = javax.net.p2p.utils.SecurityUtils.sha256("user-a");

        keyDir = new File("target/auth-keys-" + port);
        keyDir.mkdirs();
        write(keyDir, "server-public.key", serverPub);
        write(keyDir, "server-private.key", serverPri);
        write(keyDir, "client-public.key", clientPub);
        write(keyDir, "client-private.key", clientPri);

        String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "keyDir: \"auth-keys-" + port + "\"\n"
                + "client:\n"
                + "  userId: \"" + userId + "\"\n"
                + "  privateKey: \"client-private.key\"\n"
                + "  serverPublicKey: \"server-public.key\"\n"
                + "server:\n"
                + "  privateKey: \"server-private.key\"\n"
                + "  clientPublicKeys:\n"
                + "    \"" + userId + "\": \"client-public.key\"\n"
                + "  allowCommands:\n"
                + "    \"" + userId + "\":\n"
                + "      - \"ECHO\"\n";

        yamlFile = new File("target/auth-test-" + port + ".yaml");
        yamlFile.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(yamlFile)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        System.setProperty("p2p.auth.yaml", yamlFile.getAbsolutePath());

        server = P2PServerQuic.getInstance(P2PServerQuic.class, port);
        Thread.sleep(800);
        client = P2PClientQuic.getInstance(P2PClientQuic.class, "127.0.0.1", port, 4096);
        Thread.sleep(400);
    }

    @After
    public void tearDown() {
        System.clearProperty("p2p.auth.yaml");
        if (client != null) {
            client.shutdown();
        }
        if (server != null) {
            server.released();
        }
        if (yamlFile != null && yamlFile.exists()) {
            yamlFile.delete();
        }
        if (keyDir != null && keyDir.exists()) {
            deleteDir(keyDir);
        }
    }

    @Test
    public void testHandshakeAndEncryptedEcho() throws Exception {
        P2PWrapper r0 = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
        assertEquals(P2PCommand.STD_ERROR, r0.getCommand());

        client.handshake();

        P2PWrapper r1 = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
        assertEquals(P2PCommand.STD_ERROR, r1.getCommand());

        client.login();

        P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
        assertEquals(P2PCommand.ECHO, resp.getCommand());
        assertTrue(String.valueOf(resp.getData()).contains("hello"));
    }

    private int randomUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void write(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
