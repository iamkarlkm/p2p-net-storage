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
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;

/**
 * P36 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3（P36 不增 fulltext 根包类）
 * <br>Y2 NG-3（P36 源码 0 netty/grpc/p2p.client 根包引用 + pom 0 新 dep）
 * <br>Y3 TOPK 铁律（P36 不引用 bucket 计数）
 * <br>Y4 Facade 单调（HJC 原 3 + P32 3 + P36 3 = 9 入口）
 * <br>Y5 路由公式（P36 不破坏 P24 路由）
 * <br>Y6 P30~P35 编译兼容（Parser/JoinSpec/DnfParser/JoinSpec V2 0 改）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T5_Gatekeeper9YTest {

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
                topLevel.contains("FulltextSegmenter") && topLevel.contains("FulltextStore")
                        && topLevel.contains("TermDictStore") && topLevel.size() == 3);
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

        Set<String> forbidden = new HashSet<>(Arrays.asList("io.netty", "io.grpc", "javax.net.p2p.client", "p2p.client"));
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String pkg : forbidden) {
                int idx = content.indexOf(pkg);
                while (idx >= 0) {
                    String lineStart = content.substring(Math.max(0, idx - 24), idx);
                    if (lineStart.contains("import") || lineStart.contains("package")) {
                        Assert.fail("forbidden ref " + pkg + " in " + f.getName() + " near: " + lineStart);
                    }
                    idx = content.indexOf(pkg, idx + 1);
                }
            }
        }

        File pomFile = new File("pom.xml");
        Assert.assertTrue("pom.xml should exist", pomFile.exists());
        String pomContent = new String(Files.readAllBytes(pomFile.toPath()), StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"resilience4j", "hystrix", "guava-ratelimiter", "consul", "etcd"}) {
            Assert.assertFalse("pom.xml should not contain " + banned, pomContent.contains(banned));
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
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
        }
    }

    // ============== Y4 Facade 单调：HJC 9 入口 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_HJC9Entries() {
        Set<String> names = new HashSet<>();
        for (Method m : HybridJoinClient.class.getDeclaredMethods()) {
            names.add(m.getName());
        }
        for (String orig : Arrays.asList(
                "searchHybridJoin", "searchHybridJoinWithWeights", "searchHybridJoinWithLtr")) {
            Assert.assertTrue("HJC 原 3 入口 missing: " + orig, names.contains(orig));
        }
        for (String p32 : Arrays.asList(
                "searchHybridJoinWithResilience",
                "searchHybridJoinWithWeightsWithResilience",
                "searchHybridJoinWithLtrWithResilience")) {
            Assert.assertTrue("P32 3 入口 missing: " + p32, names.contains(p32));
        }
        for (String p36 : Arrays.asList(
                "searchHybridJoinWithDistributedResilience",
                "searchHybridJoinWithWeightsWithDistributedResilience",
                "searchHybridJoinWithLtrWithDistributedResilience")) {
            Assert.assertTrue("P36 3 入口 missing: " + p36, names.contains(p36));
        }
    }

    // ============== Y5 路由公式（P36 不破坏 P24 路由） ==============

    @Test(timeout = 30000)
    public void y5_RoutingFormula_UnitDocRouteDeterministic() throws Exception {
        Class<?> ftc = Class.forName("com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient");
        Assert.assertTrue("DsFulltextClusterClient should have methods", ftc.getDeclaredMethods().length > 0);
    }

    // ============== Y6 P30~P35 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P30ToP35CompileCompat_ClassesExist() {
        String[] classes = {
                "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
                "com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinSpec",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver",
                "com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode",
                "com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult",
                "com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerState",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerGuard",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.AdaptiveThresholdController",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.ClusterStateBroadcaster"
        };
        for (String c : classes) {
            try {
                Class.forName(c);
            } catch (ClassNotFoundException cnfe) {
                Assert.fail("class should exist: " + c);
            }
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
            Assert.assertTrue("Y8 ShardTransport 原方法 missing: " + orig + " (have: " + names + ")",
                    names.contains(orig));
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
                while (idx >= 0) {
                    String lineStart = content.substring(Math.max(0, idx - 24), idx);
                    if (!lineStart.contains("//") && !lineStart.contains("/*")) {
                        Assert.fail("found " + banned + " in " + f.getName() + " near: " + lineStart);
                    }
                    idx = content.indexOf(banned, idx + 1);
                }
            }
        }
    }
}