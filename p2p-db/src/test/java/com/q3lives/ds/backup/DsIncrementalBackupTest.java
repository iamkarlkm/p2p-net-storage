package com.q3lives.ds.backup;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.backup.DsFullBackup.BackupEntry;
import com.q3lives.ds.backup.DsFullBackup.BackupRole;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * binlog-as-WAL 下的 snapshot、增量备份与时间线恢复回归测试。
 */
public class DsIncrementalBackupTest {

    private File sourceRoot;
    private File targetRoot;
    private final List<File> workHomes = new ArrayList<>();

    @Before
    public void beforeEach() throws Exception {
        sourceRoot = Files.createTempDirectory("dsdb_incr_src_").toFile();
        targetRoot = Files.createTempDirectory("dsdb_incr_tgt_").toFile();
        workHomes.clear();
    }

    @After
    public void afterEach() throws Exception {
        deleteRecursive(sourceRoot);
        deleteRecursive(targetRoot);
        for (File wh : workHomes) deleteRecursive(wh);
        workHomes.clear();
    }

    @DsTable(mvcc = false)
    public static class IbUserFlat extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "score", length = 8, indexed = true)
        public long score;
        public IbUserFlat() {}
    }

    private static IbUserFlat u(String name, int age, long score) {
        IbUserFlat u = new IbUserFlat();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    @SuppressWarnings("unchecked")
    private static String readType(File backupDir) throws IOException {
        Map<String, Object> manifest = DsFullBackup.readYaml(new File(backupDir, "manifest.yaml"));
        Map<String, Object> backup = (Map<String, Object>) manifest.get("backup");
        return backup == null ? "" : String.valueOf(backup.get("type"));
    }

    @SuppressWarnings("unchecked")
    private static void assertManifestBinlogPosition(File backupDir, DsBinlogPosition expected) throws IOException {
        Map<String, Object> manifest = DsFullBackup.readYaml(new File(backupDir, "manifest.yaml"));
        Map<String, Object> backup = (Map<String, Object>) manifest.get("backup");
        Map<String, Object> bp = (Map<String, Object>) backup.get("binlogPosition");
        Assert.assertNotNull("binlogPosition missing in manifest", bp);
        Assert.assertEquals(expected.dayKey(), bp.get("dayKey"));
        Assert.assertEquals(expected.fixedOffset(), ((Number) bp.get("fixedOffset")).longValue());
        Assert.assertEquals(expected.sequence(), ((Number) bp.get("sequence")).longValue());
    }

    private static List<BackupEntry> readEntries(File backupDir) throws IOException {
        Map<String, Object> manifest = DsFullBackup.readYaml(new File(backupDir, "manifest.yaml"));
        return DsFullBackup.parseEntries(manifest, backupDir);
    }

    @Test
    public void testSnapshotRecordsBinlogPosition() throws Exception {
        try (DsDatabaseLocal db = new DsDatabaseLocal(sourceRoot, 1)) {
            db.registerTable(IbUserFlat.class);
            db.putEntity(u("alice", 25, 100L), true);
            DsBinlogPosition pos = db.getCurrentBinlogPosition();
            db.close();

            File snapshotRoot = new File(sourceRoot, "backups/snapshots");
            File snapshotDir = DsFullBackup.createSnapshot(sourceRoot, snapshotRoot, pos);
            DsFullBackup.validate(snapshotDir);

            Assert.assertEquals("SNAPSHOT", readType(snapshotDir));
            assertManifestBinlogPosition(snapshotDir, pos);
        }
    }

    @Test
    public void testIncrementalBackupCapturesChangedFiles() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        long aliceId = db.putEntity(u("alice", 25, 100L), true);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();
        db.close();

        File snapshotRoot = new File(sourceRoot, "backups/snapshots");
        File baseDir = DsFullBackup.createSnapshot(sourceRoot, snapshotRoot, basePos);
        DsFullBackup.validate(baseDir);

        db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        long bobId = db.putEntity(u("bob", 30, 200L), true);
        IbUserFlat alice = db.getTable(IbUserFlat.class, aliceId);
        Assert.assertNotNull(alice);
        alice.age = 26;
        db.putEntity(alice, false);
        DsBinlogPosition currentPos = db.getCurrentBinlogPosition();
        db.close();

        File incrRoot = new File(sourceRoot, "backups/incr");
        File incrDir = DsIncrementalBackup.create(sourceRoot, incrRoot, baseDir, currentPos);
        DsIncrementalBackup.validate(incrDir);

        Assert.assertEquals("INCREMENTAL", readType(incrDir));
        Map<String, Object> manifest = DsFullBackup.readYaml(new File(incrDir, "manifest.yaml"));
        @SuppressWarnings("unchecked")
        Map<String, Object> backup = (Map<String, Object>) manifest.get("backup");
        Assert.assertNotNull("parentBackupId missing", backup.get("parentBackupId"));
        assertManifestBinlogPosition(incrDir, currentPos);

        boolean hasFixed = false, hasDyn = false;
        for (BackupEntry e : readEntries(incrDir)) {
            if (e.role == BackupRole.BINLOG_FIXED) hasFixed = true;
            if (e.role == BackupRole.BINLOG_DYN) hasDyn = true;
        }
        Assert.assertTrue("incremental should contain binlog fixed file", hasFixed);
        Assert.assertTrue("incremental should contain binlog dyn file", hasDyn);

        // 时间线恢复：从基线 + 增量恢复到最新位点
        deleteRecursive(targetRoot);
        targetRoot.mkdirs();
        List<File> chain = Arrays.asList(baseDir, incrDir);
        DsTimelineRestore.restoreTimeline(chain, targetRoot);

        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(targetRoot, 1)) {
            targetDb.registerTable(IbUserFlat.class);
            int replayed = targetDb.replayBinlog(basePos, currentPos);
            Assert.assertTrue("should replay binlog events", replayed > 0);

            IbUserFlat aliceRestored = targetDb.getTable(IbUserFlat.class, aliceId);
            Assert.assertNotNull(aliceRestored);
            Assert.assertEquals(26, aliceRestored.age);

            IbUserFlat bobRestored = targetDb.getTable(IbUserFlat.class, bobId);
            Assert.assertNotNull(bobRestored);
            Assert.assertEquals(30, bobRestored.age);
        }
    }

    @Test
    public void testIncrementalBackupSkipsOldBinlogByDayKey() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        db.rotateBinlog("20260101");
        db.putEntity(u("alice", 25, 100L), true);
        db.rotateBinlog("20260102");
        db.putEntity(u("bob", 30, 200L), true);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();
        db.close();

        File snapshotRoot = new File(sourceRoot, "backups/snapshots");
        File baseDir = DsFullBackup.createSnapshot(sourceRoot, snapshotRoot, basePos);
        DsFullBackup.validate(baseDir);

        db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        db.rotateBinlog("20260103");
        db.putEntity(u("carol", 35, 300L), true);
        DsBinlogPosition currentPos = db.getCurrentBinlogPosition();
        db.close();

        File incrRoot = new File(sourceRoot, "backups/incr");
        File incrDir = DsIncrementalBackup.create(sourceRoot, incrRoot, baseDir, currentPos);
        DsIncrementalBackup.validate(incrDir);

        for (BackupEntry e : readEntries(incrDir)) {
            if (e.role == BackupRole.BINLOG_FIXED || e.role == BackupRole.BINLOG_DYN) {
                String rel = e.relativePath;
                Assert.assertFalse("old day1 binlog should be skipped by dayKey filter: " + rel,
                        rel.contains("dsbinlog_20260101"));
            }
        }
    }

    @Test
    public void testTimelineRestoreToBinlogPosition() throws Exception {
        DsDatabaseLocal db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        long aliceId = db.putEntity(u("alice", 25, 100L), true);
        long bobId = db.putEntity(u("bob", 30, 200L), true);
        long carolId = db.putEntity(u("carol", 35, 300L), true);
        DsBinlogPosition basePos = db.getCurrentBinlogPosition();
        System.err.println("[DEBUG] basePos=" + basePos);
        db.close();

        File snapshotRoot = new File(sourceRoot, "backups/snapshots");
        File baseDir = DsFullBackup.createSnapshot(sourceRoot, snapshotRoot, basePos);
        DsFullBackup.validate(baseDir);

        db = new DsDatabaseLocal(sourceRoot, 1);
        db.registerTable(IbUserFlat.class);
        IbUserFlat alice = db.getTable(IbUserFlat.class, aliceId);
        Assert.assertNotNull(alice);
        alice.age = 26;
        db.putEntity(alice, false);
        DsBinlogPosition posAfterUpdate = db.getCurrentBinlogPosition();
        System.err.println("[DEBUG] posAfterUpdate=" + posAfterUpdate);

        long daveId = db.putEntity(u("dave", 40, 400L), true);
        DsBinlogPosition posAfterInsert = db.getCurrentBinlogPosition();
        System.err.println("[DEBUG] posAfterInsert=" + posAfterInsert);

        db.removeTable(IbUserFlat.class, carolId, false);
        DsBinlogPosition posAfterDelete = db.getCurrentBinlogPosition();
        System.err.println("[DEBUG] posAfterDelete=" + posAfterDelete);
        db.close();

        File incrRoot = new File(sourceRoot, "backups/incr");
        File incrDir = DsIncrementalBackup.create(sourceRoot, incrRoot, baseDir, posAfterDelete);
        DsIncrementalBackup.validate(incrDir);
        List<File> chain = Arrays.asList(baseDir, incrDir);

        // 恢复到 update 之后：alice 已更新，carol 仍在，dave 尚未插入
        restoreAndReplay(chain, basePos, posAfterUpdate, targetDb -> {
            IbUserFlat a = targetDb.getTable(IbUserFlat.class, aliceId);
            Assert.assertNotNull(a);
            Assert.assertEquals(26, a.age);
            Assert.assertNotNull("bob should exist", targetDb.getTable(IbUserFlat.class, bobId));
            IbUserFlat c = targetDb.getTable(IbUserFlat.class, carolId);
            System.err.println("[DEBUG] assertion lambda carol=" + c);
            Assert.assertNotNull("carol should exist before delete", c);
            Assert.assertNull("dave should not exist yet", targetDb.getTable(IbUserFlat.class, daveId));
        });

        // 恢复到 insert 之后：alice 已更新，dave 已插入，carol 尚未删除
        restoreAndReplay(chain, basePos, posAfterInsert, targetDb -> {
            IbUserFlat a = targetDb.getTable(IbUserFlat.class, aliceId);
            Assert.assertNotNull(a);
            Assert.assertEquals(26, a.age);
            IbUserFlat d = targetDb.getTable(IbUserFlat.class, daveId);
            Assert.assertNotNull(d);
            Assert.assertEquals(40, d.age);
            Assert.assertNotNull("carol should exist before delete", targetDb.getTable(IbUserFlat.class, carolId));
        });

        // 恢复到 delete 之后：最终状态
        restoreAndReplay(chain, basePos, posAfterDelete, targetDb -> {
            IbUserFlat a = targetDb.getTable(IbUserFlat.class, aliceId);
            Assert.assertNotNull(a);
            Assert.assertEquals(26, a.age);
            Assert.assertNotNull(targetDb.getTable(IbUserFlat.class, bobId));
            Assert.assertNotNull(targetDb.getTable(IbUserFlat.class, daveId));
            Assert.assertNull("carol should be deleted", targetDb.getTable(IbUserFlat.class, carolId));
        });
    }

    private interface TargetDbAssertion {
        void check(DsDatabaseLocal db) throws Exception;
    }

    private void restoreAndReplay(List<File> chain, DsBinlogPosition basePos,
                                  DsBinlogPosition targetPos, TargetDbAssertion assertion) throws Exception {
        File work = new File(targetRoot, UUID.randomUUID().toString());
        deleteRecursive(work);
        work.mkdirs();
        DsTimelineRestore.restoreTimeline(chain, work, targetPos);

        File fixedFile = new File(work, "_binlog/dsbinlog_" + basePos.dayKey() + "_fixed.dat");
        File dynFile = new File(work, "_binlog/dsbinlog_" + basePos.dayKey() + "_dyn.dat");
        System.err.println("[DEBUG] after restoreTimeline targetPos=" + targetPos
                + " fixedLen=" + fixedFile.length() + " dynLen=" + dynFile.length());

        try (DsDatabaseLocal targetDb = new DsDatabaseLocal(work, 1)) {
            targetDb.registerTable(IbUserFlat.class);
            int replayed = targetDb.replayBinlog(basePos, targetPos);
            System.err.println("[DEBUG] explicit replayed=" + replayed);
            Assert.assertTrue("should replay binlog events", replayed > 0);
            assertion.check(targetDb);
        }
    }
}
