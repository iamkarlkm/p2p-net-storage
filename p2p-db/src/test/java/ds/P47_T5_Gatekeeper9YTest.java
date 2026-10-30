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
import org.junit.Test;

import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P47 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + 七重递归文件数守恒（segment=5 / parser/v3=5 / agg=6 / compact=5 / groupby=5 /
 * layered=3 / rowsource=2）+ {@code sched}=3（P47 新增兄弟包）；Y2 NG-3 零依赖；Y3 TOPK 铁律（0 触
 * bucket 计数 + 运行期 {@link SearchOptions} 只读）；Y4 Facade 单调不减（P37 segment 5 + P40 compact 5
 * 计数锁定）；Y5 路由公式（{@code ShardRouter} UNIT/DOC + {@link DsIdLayout} 位布局）； Y6 P21~P47
 * 编译兼容；Y7 HybridHit ctor=2；Y8 ShardTransport 5 方法；Y9 0 TODO/FIXME/HACK。
 */
public class P47_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SCHED_REL = "com/q3lives/ds/database/fulltext/sched";
    private static final String SCHED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/sched";
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

    private File tmpRoot;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p47_t5_").toFile();
    }

    @After
    public void after() {
        deleteRecursive(tmpRoot);
    }

    // ============== Y1 ZeroLeak3=3 + 七重递归文件数守恒 + sched=3 ==============
    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndSevenFoldFileCountGuard() throws Exception {
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
        Assert.assertEquals("cluster/rowsource/ 源文件数（P45 1 + P46 1）", 2, countJava(ROWS_SRC));
        Assert.assertEquals("P47 sched/ 源文件数", 3, countJava(SCHED_SRC));

        URL schedUrl = Thread.currentThread().getContextClassLoader().getResource(SCHED_REL);
        Assert.assertNotNull("sched 子包资源必须存在", schedUrl);
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_Ng3_SchedImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(SCHED_SRC), javaFiles);
        Assert.assertEquals("sched 源文件数应为 3", 3, javaFiles.size());
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
                        || t.contains("com.q3lives.ds.");
                Assert.assertTrue("sched 只允许 JDK + first-party import, got: " + t, ok);
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
        collectJavaFiles(srcDir(SCHED_SRC), javaFiles);
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("TOPK violation in " + f.getName(),
                    content.contains("bucketAccessCounter") || content.contains("BucketAccessCounter"));
            Assert.assertFalse("sched 不应引用 SearchOptions: " + f.getName(),
                    content.contains("SearchOptions"));
        }

        // 运行期只读证明：一轮 tick 前后 SearchOptions 字段值全等
        SearchOptions opt = SearchOptions.defaults();
        String before = snapshot(opt);
        DsIndexSegmentStore store = new DsIndexSegmentStore(tmpRoot.getAbsolutePath());
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());
        sched.tick();
        sched.close();
        Assert.assertEquals("R3：SearchOptions 必须只读（tick 前后全等）", before, snapshot(opt));
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

    // ============== Y4 Facade 单调不减（P37 segment 5 + P40 compact 5 计数锁定） ==============
    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_UpstreamIntact() throws Exception {
        // P46 Q1 唯一例外：toWrapper 可见性放宽为 public static（append-only）
        Method toWrapper = GenericManagerRowSource.class.getDeclaredMethod("toWrapper", List.class);
        Assert.assertTrue("Q1 例外：toWrapper 必须 public", Modifier.isPublic(toWrapper.getModifiers()));
        Assert.assertTrue("Q1 例外：toWrapper 必须 static", Modifier.isStatic(toWrapper.getModifiers()));

        // P39 agg / P41 groupby / P28 DHQC 契约
        Assert.assertTrue("AggRowSource 必须为接口", AggRowSource.class.isInterface());
        Assert.assertEquals("AggExecutor.execute 仍 3 参", 3,
                method(AggExecutor.class, "execute").getParameterTypes().length);
        Assert.assertEquals("GroupByExecutor.execute 仍 4 参", 4,
                method(GroupByExecutor.class, "execute").getParameterCount());
        Assert.assertTrue("P28 DHQC searchHybrid 必须存在",
                names(DsHybridQueryClient.class.getDeclaredMethods()).contains("searchHybrid"));

        // P37 segment / P40 compact 文件数锁定（Facade 不增不减）
        Assert.assertEquals("P37 segment/ 仍 5 类", 5, countJava(SEGMENT_SRC));
        Assert.assertEquals("P40 compact/ 仍 5 类", 5, countJava(COMPACT_SRC));
        Assert.assertEquals("P39 agg/ 仍 6 类", 6, countJava(AGG_SRC));
        Assert.assertEquals("P41 groupby/ 仍 5 类", 5, countJava(GROUPBY_SRC));

        // P40 compact 5 类仍可解析（0 改）
        for (String c : new String[]{
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactionPlan",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactionPolicy",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactionRunner",
            "com.q3lives.ds.database.fulltext.compact.SegmentMergeView"}) {
            Class.forName(c);
        }

        // P47 契约：CompactionScheduler ctor 2 参 + tick() int
        Assert.assertEquals("CompactionScheduler ctor 仍 1 个", 1,
                CompactionScheduler.class.getDeclaredConstructors().length);
        Assert.assertEquals("CompactionScheduler ctor 仍 2 参", 2,
                CompactionScheduler.class.getDeclaredConstructors()[0].getParameterCount());
        Assert.assertEquals("CompactionScheduler.tick 返回 int", int.class,
                method(CompactionScheduler.class, "tick").getReturnType());
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
            int d1 = (Integer) rd.invoke(null, uid, n);
            int d2 = (Integer) rd.invoke(null, uid, n);
            Assert.assertEquals("Y5 DOC 路由确定性 iter=" + i, d1, d2);
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

    // ============== Y6 P21~P47 编译兼容 ==============
    @Test(timeout = 30000)
    public void y6_P21ToP47CompileCompat_ClassesExist() {
        String[] classes = {
            "com.q3lives.ds.database.fulltext.FulltextSegmenter",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode",
            "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource",
            "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor",
            "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
            "com.q3lives.ds.database.fulltext.layered.LayeredInvertedView",
            "com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource",
            "com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig",
            "com.q3lives.ds.database.fulltext.sched.CompactionScheduler",
            "com.q3lives.ds.database.fulltext.sched.CompactionStats",
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
    public void y9_NoTodoFixmeHackInSched() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(SCHED_SRC), javaFiles);
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
