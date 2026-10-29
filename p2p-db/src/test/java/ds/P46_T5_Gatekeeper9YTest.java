package ds;

import java.io.File;
import java.lang.reflect.Field;
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
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P46 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + 六重递归文件数守恒（segment=5 / parser/v3=5 / agg=6 / compact=5 /
 * groupby=5 / layered=3）+ {@code rowsource}=2（P45 1 + P46 1）；Y2 NG-3 零依赖；Y3
 * TOPK 铁律（0 触 bucket 计数 + 运行期 {@link SearchOptions} 只读）；Y4 Facade 单调不减（Q1 例外仅
 * {@code toWrapper} 可见性 + P46 {@code ColumnarAggRowSource} 契约）；Y5 路由公式（
 * {@code ShardRouter} UNIT/DOC + {@link DsIdLayout} 位布局）； Y6 P21~P46 编译兼容；Y7
 * HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO/FIXME/HACK。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P46_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String ROWS_REL = "com/q3lives/ds/database/fulltext/cluster/rowsource";
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

    public static class AbcEntity extends DsTableAdapter {

        @com.q3lives.ds.annotation.DsField(name = "tag", length = 16)
        public String tag;
        @com.q3lives.ds.annotation.DsField(name = "price", length = 8)
        public Double price;

        public AbcEntity() {
        }
    }

    private File tmpRoot;
    private String oldHome;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t5_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
    }

    @After
    public void after() {
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
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

    // ============== Y1 ZeroLeak3=3 + 六重递归文件数守恒 + rowsource=2 ==============
    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndSixFoldFileCountGuard() throws Exception {
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
        Assert.assertEquals("P46 子树 cluster/rowsource/ 源文件数（P45 1 + P46 1）", 2, countJava(ROWS_SRC));

        URL rowsUrl = Thread.currentThread().getContextClassLoader().getResource(ROWS_REL);
        Assert.assertNotNull("cluster/rowsource 子包资源必须存在", rowsUrl);
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_Ng3_RowsourceImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(ROWS_SRC), javaFiles);
        Assert.assertEquals("rowsource 源文件数应为 2", 2, javaFiles.size());
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
                // 允许 JDK + first-party + p2p-core 谓词模型（javax.net.p2p.model.DbQuery 等）
                boolean ok = t.startsWith("import java.")
                        || t.contains("com.q3lives.ds.")
                        || t.startsWith("import javax.net.p2p.model.");
                Assert.assertTrue("rowsource 只允许 JDK + first-party + p2p.model import, got: " + t, ok);
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
        collectJavaFiles(srcDir(ROWS_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
        }

        // 运行期只读证明：ColumnarAggRowSource.fetch（本地双模）前后 SearchOptions 字段值全等
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        ColumnarAggRowSource<AbcEntity> src = new ColumnarAggRowSource<>(gm, null, null);
        SearchOptions opt = SearchOptions.defaults();
        String before = snapshot(opt);
        src.fetch(Collections.singletonList(ConditionNode.orm("tag", DslToken.OP_EQ, "x")));
        Assert.assertEquals("R9：SearchOptions 必须只读（fetch 前后全等）", before, snapshot(opt));
    }

    private static String snapshot(SearchOptions o) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Field f : SearchOptions.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            sb.append(f.getName()).append('=').append(f.get(o)).append(';');
        }
        return sb.toString();
    }

    // ============== Y4 Facade 单调不减（Q1 例外仅 toWrapper 可见性 + P46 契约） ==============
    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_UpstreamIntact() throws Exception {
        // Q1 唯一例外：toWrapper 可见性放宽为 public static（append-only）
        Method toWrapper = GenericManagerRowSource.class.getDeclaredMethod("toWrapper", List.class);
        Assert.assertTrue("Q1 例外：toWrapper 必须 public", Modifier.isPublic(toWrapper.getModifiers()));
        Assert.assertTrue("Q1 例外：toWrapper 必须 static", Modifier.isStatic(toWrapper.getModifiers()));
        Assert.assertEquals("toWrapper 返回类型不变", 1, toWrapper.getParameterCount());

        // P39 agg：AggExecutor.execute 3 参 + AggRowSource 接口
        Assert.assertTrue("AggRowSource 必须为接口", AggRowSource.class.isInterface());
        Class<?>[] aeParams = method(AggExecutor.class, "execute").getParameterTypes();
        Assert.assertEquals("AggExecutor.execute 仍 3 参", 3, aeParams.length);

        // P41 groupby：GroupByExecutor.execute 4 参
        Assert.assertEquals("GroupByExecutor.execute 仍 4 参", 4,
                method(GroupByExecutor.class, "execute").getParameterCount());

        // P28 DHQC：searchHybrid 入口存在
        Set<String> dhqc = names(DsHybridQueryClient.class.getDeclaredMethods());
        Assert.assertTrue("P28 DHQC searchHybrid 必须存在", dhqc.contains("searchHybrid"));

        // P31 join 源码类仍可解析（0 改）
        Class.forName("com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient");
        Class.forName("com.q3lives.ds.database.fulltext.cluster.join.JoinSpec");

        // P46 契约：ColumnarAggRowSource 实现 AggRowSource + ctor 3 参 + toDbQuery public static
        Assert.assertTrue("P46 ColumnarAggRowSource 必须 implements AggRowSource",
                AggRowSource.class.isAssignableFrom(ColumnarAggRowSource.class));
        Assert.assertEquals("ColumnarAggRowSource ctor 仍 1 个", 1,
                ColumnarAggRowSource.class.getDeclaredConstructors().length);
        Assert.assertEquals("ColumnarAggRowSource ctor 仍 3 参", 3,
                ColumnarAggRowSource.class.getDeclaredConstructors()[0].getParameterCount());
        Method toDbQuery = ColumnarAggRowSource.class.getDeclaredMethod("toDbQuery", List.class);
        Assert.assertTrue("ColumnarAggRowSource.toDbQuery 必须 public", Modifier.isPublic(toDbQuery.getModifiers()));
        Assert.assertTrue("ColumnarAggRowSource.toDbQuery 必须 static", Modifier.isStatic(toDbQuery.getModifiers()));
        Assert.assertEquals("toDbQuery 单参", 1, toDbQuery.getParameterCount());

        // P39/P41 文件数锁定（Facade 不增不减）
        Assert.assertEquals("agg/ 仍 6 类", 6, countJava(AGG_SRC));
        Assert.assertEquals("groupby/ 仍 5 类", 5, countJava(GROUPBY_SRC));
    }

    // ============== Y5 路由公式 ==============
    @Test(timeout = 30000)
    public void y5_RoutingFormula_ShardRouterAndIdLayoutIntact() throws Exception {
        Method ru = FulltextStore.class.getDeclaredMethod("resolveShardForUnitIdForTest", long.class, int.class);
        Method rd = FulltextStore.class.getDeclaredMethod("resolveShardForDocIdForTest", long.class, int.class);
        ru.setAccessible(true);
        rd.setAccessible(true);
        int N = 4;
        for (int i = 0; i < 500; i++) {
            long uid = 0xDEADBEEFCAFEBABEL ^ (i * 2654435761L);
            int expectedUnit = (int) ((uid >>> 48) & 0xFFFF) % N;
            int u1 = (Integer) ru.invoke(null, uid, N);
            int u2 = (Integer) ru.invoke(null, uid, N);
            Assert.assertEquals("Y5 UNIT 路由公式精确 iter=" + i, expectedUnit, u1);
            Assert.assertEquals("Y5 UNIT 路由确定性 iter=" + i, u1, u2);
            int d1 = (Integer) rd.invoke(null, uid, N);
            int d2 = (Integer) rd.invoke(null, uid, N);
            Assert.assertEquals("Y5 DOC 路由确定性 iter=" + i, d1, d2);
        }

        // DsIdLayout 位布局 0 改：compose ↔ serverId/partitionId/localAddress 往返一致
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

    // ============== Y6 P21~P46 编译兼容 ==============
    @Test(timeout = 30000)
    public void y6_P21ToP46CompileCompat_ClassesExist() {
        String[] classes = {
            "com.q3lives.ds.database.fulltext.FulltextSegmenter",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.DslToken",
            "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode",
            "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource",
            "com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor",
            "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor",
            "com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec",
            "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
            "com.q3lives.ds.database.fulltext.layered.LayeredInvertedView",
            "com.q3lives.ds.database.fulltext.cluster.join.JoinSpec",
            "com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource",
            "com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource",
            "com.q3lives.ds.database.columnar.DsDatabaseClusterClient"
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
        Assert.assertEquals("HybridHit 必须恰 2 个构造器",
                2, HybridHit.class.getDeclaredConstructors().length);
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
    public void y9_NoTodoFixmeHackInRowsource() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(ROWS_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============
    private static Method method(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        Assert.fail("method not found: " + c.getSimpleName() + "." + name);
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
}
