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
 * P40 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + 四重递归文件数守恒（segment=5 + parser/v3=5 + agg=6 + compact=5；根包 top-level=3）
 * <br>Y2 NG-3（compact 源码仅 JDK + first-party import，0 netty/grpc/p2p.client；pom 0 新增 dep）
 * <br>Y3 TOPK 铁律（compact 0 引用 bucket 计数 / 0 触 SearchOptions）
 * <br>Y4 Facade 单调不减（P37 segment 5 类源码 0 改 grep + P21/P22 探针签名）
 * <br>Y5 路由公式（P24 ShardRouter UNIT/DOC 入口 0 改）
 * <br>Y6 P21~P40 编译兼容（既有类型 + P40 compact 5 类全在）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK（compact 源码）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P40_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SRC_FULLTEXT = "src/main/java/com/q3lives/ds/database/fulltext";
    private static final String SEGMENT_SRC = SRC_FULLTEXT + "/segment";
    private static final String V3_SRC = SRC_FULLTEXT + "/cluster/parser/v3";
    private static final String AGG_SRC = SRC_FULLTEXT + "/cluster/agg";
    private static final String COMPACT_SRC = SRC_FULLTEXT + "/compact";

    private static final List<String> COMPACT_CLASSES = Arrays.asList(
            "SegmentMergeView", "SegmentCompactor", "SegmentCompactionPolicy",
            "SegmentCompactionPlan", "SegmentCompactionRunner");

    // ============== Y1 ZeroLeak3=3 + 四重递归文件数守恒 ==============

    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndQuadFileCountDoubleGuard() throws Exception {
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

        // 四重递归文件数守恒（P37_T5.Y2 / P38_T5.Y2 / P39_T5.Y2 基线 + P40 compact 新锁）
        Assert.assertEquals("segment 递归源文件数应为 5（P37 基线）", 5, countJava(srcDir(SEGMENT_SRC)));
        Assert.assertEquals("parser/v3 递归源文件数应为 5（P38/P39 基线）", 5, countJava(srcDir(V3_SRC)));
        Assert.assertEquals("cluster/agg 递归源文件数应为 6（P39 基线）", 6, countJava(srcDir(AGG_SRC)));
        Assert.assertEquals("compact 递归源文件数应为 5（P40 新锁）", 5, countJava(srcDir(COMPACT_SRC)));

        // P40 5 新类必须全部位于 compact/ 子包
        URL compactUrl = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT + "/compact");
        Assert.assertNotNull("compact subpackage resource not found", compactUrl);
        File compactDir = new File(compactUrl.toURI());
        Set<String> compactClasses = new HashSet<>();
        for (String f : compactDir.list((d, n) -> n.endsWith(".class") && !n.contains("$"))) {
            compactClasses.add(f.replace(".class", ""));
        }
        Assert.assertTrue("compact classes missing: " + compactClasses, compactClasses.containsAll(COMPACT_CLASSES));
    }

    // ============== Y2 NG-3 ==============

    @Test(timeout = 30000)
    public void y2_Ng3_CompactImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(COMPACT_SRC), javaFiles);
        Assert.assertEquals("compact 源文件数应为 5", 5, javaFiles.size());
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
                Assert.assertTrue("compact 只允许 JDK + first-party import, got: " + t, ok);
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
        collectJavaFiles(srcDir(COMPACT_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("compact 不应触碰 SearchOptions (K): " + f.getName(), content.contains("SearchOptions"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_P37SegmentAndPriorIntact() throws Exception {
        // P37 segment 5 源文件 0 改：逐文件校验包声明 + 关键公开 API 标记仍在
        File segDir = srcDir(SEGMENT_SRC);
        Assert.assertEquals("segment 源文件数应为 5", 5, countJava(segDir));
        assertContains(new File(segDir, "DsIndexSegment.java"), "public static final int VERSION = 1");
        assertContains(new File(segDir, "SegmentColumnCodec.java"), "public static int binarySearchKey");
        assertContains(new File(segDir, "DsIndexSegmentWriter.java"), "public DsIndexSegment finish()");
        assertContains(new File(segDir, "DsIndexSegmentReader.java"), "public static DsIndexSegmentReader open(");
        assertContains(new File(segDir, "DsIndexSegmentStore.java"), "public DsIndexSegment append(long segId");

        // P21/P22 探针/API 单调不减
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
    public void y5_RoutingFormula_ShardRouterIntact() {
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
        long uid = 5L << 48;
        Assert.assertEquals("UNIT 路由确定性", 1, FulltextStore.resolveShardForUnitIdForTest(uid, 4));
        long docId = 6L << 48;
        int a = FulltextStore.resolveShardForDocIdForTest(docId, 4);
        Assert.assertEquals("DOC 路由确定性", a, FulltextStore.resolveShardForDocIdForTest(docId, 4));
    }

    // ============== Y6 P21~P40 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P21ToP40CompileCompat_ClassesExist() {
        String[] classes = {
                "com.q3lives.ds.database.fulltext.FulltextSegmenter",
                "com.q3lives.ds.database.fulltext.FulltextStore",
                "com.q3lives.ds.database.fulltext.TermDictStore",
                "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
                "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinSpec",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
                "com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggMerger",
                "com.q3lives.ds.database.fulltext.compact.SegmentMergeView",
                "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
                "com.q3lives.ds.database.fulltext.compact.SegmentCompactionPolicy",
                "com.q3lives.ds.database.fulltext.compact.SegmentCompactionPlan",
                "com.q3lives.ds.database.fulltext.compact.SegmentCompactionRunner"
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
        collectJavaFiles(srcDir(COMPACT_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============

    private static void assertContains(File f, String needle) throws Exception {
        Assert.assertTrue("file missing: " + f.getAbsolutePath(), f.isFile());
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(needle + " missing in " + f.getName(), content.contains(needle));
    }

    private static File srcDir(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private static int countJava(File dir) {
        List<File> out = new ArrayList<>();
        collectJavaFiles(dir, out);
        return out.size();
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
