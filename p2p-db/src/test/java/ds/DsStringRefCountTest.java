package ds;

import com.q3lives.ds.util.DsString;
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
}
