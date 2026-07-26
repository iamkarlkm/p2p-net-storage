package javax.net.p2p.filesync.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.net.p2p.auth.config.AuthConfig;
import javax.net.p2p.config.P2PConfig;
import javax.net.p2p.filesync.sync.rpc.server.SyncConflictPolicy;

import org.junit.Assert;
import org.junit.Test;

public class P2PSyncConfigTest {

    @Test
    public void shouldLoadFromInlineYamlAndResolveRelativePaths() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_");
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "remoteEndpoints:\n"
                + "  - \"127.0.0.1:9001\"\n"
                + "includeGlobs:\n"
                + "  - \"**/*.txt\"\n"
                + "excludeGlobs:\n"
                + "  - \"**/*.tmp\"\n"
                + "localDir: \"./data\"\n"
                + "dsHome: \"./state\"\n"
                + "userInfo:\n"
                + "  userId: \"u1\"\n"
                + "loginInfo:\n"
                + "  username: \"u1\"\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(1L, cfg.getTaskId());
            Assert.assertTrue(cfg.getLocalDir().contains("data"));
            Assert.assertTrue(java.nio.file.Paths.get(cfg.getLocalDir()).isAbsolute());
            Assert.assertTrue(java.nio.file.Paths.get(cfg.getDsHome()).isAbsolute());
            Assert.assertEquals("u1", cfg.getUserInfo().get("userId"));
            Assert.assertEquals("u1", cfg.getLoginInfo().get("username"));
            Assert.assertEquals(1, cfg.getRemoteEndpoints().size());
            Assert.assertEquals(1, cfg.getIncludeGlobs().size());
            Assert.assertEquals(1, cfg.getExcludeGlobs().size());
            Assert.assertEquals(SyncConflictPolicy.FAIL_FAST, cfg.getConflictPolicy());
        } finally {
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }

    @Test
    public void shouldApplyAuthInlineYamlOverrides() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_auth_inline_");
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "localDir: \"./data\"\n"
                + "auth:\n"
                + "  enabled: false\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(1L, cfg.getTaskId());
            Assert.assertNotNull(System.getProperty("p2p.auth.inlineYaml"));
            Assert.assertTrue(System.getProperty("p2p.auth.inlineYaml").contains("enabled"));
            Assert.assertEquals(baseDir.toString(), System.getProperty("p2p.auth.inlineBaseDir"));
            Assert.assertFalse(AuthConfig.load().isEnabled());
        } finally {
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }

    @Test
    public void shouldApplyAuthYamlPathOverrides() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_auth_yaml_");
        Path authYaml = baseDir.resolve("auth.yaml");
        Files.write(authYaml, "enabled: false\n".getBytes(StandardCharsets.UTF_8));
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "localDir: \"./data\"\n"
                + "authYaml: \"./auth.yaml\"\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(authYaml.toAbsolutePath().toString(), cfg.getAuthYaml());
            Assert.assertEquals(authYaml.toAbsolutePath().toString(), System.getProperty("p2p.auth.yaml"));
            Assert.assertFalse(AuthConfig.load().isEnabled());
        } finally {
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }

    @Test
    public void shouldLoadConflictPolicyFromInlineYaml() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_conflict_policy_");
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "localDir: \"./data\"\n"
                + "conflictPolicy: LAST_WRITE_WINS\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(SyncConflictPolicy.LAST_WRITE_WINS, cfg.getConflictPolicy());
        } finally {
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }

    @Test
    public void shouldApplyUploadBlockSizeFromInlineYaml() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_upload_block_");
        int original = P2PConfig.DATA_PUT_BLOCK_SIZE;
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "localDir: \"./data\"\n"
                + "uploadBlockSizeBytes: 4096\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(4096, cfg.getUploadBlockSizeBytes());
            Assert.assertEquals(4096, P2PConfig.DATA_PUT_BLOCK_SIZE);
        } finally {
            P2PConfig.DATA_PUT_BLOCK_SIZE = original;
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }

    @Test
    public void shouldApplyMaxRetryCountFromInlineYaml() throws Exception {
        Path baseDir = Files.createTempDirectory("p2p_sync_cfg_retry_limit_");
        try {
            System.setProperty("p2p.sync.inlineBaseDir", baseDir.toString());
            System.setProperty("p2p.sync.inlineYaml", ""
                + "taskId: 1\n"
                + "localDir: \"./data\"\n"
                + "maxRetryCount: 5\n");

            P2PSyncConfig cfg = P2PSyncConfig.load();

            Assert.assertEquals(5, cfg.getMaxRetryCount());
        } finally {
            System.clearProperty("p2p.sync.inlineYaml");
            System.clearProperty("p2p.sync.inlineBaseDir");
            System.clearProperty("p2p.auth.inlineYaml");
            System.clearProperty("p2p.auth.inlineBaseDir");
            System.clearProperty("p2p.auth.yaml");
            System.clearProperty("p2p.key.dir");
        }
    }
}
