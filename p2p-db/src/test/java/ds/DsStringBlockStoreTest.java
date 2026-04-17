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
}
