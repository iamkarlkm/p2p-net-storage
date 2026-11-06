package com.q3lives.ds.database.columnar;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.database.columnar.index.EqIndexMetaStore;
import com.q3lives.ds.database.columnar.index.EqIndexStore;
import com.q3lives.ds.util.BloomFilter64;
import com.q3lives.ds.util.DsDataUtil;

public class P13_BloomFilterTest {

    @Test
    public void shouldObserveFppWithinTolerance() {
        BloomFilter64 bf = new BloomFilter64(100_000L, 0.01);
        for (int i = 0; i < 100_000; i++) {
            byte[] v = longToBytes(i);
            bf.add(v);
        }
        int fp = 0;
        int total = 200_000;
        for (int i = 100_000; i < 100_000 + total; i++) {
            byte[] v = longToBytes(i);
            if (bf.mightContain(v)) {
                fp++;
            }
        }
        double rate = (double) fp / (double) total;
        Assertions.assertTrue(rate <= 0.02,
            "Bloom FPP too high: fp=" + fp + "/" + total + " rate=" + String.format("%.4f", rate) + " (expected <=0.02)");
    }

    @Test
    public void shouldRoundtripSerialization() {
        BloomFilter64 bf = new BloomFilter64(20_000L, 0.01);
        for (int i = 0; i < 20_000; i++) {
            bf.add(longToBytes(i));
        }
        byte[] raw = bf.toBytes();
        Assertions.assertTrue(raw.length > 0);
        BloomFilter64 restored = BloomFilter64.fromBytes(raw);
        Assertions.assertEquals(bf.getNumBits(), restored.getNumBits());
        Assertions.assertEquals(bf.getNumHashFunctions(), restored.getNumHashFunctions());
        for (int i = 0; i < 20_000; i++) {
            Assertions.assertTrue(restored.mightContain(longToBytes(i)), "missing true-positive #" + i);
        }
        for (int i = 20_000; i < 40_000; i++) {
            boolean a = bf.mightContain(longToBytes(i));
            boolean b = restored.mightContain(longToBytes(i));
            Assertions.assertEquals(a, b, "mightContain mismatch for #" + i);
        }
    }

    @Test
    public void shouldEqIndexZeroIoOnBloomMiss() throws Exception {
        File home = Files.createTempDirectory("dsdb-p13-bloom").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            EqIndexStore store = new EqIndexStore(home);
            String entity = "User";
            long colId = 42L;
            EqIndexMetaStore.IndexDef def = new EqIndexMetaStore.IndexDef();
            def.entityClassName = entity;
            def.logicalName = "idx_" + colId;
            def.colId = colId;
            new EqIndexMetaStore(home).put(def);
            AtomicInteger dsHashMapCount = new AtomicInteger(0);
            store.enableBloomForColumn(entity, colId, 10_000L, 0.01, dsHashMapCount);
            for (int i = 0; i < 1000; i++) {
                byte[] v = ("present_" + i).getBytes();
                store.add(entity, colId, v, 1000L + i);
            }
            for (int i = 0; i < 1000; i++) {
                byte[] v = ("present_" + i).getBytes();
                long[] rows = store.findRowIds(entity, colId, v, 10);
                Assertions.assertTrue(rows.length >= 1, "should find present value #" + i);
            }
            int beforeMiss = dsHashMapCount.get();
            int missCount = 0;
            for (int i = 0; i < 99_999; i++) {
                byte[] v = ("absent_" + i).getBytes();
                long[] rows = store.findRowIds(entity, colId, v, 1);
                if (rows.length == 0) {
                    missCount++;
                }
            }
            int extra = dsHashMapCount.get() - beforeMiss;
            Assertions.assertEquals(99_999, missCount, "absent values should return empty array");
            Assertions.assertTrue(extra <= 2000,
                "Bloom should short-circuit; DsHashMap constructor called " + extra + " times during miss (expected <= 2000 for ~2% fp)");
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
            deleteRecursively(home);
        }
    }

    @Test
    public void shouldHaveNoOverheadWhenBloomDisabled() throws Exception {
        File home = Files.createTempDirectory("dsdb-p13-bloom-off").toFile();
        String old = System.getProperty("p2p.db.home");
        try {
            System.setProperty("p2p.db.home", home.getAbsolutePath());
            EqIndexStore store = new EqIndexStore(home);
            String entity = "User";
            long colId = 7L;
            EqIndexMetaStore.IndexDef def = new EqIndexMetaStore.IndexDef();
            def.entityClassName = entity;
            def.logicalName = "idx_" + colId;
            def.colId = colId;
            new EqIndexMetaStore(home).put(def);
            for (int i = 0; i < 500; i++) {
                store.add(entity, colId, ("v" + i).getBytes(), 1L + i);
            }
            long[] rows = store.findRowIds(entity, colId, "v42".getBytes(), 10);
            Assertions.assertTrue(rows.length >= 1);
            Assertions.assertFalse(store.isBloomEnabled(entity, colId),
                "Bloom should not be enabled unless explicitly enabled");
        } finally {
            if (old == null) {
                System.clearProperty("p2p.db.home");
            } else {
                System.setProperty("p2p.db.home", old);
            }
            deleteRecursively(home);
        }
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        ByteBuffer.wrap(b).putLong(v);
        return b;
    }

    @SuppressWarnings("unused")
    private static long unusedFnv(byte[] b) {
        long h = DsDataUtil.hash64(b);
        return h;
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) deleteRecursively(c);
            }
        }
        f.delete();
    }
}
