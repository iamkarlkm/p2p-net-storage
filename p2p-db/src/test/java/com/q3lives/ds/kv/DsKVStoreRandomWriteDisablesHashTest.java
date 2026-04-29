package com.q3lives.ds.kv;

import com.q3lives.ds.util.DsDataUtil;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DsKVStoreRandomWriteDisablesHashTest {

    @Test
    public void testPartialUpdateSetsValueHash32ToZero() throws Exception {
        File dir = new File("target/ds-kvstore-randwrite-hash0-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");
        try {
            byte[] key = "k1".getBytes();
            byte[] value = new byte[200_000];
            new Random(3).nextBytes(value);
            store.put(key, value);

            long indexId = store.getIndexId(key);
            int oldHash = DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20);
            assertEquals(DsKVStore.valueHash32(value), oldHash);

            byte[] patch = new byte[4096];
            Arrays.fill(patch, (byte) 7);
            int offset = 12345;
            store.updateValuePartialByIndexId(indexId, offset, patch);
            System.arraycopy(patch, 0, value, offset, patch.length);

            assertEquals(0, DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20));
            assertArrayEquals(value, store.get(key));

            try (InputStream in = store.getStream(key)) {
                assertNotNull(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream(value.length);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    out.write(buf, 0, n);
                }
                assertArrayEquals(value, out.toByteArray());
            }

            assertEquals(true, store.remove(key));
        } finally {
            store.close();
        }
    }

    @Test
    public void testRandomAccessWriteSetsValueHash32ToZero() throws Exception {
        File dir = new File("target/ds-kvstore-ra-hash0-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");
        try {
            byte[] key = "k2".getBytes();
            byte[] value = new byte[50_000];
            new Random(5).nextBytes(value);
            store.put(key, value);

            long indexId = store.getIndexId(key);
            int oldHash = DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20);
            assertEquals(DsKVStore.valueHash32(value), oldHash);

            byte[] patch = new byte[1000];
            Arrays.fill(patch, (byte) 9);
            int offset = 3333;
            try (DsKVStore.ValueRandomAccess ra = store.openValueRandomAccessByIndexId(indexId)) {
                ra.writeAt(offset, patch, 0, patch.length);
            }
            System.arraycopy(patch, 0, value, offset, patch.length);

            assertEquals(0, DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20));
            assertArrayEquals(value, store.get(key));
        } finally {
            store.close();
        }
    }

    @Test
    public void testStreamPartialUpdateKeepsHashZero() throws Exception {
        File dir = new File("target/ds-kvstore-stream-partial-hash0-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");
        try {
            byte[] key = "k3".getBytes();
            byte[] value = new byte[80_000];
            new Random(8).nextBytes(value);
            store.put(key, value);

            long indexId = store.getIndexId(key);
            byte[] patch = new byte[4096];
            Arrays.fill(patch, (byte) 1);
            int offset = 2000;
            store.updateValuePartialByIndexIdStream(indexId, offset, new ByteArrayInputStream(patch), patch.length);
            System.arraycopy(patch, 0, value, offset, patch.length);

            assertEquals(0, DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20));
            assertArrayEquals(value, store.get(key));
        } finally {
            store.close();
        }
    }
}

