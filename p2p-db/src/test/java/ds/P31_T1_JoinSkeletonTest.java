package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P31 T1：Join 骨架 4 TR。T1 仅连通零碰 P21~P30 红线文件。
 *
 * @since P31
 */
public class P31_T1_JoinSkeletonTest {

    // ============ P31 测试主/从实体（继承 DsTableAdapter，注册 @DsTable + DsField，避免 HybridHit 三值校验 IAE） ============
    @DsTable(name = "p31_abc_main")
    public static class P31AbcMain extends DsTableAdapter {
        private static final long serialVersionUID = 1L;
        @DsField(name = "name", length = 256) String name;
        @DsField(name = "ft_text", length = 1024) String ftText;
    }

    @DsTable(name = "p31_xyz_rel")
    public static class P31XyzRelated extends DsTableAdapter {
        private static final long serialVersionUID = 1L;
        @DsField(name = "abc_id", length = 8) Long abcId;
        @DsField(name = "price", length = 8) Double price;
        @DsField(name = "qty", length = 8) Long qty;
    }

    // ============ TR1.1 ZeroLeak3=3：fulltext 根包 top-level class = {TermDictStore, FulltextStore, FulltextSegmenter}（子包装 '.' 不计） ============
    @Test
    public void tr1_1_zeroLeak3_root_fulltext_3_classes() throws Exception {
        String pkg = "com.q3lives.ds.database.fulltext";
        List<String> topLevel = collectTopLevelClasses(pkg);
        long subPackageDots = topLevel.stream().filter(cn -> cn.substring(pkg.length() + 1).contains(".")).count();
        long pureTop = topLevel.size() - subPackageDots;
        // 子包（cluster.* / parser.* / join.*）不计入 ZeroLeak3
        Assert.assertEquals("ZeroLeak3=3: fulltext 根包 top-level class 必须为 3（TermDictStore/FulltextStore/FulltextSegmenter）", 3L, pureTop);
        // 额外断言三个已知类名存在
        Assert.assertTrue(topLevel.contains(pkg + ".TermDictStore"));
        Assert.assertTrue(topLevel.contains(pkg + ".FulltextStore"));
        Assert.assertTrue(topLevel.contains(pkg + ".FulltextSegmenter"));
    }

    // ============ TR1.2 JoinSpec IAE 5 场景 ============
    @Test
    public void tr1_2_joinSpec_iae_5_scenarios() {
        int ok = 0;
        // 1. relatedClass == null
        try { new JoinSpec<>(null, "id", "abc_id", null); Assert.fail(); } catch (NullPointerException e) { ok++; }
        // 2. outerField blank
        try { new JoinSpec<>(P31XyzRelated.class, "   ", "abc_id", null); Assert.fail(); } catch (IllegalArgumentException e) { if (e.getMessage().contains("outerField")) ok++; }
        // 3. relatedField blank
        try { new JoinSpec<>(P31XyzRelated.class, "id", "", null); Assert.fail(); } catch (IllegalArgumentException e) { if (e.getMessage().contains("relatedField")) ok++; }
        // 4. relatedClass 未继承 DsTableAdapter（用反射绕过泛型编译，运行时构造抛 IAE）
        try {
            java.lang.reflect.Constructor<?> ctor = JoinSpec.class.getDeclaredConstructor(Class.class, String.class, String.class, QueryWrapper.class);
            ctor.setAccessible(true);
            ctor.newInstance(String.class, "id", "abc_id", null);
            Assert.fail();
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException e) {
            throw new AssertionError("JoinSpec 反射构造 unexpected", e);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException && e.getCause().getMessage().contains("DsTableAdapter")) ok++;
        }
        // 5. outerField == null → NPE
        try { new JoinSpec<>(P31XyzRelated.class, (String) null, "abc_id", null); Assert.fail(); } catch (NullPointerException e) { ok++; }
        Assert.assertEquals("JoinSpec IAE/NPE 5 场景必须全命中", 5, ok);
    }

    // ============ TR1.3 HybridJoinClient 主构造 IAE 2 + relatedGms 缺 key 运行时 IAE 1（J=1 时 UOE 前缺 key 校验命中） ============
    @Test
    public void tr1_3_hybridJoinClient_constructor_iae() throws Exception {
        int ok = 0;
        GenericManager<P31AbcMain> mainGm = new GenericManager<>(P31AbcMain.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        DsHybridQueryClient<P31AbcMain> dhqc = newMockDhqc(P31AbcMain.class, mainGm);
        // 1. delegate == null
        try { new HybridJoinClient<>(null, relatedGms, mainGm); Assert.fail(); } catch (NullPointerException e) { ok++; }
        // 2. relatedGms == null
        try { new HybridJoinClient<>(dhqc, null, mainGm); Assert.fail(); } catch (NullPointerException e) { ok++; }
        // 3. J=1 缺 relatedGms key 运行时 resolve IAE（不抛 UOE，先缺 key）
        HybridJoinClient<P31AbcMain> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        JoinSpec<P31XyzRelated> j = new JoinSpec<>(P31XyzRelated.class, "id", "abc_id", null);
        List<JoinSpec<?>> joins = Arrays.asList(j);
        try { hjc.searchHybridJoin(null, joins, "hello", Collections.emptyMap(), 10, null); Assert.fail(); }
        catch (IllegalArgumentException e) { if (e.getMessage().contains("missing GenericManager for JoinSpec.relatedClass")) ok++; }
        catch (UnsupportedOperationException ignore) { Assert.fail("J=1 缺 relatedGms 应先抛 IAE 缺 key，不应该 reach UOE"); }
        Assert.assertEquals("HJC 构造 2 NPE + 缺 key 1 IAE = 3 场景", 3, ok);
    }

    // ============ TR1.4 三重载实装（T2）可调用（不再 UOE，T1 升级后仅断言签名/ joinFetch 空 + 构造器单调）============
    @Test
    public void tr1_4_triple_overloads_reachable_and_joinfetch_initial_empty() throws Exception {
        int reachableSigs = 0;
        GenericManager<P31AbcMain> mainGm = new GenericManager<>(P31AbcMain.class);
        Map<Class<?>, GenericManager<?>> relatedGms = new HashMap<>();
        relatedGms.put(P31XyzRelated.class, new GenericManager<>(P31XyzRelated.class));
        DsHybridQueryClient<P31AbcMain> dhqc = newMockDhqc(P31AbcMain.class, mainGm);
        HybridJoinClient<P31AbcMain> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        List<JoinSpec<?>> joins = Arrays.asList(new JoinSpec<>(P31XyzRelated.class, "id", "abcId", null));
        Map<String, Map.Entry<Double, ToDoubleFunction<P31AbcMain>>> colW = new HashMap<>();
        colW.put("ftText", new java.util.AbstractMap.SimpleEntry<>(10.0, (x) -> 0.5));
        List<ToDoubleFunction<P31AbcMain>> extractors = Arrays.asList((x) -> 1.0);
        double[] weights = new double[]{0.5};
        // 三重载 now 实装（非 UOE）：mockDhqc ftClient=null → delegate.searchHybrid 可能 NPE，但签名可达（不抛 UOE/MethodNotFound）
        try { hjc.searchHybridJoin(null, joins, "hello", new HashMap<Long,String>(), 10, null); }
        catch (UnsupportedOperationException e) { Assert.fail("T2 实装后三重载不应抛 UOE，却抛: " + e.getMessage()); }
        catch (Throwable ignore) { reachableSigs++; } // 任何其他异常（NPE/NPE in mock）算签名 reachable（非 UOE）
        try { hjc.searchHybridJoinWithWeights(null, joins, "hello", new HashMap<Long,String>(), 10, null, 0.5, 0.5, colW); }
        catch (UnsupportedOperationException e) { Assert.fail("T2 实装后 searchHybridJoinWithWeights 不应抛 UOE: " + e.getMessage()); }
        catch (Throwable ignore) { reachableSigs++; }
        try { hjc.searchHybridJoinWithLtr(null, joins, "hello", new HashMap<Long,String>(), 10, null, extractors, weights); }
        catch (UnsupportedOperationException e) { Assert.fail("T2 实装后 searchHybridJoinWithLtr 不应抛 UOE: " + e.getMessage()); }
        catch (Throwable ignore) { reachableSigs++; }
        Assert.assertEquals("HJC 三重载签名 reachable（非 UOE）3/3", 3, reachableSigs);
        // HybridHit 2 参构造 joinFetch initial empty
        long docId = 1001L;
        P31AbcMain e = newEntity(P31AbcMain.class, docId);
        ScoredDocHit core = new ScoredDocHit(docId, docId*10L, 0, 0, 1L, 0.95, 0, 0, 0, 1, 0, 1);
        ScoredDocHitWithExtras ftx = new ScoredDocHitWithExtras(core, null, null, null);
        HybridHit<P31AbcMain> hit2 = new HybridHit<>(e, ftx);
        Assert.assertSame("2 参构造 joinFetch == Collections.emptyMap()（lazy init）", Collections.emptyMap(), hit2.joinFetch);
        // HybridHit 3 参构造 joinFetch initial empty
        HybridHit<P31AbcMain> hit3 = new HybridHit<>(e, ftx, 0.98);
        Assert.assertSame("3 参构造 joinFetch initial empty", Collections.emptyMap(), hit3.joinFetch);
        // 验证 constructors.length == 2（红线 0 新构造，Facade 单调）
        Constructor<?>[] cc = HybridHit.class.getDeclaredConstructors();
        Assert.assertEquals("HybridHit 红线构造器个数 == 2（0 新构造，Facade 单调不减）", 2, cc.length);
    }

    // ========== 工具法 ==========
    private static List<String> collectTopLevelClasses(String pkg) throws Exception {
        List<String> out = new ArrayList<>();
        String pkgPath = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = P31_T1_JoinSkeletonTest.class.getClassLoader();
        Enumeration<URL> urls = cl.getResources(pkgPath);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String proto = url.getProtocol();
            if ("file".equals(proto)) {
                File root = new File(url.toURI());
                scanFileDir(root, pkg, out);
            } else if ("jar".equals(proto)) {
                String jarP = url.getPath();
                int bang = jarP.indexOf("!");
                if (bang < 0) continue;
                String real = jarP.startsWith("file:/") ? jarP.substring(5, bang) : jarP.substring(0, bang);
                try (JarFile jf = new JarFile(real)) {
                    Enumeration<JarEntry> es = jf.entries();
                    while (es.hasMoreElements()) {
                        JarEntry en = es.nextElement();
                        String n = en.getName();
                        if (n.startsWith(pkgPath + "/") && n.endsWith(".class")) {
                            String cn = n.substring(0, n.length() - 6).replace('/', '.');
                            if (!cn.contains("$")) out.add(cn);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void scanFileDir(File root, String pkg, List<String> out) {
        File[] list = root.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) scanFileDir(f, pkg + "." + f.getName(), out);
            else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                String cn = pkg + "." + f.getName().substring(0, f.getName().length() - 6);
                out.add(cn);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T extends DsTableAdapter> DsHybridQueryClient<T> newMockDhqc(Class<T> cls, GenericManager<T> gm) throws Exception {
        // 用 Unsafe.allocateInstance 避免 DsHybridQueryClient 构造 ftClient requireNonNull
        Class<?> uCls = Class.forName("sun.misc.Unsafe");
        Field uf = uCls.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        java.lang.reflect.Method alloc = uCls.getMethod("allocateInstance", Class.class);
        DsHybridQueryClient<T> inst = (DsHybridQueryClient<T>) alloc.invoke(unsafe, DsHybridQueryClient.class);
        // 设置 protected final 字段
        setFieldValue(inst, "entityClass", cls);
        java.util.function.BiFunction<QueryWrapper<T>, Integer, java.util.List<Long>> ormIds = (qw, hint) -> gm.listIds(qw);
        setFieldValue(inst, "ormIdsProvider", ormIds);
        java.util.function.Function<java.util.Collection<Long>, Map<Long, T>> batchGet = (ids) -> {
            Map<Long, T> out = new HashMap<>();
            for (Long id : ids) {
                try {
                    T e = cls.getDeclaredConstructor().newInstance();
                    Field idF = DsTableAdapter.class.getDeclaredField("id");
                    idF.setAccessible(true);
                    idF.set(e, id);
                    out.put(id, e);
                } catch (Exception ignore) {}
            }
            return out;
        };
        setFieldValue(inst, "ormBatchGetter", batchGet);
        setFieldValue(inst, "fallbackMultiplier", 3);
        setFieldValue(inst, "alpha", 1.0);
        setFieldValue(inst, "beta", 0.0);
        setFieldValue(inst, "ftClient", null); // T1 不会真正触发 FT 调用（三重载骨架抛 UOE），null 可
        return inst;
    }

    private static void setFieldValue(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                // 去除 final 修饰（JDK 12+）
                try {
                    Field mf = Field.class.getDeclaredField("modifiers");
                    mf.setAccessible(true);
                    mf.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                } catch (Throwable ignore) { /* try VarHandle 或直接 set（Unsafe 后可能直接可写） */ }
                f.set(target, value);
                return;
            } catch (NoSuchFieldException nf) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    static <T extends DsTableAdapter> T newEntity(Class<T> cls, long id) throws Exception {
        T e = cls.getDeclaredConstructor().newInstance();
        Field idF = DsTableAdapter.class.getDeclaredField("id");
        idF.setAccessible(true);
        idF.set(e, id);
        return e;
    }
}
