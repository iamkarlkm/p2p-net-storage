package com.q3lives.ds.binlog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * binlog redo/undo 日志级别单元测试（聚焦 DsBinlogStore 帧标志与序列号语义）。
 *
 * <p>注意：UNDO 帧的“默认跳过”在 DsDatabaseLocal 回放层实现；本测试类只验证
 * DsBinlogStore 能正确写入/识别 UNDO 标志，且 FULL 级别下 UNDO seq &lt; REDO seq。
 */
public class DsBinlogLogLevelTest {

    private Path tmpDir;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("dsbinlog_loglevel_test_");
    }

    @After
    public void tearDown() throws Exception {
        DsBinlogStore.forceResetForTest(tmpDir.toFile());
        Files.walk(tmpDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(f -> { try { if (!f.delete() && f.exists()) f.deleteOnExit(); } catch (Throwable ignore) {} });
    }

    @Test
    public void testRedoLevelNoUndoFlag() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            long o = store.append(DsBinlogOpType.UPDATE, 1, 1L, 1, 100,
                    new int[]{0}, new Object[]{(short) 1}, DsBinlogLogLevel.REDO);
            assertEquals(0L, o);
            store.forceFlushAll();

            List<DsBinlogEntry> list = readAllRaw(store);
            assertEquals(1, list.size());
            assertFalse("REDO 帧不应带 UNDO 标志", list.get(0).isUndo());
            assertEquals(1, ((Number) list.get(0).decodeCol(0, null).decoded).intValue());
        }
    }

    @Test
    public void testUndoLevelHasUndoFlag() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            long o = store.append(DsBinlogOpType.UPDATE, 1, 1L, 1, 100,
                    new int[]{0}, new Object[]{(short) 1}, DsBinlogLogLevel.UNDO);
            assertEquals(0L, o);
            store.forceFlushAll();

            List<DsBinlogEntry> list = readAllRaw(store);
            assertEquals(1, list.size());
            assertTrue("UNDO 帧应带 UNDO 标志", list.get(0).isUndo());
            assertEquals(DsBinlogOpType.UPDATE, list.get(0).opType());
            assertEquals(1, ((Number) list.get(0).decodeCol(0, null).decoded).intValue());
        }
    }

    @Test
    public void testFullLevelWritesUndoThenRedo() throws Exception {
        try (DsBinlogStore store = new DsBinlogStore(tmpDir.toFile(), false)) {
            // FULL 需调用方自行先写 UNDO 再写 REDO；这里模拟 UPDATE 的 before/after image
            long o1 = store.append(DsBinlogOpType.UPDATE, 1, 1L, 1, 100,
                    new int[]{0}, new Object[]{(short) 10}, DsBinlogLogLevel.UNDO);
            long o2 = store.append(DsBinlogOpType.UPDATE, 1, 2L, 1, 100,
                    new int[]{0}, new Object[]{(short) 20}, DsBinlogLogLevel.REDO);
            assertEquals(0L, o1);
            assertEquals(256L, o2);
            store.forceFlushAll();

            List<DsBinlogEntry> list = readAllRaw(store);
            assertEquals(2, list.size());
            assertTrue("第一帧应为 UNDO", list.get(0).isUndo());
            assertEquals(1L, list.get(0).sequence());
            assertEquals(10, ((Number) list.get(0).decodeCol(0, null).decoded).intValue());
            assertFalse("第二帧应为 REDO", list.get(1).isUndo());
            assertEquals(2L, list.get(1).sequence());
            assertEquals(20, ((Number) list.get(1).decodeCol(0, null).decoded).intValue());
        }
    }

    @Test
    public void testFlagBitValue() {
        // 保证 FLAG_BIT_UNDO_LOG 不与其他位冲突
        assertEquals(1 << 4, DsBinlogLayout.FLAG_BIT_UNDO_LOG);
        assertEquals(0, DsBinlogLayout.FLAG_BIT_UNDO_LOG & DsBinlogLayout.FLAG_BIT_TOMBSTONE);
        assertEquals(0, DsBinlogLayout.FLAG_BIT_UNDO_LOG & DsBinlogLayout.FLAG_BIT_HAS_DYN);
        assertEquals(0, DsBinlogLayout.FLAG_BIT_UNDO_LOG & DsBinlogLayout.FLAG_BIT_COMMIT_DURABLE);
        assertEquals(0, DsBinlogLayout.FLAG_BIT_UNDO_LOG & DsBinlogLayout.FLAG_BIT_REPLAY_DURABLE);
    }

    private List<DsBinlogEntry> readAllRaw(DsBinlogStore store) throws Exception {
        List<DsBinlogEntry> list = new ArrayList<>();
        for (String dayKey : store.listDayKeys()) {
            try (DsBinlogReplayer r = new DsBinlogReplayer(new File(tmpDir.toFile(), "_binlog"), dayKey)) {
                r.replayFrom(0L, (off, e) -> { list.add(e); return true; });
            }
        }
        return list;
    }
}
