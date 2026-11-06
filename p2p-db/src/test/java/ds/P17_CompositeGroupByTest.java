package ds;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P17_CompositeGroupByTest {

    @DsCompositeIndex(name = "idx_abc", columns = {"a", "b", "c"})
    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 16)
        public String name;
        @DsField(name = "a", length = 8)
        public long a;
        @DsField(name = "b", length = 16)
        public String b;
        @DsField(name = "c", length = 4)
        public int c;
        public AbcEntity() {}
    }

    public static class GroupResult {
        public final List<Object> key;
        public final Map<String, Number> values;
        public GroupResult(List<Object> key, Map<String, Number> values) {
            this.key = key; this.values = values;
        }
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws Exception {
        tmpRoot = new File(System.getProperty("java.io.tmpdir"),
                "p17-t4-groupby-" + System.nanoTime());
        tmpRoot.mkdirs();
        oldHome = System.getProperty("ds.home");
        System.setProperty("ds.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        if (oldHome != null) System.setProperty("ds.home", oldHome);
        else System.clearProperty("ds.home");
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

    private static void ensureInitFor(GenericManager<?> m) throws Exception {
        Method mth = GenericManager.class.getDeclaredMethod("ensureInit");
        mth.setAccessible(true);
        mth.invoke(m);
    }

    @SuppressWarnings("unused")
    private static <T extends DsTableAdapter> List<GroupResult> invokeAggregate(
            GenericManager<T> m, Class<T> clz, String ciName,
            String[] groupByCols, String[] aggTypes, String[] aggCols,
            QueryWrapper<T> filter,
            Collection<Long> whitelist) throws Exception {
        Method mth = GenericManager.class.getDeclaredMethod(
                "aggregateByComposite", Class.class, String.class,
                String[].class, String[].class, String[].class,
                QueryWrapper.class, Collection.class);
        mth.setAccessible(true);
        Object raw = mth.invoke(m, clz, ciName, groupByCols, aggTypes, aggCols, filter, whitelist);
        List<?> list = (List<?>) raw;
        List<GroupResult> out = new ArrayList<>(list.size());
        for (Object o : list) {
            Field kf = o.getClass().getDeclaredField("key");
            kf.setAccessible(true);
            List<Object> key = (List<Object>) kf.get(o);
            Field vf = o.getClass().getDeclaredField("values");
            vf.setAccessible(true);
            Map<String, Number> values = (Map<String, Number>) vf.get(o);
            out.add(new GroupResult(key, values));
        }
        return out;
    }

    // ---- TR-4.1 t01：COUNT(*) GROUP BY (a,b)，完全 whitelist 精确 对齐 ----
    @Test
    public void t01_countStarGroupByAbEqualsFullScanBaseline() throws Exception {
        GenericManager<AbcEntity> m = new GenericManager<>(AbcEntity.class);
        ensureInitFor(m);
        int N = 800;
        Random r = new Random(11L);
        List<AbcEntity> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            e.a = r.nextInt(10);
            e.b = "g" + String.format("%02d", r.nextInt(15));
            e.c = r.nextInt(1000);
            e.name = "n" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        Map<List<Object>, Long> baseline = new HashMap<>();
        for (AbcEntity e : inserted) {
            List<Object> k = Arrays.asList(e.a, e.b);
            baseline.merge(k, 1L, Long::sum);
        }
        Set<Long> wl = inserted.stream().map(AbcEntity::getId).collect(Collectors.toSet());
        List<GroupResult> got = invokeAggregate(
                m, AbcEntity.class, "idx_abc",
                new String[]{"a", "b"},
                new String[]{"COUNT"}, new String[]{null},
                null, wl);
        Assert.assertEquals("group 数 = baseline group 数", baseline.size(), got.size());
        for (GroupResult gr : got) {
            Long exp = baseline.get(gr.key);
            Assert.assertNotNull("key 在 baseline 中: " + gr.key, exp);
            Number gotCount = gr.values.get("COUNT:*");
            Assert.assertNotNull("COUNT:* 存在", gotCount);
            Assert.assertEquals("COUNT(*) 匹配 key=" + gr.key, exp.longValue(), gotCount.longValue());
        }
    }

    // ---- TR-4.2 t02：SUM(c) GROUP BY (a) ----
    @Test
    public void t02_sumCGroupByAEqualsFullScanBaseline() throws Exception {
        GenericManager<AbcEntity> m = new GenericManager<>(AbcEntity.class);
        ensureInitFor(m);
        int N = 800;
        Random r = new Random(22L);
        List<AbcEntity> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            e.a = r.nextInt(20);
            e.b = "x" + r.nextInt(100);
            e.c = r.nextInt(1000);
            e.name = "n" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        Map<List<Object>, BigInteger> baselineSum = new HashMap<>();
        for (AbcEntity e : inserted) {
            List<Object> k = Collections.singletonList(e.a);
            baselineSum.merge(k, BigInteger.valueOf(e.c), BigInteger::add);
        }
        Set<Long> wl = inserted.stream().map(AbcEntity::getId).collect(Collectors.toSet());
        List<GroupResult> got = invokeAggregate(
                m, AbcEntity.class, "idx_abc",
                new String[]{"a"},
                new String[]{"SUM"}, new String[]{"c"},
                null, wl);
        Assert.assertEquals("group 数 = baseline", baselineSum.size(), got.size());
        for (GroupResult gr : got) {
            BigInteger exp = baselineSum.get(gr.key);
            Assert.assertNotNull("key 在 baseline: " + gr.key, exp);
            Number s = gr.values.get("SUM:c");
            Assert.assertNotNull("SUM:c 存在", s);
            Assert.assertEquals("SUM(c) 匹配 key=" + gr.key,
                    0, exp.compareTo(BigInteger.valueOf(s.longValue())));
        }
    }

    // ---- TR-4.3 t03：MIN(c) + MAX(c) GROUP BY (a,b) ----
    @Test
    public void t03_minMaxCGroupByAbEqualsFullScanBaseline() throws Exception {
        GenericManager<AbcEntity> m = new GenericManager<>(AbcEntity.class);
        ensureInitFor(m);
        int N = 800;
        Random r = new Random(33L);
        List<AbcEntity> inserted = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            e.a = r.nextInt(8);
            e.b = "gb" + String.format("%02d", r.nextInt(12));
            e.c = r.nextInt();
            e.name = "n" + i;
            m.saveOrUpdate(e);
            inserted.add(e);
        }
        Map<List<Object>, int[]> baseline = new HashMap<>();
        for (AbcEntity e : inserted) {
            List<Object> k = Arrays.asList(e.a, e.b);
            int[] cur = baseline.computeIfAbsent(k, x -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            if (e.c < cur[0]) cur[0] = e.c;
            if (e.c > cur[1]) cur[1] = e.c;
        }
        Set<Long> wl = inserted.stream().map(AbcEntity::getId).collect(Collectors.toSet());
        List<GroupResult> got = invokeAggregate(
                m, AbcEntity.class, "idx_abc",
                new String[]{"a", "b"},
                new String[]{"MIN", "MAX"}, new String[]{"c", "c"},
                null, wl);
        Assert.assertEquals("group 数 = baseline", baseline.size(), got.size());
        for (GroupResult gr : got) {
            int[] exp = baseline.get(gr.key);
            Assert.assertNotNull("key 在 baseline: " + gr.key, exp);
            Number mn = gr.values.get("MIN:c");
            Number mx = gr.values.get("MAX:c");
            Assert.assertNotNull("MIN:c 存在", mn);
            Assert.assertNotNull("MAX:c 存在", mx);
            Assert.assertEquals("MIN(c) 匹配", exp[0], mn.intValue());
            Assert.assertEquals("MAX(c) 匹配", exp[1], mx.intValue());
        }
    }
}
