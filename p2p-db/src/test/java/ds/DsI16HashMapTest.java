package ds;

import com.q3lives.ds.collections.DsI16HashMap;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DsI16HashMapTest {

    private DsI16HashMap map;

    @Before
    public void setUp() {
        File f = new File("test_dsi16hashmap.dat");
        deleteWithSidecars(f);
        map = new DsI16HashMap(f);
    }

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".k16")
        };
        for (File x : files) {
            if (x.exists()) {
                x.delete();
            }
        }
    }

    @Test
    public void testPutGetRemoveWithSharedFirstByte() throws Exception {
        short k1 = 1;
        short k2 = (short) 257;

        assertNull(map.put(k1, (short) 10));
        assertEquals(Short.valueOf((short) 10), map.get(k1));

        assertNull(map.put(k2, (short) 20));
        assertEquals(Short.valueOf((short) 10), map.get(k1));
        assertEquals(Short.valueOf((short) 20), map.get(k2));

        assertNotNull(map.remove(k1));
        assertNull(map.get(k1));
        assertEquals(Short.valueOf((short) 20), map.get(k2));

        assertEquals(Short.valueOf((short) 20), map.put(k2, (short) 21));
        assertEquals(Short.valueOf((short) 21), map.get(k2));
    }
}
