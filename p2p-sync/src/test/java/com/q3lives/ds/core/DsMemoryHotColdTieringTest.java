package com.q3lives.ds.core;

import java.io.File;
import java.util.Arrays;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class DsMemoryHotColdTieringTest {

    private static final int HEADER_SIZE = 64;
    private static final int DATA_UNIT_SIZE = 2048;
    private static final int BLOCK_SIZE = 64 * 1024;

    private File dataFile;
    private File coldDir;

    @Before
    public void setUp() {
        dataFile = new File("target/test_dsmemory_tiering.dat");
        coldDir = new File("target/test_dsmemory_tiering_cold");
        ensureClean(dataFile);
        ensureCleanDir(coldDir);
    }

    @After
    public void tearDown() {
        ensureClean(dataFile);
        ensureCleanDir(coldDir);
    }

    private static void ensureClean(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }

    private static void ensureCleanDir(File dir) {
        if (dir != null && dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    @Test
    public void shouldCompressColdBlocksAndRestoreOnRead() {
        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(2);
        mem.setColdDirectory(coldDir);
        mem.setColdThresholdMs(0L);

        int blocks = 20;
        long[] markers = new long[blocks];
        for (int i = 0; i < blocks; i++) {
            long position = (long) i * BLOCK_SIZE;
            markers[i] = 0xDEADBEEFL << 32 | (i & 0xFFFFFFFFL);
            mem.storeLongOffset(position, markers[i]);
        }

        DsMemory.CacheStats stats = mem.getCacheStats();
        assertTrue("expected cold blocks after eviction, coldCount=" + stats.getColdBlockCount(),
            stats.getColdBlockCount() > 0);
        assertTrue("expected active cached blocks <= max, active=" + stats.getActiveCachedBlocks(),
            stats.getActiveCachedBlocks() <= mem.getMaxCachedBlocks());

        for (int i = 0; i < blocks; i++) {
            long position = (long) i * BLOCK_SIZE;
            assertEquals("marker mismatch at block " + i, markers[i], mem.loadLongOffset(position));
        }

        DsMemory.CacheStats statsAfterRead = mem.getCacheStats();
        assertTrue("expected cold read hits, hits=" + statsAfterRead.getColdReadHits(),
            statsAfterRead.getColdReadHits() > 0);

        mem.syncStore();

        DsMemory mem2 = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem2.setMaxCachedBlocks(2);
        mem2.setColdDirectory(coldDir);
        mem2.setColdThresholdMs(0L);
        mem2.syncLoad();
        for (int i = 0; i < blocks; i++) {
            long position = (long) i * BLOCK_SIZE;
            assertEquals("marker mismatch after syncLoad at block " + i, markers[i], mem2.loadLongOffset(position));
        }
    }

    @Test
    public void shouldRemoveColdFileWhenBlockBecomesHotAgain() {
        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(2);
        mem.setColdDirectory(coldDir);
        mem.setColdThresholdMs(0L);

        int targetBlock = 5;
        long position = (long) targetBlock * BLOCK_SIZE;
        long originalValue = 0xCAFEBABEL;
        mem.storeLongOffset(position, originalValue);

        // Evict the only cached block by touching two new blocks.
        mem.storeLongOffset(0L, 1L);
        mem.storeLongOffset((long) BLOCK_SIZE, 2L);
        mem.storeLongOffset(2L * BLOCK_SIZE, 3L);

        DsMemory.CacheStats stats = mem.getAndResetCacheStats();
        assertTrue("expected cold blocks after eviction, coldCount=" + stats.getColdBlockCount(),
            stats.getColdBlockCount() > 0);

        File coldFile = new File(coldDir, "cold_" + targetBlock + ".lz4");
        assertTrue("cold file should exist for evicted block", coldFile.exists());

        // Rewrite the block: main file becomes authoritative, cold file must be removed.
        long newValue = 0xBABEFACEL;
        mem.storeLongOffset(position, newValue);
        assertFalse("cold file should be removed after hot write", coldFile.exists());
        assertEquals("hot-written value should be readable", newValue, mem.loadLongOffset(position));

        mem.syncStore();

        DsMemory mem2 = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem2.setMaxCachedBlocks(2);
        mem2.setColdDirectory(coldDir);
        mem2.setColdThresholdMs(0L);
        mem2.syncLoad();
        assertEquals("value should remain hot after syncLoad", newValue, mem2.loadLongOffset(position));
        assertFalse("cold file should remain absent after syncLoad", coldFile.exists());
    }

    @Test
    public void shouldNotColdTierWhenDisabled() {
        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(2);
        // coldDirectory is null -> tiering disabled

        int blocks = 20;
        for (int i = 0; i < blocks; i++) {
            mem.storeLongOffset((long) i * BLOCK_SIZE, (long) i);
        }

        DsMemory.CacheStats stats = mem.getCacheStats();
        assertEquals("cold block count should be 0 when tiering disabled", 0L, stats.getColdBlockCount());
        assertEquals("cold read hits should be 0 when tiering disabled", 0L, stats.getColdReadHits());
        assertTrue("active cached blocks should stay bounded", stats.getActiveCachedBlocks() <= 2);

        for (int i = 0; i < blocks; i++) {
            assertEquals((long) i, mem.loadLongOffset((long) i * BLOCK_SIZE));
        }
    }
}
