package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * V0.8 批量 putEntity 索引 I/O 聚合专项测试。
 */
public class DsDatabaseLocalBatchPutTest {

    private File tmpRoot;
    private String oldHome;
    private DsDatabaseLocal db;

    public static class BatchUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "score", length = 8, indexed = true)
        public long score;
        public BatchUser() {}
    }

    @DsCompositeIndex(name = "idx_name_age", columns = {"name", "age"})
    public static class BatchCompositeUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "score", length = 8)
        public long score;
        public BatchCompositeUser() {}
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_batch_put_").toFile();
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

    private static BatchUser u(String name, int age, long score) {
        BatchUser u = new BatchUser();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    private static BatchCompositeUser cu(String name, int age, long score) {
        BatchCompositeUser u = new BatchCompositeUser();
        u.name = name; u.age = age; u.score = score;
        return u;
    }

    @Test
    public void testEmptyBatchReturnsEmpty() throws Exception {
        List<Long> ids = db.putEntities(new ArrayList<BatchUser>(), true);
        Assert.assertTrue(ids.isEmpty());
    }

    @Test
    public void testBatchInsertUpdatesSingleColumnIndexes() throws Exception {
        List<BatchUser> list = new ArrayList<>();
        list.add(u("a", 25, 100L));
        list.add(u("b", 25, 200L));
        list.add(u("c", 30, 100L));
        list.add(u("d", 30, 200L));

        List<Long> ids = db.putEntities(list, true);
        Assert.assertEquals(4, ids.size());

        GenericManager<BatchUser> m = new GenericManager<>(BatchUser.class);
        QueryWrapper<BatchUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        Assert.assertEquals(2, m.findList(w).size());

        w = m.buildQueryWrapper();
        w.eq("score", 100L);
        Assert.assertEquals(2, m.findList(w).size());
    }

    @Test
    public void testBatchUpdateMaintainsIndexes() throws Exception {
        List<BatchUser> list = new ArrayList<>();
        list.add(u("a", 25, 100L));
        list.add(u("b", 30, 200L));
        List<Long> ids = db.putEntities(list, true);

        // 更新：a 的 age 改为 30，b 的 score 改为 100
        BatchUser ua = db.getTable(BatchUser.class, ids.get(0));
        ua.age = 30;
        BatchUser ub = db.getTable(BatchUser.class, ids.get(1));
        ub.score = 100L;
        List<BatchUser> updates = new ArrayList<>();
        updates.add(ua);
        updates.add(ub);
        db.putEntities(updates, false);

        GenericManager<BatchUser> m = new GenericManager<>(BatchUser.class);
        QueryWrapper<BatchUser> w = m.buildQueryWrapper();
        w.eq("age", 30);
        Assert.assertEquals(2, m.findList(w).size());

        w = m.buildQueryWrapper();
        w.eq("score", 100L);
        Assert.assertEquals(2, m.findList(w).size());

        w = m.buildQueryWrapper();
        w.eq("age", 25);
        Assert.assertEquals(0, m.findList(w).size());
    }

    @Test
    public void testSameIndexedValueMultipleRows() throws Exception {
        List<BatchUser> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(u("u" + i, 25, 100L));
        }
        db.putEntities(list, true);

        GenericManager<BatchUser> m = new GenericManager<>(BatchUser.class);
        QueryWrapper<BatchUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        Assert.assertEquals(10, m.findList(w).size());

        w = m.buildQueryWrapper();
        w.eq("score", 100L);
        Assert.assertEquals(10, m.findList(w).size());
    }

    @Test
    public void testBatchInsertCompositeIndex() throws Exception {
        List<BatchCompositeUser> list = new ArrayList<>();
        list.add(cu("alice", 25, 100L));
        list.add(cu("alice", 25, 200L));
        list.add(cu("alice", 30, 100L));
        list.add(cu("bob", 25, 100L));
        db.putEntities(list, true);

        GenericManager<BatchCompositeUser> m = new GenericManager<>(BatchCompositeUser.class);
        QueryWrapper<BatchCompositeUser> w = m.buildQueryWrapper();
        w.eq("name", "alice");
        w.eq("age", 25);
        Assert.assertEquals(2, m.findList(w).size());
    }

    @Test
    public void testBatchUpdateCompositeIndex() throws Exception {
        List<BatchCompositeUser> list = new ArrayList<>();
        list.add(cu("alice", 25, 100L));
        list.add(cu("bob", 30, 200L));
        List<Long> ids = db.putEntities(list, true);

        BatchCompositeUser u1 = db.getTable(BatchCompositeUser.class, ids.get(0));
        u1.age = 30;
        BatchCompositeUser u2 = db.getTable(BatchCompositeUser.class, ids.get(1));
        u2.name = "alice";
        List<BatchCompositeUser> updates = new ArrayList<>();
        updates.add(u1);
        updates.add(u2);
        db.putEntities(updates, false);

        GenericManager<BatchCompositeUser> m = new GenericManager<>(BatchCompositeUser.class);
        QueryWrapper<BatchCompositeUser> w = m.buildQueryWrapper();
        w.eq("name", "alice");
        w.eq("age", 30);
        Assert.assertEquals(2, m.findList(w).size());

        w = m.buildQueryWrapper();
        w.eq("name", "alice");
        w.eq("age", 25);
        Assert.assertEquals(0, m.findList(w).size());
    }
}
