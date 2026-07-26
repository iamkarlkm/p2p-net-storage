package com.q3lives.ds.fs.mft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftFileSystem 配置相关功能测试：
 * YAML 加载、文件系统名称、atime、命名空间初始化、loadOrInit。
 */
class DsMftFileSystemConfigTest {

    private Path tempDir;
    private DsMftFileSystem fs;

    @AfterEach
    void tearDown() throws IOException {
        if (fs != null) {
            fs.close();
        }
        if (tempDir != null) {
            Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    // ================== 文件系统名称 ==================

    @Test
    void testFsNameStoredInSuperInode() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setFsName("myTestFs");
        cfg.setNamespaceDir(tempDir.toString());

        fs = DsMftFileSystem.loadOrInit(cfg);
        assertEquals("myTestFs", fs.getFsName());
    }

    @Test
    void testFsNameUpdateOnReload() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");

        // 第一次初始化
        DsMftFileSystemConfig cfg1 = new DsMftFileSystemConfig();
        cfg1.setFsName("firstName");
        cfg1.setNamespaceDir(tempDir.toString());
        fs = DsMftFileSystem.loadOrInit(cfg1);
        fs.close();

        // 重新加载，更新名称
        DsMftFileSystemConfig cfg2 = new DsMftFileSystemConfig();
        cfg2.setFsName("secondName");
        cfg2.setNamespaceDir(tempDir.toString());
        fs = DsMftFileSystem.loadOrInit(cfg2);
        assertEquals("secondName", fs.getFsName());
    }

    @Test
    void testFsNameEmptyWhenNotSet() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());

        fs = DsMftFileSystem.loadOrInit(cfg);
        assertEquals("", fs.getFsName());
    }

    // ================== 命名空间目录初始化 ==================

    @Test
    void testNamespaceDirsInitialized() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setNamespaceDirs(List.of("/data", "/tmp", "/logs/sub"));

        fs = DsMftFileSystem.loadOrInit(cfg);

        assertTrue(fs.exists("/data"));
        assertTrue(fs.exists("/tmp"));
        assertTrue(fs.exists("/logs"));
        assertTrue(fs.exists("/logs/sub"));

        // 验证是目录
        assertTrue(fs.stat("/data").i_mode == (short) 0x41ED);
    }

    @Test
    void testNamespaceDirsNotDuplicatedOnReload() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setNamespaceDirs(List.of("/data"));

        fs = DsMftFileSystem.loadOrInit(cfg);
        fs.writeFile("/data/file.txt", "hello".getBytes(StandardCharsets.UTF_8));
        fs.close();

        // 重新加载
        fs = DsMftFileSystem.loadOrInit(cfg);
        assertTrue(fs.exists("/data/file.txt"));
    }

    // ================== atime 记录 ==================

    @Test
    void testAtimeEnabled() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setAtimeEnabled(true);

        fs = DsMftFileSystem.loadOrInit(cfg);
        fs.writeFile("/atime_test.txt", "data".getBytes(StandardCharsets.UTF_8));

        // 写入后 atime 应为 0（尚未访问）
        assertEquals(0L, fs.getAtime("/atime_test.txt"), "atime should be 0 before read");

        // 第一次 readFile 会设置 atime
        fs.readFile("/atime_test.txt");
        long firstAtime = fs.getAtime("/atime_test.txt");
        assertTrue(firstAtime > 0, "atime should be updated after first read");

        // 稍等片刻后再次读取，atime 应被更新为更大的值
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
        fs.readFile("/atime_test.txt");
        long secondAtime = fs.getAtime("/atime_test.txt");
        assertTrue(secondAtime >= firstAtime, "atime should be updated after second read");
    }

    @Test
    void testAtimeDisabled() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setAtimeEnabled(false);

        fs = DsMftFileSystem.loadOrInit(cfg);
        fs.writeFile("/no_atime.txt", "data".getBytes(StandardCharsets.UTF_8));

        fs.readFile("/no_atime.txt");
        assertEquals(0L, fs.getAtime("/no_atime.txt"), "atime should not be updated when disabled");
    }

    @Test
    void testAtimeUpdatedOnStat() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setAtimeEnabled(true);

        fs = DsMftFileSystem.loadOrInit(cfg);
        fs.writeFile("/stat_atime.txt", "x".getBytes(StandardCharsets.UTF_8));

        assertEquals(0L, fs.getAtime("/stat_atime.txt"), "atime should be 0 before stat");

        fs.stat("/stat_atime.txt");
        assertTrue(fs.getAtime("/stat_atime.txt") > 0, "atime should be updated on stat");
    }

    // ================== YAML 配置文件加载 ==================

    @Test
    void testLoadFromYamlFile() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        Path yamlFile = tempDir.resolve("test.yaml");
        String yaml = String.join("\n",
                "fsName: yamlFs",
                "namespaceDir: " + tempDir.resolve("data").toString().replace('\\', '/'),
                "atimeEnabled: true",
                "auditLogEnabled: false",
                "namespaceDirs:",
                "  - /data",
                "  - /tmp"
        );
        Files.writeString(yamlFile, yaml);

        fs = DsMftFileSystem.loadOrInit(yamlFile);
        assertEquals("yamlFs", fs.getFsName());
        assertTrue(fs.exists("/data"));
        assertTrue(fs.exists("/tmp"));
    }

    @Test
    void testConfigGetter() throws IOException {
        tempDir = Files.createTempDirectory("mft_cfg_test_");
        DsMftFileSystemConfig cfg = new DsMftFileSystemConfig();
        cfg.setNamespaceDir(tempDir.toString());
        cfg.setAtimeEnabled(true);
        cfg.setAuditLogEnabled(false);

        fs = DsMftFileSystem.loadOrInit(cfg);
        DsMftFileSystemConfig returned = fs.getConfig();
        assertTrue(returned.isAtimeEnabled());
        assertFalse(returned.isAuditLogEnabled());
    }
}
