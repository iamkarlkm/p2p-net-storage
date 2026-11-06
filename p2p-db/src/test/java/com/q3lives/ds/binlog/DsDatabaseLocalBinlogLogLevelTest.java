package com.q3lives.ds.binlog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * DsDatabaseLocal 表级 binlog 日志级别集成测试。
 *
 * <p>核心验证方式：写入后关闭并重新打开数据库（触发 recoverFromBinlog），
 * 若 UNDO 帧被错误地正向应用，则最终实体值会回退到旧值；正确行为应保留 REDO 后的新值。
 */
public class DsDatabaseLocalBinlogLogLevelTest {

    private File tmpRoot;
    private String oldHome;
    private DsDatabaseLocal db;

    @DsTable(name = "redo_user", binlogLogLevel = DsBinlogLogLevel.REDO)
    public static class RedoUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public RedoUser() {}
    }

    @DsTable(name = "undo_user", binlogLogLevel = DsBinlogLogLevel.UNDO)
    public static class UndoUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public UndoUser() {}
    }

    @DsTable(name = "full_user", binlogLogLevel = DsBinlogLogLevel.FULL)
    public static class FullUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        public FullUser() {}
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_binlog_loglevel_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
        db = new DsDatabaseLocal(tmpRoot);
    }

    @After
    public void after() {
        try { if (db != null) db.close(); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static RedoUser redoUser(String name, int age) {
        RedoUser u = new RedoUser();
        u.name = name; u.age = age;
        return u;
    }

    private static UndoUser undoUser(String name, int age) {
        UndoUser u = new UndoUser();
        u.name = name; u.age = age;
        return u;
    }

    private static FullUser fullUser(String name, int age) {
        FullUser u = new FullUser();
        u.name = name; u.age = age;
        return u;
    }

    /** 与 DsDatabaseLocal.stableTableId 实现保持一致。 */
    private static int testTableId(Class<? extends DsTableAdapter> clazz) {
        long h = 0xcbf29ce484222325L;
        byte[] bytes = clazz.getName().getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= b & 0xFF;
            h *= 0x100000001b3L;
        }
        return (int) (h ^ (h >>> 32));
    }

    @Test
    public void testRedoLevelWritesAfterImage() throws Exception {
        RedoUser u = redoUser("a", 10);
        long id = db.putEntity(u, true);

        int tableId = testTableId(RedoUser.class);
        List<DsBinlogEntry> entries = collectUserEntries(tableId);
        assertEquals("REDO 表只写一帧 REDO", 1, entries.size());
        assertFalse(entries.get(0).isUndo());

        RedoUser loaded = reopenAndGet(RedoUser.class, id);
        assertNotNull(loaded);
        assertEquals(10, loaded.age);
    }

    @Test
    public void testUndoLevelWritesBeforeImageAndSkippedByReplay() throws Exception {
        UndoUser u1 = undoUser("a", 10);
        long id = db.putEntity(u1, true);

        UndoUser u2 = undoUser("a", 20);
        u2.setId(id);
        db.putEntity(u2, true);

        int tableId = testTableId(UndoUser.class);
        List<DsBinlogEntry> raw = collectUserEntries(tableId);
        assertEquals("UNDO 表 INSERT 降级为 REDO，UPDATE 写 UNDO", 2, raw.size());
        assertFalse("INSERT 帧应为 REDO", raw.get(0).isUndo());
        assertTrue("UPDATE 帧应为旧值（undo 标志）", raw.get(1).isUndo());

        // 重新打开并回放：UNDO 帧被跳过，最终状态保持新值 20
        UndoUser loaded = reopenAndGet(UndoUser.class, id);
        assertNotNull(loaded);
        assertEquals(20, loaded.age);
    }

    @Test
    public void testFullLevelUpdateWritesUndoThenRedo() throws Exception {
        FullUser u1 = fullUser("a", 10);
        long id = db.putEntity(u1, true);

        FullUser u2 = fullUser("a", 20);
        u2.setId(id);
        db.putEntity(u2, true);

        int tableId = testTableId(FullUser.class);
        List<DsBinlogEntry> raw = collectUserEntries(tableId);
        assertEquals("FULL 表 INSERT 写 1 帧 REDO，UPDATE 写 UNDO+REDO 两帧", 3, raw.size());
        assertFalse("INSERT 帧应为 REDO", raw.get(0).isUndo());
        assertTrue("UPDATE UNDO 帧应为旧值", raw.get(1).isUndo());
        assertFalse("UPDATE REDO 帧应为新值", raw.get(2).isUndo());
        assertTrue("UNDO seq < REDO seq", raw.get(1).sequence() < raw.get(2).sequence());

        // 重新打开并回放：UNDO 帧被跳过，最终状态应为新值 20
        FullUser loaded = reopenAndGet(FullUser.class, id);
        assertNotNull(loaded);
        assertEquals(20, loaded.age);
    }

    private List<DsBinlogEntry> collectUserEntries(int tableId) throws Exception {
        List<DsBinlogEntry> list = new ArrayList<>();
        for (String dayKey : db.getBinlogStore().listDayKeys()) {
            try (DsBinlogReplayer r = new DsBinlogReplayer(new File(tmpRoot, "_binlog"), dayKey)) {
                r.replayFrom(0L, (off, e) -> {
                    if (e.tableId() == tableId) list.add(e);
                    return true;
                });
            }
        }
        return list;
    }

    private <T extends DsTableAdapter> T reopenAndGet(Class<T> clazz, long id) throws Exception {
        db.close();
        db = new DsDatabaseLocal(tmpRoot);
        return db.getTable(clazz, id);
    }
}
