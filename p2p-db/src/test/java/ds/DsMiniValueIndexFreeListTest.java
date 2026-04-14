package ds;

import com.q3lives.ds.index.value.DsMiniValueIndex;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsMiniValueIndexFreeListTest {

    @Test
    public void testReuseAfterDelete() throws Exception {
        File dir = new File("target/ds-minivix-freelist-test-" + System.nanoTime());
        dir.mkdirs();

        DsMiniValueIndex vix = new DsMiniValueIndex(dir);
        byte[] value = new byte[] {1, 2, 3};

        vix.add(value, 101);
        vix.add(value, 102);
        vix.add(value, 103);

        assertEquals(3L, vix.getPage(value).total);

        vix.remove(value, 102);
        DsMiniValueIndex.Page p1 = vix.getPage(value);
        assertEquals(2L, p1.total);
        assertFalse(Arrays.stream(p1.ids).anyMatch(x -> x == 102));

        vix.add(value, 104);
        DsMiniValueIndex.Page p2 = vix.getPage(value);
        assertEquals(3L, p2.total);
        assertTrue(Arrays.stream(p2.ids).anyMatch(x -> x == 104));

        vix.close();
    }
}
