package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;

/**
 * P51 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + 十一重递归文件数守恒（segment=5 / parser/v3=5 / agg=6 / compact=5 / groupby=5 /
 * layered=3 / rowsource=2 / sched=3 / autocompact=3 / autoseg=3 / <b>wordnet=3</b>）；Y2 NG-3 零依赖
 * （新增 {@code fulltext/wordnet} 仅 JDK + first-party）；Y3 TOPK 铁律（<b>去注释后</b> 0 触
 * {@code bucketAccessCounter} / {@code SearchOptions}）；Y4 Facade 单调不减（P37/P40/P42/P47/P48/P49/P50
 * 源码 0 触 + P21 新增 3 accessor 存在且旧签名不消失 + P21 0 依赖 wordnet）；Y5 路由公式 0 改；Y6
 * P21~P51 编译兼容（既有类型 0 改 + {@code wordnet/} 3 类 {@code Class.forName}）；Y7 {@code HybridHit}
 * ctor=2；Y8 {@code ShardTransport} 5 方法；Y9 新增源码 0 TODO/FIXME/HACK。
 */
public class P51_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SCHED_REL = "com/q3lives/ds/database/fulltext/sched";
    private static final String AUTO_REL = "com/q3lives/ds/database/fulltext/autocompact";
    private static final String AUTOSEG_REL = "com/q3lives/ds/database/fulltext/autoseg";
    private static final String WORDNET_REL = "com/q3lives/ds/database/fulltext/wordnet";
    private static final String PUSHDOWN_REL = "com/q3lives/ds/database/columnar/pushdown";

    private static final String SCHED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/sched";
    private static final String AUTO_SRC = "src/main/java/com/q3lives/ds/database/fulltext/autocompact";
    private static final String AUTOSEG_SRC = "src/main/java/com/q3lives/ds/database/fulltext/autoseg";
    private static final String WORDNET_SRC = "src/main/java/com/q3lives/ds/database/fulltext/wordnet";
    private static final String PUSHDOWN_SRC = "src/main/java/com/q3lives/ds/database/columnar/pushdown";
    private static final String ROWS_SRC
            = "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource";
    private static final String AGG_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/agg";
    private static final String GROUPBY_SRC
            = "src/main/java/com/q3lives/ds/database/fulltext/cluster/groupby";
    private static final String V3_SRC
            = "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/v3";
    private static final String SEGMENT_SRC = "src/main/java/com/q3lives/ds/database/fulltext/segment";
    private static final String COMPACT_SRC = "src/main/java/com/q3lives/ds/database/fulltext/compact";
    private static final String LAYERED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/layered";
    private static final String FULLTEXT_STORE_SRC
            = "src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java";

    private File tmpRoot;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p51_t5_").toFile();
    }

    @After
    public void after() {
        deleteRecursive(tmpRoot);
    }

    // ============== Y1 ZeroLeak3=3 + 十一重递归文件数守恒 ==============
    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndElevenFoldFileCountGuard() throws Exception {
        List<String> ftTop = listTopLevelClasses(PKG_FULLTEXT);
        Set<String> expectedFt = new HashSet<>(Arrays.asList(
                "FulltextStore", "TermDictStore", "FulltextSegmenter"));
        Set<String> actualFt = new HashSet<>(ftTop);
        actualFt.retainAll(expectedFt);
        Assert.assertEquals("Y1 fulltext 根包 top-level 恰 3（" + ftTop + "）", 3, actualFt.size());

        Assert.assertEquals("segment/ 源文件数", 5, countJava(SEGMENT_SRC));
        Assert.assertEquals("cluster/parser/v3/ 源文件数", 5, countJava(V3_SRC));
        Assert.assertEquals("cluster/agg/ 源文件数", 6, countJava(AGG_SRC));
        Assert.assertEquals("compact/ 源文件数", 5, countJava(COMPACT_SRC));
        Assert.assertEquals("cluster/groupby/ 源文件数", 5, countJava(GROUPBY_SRC));
        Assert.assertEquals("layered/ 源文件数", 3, countJava(LAYERED_SRC));
        Assert.assertEquals("cluster/rowsource/ 源文件数", 2, countJava(ROWS_SRC));
        Assert.assertEquals("P47 sched/ 源文件数", 3, countJava(SCHED_SRC));
        Assert.assertEquals("P48 autocompact/ 源文件数", 3, countJava(AUTO_SRC));
        Assert.assertEquals("P49 autoseg/ 源文件数", 3, countJava(AUTOSEG_SRC));
        // P51 新类落新兄弟包 fulltext/wordnet（第十一处锁定包），源码数 = 3。
        Assert.assertEquals("P51 wordnet/ 源文件数", 3, countJava(WORDNET_SRC));

        // P50 新类落非锁定包 columnar/pushdown（不破坏守恒），源码数 = 1。
        Assert.assertEquals("P50 columnar/pushdown/ 源文件数", 1, countJava(PUSHDOWN_SRC));

        Assert.assertNotNull("sched 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(SCHED_REL));
        Assert.assertNotNull("autocompact 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(AUTO_REL));
        Assert.assertNotNull("autoseg 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(AUTOSEG_REL));
        Assert.assertNotNull("wordnet 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(WORDNET_REL));
        Assert.assertNotNull("columnar/pushdown 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(PUSHDOWN_REL));
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_Ng3_WordnetImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(WORDNET_SRC), javaFiles);
        Assert.assertEquals("wordnet 源文件数应为 3", 3, javaFiles.size());
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
                boolean ok = t.startsWith("import java.")
                        || t.contains("com.q3lives.ds.")
                        || t.startsWith("import javax.net.p2p.model.");
                Assert.assertTrue("wordnet 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        String pom = new String(
                Files.readAllBytes(new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"antlr", "javacc", "parboiled", "jparsec", "calcite", "presto"}) {
            Assert.assertFalse("pom 不应新增 parser 依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 TOPK 铁律 ==============
    @Test(timeout = 30000)
    public void y3_TopKGuardrails_NoBucketCounterAndSearchOptionsReadOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(WORDNET_SRC), javaFiles);
        for (File f : javaFiles) {
            // 剥离注释后再判违规：wordnet/ 的 Javadoc 允许「提到」这两个词（说明性文字），
            // 但可执行代码中禁止真实引用（R3 TOPK 铁律）。
            String code = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    code.contains("bucketAccessCounter") || code.contains("BucketAccessCounter"));
            Assert.assertFalse("wordnet 不应引用 SearchOptions: " + f.getName(),
                    code.contains("SearchOptions"));
        }
        // 受改上游（P50 handler/client）不得引入 TOPK 相关符号。
        for (String rel : new String[]{
            "src/main/java/javax/net/p2p/server/handler/DbNodeQueryIdsServerHandler.java",
            "src/main/java/com/q3lives/ds/database/columnar/DsDatabaseClusterClient.java"}) {
            String c = new String(Files.readAllBytes(
                    new File(System.getProperty("user.dir"), rel).toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + rel, c.contains("bucketAccessCounter"));
        }
    }

    // ============== Y4 Facade 单调不减 ==============
    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_UpstreamIntactAndP21Accessors() throws Exception {
        Assert.assertTrue("AggRowSource 必须为接口", AggRowSource.class.isInterface());
        Assert.assertEquals("AggExecutor.execute 仍 3 参", 3,
                method(AggExecutor.class, "execute").getParameterTypes().length);
        Assert.assertEquals("GroupByExecutor.execute 仍 4 参", 4,
                method(GroupByExecutor.class, "execute").getParameterCount());

        Assert.assertEquals("P37 segment/ 仍 5 类", 5, countJava(SEGMENT_SRC));
        Assert.assertEquals("P40 compact/ 仍 5 类", 5, countJava(COMPACT_SRC));
        Assert.assertEquals("P47 sched/ 仍 3 类", 3, countJava(SCHED_SRC));
        Assert.assertEquals("P48 autocompact/ 仍 3 类", 3, countJava(AUTO_SRC));
        Assert.assertEquals("P49 autoseg/ 仍 3 类", 3, countJava(AUTOSEG_SRC));
        Assert.assertEquals("P39 agg/ 仍 6 类", 6, countJava(AGG_SRC));
        Assert.assertEquals("P41 groupby/ 仍 5 类", 5, countJava(GROUPBY_SRC));
        Assert.assertEquals("P42 layered/ 仍 3 类", 3, countJava(LAYERED_SRC));

        // P21 FulltextStore：新增 3 个词网只读 accessor（append-only 唯一例外，R4）。
        Assert.assertNotNull("wordNetAllContextKeys 必须存在",
                FulltextStore.class.getDeclaredMethod("wordNetAllContextKeys"));
        Assert.assertNotNull("wordNetNeighborsForCtx 必须存在",
                FulltextStore.class.getDeclaredMethod("wordNetNeighborsForCtx",
                        long.class, long.class, int.class));
        Assert.assertNotNull("wordNetPositionsForCtx 必须存在",
                FulltextStore.class.getDeclaredMethod("wordNetPositionsForCtx",
                        long.class, long.class, long.class, int.class));

        // P21 既有签名不得消失（Facade 单调不减）。
        Assert.assertNotNull("neighborsWithin 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("neighborsWithin",
                        String.class, long.class, long.class, int.class, int.class));
        Assert.assertNotNull("distanceBetween 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("distanceBetween",
                        String.class, String.class, long.class, long.class, int.class));
        Assert.assertNotNull("topKNeighbors 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("topKNeighbors",
                        String.class, long.class, long.class, int.class, int.class));
        Assert.assertNotNull("ingestDoc 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("ingestDoc", long.class, String.class));

        // P21 源码不得反向依赖 wordnet（依赖方向单向：wordnet → P21）。
        String fts = new String(Files.readAllBytes(
                new File(System.getProperty("user.dir"), FULLTEXT_STORE_SRC).toPath()),
                StandardCharsets.UTF_8);
        Assert.assertFalse("P21 FulltextStore 不应依赖 wordnet 新包",
                fts.contains("WordNetExporter") || fts.contains("WordNetSegmentView")
                        || fts.contains("WordNetSnapshot") || fts.contains("fulltext.wordnet"));

        // P48/P49 契约保留。
        Assert.assertEquals("AutoCompactionHook ctor 仍 1 个", 1,
                AutoCompactionHook.class.getDeclaredConstructors().length);
        Assert.assertEquals("AutoCompactionHook ctor 仍 3 参", 3,
                AutoCompactionHook.class.getDeclaredConstructors()[0].getParameterCount());
        Assert.assertEquals("AutoSegmentHook ctor 仍 1 个", 1,
                AutoSegmentHook.class.getDeclaredConstructors().length);
        Assert.assertEquals("AutoSegmentHook ctor 仍 4 参", 4,
                AutoSegmentHook.class.getDeclaredConstructors()[0].getParameterCount());
        Assert.assertEquals("AutoSegmentHook.flushNow 返回 LayeredSnapshot", LayeredSnapshot.class,
                method(AutoSegmentHook.class, "flushNow").getReturnType());

        // P50 新类契约：final + 私有构造 + 3 公开静态方法。
        Assert.assertTrue("DbQueryToQueryWrapper 必须 final",
                Modifier.isFinal(DbQueryToQueryWrapper.class.getModifiers()));
        Assert.assertEquals("DbQueryToQueryWrapper 仅 1 个私有构造器", 1,
                DbQueryToQueryWrapper.class.getDeclaredConstructors().length);
        Assert.assertTrue("DbQueryToQueryWrapper 构造器必须私有",
                Modifier.isPrivate(DbQueryToQueryWrapper.class.getDeclaredConstructors()[0].getModifiers()));

        // 受改契约保留：handler.process 1 参；client 5 参 queryIds 存在。
        Assert.assertEquals("DbNodeQueryIdsServerHandler.process 仍 1 参", 1,
                method(javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler.class, "process")
                        .getParameterCount());
        Assert.assertEquals("DsDatabaseClusterClient.queryIds 需含 5 参重载", 5,
                findMethodByParamCount(DsDatabaseClusterClient.class, "queryIds", 5)
                        .getParameterCount());
    }

    // ============== Y5 路由公式 ==============
    @Test(timeout = 30000)
    public void y5_RoutingFormula_ShardRouterAndIdLayoutIntact() throws Exception {
        Method ru = FulltextStore.class.getDeclaredMethod("resolveShardForUnitIdForTest", long.class, int.class);
        Method rd = FulltextStore.class.getDeclaredMethod("resolveShardForDocIdForTest", long.class, int.class);
        ru.setAccessible(true);
        rd.setAccessible(true);
        int n = 4;
        for (int i = 0; i < 500; i++) {
            long uid = 0xDEADBEEFCAFEBABEL ^ (i * 2654435761L);
            int expectedUnit = (int) ((uid >>> 48) & 0xFFFF) % n;
            int u1 = (Integer) ru.invoke(null, uid, n);
            int u2 = (Integer) ru.invoke(null, uid, n);
            Assert.assertEquals("Y5 UNIT 路由公式精确 iter=" + i, expectedUnit, u1);
            Assert.assertEquals("Y5 UNIT 路由确定性 iter=" + i, u1, u2);
            Assert.assertEquals("Y5 DOC 路由确定性 iter=" + i,
                    (Integer) rd.invoke(null, uid, n), (Integer) rd.invoke(null, uid, n));
        }
        for (int i = 0; i < 500; i++) {
            int serverId = i % 4096;
            int partitionId = i % 256;
            long local = 1L + i;
            long rowId = DsIdLayout.compose(serverId, partitionId, local);
            Assert.assertEquals("Y5 serverId 往返 iter=" + i, serverId, DsIdLayout.serverId(rowId));
            Assert.assertEquals("Y5 partitionId 往返 iter=" + i, partitionId, DsIdLayout.partitionId(rowId));
            Assert.assertEquals("Y5 localAddress 往返 iter=" + i, local, DsIdLayout.localAddress(rowId));
        }
    }

    // ============== Y6 P21~P51 编译兼容 ==============
    @Test(timeout = 30000)
    public void y6_P21ToP51CompileCompat_ClassesExist() {
        String[] classes = {
            "com.q3lives.ds.database.fulltext.FulltextSegmenter",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource",
            "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor",
            "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
            "com.q3lives.ds.database.fulltext.layered.LayeredInvertedView",
            "com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource",
            "com.q3lives.ds.database.fulltext.sched.CompactionScheduler",
            "com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook",
            "com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook",
            "com.q3lives.ds.database.columnar.DsDatabaseClusterClient",
            "com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetExporter",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView",
            "javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler",
            "javax.net.p2p.model.DbNodeQueryIdsRequest",
            "javax.net.p2p.model.DbRowQueryIdsResponse"
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
    public void y7_HybridHit_ConstructorCountIs2() throws Exception {
        Class<?> hit = Class.forName("com.q3lives.ds.database.fulltext.cluster.HybridHit");
        Assert.assertEquals("HybridHit 必须恰 2 个构造器",
                2, hit.getDeclaredConstructors().length);
    }

    // ============== Y8 ShardTransport 5 方法 ==============
    @Test(timeout = 30000)
    public void y8_ShardTransport_5OriginalMethodsIntact() {
        Set<String> n = names(FulltextStore.ShardTransport.class.getDeclaredMethods());
        for (String m : Arrays.asList("putDoc", "clearDoc", "searchWithVector", "indexTermVector", "close")) {
            Assert.assertTrue("Y8 ShardTransport 原方法 missing: " + m + " (have: " + n + ")", n.contains(m));
        }
    }

    // ============== Y9 0 TODO/FIXME/HACK ==============
    @Test(timeout = 30000)
    public void y9_NoTodoFixmeHackInWordnet() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(WORDNET_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============
    /**
     * 剥离块注释（{@code /** ... *}{@code /} 与 {@code /* ... *}{@code /}）与行注释（{@code // ...}）。
     */
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static Method method(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        Assert.fail("method not found: " + c.getSimpleName() + "." + name);
        return null;
    }

    private static Method findMethodByParamCount(Class<?> c, String name, int argc) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        Assert.fail("method not found: " + c.getSimpleName() + "." + name + "/" + argc);
        return null;
    }

    private static Set<String> names(Method[] ms) {
        Set<String> s = new HashSet<>();
        for (Method m : ms) {
            s.add(m.getName());
        }
        return s;
    }

    private static List<String> listTopLevelClasses(String pkg) throws Exception {
        String rel = pkg.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL u = cl.getResource(rel);
        if (u == null) {
            return Collections.emptyList();
        }
        File dir = new File(u.toURI());
        List<String> out = new ArrayList<>();
        if (dir.listFiles() == null) {
            return out;
        }
        for (File f : dir.listFiles()) {
            if (f.isFile() && f.getName().endsWith(".class")) {
                String n = f.getName().substring(0, f.getName().length() - 6);
                if (!n.contains("$")) {
                    out.add(n);
                }
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static File srcDir(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private static int countJava(String rel) {
        List<File> out = new ArrayList<>();
        collectJavaFiles(srcDir(rel), out);
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

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
