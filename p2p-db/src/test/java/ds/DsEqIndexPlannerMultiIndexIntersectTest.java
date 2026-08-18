package ds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * V0.6 Planner 多索引交集优化专项测试。
 *
 * <p>验证 GenericManager 在 QueryWrapper 同时命中多个 indexed=true 列时：
 * 所有可用索引分别取 rowIdSet，取 size 最小集合作 driver，
 * 依次 HashSet.retainAll 求交集；单索引/无索引保持 V0.5 行为不变。</p>
 */
public class DsEqIndexPlannerMultiIndexIntersectTest {

    private File tmpRoot;
    private String oldHome;

    public static class MultiIdxUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        @DsField(name = "city", length = 32, indexed = true)
        public String city;

        @DsField(name = "status", length = 4, indexed = true)
        public int status;

        public MultiIdxUser() {}
    }

    public static class MultiIdxNoIndexUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "score", length = 8)
        public long score;
        public MultiIdxNoIndexUser() {}
    }

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_planner_multi_idx_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {}
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static MultiIdxUser u(String name, int age, long score, String city, int status) {
        MultiIdxUser u = new MultiIdxUser();
        u.name = name;
        u.age = age;
        u.score = score;
        u.city = city;
        u.status = status;
        return u;
    }

    @Test
    public void testTwoEqLongColumnsIntersect() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai", 1));
        m.saveOrUpdate(u("c", 25, 700L, "beijing", 1));
        m.saveOrUpdate(u("d", 25, 600L, "shanghai", 1));
        m.saveOrUpdate(u("e", 40, 600L, "shanghai", 1));
        m.saveOrUpdate(u("f", 25, 600L, "beijing", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.eq("score", 600L);
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (MultiIdxUser u : list) {
            Assert.assertEquals(25, u.age);
            Assert.assertEquals(600L, u.score);
        }
        Assert.assertEquals(2, m.count(w));
    }

    @Test
    public void testThreeEqColumnsIntersect() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("b", 25, 500L, "beijing", 1));
        m.saveOrUpdate(u("c", 25, 500L, "shanghai", 2));
        m.saveOrUpdate(u("d", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("e", 30, 500L, "shanghai", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.eq("score", 500L);
        w.eq("city", "shanghai");
        w.eq("status", 1);
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (MultiIdxUser u : list) {
            Assert.assertEquals(25, u.age);
            Assert.assertEquals(500L, u.score);
            Assert.assertEquals("shanghai", u.city);
            Assert.assertEquals(1, u.status);
        }
    }

    @Test
    public void testStringAndLongIntersect() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai", 1));
        m.saveOrUpdate(u("c", 25, 700L, "beijing", 1));
        m.saveOrUpdate(u("d", 25, 600L, "shanghai", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("city", "shanghai");
        w.eq("score", 600L);
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (MultiIdxUser u : list) {
            Assert.assertEquals("shanghai", u.city);
            Assert.assertEquals(600L, u.score);
        }
    }

    @Test
    public void testOneIndexEmptyReturnsEmpty() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai", 1));
        m.saveOrUpdate(u("c", 25, 700L, "beijing", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.eq("score", 9999L);
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(0, list.size());
        Assert.assertEquals(0, m.count(w));
    }

    @Test
    public void testSingleIndexKeepsV05Behavior() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai", 1));
        m.saveOrUpdate(u("c", 25, 700L, "beijing", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (MultiIdxUser u : list) Assert.assertEquals(25, u.age);
    }

    @Test
    public void testNoIndexedColumnFallback() throws Exception {
        GenericManager<MultiIdxNoIndexUser> m = new GenericManager<>(MultiIdxNoIndexUser.class);
        MultiIdxNoIndexUser u1 = new MultiIdxNoIndexUser();
        u1.name = "a"; u1.age = 25; u1.score = 500L;
        MultiIdxNoIndexUser u2 = new MultiIdxNoIndexUser();
        u2.name = "b"; u2.age = 30; u2.score = 600L;
        MultiIdxNoIndexUser u3 = new MultiIdxNoIndexUser();
        u3.name = "c"; u3.age = 25; u3.score = 700L;
        m.saveOrUpdate(u1);
        m.saveOrUpdate(u2);
        m.saveOrUpdate(u3);

        QueryWrapper<MultiIdxNoIndexUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.eq("score", 500L);
        List<MultiIdxNoIndexUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals(25, list.get(0).age);
        Assert.assertEquals(500L, list.get(0).score);
    }

    @Test
    public void testIndexPlusNonIndexMatchesAll() throws Exception {
        GenericManager<MultiIdxUser> m = new GenericManager<>(MultiIdxUser.class);
        m.saveOrUpdate(u("alice", 25, 500L, "shanghai", 1));
        m.saveOrUpdate(u("bob",   25, 600L, "shanghai", 1));
        m.saveOrUpdate(u("carol", 25, 600L, "shanghai", 2));
        m.saveOrUpdate(u("dave",  30, 600L, "shanghai", 1));

        QueryWrapper<MultiIdxUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.eq("score", 600L);
        w.like("name", "b");
        List<MultiIdxUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals("bob", list.get(0).name);
    }
}
