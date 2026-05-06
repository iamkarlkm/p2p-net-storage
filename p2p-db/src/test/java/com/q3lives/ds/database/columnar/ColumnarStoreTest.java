package com.q3lives.ds.database.columnar;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ColumnarStoreTest {
    @Test
    public void testColIdAllocateIdempotentAndMonotonic() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-reg").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnRegistry reg = new ColumnRegistry(home);
            long a1 = reg.getOrCreateColId(UserRow.class, UserRow.class.getName() + "#username");
            long a2 = reg.getOrCreateColId(UserRow.class, UserRow.class.getName() + "#username");
            long b = reg.getOrCreateColId(UserRow.class, UserRow.class.getName() + "#age");
            Assertions.assertEquals(a1, a2);
            Assertions.assertTrue(b > a1);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testPutUpdateUsesBucketUpdateSemantics() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-put").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            long rowId = 1L;

            byte[] v1 = "abc".getBytes(StandardCharsets.UTF_8);
            long id1 = store.putValue(UserRow.class, "username", rowId, v1);
            long id1Again = store.putValue(UserRow.class, "username", rowId, "abcd".getBytes(StandardCharsets.UTF_8));
            Assertions.assertEquals(id1, id1Again);

            byte[] big = new byte[5000];
            big[0] = 1;
            long id2 = store.putValue(UserRow.class, "username", rowId, big);
            Assertions.assertNotEquals(id1, id2);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testDeleteColumnHardMarksDeletedAndMakesUnreadable() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-del").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            store.putValue(UserRow.class, "username", 1L, "a".getBytes(StandardCharsets.UTF_8));
            store.putValue(UserRow.class, "username", 2L, "b".getBytes(StandardCharsets.UTF_8));

            store.deleteColumnHard(UserRow.class, "username", 64);

            byte[] got = store.getValue(UserRow.class, "username", 1L, 8);
            Assertions.assertNull(got);

            ColumnRegistry reg = new ColumnRegistry(home);
            Long colId = reg.findColId(UserRow.class, UserRow.class.getName() + "#username");
            Assertions.assertNotNull(colId);
            Assertions.assertTrue(reg.isDeleted(UserRow.class, colId));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testTableMetaIncludesCompositeGroupsAndColumns() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-meta").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            TableMetaStore store = new TableMetaStore(home);
            TableMetaStore.TableMeta meta = store.ensureMeta(UserRow.class);
            Assertions.assertEquals(UserRow.class.getName(), meta.entityClassName);
            Assertions.assertFalse(meta.columns.isEmpty());
            Assertions.assertTrue(meta.compositeGroups.containsKey("STATUS"));
            Assertions.assertTrue(meta.compositeGroups.get("STATUS").colId > 0);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testCompositeGroupBehavesLikeAColumn() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-comp").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            long rowId = 1L;
            store.putCompositeGroup(UserRow.class, "STATUS", rowId, new byte[] {1, 2});
            byte[] got = store.getCompositeGroup(UserRow.class, "STATUS", rowId);
            Assertions.assertNotNull(got);
            Assertions.assertEquals(8, got.length);
            Assertions.assertEquals(1, got[0]);
            Assertions.assertEquals(2, got[1]);
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    public static class UserRow extends DsTableAdapter {
        @DsCompositeField(name = "isActive", group = "STATUS", length = 8, startBits = 0, endBits = 0)
        private boolean active;

        @DsField(name = "username", length = 16)
        private String username;

        @DsField(name = "age", length = 4)
        private Integer age;
    }
}
