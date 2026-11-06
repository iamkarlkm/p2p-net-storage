package com.q3lives.ds.database.tx;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;

public class P14_AcidPerfTest {

    @DsTable(name = "p14_perf_row", mvcc = true)
    public static class PerfRow extends DsTableAdapter {
        @DsField(indexed = true, length = 8) public int a;
        @DsField(length = 16) public long b;
        @DsField(length = 4) public short c;
        @DsField(length = 2) public byte d;
        @DsField(length = 16) public long e;
    }

    private File home;

    @BeforeEach
    public void before() throws Exception {
        this.home = Files.createTempDirectory("dsdb-p14-perf-u1").toFile();
    }

    @AfterEach
    public void after() {
        deleteRecursively(home);
    }

    @Test
    public void U1_fiveLeafTxP99() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            final int WARMUP = 200;
            final int N = 500;
            final int ROUNDS = 3;
            double[] ratios = new double[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < WARMUP; i++) {
                    PerfRow r = new PerfRow();
                    r.a = -1 - i; r.b = i; r.c = (short) i; r.d = (byte) i; r.e = i;
                    db.putEntity(r, true);
                }
                long[] baselineNs = new long[N];
                for (int i = 0; i < N; i++) {
                    long s = System.nanoTime();
                    PerfRow r = new PerfRow();
                    r.a = 10_000_000 + round * N + i;
                    r.b = i; r.c = (short) i; r.d = (byte) i; r.e = i;
                    db.putEntity(r, true);
                    baselineNs[i] = System.nanoTime() - s;
                }
                long[] txNs = new long[N];
                final int baseId = 20_000_000 + round * N * 5;
                for (int i = 0; i < N; i++) {
                    long s = System.nanoTime();
                    try (Transaction tx = db.beginTransaction()) {
                        for (int k = 0; k < 5; k++) {
                            PerfRow r = new PerfRow();
                            r.a = baseId + i * 5 + k;
                            r.b = i; r.c = (short) i; r.d = (byte) i; r.e = k;
                            db.putEntity(r, true);
                        }
                        tx.commit();
                    }
                    txNs[i] = (System.nanoTime() - s) / 5;
                }
                Arrays.sort(baselineNs);
                Arrays.sort(txNs);
                long bp99 = baselineNs[(int) (baselineNs.length * 0.99)];
                long tp99 = txNs[(int) (txNs.length * 0.99)];
                ratios[round] = tp99 <= 0 ? 1.0 : (double) tp99 / bp99;
                System.out.println("[U1 R" + round + "] baselineP99=" + bp99
                    + "ns txP99(per-op)=" + tp99 + "ns ratio=" + ratios[round]);
            }
            Arrays.sort(ratios);
            double ratio = ratios[ROUNDS / 2];
            int score = ratio <= 1.50 ? 2 : (ratio <= 2.00 ? 1 : 0);
            System.out.println("[U1 FINAL] median-ratio(5 rounds)=" + ratio + " score=" + score
                + " (≤1.50→2, ≤2.00→1, >2.00→0, PASS≥1)");
            Assertions.assertTrue(score >= 1,
                "U1 5 叶事务 P99 相对单行写退化 ≤ 2.00× (score≥1)，实际 ratio=" + ratio);
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        try { f.delete(); } catch (Exception ignore) {}
    }
}
