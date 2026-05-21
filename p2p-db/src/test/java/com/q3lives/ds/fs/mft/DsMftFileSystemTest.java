package com.q3lives.ds.fs.mft;

import com.q3lives.ds.fs.Ds128Inode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftFileSystem 功能测试。
 */
class DsMftFileSystemTest {

    private Path fsDir;
    private DsMftFileSystem fs;

    @BeforeEach
    void setUp() throws IOException {
        fsDir = Files.createTempDirectory("mft_fs_test_");
        fs = new DsMftFileSystem(fsDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (fs != null) {
            fs.close();
        }
        if (fsDir != null) {
            Files.walk(fsDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    // ================== 根目录 ==================

    @Test
    void testRootDirExists() throws IOException {
        Ds128Inode root = fs.stat("/");
        assertNotNull(root);
        assertTrue(root.i_mode == (short) 0x41ED, "root should be directory");
    }

    // ================== 空文件 ==================

    @Test
    void testEmptyFileReadWrite() throws IOException {
        fs.writeFile("/empty.txt", new byte[0]);

        byte[] read = fs.readFile("/empty.txt");
        assertNotNull(read);
        assertEquals(0, read.length);

        Ds128Inode inode = fs.stat("/empty.txt");
        assertNotNull(inode);
        assertEquals(0L, inode.data_size);
    }

    @Test
    void testEmptyFileNoBucketAllocated() throws IOException {
        fs.writeFile("/empty2.txt", new byte[0]);
        Ds128Inode inode = fs.stat("/empty2.txt");
        assertNotNull(inode);
        // 空文件不应分配 bucket，data_size = 0
        assertEquals(0L, inode.data_size);
    }

    // ================== 小文件 ==================

    @Test
    void testSmallFileReadWrite() throws IOException {
        byte[] data = "Hello, DsMftFileSystem!".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/small.txt", data);

        byte[] read = fs.readFile("/small.txt");
        assertArrayEquals(data, read);

        Ds128Inode inode = fs.stat("/small.txt");
        assertNotNull(inode);
        assertEquals(data.length, inode.data_size);
    }

    @Test
    void testSmallFileUpdate() throws IOException {
        byte[] data1 = "version 1".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/update.txt", data1);

        byte[] data2 = "version 2 - longer content".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/update.txt", data2);

        byte[] read = fs.readFile("/update.txt");
        assertArrayEquals(data2, read);

        Ds128Inode inode = fs.stat("/update.txt");
        assertEquals(data2.length, inode.data_size);
    }

    @Test
    void testSmallFileUpdateToEmpty() throws IOException {
        byte[] data = "not empty".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/shrink.txt", data);
        assertEquals(data.length, fs.stat("/shrink.txt").data_size);

        fs.writeFile("/shrink.txt", new byte[0]);
        byte[] read = fs.readFile("/shrink.txt");
        assertEquals(0, read.length);
        assertEquals(0L, fs.stat("/shrink.txt").data_size);
    }

    // ================== 大文件 ==================

    @Test
    void testLargeFileReadWrite() throws IOException {
        byte[] data = new byte[5000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i % 256);
        }
        fs.writeFile("/large.bin", data);

        byte[] read = fs.readFile("/large.bin");
        assertArrayEquals(data, read);

        Ds128Inode inode = fs.stat("/large.bin");
        assertEquals(data.length, inode.data_size);
    }

    // ================== 目录操作 ==================

    @Test
    void testMkdirAndList() throws IOException {
        fs.mkdir("/a/b/c");

        List<DsMftFileSystem.DirEntry> rootEntries = fs.listDir("/");
        assertEquals(1, rootEntries.size());
        assertEquals("a", rootEntries.get(0).name);
        assertTrue(rootEntries.get(0).isDirectory);

        List<DsMftFileSystem.DirEntry> aEntries = fs.listDir("/a");
        assertEquals(1, aEntries.size());
        assertEquals("b", aEntries.get(0).name);

        List<DsMftFileSystem.DirEntry> bEntries = fs.listDir("/a/b");
        assertEquals(1, bEntries.size());
        assertEquals("c", bEntries.get(0).name);
    }

    @Test
    void testNestedFileWrite() throws IOException {
        fs.mkdir("/dir1/dir2");
        byte[] data = "nested content".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/dir1/dir2/file.txt", data);

        byte[] read = fs.readFile("/dir1/dir2/file.txt");
        assertArrayEquals(data, read);
    }

    @Test
    void testListDirWithMixedEntries() throws IOException {
        fs.mkdir("/mix/dir");
        fs.writeFile("/mix/file1.txt", "a".getBytes(StandardCharsets.UTF_8));
        fs.writeFile("/mix/file2.txt", "b".getBytes(StandardCharsets.UTF_8));

        List<DsMftFileSystem.DirEntry> entries = fs.listDir("/mix");
        assertEquals(3, entries.size());
    }

    @Test
    void testDeleteFile() throws IOException {
        fs.writeFile("/del.txt", "delete me".getBytes(StandardCharsets.UTF_8));
        assertTrue(fs.exists("/del.txt"));

        boolean deleted = fs.deleteFile("/del.txt");
        assertTrue(deleted);
        assertFalse(fs.exists("/del.txt"));
    }

    @Test
    void testDeleteEmptyDir() throws IOException {
        fs.mkdir("/empty_dir");
        assertTrue(fs.exists("/empty_dir"));

        boolean deleted = fs.deleteDir("/empty_dir");
        assertTrue(deleted);
        assertFalse(fs.exists("/empty_dir"));
    }

    @Test
    void testDeleteNonEmptyDirFails() throws IOException {
        fs.mkdir("/nonempty/dir");
        assertTrue(fs.exists("/nonempty"));

        boolean deleted = fs.deleteDir("/nonempty");
        assertFalse(deleted); // 非空目录删除应失败
        assertTrue(fs.exists("/nonempty"));
    }

    // ================== 文件名测试 ==================

    @Test
    void testShortFileName() throws IOException {
        String name = "short.txt"; // 9 字节，&lt;=31
        fs.writeFile("/" + name, "data".getBytes(StandardCharsets.UTF_8));

        Ds128Inode inode = fs.stat("/" + name);
        assertNotNull(inode);
        assertTrue(inode.name[0] > 0, "short name should be stored in inode");
        String storedName = new String(inode.name, 1, inode.name[0] & 0xFF, StandardCharsets.UTF_8);
        assertEquals(name, storedName);
    }

    @Test
    void testLongFileName() throws IOException {
        String name = "very_long_file_name_that_exceeds_31_bytes.txt"; // >31 字节
        fs.writeFile("/" + name, "data".getBytes(StandardCharsets.UTF_8));

        Ds128Inode inode = fs.stat("/" + name);
        assertNotNull(inode);
        assertEquals(0, inode.name[0], "long name should have name[0] == 0");
    }

    @Test
    void testLongFileNameReadWrite() throws IOException {
        String name = "this_is_a_very_long_filename_over_31_characters_long.txt";
        byte[] data = "long name data".getBytes(StandardCharsets.UTF_8);
        fs.writeFile("/" + name, data);

        byte[] read = fs.readFile("/" + name);
        assertArrayEquals(data, read);
    }

    // ================== 边界测试 ==================

    @Test
    void testReadNonExistentFile() throws IOException {
        assertNull(fs.readFile("/not_exist.txt"));
    }

    @Test
    void testStatNonExistentPath() throws IOException {
        assertNull(fs.stat("/not_exist"));
    }

    @Test
    void testExists() throws IOException {
        assertTrue(fs.exists("/"));
        assertFalse(fs.exists("/not_exist"));
        fs.writeFile("/exists.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertTrue(fs.exists("/exists.txt"));
    }

    @Test
    void testExactly31ByteName() throws IOException {
        // 刚好 31 字节应作为短名存储
        String name = "1234567890123456789012345678901"; // 31 chars
        assertEquals(31, name.getBytes(StandardCharsets.UTF_8).length);
        fs.writeFile("/" + name, "data".getBytes(StandardCharsets.UTF_8));

        Ds128Inode inode = fs.stat("/" + name);
        assertTrue(inode.name[0] > 0, "31-byte name should be short name");
    }

    @Test
    void testExactly32ByteName() throws IOException {
        // 32 字节应作为长名存储
        String name = "12345678901234567890123456789012"; // 32 chars
        assertEquals(32, name.getBytes(StandardCharsets.UTF_8).length);
        fs.writeFile("/" + name, "data".getBytes(StandardCharsets.UTF_8));

        Ds128Inode inode = fs.stat("/" + name);
        assertEquals(0, inode.name[0], "32-byte name should be long name");
    }
}
