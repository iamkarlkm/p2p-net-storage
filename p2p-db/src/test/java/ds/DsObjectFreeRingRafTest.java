package ds;

import com.q3lives.ds.core.DsFreeRing;
import com.q3lives.ds.core.DsObject;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class DsObjectFreeRingRafTest {

    @Test
    public void testOfferPollFifoAndPersistAndExpand() throws Exception {
        File base = File.createTempFile("test_free_ring_", ".dat");
        base.deleteOnExit();
        File freeFile = new File(base.getAbsolutePath() + ".free");
        //File tmpFile = new File(base.getAbsolutePath() + ".free.tmp");
        if (freeFile.exists()) {
            freeFile.delete();
        }
//        if (tmpFile.exists()) {
//            tmpFile.delete();
//        }

        try (DsFreeRing ring = new DsFreeRing(freeFile, 2)) {
            assertEquals(0L, ring.count());
            assertTrue(ring.offer(10L));
            assertTrue(ring.offer(20L));
            assertTrue(ring.offer(30L)); // 触发扩容
            assertEquals(3L, ring.count());
            assertEquals(10L, ring.poll());
            assertEquals(20L, ring.poll());
            assertEquals(30L, ring.poll());
            assertEquals(-1L, ring.poll());
            assertEquals(0L, ring.count());

            assertTrue(ring.offer(1L));
            assertTrue(ring.offer(2L));
        }

        try (DsFreeRing ring2 = new DsFreeRing(freeFile, 2)) {
            assertEquals(2L, ring2.count());
            assertEquals(1L, ring2.poll());
            assertEquals(2L, ring2.poll());
            assertEquals(-1L, ring2.poll());
            assertEquals(0L, ring2.count());
        }
    }
}

