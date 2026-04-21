package ds;

import com.q3lives.ds.collections.DsHashSetI64;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashSetI64SevereBugTest {

    private DsHashSetI64 dsHashSet;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        System.setProperty("ds.hashset.debugPaths", "true");
        dataFile = new File("test_dshashset_i64_severe_bug.dat");
        dsHashSet = new DsHashSetI64(dataFile);
        dsHashSet.clear();
    }

    @After
    public void tearDown() throws Exception {
        dsHashSet.close();
        if (dataFile.exists()) {
            dataFile.delete();
        }
        System.clearProperty("ds.hashset.debugPaths");
    }

    @Test
    public void testBugReproBulkRangeContainsMinus50000() throws Exception {
        int count = 50000;
        for (long i = -count; i < count; i++) {
            assertTrue(dsHashSet.add(i));
        }
        assertEquals(count * 2L, dsHashSet.size());
        assertTrue(dsHashSet.contains(-50000L));
        assertTrue(dsHashSet.contains(-50000));
        assertTrue(dsHashSet.contains(Integer.valueOf(-50000)));
        String dump = dsHashSet.debugDump(-50000L);
        assertNotNull(dump);
        assertTrue(dump.contains("key=-50000"));
        assertTrue(dump.contains("pathSlots="));
        assertNotNull(dsHashSet.debugDumpMap(-50000L));
        String json = dsHashSet.debugDumpJson(-50000L);
        assertNotNull(json);
        assertTrue(json.contains("\"key\":-50000"));
    }

    @Test
    public void testDeepSplitDoesNotBreakPath() throws Exception {
        long prefix = 0x0102030405060700L;
        for (int i = 0; i < 256; i++) {
            long k = prefix | (long) i;
            assertTrue(dsHashSet.add(k));
        }
        for (int i = 0; i < 256; i++) {
            long k = prefix | (long) i;
            assertTrue(dsHashSet.contains(k));
        }
        assertEquals(256L, dsHashSet.size());
    }

    @Test
    public void testFastPutCachePrefixReuseDoesNotCorruptTrie() throws Exception {
        long prefix = 0x1122334455660000L;
        for (int hi = 0; hi < 32; hi++) {
            for (int lo = 0; lo < 32; lo++) {
                long k = prefix | ((long) hi << 8) | lo;
                assertTrue(dsHashSet.add(k));
            }
        }
        for (int hi = 0; hi < 32; hi++) {
            for (int lo = 0; lo < 32; lo++) {
                long k = prefix | ((long) hi << 8) | lo;
                assertTrue(dsHashSet.contains(k));
            }
        }
        assertEquals(32L * 32L, dsHashSet.size());
    }

    @Test
    public void testFastPutMultiPrefixCacheDoesNotCorruptTrie() throws Exception {
        long prefixA = 0x0102030405000000L;
        long prefixB = 0x1112131415000000L;
        long[] prefixes = {prefixA, prefixB};
        for (int i = 0; i < 64; i++) {
            assertTrue(dsHashSet.add(prefixA | ((long) i << 8) | i));
            assertTrue(dsHashSet.add(prefixB | ((long) i << 8) | i));
        }
        for (int i = 0; i < 256; i++) {
            assertTrue(dsHashSet.add(prefixA | 0x0000FF00L | i));
            assertTrue(dsHashSet.add(prefixB | 0x0000FF00L | i));
        }
        for (int i = 0; i < 128; i++) {
            assertTrue(dsHashSet.add(prefixA | 0x0000EE00L | i));
            assertTrue(dsHashSet.add(prefixB | 0x0000EE00L | i));
        }
        for (int i = 0; i < 256; i++) {
            assertTrue(dsHashSet.contains(prefixA | 0x0000FF00L | i));
            assertTrue(dsHashSet.contains(prefixB | 0x0000FF00L | i));
        }
        DsHashSetI64.FastPutStats stats = dsHashSet.getFastPutStats();
        assertNotNull(stats);
        assertTrue(stats.lastHitCount() > 0);
        assertTrue(stats.quickCacheSize() > 0);
        assertTrue(stats.quickCacheCapacity() >= stats.quickCacheSize());
        assertEquals(dsHashSet.getFastPutStatsMap().get("quickCacheCapacity"), Integer.valueOf(Math.max(8, Integer.getInteger("ds.hashset.quickCacheSize", 256))));
    }

    @Test
    public void testIntegerOrderTraversalIsNegativeToPositive() throws Exception {
        long[] values = {5L, -2L, 0L, -10L, 3L, Long.MIN_VALUE, Long.MAX_VALUE, -1L};
        for (long v : values) {
            assertTrue(dsHashSet.add(v));
        }

        long[] expected = {Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE};
        assertEquals(Long.valueOf(expected[0]), dsHashSet.first());
        assertEquals(Long.valueOf(expected[expected.length - 1]), dsHashSet.last());
        assertArrayEquals(expected, dsHashSet.toArrayLong());
        assertEquals(Arrays.asList(Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE), dsHashSet.range(0, expected.length));
        assertEquals(Arrays.asList(-2L, -1L, 0L), dsHashSet.range(2, 3));
        assertEquals(Arrays.asList(3L, 5L, Long.MAX_VALUE), dsHashSet.range(5, 10));
        assertTrue(dsHashSet.range(expected.length, 3).isEmpty());
        List<Long> ranged = new ArrayList<>();
        assertEquals(3, dsHashSet.forEachRange(2, 3, ranged::add));
        assertEquals(Arrays.asList(-2L, -1L, 0L), ranged);
        ranged.clear();
        assertEquals(3, dsHashSet.forEachRange(5, 10, ranged::add));
        assertEquals(Arrays.asList(3L, 5L, Long.MAX_VALUE), ranged);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(Long.valueOf(expected[i]), dsHashSet.getByIndex(i));
            assertEquals(i, dsHashSet.indexOf(expected[i]));
            assertEquals(i, dsHashSet.lastIndexOf(expected[i]));
        }

        List<Long> iterated = new ArrayList<>();
        for (Long v : dsHashSet) {
            iterated.add(v);
        }
        assertEquals(Arrays.asList(Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE), iterated);
    }
}
