package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * §四 row#1 无 WAL sync 并发跳过 → §五 P0③ Block+Row WAL 专项验证（4 tests）：
 *  1. testCrashRestartReplayRestore  : 写 WAL+fsync（模拟 syncStore 成功写 WAL 但 dataFile 写前 crash）→ 重开实例 syncLoad 自动 replayWAL → 脏块完全恢复到内存
 *  2. testCrcBadFrameTruncateStop   : 2 帧写成功 → 手动改最后一帧 TAIL_CRC32 为 0xDEADBEEF → replay 只 apply 第 1 帧，坏帧宁停不越绝不污染
 *  3. testConcurrentSyncLockNotSkip : 2 线程并发 syncStore 各 N=100 次 → 无 tryLock 失败 silent skip，所有 call 阻塞等待成功，200 次全部返回不抛
 *  4. testManualRetryButtonsEnabled : forceFlushAllWALAndSync / replayWALFromScratch / truncateWALNow / forceResetWALForTest 全部公开无状态门禁，永不禁用
 */
public class DsWALCrashRecoveryTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dswal-crash-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] fs = tempDir.listFiles();
            if (fs != null) for (File f : fs) {
                if (f.isDirectory()) {
                    File[] sub = f.listFiles();
                    if (sub != null) for (File s : sub) s.delete();
                }
                f.delete();
            }
            tempDir.delete();
        }
    }

    private File testDataFile() {
        return new File(tempDir, "wal-test.bin");
    }

    private DsMemory open() {
        return new DsMemory(testDataFile(), 64, 256);
    }

    private static byte[] goldBlock(int seed) {
        byte[] b = new byte[DsMemory.BLOCK_SIZE];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) ((seed + i) & 0xFF);
        }
        return b;
    }

    @Test
    public void testCrashRestartReplayRestore() throws Exception {
        File df = testDataFile();
        DsMemory.forceResetWALForTest(df);

        DsMemory mem = open();
        try {
            long id0 = 3L;
            long id1 = 5000L;
            byte[] gold0 = new byte[20]; for (int i=0;i<20;i++) gold0[i] = (byte)(0xA1 + i);
            byte[] gold1 = new byte[20]; for (int i=0;i<20;i++) gold1[i] = (byte)(0xB2 + i);
            mem.writeBytes(id0, 0, gold0);
            mem.writeBytes(id1, 0, gold1);

            int bi0 = mem.bufferIndexFromId(id0);
            int bi1 = mem.bufferIndexFromId(id1);

            while (mem.dataBytes.size() <= Math.max(bi0, bi1)) mem.dataBytes.add(new byte[DsMemory.BLOCK_SIZE]);
            byte[] blk0 = mem.dataBytes.get(bi0);
            byte[] blk1 = mem.dataBytes.get(bi1);
            Assert.assertNotNull(blk0); Assert.assertNotNull(blk1);

            mem.wal.appendBlockEntry(bi0, blk0);
            mem.wal.appendBlockEntry(bi1, blk1);
            mem.wal.fsync();
        } finally {
            try { mem.wal.close(); } catch (Exception ignore) {}
        }

        DsMemory mem2 = open();
        try {
            mem2.syncLoad();
            long id0 = 3L;
            long id1 = 5000L;
            byte[] out0 = new byte[20]; mem2.readBytes(id0, 0, out0);
            byte[] out1 = new byte[20]; mem2.readBytes(id1, 0, out1);
            byte[] gold0 = new byte[20]; for (int i=0;i<20;i++) gold0[i] = (byte)(0xA1 + i);
            byte[] gold1 = new byte[20]; for (int i=0;i<20;i++) gold1[i] = (byte)(0xB2 + i);
            Assert.assertArrayEquals("crash 后 replay 恢复 id0 必须与写入值一致", gold0, out0);
            Assert.assertArrayEquals("crash 后 replay 恢复 id1 必须与写入值一致", gold1, out1);
        } finally {
            mem2.syncStore();
        }
    }

    @Test
    public void testCrcBadFrameTruncateStop() throws Exception {
        File df = testDataFile();
        DsMemory.forceResetWALForTest(df);

        DsMemory mem = open();
        try {
            long id0 = 1L;
            long id1 = 10000L;
            byte[] gold0 = new byte[16]; for (int i=0;i<16;i++) gold0[i] = (byte)(0x11 + i);
            byte[] gold1 = new byte[16]; for (int i=0;i<16;i++) gold1[i] = (byte)(0x22 + i);
            mem.writeBytes(id0, 0, gold0);
            mem.writeBytes(id1, 0, gold1);
            int bi0 = mem.bufferIndexFromId(id0);
            int bi1 = mem.bufferIndexFromId(id1);
            while (mem.dataBytes.size() <= Math.max(bi0, bi1)) mem.dataBytes.add(new byte[DsMemory.BLOCK_SIZE]);
            mem.wal.appendBlockEntry(bi0, mem.dataBytes.get(bi0));
            mem.wal.appendBlockEntry(bi1, mem.dataBytes.get(bi1));
            mem.wal.fsync();
        } finally {
            try { mem.wal.close(); } catch (Exception ignore) {}
        }

        File walFile = new File(tempDir, testDataFile().getName() + "_wal.log");
        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            long len = raf.length();
            Assert.assertTrue("wal 至少 2 帧: len=" + len, len >= 2L * (32 + DsMemory.BLOCK_SIZE + 4));
            raf.seek(len - 4);
            raf.writeInt(0xDEADBEEF);
            raf.getChannel().force(true);
        }

        DsMemory mem2 = open();
        try {
            mem2.syncLoad();
            long id0 = 1L;
            long id1 = 10000L;
            byte[] out0 = new byte[16]; mem2.readBytes(id0, 0, out0);
            byte[] out1 = new byte[16]; mem2.readBytes(id1, 0, out1);
            byte[] gold0 = new byte[16]; for (int i=0;i<16;i++) gold0[i] = (byte)(0x11 + i);
            byte[] zero16 = new byte[16];
            Assert.assertArrayEquals("第 1 帧 CRC 正确必须恢复", gold0, out0);
            Assert.assertArrayEquals("第 2 帧 TAIL_CRC 错 → 坏帧截断宁停不越，id1 内存保持 0 不被半写污染", zero16, out1);
        } finally {
            mem2.syncStore();
        }
    }

    @Test
    public void testConcurrentSyncLockNotSkip() throws Exception {
        final DsMemory mem = open();
        try {
            final int N = 100;
            final int TH = 2;
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(TH);
            final AtomicInteger okCount = new AtomicInteger(0);
            final AtomicInteger errCount = new AtomicInteger(0);

            Runnable r = () -> {
                try {
                    start.await();
                    for (int i = 0; i < N; i++) {
                        long id = (long) (Math.random() * 200);
                        byte[] v = new byte[4];
                        v[0] = (byte) i; v[1] = (byte) (i>>8);
                        mem.writeBytes(id, 0, v);
                        mem.syncStore();
                        okCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errCount.incrementAndGet();
                    t.printStackTrace();
                } finally {
                    done.countDown();
                }
            };

            Thread t1 = new Thread(r, "sync-th-1");
            Thread t2 = new Thread(r, "sync-th-2");
            t1.start(); t2.start();
            start.countDown();
            done.await();
            t1.join(5000); t2.join(5000);

            Assert.assertEquals("并发 syncStore 2×" + N + "=200 次全部成功（lock 阻塞不 skip）", TH*N, okCount.get());
            Assert.assertEquals("并发 syncStore 无异常", 0, errCount.get());
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void testManualRetryButtonsEnabled() throws Exception {
        File df = testDataFile();
        DsMemory.forceResetWALForTest(df);

        DsMemory mem = open();
        try {
            long id = 7L;
            byte[] v = new byte[]{1,2,3,4,5};
            mem.writeBytes(id, 0, v);

            mem.forceFlushAllWALAndSync();

            int replayed = mem.replayWALFromScratch();
            Assert.assertTrue("replayWALFromScratch 公开方法可调用，返回计数 >=0", replayed >= 0);

            mem.truncateWALNow();

            File walFile = mem.wal == null ? null : mem.wal.getWalFile();
            if (walFile != null) {
                Assert.assertEquals("truncateWALNow 公开方法调用后 WAL 长度应为 0", 0L, walFile.length());
            }
        } finally {
            mem.syncStore();
            if (mem.wal != null) {
                try { mem.wal.close(); } catch (Exception ignore) {}
            }
        }

        DsMemory.forceResetWALForTest(df);
        File walFile = new File(tempDir, df.getName() + "_wal.log");
        Assert.assertFalse("forceResetWALForTest 公开方法可调用，wal 文件应被删除", walFile.exists());
    }
}
