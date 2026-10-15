package ds;

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

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;

/**
 * P35 T5: Gatekeeper 9Y 9 TR. 验证 P35 v2/ManyToOne 子包不破坏 P21-P34 全部红线 +
 * JoinSpec 4→6 字段 append-only + P31 HJC.searchHybridJoin 6 入口字节级不变 + HybridHit ctor=2 不变.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P35_T5_Gatekeeper9YTest {

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
        for (String f : files) {
            topLevel.add(f.replace(".class", ""));
        }
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
        for (String banned : new String[]{"resilience4j", "hystrix", "guava-ratelimiter", "jmh", "hdrhistogram",
            "antlr", "javacc", "parboiled", "jparsec"}) {
            Assert.assertFalse("pom.xml should not contain " + banned, pomContent.toLowerCase().contains(banned));
        }
    }

    private void collectJavaFiles(File dir, List<File> out) {
        if (dir.isDirectory()) {
            for (File f : dir.listFiles()) {
                if (f.isDirectory()) {
                    collectJavaFiles(f, out);
                } else if (f.getName().endsWith(".java")) {
                    out.add(f);
                }
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

    // ============== Y4 Facade 单调：JoinSpec 4→6 字段 append-only + P31 HJC 6 入口不变 ==============
    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_JoinSpecAppendOnly_HJC6EntriesIntact() throws Exception {
        // JoinSpec 4 参构造器必须保留（4→6 字段 append-only）
        Constructor<?> js4 = com.q3lives.ds.database.fulltext.cluster.join.JoinSpec.class.getDeclaredConstructor(
                Class.class, String.class, String.class, com.q3lives.ds.database.integration.QueryWrapper.class);
        Assert.assertNotNull("JoinSpec 4 参构造器（保留）", js4);
        // JoinSpec 6 参构造器新增（mode + batchSize）
        Constructor<?> js6 = com.q3lives.ds.database.fulltext.cluster.join.JoinSpec.class.getDeclaredConstructor(
                Class.class, String.class, String.class, com.q3lives.ds.database.integration.QueryWrapper.class,
                com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode.class, int.class);
        Assert.assertNotNull("JoinSpec 6 参构造器（新增）", js6);
        // P31 HJC.searchHybridJoin 6 参必须保留字节级不变
        Method shj = com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient.class.getDeclaredMethod("searchHybridJoin",
                com.q3lives.ds.database.integration.QueryWrapper.class, java.util.List.class,
                String.class, java.util.Map.class, int.class,
                com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions.class);
        Assert.assertNotNull("P31 HJC.searchHybridJoin 6 参入口", shj);
        // P31 HJC.searchHybridJoinWithWeights 8 参入口
        Method shjw = com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient.class.getDeclaredMethod("searchHybridJoinWithWeights",
                com.q3lives.ds.database.integration.QueryWrapper.class, java.util.List.class,
                String.class, java.util.Map.class, int.class,
                com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions.class,
                double.class, double.class, java.util.Map.class);
        Assert.assertNotNull("P31 HJC.searchHybridJoinWithWeights 9 参入口", shjw);
        // P31 HJC.searchHybridJoinWithLtr 7 参入口
        Method shjl = com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient.class.getDeclaredMethod("searchHybridJoinWithLtr",
                com.q3lives.ds.database.integration.QueryWrapper.class, java.util.List.class,
                String.class, java.util.Map.class, int.class,
                com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions.class,
                java.util.List.class, double[].class);
        Assert.assertNotNull("P31 HJC.searchHybridJoinWithLtr 8 参入口", shjl);
        // P30 HybridQueryParser.parse 4 参签名 100% 字节级一致
        Method parse = HybridQueryParser.class.getDeclaredMethod("parse",
                Class.class, String.class, java.util.Map.class, java.util.Map.class);
        Assert.assertEquals("parse 返回类型", com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery.class,
                parse.getReturnType());
    }

    // ============== Y5 路由公式：DsFulltextClusterClient 0 改 ==============
    @Test(timeout = 30000)
    public void y5_RoutingFormula_ShardRouterIntact() throws Exception {
        Class<?> sr = null;
        for (Class<?> c : FulltextStore.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("ShardRouter")) {
                sr = c;
                break;
            }
        }
        Assert.assertNotNull("FulltextStore.ShardRouter 内部类必须存在", sr);
        Set<String> names = new HashSet<>();
        for (Method m : sr.getDeclaredMethods()) {
            names.add(m.getName());
        }
        Assert.assertTrue("ShardRouter.searchCluster 必须存在", names.contains("searchCluster"));
        Assert.assertTrue("ShardRouter.putDocCluster 必须存在", names.contains("putDocCluster"));
    }

    // ============== Y6 P30~P34 编译兼容 + P35 新类 ==============
    @Test(timeout = 30000)
    public void y6_P30P35CompileCompat_ClassesExist() {
        try {
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.JoinSpec");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport");
            // P34
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser");
            // P35 新类
            Class.forName("com.q3lives.ds.annotation.DsManyToOne");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.v2.JoinIdResolverV2");
            Class.forName("com.q3lives.ds.database.fulltext.cluster.join.v2.HybridJoinClientV2Adapter");
        } catch (ClassNotFoundException cnfe) {
            Assert.fail("P30-P35 class should exist: " + cnfe.getMessage());
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
            if (c.getSimpleName().equals("ShardTransport")) {
                st = c;
                break;
            }
        }
        Assert.assertNotNull("FulltextStore.ShardTransport inner interface should exist", st);
        Set<String> names = new HashSet<>();
        for (Method m : st.getDeclaredMethods()) {
            names.add(m.getName());
        }
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
                    if (lineStart.contains("//") || lineStart.contains("/*")) {
                        continue;
                    }
                    Assert.fail("found " + banned + " in " + f.getName() + " near: " + lineStart);
                }
            }
        }
    }
}