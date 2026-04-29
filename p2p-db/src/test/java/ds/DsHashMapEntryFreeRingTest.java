package ds;

import com.q3lives.ds.collections.DsHashMap;
import java.io.File;
import org.junit.Test;

import static org.junit.Assert.*;

public class DsHashMapEntryFreeRingTest {

    private static void deleteWithSidecars(File f) {
        File[] files = new File[] {
                f,
                new File(f.getAbsolutePath() + ".e16"),
                new File(f.getAbsolutePath() + ".e16.next"),
                new File(f.getAbsolutePath() + ".e16.free"),
                new File(f.getAbsolutePath() + ".e16.free.tmp"),
                new File(f.getAbsolutePath() + ".e32"),
                new File(f.getAbsolutePath() + ".e32.next"),
                new File(f.getAbsolutePath() + ".e32.free"),
                new File(f.getAbsolutePath() + ".e32.free.tmp"),
                new File(f.getAbsolutePath() + ".e64"),
                new File(f.getAbsolutePath() + ".e64.next"),
                new File(f.getAbsolutePath() + ".e64.free"),
                new File(f.getAbsolutePath() + ".e64.free.tmp"),
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
    public void testReuseAfterRemove() throws Exception {
        File mapFile = new File("test_dshashmap_entry_free_ring.map");
        deleteWithSidecars(mapFile);
        DsHashMap map = new DsHashMap(mapFile);
        map.clear();

        int n = 1000;
        for (long i = 0; i < n; i++) {
            map.put(i, i);
        }
        long usedBefore = map.getStoreUsed();

        for (long i = 0; i < n; i += 2) {
            assertNotNull("remove failed for i=" + i, map.remove(i));
            assertNull(map.get(i));
        }
        assertEquals(500, map.sizeLong());
        assertEquals(usedBefore, map.getStoreUsed());

        for (long i = 0; i < n; i += 2) {
            map.put(i, i);
            assertEquals(Long.valueOf(i), map.get(i));
        }
        assertEquals(n, map.sizeLong());
        assertEquals("应复用回收的 entryId，避免继续增长", usedBefore, map.getStoreUsed());
    }
}
