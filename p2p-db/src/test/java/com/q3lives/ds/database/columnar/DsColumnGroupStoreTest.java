package com.q3lives.ds.database.columnar;

import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DsColumnGroupStoreTest {

    @Test
    public void testPutGetRoundTrip() throws Exception {
        File home = Files.createTempDirectory("dsdb-group").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 16)) {
            long rowId = 42L;
            byte[] group = new byte[16];
            for (int i = 0; i < 16; i++) {
                group[i] = (byte) i;
            }

            long id1 = store.put(rowId, group);
            long id2 = store.put(rowId, group);
            Assertions.assertEquals(id1, id2);

            byte[] got = store.get(rowId);
            Assertions.assertNotNull(got);
            Assertions.assertArrayEquals(group, got);
        }
    }

    @Test
    public void testGetColumnByOffset() throws Exception {
        File home = Files.createTempDirectory("dsdb-group-col").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 12)) {
            long rowId = 1L;
            byte[] group = new byte[12];
            System.arraycopy(new byte[] {0, 0, 0, 25}, 0, group, 0, 4);
            System.arraycopy("ab".getBytes(), 0, group, 4, 2);
            System.arraycopy(new byte[] {0, 0, 0, 5}, 0, group, 8, 4);
            store.put(rowId, group);

            byte[] age = store.getColumn(rowId, 0, 4);
            Assertions.assertArrayEquals(new byte[] {0, 0, 0, 25}, age);

            byte[] name = store.getColumn(rowId, 4, 2);
            Assertions.assertArrayEquals("ab".getBytes(), name);

            byte[] count = store.getColumn(rowId, 8, 4);
            Assertions.assertArrayEquals(new byte[] {0, 0, 0, 5}, count);
        }
    }

    @Test
    public void testRemoveRecyclesRecordId() throws Exception {
        File home = Files.createTempDirectory("dsdb-group-rm").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 8)) {
            long rowId = 1L;
            byte[] group = {1, 2, 3, 4, 5, 6, 7, 8};
            long id1 = store.put(rowId, group);
            Assertions.assertTrue(store.remove(rowId));
            Assertions.assertNull(store.get(rowId));

            long id2 = store.put(rowId, group);
            Assertions.assertEquals(id1, id2, "recordId should be recycled");
        }
    }

    @Test
    public void testRemoveMissingReturnsFalse() throws Exception {
        File home = Files.createTempDirectory("dsdb-group-miss").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 8)) {
            Assertions.assertFalse(store.remove(999L));
            Assertions.assertNull(store.getColumn(999L, 0, 4));
        }
    }

    @Test
    public void testInvalidGroupSizeRejected() {
        File home = new File("target");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new DsColumnGroupStore(new File(home, "x.map"), 0));
    }

    @Test
    public void testWrongGroupBytesLengthRejected() throws Exception {
        File home = Files.createTempDirectory("dsdb-group-bad").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 8)) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.put(1L, new byte[4]));
            Assertions.assertThrows(NullPointerException.class,
                    () -> store.put(1L, null));
        }
    }

    @Test
    public void testColumnRangeOutOfBoundsThrows() throws Exception {
        File home = Files.createTempDirectory("dsdb-group-bounds").toFile();
        File mapFile = new File(home, "test.map");
        try (DsColumnGroupStore store = new DsColumnGroupStore(mapFile, 8)) {
            store.put(1L, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.getColumn(1L, 6, 4));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.getColumn(1L, -1, 4));
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.getColumn(1L, 0, -1));
        }
    }
}
