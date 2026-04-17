package javax.net.p2p.auth.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class AuthConfig {

    private boolean enabled;
    private int xorKeyLength;
    private String keyDir;
    private Client client;
    private Server server;

    public AuthConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getXorKeyLength() {
        return xorKeyLength;
    }

    public void setXorKeyLength(int xorKeyLength) {
        this.xorKeyLength = xorKeyLength;
    }

    public String getKeyDir() {
        return keyDir;
    }

    public void setKeyDir(String keyDir) {
        this.keyDir = keyDir;
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }

    public static AuthConfig load() {
        String path = System.getProperty("p2p.auth.yaml");
        try {
            Yaml yaml = new Yaml();
            if (path != null && !path.isBlank()) {
                try (InputStream in = new FileInputStream(path)) {
                    AuthConfig cfg = yaml.loadAs(in, AuthConfig.class);
                    applyKeyDir(cfg, new File(path).getParentFile());
                    return cfg == null ? new AuthConfig() : cfg;
                }
            }
            File local = new File(System.getProperty("user.dir", "."), "auth.yaml").getAbsoluteFile();
            if (!local.exists() || !local.isFile()) {
                return new AuthConfig();
            }
            try (InputStream in = new FileInputStream(local)) {
                AuthConfig cfg = yaml.loadAs(in, AuthConfig.class);
                applyKeyDir(cfg, local.getParentFile());
                return cfg == null ? new AuthConfig() : cfg;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void applyKeyDir(AuthConfig cfg, File yamlBaseDir) {
        if (cfg == null) {
            return;
        }
        String dir = cfg.getKeyDir();
        if (dir == null || dir.isBlank()) {
            System.setProperty("p2p.key.dir", yamlBaseDir.getAbsolutePath());
            return;
        }
        File f = new File(dir);
        if (f.isAbsolute()) {
            throw new IllegalArgumentException("absolute keyDir is not allowed");
        }
        File resolved = new File(yamlBaseDir, dir).getAbsoluteFile();
        System.setProperty("p2p.key.dir", resolved.getAbsolutePath());
    }

    public static class Client {
        private String user;
        private String userId;
        private String privateKey;
        private String serverPublicKey;

        public Client() {
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public String getServerPublicKey() {
            return serverPublicKey;
        }

        public void setServerPublicKey(String serverPublicKey) {
            this.serverPublicKey = serverPublicKey;
        }
    }

    public static class Server {
        private String privateKey;
        private Map<String, String> clientPublicKeys = new HashMap<>();
        private Map<String, List<String>> allowCommands = new HashMap<>();

        public Server() {
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public void setPrivateKey(String privateKey) {
            this.privateKey = privateKey;
        }

        public Map<String, String> getClientPublicKeys() {
            return clientPublicKeys;
        }

        public void setClientPublicKeys(Map<String, String> clientPublicKeys) {
            this.clientPublicKeys = clientPublicKeys;
        }

        public Map<String, List<String>> getAllowCommands() {
            return allowCommands;
        }

        public void setAllowCommands(Map<String, List<String>> allowCommands) {
            this.allowCommands = allowCommands;
        }
    }
}
