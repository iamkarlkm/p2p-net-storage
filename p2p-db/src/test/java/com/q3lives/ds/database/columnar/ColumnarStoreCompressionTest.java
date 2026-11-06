package com.q3lives.ds.database.columnar;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class ColumnarStoreCompressionTest {

    @Test
    public void shouldCompressLargeValueAndReadBack() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-compress").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            store.setCompressionThreshold(64);

            long rowId = 1L;
            byte[] original = new byte[500];
            Arrays.fill(original, (byte) 'a');
            original[0] = 1;
            original[499] = 2;

            long valueId = store.putValue(UserRow.class, "payload", rowId, original);
            byte[] got = store.getValue(UserRow.class, "payload", rowId, 500);
            Assert.assertNotNull(got);
            Assert.assertArrayEquals(original, got);

            byte[] compressed = ColumnCompression.compress(original, 64);
            Assert.assertNotNull("compression should reduce size", compressed);
            byte[] raw = store.getBucketStore().get(UserRow.class.getName(), "col_" + resolveColId(store, "payload"), valueId, 0, compressed.length);
            Assert.assertArrayEquals("large value should be stored as compressed", compressed, raw);
        } finally {
            restoreHome(old);
        }
    }

    @Test
    public void shouldNotCompressSmallValue() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-small").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            store.setCompressionThreshold(64);

            long rowId = 1L;
            byte[] original = "hello".getBytes(StandardCharsets.UTF_8);
            long valueId = store.putValue(UserRow.class, "payload", rowId, original);
            byte[] got = store.getValue(UserRow.class, "payload", rowId, 5);
            Assert.assertArrayEquals(original, got);

            byte[] compressed = ColumnCompression.compress(original, 64);
            Assert.assertNull("small value should not be compressed", compressed);
            // variable bucket adds a 7-byte header in front of the raw bytes
            byte[] raw = store.getBucketStore().get(UserRow.class.getName(), "col_" + resolveColId(store, "payload"), valueId, 0, 7 + original.length);
            byte[] rawPayload = Arrays.copyOfRange(raw, 7, raw.length);
            Assert.assertArrayEquals("small value should be stored as-is after variable header", original, rawPayload);
        } finally {
            restoreHome(old);
        }
    }

    @Test
    public void shouldDisableCompressionWhenThresholdIsZero() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-off").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            store.setCompressionThreshold(0);

            long rowId = 1L;
            byte[] original = new byte[500];
            Arrays.fill(original, (byte) 'b');
            long valueId = store.putValue(UserRow.class, "payload", rowId, original);
            byte[] got = store.getValue(UserRow.class, "payload", rowId, 500);
            Assert.assertArrayEquals(original, got);

            // variable bucket adds a 7-byte header in front of the raw bytes
            byte[] raw = store.getBucketStore().get(UserRow.class.getName(), "col_" + resolveColId(store, "payload"), valueId, 0, 7 + original.length);
            byte[] rawPayload = Arrays.copyOfRange(raw, 7, raw.length);
            Assert.assertArrayEquals("compression disabled when threshold=0", original, rawPayload);
        } finally {
            restoreHome(old);
        }
    }

    @Test
    public void shouldCompressCompositeGroup() throws Exception {
        File home = Files.createTempDirectory("dsdb-col-comp").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            ColumnarStore store = ColumnarStore.load();
            store.setCompressionThreshold(8);

            long rowId = 1L;
            byte[] original = new byte[64];
            Arrays.fill(original, (byte) 'c');
            original[0] = 7;

            long valueId = store.putCompositeGroup(UserRow.class, "META", rowId, original);
            byte[] got = store.getCompositeGroup(UserRow.class, "META", rowId);
            Assert.assertNotNull(got);
            Assert.assertEquals(64, got.length);
            Assert.assertArrayEquals(original, got);

            byte[] compressed = ColumnCompression.compress(original, 8);
            Assert.assertNotNull("composite group should be compressed", compressed);
            byte[] raw = store.getBucketStore().get(UserRow.class.getName(), "col_" + resolveCompositeColId(store, "META"), valueId, 0, compressed.length);
            Assert.assertArrayEquals("composite group should be stored as compressed", compressed, raw);
        } finally {
            restoreHome(old);
        }
    }

    private long resolveColId(ColumnarStore store, String logicalName) {
        TableMetaStore.TableMeta meta = store.getTableMetaStore().getMeta(UserRow.class.getName());
        for (TableMetaStore.ColumnDef c : meta.columns) {
            if (logicalName.equals(c.name)) {
                return c.colId;
            }
        }
        throw new IllegalStateException("column not found: " + logicalName);
    }

    private long resolveCompositeColId(ColumnarStore store, String groupName) {
        TableMetaStore.TableMeta meta = store.getTableMetaStore().getMeta(UserRow.class.getName());
        TableMetaStore.CompositeGroup g = meta.compositeGroups.get(groupName);
        if (g == null) {
            throw new IllegalStateException("composite group not found: " + groupName);
        }
        return g.colId;
    }

    private static void restoreHome(String old) {
        if (old == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", old);
        }
    }

    public static class UserRow extends DsTableAdapter {
        @DsCompositeField(name = "meta", group = "META", length = 64, startBits = 0, endBits = 7)
        private byte meta;

        @DsField(name = "payload", length = 0)
        private String payload;
    }
}
