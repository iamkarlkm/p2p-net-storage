package ds;

import com.q3lives.ds.collections.DsManyToManyStore;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsManyToManyStoreTest {

    @Test
    public void shouldLinkUnlinkAndPersist() throws Exception {
        File dir = new File("target/ds-m2m-test-" + System.nanoTime());
        dir.mkdirs();

        try (DsManyToManyStore store = new DsManyToManyStore(dir.toPath(), "rel", 4)) {
            assertTrue(store.link(1, 10));
            assertTrue(store.link(1, 11));
            assertTrue(store.link(2, 10));
            assertFalse(store.link(1, 10));

            assertEquals(setOf(10, 11), setOf(store.listRights(1)));
            assertEquals(setOf(1, 2), setOf(store.listLefts(10)));

            assertTrue(store.unlink(1, 10));
            assertFalse(store.unlink(1, 10));
            assertEquals(setOf(11), setOf(store.listRights(1)));
            assertEquals(setOf(2), setOf(store.listLefts(10)));
        }

        try (DsManyToManyStore store = new DsManyToManyStore(dir.toPath(), "rel", 4)) {
            assertEquals(setOf(11), setOf(store.listRights(1)));
            assertEquals(setOf(2), setOf(store.listLefts(10)));
        }
    }

    private static Set<Long> setOf(long... values) {
        Set<Long> s = new HashSet<>();
        if (values == null) {
            return s;
        }
        for (long v : values) {
            s.add(v);
        }
        return s;
    }
}

