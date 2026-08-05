package com.q3lives.ds.core;

import java.io.File;
import java.util.Random;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class DsMemoryEvictionTest {

    private static final int HEADER_SIZE = 64;
    private static final int DATA_UNIT_SIZE = 2048;
    private static final int MAX_BLOCKS = 4;
    private static final int BLOCK_SIZE = 64 * 1024;

    private File dataFile;

    @Before
    public void setUp() {
        dataFile = new File("target/test_dsmemory_eviction.dat");
        File parent = dataFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (dataFile.exists()) {
            dataFile.delete();
        }
    }

    @After
    public void tearDown() {
        if (dataFile != null && dataFile.exists()) {
            dataFile.delete();
        }
    }

    @Test
    public void shouldKeepCachedBlocksBelowLimit() {
        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(MAX_BLOCKS);

        long[] positions = new long[80];
        for (int i = 0; i < positions.length; i++) {
            long pos = (long) i * 100_000L + 9L;
            positions[i] = pos;
        }

        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < positions.length; i++) {
                long pos = positions[i];
                long v = ((long) round << 32) | (i & 0xFFFFFFFFL);
                mem.storeLongOffset(pos, v);
                mem.storeLongOffset(pos + 8, v ^ 0x5A5A5A5A5A5A5A5AL);
            }
            DsMemory.CacheStats stats = mem.getCacheStats();
            assertTrue(
                "activeCachedBlocks=" + stats.getActiveCachedBlocks() + " exceeds max=" + MAX_BLOCKS
                    + " (round=" + round + ")",
                stats.getActiveCachedBlocks() <= MAX_BLOCKS
            );
            assertTrue(
                "cachedBytes=" + stats.getCachedBytes() + " exceeds maxBytes=" + (MAX_BLOCKS * (long) BLOCK_SIZE),
                stats.getCachedBytes() <= (long) MAX_BLOCKS * BLOCK_SIZE
            );
        }

        for (int i = 0; i < positions.length; i++) {
            long pos = positions[i];
            long expectedRound4 = (4L << 32) | (i & 0xFFFFFFFFL);
            assertEquals(
                "pos=" + pos + " first long mismatch",
                expectedRound4,
                mem.loadLongOffset(pos)
            );
            assertEquals(
                "pos=" + pos + " second long mismatch",
                expectedRound4 ^ 0x5A5A5A5A5A5A5A5AL,
                mem.loadLongOffset(pos + 8)
            );
        }

        DsMemory.CacheStats stats = mem.getCacheStats();
        assertTrue("eviction attempts should be > 0, attempts=" + stats.getEvictionAttempts(),
            stats.getEvictionAttempts() > 0);
        assertTrue("eviction success should be > 0, success=" + stats.getEvictionSuccess(),
            stats.getEvictionSuccess() > 0);
    }

    @Test
    public void shouldPersistAndReloadCorrectlyThroughEvictions() {
        Random rand = new Random(42L);
        int items = 500;
        long[] positions = new long[items];
        long[] values = new long[items];
        for (int i = 0; i < items; i++) {
            positions[i] = (long) rand.nextInt(5_000_000) * 8L;
            values[i] = rand.nextLong();
        }

        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(MAX_BLOCKS);
        for (int i = 0; i < items; i++) {
            mem.storeLongOffset(positions[i], values[i]);
        }

        for (int i = 0; i < items; i++) {
            assertEquals("mismatch at i=" + i + " pos=" + positions[i] + " before sync",
                values[i], mem.loadLongOffset(positions[i]));
        }

        DsMemory.CacheStats stats1 = mem.getAndResetCacheStats();
        assertTrue("expected evictions before sync, attempts=" + stats1.getEvictionAttempts(),
            stats1.getEvictionAttempts() > 0);

        mem.syncStore();

        DsMemory mem2 = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem2.setMaxCachedBlocks(Math.max(1, MAX_BLOCKS / 2));
        mem2.syncLoad();
        for (int i = items - 1; i >= 0; i--) {
            assertEquals("mismatch at i=" + i + " pos=" + positions[i] + " after reload",
                values[i], mem2.loadLongOffset(positions[i]));
        }
        DsMemory.CacheStats stats2 = mem2.getCacheStats();
        assertTrue("eviction should have occurred during reload, attempts=" + stats2.getEvictionAttempts(),
            stats2.getEvictionAttempts() > 0);
        assertTrue("active after reload should stay <= max, active=" + stats2.getActiveCachedBlocks()
                + " max=" + mem2.getMaxCachedBlocks(),
            stats2.getActiveCachedBlocks() <= mem2.getMaxCachedBlocks());
    }

    @Test
    public void shouldTrimWhenMaxShrinks() {
        DsMemory mem = new DsMemory(dataFile, HEADER_SIZE, DATA_UNIT_SIZE);
        mem.setMaxCachedBlocks(32);
        for (int i = 0; i < 200; i++) {
            long pos = (long) i * 10_000L;
            mem.storeLongOffset(pos, i * 13L + 7L);
        }
        assertTrue("active before shrink should be > 4, active=" + mem.getActiveCachedBlocks(),
            mem.getActiveCachedBlocks() > 4);

        mem.setMaxCachedBlocks(4);
        assertTrue("active after shrink should be <= 4, active=" + mem.getActiveCachedBlocks(),
            mem.getActiveCachedBlocks() <= 4);

        for (int i = 0; i < 200; i++) {
            long pos = (long) i * 10_000L;
            assertEquals("value mismatch at i=" + i,
                i * 13L + 7L,
                mem.loadLongOffset(pos));
        }
    }
}
