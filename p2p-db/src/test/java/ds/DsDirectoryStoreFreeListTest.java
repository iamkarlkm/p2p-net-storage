package ds;

import com.q3lives.ds.fs.Ds256DirectoryStore;
import java.io.File;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsDirectoryStoreFreeListTest {

    @Test
    public void testReuseAfterDelete() throws Exception {
        File dir = new File("target/ds-dirstore-freelist-test-" + System.nanoTime());
        dir.mkdirs();

        Ds256DirectoryStore store = new Ds256DirectoryStore(dir.getPath());
        long dirId = store.createDir();

        store.appendEntry(dirId, 11);
        store.appendEntry(dirId, 22);
        store.appendEntry(dirId, 33);
        assertEquals(3L, store.size(dirId));

        assertTrue(store.removeEntry(dirId, 22));
        assertEquals(2L, store.size(dirId));

        store.appendEntry(dirId, 44);
        assertEquals(3L, store.size(dirId));

        long[] ids = store.listEntries(dirId, 0, 10);
        boolean has11 = false;
        boolean has22 = false;
        boolean has33 = false;
        boolean has44 = false;
        for (long v : ids) {
            if (v == 11) has11 = true;
            if (v == 22) has22 = true;
            if (v == 33) has33 = true;
            if (v == 44) has44 = true;
        }
        assertTrue(has11);
        assertFalse(has22);
        assertTrue(has33);
        assertTrue(has44);

        store.close();
    }
}
