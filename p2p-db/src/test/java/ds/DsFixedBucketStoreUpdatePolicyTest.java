package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsFixedBucketStoreUpdatePolicyTest {

    @Test
    public void testUpdatePolicy() throws Exception {
        File dir = new File("target/ds-bucket-update-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.DATA_SPACE;
        String type = "value";

        byte[] v1 = new byte[300];
        Arrays.fill(v1, (byte) 1);
        long id1 = store.put(space, type, v1);
        assertArrayEquals(v1, store.get(space, type, id1, v1.length));

        byte[] vSmall = new byte[50];
        Arrays.fill(vSmall, (byte) 2);
        long id2 = store.update(space, type, id1, vSmall, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        assertEquals(id1, id2);
        assertArrayEquals(vSmall, store.get(space, type, id2, vSmall.length));

        long id3 = store.update(space, type, id2, vSmall, DsFixedBucketStore.UpdatePolicy.SHRINK_TO_FIT);
        assertNotEquals(id2, id3);
        assertArrayEquals(vSmall, store.get(space, type, id3, vSmall.length));

        byte[] vBig = new byte[900];
        Arrays.fill(vBig, (byte) 3);
        long id4 = store.update(space, type, id3, vBig, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        assertNotEquals(id3, id4);
        assertArrayEquals(vBig, store.get(space, type, id4, vBig.length));
    }
}
