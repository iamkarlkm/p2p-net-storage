package com.q3lives.ds.kv;

import com.q3lives.ds.util.DsDataUtil;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class DsKVStoreLargeValueHash32Test {

    @Test
    public void testValueHash32UsesFirstBlockWhenValueIsLarge() throws Exception {
        int oldMax = DsKVStore.maxValueHash32Bytes;
        DsKVStore.maxValueHash32Bytes = 16;
        try {
            byte[] value = new byte[64];
            for (int i = 0; i < value.length; i++) {
                value[i] = (byte) i;
            }

            int partial = DsDataUtil.hash32(value, 0, 16);
            int full = DsDataUtil.hash32(value);
            assertNotEquals(full, partial);
            assertEquals(partial, DsKVStore.valueHash32(value));

            File dir = new File("target/ds-kvstore-largehash-test-" + System.nanoTime());
            dir.mkdirs();
            DsKVStore store = new DsKVStore(dir.getPath(), "kv");
            try {
                byte[] key = "k1".getBytes();
                long indexId = store.put(key, value);
                assertEquals(partial, DsDataUtil.loadInt(store.getIndexRecordForTest(indexId), 20));
                assertArrayEquals(value, store.get(key));
            } finally {
                store.close();
            }
        } finally {
            DsKVStore.maxValueHash32Bytes = oldMax;
        }
    }
}
