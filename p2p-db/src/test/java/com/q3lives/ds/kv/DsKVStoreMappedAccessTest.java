package com.q3lives.ds.kv;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DsKVStoreMappedAccessTest {

    @Test
    public void testMappedWriteAutoGrowSingle() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 4096;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-mapped-single-" + System.nanoTime());
            dir.mkdirs();
            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "m1".getBytes();
                byte[] value = new byte[100];
                new Random(1).nextBytes(value);
                store.put(key, value);
                long indexId = store.getIndexId(key);

                int offset = 300;
                byte[] patch = new byte[20];
                for (int i = 0; i < patch.length; i++) {
                    patch[i] = (byte) (10 + i);
                }

                try (DsKVStore.ValueMappedAccess ma = store.openValueMappedAccessByIndexId(indexId)) {
                    List<DsKVStore.ValueMappedWindow> windows = ma.mapWindows(offset, patch.length, true);
                    assertEquals(1, windows.size());
                    try (DsKVStore.ValueMappedWindow w = windows.get(0)) {
                        ByteBuffer buf = w.buffer();
                        buf.put(patch);
                        w.force();
                    }
                }

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
    public void testMappedWriteAutoGrowChunkedAcrossChunks() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 256;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-mapped-chunked-" + System.nanoTime());
            dir.mkdirs();
            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "m2".getBytes();
                byte[] value = new byte[100];
                new Random(2).nextBytes(value);
                store.put(key, value);
                long indexId = store.getIndexId(key);

                int offset = 200;
                byte[] patch = new byte[200];
                new Random(3).nextBytes(patch);

                try (DsKVStore.ValueMappedAccess ma = store.openValueMappedAccessByIndexId(indexId)) {
                    List<DsKVStore.ValueMappedWindow> windows = ma.mapWindows(offset, patch.length, true);
                    int written = 0;
                    for (DsKVStore.ValueMappedWindow w : windows) {
                        try (w) {
                            ByteBuffer buf = w.buffer();
                            int can = buf.remaining();
                            buf.put(patch, written, can);
                            w.force();
                            written += can;
                        }
                    }
                    assertEquals(patch.length, written);
                }

                byte[] out = store.get(key);
                byte[] expected = new byte[offset + patch.length];
                System.arraycopy(value, 0, expected, 0, value.length);
                System.arraycopy(patch, 0, expected, offset, patch.length);
                assertArrayEquals(expected, out);
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueChunkBytes = oldChunk;
            DsKVStore.maxValueHash32Bytes = oldHash;
        }
    }

    @Test
    public void testMappedReadHoleUsesDirectZeroBuffer() throws Exception {
        int oldChunk = DsKVStore.maxValueChunkBytes;
        int oldHash = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueChunkBytes = 256;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            File dir = new File("target/ds-kvstore-mapped-hole-read-" + System.nanoTime());
            dir.mkdirs();
            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "m3".getBytes();
                store.put(key, new byte[1]);
                long indexId = store.getIndexId(key);

                byte[] patch = new byte[] { 1, 2, 3, 4 };
                store.updateValuePartialByIndexId(indexId, 600, patch);

                try (DsKVStore.ValueMappedAccess ma = store.openValueMappedAccessByIndexId(indexId)) {
                    List<DsKVStore.ValueMappedWindow> windows = ma.mapWindows(10, 200, false);
                    for (DsKVStore.ValueMappedWindow w : windows) {
                        try (w) {
                            ByteBuffer b = w.buffer();
                            assertTrue(b.isDirect());
                            while (b.hasRemaining()) {
                                assertEquals(0, b.get());
                            }
                        }
                    }
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
