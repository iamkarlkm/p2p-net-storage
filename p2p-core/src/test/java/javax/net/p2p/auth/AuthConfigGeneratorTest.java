package javax.net.p2p.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.net.p2p.utils.RSAUtils;
import javax.net.p2p.utils.SecurityUtils;
import org.junit.Test;

public class AuthConfigGeneratorTest {

    @Test
    public void generate() throws Exception {
        Map<String, Object> serverKeys = RSAUtils.initKey();
        String serverPub = RSAUtils.getPublicKey(serverKeys);
        String serverPri = RSAUtils.getPrivateKey(serverKeys);

        Map<String, Object> clientKeys = RSAUtils.initKey();
        String clientPub = RSAUtils.getPublicKey(clientKeys);
        String clientPri = RSAUtils.getPrivateKey(clientKeys);

        String user = "user-a";
        String userId = SecurityUtils.sha256(user);

        String clientYaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "keyDir: \"\"\n"
                + "client:\n"
                + "  user: \"" + user + "\"\n"
                + "  userId: \"" + userId + "\"\n"
                + "  privateKey: \"" + clientPri + "\"\n"
                + "  serverPublicKey: \"" + serverPub + "\"\n";

        String serverYaml = ""
                + "enabled: true\n"
                + "xorKeyLength: 4096\n"
                + "keyDir: \"\"\n"
                + "server:\n"
                + "  privateKey: \"" + serverPri + "\"\n"
                + "  clientPublicKeys:\n"
                + "    \"" + userId + "\": \"" + clientPub + "\"\n"
                + "  allowCommands:\n"
                + "    \"" + userId + "\":\n"
                + "      - \"*\"\n";

        Path dir = Paths.get("target", "generated-auth");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("auth-client.yaml"), clientYaml, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("auth-server.yaml"), serverYaml, StandardCharsets.UTF_8);

        System.out.println("Generated userId: " + userId);
        System.out.println("Client config: " + dir.resolve("auth-client.yaml").toAbsolutePath());
        System.out.println("Server config: " + dir.resolve("auth-server.yaml").toAbsolutePath());
    }
}

