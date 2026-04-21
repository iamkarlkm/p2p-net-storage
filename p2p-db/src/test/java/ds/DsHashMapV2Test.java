package ds;

import com.q3lives.ds.collections.DsHashMapV2;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapV2Test {
    private File dataFile;

    @Before
    public void setUp() throws IOException {
        File tempFile = File.createTempFile("test_dshashmap_v2_", ".dat");
        assertTrue(tempFile.delete());
        dataFile = tempFile;
        deleteWithSidecars(dataFile);
    }

    @After
    public void tearDown() {
        deleteWithSidecars(dataFile);
    }

    @Test
    public void testPutGetUpdateRemove() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertNull(map.put(100L, 200L));
        assertEquals(Long.valueOf(200L), map.get(100L));
        assertEquals(Long.valueOf(200L), map.put(100L, 300L));
        assertEquals(Long.valueOf(300L), map.get(100L));
        assertEquals(1, map.size());

        assertEquals(Long.valueOf(300L), map.remove(100L));
        assertNull(map.get(100L));
        assertTrue(map.isEmpty());

        assertNull(map.remove(100L));
        map.close();
    }

    @Test
    public void testPromoteFrom16To256() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash);
        long k1 = 1L;
        long k2 = 2L;
        long k3 = 3L;
        long k4 = 4L;
        long k5 = 5L;

        assertNull(map.put(k1, 101L));
        assertNull(map.put(k2, 102L)); // 16 -> 32
        assertNull(map.put(k3, 103L)); // 32 -> 64
        assertNull(map.put(k4, 104L)); // 64 -> 128
        assertNull(map.put(k5, 105L)); // 128 -> 256

        assertEquals(5, map.size());
        assertEquals(Long.valueOf(101L), map.get(k1));
        assertEquals(Long.valueOf(102L), map.get(k2));
        assertEquals(Long.valueOf(103L), map.get(k3));
        assertEquals(Long.valueOf(104L), map.get(k4));
        assertEquals(Long.valueOf(105L), map.get(k5));

        assertEquals(Long.valueOf(104L), map.remove(k4));
        assertNull(map.get(k4));
        assertEquals(Long.valueOf(101L), map.get(k1));
        assertEquals(Long.valueOf(103L), map.get(k3));
        assertEquals(Long.valueOf(105L), map.get(k5));
        assertEquals(4, map.size());

        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(k1));
        assertNull(map.get(k2));
        assertNull(map.get(k3));
        assertNull(map.get(k5));
        map.close();
    }

    @Test
    public void testFreeRingReusesFreedEntry() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertNull(map.put(10L, 100L));
        assertNull(map.put(20L, 200L));
        DsHashMapV2.DebugStats beforeRemove = map.getDebugStats();
        assertEquals(3L, beforeRemove.nextEntryId());
        assertEquals(0L, beforeRemove.freeEntryCount());

        assertEquals(Long.valueOf(100L), map.remove(10L));
        DsHashMapV2.DebugStats afterRemove = map.getDebugStats();
        assertEquals(3L, afterRemove.nextEntryId());
        assertEquals(1L, afterRemove.freeEntryCount());
        assertEquals(1L, afterRemove.usedEntryCount());

        assertNull(map.put(30L, 300L));
        DsHashMapV2.DebugStats afterReuse = map.getDebugStats();
        assertEquals(3L, afterReuse.nextEntryId());
        assertEquals(0L, afterReuse.freeEntryCount());
        assertEquals(2L, afterReuse.usedEntryCount());
        assertEquals(Long.valueOf(200L), map.get(20L));
        assertEquals(Long.valueOf(300L), map.get(30L));
        map.close();
    }

    @Test
    public void testClearResetsEntryStoreHead() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertNull(map.put(100L, 1000L));
        assertNull(map.put(200L, 2000L));
        assertEquals(3L, map.getDebugStats().nextEntryId());

        map.clear();
        DsHashMapV2.DebugStats afterClear = map.getDebugStats();
        assertEquals(0L, afterClear.totalSize());
        assertEquals(1L, afterClear.nextEntryId());
        assertEquals(0L, afterClear.freeEntryId());
        assertEquals(0L, afterClear.freeEntryCount());
        assertEquals(0L, afterClear.usedEntryCount());

        assertNull(map.put(300L, 3000L));
        DsHashMapV2.DebugStats afterRewrite = map.getDebugStats();
        assertEquals(1L, afterRewrite.totalSize());
        assertEquals(2L, afterRewrite.nextEntryId());
        assertEquals(1L, afterRewrite.usedEntryCount());
        assertEquals(Long.valueOf(3000L), map.get(300L));
        map.close();
    }

    @Test
    public void testDebugStatsMapContainsExpectedFields() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertNull(map.put(11L, 111L));
        assertNull(map.put(22L, 222L));

        Map<String, Object> stats = map.getDebugStatsMap();
        assertEquals(2L, ((Number) stats.get("totalSize")).longValue());
        assertEquals(0L, ((Number) stats.get("freeEntryCount")).longValue());
        assertEquals(2L, ((Number) stats.get("usedEntryCount")).longValue());
        assertTrue(((Number) stats.get("nextEntryId")).longValue() >= 3L);
        map.close();
    }

    @Test
    public void testDebugDumpShowsPromotionPath() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash);
        assertNull(map.put(1L, 101L));
        assertNull(map.put(2L, 102L));
        assertNull(map.put(3L, 103L));
        assertNull(map.put(4L, 104L));
        assertNull(map.put(5L, 105L));

        String dump = map.debugDump(5L);
        assertNotNull(dump);
        assertTrue(dump.contains("DsHashMapV2 key=5"));
        assertTrue(dump.contains("state=NEXT_LEVEL"));
        assertTrue(dump.contains("state=VALUE"));

        Map<String, Object> dumpMap = map.debugDumpMap(5L);
        assertEquals(Long.valueOf(105L), dumpMap.get("value"));
        Object[] levels = (Object[]) dumpMap.get("levels");
        assertTrue(levels.length >= 5);
        boolean hasNextLevel = false;
        for (Object current : levels) {
            Map<?, ?> row = (Map<?, ?>) current;
            if ("NEXT_LEVEL".equals(row.get("state"))) {
                hasNextLevel = true;
                break;
            }
        }
        assertTrue(hasNextLevel);
        map.close();
    }

    @Test
    public void testDebugDumpJsonContainsStructuredFields() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash);
        assertNull(map.put(1L, 101L));
        assertNull(map.put(2L, 102L));
        assertNull(map.put(3L, 103L));
        assertNull(map.put(4L, 104L));
        assertNull(map.put(5L, 105L));

        String json = map.debugDumpJson(5L);
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("\"key\":5"));
        assertTrue(json.contains("\"value\":105"));
        assertTrue(json.contains("\"hashHex\""));
        assertTrue(json.contains("\"levels\""));
        assertTrue(json.contains("\"state\":\"NEXT_LEVEL\""));
        assertTrue(json.contains("\"state\":\"VALUE\""));
        map.close();
    }

    @Test
    public void testBasicMapInterfaceOps() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertTrue(map.isEmpty());
        assertEquals(0, map.size());

        assertNull(map.put(1L, 10L));
        assertEquals(Long.valueOf(10L), map.get(1L));
        assertTrue(map.containsKey(1L));
        assertFalse(map.containsKey(2L));
        assertTrue(map.containsValue(10L));

        assertEquals(Long.valueOf(10L), map.put(1L, 11L));
        assertEquals(Long.valueOf(11L), map.get(1L));
        assertEquals(1, map.size());

        assertEquals(Long.valueOf(11L), map.remove(1L));
        assertNull(map.get(1L));
        assertFalse(map.containsKey(1L));
        map.close();
    }

    @Test
    public void testPutAllAndViews() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        HashMap<Long, Long> src = new HashMap<>();
        src.put(1L, 100L);
        src.put(2L, 200L);
        src.put(3L, 300L);
        map.putAll(src);

        assertEquals(3, map.size());
        assertTrue(map.keySet().contains(1L));
        assertTrue(map.values().contains(200L));

        Iterator<Long> keyIterator = map.keySet().iterator();
        Long removedKey = keyIterator.next();
        keyIterator.remove();
        assertFalse(map.containsKey(removedKey));
        assertEquals(2, map.size());

        Iterator<Long> valueIterator = map.values().iterator();
        Long removedValue = valueIterator.next();
        valueIterator.remove();
        assertFalse(map.containsValue(removedValue));
        assertEquals(1, map.size());

        Map.Entry<Long, Long> entry = map.entrySet().iterator().next();
        Long oldValue = entry.setValue(999L);
        assertNotNull(oldValue);
        assertEquals(Long.valueOf(999L), map.get(entry.getKey()));
        map.close();
    }

    @Test
    public void testClearAndPrefixIsolationForMapView() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        map.put(1L, 10L);
        assertNull(map.get(257L));
        assertNull(map.remove(257L));
        assertEquals(Long.valueOf(10L), map.get(1L));

        map.put(65537L, 20L);
        map.put(4294967297L, 30L);
        assertEquals(Long.valueOf(10L), map.get(1L));
        assertEquals(Long.valueOf(20L), map.get(65537L));
        assertEquals(Long.valueOf(30L), map.get(4294967297L));

        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(1L));
        assertNull(map.get(65537L));
        assertNull(map.get(4294967297L));
        map.close();
    }

    @Test
    public void testPersistenceAfterReopenDefaultProvider() throws Exception {
        DsHashMapV2 writer = new DsHashMapV2(dataFile);
        assertNull(writer.put(1L, 10L));
        assertNull(writer.put(65537L, 20L));
        assertNull(writer.put(4294967297L, 30L));
        assertEquals(3, writer.size());
        writer.close();

        DsHashMapV2 reader = new DsHashMapV2(dataFile);
        assertEquals(3, reader.size());
        assertEquals(Long.valueOf(10L), reader.get(1L));
        assertEquals(Long.valueOf(20L), reader.get(65537L));
        assertEquals(Long.valueOf(30L), reader.get(4294967297L));
        reader.close();
    }

    @Test
    public void testPersistenceAfterReopenWithCustomProvider() throws Exception {
        DsHashMapV2 writer = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash);
        assertNull(writer.put(1L, 101L));
        assertNull(writer.put(2L, 102L));
        assertNull(writer.put(3L, 103L));
        assertNull(writer.put(4L, 104L));
        assertNull(writer.put(5L, 105L));
        assertEquals(5, writer.size());
        writer.close();

        DsHashMapV2 readerSameProvider = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash);
        assertEquals(5, readerSameProvider.size());
        assertEquals(Long.valueOf(101L), readerSameProvider.get(1L));
        assertEquals(Long.valueOf(102L), readerSameProvider.get(2L));
        assertEquals(Long.valueOf(103L), readerSameProvider.get(3L));
        assertEquals(Long.valueOf(104L), readerSameProvider.get(4L));
        assertEquals(Long.valueOf(105L), readerSameProvider.get(5L));
        readerSameProvider.close();

        DsHashMapV2 readerDifferentProvider = new DsHashMapV2(dataFile);
        assertNull(readerDifferentProvider.get(1L));
        assertNull(readerDifferentProvider.get(5L));
        readerDifferentProvider.close();
    }

    @Test
    public void testFreeRingPersistsAcrossReopen() throws Exception {
        DsHashMapV2 writer = new DsHashMapV2(dataFile);
        assertNull(writer.put(10L, 100L));
        assertNull(writer.put(20L, 200L));
        assertEquals(Long.valueOf(100L), writer.remove(10L));
        DsHashMapV2.DebugStats beforeClose = writer.getDebugStats();
        assertEquals(1L, beforeClose.freeEntryCount());
        writer.close();

        DsHashMapV2 reader = new DsHashMapV2(dataFile);
        DsHashMapV2.DebugStats afterReopen = reader.getDebugStats();
        assertEquals(1L, afterReopen.freeEntryCount());
        assertEquals(1L, afterReopen.usedEntryCount());
        assertEquals(Long.valueOf(200L), reader.get(20L));

        assertNull(reader.put(30L, 300L));
        DsHashMapV2.DebugStats afterReuse = reader.getDebugStats();
        assertEquals(0L, afterReuse.freeEntryCount());
        assertEquals(2L, afterReuse.usedEntryCount());
        assertEquals(Long.valueOf(300L), reader.get(30L));
        reader.close();
    }

    @Test
    public void testRejectReopenOnProviderIdMismatch() throws Exception {
        DsHashMapV2 writer = new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash, 123L);
        assertNull(writer.put(1L, 101L));
        writer.close();

        try {
            new DsHashMapV2(dataFile, DsHashMapV2Test::fillTieredHash, 456L);
            fail("should reject reopen on hash provider id mismatch");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("hash provider id mismatch"));
        }
    }

    @Test
    public void testSyncModeConfiguration() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile);
        assertEquals("MANUAL", map.getSyncModeMap().get("mode"));

        map.setSyncModeWriteRequests(2);
        assertEquals("WRITE_REQUESTS", map.getSyncModeMap().get("mode"));
        assertEquals(Boolean.FALSE, map.getSyncModeMap().get("rootBackgroundFlushEnabled"));

        map.put(1L, 10L);
        map.put(2L, 20L);

        map.setSyncModeSeconds(1);
        assertEquals("SECONDS", map.getSyncModeMap().get("mode"));
        assertEquals(Boolean.TRUE, map.getSyncModeMap().get("rootBackgroundFlushEnabled"));
        assertEquals(6, ((Number) map.getSyncModeMap().get("enabledObjectCount")).intValue());

        map.disableSyncMode();
        assertEquals("MANUAL", map.getSyncModeMap().get("mode"));
        assertEquals(Boolean.FALSE, map.getSyncModeMap().get("rootBackgroundFlushEnabled"));

        map.setSyncModeStrong100ms();
        assertEquals("STRONG_100MS", map.getSyncModeMap().get("mode"));
        assertEquals(Boolean.TRUE, map.getSyncModeMap().get("rootBackgroundFlushEnabled"));

        map.setSyncModeSystemAuto();
        assertEquals("SYSTEM_AUTO", map.getSyncModeMap().get("mode"));
        assertEquals(Boolean.TRUE, map.getSyncModeMap().get("rootBackgroundFlushEnabled"));
        assertEquals(6, ((Number) map.getSyncModeMap().get("enabledObjectCount")).intValue());
        map.close();
    }

    @Test
    public void testOrderedTraversalAndEntryPagination() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile, DsHashMapV2Test::fillKeyAsHash, 1L);
        long[] keys = new long[] {Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE};
        for (int i = 0; i < keys.length; i++) {
            assertNull(map.put(keys[i], (long) i));
        }

        ArrayList<Long> iterKeys = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : map.entrySet()) {
            iterKeys.add(entry.getKey());
        }
        assertEquals(Arrays.asList(Long.MIN_VALUE, -10L, -2L, -1L, 0L, 3L, 5L, Long.MAX_VALUE), iterKeys);

        List<Map.Entry<Long, Long>> page = map.rangeEntries(2, 3);
        assertEquals(3, page.size());
        assertEquals(Long.valueOf(-2L), page.get(0).getKey());
        assertEquals(Long.valueOf(2L), page.get(0).getValue());
        assertEquals(Long.valueOf(-1L), page.get(1).getKey());
        assertEquals(Long.valueOf(3L), page.get(1).getValue());
        assertEquals(Long.valueOf(0L), page.get(2).getKey());
        assertEquals(Long.valueOf(4L), page.get(2).getValue());

        ArrayList<Long> rangedKeys = new ArrayList<>();
        int emitted = map.forEachEntryRange(2, 3, (k, v) -> rangedKeys.add(k));
        assertEquals(3, emitted);
        assertEquals(Arrays.asList(-2L, -1L, 0L), rangedKeys);
        map.close();
    }

    @Test
    public void testFastPutHasHits() throws Exception {
        DsHashMapV2 map = new DsHashMapV2(dataFile, DsHashMapV2Test::fillKeyAsHash, 1L);
        map.setQuickCacheSize(32);
        for (long key = -512L; key < 0L; key++) {
            assertNull(map.put(key, key));
        }
        DsHashMapV2.FastPutStats stats = map.getFastPutStats();
        assertTrue(stats.missCount() > 0);
        assertTrue(stats.lastHitCount() + stats.quickHitCount() > 0);
        map.close();
    }

    private static void fillKeyAsHash(long key, byte[] out) {
        Arrays.fill(out, (byte) 0);
        out[0] = (byte) (key >>> 56);
        out[1] = (byte) (key >>> 48);
        out[2] = (byte) (key >>> 40);
        out[3] = (byte) (key >>> 32);
        out[4] = (byte) (key >>> 24);
        out[5] = (byte) (key >>> 16);
        out[6] = (byte) (key >>> 8);
        out[7] = (byte) key;
    }

    private static void fillTieredHash(long key, byte[] out) {
        Arrays.fill(out, (byte) 0);
        switch ((int) key) {
            case 1 -> fillBytes(out,
                0x11, 0x22,
                0x33, 0x44,
                0x55, 0x66, 0x77, 0x01,
                0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38,
                0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48
            );
            case 2 -> fillBytes(out,
                0x11, 0x22,
                0x66, 0x77,
                0x10, 0x11, 0x12, 0x13,
                0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B,
                0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23,
                0x24, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B
            );
            case 3 -> fillBytes(out,
                0x11, 0x22,
                0x33, 0x44,
                0x88, 0x89, 0x8A, 0x8B,
                0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
                0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
                0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78
            );
            case 4 -> fillBytes(out,
                0x11, 0x22,
                0x33, 0x44,
                0x55, 0x66, 0x77, 0x01,
                0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98,
                0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8,
                0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8
            );
            case 5 -> fillBytes(out,
                0x11, 0x22,
                0x33, 0x44,
                0x55, 0x66, 0x77, 0x01,
                0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8,
                0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8
            );
            default -> throw new IllegalArgumentException("unsupported test key: " + key);
        }
    }

    private static void fillBytes(byte[] out, int... values) {
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
    }

    private static void deleteWithSidecars(File file) {
        File[] files = new File[] {
            file,
            new File(file.getAbsolutePath() + ".entries"),
            new File(file.getAbsolutePath() + ".m32"),
            new File(file.getAbsolutePath() + ".m64"),
            new File(file.getAbsolutePath() + ".m128"),
            new File(file.getAbsolutePath() + ".m256")
        };
        for (File current : files) {
            if (current.exists()) {
                current.delete();
            }
        }
    }
}
