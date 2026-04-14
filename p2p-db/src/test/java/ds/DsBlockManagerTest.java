package ds;

import com.q3lives.ds.bucket.DsBlockManager;
import com.q3lives.ds.core.DsObject;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsBlockManagerTest {

    @Test
    public void testAllocateReleaseReuseAndReload() {
        File dir = new File("target/ds-block-test-" + System.nanoTime());
        dir.mkdirs();
        File data = new File(dir, "data.dat");
        File meta = new File(dir, "data.mgr");

        DsObject dataStore = new DsObject(data, 128);
        DsBlockManager mgr = new DsBlockManager(meta, dataStore, 128, 1L);

        long a = mgr.getNewId();
        long b = mgr.getNewId();
        assertEquals(1L, a);
        assertEquals(2L, b);

        mgr.releaseId(a);
        long c = mgr.getNewId();
        assertEquals(1L, c);

        DsBlockManager mgr2 = new DsBlockManager(meta, dataStore, 128, 1L);
        long d = mgr2.getNewId();
        assertEquals(3L, d);

        mgr2.releaseId(2L);
        long e = mgr2.getNewId();
        assertEquals(2L, e);
    }
}
