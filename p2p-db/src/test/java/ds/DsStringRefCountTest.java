package ds;

import com.q3lives.ds.core.DsString;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsStringRefCountTest {

    @Test
    public void testRetainRelease() throws Exception {
        File dir = new File("target/ds-string-refcount-test-" + System.nanoTime());
        dir.mkdirs();
        DsString ds = new DsString(dir.getPath());

        long id = ds.add("v");
        assertEquals(1, ds.refCount(id));
        assertEquals("v", ds.get(id));

        ds.retain(id);
        assertEquals(2, ds.refCount(id));
        assertEquals("v", ds.get(id));

        ds.release(id);
        assertEquals(1, ds.refCount(id));
        assertEquals("v", ds.get(id));

        ds.release(id);
        assertEquals(0, ds.refCount(id));
        assertNull(ds.get(id));
        ds.close();
    }

    @Test
    public void testContainsDoesNotChangeRefCount() throws Exception {
        File dir = new File("target/ds-string-contains-refcount-test-" + System.nanoTime());
        dir.mkdirs();
        DsString ds = new DsString(dir.getPath());
        try {
            long id = ds.add("hello");
            assertEquals(1, ds.refCount(id));
            assertTrue(ds.contains("hello"));
            assertEquals(1, ds.refCount(id));

            ds.retain(id);
            assertEquals(2, ds.refCount(id));
            for (int i = 0; i < 5; i++) {
                assertTrue(ds.contains("hello"));
            }
            assertEquals(2, ds.refCount(id));

            ds.release(id);
            assertEquals(1, ds.refCount(id));
            assertTrue(ds.contains("hello"));
            assertEquals(1, ds.refCount(id));

            ds.release(id);
            assertEquals(0, ds.refCount(id));
            assertFalse(ds.contains("hello"));
        } finally {
            ds.close();
        }
    }
}
