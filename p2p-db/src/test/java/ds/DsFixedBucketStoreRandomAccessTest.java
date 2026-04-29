package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class DsFixedBucketStoreRandomAccessTest {

    @Test
    public void testRandomAccessReadWriteAt() throws Exception {
        File dir = new File("target/ds-bucket-random-access-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        try {
            byte[] data = new byte[300_000];
            byte[] expected = Arrays.copyOf(data, data.length);
            long id = store.put(DsFixedBucketStore.DATA_SPACE, "value", data);

            try (DsFixedBucketStore.RecordRandomAccess ra = store.openRandomAccess("value", id)) {
                Random rnd = new Random(11);
                byte[] buf = new byte[800];
                for (int i = 0; i < 200; i++) {
                    int len = 1 + rnd.nextInt(800);
                    int pos = rnd.nextInt(expected.length - len);
                    rnd.nextBytes(buf);
                    ra.writeAt(pos, buf, 0, len);
                    System.arraycopy(buf, 0, expected, pos, len);
                }

                byte[] slice = new byte[4096];
                for (int i = 0; i < 50; i++) {
                    int len = 1 + rnd.nextInt(slice.length);
                    int pos = rnd.nextInt(expected.length - len);
                    int n = ra.readAt(pos, slice, 0, len);
                    assertEquals(len, n);
                    assertArrayEquals(Arrays.copyOfRange(expected, pos, pos + len), Arrays.copyOf(slice, len));
                }
            }

            byte[] out = store.get("value", id, expected.length);
            assertArrayEquals(expected, out);
        } finally {
            store.close();
        }
    }

    @Test
    public void testUpdatePartialFromStream() throws Exception {
        File dir = new File("target/ds-bucket-random-update-stream-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        try {
            byte[] data = new byte[200_000];
            long id = store.put(DsFixedBucketStore.DATA_SPACE, "value", data);

            byte[] patch = new byte[10_000];
            new Random(7).nextBytes(patch);
            int offset = 12345;
            store.update(DsFixedBucketStore.DATA_SPACE, "value", id, offset, new ByteArrayInputStream(patch), patch.length);

            byte[] out = store.get("value", id, data.length);
            System.arraycopy(patch, 0, data, offset, patch.length);
            assertArrayEquals(data, out);
        } finally {
            store.close();
        }
    }
}
