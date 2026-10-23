package ds;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask;

/**
 * P39 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + {@code parser/v3} 文件数=5 双保（P39 6 新类全落 {@code cluster/agg/} 子包）
 * <br>Y2 NG-3（agg 源码仅 JDK + first-party import，0 netty/grpc/p2p.client；pom 0 parser 库）
 * <br>Y3 TOPK 铁律（agg 0 引用 bucket 计数 / 0 触 SearchOptions）
 * <br>Y4 Facade 单调不减（P30 parse 4 参 / P34 parseOr 6 参 / P38 AggParser.parse 6 参 + AggQueryPlan 表面对齐 + 词法 0 新增关键字）
 * <br>Y5 路由公式（P24 ShardRouter UNIT/DOC 入口 0 改）
 * <br>Y6 P21~P39 编译兼容（既有类型 + P38 v3 5 类 + P39 agg 6 类全在）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK（agg 源码）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P39_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String V3_REL = "com/q3lives/ds/database/fulltext/cluster/parser/v3";
    private static final String V3_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/v3";
    private static final String AGG_REL = "com/q3lives/ds/database/fulltext/cluster/agg";
    private static final String AGG_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/agg";

    private static final List<String> AGG_CLASSES = Arrays.asList(
            "AggRowSource", "GenericManagerRowSource", "AggValue", "AggResult", "AggExecutor", "AggMerger");

    // ============== Y1 ZeroLeak3=3 + parser/v3=5 双保 ==============

    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndParserV3FileCountDoubleGuard() throws Exception {
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

        // P38 基线：parser/v3 源文件数仍 = 5（P38_T5.Y2 断言），P39 新类不得污染
        List<File> v3Files = new ArrayList<>();
        collectJavaFiles(v3Dir(), v3Files);
        Assert.assertEquals("parser/v3 源文件数应为 5（P38 基线双保）", 5, v3Files.size());

        // P39 6 新类必须全部位于 cluster/agg/ 子包
        URL aggUrl = Thread.currentThread().getContextClassLoader().getResource(AGG_REL);
        Assert.assertNotNull("cluster/agg subpackage resource not found", aggUrl);
        File aggDir = new File(aggUrl.toURI());
        Set<String> aggClasses = new HashSet<>();
        for (String f : aggDir.list((d, n) -> n.endsWith(".class") && !n.contains("$"))) {
            aggClasses.add(f.replace(".class", ""));
        }
        Assert.assertTrue("cluster/agg classes missing: " + aggClasses, aggClasses.containsAll(AGG_CLASSES));
    }

    // ============== Y2 NG-3 ==============

    @Test(timeout = 30000)
    public void y2_Ng3_AggImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(aggDir(), javaFiles);
        Assert.assertEquals("cluster/agg 源文件数应为 6", 6, javaFiles.size());
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
                Assert.assertTrue("cluster/agg 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        String pom = new String(Files.readAllBytes(new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"antlr", "javacc", "parboiled", "jparsec", "calcite", "presto"}) {
            Assert.assertFalse("pom 不应新增 parser 依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 TOPK 铁律 ==============

    @Test(timeout = 30000)
    public void y3_TopKGuardrails_NoBucketCounterNoSearchOptions() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(aggDir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("cluster/agg 不应触碰 SearchOptions (K): " + f.getName(),
                    content.contains("SearchOptions"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_P30P34P38Intact() throws Exception {
        // P30 HybridQueryParser.parse 4 参签名 0 改
        Method parse = HybridQueryParser.class.getDeclaredMethod("parse",
                Class.class, String.class, Map.class, Map.class);
        Assert.assertTrue("parse is static", Modifier.isStatic(parse.getModifiers()));
        Assert.assertTrue("parse is public", Modifier.isPublic(parse.getModifiers()));
        Assert.assertEquals("parse 返回类型", ParsedHybridQuery.class, parse.getReturnType());
        // P34 DnfParser.parseOr 6 参签名 0 改
        Method parseOr = DnfParser.class.getDeclaredMethod("parseOr",
                Class.class, String.class, Map.class, Map.class, int.class, DnfReturnMode.class);
        Assert.assertNotNull("DnfParser.parseOr 6 参必须存在", parseOr);
        // P38 AggParser.parse 6 参签名 0 改
        Method aggParse = AggParser.class.getDeclaredMethod("parse",
                Class.class, String.class, Map.class, Map.class, int.class, int.class);
        Assert.assertTrue("AggParser.parse is static", Modifier.isStatic(aggParse.getModifiers()));
        Assert.assertEquals("AggParser.parse 返回类型", AggQueryPlan.class, aggParse.getReturnType());
        // P38 AggQueryPlan 表面对齐（clause/aggs 公开字段 + MAX_AGG + tasks/taskCount/fingerprint/deepEquals）
        Assert.assertEquals("MAX_AGG 锁定", 8, AggQueryPlan.MAX_AGG);
        Set<String> pubFields = new HashSet<>();
        for (Field f : AggQueryPlan.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                pubFields.add(f.getName());
            }
        }
        Assert.assertEquals("AggQueryPlan 公开实例字段锁定 = {clause, aggs}",
                new HashSet<>(Arrays.asList("clause", "aggs")), pubFields);
        for (String m : new String[]{"tasks", "taskCount", "fingerprint", "deepEquals"}) {
            Assert.assertTrue("AggQueryPlan." + m + " 必须存在",
                    hasMethod(AggQueryPlan.class, m));
        }
        // P38 AggFn 5 值 + 唯一 code 往返；AggSpec 2 参构造；MintermAggTask 3 参构造
        Assert.assertEquals("AggFn 5 值", 5, AggFn.values().length);
        Assert.assertEquals("AggFn code 往返 COUNT", AggFn.COUNT, AggFn.fromCode(AggFn.COUNT.code()));
        Assert.assertEquals("AggSpec (AggFn,String) 构造", 2, AggSpec.class.getDeclaredConstructors()[0].getParameterCount());
        Constructor<MintermAggTask>[] mts = (Constructor<MintermAggTask>[]) MintermAggTask.class.getDeclaredConstructors();
        Assert.assertEquals("MintermAggTask 3 参构造", 3, mts[0].getParameterCount());
        // P30 词法 0 新增关键字
        Assert.assertEquals("DslToken 值数锁定（0 新增关键字）", 25, DslToken.values().length);
        for (String kw : new String[]{"AGG", "COUNT", "SUM", "AVG", "MIN", "MAX"}) {
            DslTokenizer tz = new DslTokenizer(kw);
            DslTokenizer.TokenEntry e = tz.nextToken();
            Assert.assertNotNull(kw + " token 不应为 null", e);
            Assert.assertEquals(kw + " 必须落 IDENT（未注册关键字）", DslToken.IDENT, e.type);
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
    }

    // ============== Y6 P21~P39 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P21ToP39CompileCompat_ClassesExist() {
        String[] classes = {
                "com.q3lives.ds.database.fulltext.FulltextSegmenter",
                "com.q3lives.ds.database.fulltext.FulltextStore",
                "com.q3lives.ds.database.fulltext.TermDictStore",
                "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
                "com.q3lives.ds.database.fulltext.cluster.parser.DslToken",
                "com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer",
                "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser",
                "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause",
                "com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode",
                "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinSpec",
                "com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver",
                "com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
                "com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan",
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource",
                "com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggValue",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggResult",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor",
                "com.q3lives.ds.database.fulltext.cluster.agg.AggMerger"
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
        collectJavaFiles(aggDir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============

    private static boolean hasMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static File v3Dir() {
        File dir = new File(System.getProperty("user.dir"), V3_SRC);
        Assert.assertTrue("parser/v3 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private static File aggDir() {
        File dir = new File(System.getProperty("user.dir"), AGG_SRC);
        Assert.assertTrue("cluster/agg 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
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
