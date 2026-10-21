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
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * P38 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3（P38 5 新类全落 parser/v3/ 子包，fulltext 根包仍 = 3）
 * <br>Y2 NG-3（v3 源码仅 JDK + first-party import，0 netty/grpc/p2p.client；pom 0 parser 库）
 * <br>Y3 TOPK 铁律（v3 0 引用 bucket 计数 / 0 触 SearchOptions）
 * <br>Y4 Facade 单调不减（P30 parse 4 参 + P34 parseOr 6 参签名 + 词法 0 新增关键字 AGG/COUNT）
 * <br>Y5 路由公式（P24 ShardRouter UNIT/DOC 入口 0 改）
 * <br>Y6 P21~P38 编译兼容（既有类型 + v3 5 新类全在）
 * <br>Y7 HybridHit ctor=2
 * <br>Y8 ShardTransport 5 方法
 * <br>Y9 0 TODO/FIXME/HACK（v3 源码）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P38_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String V3_REL = "com/q3lives/ds/database/fulltext/cluster/parser/v3";
    private static final String V3_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/v3";

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

        // P38 5 新类必须全部存在且位于 parser/v3 子包
        URL v3Url = Thread.currentThread().getContextClassLoader().getResource(V3_REL);
        Assert.assertNotNull("parser/v3 subpackage resource not found", v3Url);
        File v3Dir = new File(v3Url.toURI());
        Set<String> v3Classes = new HashSet<>();
        for (String f : v3Dir.list((d, n) -> n.endsWith(".class") && !n.contains("$"))) {
            v3Classes.add(f.replace(".class", ""));
        }
        Assert.assertTrue("parser/v3 classes missing: " + v3Classes,
                v3Classes.containsAll(Arrays.asList("AggFn", "AggSpec", "MintermAggTask", "AggQueryPlan",
                        "AggParser")));
    }

    // ============== Y2 NG-3 ==============

    @Test(timeout = 30000)
    public void y2_Ng3_V3ImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(v3Dir(), javaFiles);
        Assert.assertEquals("parser/v3 源文件数应为 5", 5, javaFiles.size());
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
                Assert.assertTrue("parser/v3 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        // pom 0 新增 parser 库依赖
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
        collectJavaFiles(v3Dir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("parser/v3 不应触碰 SearchOptions (K): " + f.getName(),
                    content.contains("SearchOptions"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============

    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_P30P34SignaturesIntact() throws Exception {
        // P30 HybridQueryParser.parse 4 参签名 0 改
        Method parse = HybridQueryParser.class.getDeclaredMethod("parse",
                Class.class, String.class, java.util.Map.class, java.util.Map.class);
        Assert.assertTrue("parse is static", java.lang.reflect.Modifier.isStatic(parse.getModifiers()));
        Assert.assertTrue("parse is public", java.lang.reflect.Modifier.isPublic(parse.getModifiers()));
        Assert.assertEquals("parse 返回类型", com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery.class,
                parse.getReturnType());
        // P34 DnfParser.parseOr 6 参签名 0 改
        Method parseOr = DnfParser.class.getDeclaredMethod("parseOr",
                Class.class, String.class, java.util.Map.class, java.util.Map.class, int.class, DnfReturnMode.class);
        Assert.assertNotNull("DnfParser.parseOr 6 参必须存在", parseOr);
        // P30 词法 0 新增关键字：DslToken 值数锁定 + AGG/COUNT 等仍落 IDENT
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
        // P24 UNIT/DOC 路由确定性探针仍在
        long uid = 5L << 48;
        Assert.assertEquals("UNIT 路由确定性", FulltextStore.resolveShardForUnitIdForTest(uid, 4), 1);
    }

    // ============== Y6 P21~P38 编译兼容 ==============

    @Test(timeout = 30000)
    public void y6_P21ToP38CompileCompat_ClassesExist() {
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
                "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser"
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
        collectJavaFiles(v3Dir(), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============

    private static File v3Dir() {
        File dir = new File(System.getProperty("user.dir"), V3_SRC);
        Assert.assertTrue("parser/v3 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
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
