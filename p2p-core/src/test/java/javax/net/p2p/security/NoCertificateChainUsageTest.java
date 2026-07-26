package javax.net.p2p.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;
import static org.junit.Assert.*;

public class NoCertificateChainUsageTest {

    @Test
    public void p2pCoreDoesNotUseCertificateChains() throws Exception {
        Path root = Paths.get("src/main/java/javax/net/p2p");
        if (!Files.exists(root)) {
            fail("missing source root: " + root.toAbsolutePath());
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> scanFile(p, violations));
        }

        if (!violations.isEmpty()) {
            fail("发现证书链/PKI/Keystore 相关使用（项目要求禁止）:\n" + String.join("\n", violations));
        }
    }

    private static void scanFile(Path file, List<String> violations) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            violations.add(file + ": <read failed> " + e.getMessage());
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            if (isForbidden(line)) {
                violations.add(file + ":" + (i + 1) + ": " + line.trim());
            }
        }
    }

    private static boolean isForbidden(String line) {
        if (line.contains("InsecureTrustManagerFactory")) {
            return false;
        }
        return line.contains("KeyStore")
                || line.contains("PKCS12")
                || line.contains("TrustManagerFactory")
                || line.contains("X509TrustManager")
                || line.contains("X509ExtendedTrustManager")
                || line.contains("CertificateFactory")
                || line.contains("X509Certificate")
                || line.contains("BEGIN CERTIFICATE")
                || line.contains(".p12")
                || line.contains(".pfx")
                || line.contains(".jks")
                || line.contains("trustStore")
                || line.contains("cacerts");
    }
}
