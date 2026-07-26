package javax.net.p2p.filesync.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.sync.rpc.server.SyncConflictPolicy;

import org.yaml.snakeyaml.Yaml;

public class P2PSyncConfig {

    private long taskId;
    private int storeId;
    private int listenPort;
    private int monitorPort;
    private List<String> remoteEndpoints = new ArrayList<>();
    private List<String> includeGlobs = new ArrayList<>();
    private List<String> excludeGlobs = new ArrayList<>();
    private String localDir;
    private String dsHome;
    private int uploadBlockSizeBytes;
    private int maxRetryCount = 3;
    private long retryBackoffMillis = 2000L;
    private long receiverPendingExpireMillis = 300000L;
    private Map<String, String> userInfo;
    private Map<String, String> loginInfo;
    private AuthConfig auth;
    private String authYaml;
    private SyncConflictPolicy conflictPolicy;

    public P2PSyncConfig() {
    }

    public long getTaskId() {
        return taskId;
    }

    public void setTaskId(long taskId) {
        this.taskId = taskId;
    }

    public int getStoreId() {
        return storeId;
    }

    public void setStoreId(int storeId) {
        this.storeId = storeId;
    }

    public int getListenPort() {
        return listenPort;
    }

    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    public int getMonitorPort() {
        return monitorPort;
    }

    public void setMonitorPort(int monitorPort) {
        this.monitorPort = monitorPort;
    }

    public List<String> getRemoteEndpoints() {
        return remoteEndpoints;
    }

    public void setRemoteEndpoints(List<String> remoteEndpoints) {
        this.remoteEndpoints = remoteEndpoints;
    }

    public String getLocalDir() {
        return localDir;
    }

    public void setLocalDir(String localDir) {
        this.localDir = localDir;
    }

    public List<String> getIncludeGlobs() {
        return includeGlobs;
    }

    public void setIncludeGlobs(List<String> includeGlobs) {
        this.includeGlobs = includeGlobs;
    }

    public List<String> getExcludeGlobs() {
        return excludeGlobs;
    }

    public void setExcludeGlobs(List<String> excludeGlobs) {
        this.excludeGlobs = excludeGlobs;
    }

    public String getDsHome() {
        return dsHome;
    }

    public void setDsHome(String dsHome) {
        this.dsHome = dsHome;
    }

    public int getUploadBlockSizeBytes() {
        return uploadBlockSizeBytes;
    }

    public void setUploadBlockSizeBytes(int uploadBlockSizeBytes) {
        this.uploadBlockSizeBytes = uploadBlockSizeBytes;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public long getReceiverPendingExpireMillis() {
        return receiverPendingExpireMillis;
    }

    public void setReceiverPendingExpireMillis(long receiverPendingExpireMillis) {
        this.receiverPendingExpireMillis = receiverPendingExpireMillis;
    }

    public Map<String, String> getUserInfo() {
        return userInfo;
    }

    public void setUserInfo(Map<String, String> userInfo) {
        this.userInfo = userInfo;
    }

    public Map<String, String> getLoginInfo() {
        return loginInfo;
    }

    public void setLoginInfo(Map<String, String> loginInfo) {
        this.loginInfo = loginInfo;
    }

    public AuthConfig getAuth() {
        return auth;
    }

    public void setAuth(AuthConfig auth) {
        this.auth = auth;
    }

    public String getAuthYaml() {
        return authYaml;
    }

    public void setAuthYaml(String authYaml) {
        this.authYaml = authYaml;
    }

    public SyncConflictPolicy getConflictPolicy() {
        return conflictPolicy;
    }

    public void setConflictPolicy(SyncConflictPolicy conflictPolicy) {
        this.conflictPolicy = conflictPolicy;
    }

    public static P2PSyncConfig load() {
        String inlineYaml = System.getProperty("p2p.sync.inlineYaml");
        if (inlineYaml != null && !inlineYaml.trim().isEmpty()) {
            Yaml yaml = new Yaml();
            P2PSyncConfig cfg = yaml.loadAs(new StringReader(inlineYaml), P2PSyncConfig.class);
            String baseDir = System.getProperty("p2p.sync.inlineBaseDir");
            File yamlBaseDir = baseDir == null || baseDir.trim().isEmpty()
                ? new File(System.getProperty("user.dir", ".")).getAbsoluteFile()
                : new File(baseDir).getAbsoluteFile();
            return resolveAndValidate(cfg, yamlBaseDir);
        }

        String path = System.getProperty("p2p.sync.yaml");
        try {
            Yaml yaml = new Yaml();
            if (path != null && !path.trim().isEmpty()) {
                try (InputStream in = new FileInputStream(path)) {
                    P2PSyncConfig cfg = yaml.loadAs(in, P2PSyncConfig.class);
                    return resolveAndValidate(cfg, new File(path).getParentFile());
                }
            }
            File local = new File(System.getProperty("user.dir", "."), "p2p-sync.yaml").getAbsoluteFile();
            if (!local.exists() || !local.isFile()) {
                return new P2PSyncConfig();
            }
            try (InputStream in = new FileInputStream(local)) {
                P2PSyncConfig cfg = yaml.loadAs(in, P2PSyncConfig.class);
                return resolveAndValidate(cfg, local.getParentFile());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static P2PSyncConfig resolveAndValidate(P2PSyncConfig cfg, File yamlBaseDir) {
        if (cfg == null) {
            return new P2PSyncConfig();
        }

        if (cfg.remoteEndpoints == null) {
            cfg.remoteEndpoints = new ArrayList<>();
        }
        if (cfg.includeGlobs == null) {
            cfg.includeGlobs = new ArrayList<>();
        }
        if (cfg.excludeGlobs == null) {
            cfg.excludeGlobs = new ArrayList<>();
        }

        if (cfg.localDir != null && !cfg.localDir.trim().isEmpty()) {
            Path dir = Paths.get(cfg.localDir);
            if (!dir.isAbsolute()) {
                cfg.localDir = new File(yamlBaseDir, cfg.localDir).getAbsolutePath();
            }
        }

        if (cfg.dsHome != null && !cfg.dsHome.trim().isEmpty()) {
            Path dir = Paths.get(cfg.dsHome);
            if (!dir.isAbsolute()) {
                cfg.dsHome = new File(yamlBaseDir, cfg.dsHome).getAbsolutePath();
            }
        }

        if (cfg.conflictPolicy == null) {
            cfg.conflictPolicy = SyncConflictPolicy.FAIL_FAST;
        }

        applyAuthOverrides(cfg, yamlBaseDir);

        if (cfg.taskId == 0L) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (cfg.localDir == null || cfg.localDir.trim().isEmpty()) {
            throw new IllegalArgumentException("localDir is required");
        }
        if (cfg.storeId == 0) {
            cfg.storeId = deriveStoreId(cfg.taskId);
        }
        if (cfg.listenPort == 0) {
            cfg.listenPort = 6060;
        }
        if (cfg.monitorPort == 0) {
            cfg.monitorPort = 8090;
        }
        if (cfg.uploadBlockSizeBytes <= 0) {
            cfg.uploadBlockSizeBytes = P2PConfig.DATA_PUT_BLOCK_SIZE;
        }
        if (cfg.maxRetryCount <= 0) {
            cfg.maxRetryCount = 3;
        }
        if (cfg.retryBackoffMillis < 0L) {
            cfg.retryBackoffMillis = 2000L;
        }
        if (cfg.receiverPendingExpireMillis <= 0L) {
            cfg.receiverPendingExpireMillis = 300000L;
        }
        P2PConfig.DATA_PUT_BLOCK_SIZE = cfg.uploadBlockSizeBytes;
        return cfg;
    }

    private static void applyAuthOverrides(P2PSyncConfig cfg, File yamlBaseDir) {
        if (cfg.auth != null && cfg.authYaml != null && !cfg.authYaml.trim().isEmpty()) {
            throw new IllegalArgumentException("auth and authYaml cannot both be set");
        }
        if (cfg.auth != null) {
            Yaml yaml = new Yaml();
            String inline = yaml.dumpAsMap(cfg.auth);
            System.setProperty("p2p.auth.inlineYaml", inline);
            System.setProperty("p2p.auth.inlineBaseDir", yamlBaseDir.getAbsolutePath());
            System.clearProperty("p2p.auth.yaml");
            return;
        }
        if (cfg.authYaml != null && !cfg.authYaml.trim().isEmpty()) {
            Path p = Paths.get(cfg.authYaml);
            if (!p.isAbsolute()) {
                p = yamlBaseDir.toPath().toAbsolutePath().normalize().resolve(cfg.authYaml).normalize();
                cfg.authYaml = p.toString();
            }
            System.setProperty("p2p.auth.yaml", p.toString());
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
        }
    }

    private static int deriveStoreId(long taskId) {
        long mixed = taskId ^ (taskId >>> 32);
        int positive = (int) (mixed & 0x7FFFFFFF);
        return positive == 0 ? 1 : positive;
    }
}
