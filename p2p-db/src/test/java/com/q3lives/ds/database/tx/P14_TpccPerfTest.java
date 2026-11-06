package com.q3lives.ds.database.tx;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.tx.P14_TpccNewOrderTest.OLine;
import com.q3lives.ds.database.tx.P14_TpccNewOrderTest.TpccIds;

public class P14_TpccPerfTest {

    private File home;

    @BeforeEach
    public void before() throws Exception {
        this.home = Files.createTempDirectory("dsdb-p14-perf-u2").toFile();
    }

    @AfterEach
    public void after() {
        P14_AcidPerfTest.deleteRecursively(home);
    }

    @Test
    @Timeout(value = 900, unit = TimeUnit.SECONDS)
    public void U2_newOrderConcurrent() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(home)) {
            final int W = 1, D = 2, C = 20, I = 50, N = 500;
            final TpccIds ids = P14_TpccNewOrderTest.loadSeedData(db, W, D, C, I);
            final long seedA = 17L;

            // Scenario A: serial for-loop 1000 newOrder (tx wrap 每 1 次 commit, 串行)
            long sA = System.nanoTime();
            int successA = 0;
            Random rndA = new Random(seedA);
            for (int i = 0; i < N; i++) {
                int dId = 1 + (i % D);
                int cId = 1 + rndA.nextInt(C);
                int lineCount = 1 + (i % 5);
                List<OLine> lines = new ArrayList<>(lineCount);
                for (int k = 0; k < lineCount; k++) {
                    lines.add(new OLine(1 + rndA.nextInt(I), W, 1 + (k % 3)));
                }
                try {
                    P14_TpccNewOrderTest.newOrderTx(db, ids, W, dId, cId, lines);
                    successA++;
                } catch (Exception ignore) {}
            }
            long durA = Math.max(1L, System.nanoTime() - sA);
            double tpsA = (double) successA * 1_000_000_000.0 / durA;

            // Scenario B: 10 threads × each 100 tx = 1000 total
            final int THREADS = 10, PER = N / THREADS;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(THREADS);
            AtomicInteger successB = new AtomicInteger();
            long sB = System.nanoTime();
            for (int t = 0; t < THREADS; t++) {
                final int tid = t;
                Thread th = new Thread(() -> {
                    try {
                        start.await();
                        Random lr = new Random(seedA + tid * 131L);
                        for (int i = 0; i < PER; i++) {
                            int dId = 1 + ((tid * PER + i) % D);
                            int cId = 1 + lr.nextInt(C);
                            int linesN = 1 + ((tid * PER + i) % 5);
                            List<OLine> lines = new ArrayList<>(linesN);
                            for (int k = 0; k < linesN; k++) {
                                lines.add(new OLine(1 + lr.nextInt(I), W, 1 + (k % 3)));
                            }
                            try {
                                P14_TpccNewOrderTest.newOrderTx(db, ids, W, dId, cId, lines);
                                successB.incrementAndGet();
                            } catch (Exception ignore) {}
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }, "p14-u2-t" + t);
                th.setDaemon(true);
                th.start();
            }
            start.countDown();
            boolean ok = done.await(360, TimeUnit.SECONDS);
            long durB = Math.max(1L, System.nanoTime() - sB);
            double tpsB = (double) successB.get() * 1_000_000_000.0 / durB;
            double ratio = tpsA == 0 ? 0 : tpsB / tpsA;
            int score = ratio >= 1.00 ? 2 : (ratio >= 0.50 ? 1 : 0);
            System.out.println("[U2] serial: ok=" + successA + "/" + N
                + " tps=" + String.format("%.2f", tpsA));
            System.out.println("[U2] concurrent(10T×" + PER + "): ok=" + successB
                + " tps=" + String.format("%.2f", tpsB) + " done.await=" + ok);
            System.out.println("[U2] ratio(B/A)=" + String.format("%.3f", ratio)
                + " score=" + score + " (≥1.00→2, ≥0.50→1, <0.50→0, PASS≥1)");
            Assertions.assertTrue(ok, "并发场景 360s 内应完成，success=" + successB.get());
            Assertions.assertTrue(score >= 1,
                "U2 并发吞吐 ≥ 串行 × 0.50 (score≥1)，实际 ratio=" + ratio);
        }
    }
}
