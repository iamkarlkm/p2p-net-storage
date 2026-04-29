package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class DsFixedBucketStoreStreamTest {

    @Test
    public void testPutAndReadStream() throws Exception {
        File dir = new File("target/ds-bucket-stream-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        try {
            byte[] data = new byte[900_000];
            new Random(7).nextBytes(data);

            try (InputStream in = new ByteArrayInputStream(data)) {
                long id = store.put(DsFixedBucketStore.DATA_SPACE, "value", in, data.length);
                try (InputStream in2 = store.openInputStream("value", id, data.length)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in2.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        out.write(buf, 0, n);
                    }
                    assertArrayEquals(data, out.toByteArray());
                }
            }
        } finally {
            store.close();
        }
    }
}

