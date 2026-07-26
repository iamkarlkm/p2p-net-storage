package javax.net.p2p.auth;

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.client.P2PClientTcp;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.P2PServerTcp;
import javax.net.p2p.server.handler.HandServerHandler;
import javax.net.p2p.server.handler.LoginServerHandler;
import javax.net.p2p.utils.RSAUtils;
import org.junit.Test;
import static org.junit.Assert.*;

public class AuthHandshakeModesTcpTest {

    @Test
    public void testModes() throws Exception {
        testOneMode("PLAIN");
        testOneMode("CLIENT_RANDOM");
        testOneMode("SERVER_RANDOM");
        testOneMode("KEYFILE");
    }

    private void testOneMode(String cryptoMode) throws Exception {
        int port = randomTcpPort();
        File yamlFile = null;
        File keyDir = null;
        P2PServerTcp server = null;
        Thread serverThread = null;
        P2PClientTcp client = null;
        try {
            Map<String, Object> serverKeys = RSAUtils.initKey();
            String serverPub = RSAUtils.getPublicKey(serverKeys);
            String serverPri = RSAUtils.getPrivateKey(serverKeys);

            Map<String, Object> clientKeys = RSAUtils.initKey();
            String clientPub = RSAUtils.getPublicKey(clientKeys);
            String clientPri = RSAUtils.getPrivateKey(clientKeys);

            String userId = javax.net.p2p.utils.SecurityUtils.sha256("user-a");

            keyDir = new File("target/auth-keys-tcp-" + cryptoMode + "-" + port);
            keyDir.mkdirs();
            write(keyDir, "server-public.key", serverPub);
            write(keyDir, "server-private.key", serverPri);
            write(keyDir, "client-public.key", clientPub);
            write(keyDir, "client-private.key", clientPri);
            if ("KEYFILE".equals(cryptoMode)) {
                writeBytes(keyDir, "xor.key", randomBytes(4096));
            }

            String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "cryptoMode: \"" + cryptoMode + "\"\n"
                + ("KEYFILE".equals(cryptoMode) ? "xorKeyFile: \"xor.key\"\n" : "")
                + "keyDir: \"auth-keys-tcp-" + cryptoMode + "-" + port + "\"\n"
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

            yamlFile = new File("target/auth-test-tcp-" + cryptoMode + "-" + port + ".yaml");
            yamlFile.getParentFile().mkdirs();
            try (FileOutputStream out = new FileOutputStream(yamlFile)) {
                out.write(yaml.getBytes(StandardCharsets.UTF_8));
            }
            System.setProperty("p2p.auth.yaml", yamlFile.getAbsolutePath());
            resetAuthCaches();

            server = new P2PServerTcp(port);
            serverThread = new Thread(server::start, "p2p-tcp-auth-" + port);
            serverThread.setDaemon(true);
            serverThread.start();
            Thread.sleep(800);

            client = new P2PClientTcp(new InetSocketAddress("127.0.0.1", port));
            client.newSendMesageExecutorToQueue();
            Thread.sleep(200);

            P2PWrapper r0 = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
            assertEquals(P2PCommand.STD_ERROR, r0.getCommand());

            client.handshake();

            P2PWrapper r1 = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
            assertEquals(P2PCommand.STD_ERROR, r1.getCommand());

            client.login();

            P2PWrapper resp = client.excute(P2PWrapper.build(P2PCommand.ECHO, "hello"), 15, TimeUnit.SECONDS);
            assertEquals(P2PCommand.ECHO, resp.getCommand());
            assertTrue(String.valueOf(resp.getData()).contains("hello"));
        } finally {
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
            if (client != null) {
                client.shutdown();
            }
            if (server != null) {
                server.stop();
            }
            if (serverThread != null) {
                serverThread.interrupt();
                serverThread.join(2000);
            }
            if (yamlFile != null && yamlFile.exists()) {
                yamlFile.delete();
            }
            if (keyDir != null && keyDir.exists()) {
                deleteDir(keyDir);
            }
        }
    }

    private void resetAuthCaches() throws Exception {
        setStatic(HandServerHandler.class, "CONFIG", null);
        setStatic(LoginServerHandler.class, "CONFIG", null);
        setStatic(AuthEnforcer.class, "CONFIG", null);
        setStatic(AuthEnforcer.class, "CONFIG_SOURCE", null);
    }

    private void setStatic(Class<?> c, String name, Object v) throws Exception {
        var f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    private int randomTcpPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private byte[] randomBytes(int len) {
        return javax.net.p2p.auth.utils.AuthCrypto.randomBytes(len);
    }

    private void write(File dir, String name, String content) throws Exception {
        writeBytes(dir, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(File dir, String name, byte[] bytes) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
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
