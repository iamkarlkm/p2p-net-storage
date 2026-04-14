package ds;

import com.q3lives.ds.index.value.DsMiniValueIndex;
import com.q3lives.ds.kv.DsKVStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsKVStoreValueIndexTest {

    @Test
    public void testGetIndexAndKeyByValue() throws Exception {
        File dir = new File("target/ds-kvstore-valueindex-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");

        byte[] k1 = "k1".getBytes(StandardCharsets.UTF_8);
        byte[] k2 = "k2".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        byte[] v2 = "v2".getBytes(StandardCharsets.UTF_8);

        long i1 = store.put(k1, v1);
        long i2 = store.put(k2, v2);

        DsMiniValueIndex.Page byV1 = store.getIndexByValue(v1);
        assertEquals(0L, byV1.index);
        assertEquals(byV1.size, DsMiniValueIndex.DEFAULT_PAGE_SIZE);
        assertEquals(1L, byV1.total);
        assertEquals(i1, byV1.ids[0]);
        assertArrayEquals(k1, store.getKeyByValue(v1));

        DsMiniValueIndex.Page byV2 = store.getIndexByValue(v2);
        assertEquals(1L, byV2.total);
        assertEquals(i2, byV2.ids[0]);
        assertArrayEquals(k2, store.getKeyByValue(v2));

        byte[] v1b = "v1b".getBytes(StandardCharsets.UTF_8);
        store.updateValueByIndexId(i1, v1b);

        assertEquals(0L, store.getIndexByValue(v1).total);
        DsMiniValueIndex.Page byV1b = store.getIndexByValue(v1b);
        assertEquals(1L, byV1b.total);
        assertEquals(i1, byV1b.ids[0]);
        assertArrayEquals(k1, store.getKeyByValue(v1b));

        assertTrue(store.remove(k2));
        assertEquals(0L, store.getIndexByValue(v2).total);

        store.close();
    }
}
