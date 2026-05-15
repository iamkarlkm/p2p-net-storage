package javax.net.p2p.auth.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

public class AuthConfig {

    private boolean enabled;
    private int xorKeyLength;
    private String cryptoMode;
    private String xorKeyFile;
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

    public String getCryptoMode() {
        return cryptoMode;
    }

    public void setCryptoMode(String cryptoMode) {
        this.cryptoMode = cryptoMode;
    }

    public String getXorKeyFile() {
        return xorKeyFile;
    }

    public void setXorKeyFile(String xorKeyFile) {
        this.xorKeyFile = xorKeyFile;
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
        String inlineYaml = System.getProperty("p2p.auth.inlineYaml");
        if (inlineYaml != null && !inlineYaml.isBlank()) {
            try {
                Yaml yaml = new Yaml();
                AuthConfig cfg = yaml.loadAs(new StringReader(inlineYaml), AuthConfig.class);
                String baseDir = System.getProperty("p2p.auth.inlineBaseDir");
                File yamlBaseDir = baseDir == null || baseDir.isBlank()
                    ? new File(System.getProperty("user.dir", ".")).getAbsoluteFile()
                    : new File(baseDir).getAbsoluteFile();
                applyKeyDir(cfg, yamlBaseDir);
                return cfg == null ? new AuthConfig() : cfg;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
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
        private Map<String, RolePolicy> roles = new HashMap<>();
        private List<String> defaultRoles;
        private List<RoleBinding> roleBindings;
        private Map<String, List<String>> userRoles = new HashMap<>();
        private String clientPublicKeyTemplate;
        private String clientPublicKeyDir;
        private String clientPublicKeySuffix;
        private List<ClientKeyBinding> clientPublicKeyBindings;

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

        public Map<String, RolePolicy> getRoles() {
            return roles;
        }

        public void setRoles(Map<String, RolePolicy> roles) {
            this.roles = roles;
        }

        public List<String> getDefaultRoles() {
            return defaultRoles;
        }

        public void setDefaultRoles(List<String> defaultRoles) {
            this.defaultRoles = defaultRoles;
        }

        public List<RoleBinding> getRoleBindings() {
            return roleBindings;
        }

        public void setRoleBindings(List<RoleBinding> roleBindings) {
            this.roleBindings = roleBindings;
        }

        public Map<String, List<String>> getUserRoles() {
            return userRoles;
        }

        public void setUserRoles(Map<String, List<String>> userRoles) {
            this.userRoles = userRoles;
        }

        public String getClientPublicKeyTemplate() {
            return clientPublicKeyTemplate;
        }

        public void setClientPublicKeyTemplate(String clientPublicKeyTemplate) {
            this.clientPublicKeyTemplate = clientPublicKeyTemplate;
        }

        public String getClientPublicKeyDir() {
            return clientPublicKeyDir;
        }

        public void setClientPublicKeyDir(String clientPublicKeyDir) {
            this.clientPublicKeyDir = clientPublicKeyDir;
        }

        public String getClientPublicKeySuffix() {
            return clientPublicKeySuffix;
        }

        public void setClientPublicKeySuffix(String clientPublicKeySuffix) {
            this.clientPublicKeySuffix = clientPublicKeySuffix;
        }

        public List<ClientKeyBinding> getClientPublicKeyBindings() {
            return clientPublicKeyBindings;
        }

        public void setClientPublicKeyBindings(List<ClientKeyBinding> clientPublicKeyBindings) {
            this.clientPublicKeyBindings = clientPublicKeyBindings;
        }
    }

    public static class RolePolicy {
        private List<String> allowCategories;
        private List<String> allowCommands;

        public RolePolicy() {
        }

        public List<String> getAllowCategories() {
            return allowCategories;
        }

        public void setAllowCategories(List<String> allowCategories) {
            this.allowCategories = allowCategories;
        }

        public List<String> getAllowCommands() {
            return allowCommands;
        }

        public void setAllowCommands(List<String> allowCommands) {
            this.allowCommands = allowCommands;
        }
    }

    public static class RoleBinding {
        private String match;
        private List<String> roles;

        public RoleBinding() {
        }

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    public static class ClientKeyBinding {
        private String match;
        private String publicKey;

        public ClientKeyBinding() {
        }

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }
    }
}
