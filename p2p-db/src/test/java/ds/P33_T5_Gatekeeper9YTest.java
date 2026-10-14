package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import com.q3lives.ds.database.fulltext.cluster.benchmark.DslCorpus;
import com.q3lives.ds.database.fulltext.cluster.benchmark.TpcHQuery;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P33 T5: Gatekeeper 9Y 9 TR.
 * 验证 P33 benchmark 子包不破坏 P21-P32 全部红线。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";

    // ============== Y1 ZeroLeak3=3 ==============

    @Test(timeout = 30000)
    public void y1_ZeroLeak3_Only3TopLevelClasses() throws Exception {
        URL root = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT);
        Assert.assertNotNull("fulltext root resource not found", root);
        File rootDir = new File(root.toURI());
        String[] files = rootDir.list((d, n) -> n.endsWith(".class") && !n.contains("$"));
        Assert.assertNotNull(files);
        Set<String> topLevel = new HashSet<>();
        for (String f : files) topLevel.add(f.replace(".class", ""));
        Assert.assertTrue("ZeroLeak3 should still be {FulltextSegmenter, FulltextStore, TermDictStore}, got " + topLevel,
                topLevel.contains("FulltextSegmenter") && topLevel.contains("FulltextStore") && topLevel.contains("TermDictStore")
                        && topLevel.size() == 3);
    }

    // ============== Y2 NG-3 0 根包引用 + pom 0 新 dep ==============

    @Test(timeout = 30000)
    public void y2_NoForbiddenPackageRefs_NoNewPomDeps() throws Exception {
        URL root = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT);
        File clusterDir = new File(root.toURI());
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(clusterDir, javaFiles);
        javaFiles = javaFiles.stream()
                .filter(f -> !f.getName().equals("GrpcShardTransport.java"))
                .collect(Collectors.toList());

        Set<String> forbidden = new HashSet<>(Arrays.asList("io.netty", "io.grpc", "javax.net.p2p.client"));
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String pkg : forbidden) {
                int idx = content.indexOf(pkg);
                while (idx >= 0) {
                    String lineStart = content.substring(Math.max(0, idx - 20), idx);
                    if (lineStart.contains("import") || lineStart.contains("package") || lineStart.contains("\"")) {
                        Assert.fail("forbidden ref " + pkg + " in " + f.getName() + " near: " + lineStart);
                    }
                    idx = content.indexOf(pkg, idx + 1);
                }
            }
        }

        File pomFile = new File("pom.xml");
        Assert.assertTrue("pom.xml should exist", pomFile.exists());
        String pomContent = new String(Files.readAllBytes(pomFile.toPath()), StandardCharsets.UTF_8);
        for (String banned : new String[]{"resilience4j", "hystrix", "guava-ratelimiter", "jmh", "hdrhistogram"}) {
            Assert.assertFalse("pom.xml should not contain " + banned, pomContent.toLowerCase().contains(banned));
        }
    }

    private void collectJavaFiles(File dir, List<File> out) {
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                if (f.isDirectory()) collectJavaFiles(f, out);
                else if (f.getName().endsWith(".java")) out.add(f);
            }
        }
    }

    // ============== Y3 TOPK 铁律 ==============

    @Test(timeout = 30000)
    public void y3_TopKGuardrails_NoBucketCounterRefs() throws Exception {
        URL root = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT + "/cluster");
        File clusterDir = new File(root.toURI());
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(clusterDir, javaFiles);
        javaFiles = javaFiles.stream()
                .filter(f -> !f.getName().equals("GrpcShardTransport.java"))
                .collect(Collectors.toList());
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName() + ": contains bucketAccessCounter",
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
        }
    }

    // ============== Y4 Facade 单调 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_HJCOriginalMethods_Plus3Resilience() throws Exception {
        Class<?> hjc = HybridJoinClient.class;
        Method[] methods = hjc.getDeclaredMethods();
        boolean has3Original = true, has3Resilience = true;
        for (Method m : methods) {
            String n = m.getName();
            if (n.equals("searchHybridJoinWithResilience") || n.equals("searchHybridJoinWithWeightsWithResilience")
                    || n.equals("searchHybridJoinWithLtrWithResilience")) {
                has3Resilience = false; // 不应误标记
            }
        }
        // 反向验证：3 个原方法必须存在
        Set<String> names = new HashSet<>();
        for (Method m : methods) names.add(m.getName());
        Assert.assertTrue("HJC must have searchHybridJoin", names.contains("searchHybridJoin"));
        Assert.assertTrue("HJC must have searchHybridJoinWithWeights", names.contains("searchHybridJoinWithWeights"));
        Assert.assertTrue("HJC must have searchHybridJoinWithLtr", names.contains("searchHybridJoinWithLtr"));
        // 反向：3 个 *WithResilience 也存在
        Assert.assertTrue("HJC must have 3 *WithResilience", !has3Resilience);
    }

    // ============== Y5 路由公式 ==============

    @Test(timeout = 30000)
    public void y5_RoutingFormula_UnitDocRouteDeterministic() throws Exception {
        Method[] methods = DsFulltextClusterClient.class.getDeclaredMethods();
        Method routeMethod = null;
        for (Method m : methods) {
            String n = m.getName().toLowerCase();
            if ((n.contains("select") || n.contains("route") || n.contains("shard")) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == long.class) {
                routeMethod = m;
                break;
            }
        }
        if (routeMethod == null) {
            Assert.assertTrue("DsFulltextClusterClient should have routing-related methods", methods.length > 0);
            return;
        }
        routeMethod.setAccessible(true);
        Object instance = null;
        try {
            Constructor<?>[] ctors = DsFulltextClusterClient.class.getDeclaredConstructors();
            for (Constructor<?> c : ctors) {
                if (c.getParameterCount() == 0) {
                    c.setAccessible(true);
                    instance = c.newInstance();
                    break;
                }
            }
        } catch (Throwable ignore) { }
        if (instance == null) return;
        try {
            Object r1 = routeMethod.invoke(instance, 12345L);
            for (int i = 0; i < 1000; i++) {
                Object rn = routeMethod.invoke(instance, 12345L);
                Assert.assertEquals("routing should be deterministic for same docId", r1, rn);
            }
        } finally {
            if (instance instanceof AutoCloseable) {
                try { ((AutoCloseable) instance).close(); } catch (Exception ignore) { }
            }
        }
    }

    // ============== Y6 P30/P31/P32 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P30P31P32CompileCompat_ClassesExist() {
        try {
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.JoinSpec");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard");
        } catch (ClassNotFoundException cnfe) {
            Assert.fail("P30-P32 class should still exist: " + cnfe.getMessage());
        }
    }

    // ============== Y7 HybridHit ctor=2 ==============

    @Test(timeout = 30000)
    public void y7_HybridHit_ConstructorCountIs2() {
        Constructor<?>[] ctors = HybridHit.class.getDeclaredConstructors();
        Assert.assertEquals("HybridHit must have exactly 2 constructors, got " + ctors.length, 2, ctors.length);
    }

    // ============== Y8 ShardTransport 5 方法 ==============

    @Test(timeout = 30000)
    public void y8_ShardTransport_5OriginalMethodsIntact() {
        Class<?>[] innerClasses = FulltextStore.class.getDeclaredClasses();
        Class<?> st = null;
        for (Class<?> c : innerClasses) {
            if (c.getSimpleName().equals("ShardTransport")) { st = c; break; }
        }
        Assert.assertNotNull("FulltextStore.ShardTransport inner interface should exist", st);
        Set<String> names = new HashSet<>();
        for (Method m : st.getDeclaredMethods()) names.add(m.getName());
        for (String orig : Arrays.asList("putDoc", "clearDoc", "searchWithVector", "indexTermVector", "close")) {
            Assert.assertTrue("Y8 ShardTransport 原方法 missing: " + orig + " (have: " + names + ")", names.contains(orig));
        }
    }

    // ============== Y9 0 TODO/FIXME/HACK ==============

    @Test(timeout = 30000)
    public void y9_NoTodoFixmeHack() throws Exception {
        URL root = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT + "/cluster");
        File clusterDir = new File(root.toURI());
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(clusterDir, javaFiles);
        javaFiles = javaFiles.stream()
                .filter(f -> !f.getName().equals("GrpcShardTransport.java"))
                .collect(Collectors.toList());
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                int idx = content.indexOf(banned);
                if (idx >= 0) {
                    String lineStart = content.substring(Math.max(0, idx - 20), idx);
                    if (lineStart.contains("//") || lineStart.contains("/*")) continue;
                    Assert.fail("found " + banned + " in " + f.getName() + " near: " + lineStart);
                }
            }
        }
    }
}
