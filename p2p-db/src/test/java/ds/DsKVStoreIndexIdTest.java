package ds;

import com.q3lives.ds.kv.DsKVStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsKVStoreIndexIdTest {

    @Test
    public void testGetAndUpdateByIndexId() throws Exception {
        File dir = new File("target/ds-kvstore-indexid-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");
        byte[] key = "k1".getBytes(StandardCharsets.UTF_8);
        byte[] v1 = "v1".getBytes(StandardCharsets.UTF_8);
        long indexId = store.put(key, v1);
        assertTrue(indexId > 0);

        byte[] got1 = store.getValueByIndexId(indexId);
        assertArrayEquals(v1, got1);

        byte[] v2 = "v2-longer".getBytes(StandardCharsets.UTF_8);
        long same = store.updateValueByIndexId(indexId, v2);
        assertEquals(indexId, same);

        byte[] got2 = store.getValueByIndexId(indexId);
        assertArrayEquals(v2, got2);

        byte[] gotKey = store.getKeyByIndexId(indexId);
        assertArrayEquals(key, gotKey);

        byte[] gotByKey = store.get(key);
        assertArrayEquals(v2, gotByKey);

        store.close();
    }
}
