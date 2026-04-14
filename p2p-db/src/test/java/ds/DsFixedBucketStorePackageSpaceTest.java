package ds;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DsFixedBucketStorePackageSpaceTest {

    @Test
    public void testPackageStyleSpace() throws IOException {
        File dir = new File("target/ds-bucket-pkg-space-test-" + System.nanoTime());
        dir.mkdirs();

        DsFixedBucketStore store = new DsFixedBucketStore(dir.getPath());
        String space = "com.example.data";
        String type = "value";

        byte[] v1 = new byte[] {1, 2, 3, 4, 5};
        long id = store.put(space, type, v1);

        byte[] out = store.get(space, type, id, v1.length);
        assertArrayEquals(v1, out);

        // Verify the file was created in the nested directory structure
        String expectedPath = "com" + File.separator + "example" + File.separator + "data";
        File expectedDir = new File(dir, expectedPath + File.separator + type);
        
        assertTrue("Nested directory structure should exist", expectedDir.exists());
        assertTrue("Nested directory should be a directory", expectedDir.isDirectory());
        
        File[] files = expectedDir.listFiles((d, name) -> name.endsWith(".dat"));
        assertNotNull(files);
        assertTrue("Data file should exist in the nested directory", files.length > 0);

        store.close();
    }
}
