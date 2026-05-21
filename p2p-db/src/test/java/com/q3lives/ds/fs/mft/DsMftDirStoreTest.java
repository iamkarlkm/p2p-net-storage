package com.q3lives.ds.fs.mft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DsMftDirStore 功能测试：16 字节条目 + free-ring + 4K->64K->64M 三级分级。
 */
class DsMftDirStoreTest {

    private Path rootDir;
    private DsMftDirStore dirStore;

    @BeforeEach
    void setUp() throws IOException {
        rootDir = Files.createTempDirectory("mft_dir_test_");
        dirStore = new DsMftDirStore(rootDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dirStore != null) {
            dirStore.close();
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

    // ================== 基本 CRUD ==================

    @Test
    void testCreateDirAndSize() throws IOException {
        long dirId = dirStore.createDir(0L);
        assertEquals(0L, dirStore.size(dirId));
    }

    @Test
    void testParentDirId() throws IOException {
        long rootId = dirStore.createDir(0L);
        assertEquals(0L, dirStore.getParentDirId(rootId));

        long childId = dirStore.createDir(rootId);
        assertEquals(rootId, dirStore.getParentDirId(childId));
    }

    @Test
    void testAppendAndList() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 10L, "file1".hashCode(), (short) 0x81A4);
        dirStore.appendEntry(dirId, 20L, "file2".hashCode(), (short) 0x41ED);

        assertEquals(2L, dirStore.size(dirId));

        DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, 0, 10);
        assertEquals(2, entries.length);
        // 条目无序，按 fileId 查找
        boolean found10 = false, found20 = false;
        for (DsMftDirStore.Entry e : entries) {
            if (e.fileId == 10L) {
                found10 = true;
                assertEquals("file1".hashCode(), e.nameHash);
                assertFalse(e.isDirectory());
            } else if (e.fileId == 20L) {
                found20 = true;
                assertEquals("file2".hashCode(), e.nameHash);
                assertTrue(e.isDirectory());
            }
        }
        assertTrue(found10, "should find fileId=10");
        assertTrue(found20, "should find fileId=20");
    }

    @Test
    void testFindEntry() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 30L, "target".hashCode(), (short) 0x81A4);

        DsMftDirStore.Entry e = dirStore.findEntry(dirId, 30L);
        assertNotNull(e);
        assertEquals(30L, e.fileId);
        assertEquals("target".hashCode(), e.nameHash);

        assertNull(dirStore.findEntry(dirId, 99L));
    }

    @Test
    void testRemoveEntry() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 40L, "del".hashCode(), (short) 0x81A4);
        assertEquals(1L, dirStore.size(dirId));

        boolean removed = dirStore.removeEntry(dirId, 40L);
        assertTrue(removed);
        assertEquals(0L, dirStore.size(dirId));
        assertNull(dirStore.findEntry(dirId, 40L));

        assertFalse(dirStore.removeEntry(dirId, 40L));
    }

    // ================== free-ring 空洞复用 ==================

    @Test
    void testFreeRingReuse() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 1L, "a".hashCode(), (short) 0x81A4);
        dirStore.appendEntry(dirId, 2L, "b".hashCode(), (short) 0x81A4);
        dirStore.appendEntry(dirId, 3L, "c".hashCode(), (short) 0x81A4);
        assertEquals(3L, dirStore.size(dirId));

        // 删除中间条目
        dirStore.removeEntry(dirId, 2L);
        assertEquals(2L, dirStore.size(dirId));

        // 添加新条目，应复用空洞
        dirStore.appendEntry(dirId, 4L, "d".hashCode(), (short) 0x81A4);
        assertEquals(3L, dirStore.size(dirId));

        DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, 0, 10);
        assertEquals(3, entries.length);
        boolean found4 = false;
        for (DsMftDirStore.Entry e : entries) {
            if (e.fileId == 4L) {
                found4 = true;
                break;
            }
        }
        assertTrue(found4, "new entry should be inserted into freed slot");
    }

    @Test
    void testFreeRingReuseHeadSlot0() throws IOException {
        // 验证索引 0 的空洞也能被正确复用
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 1L, "a".hashCode(), (short) 0x81A4);
        dirStore.appendEntry(dirId, 2L, "b".hashCode(), (short) 0x81A4);
        assertEquals(2L, dirStore.size(dirId));

        // 删除索引 0（第一个条目）
        dirStore.removeEntry(dirId, 1L);
        assertEquals(1L, dirStore.size(dirId));

        // 添加新条目，应复用索引 0 的空洞
        dirStore.appendEntry(dirId, 99L, "new".hashCode(), (short) 0x81A4);
        assertEquals(2L, dirStore.size(dirId));

        DsMftDirStore.Entry e = dirStore.findEntry(dirId, 99L);
        assertNotNull(e);
    }

    // ================== 分页 ==================

    @Test
    void testListEntriesPaging() throws IOException {
        long dirId = dirStore.createDir(0L);
        for (int i = 1; i <= 10; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        DsMftDirStore.Entry[] page1 = dirStore.listEntries(dirId, 0, 3);
        assertEquals(3, page1.length);

        DsMftDirStore.Entry[] page2 = dirStore.listEntries(dirId, 3, 3);
        assertEquals(3, page2.length);

        DsMftDirStore.Entry[] page3 = dirStore.listEntries(dirId, 6, 10);
        assertEquals(4, page3.length);
    }

    @Test
    void testListEntriesWithOffsetBeyondSize() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 1L, "a".hashCode(), (short) 0x81A4);

        DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, 100, 10);
        assertEquals(0, entries.length);
    }

    // ================== 4K->64K 扩展 ==================

    @Test
    void testExpandTo64k() throws IOException {
        long dirId = dirStore.createDir(0L);
        // 4K 直接区容量为 127，填满并多写一个触发 64K 块
        int count = 130;
        for (int i = 1; i <= count; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        assertEquals(count, dirStore.size(dirId));

        // 验证所有条目都能读出
        long total = dirStore.size(dirId);
        long offset = 0;
        int found = 0;
        while (offset < total) {
            DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, offset, 100);
            found += entries.length;
            offset += entries.length;
        }
        assertEquals(count, found);
    }

    @Test
    void test64kRemoveAndFind() throws IOException {
        long dirId = dirStore.createDir(0L);
        // 写入超过 127 条，触发 64K
        for (int i = 1; i <= 150; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        // 删除 64K 块中的条目
        boolean removed = dirStore.removeEntry(dirId, 140L);
        assertTrue(removed);
        assertEquals(149L, dirStore.size(dirId));

        // 再次查找应失败
        assertNull(dirStore.findEntry(dirId, 140L));

        // 其他条目仍在
        assertNotNull(dirStore.findEntry(dirId, 130L));
        assertNotNull(dirStore.findEntry(dirId, 150L));
    }

    // ================== 混合文件和目录 ==================

    @Test
    void testMixedFilesAndDirs() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 1L, "file".hashCode(), (short) 0x81A4);
        dirStore.appendEntry(dirId, 2L, "dir".hashCode(), (short) 0x41ED);

        DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, 0, 10);
        assertEquals(2, entries.length);

        for (DsMftDirStore.Entry e : entries) {
            if (e.fileId == 1L) {
                assertFalse(e.isDirectory());
            } else if (e.fileId == 2L) {
                assertTrue(e.isDirectory());
            }
        }
    }

    // ================== 空目录边界 ==================

    @Test
    void testEmptyDirListReturnsEmpty() throws IOException {
        long dirId = dirStore.createDir(0L);
        DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, 0, 10);
        assertEquals(0, entries.length);
    }

    @Test
    void testAppendZeroFileIdIgnored() throws IOException {
        long dirId = dirStore.createDir(0L);
        dirStore.appendEntry(dirId, 0L, "zero".hashCode(), (short) 0x81A4);
        assertEquals(0L, dirStore.size(dirId));
    }

    // ================== 4K->64K->64M 三级扩展 ==================

    @Test
    void testExpandTo64m() throws IOException {
        long dirId = dirStore.createDir(0L);
        // 4K 直接区 127 + 128 个 64K 块(各 4095) = 127 + 524160 = 524287
        // 再多写一个就会触发 64M 块
        int count = 524_290;
        for (int i = 1; i <= count; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        assertEquals(count, dirStore.size(dirId));

        // 验证所有条目都能分页读出
        long total = dirStore.size(dirId);
        long offset = 0;
        int found = 0;
        while (offset < total) {
            DsMftDirStore.Entry[] entries = dirStore.listEntries(dirId, offset, 1000);
            found += entries.length;
            offset += entries.length;
        }
        assertEquals(count, found);
    }

    @Test
    void test64mRemoveAndFind() throws IOException {
        long dirId = dirStore.createDir(0L);
        // 填满直接区 + 所有 64K 块，让条目落入 64M 块
        int count = 524_300;
        for (int i = 1; i <= count; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        // 删除 64M 块中的条目（fileId=524295 一定在 64M 区）
        boolean removed = dirStore.removeEntry(dirId, 524_295L);
        assertTrue(removed);
        assertEquals(count - 1L, dirStore.size(dirId));
        assertNull(dirStore.findEntry(dirId, 524_295L));

        // 其他 64M 区条目仍在
        assertNotNull(dirStore.findEntry(dirId, 524_290L));
        assertNotNull(dirStore.findEntry(dirId, 524_300L));
    }

    @Test
    void test64mFreeRingReuse() throws IOException {
        long dirId = dirStore.createDir(0L);
        // 让条目落入 64M 块
        int count = 524_300;
        for (int i = 1; i <= count; i++) {
            dirStore.appendEntry(dirId, i, ("f" + i).hashCode(), (short) 0x81A4);
        }

        // 删除 64M 区的一些条目
        dirStore.removeEntry(dirId, 524_290L);
        dirStore.removeEntry(dirId, 524_291L);
        assertEquals(count - 2L, dirStore.size(dirId));

        // 添加新条目，应复用 64M 区的空洞
        dirStore.appendEntry(dirId, 999_999L, "new".hashCode(), (short) 0x81A4);
        assertEquals(count - 1L, dirStore.size(dirId));
        assertNotNull(dirStore.findEntry(dirId, 999_999L));
    }
}
