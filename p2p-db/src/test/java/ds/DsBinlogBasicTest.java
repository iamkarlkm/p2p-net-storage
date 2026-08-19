package ds;

import com.q3lives.ds.binlog.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * DsBinlog 6 场景单测（严格与用户 5 条硬约束对齐）：
 * 1. testBasicPutReplay  固定帧+基本 short/int/long/double/float/byte[] 列写回放对齐
 * 2. testDsBytesRef       DsBytes 动态列索引存储超长 bytes（不重复内容写 binlog dyn）
 * 3. testRolloverRotate   rotate 跨 2 个 dayKey 顺序回放不丢帧
 * 4. testCrcFailTruncate  末尾帧 CRC 手动改 0xDEAD → 回放仅停在上一帧，坏帧永不越过
 * 5. testInReplayNoWrite  回放 handler.apply 内部再调用 append → 返回 -1 不产生新帧
 * 6. testMultiServerId    多 serverId 乱序写入 + 回放按 seq/serverId 分桶正确
 */
public class DsBinlogBasicTest {

    private Path tmpDir;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("dsbinlog_test_");
    }

    @After
    public void tearDown() throws Exception {
        DsBinlogStore.forceResetForTest(tmpDir.toFile());
        DsBinlogContext.clear();
        Files.walk(tmpDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(f -> { try { if (!f.delete() && f.exists()) f.deleteOnExit(); } catch (Throwable ignore) {} });
    }

    @Test
    public void testBasicPutReplay() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            int serverId = 101;
            int tableId = 7;
            int[] cols = { 1, 3, 10, 511 };
            Object[] vals = new Object[] {
                    (short) 16383,  // canInlineShort 上限 = 16383 (15-bit signed max) → slot inline 走 bit15
                    1_000_000,   // Integer, 超过 Short.MAX，落 dyn 区 DVT_INT
                    1L << 40,    // Long 超过 short，落 dyn 区 DVT_LONG
                    new byte[] { 0x11, 0x22, 0x33, 0x44 }
            };
            long o1 = store.append(DsBinlogOpType.INSERT, serverId, 0L, tableId, cols[1], cols, vals);
            assertEquals(0L, o1); // fixed 文件第一帧 = 0

            Object[] v2 = new Object[] { (short) -1, 5, null, null };
            long o2 = store.append(DsBinlogOpType.UPDATE, serverId, 0L, tableId, cols[1], cols, v2);
            assertEquals(256L, o2); // 第二帧 = 1 * 256
            store.forceFlushAll();

            List<DsBinlogEntry> entries = new ArrayList<>();
            int n = store.replayAll((off, e) -> { entries.add(e); return true; });
            assertEquals(2, n);
            assertEquals(2, entries.size());

            DsBinlogEntry e0 = entries.get(0);
            assertEquals(DsBinlogOpType.INSERT, e0.opType());
            assertEquals(serverId, e0.serverId());
            assertEquals(tableId, e0.tableId());
            assertEquals(4, e0.colCount());
            assertArrayEquals(new int[] { 1, 3, 10, 511 }, e0.colIds());
            // slot 0: short inline 16383 (canInlineShort 上限)
            assertEquals(16383, ((Number) e0.decodeCol(0, null).decoded).shortValue());
            // slot 1: int 1_000_000 dyn
            assertEquals(1_000_000, e0.decodeCol(1, null).decoded);
            // slot 2: long 1<<40
            assertEquals(1L << 40, e0.decodeCol(2, null).decoded);
            // slot 3: byte[] {11,22,33,44}
            byte[] b3 = (byte[]) e0.decodeCol(3, null).decoded;
            assertArrayEquals(new byte[] { 0x11, 0x22, 0x33, 0x44 }, b3);

            DsBinlogEntry e1 = entries.get(1);
            assertEquals(DsBinlogOpType.UPDATE, e1.opType());
            assertEquals(-1, ((Number) e1.decodeCol(0, null).decoded).shortValue());
            // col 1: Integer 5 → canInlineInt(5)==true → inline short，故 decoded type = Short，用通用 Number 比较
            assertEquals(5, ((Number) e1.decodeCol(1, null).decoded).intValue());
            // col 2 / col 3 是 null
            assertNull(e1.decodeCol(2, null).decoded);
            assertNull(e1.decodeCol(3, null).decoded);
        }
    }

    @Test
    public void testDsBytesRef() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), true)) {
            DsBytes ds = store.dsBytes();
            assertNotNull(ds);
            byte[] big = new byte[16 * 1024];
            for (int i = 0; i < big.length; i++) big[i] = (byte) (i & 0xFF);
            long idxA = ds.put(big);
            long idxB = ds.put(big); // same content → 同 id + refCount 2
            assertEquals("DsData 内容去重生效", idxA, idxB);
            assertEquals(2, ds.refCount(idxA));

            int serverId = 22;
            int[] cols = { 0, 1 };
            Object[] vals = new Object[] { DsBinlogStore.ofDsBytesIndex(idxA), "hello-string" };
            store.append(DsBinlogOpType.INSERT, serverId, 0L, 1, 0, cols, vals);
            store.append(DsBinlogOpType.INSERT, serverId, 0L, 1, 0, cols, vals); // 再 append 一次相同 ref
            store.forceFlushAll();

            List<DsBinlogEntry> list = new ArrayList<>();
            store.replayAll((o, e) -> { list.add(e); return true; });
            assertEquals(2, list.size());
            for (DsBinlogEntry e : list) {
                Object col0 = e.decodeCol(0, ds).decoded;
                assertNotNull(col0);
                byte[] back = (byte[]) col0;
                assertArrayEquals(big, back);
                // col1: string fallback bytes raw
                byte[] s1b = (byte[]) e.decodeCol(1, null).decoded;
                assertEquals("hello-string", new String(s1b, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void testRolloverRotate() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            int s = 1;
            store.append(DsBinlogOpType.INSERT, s, 1, 1, 0, new int[]{0}, new Object[]{(short)1});
            store.append(DsBinlogOpType.INSERT, s, 2, 1, 0, new int[]{0}, new Object[]{(short)2});
            String d1 = store.currentDayKey();
            store.rotate("20990101");
            String d2 = store.currentDayKey();
            assertEquals("20990101", d2);
            store.append(DsBinlogOpType.UPDATE, s, 3, 1, 0, new int[]{0}, new Object[]{(short)3});
            store.append(DsBinlogOpType.DELETE, s, 4, 1, 0, new int[]{0}, new Object[]{(short)4});
            store.forceFlushAll();

            AtomicInteger total = new AtomicInteger(0);
            AtomicLong seqSum = new AtomicLong(0);
            for (String dk : store.listDayKeys()) {
                int cnt = store.replayFrom(dk, 0, (off, e) -> {
                    seqSum.addAndGet(e.sequence());
                    total.incrementAndGet();
                    return true;
                });
                assertTrue("day " + dk + " 回放数>=1", cnt >= 1);
            }
            assertEquals(4, total.get());
            assertEquals(10L, seqSum.get()); // 1+2+3+4
        }
    }

    @Test
    public void testCrcFailTruncate() throws Exception {
        File root = tmpDir.toFile();
        try (DsBinlogStore store = new DsBinlogStore(root, false)) {
            store.append(DsBinlogOpType.INSERT, 1, 1, 0, 0, new int[]{0}, new Object[]{(short) 10});
            store.append(DsBinlogOpType.INSERT, 1, 2, 0, 0, new int[]{0}, new Object[]{(short) 20});
            store.forceFlushAll();
        }

        // 手动破坏最后一帧的 CRC32 位置（offset = 1*256 + OFF_CRC32 = 268）：写 0xDEADBEEF
        File binDir = new File(root, "_binlog");
        File fixed = new File(binDir, DsBinlogLayout.FILE_PREFIX +
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) +
                DsBinlogLayout.FIXED_SUFFIX);
        assertTrue(fixed.exists());
        try (RandomAccessFile raf = new RandomAccessFile(fixed, "rw")) {
            raf.seek(256 + DsBinlogLayout.OFF_CRC32);
            raf.writeInt(0xDEADBEEF);
        }

        List<DsBinlogEntry> list = new ArrayList<>();
        try (DsBinlogStore reopen = new DsBinlogStore(root, false)) {
            int n = reopen.replayAll((off, e) -> { list.add(e); return true; });
            assertEquals(1, n); // 只应该看到第一帧（CRC 正确），第二帧 CRC fail truncate stop
            assertEquals(1, list.size());
            assertEquals(1L, list.get(0).sequence());
            assertEquals(10, ((Number) list.get(0).decodeCol(0, null).decoded).shortValue());
        }
    }

    @Test
    public void testInReplayNoWrite() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            store.append(DsBinlogOpType.INSERT, 1, 1, 0, 0, new int[]{0}, new Object[]{(short) 1});
            store.append(DsBinlogOpType.INSERT, 1, 2, 0, 0, new int[]{0}, new Object[]{(short) 2});

            AtomicInteger innerOffsetsNonMinus1 = new AtomicInteger(0);
            // handler 里尝试再 append（模拟业务 apply 又触发写 binlog）
            store.replayAll((off, e) -> {
                long innerOff = store.append(DsBinlogOpType.HEARTBEAT, 9, 999, 0, 0,
                        new int[]{0}, new Object[]{(short) 99});
                if (innerOff != -1L) innerOffsetsNonMinus1.incrementAndGet();
                return true;
            });
            assertEquals(0, innerOffsetsNonMinus1.get()); // 回放期间所有 append 都必须返回 -1

            store.forceFlushAll();

            List<DsBinlogEntry> list = new ArrayList<>();
            store.replayAll((off, e) -> { list.add(e); return true; });
            assertEquals(2, list.size()); // 只应是最开始的 2 条，无嵌套的 HEARTBEAT
        }
    }

    @Test
    public void testMultiServerId() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            // s1 写 seq 1/3/5, s2 写 seq 2/4/6 (乱序交叉提交)
            // value = seq*10 + serverId: s1→11/31/51, s2→22/42/62
            store.append(DsBinlogOpType.INSERT, 1, 1, 0, 0, new int[]{0}, new Object[]{(short) (1 * 10 + 1)});
            store.append(DsBinlogOpType.INSERT, 2, 2, 0, 0, new int[]{0}, new Object[]{(short) (2 * 10 + 2)});
            store.append(DsBinlogOpType.UPDATE, 1, 3, 0, 0, new int[]{0}, new Object[]{(short) (3 * 10 + 1)});
            store.append(DsBinlogOpType.UPDATE, 2, 4, 0, 0, new int[]{0}, new Object[]{(short) (4 * 10 + 2)});
            store.append(DsBinlogOpType.DELETE, 1, 5, 0, 0, new int[]{0}, new Object[]{(short) (5 * 10 + 1)});
            store.append(DsBinlogOpType.DELETE, 2, 6, 0, 0, new int[]{0}, new Object[]{(short) (6 * 10 + 2)});
            store.forceFlushAll();

            AtomicLong s1Seq = new AtomicLong(0), s2Seq = new AtomicLong(0);
            AtomicInteger s1Count = new AtomicInteger(0), s2Count = new AtomicInteger(0);
            store.replayAll((off, e) -> {
                short v = ((Number) e.decodeCol(0, null).decoded).shortValue();
                if (e.serverId() == 1) {
                    s1Count.incrementAndGet();
                    s1Seq.addAndGet(e.sequence());
                    assertEquals(e.sequence() * 10 + 1, v);  // seq 1→11,3→33,5→55
                } else if (e.serverId() == 2) {
                    s2Count.incrementAndGet();
                    s2Seq.addAndGet(e.sequence());
                    assertEquals(e.sequence() * 10 + 2, v);  // seq 2→22,4→44,6→66
                }
                return true;
            });
            assertEquals(3, s1Count.get());
            assertEquals(3, s2Count.get());
            assertEquals(9L, s1Seq.get());   // 1+3+5
            assertEquals(12L, s2Seq.get());  // 2+4+6
        }
    }
}
