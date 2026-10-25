package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggMerger;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;

/**
 * P41 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + 五重递归文件数守恒（segment=5 + parser/v3=5 + agg=6 + compact=5 + groupby=5；
 * 根包 top-level=3）
 * <br>Y2 NG-3（groupby 源码仅 JDK + first-party import，0 netty/grpc/p2p.client；pom 0 新增 forbidden dep）
 * <br>Y3 TOPK 铁律（groupby 0 引用 bucket 计数 / 0 触 SearchOptions）
 * <br>Y4 Facade 单调不减（P30/P34/P38/P39 关键签名反射 + 源码标记 grep）
 * <br>Y5 路由公式（P24 ShardRouter UNIT/DOC 入口 0 改）
 * <br>Y6 P21~P41 编译兼容（既有类型 + P41 groupby 5 类全在）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK（groupby 源码）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P41_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SRC_FULLTEXT = "src/main/java/com/q3lives/ds/database/fulltext";
    private static final String SEGMENT_SRC = SRC_FULLTEXT + "/segment";
    private static final String V3_SRC = SRC_FULLTEXT + "/cluster/parser/v3";
    private static final String AGG_SRC = SRC_FULLTEXT + "/cluster/agg";
    private static final String COMPACT_SRC = SRC_FULLTEXT + "/compact";
    private static final String GROUPBY_SRC = SRC_FULLTEXT + "/cluster/groupby";

    private static final List<String> GROUPBY_CLASSES = Arrays.asList(
            "HavingSpec", "GroupBySpec", "GroupBucket", "GroupByResult", "GroupByExecutor");

    // ============== Y1 ZeroLeak3=3 + 五重递归文件数守恒 ==============

    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndPentaFileCount() throws Exception {
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

        // 五重递归文件数守恒（P37/P38/P39/P40 基线 + P41 groupby 新锁）
        Assert.assertEquals("segment 递归源文件数应为 5（P37 基线）", 5, countJava(srcDir(SEGMENT_SRC)));
        Assert.assertEquals("parser/v3 递归源文件数应为 5（P38/P39 基线）", 5, countJava(srcDir(V3_SRC)));
        Assert.assertEquals("cluster/agg 递归源文件数应为 6（P39 基线）", 6, countJava(srcDir(AGG_SRC)));
        Assert.assertEquals("compact 递归源文件数应为 5（P40 基线）", 5, countJava(srcDir(COMPACT_SRC)));
        Assert.assertEquals("groupby 递归源文件数应为 5（P41 新锁）", 5, countJava(srcDir(GROUPBY_SRC)));

        // P41 5 新类必须全部位于 groupby/ 子包
        URL gbUrl = Thread.currentThread().getContextClassLoader().getResource(PKG_FULLTEXT + "/cluster/groupby");
        Assert.assertNotNull("groupby subpackage resource not found", gbUrl);
        File gbDir = new File(gbUrl.toURI());
        Set<String> gbClasses = new HashSet<>();
        for (String f : gbDir.list((d, n) -> n.endsWith(".class") && !n.contains("$"))) {
            gbClasses.add(f.replace(".class", ""));
        }
        Assert.assertTrue("groupby classes missing: " + gbClasses, gbClasses.containsAll(GROUPBY_CLASSES));
    }

    // ============== Y2 NG-3 ==============

    @Test(timeout = 30000)
    public void y2_Ng3_GroupByImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(GROUPBY_SRC), javaFiles);
        Assert.assertEquals("groupby 源文件数应为 5", 5, javaFiles.size());
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
                Assert.assertTrue("groupby 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        // pom 0 新增 forbidden dep/plugin
        String pom = new String(Files.readAllBytes(new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"netty", "grpc", "zstd", "snappy", "aircompressor", "brotli"}) {
            Assert.assertFalse("pom 不应新增 forbidden dep: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 TOPK 铁律 ==============

    @Test(timeout = 30000)
    public void y3_TopKGuardrails_NoBucketCounterNoSearchOptions() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(GROUPBY_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("groupby 不应触碰 SearchOptions (K): " + f.getName(), content.contains("SearchOptions"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_P30P34P38P39Intact() throws Exception {
        // P38 AggParser.parse 6 参签名
        Method parse = AggParser.class.getMethod("parse", Class.class, String.class, Map.class, Map.class, int.class,
                int.class);
        Assert.assertNotNull(parse);
        // P39 AggExecutor.execute 3 参签名
        AggExecutor.class.getMethod("execute", AggQueryPlan.class, AggRowSource.class, Map.class);
        // P39 AggMerger.merge 1 参签名
        AggMerger.class.getMethod("merge", List.class);
        // P38 AggQueryPlan 公开字段 {clause, aggs}
        Field clause = AggQueryPlan.class.getField("clause");
        Field aggs = AggQueryPlan.class.getField("aggs");
        Assert.assertNotNull(clause);
        Assert.assertNotNull(aggs);
        // P38 AggFn 5 值
        Assert.assertEquals("AggFn 应保持 5 值", 5, AggFn.values().length);

        // 源码标记仍在（P38/P39 源码 0 改）
        assertContains(new File(srcDir(AGG_SRC), "AggExecutor.java"),
                "public AggResult execute(AggQueryPlan plan, AggRowSource<T> source,");
        assertContains(new File(srcDir(AGG_SRC), "AggMerger.java"),
                "public static AggResult merge(List<AggResult> partials)");
        assertContains(new File(srcDir(V3_SRC), "AggParser.java"),
                "public static <T extends DsTableAdapter> AggQueryPlan parse(");
        assertContains(new File(srcDir(V3_SRC), "AggQueryPlan.java"), "public final List<AggSpec> aggs;");
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

    // ============== Y6 P21~P41 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P21ToP41CompileCompat_ClassesExist() {
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
                "com.q3lives.ds.database.fulltext.compact.SegmentCompactionRunner",
                "com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec",
                "com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec",
                "com.q3lives.ds.database.fulltext.cluster.groupby.GroupBucket",
                "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByResult",
                "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor"
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
        collectJavaFiles(srcDir(GROUPBY_SRC), javaFiles);
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
