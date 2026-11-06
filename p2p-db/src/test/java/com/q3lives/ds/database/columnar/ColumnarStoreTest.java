package com.q3lives.ds.database.columnar;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.database.adapter.DsTableAdapter;

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
    public void testPutUpdateFixedValueStoreKeepsRecordId() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-put").toFile();
        String old = System.getProperty("p2p.db.home");
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            long rowId = 1L;

            long id1 = store.putValue(UserRow.class, "username", rowId, "abc".getBytes(StandardCharsets.UTF_8));
            long id1Again = store.putValue(UserRow.class, "username", rowId, "abcd".getBytes(StandardCharsets.UTF_8));
            Assertions.assertEquals(id1, id1Again);

            byte[] got = store.getValue(UserRow.class, "username", rowId);
            Assertions.assertNotNull(got);
            Assertions.assertEquals("abcd\0\0\0\0\0\0\0\0\0\0\0\0", new String(got, StandardCharsets.UTF_8));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testVariableColumnUpdateChangesValueId() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-var").toFile();
        String old = System.getProperty("p2p.db.home");
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            long rowId = 1L;

            long id1 = store.putValue(UserRow.class, "bio", rowId, "short".getBytes(StandardCharsets.UTF_8));
            byte[] big = new byte[5000];
            big[0] = 1;
            long id2 = store.putValue(UserRow.class, "bio", rowId, big);
            Assertions.assertNotEquals(id1, id2);

            byte[] got = store.getValue(UserRow.class, "bio", rowId);
            Assertions.assertNotNull(got);
            Assertions.assertEquals(5000, got.length);
            Assertions.assertEquals(1, got[0]);
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
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
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
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
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

    @Test
    public void testFieldGroupRoundTrip() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-group").toFile();
        String old = System.getProperty("p2p.db.home");
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            long rowId = 1L;
            byte[] groupBytes = new byte[12];
            System.arraycopy("ab".getBytes(StandardCharsets.UTF_8), 0, groupBytes, 0, 2);
            System.arraycopy(new byte[] {0, 0, 0, 25}, 0, groupBytes, 4, 4);
            System.arraycopy("XY".getBytes(StandardCharsets.UTF_8), 0, groupBytes, 8, 2);

            store.putFieldGroup(UserRow.class, "PROFILE", rowId, groupBytes);

            byte[] got = store.getFieldGroup(UserRow.class, "PROFILE", rowId);
            Assertions.assertNotNull(got);
            Assertions.assertArrayEquals(groupBytes, got);

            byte[] firstName = store.getValue(UserRow.class, "firstName", rowId);
            Assertions.assertNotNull(firstName);
            Assertions.assertEquals(4, firstName.length);
            Assertions.assertEquals("ab\0\0", new String(firstName, StandardCharsets.UTF_8));

            byte[] ageAlias = store.getValue(UserRow.class, "ageAlias", rowId);
            Assertions.assertNotNull(ageAlias);
            Assertions.assertEquals(4, ageAlias.length);
            Assertions.assertEquals(25, ageAlias[3] & 0xFF);

            Assertions.assertTrue(store.removeFieldGroup(UserRow.class, "PROFILE", rowId));
            Assertions.assertNull(store.getFieldGroup(UserRow.class, "PROFILE", rowId));
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
        }
    }

    @Test
    public void testInlineColumnRoundTrip() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-inline").toFile();
        String old = System.getProperty("p2p.db.home");
        try (ColumnarStore store = new ColumnarStore(home)) {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            long rowId = 1L;
            store.putValue(UserRow.class, "flags", rowId, new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});
            byte[] got = store.getValue(UserRow.class, "flags", rowId);
            Assertions.assertNotNull(got);
            Assertions.assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08}, got);
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

        @DsField(name = "bio", length = 0)
        private String bio;

        @DsField(name = "flags", length = 8)
        private Long flags;

        @DsField(name = "firstName", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 0)
        private String firstName;

        @DsField(name = "ageAlias", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 1)
        private Integer ageAlias;

        @DsField(name = "code", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 2)
        private String code;
    }
}
