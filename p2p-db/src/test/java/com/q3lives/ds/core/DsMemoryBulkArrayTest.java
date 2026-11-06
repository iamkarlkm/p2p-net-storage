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

    private static void storeBytesOneByOne(DsMemory mem, long position, byte[] src) {
        for (int i = 0; i < src.length; i++) {
            mem.storeByteOffset(position + i, src[i]);
        }
    }

    @Test
    public void shouldRoundTripBytesWithinSingleBlock() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int len = 2048;
            byte[] original = new byte[len];
            Random r = new Random(2026092201L);
            r.nextBytes(original);
            long position = block / 2L - 1024L;
            storeBytesOneByOne(mem, position, original);
            byte[] loaded = new byte[len];
            mem.loadBytesOffset(position, loaded, 0, len);
            Assert.assertArrayEquals("bytes within single block (dest 0+len)", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripBytesAcrossTwoBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int len = 200;
            byte[] original = new byte[len];
            Random r = new Random(2026092202L);
            r.nextBytes(original);
            long position = block - 100L;
            storeBytesOneByOne(mem, position, original);
            byte[] loaded = new byte[len + 16];
            mem.loadBytesOffset(position, loaded, 8, len);
            byte[] sliced = new byte[len];
            System.arraycopy(loaded, 8, sliced, 0, len);
            Assert.assertArrayEquals("bytes across two blocks (destOffset=8, len=200)", original, sliced);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripBytesAcrossThreeOrMoreBlocks() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int len = 2 * block + 200;
            byte[] original = new byte[len];
            Random r = new Random(2026092203L);
            r.nextBytes(original);
            long position = block - 50L;
            storeBytesOneByOne(mem, position, original);
            byte[] loaded = new byte[len];
            mem.loadBytesOffset(position, loaded);
            Assert.assertArrayEquals("bytes across 3+ blocks (len=131272)", original, loaded);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void shouldRoundTripBytesViaSymmetricStoreBytesOffset() {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            int len = 2 * block + 200;
            byte[] original = new byte[len];
            Random r = new Random(2026092204L);
            r.nextBytes(original);
            long position = block - 50L;
            mem.storeBytesOffset(position, original);
            byte[] loaded = new byte[len + 64];
            mem.loadBytesOffset(position, loaded, 32, len);
            byte[] sliced = new byte[len];
            System.arraycopy(loaded, 32, sliced, 0, len);
            Assert.assertArrayEquals("storeBytesOffset -> loadBytesOffset symmetric (destOffset 32)", original, sliced);
        } finally {
            mem.syncStore();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidDestBounds() {
        DsMemory mem = open(64, 16);
        try {
            byte[] dest = new byte[100];
            mem.loadBytesOffset(0L, dest, 80, 30);
        } finally {
            mem.syncStore();
        }
    }
}
