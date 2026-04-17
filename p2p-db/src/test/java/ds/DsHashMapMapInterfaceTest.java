package ds;

import com.q3lives.ds.collections.DsHashMapI64;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.junit.Assert.*;

public class DsHashMapMapInterfaceTest {

    private DsHashMapI64 backing;
    private Map<Long, Long> map;

    @Before
    public void setUp() throws Exception {
        File dataFile = new File("test_dshashmap_map_iface.dat");
        deleteWithSidecars(dataFile);
        backing = new DsHashMapI64(dataFile);
        map = backing;
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".k16"),
                new File(f.getAbsolutePath() + ".k32"),
                new File(f.getAbsolutePath() + ".k64"),
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
    public void testBasicMapOps() {
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
    }

    @Test
    public void testPutAllAndViews() {
        HashMap<Long, Long> src = new HashMap<>();
        src.put(1L, 100L);
        src.put(2L, 200L);
        map.putAll(src);

        assertEquals(2, map.size());
        assertTrue(map.keySet().contains(1L));
        assertTrue(map.values().contains(200L));

        Iterator<Long> kit = map.keySet().iterator();
        Long k = kit.next();
        kit.remove();
        assertFalse(map.containsKey(k));
        assertEquals(1, map.size());

        Map.Entry<Long, Long> e = map.entrySet().iterator().next();
        Long old = e.setValue(999L);
        assertNotNull(old);
        assertEquals(Long.valueOf(999L), map.get(e.getKey()));
    }

    @Test
    public void testClearAlsoClearsPromotedLevel() {
        map.put(1L, 10L);
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
    }

    @Test
    public void testGetRemoveDoNotMatchPrefixOnly() {
        map.put(1L, 10L);
        assertNull(map.get(257L));
        assertNull(map.remove(257L));
        assertEquals(Long.valueOf(10L), map.get(1L));
        assertTrue(map.containsKey(1L));
        assertFalse(map.containsKey(257L));
    }
}
