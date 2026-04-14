package ds;

import com.q3lives.ds.collections.DsHashSet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;

import static org.junit.Assert.*;

public class DsHashSetTest {

    private DsHashSet dsHashSet;
    private File dataFile;

    @Before
    public void setUp() throws Exception {
        dataFile = new File("test_dshashset.dat");
        if (dataFile.exists()) {
            dataFile.delete();
        }
        dsHashSet = new DsHashSet(dataFile);
    }

    @After
    public void tearDown() throws Exception {
        dsHashSet.close();
    }

    @Test
    public void testAddContainsRemove() throws Exception {
        assertTrue(dsHashSet.isEmpty());
        assertEquals(0, dsHashSet.size());

        assertTrue(dsHashSet.add(1L));
        assertTrue(dsHashSet.contains(1L));
        assertEquals(1, dsHashSet.size());

        assertFalse(dsHashSet.add(1L));
        assertEquals(1, dsHashSet.size());

        assertTrue(dsHashSet.add(2L));
        assertTrue(dsHashSet.contains(2L));
        assertEquals(2, dsHashSet.size());

        assertTrue(dsHashSet.remove(1L));
        assertFalse(dsHashSet.contains(1L));
        assertEquals(1, dsHashSet.size());

        assertFalse(dsHashSet.remove(1L));
        assertFalse(dsHashSet.remove("x"));
    }

    @Test
    public void testToArrayAndIterator() throws Exception {
        dsHashSet.add(10L);
        dsHashSet.add(20L);
        dsHashSet.add(30L);

        Object[] arr = dsHashSet.toArray();
        assertEquals(3, arr.length);
        for (Object o : arr) {
            assertTrue(o instanceof Long);
            assertTrue(dsHashSet.contains(o));
        }

        long[] longs = dsHashSet.toArrayLong();
        assertEquals(3, longs.length);
        for (long v : longs) {
            assertTrue(dsHashSet.contains(v));
        }

        int count = 0;
        for (Long v : dsHashSet) {
            assertNotNull(v);
            assertTrue(dsHashSet.contains(v));
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    public void testBulkOps() throws Exception {
        assertTrue(dsHashSet.addAll(java.util.Arrays.asList(1L, 2L, 3L)));
        assertFalse(dsHashSet.addAll(java.util.Arrays.asList(1L, 2L)));
        assertTrue(dsHashSet.containsAll(java.util.Arrays.asList(1L, 2L)));

        assertTrue(dsHashSet.removeAll(java.util.Arrays.asList(2L, 999L)));
        assertFalse(dsHashSet.contains(2L));
        assertEquals(2, dsHashSet.size());

        assertTrue(dsHashSet.retainAll(java.util.Arrays.asList(1L)));
        assertTrue(dsHashSet.contains(1L));
        assertFalse(dsHashSet.contains(3L));
        assertEquals(1, dsHashSet.size());
    }

    @Test
    public void testClear() throws Exception {
        dsHashSet.add(1L);
        dsHashSet.add(2L);
        assertFalse(dsHashSet.isEmpty());
        dsHashSet.clear();
        assertTrue(dsHashSet.isEmpty());
        assertEquals(0, dsHashSet.size());
        assertFalse(dsHashSet.contains(1L));
        assertFalse(dsHashSet.contains(2L));
    }

    @Test
    public void testLiveIteratorReflectsChanges() throws Exception {
        dsHashSet.add(1L);
        dsHashSet.add(2L);

        Iterator<Long> it = dsHashSet.iterator();
        assertTrue(it.hasNext());
        Long first = it.next();
        assertNotNull(first);

        dsHashSet.add(3L);
        dsHashSet.remove(2L);

        HashSet<Long> rest = new HashSet<>();
        while (it.hasNext()) {
            rest.add(it.next());
        }

        assertTrue(rest.contains(3L));
        assertFalse(rest.contains(2L));
    }
}
