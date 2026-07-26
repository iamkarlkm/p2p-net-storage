package ds;

import com.q3lives.ds.index.value.DsTagsManyToManyStore;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsTagsManyToManyStoreTest {

    @Test
    public void shouldStoreTagsManyToMany() throws Exception {
        File dir = new File("target/ds-tags-m2m-test-" + System.nanoTime());
        dir.mkdirs();

        long tagA;
        long tagB;
        try (DsTagsManyToManyStore tags = new DsTagsManyToManyStore(dir.toPath())) {
            tagA = tags.getOrCreateTagId("a");
            tagB = tags.getOrCreateTagId("b");

            assertTrue(tags.addTagToFile(tagA, 100));
            assertTrue(tags.addTagToFile(tagA, 101));
            assertTrue(tags.addTagToFile(tagB, 100));
            assertFalse(tags.addTagToFile(tagA, 100));

            assertEquals(setOf(100, 101), setOf(tags.listFilesByTag(tagA)));
            assertEquals(setOf(tagA, tagB), setOf(tags.listTagsByFile(100)));
        }

        try (DsTagsManyToManyStore tags = new DsTagsManyToManyStore(dir.toPath())) {
            assertEquals(setOf(100, 101), setOf(tags.listFilesByTag(tagA)));
            assertEquals(setOf(tagA, tagB), setOf(tags.listTagsByFile(100)));
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

