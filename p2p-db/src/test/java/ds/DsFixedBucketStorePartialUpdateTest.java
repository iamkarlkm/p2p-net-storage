package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsFixedBucketStorePartialUpdateTest {

    @Test
    public void testPartialUpdate() throws Exception {
        File dir = new File("target/ds-bucket-partial-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.INDEPENDENT_SPACE;
        String type = "value";

        byte[] v1 = new byte[300];
        Arrays.fill(v1, (byte) 1);
        long id = store.put(space, type, v1);

        byte[] patch = new byte[] {9, 9, 9, 9, 9};
        store.update(space, type, id, 10, patch);

        byte[] part = store.get(space, type, id, 8, 10);
        assertEquals(10, part.length);
        assertEquals(1, part[0]);
        assertEquals(1, part[1]);
        for (int i = 2; i < 2 + patch.length; i++) {
            assertEquals(9, part[i]);
        }
        assertEquals(1, part[7]);
        assertEquals(1, part[8]);
        assertEquals(1, part[9]);

        byte[] out = store.get(space, type, id, v1.length);
        for (int i = 0; i < out.length; i++) {
            byte expect = 1;
            if (i >= 10 && i < 10 + patch.length) {
                expect = 9;
            }
            assertEquals(expect, out[i]);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPartialUpdateOverflow() throws Exception {
        File dir = new File("target/ds-bucket-partial-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.INDEPENDENT_SPACE;
        String type = "value";

        byte[] v1 = new byte[300];
        long id = store.put(space, type, v1);

        byte[] patch = new byte[4];
        store.update(space, type, id, 510, patch);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPartialGetOverflow() throws Exception {
        File dir = new File("target/ds-bucket-partial-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.INDEPENDENT_SPACE;
        String type = "value";

        byte[] v1 = new byte[300];
        long id = store.put(space, type, v1);

        store.get(space, type, id, 510, 4);
    }
}
