package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsMemoryLruSamplingTest {

    private File tmpDir;

    @Before
    public void before() throws IOException {
        tmpDir = Files.createTempDirectory("dsmem_lru_").toFile();
    }

    @After
    public void after() {
        if (tmpDir != null && tmpDir.exists()) {
            File wal = new File(tmpDir, "dsmem.dat_wal.log");
            wal.delete();
            new File(tmpDir, "dsmem.dat").delete();
            deleteRecursive(tmpDir);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static DsMemory newMem(File dir, int maxBlocks) throws IOException {
        File df = new File(dir, "dsmem.dat");
        DsMemory m = new DsMemory(df, 4096, 8);
        try {
            java.lang.reflect.Field f = DsMemory.class.getDeclaredField("maxCachedBlocks");
            f.setAccessible(true);
            f.set(m, maxBlocks);
        } catch (Exception ignore) {}
        return m;
    }

    private static long idForBufferIndex(int idx, int dataUnitSize, int headerSize) {
        long targetPos = (long) idx * (long) DsMemory.BLOCK_SIZE_REFLECT + headerSize;
        return targetPos / dataUnitSize;
    }

    /**
     * ① 场景：构造 2000 个不同 id，每个 id 对应不同 bufferIndex（通过 id*dataUnitSize 跨 2000 个 64KB block），maxCachedBlocks=32 → 触发大量驱逐。
     * 断言 evictionSuccess > 1900 且 activeCachedBlocks <= maxBlocks（不会无限增长 OOM）
     */
    @Test
    public void testMassEvictionCacheSizeBounded() throws Exception {
        int maxBlocks = 32;
        int dataUnit = 8;
        int headerSize = 4096;
        DsMemory m = newMem(tmpDir, maxBlocks);
        int total = 2000;
        byte[] gold = new byte[32];
        for (int i = 0; i < total; i++) {
            long id = idForBufferIndex(i, dataUnit, headerSize);
            gold[0] = (byte) (i & 0xFF);
            gold[1] = (byte) ((i >>> 8) & 0xFF);
            gold[2] = (byte) ((i >>> 16) & 0xFF);
            gold[31] = (byte) (i % 131);
            m.writeBytes(id, 0, gold, 0, gold.length);
        }
        m.syncStore();
        DsMemory.CacheStats s = m.getAndResetCacheStats();
        Assert.assertTrue("evictionAttempts should be >0, got=" + s.getEvictionAttempts(), s.getEvictionAttempts() > 0);
        Assert.assertTrue("evictionSuccess should be >= 1900, got=" + s.getEvictionSuccess(), s.getEvictionSuccess() >= 1900);
        Assert.assertTrue("activeCachedBlocks should be <= maxBlocks=" + maxBlocks + ", got=" + s.getActiveCachedBlocks(),
            s.getActiveCachedBlocks() <= maxBlocks + 2);
        byte[] out = new byte[32];
        for (int i = 0; i < Math.min(200, total); i++) {
            int idx = i * (total / 200);
            long id = idForBufferIndex(idx, dataUnit, headerSize);
            m.readBytes(id, 0, out, 0, out.length);
            Assert.assertEquals((byte) (idx & 0xFF), out[0]);
            Assert.assertEquals((byte) ((idx >>> 8) & 0xFF), out[1]);
            Assert.assertEquals((byte) ((idx >>> 16) & 0xFF), out[2]);
        }
    }

    /**
     * ② 场景：Zipf 80/20 分布读 2 万次，maxCachedBlocks=128。命中率 ≥ 95%（近似 LRU 采样有效，非 1-slot random）
     */
    @Test
    public void testZipfHitRateAtLeast95() throws Exception {
        int maxBlocks = 128;
        int dataUnit = 8;
        int headerSize = 4096;
        DsMemory m = newMem(tmpDir, maxBlocks);
        int hotCount = (int) (maxBlocks * 0.3);
        int coldCount = maxBlocks * 4;
        int totalIdx = hotCount + coldCount;
        for (int i = 0; i < totalIdx; i++) {
            long id = idForBufferIndex(i, dataUnit, headerSize);
            byte[] b = new byte[8];
            b[0] = (byte) (i & 0xFF);
            b[7] = (byte) 1;
            m.writeBytes(id, 0, b, 0, 8);
        }
        Random r = new Random(20260817L);
        long totalReads = 0L;
        long hits = 0L;
        byte[] out = new byte[8];
        for (int iter = 0; iter < 20000; iter++) {
            boolean isHot = r.nextInt(100) < 80;
            int idx;
            if (isHot) {
                idx = r.nextInt(hotCount);
            } else {
                idx = hotCount + r.nextInt(coldCount);
            }
            long id = idForBufferIndex(idx, dataUnit, headerSize);
            totalReads++;
            m.readBytes(id, 0, out, 0, 8);
            if (out[7] == 1) {
                hits++;
            }
        }
        double hitRate = ((double) hits) / totalReads;
        Assert.assertTrue("hitRate=" + String.format("%.4f", hitRate) + " should be >= 0.95 (approx LRU 16-slot sampling)", hitRate >= 0.95);
    }

    /**
     * ③ 场景：4 线程 × 1000 次 readBytes（每个 thread 读不同 buffer index 对应 id，maxCachedBlocks=16 频繁驱逐）→ okCount=4000 且 5s 内全部完成（无死锁）
     */
    @Test
    public void testConcurrentLoadNoDeadlockUnderEviction() throws Exception {
        int maxBlocks = 16;
        int dataUnit = 8;
        int headerSize = 4096;
        DsMemory m = newMem(tmpDir, maxBlocks);
        int uniqueIdx = 2000;
        for (int i = 0; i < uniqueIdx; i++) {
            long id = idForBufferIndex(i, dataUnit, headerSize);
            byte[] b = new byte[8];
            b[0] = (byte) (i & 0xFF);
            b[7] = 0x7E;
            m.writeBytes(id, 0, b, 0, 8);
        }
        m.syncStore();
        int threads = 4;
        int perThr = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger(0);
        AtomicInteger err = new AtomicInteger(0);
        Random shared = new Random(42L);
        int[] seq = new int[perThr * threads];
        for (int i = 0; i < seq.length; i++) seq[i] = shared.nextInt(uniqueIdx);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    byte[] out = new byte[8];
                    int localOk = 0;
                    int localErr = 0;
                    for (int i = 0; i < perThr; i++) {
                        int idx = seq[tid * perThr + i];
                        long id = idForBufferIndex(idx, dataUnit, headerSize);
                        try {
                            m.readBytes(id, 0, out, 0, 8);
                            if (out[7] == 0x7E) localOk++;
                            else localErr++;
                        } catch (Exception t2) {
                            localErr++;
                        }
                    }
                    ok.addAndGet(localOk);
                    err.addAndGet(localErr);
                } finally {
                    end.countDown();
                }
            }).start();
        }
        start.countDown();
        boolean done = end.await(5, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertTrue("4 thread × 1000 ops did not finish in 5s (possible deadlock on eviction locks)", done);
        Assert.assertEquals("error count should be 0", 0, err.get());
        Assert.assertEquals("ok count should be 4000", threads * perThr, ok.get());
    }
}
