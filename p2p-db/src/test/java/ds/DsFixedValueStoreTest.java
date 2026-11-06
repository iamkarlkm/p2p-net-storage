package ds;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.collections.DsFixedValueStore;

public class DsFixedValueStoreTest {

    private File valuesFile;

    @Before
    public void setUp() throws Exception {
        valuesFile = File.createTempFile("test_fixed_value_store_", ".values");
        valuesFile.deleteOnExit();
        deleteSidecars();
    }

    @After
    public void tearDown() throws Exception {
        deleteSidecars();
    }

    private void deleteSidecars() {
        File[] files = new File[] {
                valuesFile,
                new File(valuesFile.getAbsolutePath() + ".free")
        };
        for (File f : files) {
            if (f.exists()) {
                f.delete();
            }
        }
    }

    @Test
    public void testAllocateAndWriteRead() throws IOException {
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 16)) {
            long id1 = store.allocateRecord();
            long id2 = store.allocateRecord();
            assertEquals(1L, id1);
            assertEquals(2L, id2);

            byte[] data = new byte[16];
            for (int i = 0; i < 16; i++) {
                data[i] = (byte) (i + 1);
            }
            store.write(id1, data);

            byte[] read = store.read(id1);
            assertArrayEquals(data, read);
        }
    }

    @Test
    public void testRecycleReusesRecordId() throws IOException {
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 8)) {
            long id1 = store.allocateRecord();
            assertEquals(1L, id1);
            store.write(id1, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });

            store.recycle(id1);
            long id2 = store.allocateRecord();
            assertEquals(id1, id2);

            byte[] read = store.read(id2);
            assertEquals(8, read.length);
        }
    }

    @Test
    public void testRecordSizeMismatchThrows() throws IOException {
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 8)) {
            long id = store.allocateRecord();
            try {
                store.write(id, new byte[7]);
            } catch (IllegalArgumentException e) {
                assertEquals("data length 7 != recordSize 8", e.getMessage());
            }
        }
    }

    @Test
    public void testReopenPreservesRecordSizeAndData() throws IOException {
        byte[] data = new byte[32];
        for (int i = 0; i < 32; i++) {
            data[i] = (byte) (i * 3);
        }

        long id;
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 32)) {
            id = store.allocateRecord();
            store.write(id, data);
        }

        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 32)) {
            assertEquals(32, store.getRecordSize());
            assertEquals(2L, store.getNextRecordId());
            assertArrayEquals(data, store.read(id));
        }
    }

    @Test
    public void testReopenWithDifferentRecordSizeThrows() throws IOException {
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 16)) {
            store.allocateRecord();
        }

        try {
            new DsFixedValueStore(valuesFile, 8).close();
        } catch (IllegalStateException e) {
            assertEquals("recordSize mismatch: stored=16, expected=8", e.getMessage());
        }
    }

    @Test
    public void testAllocateAfterRecycleDoesNotReuseIfQueueEmpty() throws IOException {
        try (DsFixedValueStore store = new DsFixedValueStore(valuesFile, 8)) {
            long id0 = store.allocateRecord();
            long id1 = store.allocateRecord();
            assertNotEquals(id0, id1);

            store.recycle(id0);
            long id2 = store.allocateRecord();
            assertEquals(id0, id2);

            long id3 = store.allocateRecord();
            assertEquals(id1 + 1, id3);
        }
    }
}
