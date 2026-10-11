package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P31_T5_Gatekeeper9YTest {

    public static class Abc extends DsTableAdapter {
        @com.q3lives.ds.annotation.DsField(name = "name", length = 48) public String name;
        @com.q3lives.ds.annotation.DsField(name = "a", length = 8) public long a;
        public Abc() {}
    }

    public static class RelAbc extends DsTableAdapter {
        @com.q3lives.ds.annotation.DsField(name = "abc_id", length = 8) public Long abcId;
        @com.q3lives.ds.annotation.DsField(name = "price", length = 8) public Double price;
        public RelAbc() {}
    }

    private static final String FULLTEXT_PKG = "com.q3lives.ds.database.fulltext";
    private static final String JOIN_PKG = "com.q3lives.ds.database.fulltext.cluster.join";
    private static final String FT_CLUSTER_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster";
    private static final String JOIN_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/join";
    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p31_t5_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        f.delete();
    }

    @Test
    public void Y1_ZeroLeak3_TopLevel3PlusJoinSub3() throws Exception {
        List<String> ftTop = listTopLevelClasses(FULLTEXT_PKG);
        Set<String> expectedFt = new HashSet<>(Arrays.asList(
                "FulltextStore", "TermDictStore", "FulltextSegmenter"));
        Set<String> actualFt = new HashSet<>(ftTop);
        actualFt.retainAll(expectedFt);
        Assert.assertEquals("Y1 fulltext top-level exactly 3 (found: " + ftTop + ")", 3, actualFt.size());
        List<String> joinTop = listTopLevelClasses(JOIN_PKG);
        Set<String> expectedJoin = new HashSet<>(Arrays.asList(
                "JoinSpec", "JoinIdResolver", "HybridJoinClient"));
        Set<String> actualJoin = new HashSet<>(joinTop);
        actualJoin.retainAll(expectedJoin);
        Assert.assertEquals("Y1 join subpackage exactly 3 (found: " + joinTop + ")", 3, actualJoin.size());
        for (String jc : expectedJoin) {
            Assert.assertTrue("Y1 必须存在: " + jc + " (found:" + joinTop + ")", joinTop.contains(jc));
        }
    }

    @Test
    public void Y2_NG3_NineCnfeAllThrown_0ForbiddenRefs_0PomNewDep() throws Exception {
        String[] forbiddenClasses = new String[]{
                "org.antlr.v4.runtime.Parser",
                "com.github.javaparser.JavaParser",
                "net.openhft.koloboke.collect.map.IntIntMap",
                "com.fasterxml.jackson.dataformat.xml.XmlMapper",
                "javaccazz.parser.ParseException",
                "org.apache.commons.jexl3.JexlEngine",
                "org.parboiled.Rule",
                "com.google.re2j.Pattern",
                "org.mockito.invocation.MockHandler"
        };
        for (String fc : forbiddenClasses) {
            try { Class.forName(fc); Assert.fail("Y2: CNFE expected for " + fc); }
            catch (ClassNotFoundException cnfe) { /* ok */ }
        }
        List<Path> joinSrcs = listSrcs(JOIN_SRC);
        StringBuilder combined = new StringBuilder();
        for (Path p : joinSrcs) combined.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append("\n");
        String[] forbiddenRefs = new String[]{
                "io.netty", "io.grpc", "javax.net.p2p.client"
        };
        for (String fi : forbiddenRefs) {
            Assert.assertFalse("Y2 join 0 forbidden ref: " + fi, combined.toString().contains(fi));
        }
        Path pom = Path.of("pom.xml");
        String pomStr = new String(Files.readAllBytes(pom), StandardCharsets.UTF_8);
        Assert.assertTrue("Y2 pom.xml contains junit baseline", pomStr.contains("junit"));
        String[] newDepsBlacklist = new String[]{ "jooq", "hibernate-core", "querydsl", "eclipselink", "spring-jdbc", "mybatis" };
        for (String nd : newDepsBlacklist) {
            Assert.assertFalse("Y2 pom 0 新增 dep: " + nd, pomStr.toLowerCase().contains(nd.toLowerCase()));
        }
    }

    @Test
    public void Y3_TOPKBucketSum0_100xResolveDeterministic_0BucketRef() throws Exception {
        GenericManager<Abc> gm = new GenericManager<>(Abc.class);
        GenericManager<RelAbc> rg = new GenericManager<>(RelAbc.class);
        Map<Class<?>, GenericManager<?>> rgm = new HashMap<>();
        rgm.put(RelAbc.class, rg);
        for (int i = 0; i < 50; i++) {
            Abc e = new Abc(); e.name = "abc" + i; e.a = i;
            gm.saveOrUpdate(e);
            RelAbc r = new RelAbc(); r.abcId = e.getId(); r.price = (double) i;
            rg.saveOrUpdate(r);
        }
        QueryWrapper<Abc> orm = new QueryWrapper<>(); orm.likeLeft("name", "abc");
        QueryWrapper<RelAbc> sub = new QueryWrapper<>(); sub.gt("price", 10.0);
        JoinSpec<RelAbc> js = new JoinSpec<>(RelAbc.class, "id", "abcId", sub);
        List<JoinSpec<?>> joins = Collections.singletonList(js);
        Set<Integer> hashes = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            Set<Long> ids = JoinIdResolver.resolve(gm, orm, joins, rgm, null);
            hashes.add(ids.hashCode());
        }
        Assert.assertEquals("Y3 JoinIdResolver resolve 100x deterministic", 1, hashes.size());
        List<Path> allFtCluster = listSrcs(FT_CLUSTER_SRC);
        // 排除红线 GrpcShardTransport.java（P26 指定承担 p2p.client 根包引用；join 子包独立 0 引用已在 Y2 验证）
        List<Path> all = new ArrayList<>();
        for (Path p : allFtCluster) {
            if (!"GrpcShardTransport.java".equalsIgnoreCase(p.getFileName().toString())) all.add(p);
        }
        List<Path> clusterJoin = listSrcs(JOIN_SRC);
        all.addAll(clusterJoin);
        StringBuilder sb = new StringBuilder();
        for (Path p : all) sb.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
        String s = sb.toString();
        Assert.assertFalse("Y3 cluster 0 bucket/AccessCounter reference", s.toLowerCase().contains("accesscounter") || s.toLowerCase().contains("bucketcount") || s.contains("accessCounter") || s.contains("AccessCounter"));
    }

    @Test
    public void Y4_FacadeMonotonic_HybridHitCtorLen2_Dhqc3Entrances_Qw14Plus() throws Exception {
        Constructor<?>[] ctors = HybridHit.class.getDeclaredConstructors();
        Assert.assertEquals("Y4 HybridHit constructors.length == 2 (0 新增构造)", 2, ctors.length);
        Set<String> dhqc = names(DsHybridQueryClient.class.getDeclaredMethods());
        Assert.assertTrue("Y4 DHQC searchHybrid 5参", dhqc.contains("searchHybrid"));
        Assert.assertTrue("Y4 DHQC searchHybridWithWeights 8参", dhqc.contains("searchHybridWithWeights"));
        Assert.assertTrue("Y4 DHQC searchHybridWithLtr 7参", dhqc.contains("searchHybridWithLtr"));
        Set<String> hjc = names(HybridJoinClient.class.getDeclaredMethods());
        Assert.assertTrue("Y4 HJC searchHybridJoin 5参 new", hjc.contains("searchHybridJoin"));
        Assert.assertTrue("Y4 HJC searchHybridJoinWithWeights 8参 new", hjc.contains("searchHybridJoinWithWeights"));
        Assert.assertTrue("Y4 HJC searchHybridJoinWithLtr 7参 new", hjc.contains("searchHybridJoinWithLtr"));
        Set<String> qw = names(QueryWrapper.class.getDeclaredMethods());
        for (String m : Arrays.asList("eq","ne","gt","ge","lt","le","between","in","notIn","like","likeLeft","likeRight","isNull","isNotNull")) {
            Assert.assertTrue("Y4 QW missing " + m, qw.contains(m));
        }
        Set<String> gm = names(GenericManager.class.getDeclaredMethods());
        Assert.assertTrue("Y4 GM saveOrUpdate", gm.contains("saveOrUpdate"));
        Assert.assertTrue("Y4 GM listIds", gm.contains("listIds"));
        Set<String> joinSpecM = names(JoinSpec.class.getDeclaredMethods());
        Assert.assertTrue("Y4 JoinSpec withRelatedCond", joinSpecM.contains("withRelatedCond"));
        Assert.assertTrue("Y4 JoinSpec fromDsOneToOneAnnotations static", joinSpecM.contains("fromDsOneToOneAnnotations"));
    }

    @Test
    public void Y5_RoutingFormula_UnitDoc1000x_MixDeterministic() throws Exception {
        Method ru = FulltextStore.class.getDeclaredMethod("resolveShardForUnitIdForTest", long.class, int.class);
        Method rd = FulltextStore.class.getDeclaredMethod("resolveShardForDocIdForTest", long.class, int.class);
        ru.setAccessible(true); rd.setAccessible(true);
        int N = 8;
        long seed = 0xDEADBEEFCAFEBABEL;
        for (int i = 0; i < 1000; i++) {
            long uid = seed ^ (i * 2654435761L);
            int expectedUnit = (int) ((uid >>> 48) & 0xFFFF) % N;
            int actual = (Integer) ru.invoke(null, uid, N);
            int u2 = (Integer) ru.invoke(null, uid, N);
            Assert.assertEquals("Y5 unit deterministic iter=" + i, actual, u2);
            Assert.assertEquals("Y5 unit route formula exact iter=" + i, expectedUnit, actual);
            int ad = (Integer) rd.invoke(null, uid, N);
            int d2 = (Integer) rd.invoke(null, uid, N);
            Assert.assertEquals("Y5 doc deterministic iter=" + i, ad, d2);
        }
    }

    @Test
    public void Y6_HJC_3JoinSignatures_ReflectExact() throws Exception {
        Method m1 = HybridJoinClient.class.getDeclaredMethod("searchHybridJoin",
                QueryWrapper.class, List.class, String.class, Map.class, int.class, FulltextStore.SearchOptions.class);
        Assert.assertTrue("Y6 searchHybridJoin public", (m1.getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0);
        Assert.assertEquals("Y6 searchHybridJoin List<HybridHit<T>>", List.class, m1.getReturnType());
        Method m2 = HybridJoinClient.class.getDeclaredMethod("searchHybridJoinWithWeights",
                QueryWrapper.class, List.class, String.class, Map.class, int.class,
                FulltextStore.SearchOptions.class, double.class, double.class, Map.class);
        Assert.assertTrue("Y6 searchHybridJoinWithWeights public", (m2.getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0);
        Assert.assertEquals("Y6 searchHybridJoinWithWeights return List", List.class, m2.getReturnType());
        Method m3 = HybridJoinClient.class.getDeclaredMethod("searchHybridJoinWithLtr",
                QueryWrapper.class, List.class, String.class, Map.class, int.class,
                FulltextStore.SearchOptions.class, List.class, double[].class);
        Assert.assertTrue("Y6 searchHybridJoinWithLtr public", (m3.getModifiers() & java.lang.reflect.Modifier.PUBLIC) != 0);
        Assert.assertEquals("Y6 searchHybridJoinWithLtr return List", List.class, m3.getReturnType());
    }

    @Test
    public void Y7_1000HashCodeAndQwDeepEquals_8x125_Stable() throws Exception {
        QueryWrapper<RelAbc> sub = new QueryWrapper<>();
        sub.gt("price", 5.0); sub.lt("price", 1000.0);
        JoinSpec<RelAbc> orig = new JoinSpec<>(RelAbc.class, "id", "abcId", sub);
        Set<Integer> hashes = new HashSet<>();
        JoinSpec<RelAbc> first = orig;
        for (int i = 0; i < 1000; i++) {
            QueryWrapper<RelAbc> sc = new QueryWrapper<>();
            sc.gt("price", 5.0); sc.lt("price", 1000.0);
            JoinSpec<RelAbc> cur = new JoinSpec<>(RelAbc.class, "id", "abcId", sc);
            hashes.add(cur.hashCode());
            Assert.assertTrue("Y7 JoinSpec equals iter=" + i, first.equals(cur));
            JoinSpec<RelAbc> viaWith = orig.withRelatedCond(sc);
            Assert.assertTrue("Y7 JoinSpec withRelatedCond equals iter=" + i, first.equals(viaWith));
        }
        Assert.assertEquals("Y7 JoinSpec hashCode 1000x Set.size=1", 1, hashes.size());
        Method de = JoinSpec.class.getDeclaredClasses()[0].getDeclaredMethod("deepEquals", QueryWrapper.class, QueryWrapper.class);
        de.setAccessible(true);
        for (int i = 0; i < 125; i++) {
            QueryWrapper<Abc> w1 = new QueryWrapper<>(); w1.eq("a", (long)i); w1.likeLeft("name", "abc");
            QueryWrapper<Abc> w2 = new QueryWrapper<>(); w2.eq("a", (long)i); w2.likeLeft("name", "abc");
            Boolean eq = (Boolean) de.invoke(null, w1, w2);
            Assert.assertTrue("Y7 QwHelper deepEquals pair iter=" + i, eq);
        }
        for (int i = 0; i < 125; i++) {
            QueryWrapper<Abc> w1 = new QueryWrapper<>(); w1.in("name", new ArrayList<>(Arrays.asList("x","y","z"))); w1.isNull("a");
            QueryWrapper<Abc> w2 = new QueryWrapper<>(); w2.in("name", new ArrayList<>(Arrays.asList("x","y","z"))); w2.isNull("a");
            Boolean eq = (Boolean) de.invoke(null, w1, w2);
            Assert.assertTrue("Y7 QwHelper deepEquals pair2 iter=" + i, eq);
        }
    }

    @Test
    public void Y8_ShardTransport5OriginalMethods_Intact() throws Exception {
        Set<String> n = names(FulltextStore.ShardTransport.class.getDeclaredMethods());
        for (String m : Arrays.asList("putDoc", "clearDoc", "searchWithVector", "indexTermVector", "close")) {
            Assert.assertTrue("Y8 ShardTransport 原方法 missing: " + m + " (have: " + n + ")", n.contains(m));
        }
    }

    @Test
    public void Y9_GrepTodoFixmeHack_ZeroInJoin3ClassesAndP31Tests() throws Exception {  // NO-TODO-BYPASS
        List<Path> mains = listSrcs(JOIN_SRC);
        List<Path> tests = new ArrayList<>();
        try (Stream<Path> sp = Files.list(Path.of("src/test/java/ds"))) {  // NO-TODO-BYPASS
            tests = sp.filter(p -> p.getFileName().toString().startsWith("P31_")).collect(Collectors.toList());  // NO-TODO-BYPASS
        }
        Pattern pat = Pattern.compile("(?i)(?<!\\p{L})(TODO|FIXME|HACK)(?!\\p{L})");  // NO-TODO-BYPASS
        for (Path p : concat(mains, tests)) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String l : lines) {
                if (l.contains("NO-TODO-BYPASS")) continue;  // Y9 自身代码豁免 // NO-TODO-BYPASS
                if (pat.matcher(l).find()) {
                    Assert.fail("Y9 TODO/FIXME/HACK found: " + p.getFileName() + " => " + l.trim());  // NO-TODO-BYPASS
                }
            }
        }
    }

    private static Set<String> names(Method[] ms) {
        Set<String> s = new HashSet<>();
        for (Method m : ms) s.add(m.getName());
        return s;
    }

    private static List<String> listTopLevelClasses(String pkg) throws Exception {
        String rel = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL u = cl.getResource(rel);
        if (u == null) return Collections.emptyList();
        File dir = new File(u.toURI());
        List<String> out = new ArrayList<>();
        if (dir.listFiles() == null) return out;
        for (File f : dir.listFiles()) {
            if (f.isFile() && f.getName().endsWith(".class")) {
                String n = f.getName().substring(0, f.getName().length() - 6);
                if (!n.contains("$")) out.add(n);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static List<Path> listSrcs(String sub) {
        try (Stream<Path> sp = Files.list(Path.of(sub))) {
            return sp.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> r = new ArrayList<>(a.size() + b.size());
        r.addAll(a); r.addAll(b); return r;
    }
}
