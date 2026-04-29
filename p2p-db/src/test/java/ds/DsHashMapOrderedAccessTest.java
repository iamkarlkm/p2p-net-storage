package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DsHashMapOrderedAccessTest {

    private DsHashMap map;
    private File dataFile;

    @Before
    public void setUp() {
        dataFile = new File("test_dshashmap_ordered.dat");
        deleteWithSidecars(dataFile);
        map = new DsHashMap(dataFile);
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".e16"),
                new File(f.getAbsolutePath() + ".e16.next"),
                new File(f.getAbsolutePath() + ".e16.free"),
                new File(f.getAbsolutePath() + ".e32"),
                new File(f.getAbsolutePath() + ".e32.next"),
                new File(f.getAbsolutePath() + ".e32.free"),
                new File(f.getAbsolutePath() + ".e64"),
                new File(f.getAbsolutePath() + ".e64.next"),
                new File(f.getAbsolutePath() + ".e64.free"),
                new File(f.getAbsolutePath() + ".m32"),
                new File(f.getAbsolutePath() + ".m64")
        };
        for (File x : files) {
            if (x.exists()) {
                x.delete();
            }
        }
    }

    @Test
    public void testOrderedApisConsistency() throws Exception {
        long[] keys = new long[] {
                1L, 2L, 3L, 255L, 256L, 257L,
                1000L, 2000L, 3000L, 40000L, 65535L
        };
        for (long k : keys) {
            map.put(k, k * 10);
        }

        List<Long> orderedKeys = new ArrayList<>();
        Iterator<Map.Entry<Long, Long>> it = map.iterator();
        while (it.hasNext()) {
            orderedKeys.add(it.next().getKey());
        }

        assertFalse(orderedKeys.isEmpty());
        assertEquals("orderedKeys=" + orderedKeys, orderedKeys.get(0), map.first());
        assertEquals("orderedKeys=" + orderedKeys, orderedKeys.get(orderedKeys.size() - 1), map.last());

        for (int i = 0; i < orderedKeys.size(); i++) {
            Long k = orderedKeys.get(i);
            assertEquals(k, map.getByIndex(i));
            assertEquals((long) i, map.indexOf(k.longValue()));
            assertEquals(k.longValue() * 10, map.get(k).longValue());
        }

        assertEquals(-1L, map.indexOf(123456789L));

        int start = 2;
        int count = 5;
        List<Map.Entry<Long, Long>> page = map.range(start, count);
        assertEquals(count, page.size());
        for (int i = 0; i < count; i++) {
            assertEquals(orderedKeys.get(start + i), page.get(i).getKey());
        }

        Map.Entry<Long, Long> firstEntry = map.getEntryByIndex(0);
        assertNotNull(firstEntry);
        assertEquals(orderedKeys.get(0), firstEntry.getKey());
        assertEquals(map.get(firstEntry.getKey()), firstEntry.getValue());
    }

    @Test
    public void testIteratorRemoveUpdatesOrder() throws Exception {
        map.put(1L, 10L);
        map.put(257L, 2570L);
        map.put(513L, 5130L);

        Iterator<Map.Entry<Long, Long>> it = map.iterator();
        assertTrue(it.hasNext());
        Long removedKey = it.next().getKey();
        it.remove();

        assertNull(map.get(removedKey));
        assertEquals(-1L, map.indexOf(removedKey.longValue()));
        assertNotEquals(removedKey, map.first());
    }
}
