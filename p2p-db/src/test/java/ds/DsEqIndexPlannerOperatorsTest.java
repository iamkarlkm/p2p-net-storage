package ds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
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
 * V0.9 Query Planner 算子补齐专项测试。
 *
 * <p>验证 GenericManager 对 OR / IN / NOT_IN / COUNT / EXISTS 五个算子的支持，
 * 其中 IN 在索引列上走索引 narrowing，最终仍由 matchesAll 裁决。</p>
 */
public class DsEqIndexPlannerOperatorsTest {

    private File tmpRoot;
    private String oldHome;

    public static class OpUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        @DsField(name = "city", length = 32, indexed = true)
        public String city;

        public OpUser() {}
    }

    public static class OpOrder extends DsTableAdapter {
        @DsField(name = "user_id", length = 8, indexed = true)
        public long userId;

        @DsField(name = "status", length = 4, indexed = true)
        public int status;

        public OpOrder() {}
    }

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_planner_ops_").toFile();
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

    private static OpUser u(String name, int age, long score, String city) {
        OpUser u = new OpUser();
        u.name = name;
        u.age = age;
        u.score = score;
        u.city = city;
        return u;
    }

    private static OpOrder o(long userId, int status) {
        OpOrder o = new OpOrder();
        o.userId = userId;
        o.status = status;
        return o;
    }

    @Test
    public void testInLongHitsIndex() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 25, 700L, "guangzhou"));
        m.saveOrUpdate(u("d", 40, 800L, "shenzhen"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.in("age", Arrays.asList(25, 40));
        List<OpUser> list = m.findList(w);
        Assert.assertEquals(3, list.size());
        for (OpUser u : list) {
            Assert.assertTrue(u.age == 25 || u.age == 40);
        }
        Assert.assertEquals(3, m.count(w));
    }

    @Test
    public void testInStringHitsIndex() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 25, 700L, "shanghai"));
        m.saveOrUpdate(u("d", 40, 800L, "shenzhen"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.in("city", Arrays.asList("shanghai", "shenzhen"));
        List<OpUser> list = m.findList(w);
        Assert.assertEquals(3, list.size());
        for (OpUser u : list) {
            Assert.assertTrue(u.city.equals("shanghai") || u.city.equals("shenzhen"));
        }
    }

    @Test
    public void testInWithIndexPlusNonIndexFilter() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("alice", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("bob", 25, 600L, "beijing"));
        m.saveOrUpdate(u("abby", 30, 600L, "beijing"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.in("age", Arrays.asList(25, 30));
        w.like("name", "b");
        List<OpUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (OpUser u : list) {
            Assert.assertTrue(u.name.contains("b"));
            Assert.assertTrue(u.age == 25 || u.age == 30);
        }
    }

    @Test
    public void testNotInFallbackToFullScan() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 25, 700L, "guangzhou"));
        m.saveOrUpdate(u("d", 40, 800L, "shenzhen"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.notIn("age", Arrays.asList(25, 40));
        List<OpUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals(30, list.get(0).age);
        Assert.assertEquals(1, m.count(w));
    }

    @Test
    public void testOrBranch() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai"));
        m.saveOrUpdate(u("c", 35, 700L, "guangzhou"));
        m.saveOrUpdate(u("d", 40, 800L, "shanghai"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.eq("city", "shanghai");
        QueryWrapper<OpUser> or1 = m.buildQueryWrapper();
        or1.eq("age", 30);
        QueryWrapper<OpUser> or2 = m.buildQueryWrapper();
        or2.eq("score", 800L);
        w.or(or1);
        w.or(or2);

        List<OpUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (OpUser u : list) {
            Assert.assertEquals("shanghai", u.city);
            Assert.assertTrue(u.age == 30 || u.score == 800L);
        }
    }

    @Test
    public void testPureOrBranch() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 35, 700L, "guangzhou"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        QueryWrapper<OpUser> or1 = m.buildQueryWrapper();
        or1.eq("age", 25);
        QueryWrapper<OpUser> or2 = m.buildQueryWrapper();
        or2.eq("score", 700L);
        w.or(or1);
        w.or(or2);

        List<OpUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
    }

    @Test
    public void testCountEmptyWrapper() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 35, 700L, "guangzhou"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        Assert.assertEquals(3, m.count(w));
        Assert.assertEquals(3, m.count((QueryWrapper<OpUser>) null));
    }

    @Test
    public void testCountWithFilter() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 25, 700L, "guangzhou"));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        Assert.assertEquals(2, m.count(w));
    }

    @Test
    public void testExists() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        GenericManager<OpOrder> om = new GenericManager<>(OpOrder.class);

        OpUser u1 = u("a", 25, 500L, "shanghai");
        m.saveOrUpdate(u1);
        OpUser u2 = u("b", 30, 600L, "beijing");
        m.saveOrUpdate(u2);

        om.saveOrUpdate(o(u1.getId(), 1));
        om.saveOrUpdate(o(u1.getId(), 2));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        QueryWrapper<OpOrder> sub = new QueryWrapper<>();
        sub.eq("status", 1);
        w.exists(OpOrder.class, "user_id", sub);

        List<OpUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals(u1.getId(), list.get(0).getId());
    }

    @Test
    public void testNotExists() throws Exception {
        GenericManager<OpUser> m = new GenericManager<>(OpUser.class);
        GenericManager<OpOrder> om = new GenericManager<>(OpOrder.class);

        OpUser u1 = u("a", 25, 500L, "shanghai");
        m.saveOrUpdate(u1);
        OpUser u2 = u("b", 30, 600L, "beijing");
        m.saveOrUpdate(u2);

        om.saveOrUpdate(o(u1.getId(), 1));

        QueryWrapper<OpUser> w = m.buildQueryWrapper();
        QueryWrapper<OpOrder> sub = new QueryWrapper<>();
        sub.eq("status", 99);
        w.notExists(OpOrder.class, "user_id", sub);

        List<OpUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
    }
}
