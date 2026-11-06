package com.q3lives.ds.backup;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * binlog 位点热备、增量备份与时间线恢复回归测试。
 */
public class DsBinlogReplayTest {

    private File sourceRoot;
    private File targetRoot;
    private DsDatabaseLocal db;

    @DsTable(mvcc = true)
    public static class BrUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "score", length = 8, indexed = true)
        public long score;
        public BrUser() {}
    }

    @DsTable(mvcc = false)
    public static class BrUserFlat extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "score", length = 8, indexed = true)
        public long score;
        public BrUserFlat() {}
    }

    @Before
    public void beforeEach() throws Exception {
        sourceRoot = Files.createTempDirectory("dsdb_binlog_src_").toFile();
        targetRoot = Files.createTempDirectory("dsdb_binlog_tgt_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(sourceRoot);
        db = new DsDatabaseLocal(sourceRoot, 1);
    }

    @After
    public void afterEach() throws Exception {
        try { if (db != null) db.close(); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetAllIndexesForTest(sourceRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(sourceRoot); } catch (Exception ignore) {}
        deleteRecursive(sourceRoot);
        deleteRecursive(targetRoot);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static BrUser u(String name, int age, long score) {
        BrUser u = new BrUser();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    @Test
    public void testGetCurrentBinlogPositionAdvances() throws Exception {
        DsBinlogPosition p0 = db.getCurrentBinlogPosition();
        Assert.assertNotNull(p0);
        Assert.assertEquals("empty db fixed offset should be 0", 0L, p0.fixedOffset());
        Assert.assertEquals("empty db sequence should be 0", 0L, p0.sequence());

        long aliceId = db.putEntity(u("alice", 25, 100L), true);
        DsBinlogPosition p1 = db.getCurrentBinlogPosition();
        Assert.assertTrue("fixed offset should advance after insert", p1.fixedOffset() > p0.fixedOffset());
        Assert.assertTrue("sequence should advance after insert", p1.sequence() > p0.sequence());

        BrUser updated = db.getTable(BrUser.class, aliceId);
        Assert.assertNotNull(updated);
        updated.age = 26;
        db.putEntity(updated, false);
        DsBinlogPosition p2 = db.getCurrentBinlogPosition();
        Assert.assertTrue("fixed offset should advance after update", p2.fixedOffset() > p1.fixedOffset());
        Assert.assertTrue("sequence should advance after update", p2.sequence() > p1.sequence());
    }

    @Test
    public void testReplayBinlogReconstructsState() throws Exception {
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();

        List<BrUser> initial = Arrays.asList(u("alice", 25, 100L), u("bob", 30, 200L), u("carol", 25, 300L));
        List<Long> ids = db.putEntities(initial, true);
        Assert.assertEquals(3, ids.size());

        BrUser alice = db.getTable(BrUser.class, ids.get(0));
        alice.age = 26;
        db.putEntity(alice, false);

        db.removeTable(BrUser.class, ids.get(2), false);

        BrUser dave = u("dave", 40, 400L);
        long daveId = db.putEntity(dave, true);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        Assert.assertTrue(targetPos.fixedOffset() > basePos.fixedOffset());

        db.close();
        db = null;

        // 将源库的 binlog 文件复制到空目标库后回放
        copyBinlogDirectory(sourceRoot, targetRoot);

        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUser.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos);
            Assert.assertTrue("should replay binlog events", replayed > 0);

            BrUser aliceRestored = targetDb.getTable(BrUser.class, ids.get(0));
            Assert.assertNotNull(aliceRestored);
            Assert.assertEquals("alice", aliceRestored.name);
            Assert.assertEquals(26, aliceRestored.age);
            Assert.assertEquals(100L, aliceRestored.score);

            BrUser bobRestored = targetDb.getTable(BrUser.class, ids.get(1));
            Assert.assertNotNull(bobRestored);
            Assert.assertEquals("bob", bobRestored.name);
            Assert.assertEquals(30, bobRestored.age);
            Assert.assertEquals(200L, bobRestored.score);

            Assert.assertNull("carol should be deleted", targetDb.getTable(BrUser.class, ids.get(2)));

            BrUser daveRestored = targetDb.getTable(BrUser.class, daveId);
            Assert.assertNotNull(daveRestored);
            Assert.assertEquals("dave", daveRestored.name);
            Assert.assertEquals(40, daveRestored.age);
            Assert.assertEquals(400L, daveRestored.score);
        }
    }

    @Test
    public void testEndToEndHotBackupViaTimelineRestore() throws Exception {
        // 基线数据
        List<BrUser> initial = Arrays.asList(u("alice", 25, 100L), u("bob", 30, 200L), u("carol", 25, 300L));
        List<Long> ids = db.putEntities(initial, true);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();

        // 先关闭源库确保数据文件落盘，再创建 snapshot 并记录 binlog 水位
        db.close();
        db = null;
        File snapshotRoot = new File(sourceRoot, "backups/snapshots");
        File snapshotDir = DsFullBackup.createSnapshot(sourceRoot, snapshotRoot, basePos);
        DsFullBackup.validate(snapshotDir);
        assertManifestBinlogPosition(snapshotDir, basePos);

        // 重新打开源库继续写入（验证 binlog 追加与水位推进）
        db = new DsDatabaseLocal(sourceRoot, 1);
        BrUser alice = db.getTable(BrUser.class, ids.get(0));
        alice.age = 26;
        db.putEntity(alice, false);
        db.removeTable(BrUser.class, ids.get(2), false);
        BrUser dave = u("dave", 40, 400L);
        long daveId = db.putEntity(dave, true);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        Assert.assertTrue(targetPos.fixedOffset() > basePos.fixedOffset());

        db.close();
        db = null;

        // 增量备份：只捕获水位 basePos 之后的 binlog 文件
        File incrRoot = new File(sourceRoot, "backups/incr");
        File incrDir = DsIncrementalBackup.create(sourceRoot, incrRoot, snapshotDir, targetPos);
        DsIncrementalBackup.validate(incrDir);
        assertManifestBinlogPosition(incrDir, targetPos);

        // 时间线恢复：先还原基线与增量 binlog 文件，再由调用方重放到目标水位
        List<File> chain = Arrays.asList(snapshotDir, incrDir);
        DsTimelineRestore.restoreTimeline(chain, targetRoot);

        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUser.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos);
            Assert.assertTrue("should replay binlog events", replayed > 0);

            BrUser aliceRestored = targetDb.getTable(BrUser.class, ids.get(0));
            Assert.assertNotNull(aliceRestored);
            Assert.assertEquals(26, aliceRestored.age);

            BrUser bobRestored = targetDb.getTable(BrUser.class, ids.get(1));
            Assert.assertNotNull(bobRestored);
            Assert.assertEquals(30, bobRestored.age);

            Assert.assertNull("carol should be deleted", targetDb.getTable(BrUser.class, ids.get(2)));

            BrUser daveRestored = targetDb.getTable(BrUser.class, daveId);
            Assert.assertNotNull(daveRestored);
            Assert.assertEquals(40, daveRestored.age);
        }
    }

    private static BrUserFlat uf(String name, int age, long score) {
        BrUserFlat u = new BrUserFlat();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    @Test
    public void testBatchReplayAggregatesInsertsAndUpdates() throws Exception {
        db.registerTable(BrUser.class);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();

        List<BrUser> initial = Arrays.asList(
                u("alice", 25, 100L), u("bob", 30, 200L), u("carol", 25, 300L),
                u("dave", 40, 400L), u("eve", 35, 500L));
        List<Long> ids = db.putEntities(initial, true);

        BrUser alice = db.getTable(BrUser.class, ids.get(0));
        alice.age = 26;
        db.putEntity(alice, false);

        db.removeTable(BrUser.class, ids.get(2), false);

        BrUser frank = u("frank", 28, 600L);
        long frankId = db.putEntity(frank, true);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        db.close();
        db = null;

        copyBinlogDirectory(sourceRoot, targetRoot);
        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUser.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos, 32);
            Assert.assertTrue("should replay binlog events", replayed > 0);

            BrUser aliceRestored = targetDb.getTable(BrUser.class, ids.get(0));
            Assert.assertNotNull(aliceRestored);
            Assert.assertEquals("alice", aliceRestored.name);
            Assert.assertEquals(26, aliceRestored.age);

            Assert.assertNull("carol should be deleted", targetDb.getTable(BrUser.class, ids.get(2)));

            BrUser frankRestored = targetDb.getTable(BrUser.class, frankId);
            Assert.assertNotNull(frankRestored);
            Assert.assertEquals("frank", frankRestored.name);
            Assert.assertEquals(600L, frankRestored.score);
        }
    }

    @Test
    public void testParallelBatchReplay() throws Exception {
        db.registerTable(BrUser.class);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();

        List<BrUser> initial = Arrays.asList(
                u("alice", 25, 100L), u("bob", 30, 200L), u("carol", 25, 300L),
                u("dave", 40, 400L), u("eve", 35, 500L));
        List<Long> ids = db.putEntities(initial, true);

        BrUser bob = db.getTable(BrUser.class, ids.get(1));
        bob.score = 250L;
        db.putEntity(bob, false);

        db.removeTable(BrUser.class, ids.get(3), false);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        db.close();
        db = null;

        copyBinlogDirectory(sourceRoot, targetRoot);
        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUser.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos, 2, 4);
            Assert.assertTrue("should replay binlog events", replayed > 0);

            BrUser bobRestored = targetDb.getTable(BrUser.class, ids.get(1));
            Assert.assertNotNull(bobRestored);
            Assert.assertEquals(250L, bobRestored.score);

            Assert.assertNull("dave should be deleted", targetDb.getTable(BrUser.class, ids.get(3)));

            BrUser eveRestored = targetDb.getTable(BrUser.class, ids.get(4));
            Assert.assertNotNull(eveRestored);
            Assert.assertEquals(35, eveRestored.age);
        }
    }

    @Test
    public void testCrossDayRotateReplay() throws Exception {
        db.registerTable(BrUserFlat.class);
        db.rotateBinlog("20260101");
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();

        List<BrUserFlat> day1 = Arrays.asList(uf("alice", 25, 100L), uf("bob", 30, 200L));
        List<Long> ids = db.putEntities(day1, true);

        db.rotateBinlog("20260102");
        BrUserFlat carol = uf("carol", 25, 300L);
        long carolId = db.putEntity(carol, true);

        BrUserFlat alice = db.getTable(BrUserFlat.class, ids.get(0));
        alice.age = 26;
        db.putEntity(alice, false);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        Assert.assertEquals("targetPos should be on next day", "20260102", targetPos.dayKey());
        Assert.assertTrue("targetPos should advance", targetPos.fixedOffset() > 0);

        db.close();
        db = null;

        copyBinlogDirectory(sourceRoot, targetRoot);
        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUserFlat.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos, 32);
            Assert.assertTrue("should replay across day boundaries", replayed > 0);

            BrUserFlat aliceRestored = targetDb.getTable(BrUserFlat.class, ids.get(0));
            Assert.assertNotNull(aliceRestored);
            Assert.assertEquals(26, aliceRestored.age);

            BrUserFlat bobRestored = targetDb.getTable(BrUserFlat.class, ids.get(1));
            Assert.assertNotNull(bobRestored);
            Assert.assertEquals(30, bobRestored.age);

            BrUserFlat carolRestored = targetDb.getTable(BrUserFlat.class, carolId);
            Assert.assertNotNull(carolRestored);
            Assert.assertEquals(25, carolRestored.age);
        }
    }

    @Test
    public void testReplayWithMissingStartDay() throws Exception {
        db.registerTable(BrUserFlat.class);
        db.rotateBinlog("20260102");
        DsBinlogPosition basePos = new DsBinlogPosition("20260101", 0L, 0L);

        List<BrUserFlat> rows = Arrays.asList(uf("alice", 25, 100L), uf("bob", 30, 200L));
        List<Long> ids = db.putEntities(rows, true);

        DsBinlogPosition targetPos = db.getCurrentBinlogPosition();
        db.close();
        db = null;

        copyBinlogDirectory(sourceRoot, targetRoot);
        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(BrUserFlat.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos, 32);
            Assert.assertTrue("should replay even when start day file is missing", replayed > 0);

            Assert.assertNotNull(targetDb.getTable(BrUserFlat.class, ids.get(0)));
            Assert.assertNotNull(targetDb.getTable(BrUserFlat.class, ids.get(1)));
        }
    }

    private static void copyBinlogDirectory(File srcRoot, File dstRoot) throws Exception {
        File srcBinlog = new File(srcRoot, "_binlog");
        if (!srcBinlog.isDirectory()) return;
        File dstBinlog = new File(dstRoot, "_binlog");
        dstBinlog.mkdirs();
        Files.walk(srcBinlog.toPath()).forEach(path -> {
            try {
                File src = path.toFile();
                File dst = new File(dstBinlog, srcBinlog.toPath().relativize(path).toString());
                if (src.isDirectory()) {
                    dst.mkdirs();
                } else {
                    dst.getParentFile().mkdirs();
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void assertManifestBinlogPosition(File backupDir, DsBinlogPosition expected) throws Exception {
        Map<String, Object> manifest = DsFullBackup.readYaml(new File(backupDir, "manifest.yaml"));
        Map<String, Object> backup = (Map<String, Object>) manifest.get("backup");
        Map<String, Object> bp = (Map<String, Object>) backup.get("binlogPosition");
        Assert.assertNotNull("binlogPosition missing in manifest", bp);
        Assert.assertEquals(expected.dayKey(), bp.get("dayKey"));
        Assert.assertEquals(expected.fixedOffset(), ((Number) bp.get("fixedOffset")).longValue());
        Assert.assertEquals(expected.sequence(), ((Number) bp.get("sequence")).longValue());
    }
}
