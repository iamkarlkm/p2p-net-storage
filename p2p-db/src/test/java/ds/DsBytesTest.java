package ds;

import com.q3lives.ds.util.DsBytes;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsBytesTest {

    @Test
    public void testPutGetRemoveAndReuse() throws Exception {
        File dir = new File("target/ds-bytes-test-" + System.nanoTime());
        dir.mkdirs();

        DsBytes store = new DsBytes(dir.getPath(), "value");

        byte[] v1 = new byte[300];
        for (int i = 0; i < v1.length; i++) {
            v1[i] = (byte) (i & 0xFF);
        }
        long id1 = store.put(v1);
        assertTrue(id1 > 0);
        assertArrayEquals(v1, store.get(id1, v1.length));

        store.remove(id1);

        byte[] v2 = new byte[300];
        Arrays.fill(v2, (byte) 7);
        long id2 = store.put(v2);
        assertEquals(id1, id2);
        assertArrayEquals(v2, store.get(id2, v2.length));
    }
}
