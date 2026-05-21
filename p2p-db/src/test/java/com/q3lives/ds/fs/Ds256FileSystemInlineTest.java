package com.q3lives.ds.fs;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ds256FileSystem 小文件内联与空文件优化测试。
 */
class Ds256FileSystemInlineTest {

    private Path rootDir;
    private Ds256FileSystem fs;

    @BeforeEach
    void setUp() throws IOException {
        rootDir = Files.createTempDirectory("ds256_inline_test_");
        fs = new Ds256FileSystem(rootDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (fs != null) {
            fs.close();
        }
        if (rootDir != null) {
            Files.walk(rootDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void testEmptyFileNoHash() throws Exception {
        FileMetadata metadata = fs.saveFile("/empty.txt", new byte[0], null);

        assertNotNull(metadata);
        assertEquals(0L, metadata.basic.fileSize);
        assertTrue(metadata.basic.contentHash == null || metadata.basic.contentHash.isEmpty(),
                "empty file should have no contentHash");
        assertEquals(0L, metadata.storage.inlineId);

        // 读取应返回空数组
        byte[] content = fs.getFileContentByPath("/empty.txt");
        assertNotNull(content);
        assertEquals(0, content.length);
    }

    @Test
    void testSmallFileInline() throws Exception {
        byte[] data = "Hello, inline small file!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileMetadata metadata = fs.saveFile("/small.txt", data, null);

        assertNotNull(metadata);
        assertEquals(data.length, metadata.basic.fileSize);
        assertTrue(metadata.basic.contentHash == null || metadata.basic.contentHash.isEmpty(),
                "small file should not have SHA-256 contentHash");
        assertNotEquals(0L, metadata.storage.inlineId, "small file should have inlineId");
        assertEquals("INLINE", metadata.security.hashAlgorithm);

        // 读取内容应一致
        byte[] read = fs.getFileContentByPath("/small.txt");
        assertArrayEquals(data, read);
    }

    @Test
    void testLargeFileSha256() throws Exception {
        // 超过 4KB 的文件应走 SHA-256
        byte[] data = new byte[4097];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        FileMetadata metadata = fs.saveFile("/large.bin", data, null);

        assertNotNull(metadata);
        assertEquals(data.length, metadata.basic.fileSize);
        assertNotNull(metadata.basic.contentHash);
        assertFalse(metadata.basic.contentHash.isEmpty(), "large file should have SHA-256 contentHash");
        assertEquals(0L, metadata.storage.inlineId, "large file should not have inlineId");
        assertEquals("SHA-256", metadata.security.hashAlgorithm);

        byte[] read = fs.getFileContentByPath("/large.bin");
        assertArrayEquals(data, read);
    }

    @Test
    void testExactlyThresholdInline() throws Exception {
        // 刚好 4KB 应走内联（<= 阈值）
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        FileMetadata metadata = fs.saveFile("/threshold.txt", data, null);

        assertNotNull(metadata);
        assertEquals(4096L, metadata.basic.fileSize);
        assertNotEquals(0L, metadata.storage.inlineId, "4KB file should be inline");

        byte[] read = fs.getFileContentByPath("/threshold.txt");
        assertArrayEquals(data, read);
    }

    @Test
    void testUpdateSmallToLarge() throws Exception {
        // 先写小文件（内联）
        byte[] small = "small".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileMetadata meta1 = fs.saveFile("/update.txt", small, null);
        long oldInlineId = meta1.storage.inlineId;
        assertNotEquals(0L, oldInlineId);

        // 再更新为大文件（SHA-256）
        byte[] large = new byte[5000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 256);
        }
        FileMetadata meta2 = fs.saveFile("/update.txt", large, null);

        assertEquals(0L, meta2.storage.inlineId, "after update to large, inlineId should be cleared");
        assertNotNull(meta2.basic.contentHash);

        byte[] read = fs.getFileContentByPath("/update.txt");
        assertArrayEquals(large, read);
    }

    @Test
    void testUpdateLargeToSmall() throws Exception {
        // 先写大文件（SHA-256）
        byte[] large = new byte[5000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 256);
        }
        FileMetadata meta1 = fs.saveFile("/shrink.txt", large, null);
        assertEquals(0L, meta1.storage.inlineId);

        // 再更新为小文件（内联）
        byte[] small = "shrunk".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileMetadata meta2 = fs.saveFile("/shrink.txt", small, null);

        assertNotEquals(0L, meta2.storage.inlineId, "after update to small, should get inlineId");

        byte[] read = fs.getFileContentByPath("/shrink.txt");
        assertArrayEquals(small, read);
    }

    @Test
    void testGetFileContentById() throws Exception {
        byte[] data = "by global id".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        FileMetadata metadata = fs.saveFile("/byid.txt", data, null);
        String globalFileId = metadata.globalFileId;
        assertNotNull(globalFileId);

        // 先通过路径读取确认数据正确
        byte[] byPath = fs.getFileContentByPath("/byid.txt");
        assertArrayEquals(data, byPath);

        // 再通过 globalFileId 读取
        byte[] read = fs.getFileContentById(globalFileId);
        assertNotNull(read, "getFileContentById should not return null");
        assertArrayEquals(data, read);
    }

    @Test
    void testGetMetadataByPath() throws Exception {
        byte[] data = "metadata check".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        fs.saveFile("/meta.txt", data, null);

        FileMetadata metadata = fs.getMetadataByPath("/meta.txt");
        assertNotNull(metadata);
        assertNotNull(metadata.basic);
        assertNotNull(metadata.storage);
        assertEquals(data.length, metadata.basic.fileSize);
        assertNotEquals(0L, metadata.storage.inlineId);
    }
}
