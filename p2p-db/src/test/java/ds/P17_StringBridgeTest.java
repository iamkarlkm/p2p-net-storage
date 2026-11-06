package ds;

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
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P17_StringBridgeTest {

    public static class SEnt extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "age", length = 4, indexed = true)
        public int age;
        @DsField(name = "city", length = 24, indexed = true)
        public String city;
        public SEnt() {}
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws IOException {
        tmpRoot = Files.createTempDirectory("p17-t5-bridge-").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    // ---- TR-5.1 t01：String EQ 走 bridge ，与 全 表 baseline 100% 对齐 ----
    @Test(timeout = 240000)
    public void t01_stringEqBridgeEqualsFullScan() throws Exception {
        GenericManager<SEnt> m = new GenericManager<>(SEnt.class);
        int N = 500;
        Random r = new Random(51);
        List<SEnt> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            SEnt e = new SEnt();
            e.age = r.nextInt(100);
            e.city = "c_" + String.format("%03d", r.nextInt(50));
            e.name = "n" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        String targetCity = inserted.get(10).city;
        Set<Long> expected = inserted.stream()
                .filter(x -> targetCity.equals(x.city))
                .map(SEnt::getId).collect(Collectors.toSet());
        QueryWrapper<SEnt> w = m.buildQueryWrapper();
        w.eq("city", targetCity);
        List<SEnt> list = m.findList(w);
        Set<Long> got = list.stream().map(SEnt::getId).collect(Collectors.toSet());
        Assert.assertEquals("city EQ 集 合 100% 对 齐 大 小", expected.size(), got.size());
        for (Long id : expected) Assert.assertTrue("contains id=" + id, got.contains(id));
    }

    // ---- TR-5.2 t02：String BETWEEN + ORDER BY DESC ，走 bridge range ，结 果 100% baseline ----
    @Test(timeout = 240000)
    public void t02_stringBetweenOrderByDescEqualsBaseline() throws Exception {
        GenericManager<SEnt> m = new GenericManager<>(SEnt.class);
        int N = 1_000;
        Random r = new Random(1234);
        String[] pool = new String[] {
                "alpha_", "beta_", "gamma_", "delta_", "epsilon_", "zeta_",
                "eta_", "theta_", "iota_", "kappa_"
        };
        List<SEnt> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            SEnt e = new SEnt();
            e.age = 20 + r.nextInt(60);
            e.city = pool[r.nextInt(pool.length)] + String.format("%05d", r.nextInt(10000));
            e.name = "u_" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        String lo = "b", hi = "m";
        List<SEnt> expected = inserted.stream()
                .filter(x -> x.city.compareTo(lo) >= 0 && x.city.compareTo(hi) <= 0)
                .sorted(Comparator.comparing(x -> ((SEnt) x).city).reversed())
                .collect(Collectors.toList());
        Assert.assertTrue("baseline size >= 1", expected.size() >= 1);
        QueryWrapper<SEnt> w = m.buildQueryWrapper();
        w.between("city", lo, hi);
        w.orderByDesc("city");
        List<SEnt> actual = m.findList(w);
        Assert.assertEquals("BETWEEN 大小 对 齐", expected.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            SEnt ep = expected.get(i), ap = actual.get(i);
            Assert.assertEquals("city 字典序 DESC 第 " + i + " 位", ep.city, ap.city);
            Assert.assertEquals("同 city id 对 齐？如 重 复 city，按 rowId，否则 等 价",
                    ep.city.compareTo(ap.city), 0);
            Assert.assertTrue("实际 city 在 range", ap.city.compareTo(lo) >= 0 && ap.city.compareTo(hi) <= 0);
        }
    }

    // ---- TR-5.3 t03：String GT/GE + 删 除 removeById 后 bridge 同步 删 除，查 询 结 果 不 含 已 删 ----
    @Test(timeout = 240000)
    public void t03_stringGtRemoveByIdBridgeSynced() throws Exception {
        GenericManager<SEnt> m = new GenericManager<>(SEnt.class);
        int N = 400;
        Random r = new Random(77);
        List<SEnt> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            SEnt e = new SEnt();
            e.age = r.nextInt(200);
            e.city = "z_" + String.format("%05d", r.nextInt(80000));
            e.name = "u" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        String threshold = "z_50000";
        Set<Long> expectedAll = new HashSet<>();
        for (SEnt e : inserted) {
            if (e.city.compareTo(threshold) > 0) expectedAll.add(e.getId());
        }
        // 删 除 前 N/4 个 符 合 条 件 的
        List<Long> toRemove = expectedAll.stream().limit(N / 8).collect(Collectors.toList());
        for (Long id : toRemove) m.removeById(id);
        Set<Long> expectedAfter = new HashSet<>(expectedAll);
        expectedAfter.removeAll(toRemove);
        QueryWrapper<SEnt> w = m.buildQueryWrapper();
        w.gt("city", threshold);
        List<SEnt> actual = m.findList(w);
        Set<Long> got = actual.stream().map(SEnt::getId).collect(Collectors.toSet());
        Assert.assertEquals("GT 删 除 后 大 小", expectedAfter.size(), got.size());
        for (Long id : toRemove) Assert.assertFalse("已 删除 id 不 出 现: " + id, got.contains(id));
        for (Long id : expectedAfter) Assert.assertTrue("预 期 id 存 在: " + id, got.contains(id));
    }
}
