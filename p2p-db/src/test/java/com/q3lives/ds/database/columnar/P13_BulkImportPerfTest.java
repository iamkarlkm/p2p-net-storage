package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStoreTest.UserRow;

public class P13_BulkImportPerfTest {

    private static final String[] COLS = {"username", "age", "flags", "bio"};

    @Test
    public void shouldBatchThroughputBeAtLeastSingle() throws Exception {
        final int N = 2000;
        Map<Long, Map<String, byte[]>> dataset = genDataset(N);

        File dirSingle = Files.createTempDirectory("dsdb-p13-single").toFile();
        long singleNs;
        try (ColumnarStore store = new ColumnarStore(dirSingle)) {
            String old = System.getProperty("p2p.db.home");
            try {
                System.setProperty("p2p.db.home", dirSingle.getAbsolutePath());
                long t0 = System.nanoTime();
                putSingleLoop(store, UserRow.class.getName(), dataset);
                singleNs = System.nanoTime() - t0;
            } finally {
                restoreHome(old);
            }
        } finally {
            deleteRecursively(dirSingle);
        }

        File dirBatch = Files.createTempDirectory("dsdb-p13-batch").toFile();
        long batchNs;
        try (ColumnarStore store = new ColumnarStore(dirBatch)) {
            String old = System.getProperty("p2p.db.home");
            try {
                System.setProperty("p2p.db.home", dirBatch.getAbsolutePath());
                long t0 = System.nanoTime();
                int cells = store.putValueBatch(UserRow.class.getName(), dataset);
                batchNs = System.nanoTime() - t0;
                Assertions.assertEquals(N * COLS.length, cells, "cells count mismatch");
            } finally {
                restoreHome(old);
            }
        } finally {
            deleteRecursively(dirBatch);
        }

        long cellsTotal = (long) N * COLS.length;
        double singlePerSec = (cellsTotal * 1_000_000_000.0) / singleNs;
        double batchPerSec = (cellsTotal * 1_000_000_000.0) / batchNs;
        double ratio = batchPerSec / singlePerSec;
        System.out.printf("[P13 BulkPerf] N=%d cols=%d%n  single: %.0f cells/sec (%d ms)%n  batch:  %.0f cells/sec (%d ms)%n  ratio  batch/single = %.3fx %n",
            N, COLS.length, singlePerSec, singleNs / 1_000_000, batchPerSec, batchNs / 1_000_000, ratio);
        Assertions.assertTrue(ratio >= 1.0,
            String.format("batch throughput must be >= single (got ratio=%.3f batch/single; batch=%.0f/sec vs single=%.0f/sec)",
                ratio, batchPerSec, singlePerSec));
    }

    @Test
    public void shouldBatchProduceByteForByteEqualValues() throws Exception {
        final int N = 800;
        Map<Long, Map<String, byte[]>> dataset = genDataset(N);

        File dirSingle = Files.createTempDirectory("dsdb-p13-cmp-s").toFile();
        File dirBatch = Files.createTempDirectory("dsdb-p13-cmp-b").toFile();
        try {
            try (ColumnarStore store = new ColumnarStore(dirSingle)) {
                String old = System.getProperty("p2p.db.home");
                try {
                    System.setProperty("p2p.db.home", dirSingle.getAbsolutePath());
                    putSingleLoop(store, UserRow.class.getName(), dataset);
                } finally {
                    restoreHome(old);
                }
            }
            try (ColumnarStore store = new ColumnarStore(dirBatch)) {
                String old = System.getProperty("p2p.db.home");
                try {
                    System.setProperty("p2p.db.home", dirBatch.getAbsolutePath());
                    store.putValueBatch(UserRow.class.getName(), dataset);
                } finally {
                    restoreHome(old);
                }
            }

            try (ColumnarStore sSingle = new ColumnarStore(dirSingle);
                 ColumnarStore sBatch = new ColumnarStore(dirBatch)) {
                String oldS = System.getProperty("p2p.db.home");
                try {
                    for (long rowId = 1L; rowId <= N; rowId++) {
                        for (String col : COLS) {
                            System.setProperty("p2p.db.home", dirSingle.getAbsolutePath());
                            byte[] a = sSingle.getValue(UserRow.class, col, rowId);
                            System.setProperty("p2p.db.home", dirBatch.getAbsolutePath());
                            byte[] b = sBatch.getValue(UserRow.class, col, rowId);
                            Assertions.assertTrue(Arrays.equals(a, b),
                                "byte mismatch rowId=" + rowId + " col=" + col
                                    + " single=" + Arrays.toString(a) + " batch=" + Arrays.toString(b));
                        }
                    }
                } finally {
                    restoreHome(oldS);
                }
            }
        } finally {
            deleteRecursively(dirSingle);
            deleteRecursively(dirBatch);
        }
    }

    @Test
    public void shouldBatchValidateArguments() throws Exception {
        File dir = Files.createTempDirectory("dsdb-p13-args").toFile();
        try (ColumnarStore store = new ColumnarStore(dir)) {
            String old = System.getProperty("p2p.db.home");
            try {
                System.setProperty("p2p.db.home", dir.getAbsolutePath());
                Assertions.assertEquals(0, store.putValueBatch(UserRow.class.getName(), null));
                Assertions.assertEquals(0, store.putFieldGroupBatch(UserRow.class.getName(), "PROFILE", null));
                Assertions.assertEquals(0, store.putCompositeGroupBatch(UserRow.class, "STATUS", null));
                IllegalArgumentException ex1 = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.putValueBatch("  ", new TreeMap<>()));
                Assertions.assertTrue(ex1.getMessage().contains("blank"));
                IllegalArgumentException ex2 = Assertions.assertThrows(IllegalArgumentException.class,
                    () -> store.putValueBatch(UserRow.class.getName(), buildBadUnknownCol()));
                Assertions.assertTrue(ex2.getMessage().contains("unknown column"),
                    "expected 'unknown column' in: " + ex2.getMessage());
            } finally {
                restoreHome(old);
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private static Map<Long, Map<String, byte[]>> buildBadUnknownCol() {
        Map<Long, Map<String, byte[]>> m = new TreeMap<>();
        Map<String, byte[]> row = new HashMap<>();
        row.put("this_col_does_not_exist_xyz", new byte[0]);
        m.put(1L, row);
        return m;
    }

    private static void putSingleLoop(ColumnarStore store, String entity, Map<Long, Map<String, byte[]>> dataset) throws IOException {
        for (Map.Entry<Long, Map<String, byte[]>> re : dataset.entrySet()) {
            long rowId = re.getKey();
            Map<String, byte[]> cols = re.getValue();
            for (Map.Entry<String, byte[]> ce : cols.entrySet()) {
                store.putValue(entity, ce.getKey(), rowId, ce.getValue());
            }
        }
    }

    private static Map<Long, Map<String, byte[]>> genDataset(int n) {
        TreeMap<Long, Map<String, byte[]>> out = new TreeMap<>();
        for (int i = 1; i <= n; i++) {
            long rowId = i;
            Map<String, byte[]> cols = new HashMap<>();
            cols.put("username", String.format("u%08d_name", i).getBytes(StandardCharsets.UTF_8));
            cols.put("age", intBytes(i % 120));
            cols.put("flags", longBytes(0xDEAD_BEEF_CAFE_BABEL + i));
            cols.put("bio", String.format("bio_lorem_ipsum_%d_%s", i, "x".repeat(i % 40))
                .getBytes(StandardCharsets.UTF_8));
            out.put(rowId, cols);
        }
        return out;
    }

    private static byte[] intBytes(int v) {
        byte[] b = new byte[4];
        b[0] = (byte) (v >>> 24);
        b[1] = (byte) (v >>> 16);
        b[2] = (byte) (v >>> 8);
        b[3] = (byte) v;
        return b;
    }

    private static byte[] longBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    private static void restoreHome(String old) {
        if (old == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", old);
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursively(c);
        }
        f.delete();
    }

    @SuppressWarnings("unused")
    private static Class<? extends DsTableAdapter> userRow = UserRow.class;
}
