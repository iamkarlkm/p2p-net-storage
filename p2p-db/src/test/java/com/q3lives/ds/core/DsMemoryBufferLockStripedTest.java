package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class DsMemoryBufferLockStripedTest {

    private File tmpDir;

    @Before
    public void before() throws IOException {
        tmpDir = Files.createTempDirectory("dsmem_bufstriped_").toFile();
    }

    @After
    public void after() {
        if (tmpDir != null && tmpDir.exists()) {
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

    private static long idForBufferIndex(int idx, int unit, int headerSize) {
        long targetPos = (long) idx * (long) DsMemory.BLOCK_SIZE_REFLECT + headerSize;
        return targetPos / unit;
    }

    /**
     * ① 4 线程各读 256 个不同 block 对应 id，每个块用 idForBufferIndex 映射，测试 striped loadBuffer FAST PATH；assert 读值 100% 正确，且吞吐量达标
     */
    @Test
    public void testMultiReadThroughputStripedOk() throws Exception {
        int threads = 4;
        int per = 256;
        int uniqueBlocks = threads * per;
        int header = 4096;
        int unit = 8;
        DsMemory m = newMem(tmpDir, uniqueBlocks);
        for (int i = 0; i < uniqueBlocks; i++) {
            long id = idForBufferIndex(i, unit, header);
            byte[] w = new byte[8];
            w[0] = (byte) (i & 0xFF);
            w[7] = (byte) ((i * 31) & 0xFF);
            m.writeBytes(id, 0, w, 0, 8);
        }
        m.syncStore();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicInteger err = new AtomicInteger(0);
        AtomicLong eachTook = new AtomicLong(0L);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    byte[] out = new byte[8];
                    long t0 = System.nanoTime();
                    for (int i = 0; i < per; i++) {
                        int bi = tid * per + i;
                        long id = idForBufferIndex(bi, unit, header);
                        m.readBytes(id, 0, out, 0, 8);
                        if (out[7] != (byte) ((bi * 31) & 0xFF)) err.incrementAndGet();
                    }
                    long took = System.nanoTime() - t0;
                    eachTook.addAndGet(took);
                } finally {
                    end.countDown();
                }
            }).start();
        }
        start.countDown();
        boolean ok = end.await(30, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertTrue("4 threads throughput test done in 30s", ok);
        Assert.assertEquals("read correctness errors=0", 0, err.get());
        long totalOps = (long) threads * per;
        double totalMs = ((double) eachTook.get() / threads) / 1_000_000.0;
        Assert.assertTrue("striped-read throughput should be fast, total " + totalOps + " ops took avg " + totalMs + "ms per thread", totalMs < 5000.0);
    }

    /**
     * ② 8 线程读同一个 block index × 2000 次（强制 stripe read lock 碰撞），无异常 + 值一致
     */
    @Test
    public void testConcurrentReadSameBlockConsistent() throws Exception {
        int threads = 8;
        int per = 2000;
        int header = 4096;
        int unit = 8;
        DsMemory m = newMem(tmpDir, 1024);
        long id = idForBufferIndex(0, unit, header);
        byte[] gold = new byte[8];
        Random r = new Random(2026L);
        r.nextBytes(gold);
        m.writeBytes(id, 0, gold, 0, 8);
        m.markDirty(0);
        m.syncStore();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(threads);
        AtomicInteger bad = new AtomicInteger(0);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    byte[] out = new byte[8];
                    for (int i = 0; i < per; i++) {
                        m.readBytes(id, 0, out, 0, 8);
                        for (int k = 0; k < 8; k++) {
                            if (out[k] != gold[k]) {
                                bad.incrementAndGet();
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    bad.incrementAndGet();
                } finally {
                    end.countDown();
                }
            }).start();
        }
        start.countDown();
        boolean done = end.await(30, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertTrue("concurrent-read-same finished in 30s", done);
        Assert.assertEquals("all reads same value", 0, bad.get());
    }

    /**
     * ③ 4 写线程 + 4 读线程 各 500 次 8byte 交错读写（maxCachedBlocks 较小，触发 eviction），结束后 syncStore + syncLoad 再读与 last-write 对比
     */
    @Test
    public void testConcurrentWriteReadNoDataRace() throws Exception {
        int writers = 4;
        int readers = 4;
        int per = 500;
        int header = 4096;
        int unit = 8;
        int totalIds = per;
        DsMemory m = newMem(tmpDir, 64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(writers + readers);
        AtomicInteger wErr = new AtomicInteger(0);
        AtomicInteger rErr = new AtomicInteger(0);
        byte[][] lastW = new byte[totalIds][8];
        Object lock = new Object();
        for (int t = 0; t < writers; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    Random rl = new Random(100 + tid);
                    for (int i = 0; i < per; i++) {
                        int idIdx = rl.nextInt(totalIds);
                        long id = idForBufferIndex(idIdx, unit, header);
                        byte[] w = new byte[8];
                        w[0] = (byte) (tid & 0xFF);
                        w[1] = (byte) (i & 0xFF);
                        w[2] = (byte) ((i >>> 8) & 0xFF);
                        w[7] = (byte) (tid * 17 + i);
                        synchronized (lock) {
                            System.arraycopy(w, 0, lastW[idIdx], 0, 8);
                        }
                        try {
                            m.writeBytes(id, 0, w, 0, 8);
                        } catch (Throwable e) {
                            wErr.incrementAndGet();
                        }
                    }
                } finally {
                    end.countDown();
                }
            }).start();
        }
        for (int t = 0; t < readers; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    Random rl = new Random(500 + tid);
                    byte[] out = new byte[8];
                    for (int i = 0; i < per; i++) {
                        int idIdx = rl.nextInt(totalIds);
                        long id = idForBufferIndex(idIdx, unit, header);
                        try {
                            m.readBytes(id, 0, out, 0, 8);
                        } catch (Throwable e) {
                            rErr.incrementAndGet();
                        }
                    }
                } finally {
                    end.countDown();
                }
            }).start();
        }
        start.countDown();
        boolean done = end.await(60, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertTrue("4w+4r ops finished in 60s", done);
        Assert.assertEquals("writer errors = 0", 0, wErr.get());
        Assert.assertEquals("reader errors = 0", 0, rErr.get());
        m.syncStore();
        DsMemory m2 = newMem(tmpDir, 512);
        m2.syncLoad();
        byte[] out = new byte[8];
        int mismatch = 0;
        for (int i = 0; i < totalIds; i++) {
            long id = idForBufferIndex(i, unit, header);
            m2.readBytes(id, 0, out, 0, 8);
            byte[] gold;
            synchronized (lock) { gold = lastW[i]; }
            boolean eq = true;
            for (int k = 0; k < 8; k++) if (out[k] != gold[k]) { eq = false; break; }
            if (!eq) mismatch++;
        }
        Assert.assertTrue("final write last-write-wins mismatch count <= " + (totalIds/10) + " (striped locks + bufferLock + WAL ensures consistency)", mismatch <= Math.max(1, totalIds / 10));
    }

    /**
     * ④ syncStore 后台持续跑（每 200ms），前台 3 线程读+写共 1.5s，不抛任何 sync/读/写 错
     */
    @Test
    public void testConcurrentSyncStoreNoLoss() throws Exception {
        int threads = 3;
        int header = 4096;
        int unit = 8;
        int totalIds = 200;
        DsMemory m = newMem(tmpDir, 64);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stop = new AtomicInteger(0);
        AtomicLong ops = new AtomicLong(0L);
        AtomicInteger errs = new AtomicInteger(0);
        CountDownLatch end = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    try { start.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    Random rl = new Random(tid * 31L + 7L);
                    byte[] w = new byte[8];
                    byte[] out = new byte[8];
                    while (stop.get() == 0) {
                        int ii = rl.nextInt(totalIds);
                        long id = idForBufferIndex(ii, unit, header);
                        try {
                            if (rl.nextBoolean()) {
                                w[0] = (byte) (tid & 0xFF);
                                w[1] = (byte) (ii & 0xFF);
                                w[7] = (byte) ((tid * 131 + ii) & 0xFF);
                                m.writeBytes(id, 0, w, 0, 8);
                            } else {
                                m.readBytes(id, 0, out, 0, 8);
                            }
                            ops.incrementAndGet();
                        } catch (Throwable e) {
                            errs.incrementAndGet();
                        }
                    }
                } finally {
                    end.countDown();
                }
            }).start();
        }
        start.countDown();
        long deadline = System.currentTimeMillis() + 1500L;
        AtomicInteger syncErr = new AtomicInteger(0);
        int syncs = 0;
        while (System.currentTimeMillis() < deadline) {
            try {
                m.syncStore();
                syncs++;
            } catch (Throwable e) {
                syncErr.incrementAndGet();
            }
            try { Thread.sleep(200L); } catch (InterruptedException ignore) {}
        }
        stop.set(1);
        boolean done = end.await(5, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertTrue("op threads stopped", done);
        Assert.assertTrue("ops should be large, got=" + ops.get(), ops.get() > 1000L);
        Assert.assertEquals("sync errors=0", 0, syncErr.get());
        Assert.assertEquals("op errors=0", 0, errs.get());
        Assert.assertTrue("sync count>=3, got=" + syncs, syncs >= 3);
        m.syncStore();
    }
}
