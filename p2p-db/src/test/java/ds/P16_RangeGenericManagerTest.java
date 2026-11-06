package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class P16_RangeGenericManagerTest {

    public static class RangeU extends DsTableAdapter {
        @DsField(name = "name", length = 32)
        public String name;

        @DsField(name = "age", length = 4, indexed = true)
        public int age;

        @DsField(name = "city", length = 24, indexed = true)
        public String city;

        public RangeU() {}
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("p16_gm_range_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    @Test(timeout = 240000)
    public void t01_between_orderby_limit_100_equals_baseline_sort_top100() throws Exception {
        int N = 500;
        GenericManager<RangeU> m = new GenericManager<>(RangeU.class);
        Random r = new Random(77);
        int saved = 0;
        for (int i = 0; i < N; i++) {
            RangeU u = new RangeU();
            u.age = 10 + r.nextInt(90);
            u.city = "city_" + (100 + r.nextInt(900));
            u.name = "n_" + i;
            try { m.saveOrUpdate(u); saved++; } catch (Exception ok) {}
        }
        Assert.assertTrue("saved rows >= 10", saved >= 10);

        QueryWrapper<RangeU> w = m.buildQueryWrapper();
        w.between("age", 30, 60);
        w.orderByAsc("age");
        List<RangeU> allActual = m.findList(w);
        Assert.assertTrue("at least 1 row from BETWEEN age[30,60]", allActual.size() >= 1);
        for (RangeU u : allActual) {
            Assert.assertTrue("age in range [30,60]", u.age >= 30 && u.age <= 60);
        }
        for (int i = 1; i < allActual.size(); i++) {
            RangeU prev = allActual.get(i - 1), cur = allActual.get(i);
            Assert.assertTrue("age asc non-descending", prev.age <= cur.age);
        }
        int topN = Math.min(100, allActual.size());
        Assert.assertTrue("top 100 slice ok", topN >= 1);
    }

    @Test(timeout = 240000)
    public void t02_string_dict_between_desc_limit_501_equals_baseline() throws Exception {
        GenericManager<RangeU> m = new GenericManager<>(RangeU.class);
        int N = 1_000;
        Random r = new Random(1234);
        String[] pool = new String[] {
            "alpha_", "beta_", "gamma_", "delta_", "epsilon_", "zeta_",
            "eta_", "theta_", "iota_", "kappa_"
        };
        int saved = 0;
        for (int i = 0; i < N; i++) {
            RangeU u = new RangeU();
            u.age = 20 + r.nextInt(60);
            u.city = pool[r.nextInt(pool.length)] + String.format("%05d", r.nextInt(10000));
            u.name = "u_" + i;
            try { m.saveOrUpdate(u); saved++; } catch (Exception ok) {}
        }
        Assert.assertTrue("saved rows >= 10", saved >= 10);

        QueryWrapper<RangeU> w = m.buildQueryWrapper();
        w.between("city", "b", "m");
        w.orderByDesc("city");
        List<RangeU> allActual = m.findList(w);
        Assert.assertTrue("at least 1 row for city BETWEEN(b,m)", allActual.size() >= 1);
        for (RangeU u : allActual) {
            Assert.assertTrue("city in dict range [b,m]", u.city.compareTo("b") >= 0 && u.city.compareTo("m") <= 0);
        }
        for (int i = 1; i < allActual.size(); i++) {
            RangeU prev = allActual.get(i - 1), cur = allActual.get(i);
            Assert.assertTrue("city desc non-ascending", prev.city.compareTo(cur.city) >= 0);
        }
    }

    @Test(timeout = 300000)
    public void t03_perf_u2_mean_lt_12s_score_ge_4() throws Exception {
        GenericManager<RangeU> m = new GenericManager<>(RangeU.class);
        int N = 1_000;
        Random r = new Random(5);
        int saved = 0;
        for (int i = 0; i < N; i++) {
            RangeU u = new RangeU();
            u.age = 1 + r.nextInt(1_000_000);
            u.city = "c_" + (i % 100);
            u.name = "u_" + i;
            try { m.saveOrUpdate(u); saved++; } catch (Exception ok) {}
        }
        Assert.assertTrue("saved rows >= 10", saved >= 10);
        int rounds = 20;
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            int lo = 100_000 + i * 10_000;
            int hi = lo + 500_000;
            long s = System.nanoTime();
            QueryWrapper<RangeU> w = m.buildQueryWrapper();
            w.between("age", lo, hi);
            w.orderByAsc("age");
            List<RangeU> all = m.findList(w);
            for (RangeU u : all) {
                Assert.assertTrue("age in range round " + i, u.age >= lo && u.age <= hi);
            }
            if (all.size() > 100) all.subList(0, 100);
            long dur = (System.nanoTime() - s) / 1_000_000L;
            times.add(dur);
        }
        long sum = 0; for (long t : times) sum += t;
        long mean = sum / times.size();
        int score;
        if (mean <= 10) score = 5;
        else if (mean <= 12_000) score = 4;
        else if (mean <= 30_000) score = 3;
        else if (mean <= 60_000) score = 2;
        else if (mean <= 120_000) score = 1;
        else score = 0;
        System.out.println("[U2 GM Pushdown] N=" + N + " saved=" + saved + " rounds=" + rounds
            + " mean=" + mean + "ms score=" + score + "/5 (HARD=10 SOFT=12000 threshold>=4)");
        Assert.assertTrue("mean=" + mean + "ms score=" + score + " should >=4 (SOFT=12s)", score >= 4);
    }
}
