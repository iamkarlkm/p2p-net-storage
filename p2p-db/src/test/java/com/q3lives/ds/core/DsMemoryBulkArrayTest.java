package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsMemoryBulkArrayTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dsmemory-bulk-array-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            for (File f : tempDir.listFiles()) {
                f.delete();
            }
            tempDir.delete();
        }
    }

    private DsMemory open(int headerSize, int dataUnitSize) {
        return new DsMemory(new File(tempDir, "bulk-array-test.bin"), headerSize, dataUnitSize);
    }

    @Test
    public void shouldRoundTripLongArrayWithinSingleBlock() {
        DsMemory mem = open(64, 16);
        try {
            int count = 100;
            long[] original = new long[count];
            Random r = new Random(20260809L);
            for (int i = 0; i < count; i++) {
                original[i] = r.nextLong();
            }
            long position = 512L;
            mem.storeLongOffset(position, original);
            long[] loaded = new long[count];
            mem.loadLongOffset(position, loaded);
            Assert.assertArrayEquals("within single block long[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripLongArrayAcrossTwoBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int count = 9000;
            long[] original = new long[count];
            for (int i = 0; i < count; i++) {
                original[i] = (long) i * 1315423911L + 0x9E3779B97F4A7C15L;
            }
            long position = block - 1024L;
            mem.storeLongOffset(position, original);
            long[] loaded = new long[count];
            mem.loadLongOffset(position, loaded);
            Assert.assertArrayEquals("across two blocks long[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripLongArrayAcrossThreeOrMoreBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int count = 30000;
            long[] original = new long[count];
            Random r = new Random(202608091L);
            for (int i = 0; i < count; i++) {
                original[i] = r.nextLong();
            }
            long position = 2 * block - 512L;
            mem.storeLongOffset(position, original);
            long[] loaded = new long[count];
            mem.loadLongOffset(position, loaded);
            Assert.assertArrayEquals("across three+ blocks long[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripIntArrayWithinSingleBlock() {
        DsMemory mem = open(64, 16);
        try {
            int count = 200;
            int[] original = new int[count];
            Random r = new Random(202608092L);
            for (int i = 0; i < count; i++) {
                original[i] = r.nextInt();
            }
            long position = 1024L;
            mem.storeIntOffset(position, original);
            int[] loaded = new int[count];
            loadIntOffsetViaPublic(mem, position, loaded);
            Assert.assertArrayEquals("within single block int[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripIntArrayAcrossTwoBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int count = 20000;
            int[] original = new int[count];
            for (int i = 0; i < count; i++) {
                original[i] = i * 265443576;
            }
            long position = block - 1024L;
            mem.storeIntOffset(position, original);
            int[] loaded = new int[count];
            loadIntOffsetViaPublic(mem, position, loaded);
            Assert.assertArrayEquals("across two blocks int[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripIntArrayAcrossThreeOrMoreBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int count = 70000;
            int[] original = new int[count];
            Random r = new Random(202608093L);
            for (int i = 0; i < count; i++) {
                original[i] = r.nextInt();
            }
            long position = 2 * block - 512L;
            mem.storeIntOffset(position, original);
            int[] loaded = new int[count];
            loadIntOffsetViaPublic(mem, position, loaded);
            Assert.assertArrayEquals("across three+ blocks int[]", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldPreserveHighBitsOfLongAfterMultiBlockRead() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int count = 9000;
            long[] original = new long[count];
            for (int i = 0; i < count; i++) {
                original[i] = 0xDEADBEEFCAFEBABEL | ((long) i << 4);
            }
            long position = block - 1024L;
            mem.storeLongOffset(position, original);
            long[] loaded = new long[count];
            mem.loadLongOffset(position, loaded);
            for (int i = 0; i < count; i++) {
                Assert.assertEquals("high 32 bits preserved at index " + i,
                    original[i], loaded[i]);
                Assert.assertTrue("high bits not truncated at index " + i,
                    (loaded[i] & 0xFFFFFFFF00000000L) != 0L);
            }
        } finally {
            mem.syncStore();
        }
    }

    private static void loadIntOffsetViaPublic(DsMemory mem, long position, int[] dest) {
        for (int i = 0; i < dest.length; i++) {
            dest[i] = (int) mem.loadU32ByOffset(position + (long) i * 4L);
        }
    }
}
