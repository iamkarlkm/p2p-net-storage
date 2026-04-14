package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.index.master.DsTieredMasterIndex;
import com.q3lives.ds.kv.DsKVStore;
import com.q3lives.ds.util.DsDataUtil;
import java.io.File;
import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsKVStoreReuseTest {

    @Test
    public void testDeleteAndReuseBlocks() throws Exception {
        File dir = new File("target/ds-kvstore-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");

        String key = "k";
        String v1 = repeat('a', 9000);
        store.put(key, v1);
        Parsed p1 = readIndexByKey(store, key);
        assertTrue(p1.indexId > 0);
        assertTrue(p1.valueId > 0);

        assertTrue(store.remove(key));

        String v2 = repeat('b', 9000);
        store.put(key, v2);
        Parsed p2 = readIndexByKey(store, key);
        assertTrue(p2.indexId > 0);
        assertTrue(p2.valueId > 0);

        assertEquals(p1.valueLen, p2.valueLen);
        assertEquals(p1.valueId, p2.valueId);
        assertEquals(p1.indexId, p2.indexId);
        assertEquals(v2, store.get(key));
    }

    private Parsed readIndexByKey(DsKVStore store, String key) throws Exception {
        Field fMaster = DsKVStore.class.getDeclaredField("masterIndex");
        fMaster.setAccessible(true);
        DsTieredMasterIndex master = (DsTieredMasterIndex) fMaster.get(store);

        Field fBucket = DsKVStore.class.getDeclaredField("bucketStore");
        fBucket.setAccessible(true);
        DsFixedBucketStore buckets = (DsFixedBucketStore) fBucket.get(store);

        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Long indexId = master.get(keyBytes);
        assertNotNull(indexId);
        byte[] rec = buckets.get("index", indexId, DsKVStore.INDEX_RECORD_SIZE);
        Parsed p = new Parsed();
        p.indexId = indexId;
        p.valueLen = DsDataUtil.loadInt(rec, 16);
        p.valueId = DsDataUtil.loadLong(rec, 24);
        return p;
    }

    private String repeat(char c, int n) {
        char[] a = new char[n];
        for (int i = 0; i < n; i++) {
            a[i] = c;
        }
        return new String(a);
    }

    private static final class Parsed {
        long indexId;
        int valueLen;
        long valueId;
    }
}
