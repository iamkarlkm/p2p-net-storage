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

public class DsEqIndexQueryPlannerTest {

    private File tmpRoot;
    private String oldHome;

    public static class PlannerUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "score", length = 8, indexed = true)
        public long score;

        @DsField(name = "city", length = 32, indexed = true)
        public String city;

        public PlannerUser() {}
    }

    public static class PlannerNoIndexUser extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4)
        public int age;
        @DsField(name = "score", length = 8)
        public long score;
        public PlannerNoIndexUser() {}
    }

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("dsdb_planner_idx_").toFile();
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

    private static PlannerUser u(String name, int age, long score, String city) {
        PlannerUser u = new PlannerUser();
        u.name = name;
        u.age = age;
        u.score = score;
        u.city = city;
        return u;
    }

    @Test
    public void testEqLongAgeHitIndex() throws Exception {
        GenericManager<PlannerUser> m = new GenericManager<>(PlannerUser.class);
        m.saveOrUpdate(u("alice", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("bob",   30, 600L, "beijing"));
        m.saveOrUpdate(u("carol", 25, 700L, "guangzhou"));
        m.saveOrUpdate(u("dave",  40, 800L, "shenzhen"));

        QueryWrapper<PlannerUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        List<PlannerUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (PlannerUser u : list) Assert.assertEquals(25, u.age);
    }

    @Test
    public void testBetweenScoreHitIndex() throws Exception {
        GenericManager<PlannerUser> m = new GenericManager<>(PlannerUser.class);
        m.saveOrUpdate(u("a", 18, 100L, "sh"));
        m.saveOrUpdate(u("b", 20, 500L, "bj"));
        m.saveOrUpdate(u("c", 22, 650L, "gz"));
        m.saveOrUpdate(u("d", 25, 800L, "sz"));
        m.saveOrUpdate(u("e", 30, 999L, "hz"));

        QueryWrapper<PlannerUser> w = m.buildQueryWrapper();
        w.between("score", 500L, 800L);
        List<PlannerUser> list = m.findList(w);
        Assert.assertEquals(3, list.size());
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (PlannerUser u : list) {
            if (u.score < min) min = u.score;
            if (u.score > max) max = u.score;
        }
        Assert.assertEquals(500L, min);
        Assert.assertEquals(800L, max);
    }

    @Test
    public void testEqStringCityHitIndex() throws Exception {
        GenericManager<PlannerUser> m = new GenericManager<>(PlannerUser.class);
        m.saveOrUpdate(u("a", 18, 100L, "shanghai"));
        m.saveOrUpdate(u("b", 20, 200L, "beijing"));
        m.saveOrUpdate(u("c", 22, 300L, "shanghai"));
        m.saveOrUpdate(u("d", 25, 400L, "shenzhen"));

        QueryWrapper<PlannerUser> w = m.buildQueryWrapper();
        w.eq("city", "shanghai");
        List<PlannerUser> list = m.findList(w);
        Assert.assertEquals(2, list.size());
        for (PlannerUser u : list) Assert.assertEquals("shanghai", u.city);
        Assert.assertEquals(2, m.count(w));
    }

    @Test
    public void testMultiCondEqAndRangeMixed() throws Exception {
        GenericManager<PlannerUser> m = new GenericManager<>(PlannerUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "shanghai"));
        m.saveOrUpdate(u("c", 25, 900L, "beijing"));
        m.saveOrUpdate(u("d", 25, 750L, "shanghai"));
        m.saveOrUpdate(u("e", 40, 700L, "shanghai"));
        m.saveOrUpdate(u("f", 24, 650L, "shanghai"));

        QueryWrapper<PlannerUser> w = m.buildQueryWrapper();
        w.eq("city", "shanghai");
        w.ge("age", 25);
        w.lt("score", 800L);
        List<PlannerUser> list = m.findList(w);
        Assert.assertEquals(4, list.size());
        for (PlannerUser u : list) {
            Assert.assertEquals("shanghai", u.city);
            Assert.assertTrue(String.valueOf(u.age), u.age >= 25);
            Assert.assertTrue(u.score < 800L);
        }
    }

    @Test
    public void testNoIndexFallback() throws Exception {
        GenericManager<PlannerNoIndexUser> m = new GenericManager<>(PlannerNoIndexUser.class);
        PlannerNoIndexUser u1 = new PlannerNoIndexUser();
        u1.name = "a"; u1.age = 25; u1.score = 500L;
        PlannerNoIndexUser u2 = new PlannerNoIndexUser();
        u2.name = "b"; u2.age = 30; u2.score = 600L;
        PlannerNoIndexUser u3 = new PlannerNoIndexUser();
        u3.name = "c"; u3.age = 25; u3.score = 700L;
        m.saveOrUpdate(u1);
        m.saveOrUpdate(u2);
        m.saveOrUpdate(u3);

        QueryWrapper<PlannerNoIndexUser> w = m.buildQueryWrapper();
        w.eq("age", 25);
        w.between("score", 400L, 650L);
        List<PlannerNoIndexUser> list = m.findList(w);
        Assert.assertEquals(1, list.size());
        Assert.assertEquals(1, m.count(w));
    }

    @Test
    public void testGetOneHitIndex() throws Exception {
        GenericManager<PlannerUser> m = new GenericManager<>(PlannerUser.class);
        m.saveOrUpdate(u("a", 25, 500L, "shanghai"));
        m.saveOrUpdate(u("b", 30, 600L, "beijing"));
        m.saveOrUpdate(u("c", 25, 700L, "guangzhou"));

        QueryWrapper<PlannerUser> w = m.buildQueryWrapper();
        w.eq("age", 30);
        PlannerUser one = m.getOne(w);
        Assert.assertNotNull(one);
        Assert.assertEquals(30, one.age);
    }
}
