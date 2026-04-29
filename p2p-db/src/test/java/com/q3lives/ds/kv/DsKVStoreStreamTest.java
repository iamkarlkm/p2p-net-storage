package com.q3lives.ds.kv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

public class DsKVStoreStreamTest {

    @Test
    public void testPutStreamAndGetStream() throws Exception {
        File dir = new File("target/ds-kvstore-stream-test-" + System.nanoTime());
        dir.mkdirs();

        DsKVStore store = new DsKVStore(dir.getPath(), "kv");
        try {
            byte[] key = "k-stream".getBytes();
            byte[] value = new byte[700_000];
            new Random(9).nextBytes(value);

            try (InputStream in = new ByteArrayInputStream(value)) {
                store.putStream(key, in, value.length);
            }

            try (InputStream in2 = store.getStream(key)) {
                assertNotNull(in2);
                ByteArrayOutputStream out = new ByteArrayOutputStream(value.length);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in2.read(buf)) >= 0) {
                    if (n == 0) {
                        continue;
                    }
                    out.write(buf, 0, n);
                }
                assertArrayEquals(value, out.toByteArray());
            }
        } finally {
            store.close();
        }
    }
}

