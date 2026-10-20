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

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;

/**
 * P37 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3（P37 5 新类全落 segment/ 子包，fulltext 根包仍 = 3）
 * <br>Y2 NG-3（segment 源码仅 JDK + first-party import，0 netty/grpc/p2p.client；pom 复用 lz4 不新增压缩 dep）
 * <br>Y3 TOPK 铁律（segment 0 引用 bucket 计数 / 0 触 SearchOptions）
 * <br>Y4 Facade 单调不减（P21/P22 公共探针签名全在 + 内嵌 WeightStore/Weight*Row 不消失）
 * <br>Y5 路由公式（P24 UNIT/DOC 路由确定性不破坏）
 * <br>Y6 P21~P36 编译兼容（TermDictStore/WeightStore/Join/Resilience/Parser 类型全在）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK（segment 源码）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SEGMENT_SRC = "src/main/java/com/q3lives/ds/database/fulltext/segment";

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

        // P37 5 新类必须全部存在且位于 segment 子包
        URL segUrl = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT + "/segment");
        Assert.assertNotNull("segment subpackage resource not found", segUrl);
        File segDir = new File(segUrl.toURI());
        Set<String> segClasses = new HashSet<>();
        for (String f : segDir.list((d, n) -> n.endsWith(".class") && !n.contains("$"))) {
            segClasses.add(f.replace(".class", ""));
        }
        Assert.assertTrue("segment classes missing: " + segClasses,
                segClasses.containsAll(Arrays.asList("DsIndexSegment", "SegmentColumnCodec", "DsIndexSegmentWriter",
                        "DsIndexSegmentReader", "DsIndexSegmentStore")));
    }

    // ============== Y2 NG-3 ==============

    @Test(timeout = 30000)
    public void y2_Ng3_SegmentImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(segmentDir(), javaFiles);
        Assert.assertEquals("segment 源文件数应为 5", 5, javaFiles.size());
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String pkg : new String[]{"io.netty", "io.grpc", "javax.net.p2p.client", "p2p.client"}) {
                Assert.assertFalse("forbidden ref " + pkg + " in " + f.getName(), content.contains(pkg));
            }
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("import ")) {
                    continue;
                }
                boolean ok = t.startsWith("import java.") || t.contains("com.q3lives.ds.");
                Assert.assertTrue("segment 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        // pom 复用既有 lz4-java，且未引入其它压缩依赖
        String pom = new String(Files.readAllBytes(new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        Assert.assertTrue("pom 应复用既有 lz4-java", pom.contains("lz4"));
        for (String banned : new String[]{"zstd", "snappy", "aircompressor", "zstd-jni", "brotli"}) {
            Assert.assertFalse("pom 不应新增压缩依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 TOPK 铁律 ==============

    @Test(timeout = 30000)
    public void y3_TopKGuardrails_NoBucketCounterNoSearchOptions() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(segmentDir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("segment 不应触碰 SearchOptions (K): " + f.getName(), content.contains("SearchOptions"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_P21P22ProbesIntact() throws Exception {
        Set<String> names = new HashSet<>();
        for (Method m : FulltextStore.class.getDeclaredMethods()) {
            names.add(m.getName());
        }
        for (String probe : Arrays.asList(
                "putDoc", "ingestDoc", "getDocStatForTest", "getTermStatForTest", "getTfForTest",
                "weightStoreAllDocKeysForTest", "termDictToUidForTest", "recomputeIdfForTerms", "close")) {
            Assert.assertTrue("P21/P22 探针/API missing: " + probe + " (have " + names.size() + " methods)",
                    names.contains(probe));
        }
        Set<String> nested = new HashSet<>();
        for (Class<?> c : FulltextStore.class.getDeclaredClasses()) {
            nested.add(c.getSimpleName());
        }
        for (String inner : Arrays.asList("WeightStore", "WeightDocStatRow", "WeightTermStatRow", "ShardTransport")) {
            Assert.assertTrue("P21/P22 内嵌类型 missing: " + inner, nested.contains(inner));
        }
    }

    // ============== Y5 路由公式 ==============

    @Test(timeout = 30000)
    public void y5_RoutingFormula_Deterministic() {
        long uid = 5L << 48;
        Assert.assertEquals("UNIT 路由 high16%shardCount", 1, FulltextStore.resolveShardForUnitIdForTest(uid, 4));
        long docId = 6L << 48;
        int a = FulltextStore.resolveShardForDocIdForTest(docId, 4);
        int b = FulltextStore.resolveShardForDocIdForTest(docId, 4);
        Assert.assertEquals("DOC 路由确定性", a, b);
        Assert.assertTrue("DOC 路由在 [0,4)", a >= 0 && a < 4);
    }

    // ============== Y6 P21~P36 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P21ToP36CompileCompat_ClassesExist() {
        String[] classes = {
                "com.q3lives.ds.database.fulltext.FulltextSegmenter",
                "com.q3lives.ds.database.fulltext.FulltextStore",
                "com.q3lives.ds.database.fulltext.TermDictStore",
                "com.q3lives.ds.database.fulltext.TermDictStore$FarmHash64",
                "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinSpec",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver",
                "com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode",
                "com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult",
                "com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
                "com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore"
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
        Class<?> st = null;
        for (Class<?> c : FulltextStore.class.getDeclaredClasses()) {
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
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(segmentDir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============

    private static File segmentDir() {
        File dir = new File(System.getProperty("user.dir"), SEGMENT_SRC);
        Assert.assertTrue("segment 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private static void collectJavaFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collectJavaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
    }
}
