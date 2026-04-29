package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

public class DsFixedBucketStorePartialReadIntoTest {

    @Test
    public void testGetPartialIntoMatchesSlice() throws Exception {
        File dir = new File("target/ds-bucket-partial-read-into-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = DsFixedBucketStore.DATA_SPACE;
        String type = "value";

        byte[] data = new byte[220_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }

        long id = store.put(space, type, data);

        int offset = 60_000;
        int length = 120_000;
        byte[] out = new byte[length];
        store.get(type, id, offset, length, out, 0);

        assertArrayEquals(Arrays.copyOfRange(data, offset, offset + length), out);
        store.close();
    }
}

