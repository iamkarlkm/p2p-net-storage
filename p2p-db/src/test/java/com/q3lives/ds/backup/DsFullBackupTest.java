package com.q3lives.ds.backup;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 全量备份目录化格式回归测试。
 */
public class DsFullBackupTest {

    private File sourceRoot;
    private File targetRoot;
    private DsDatabaseLocal db;

    public static class BuUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "score", length = 8, indexed = true)
        public long score;
        public BuUser() {}
    }

    @Before
    public void beforeEach() throws Exception {
        sourceRoot = Files.createTempDirectory("dsdb_backup_src_").toFile();
        targetRoot = Files.createTempDirectory("dsdb_backup_tgt_").toFile();
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

    private static BuUser u(String name, int age, long score) {
        BuUser u = new BuUser();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    @Test
    public void testCreateRestoreAndValidate() throws Exception {
        // 写入初始数据
        List<BuUser> list = new ArrayList<>();
        list.add(u("alice", 25, 100L));
        list.add(u("bob", 30, 200L));
        list.add(u("carol", 25, 300L));
        List<Long> ids = db.putEntities(list, true);
        Assert.assertEquals(3, ids.size());

        // 更新一条、删除一条，产生 INSERT/UPDATE/DELETE binlog
        BuUser updated = db.getTable(BuUser.class, ids.get(0));
        updated.age = 26;
        db.putEntity(updated, false);
        db.removeTable(BuUser.class, ids.get(2), false);

        db.close();
        db = null;

        // 全量备份
        File backupRoot = new File(sourceRoot, "backups/full");
        File backupDir = DsFullBackup.create(sourceRoot, backupRoot);
        Assert.assertTrue("manifest missing", new File(backupDir, "manifest.yaml").isFile());

        // 校验备份
        DsFullBackup.validate(backupDir);

        // 确认 binlog 文件被包含（按 role 分 binlog_fixed、binlog_dyn、binlog_bytes）
        File fixedDir = new File(backupDir, "binlog_fixed/_binlog");
        File dynDir = new File(backupDir, "binlog_dyn/_binlog");
        Assert.assertTrue("binlog fixed dir missing", fixedDir.isDirectory());
        Assert.assertTrue("binlog dyn dir missing", dynDir.isDirectory());
        boolean hasFixed = false;
        for (File f : fixedDir.listFiles()) {
            if (f.getName().endsWith("_fixed.dat")) hasFixed = true;
        }
        boolean hasDyn = false;
        for (File f : dynDir.listFiles()) {
            if (f.getName().endsWith("_dyn.dat")) hasDyn = true;
        }
        Assert.assertTrue("binlog fixed file missing", hasFixed);
        Assert.assertTrue("binlog dyn file missing", hasDyn);

        // 恢复到新目录
        DsFullBackup.restore(backupDir, targetRoot);

        // 验证索引与数据一致性
        try (DsDatabaseLocal db2 = new DsDatabaseLocal(targetRoot, 1)) {
            BuUser alice = db2.getTable(BuUser.class, ids.get(0));
            Assert.assertNotNull(alice);
            Assert.assertEquals("alice", alice.name);
            Assert.assertEquals(26, alice.age);
            Assert.assertEquals(100L, alice.score);

            BuUser bob = db2.getTable(BuUser.class, ids.get(1));
            Assert.assertNotNull(bob);
            Assert.assertEquals("bob", bob.name);
            Assert.assertEquals(30, bob.age);

            String oldHome = System.getProperty("p2p.db.home");
            System.setProperty("p2p.db.home", targetRoot.getAbsolutePath());
            try {
                GenericManager<BuUser> m = new GenericManager<>(BuUser.class);
                QueryWrapper<BuUser> w = m.buildQueryWrapper();
                w.eq("age", 26);
                List<BuUser> byAge = m.findList(w);
                Assert.assertEquals(1, byAge.size());
                Assert.assertEquals("alice", byAge.get(0).name);

                w = m.buildQueryWrapper();
                w.eq("score", 200L);
                List<BuUser> byScore = m.findList(w);
                Assert.assertEquals(1, byScore.size());
                Assert.assertEquals("bob", byScore.get(0).name);

                // 删除的 carol（score=300, age=25）不应再出现在索引查询结果中
                w = m.buildQueryWrapper();
                w.eq("score", 300L);
                Assert.assertEquals("deleted row should be gone from index", 0, m.findList(w).size());

                w = m.buildQueryWrapper();
                w.eq("age", 25);
                Assert.assertEquals(0, m.findList(w).size());
            } finally {
                if (oldHome == null) {
                    System.clearProperty("p2p.db.home");
                } else {
                    System.setProperty("p2p.db.home", oldHome);
                }
            }
        }

        // 确认恢复出的 _binlog 文件存在
        Assert.assertTrue(new File(targetRoot, "_binlog").isDirectory());
    }
}
