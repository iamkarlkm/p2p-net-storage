package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DsFixedBucketStoreCrossBlockZeroFillTest {

    @Test
    public void testUpdateSmallerKeepsBucketAndZeroFillsTailAcrossBlock() throws Exception {
        File dir = new File("target/ds-bucket-crossblock-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.DATA_SPACE;
        String type = "value";

        byte[] v1 = new byte[65500];
        Arrays.fill(v1, (byte) 1);
        long id = store.put(space, type, v1);

        byte[] v2 = new byte[100];
        Arrays.fill(v2, (byte) 2);
        store.update(space, type, id, v2, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);

        byte[] out = store.get(space, type, id, 65536);
        assertEquals(65536, out.length);
        for (int i = 0; i < 100; i++) {
            assertEquals(2, out[i]);
        }
        for (int i = 100; i < out.length; i++) {
            assertEquals(0, out[i]);
        }
    }
}

