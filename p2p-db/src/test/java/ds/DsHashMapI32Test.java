package ds;

import com.q3lives.ds.collections.DsHashMap;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DsHashMapI32Test {

    private DsHashMap map;

    @Before
    public void setUp() {
        File f = new File("test_dsi32hashmap.dat");
        deleteWithSidecars(f);
        map = new DsHashMap(f);
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
    public void testPutGetRemoveWithPromotion() throws Exception {
        int k1 = 1;
        int k2 = 65537;

        assertNull(map.put(k1, 10));
        assertEquals(Integer.valueOf(10), map.get(k1));

        //测试同位置插入新值。
        assertNotNull(map.put(k1, 20));
        assertEquals(Integer.valueOf(20), map.get(k1));
        
        assertNull(map.put(k2, 20));
        assertEquals(Integer.valueOf(20), map.get(k2));

        assertNotNull(map.remove(k1));
        assertNull(map.get(k1));
        assertEquals(Integer.valueOf(20), map.get(k2));

        assertEquals(Integer.valueOf(20), map.put(k2, 21));
        assertEquals(Integer.valueOf(21), map.get(k2));
    }
}
