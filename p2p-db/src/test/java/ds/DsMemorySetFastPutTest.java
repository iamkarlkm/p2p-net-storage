package ds;

import com.q3lives.ds.collections.DsMemorySet;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsMemorySetFastPutTest {
    private static final int HDR_CPU_ENDIAN = 24;
    private static final int CPU_ENDIAN_BIG = 1;
    private static final int CPU_ENDIAN_LITTLE = 2;

    private DsMemorySet memorySet;

    @Before
    public void setUp() {
        System.setProperty("ds.hashset.debugPaths", "true");
        memorySet = new DsMemorySet(null);
        memorySet.clear();
    }

    @After
    public void tearDown() {
        memorySet.clear();
        System.clearProperty("ds.hashset.debugPaths");
    }

    @Test
    public void testContainsSupportsIntegerAndLong() throws Exception {
        int count = 20000;
        for (long i = -count; i < count; i++) {
            assertTrue(memorySet.add(i));
        }
        assertEquals(count * 2L, memorySet.total());
        assertTrue(memorySet.contains(-20000L));
        assertTrue(memorySet.contains(-20000));
        assertTrue(memorySet.contains(Integer.valueOf(-20000)));
        String dump = memorySet.debugDump(-20000L);
        assertNotNull(dump);
        assertTrue(dump.contains("key=-20000"));
    }

    @Test
    public void testFastPutPrefixReuseDoesNotCorruptTrie() throws Exception {
        long prefix = 0x1122334455660000L;
        for (int hi = 0; hi < 32; hi++) {
            for (int lo = 0; lo < 32; lo++) {
                long k = prefix | ((long) hi << 8) | lo;
                assertTrue(memorySet.add(k));
            }
        }
        for (int hi = 0; hi < 32; hi++) {
            for (int lo = 0; lo < 32; lo++) {
                long k = prefix | ((long) hi << 8) | lo;
                assertTrue(memorySet.contains(k));
            }
        }
        assertEquals(32L * 32L, memorySet.total());
        DsMemorySet.FastPutStats stats = memorySet.getFastPutStats();
        assertNotNull(stats);
        assertTrue(stats.lastHitCount() > 0 || stats.quickHitCount() > 0);
    }

    @Test
    public void testIntegerOrderTraversalIsNegativeToPositive() throws Exception {
        long[] values = {5L, -2L, 0L, -10L, 3L, Long.MIN_VALUE, Long.MAX_VALUE, -1L};
        for (long v : values) {
            assertTrue(memorySet.add(v));
        }
        for (long v : values) {
            assertTrue("missing value: " + v, memorySet.contains(v));
        }
        long[] expected = {Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE};
        assertEquals(Long.valueOf(expected[0]), memorySet.first());
        assertEquals(Long.valueOf(expected[expected.length - 1]), memorySet.last());
        assertEquals(Arrays.asList(Long.MIN_VALUE, -10L, -2L), memorySet.range(0, 3));
        assertEquals(Arrays.asList(-2L, -1L, 0L), memorySet.range(2, 3));
        List<Long> ranged = new ArrayList<>();
        assertEquals(3, memorySet.forEachRange(2, 3, ranged::add));
        assertEquals(Arrays.asList(-2L, -1L, 0L), ranged);
        assertArrayEquals(expected, memorySet.toArrayLong());

        List<Long> iterated = new ArrayList<>();
        for (Long v : memorySet) {
            iterated.add(v);
        }
        assertEquals(Arrays.asList(Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE), iterated);
    }

    @Test
    public void testSyncAndReloadKeepsRawLayout() throws Exception {
        File file = File.createTempFile("dsmemoryset-sync", ".bin");
        try {
            assertTrue(file.delete());
            DsMemorySet saved = new DsMemorySet(file);
            for (long i = -5000; i < 5000; i++) {
                assertTrue(saved.add(i));
            }
            saved.sync();

            DsMemorySet loaded = new DsMemorySet(file);
            assertEquals(10000L, loaded.total());
            assertTrue(loaded.contains(-5000L));
            assertTrue(loaded.contains(0L));
            assertTrue(loaded.contains(4999L));
            assertEquals(Long.valueOf(-5000L), loaded.first());
            assertEquals(Long.valueOf(4999L), loaded.last());
            assertEquals(Arrays.asList(-5000L, -4999L, -4998L), loaded.range(0, 3));
        } finally {
            file.delete();
        }
    }

    @Test
    public void testRejectLoadOnCpuEndianMismatch() throws Exception {
        File file = File.createTempFile("dsmemoryset-endian", ".bin");
        try {
            assertTrue(file.delete());
            DsMemorySet saved = new DsMemorySet(file);
            assertTrue(saved.add(1L));
            saved.sync();

            int current = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? CPU_ENDIAN_BIG : CPU_ENDIAN_LITTLE;
            int opposite = current == CPU_ENDIAN_BIG ? CPU_ENDIAN_LITTLE : CPU_ENDIAN_BIG;
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.seek(HDR_CPU_ENDIAN);
                raf.writeInt(opposite);
            }

            try {
                new DsMemorySet(file);
                fail("expected CPU byte order mismatch");
            } catch (RuntimeException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                assertTrue(cause.getMessage().contains("CPU byte order mismatch"));
            }
        } finally {
            file.delete();
        }
    }
}
