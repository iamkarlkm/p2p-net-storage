package javax.net.p2p.server;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.net.p2p.utils.RSAUtils;
import javax.net.p2p.utils.SecurityUtils;

public final class P2PServerWebSocketAuthDevMain {
    private P2PServerWebSocketAuthDevMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 18089;
        int magic = args.length >= 2 ? Integer.decode(args[1]) : -252702961;

        Map<String, Object> serverKeys = RSAUtils.initKey();
        String serverPub = RSAUtils.getPublicKey(serverKeys);
        String serverPri = RSAUtils.getPrivateKey(serverKeys);

        Map<String, Object> clientKeys = RSAUtils.initKey();
        String clientPub = RSAUtils.getPublicKey(clientKeys);
        String clientPri = RSAUtils.getPrivateKey(clientKeys);

        String userId = SecurityUtils.sha256("example-user-id");

        File keyDir = new File("target/ws-auth-keys-" + port);
        keyDir.mkdirs();
        write(keyDir, "server-public.key", serverPub);
        write(keyDir, "server-private.key", serverPri);
        write(keyDir, "client-public.key", clientPub);
        write(keyDir, "client-private.key", clientPri);

        String yaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "keyDir: \"ws-auth-keys-" + port + "\"\n"
                + "server:\n"
                + "  privateKey: \"server-private.key\"\n"
                + "  clientPublicKeys:\n"
                + "    \"" + userId + "\": \"client-public.key\"\n"
                + "  allowCommands:\n"
                + "    \"" + userId + "\":\n"
                + "      - \"RPC_DISCOVER\"\n"
                + "      - \"RPC_HEALTH\"\n"
                + "      - \"RPC_UNARY\"\n"
                + "      - \"RPC_STREAM\"\n"
                + "      - \"RPC_EVENT\"\n"
                + "      - \"RPC_CONTROL\"\n";

        File yamlFile = new File("target/ws-auth-" + port + ".yaml");
        try (FileOutputStream out = new FileOutputStream(yamlFile)) {
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
        }
        System.setProperty("p2p.auth.yaml", yamlFile.getAbsolutePath());

        P2PServerWebSocket.getInstance(P2PServerWebSocket.class, port, 4096, 2, magic);

        System.out.println("WS_URL=ws://127.0.0.1:" + port + P2PServerWebSocket.DEFAULT_PATH);
        System.out.println("MAGIC=" + magic);
        System.out.println("USER_ID=" + userId);
        System.out.println("CLIENT_PRIVATE_KEY_PATH=" + new File(keyDir, "client-private.key").getAbsolutePath());
        new CountDownLatch(1).await();
    }

    private static void write(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
