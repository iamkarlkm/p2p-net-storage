package com.q3lives.ds.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * §四 row#2/3/7 DsMemory 纯代码 bug 专项复现 + 闭环单测（3 tests）：
 *  1. testWriteBytesPutNotGet    : writeBytes(id,offset,value,offsetIn,count) 原来是 buf.get(...) 反向读，实际写入完全不生效
 *  2. testLoadIntOffsetAbsolute  : protected int loadIntOffset(long position) 原 buf.getInt((int)position) 把 absolute position 当成 buffer 内 index，越块会 IndexOutOfBounds 或读错
 *  3. testReadBytesCrossPageTail : readBytes 跨块时 for(int i=buf.remaining();i>=LONG_SIZE;i-=LONG_SIZE) 第一页末尾剩余 < LONG_SIZE(8) 时直接不读，尾部 count - rest 字节全部丢
 */
public class DsMemoryBugFixTest {

    private File tempDir;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("dsmemory-bugfix-").toFile();
    }

    @After
    public void tearDown() {
        if (tempDir != null && tempDir.exists()) {
            File[] fs = tempDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            tempDir.delete();
        }
    }

    private DsMemory open(int headerSize, int dataUnitSize) {
        return new DsMemory(new File(tempDir, "bugfix-test.bin"), headerSize, dataUnitSize);
    }

    @Test
    public void testWriteBytesPutNotGet() throws Exception {
        DsMemory mem = open(64, 256);
        try {
            long id = 7L;
            int N = 20;
            byte[] gold = new byte[N];
            for (int i = 0; i < N; i++) gold[i] = (byte) (0xAB + i);
            mem.writeBytes(id, 0, gold);
            byte[] out = new byte[N];
            mem.readBytes(id, 0, out);
            Assert.assertArrayEquals("writeBytes 写 20 字节 readBytes 回来必须 100% 相等", gold, out);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void testLoadIntOffsetAbsolute() throws Exception {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            long pos = 2L * block + 128L; // 绝对位置超过 2 blocks，保证 position%BLOCK_SIZE != position
            int gold = 0xDEADBEEF;
            // store long[] 形式存进去
            mem.storeIntOffset(pos, gold);
            int got = mem.loadIntOffset(pos);
            Assert.assertEquals("loadIntOffset(position) 跨块时必须按块内 offset 读，不得用 absolute position 当 index", gold, got);
        } finally {
            mem.syncStore();
        }
    }

    @Test
    public void testReadBytesCrossPageTail() throws Exception {
        DsMemory mem = open(64, 16);
        try {
            int block = 64 * 1024;
            long id = 3L;
            // 让 id + offset 刚落在块尾剩余 < 8 bytes，触发 readBytes 的 for(i>=LONG_SIZE) 不进入分支
            // absolute position = id*16 + 64 + offset，尽量让 absolute % BLOCK_SIZE 接近 BLOCK_SIZE
            long absoluteTail = (1L * block) - 3L;   // last 3 bytes of block0
            // 通过 id + offset 凑到 absoluteTail
            // 找 id/offset : headerSize + id*dataUnitSize + offset = absoluteTail → 16*id + 64 + offset = 65536 - 3
            int dataUnit = 16;
            int headerSize = 64;
            int wantOffset = dataUnit - 1;
            long id2 = (absoluteTail - headerSize - wantOffset) / dataUnit;
            if (id2 < 0) id2 = 0L;
            // 更简单：直接用 writeBytes/readBytes 足够多数据，跨过一页末尾，尾部必然有 < 8 字节残留
            long idX = 0L;
            int N = block * 2 + 17;   // 2 full blocks + 17 bytes tail
            byte[] gold = new byte[N];
            for (int i = 0; i < N; i++) gold[i] = (byte) (i & 0xFF);
            mem.writeBytes(idX, 0, gold);
            byte[] out = new byte[N];
            mem.readBytes(idX, 0, out);
            int firstDiff = -1;
            for (int i = 0; i < N; i++) if (out[i] != gold[i]) { firstDiff = i; break; }
            Assert.assertEquals("跨块 readBytes N=" + N + " 字节 100% 对齐，firstDiff=-1", -1, firstDiff);
            Assert.assertTrue("Arrays.equals 跨块 tail 校验", Arrays.equals(gold, out));
        } finally {
            mem.syncStore();
        }
    }
}
