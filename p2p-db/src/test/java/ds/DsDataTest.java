package ds;

import com.q3lives.ds.bucket.DsData;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsDataTest {

    @Test
    public void testDedupAndRefCount() throws Exception {
        File dir = new File("target/ds-data-test-" + System.nanoTime());
        dir.mkdirs();

        DsData d = new DsData(dir.getPath(), "data");
        byte[] v = "hello".getBytes(StandardCharsets.UTF_8);

        long i1 = d.put(v);
        assertTrue(i1 > 0);
        assertEquals(1, d.getRefCountByIndexId(i1));
        assertArrayEquals(v, d.getValueByIndexId(i1));

        long i2 = d.put(v);
        assertEquals(i1, i2);
        assertEquals(2, d.getRefCountByIndexId(i1));

        assertTrue(d.remove(i1));
        assertEquals(1, d.getRefCountByIndexId(i1));

        assertTrue(d.remove(i1));
        assertNull(d.getIndexId(v));

        // Test partial read/write
        byte[] v3 = "hello world".getBytes(StandardCharsets.UTF_8);
        long i3 = d.put(v3);
        
        byte[] part = d.getPartialValueByIndexId(i3, 6, 5);
        assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), part);
        
        long i3b = d.updatePartialValueByIndexId(i3, 6, "there".getBytes(StandardCharsets.UTF_8));
        assertEquals(i3, i3b);
        byte[] updated = d.getValueByIndexId(i3b);
        assertArrayEquals("hello there".getBytes(StandardCharsets.UTF_8), updated);
        
        // Verify index was updated correctly
        assertNotNull(d.getIndexId("hello there".getBytes(StandardCharsets.UTF_8)));
        assertNull(d.getIndexId("hello world".getBytes(StandardCharsets.UTF_8)));

        // Test copy-on-write when refCount > 1
        byte[] v4 = "cow test".getBytes(StandardCharsets.UTF_8);
        long i4 = d.put(v4);
        long i4b = d.put(v4);
        assertEquals(i4, i4b);
        assertEquals(2, d.getRefCountByIndexId(i4));

        byte[] v4new = "cow best".getBytes(StandardCharsets.UTF_8);
        long i4new = d.updatePartialValueByIndexId(i4, 4, "best".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(i4, i4new);
        assertEquals(1, d.getRefCountByIndexId(i4));
        assertArrayEquals(v4, d.getValueByIndexId(i4));
        assertArrayEquals(v4new, d.getValueByIndexId(i4new));
        assertEquals(Long.valueOf(i4), d.getIndexId(v4));
        assertEquals(Long.valueOf(i4new), d.getIndexId(v4new));

        d.close();
    }
}
