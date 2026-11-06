package ds;

import com.q3lives.ds.core.DsString;
import java.io.File;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsStringBlockStoreTest {

    @Test
    public void testAddGetUpdateRemove() throws Exception {
        File dir = new File("target/ds-string-test-" + System.nanoTime());
        dir.mkdirs();
        DsString ds = new DsString(dir.getPath());

        long id = ds.add("abc");
        assertEquals("abc", ds.get(id));

        long id2 = ds.update(id, "hello world");
        assertEquals("hello world", ds.get(id2));

        ds.remove(id2);
        assertNull(ds.get(id2));
    }

    @Test
    public void testContainsBasic() throws Exception {
        File dir = new File("target/ds-string-contains-test-" + System.nanoTime());
        dir.mkdirs();
        DsString ds = new DsString(dir.getPath());
        try {
            assertFalse(ds.contains("abc"));
            assertFalse(ds.contains(null));
            assertFalse(ds.contains(""));

            long id = ds.add("abc");
            assertTrue(ds.contains("abc"));
            assertFalse(ds.contains("xyz"));

            long idEmpty = ds.add("");
            assertTrue(ds.contains(""));
            assertTrue(ds.contains(null));

            long idNull = ds.add(null);
            assertEquals(idEmpty, idNull);

            ds.remove(id);
            assertFalse(ds.contains("abc"));
            assertTrue(ds.contains(""));
        } finally {
            ds.close();
        }
    }

    @Test
    public void testGetIndexId() throws Exception {
        File dir = new File("target/ds-string-indexid-test-" + System.nanoTime());
        dir.mkdirs();
        DsString ds = new DsString(dir.getPath());
        try {
            assertNull(ds.getIndexId("abc"));
            assertNull(ds.getIndexId(null));
            assertNull(ds.getIndexId(""));
            assertNull(ds.getIndexId("abc", false));
            assertNull(ds.getIndexId(null, false));
            assertNull(ds.getIndexId("", false));

            long id = ds.add("abc");
            Long q1 = ds.getIndexId("abc");
            assertNotNull(q1);
            assertEquals(id, q1.longValue());
            assertEquals(id, ds.getIndexId("abc", false).longValue());
            assertEquals(id, ds.getIndexId("abc", true).longValue());
            assertNull(ds.getIndexId("xyz"));

            long id2 = ds.add("abc");
            assertEquals(id, id2);
            assertEquals(id, ds.getIndexId("abc").longValue());

            long idEmpty = ds.add("");
            Long qEmpty = ds.getIndexId("");
            Long qNull = ds.getIndexId(null);
            assertNotNull(qEmpty);
            assertNotNull(qNull);
            assertEquals(idEmpty, qEmpty.longValue());
            assertEquals(qEmpty, qNull);
            assertEquals(idEmpty, ds.getIndexId("", true).longValue());
            assertEquals(idEmpty, ds.getIndexId(null, true).longValue());

            ds.release(id);
            assertEquals(1, ds.refCount(id));
            assertEquals(id, ds.getIndexId("abc").longValue());
            assertEquals(id, ds.getIndexId("abc", true).longValue());

            ds.release(id2);
            assertEquals(0, ds.refCount(id));
            assertNull(ds.getIndexId("abc"));
            assertNull(ds.getIndexId("abc", false));

            Long newId = ds.getIndexId("abc", true);
            assertNotNull(newId);
            long id3 = ds.add("abc");
            assertEquals(newId.longValue(), id3);
            assertEquals(2, ds.refCount(id3));
        } finally {
            ds.close();
        }
    }
}
