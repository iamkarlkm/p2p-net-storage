package com.q3lives.ds.kv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DsKVStoreChunkedAutoGrowTest {

    @Test
    public void testPutStreamChunkedAndReadBack() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 1024;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-chunked-stream-test-" + System.nanoTime());
            dir.mkdirs();

            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k1".getBytes();
                byte[] value = new byte[3000];
                new Random(1).nextBytes(value);

                try (InputStream in = new ByteArrayInputStream(value)) {
                    store.putStream(key, in, value.length);
                }

                assertArrayEquals(value, store.get(key));
                try (InputStream in2 = store.getStream(key)) {
                    assertNotNull(in2);
                    ByteArrayOutputStream out = new ByteArrayOutputStream(value.length);
                    byte[] buf = new byte[512];
                    int n;
                    while ((n = in2.read(buf)) >= 0) {
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
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }

    @Test
    public void testPartialUpdateAutoGrowsAndConvertsToChunked() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 1024;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-autogrow-convert-test-" + System.nanoTime());
            dir.mkdirs();

            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k2".getBytes();
                byte[] value = new byte[900];
                new Random(2).nextBytes(value);
                store.put(key, value);

                long indexId = store.getIndexId(key);
                byte[] patch = new byte[500];
                new Random(3).nextBytes(patch);
                int offset = 800;
                store.updateValuePartialByIndexId(indexId, offset, patch);

                byte[] expected = new byte[offset + patch.length];
                System.arraycopy(value, 0, expected, 0, value.length);
                System.arraycopy(patch, 0, expected, offset, patch.length);

                byte[] indexRecord = store.getIndexRecordForTest(indexId);
                assertEquals(expected.length, com.q3lives.ds.util.DsDataUtil.loadInt(indexRecord, 16));
                assertEquals(0, com.q3lives.ds.util.DsDataUtil.loadInt(indexRecord, 20));
                assertArrayEquals(expected, store.get(key));
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }

    @Test
    public void testChunkedAppendByStreamAutoGrows() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 1024;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-chunked-append-test-" + System.nanoTime());
            dir.mkdirs();

            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k3".getBytes();
                byte[] value = new byte[2000];
                new Random(4).nextBytes(value);
                try (InputStream in = new ByteArrayInputStream(value)) {
                    store.putStream(key, in, value.length);
                }

                long indexId = store.getIndexId(key);
                byte[] patch = new byte[1000];
                new Random(5).nextBytes(patch);
                try (InputStream in2 = new ByteArrayInputStream(patch)) {
                    store.updateValuePartialByIndexIdStream(indexId, value.length, in2, patch.length);
                }

                byte[] expected = new byte[value.length + patch.length];
                System.arraycopy(value, 0, expected, 0, value.length);
                System.arraycopy(patch, 0, expected, value.length, patch.length);

                assertArrayEquals(expected, store.get(key));
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }

    @Test
    public void testSparseHoleIsZeroInChunkedMode() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 1024;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-sparse-hole-chunked-test-" + System.nanoTime());
            dir.mkdirs();

            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k4".getBytes();
                byte[] value = new byte[1200];
                new Random(6).nextBytes(value);
                store.put(key, value);

                long indexId = store.getIndexId(key);
                byte[] patch = new byte[10];
                for (int i = 0; i < patch.length; i++) {
                    patch[i] = (byte) (100 + i);
                }
                int offset = 3000;
                store.updateValuePartialByIndexId(indexId, offset, patch);

                byte[] out = store.get(key);
                assertEquals(offset + patch.length, out.length);
                for (int i = value.length; i < offset; i++) {
                    assertEquals(0, out[i]);
                }
                for (int i = 0; i < patch.length; i++) {
                    assertEquals(patch[i], out[offset + i]);
                }
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }

    @Test
    public void testSparseHoleIsZeroInSingleBlockMode() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 4096;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-sparse-hole-single-test-" + System.nanoTime());
            dir.mkdirs();

            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k5".getBytes();
                byte[] value = new byte[100];
                new Random(7).nextBytes(value);
                store.put(key, value);

                long indexId = store.getIndexId(key);
                byte[] patch = new byte[20];
                new Random(8).nextBytes(patch);
                int offset = 300;
                store.updateValuePartialByIndexId(indexId, offset, patch);

                byte[] out = store.get(key);
                assertEquals(offset + patch.length, out.length);
                for (int i = value.length; i < offset; i++) {
                    assertEquals(0, out[i]);
                }
                for (int i = 0; i < patch.length; i++) {
                    assertEquals(patch[i], out[offset + i]);
                }
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }
}
