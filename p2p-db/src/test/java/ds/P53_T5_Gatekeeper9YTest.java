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
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;
import com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOrder;

/**
 * P53 T5：Gatekeeper 9Y 9 TR 0F0E。
 *
 * <p>
 * Y1 ZeroLeak3=3 + <b>十三重递归文件数守恒</b>（segment=5 / parser/v3=5 / agg=6 / compact=5 /
 * groupby=5 / layered=3 / rowsource=2 / sched=3 / autocompact=3 / autoseg=3 / wordnet=3 /
 * columnar/pushdown=2 / <b>columnar/aggpushdown=1（第 13 处锁）</b>）；Y2 NG-3 零依赖（
 * {@code aggpushdown/} 仅 JDK + first-party + {@code javax.net.p2p.model.}）；Y3 {@code P2PCommand}
 * 既有枚举值 0 变 + 双份定义（core 数值版 / sdk ordinal 版 / ordinals 表）两侧均含新命令；Y4 Facade
 * 单调不减（P21/P37/P40/P42/P47/P48/P49/P51/P52 目录源码 0 改 + {@link ColumnScanPushdown} 公开签名 0
 * 改 + {@link ColumnAggPushdown} 新类契约）；Y5 路由公式 0 改；Y6 P21~P53 编译兼容（含 P53 新类）；Y7
 * {@code DbQuery}/{@code DbQueryOrder}/{@code DbQueryCriterion} 模型字段 0 改；Y8 段格式 0 变更（
 * {@code MAGIC="DIDX"} / {@code VERSION=1}）；Y9 新增源码 0 TODO/FIXME/HACK + 0 新线程。
 *
 * <p>
 * 所有跨模块源路径经 {@link #projectRoot()} 自 {@code user.dir} 向上回溯定位含 {@code p2p-db} 与
 * {@code p2p-core} 的仓库根，规避 surefire 工作目录差异。
 */
public class P53_T5_Gatekeeper9YTest {

    private static final String PKG_FULLTEXT = "com/q3lives/ds/database/fulltext";
    private static final String SCHED_REL = "com/q3lives/ds/database/fulltext/sched";
    private static final String AUTO_REL = "com/q3lives/ds/database/fulltext/autocompact";
    private static final String AUTOSEG_REL = "com/q3lives/ds/database/fulltext/autoseg";
    private static final String WORDNET_REL = "com/q3lives/ds/database/fulltext/wordnet";
    private static final String PUSHDOWN_REL = "com/q3lives/ds/database/columnar/pushdown";
    private static final String AGGRUN_REL = "com/q3lives/ds/database/columnar/aggpushdown";

    private static final String FT_ROOT_SRC = "p2p-db/src/main/java/com/q3lives/ds/database/fulltext";
    private static final String SEGMENT_SRC = FT_ROOT_SRC + "/segment";
    private static final String V3_SRC = FT_ROOT_SRC + "/cluster/parser/v3";
    private static final String AGG_SRC = FT_ROOT_SRC + "/cluster/agg";
    private static final String COMPACT_SRC = FT_ROOT_SRC + "/compact";
    private static final String GROUPBY_SRC = FT_ROOT_SRC + "/cluster/groupby";
    private static final String LAYERED_SRC = FT_ROOT_SRC + "/layered";
    private static final String ROWS_SRC = FT_ROOT_SRC + "/cluster/rowsource";
    private static final String SCHED_SRC = FT_ROOT_SRC + "/sched";
    private static final String AUTO_SRC = FT_ROOT_SRC + "/autocompact";
    private static final String AUTOSEG_SRC = FT_ROOT_SRC + "/autoseg";
    private static final String WORDNET_SRC = FT_ROOT_SRC + "/wordnet";
    private static final String PUSHDOWN_SRC
            = "p2p-db/src/main/java/com/q3lives/ds/database/columnar/pushdown";
    private static final String AGGRUN_SRC
            = "p2p-db/src/main/java/com/q3lives/ds/database/columnar/aggpushdown";
    private static final String HANDLER_SRC
            = "p2p-db/src/main/java/javax/net/p2p/server/handler";
    private static final String CLIENT_SRC
            = "p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsDatabaseClusterClient.java";
    private static final String FULLTEXT_STORE_SRC = FT_ROOT_SRC + "/FulltextStore.java";
    private static final String CORE_CMD_SRC
            = "p2p-core/src/main/java/javax/net/p2p/api/P2PCommand.java";
    private static final String SDK_CMD_SRC
            = "p2p-ws-sdk-java/src/main/java/javax/net/p2p/api/P2PCommand.java";
    private static final String ORDINALS_SRC
            = "p2p-ws-sdk-java/src/main/java/p2pws/sdk/core_compat/P2PCommandOrdinals.java";

    private File tmpRoot;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p53_t5_").toFile();
    }

    @After
    public void after() {
        deleteRecursive(tmpRoot);
    }

    // ============== Y1 ZeroLeak3=3 + 十三重递归文件数守恒 ==============
    @Test(timeout = 30000)
    public void y1_ZeroLeak3AndThirteenFoldFileCountGuard() throws Exception {
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
        Assert.assertEquals("P51 wordnet/ 源文件数", 3, countJava(WORDNET_SRC));
        Assert.assertEquals("P52 columnar/pushdown/ 源文件数", 2, countJava(PUSHDOWN_SRC));
        // P53 第 13 处新锁：columnar/aggpushdown/ = 1。
        Assert.assertEquals("P53 columnar/aggpushdown/ 源文件数", 1, countJava(AGGRUN_SRC));

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
        Assert.assertNotNull("columnar/aggpushdown 子包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(AGGRUN_REL));
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_Ng3_AggPushdownImportsJdkAndFirstPartyOnly() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(AGGRUN_SRC), javaFiles);
        Assert.assertEquals("aggpushdown 源文件数应为 1", 1, javaFiles.size());
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
                Assert.assertTrue("aggpushdown 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        String pom = new String(
                Files.readAllBytes(new File(projectRoot(), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"antlr", "javacc", "parboiled", "jparsec", "calcite", "presto"}) {
            Assert.assertFalse("pom 不应新增 parser 依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 P2PCommand 既有值 0 变 + 双份定义均含新命令 ==============
    @Test(timeout = 30000)
    public void y3_CommandEnumAppendOnly_BothDefinitions() throws Exception {
        // 运行时（p2p-core 数值版）：既有命令值 0 变 + 新命令追加。
        Assert.assertEquals("RPC_UNARY 值 0 变", 30000, P2PCommand.RPC_UNARY.getValue());
        Assert.assertEquals("RPC_EVENT 值 0 变", 30005, P2PCommand.RPC_EVENT.getValue());
        Assert.assertEquals("DB_ROW_AGG 追加值", 24048, P2PCommand.DB_ROW_AGG.getValue());
        Assert.assertEquals("R_OK_DB_ROW_AGG 追加值", 24049, P2PCommand.R_OK_DB_ROW_AGG.getValue());

        String core = readFile(CORE_CMD_SRC);
        for (String must : new String[]{
            "RPC_UNARY(30000", "RPC_EVENT(30005",
            "DB_ROW_AGG(24048", "R_OK_DB_ROW_AGG(24049"}) {
            Assert.assertTrue("core P2PCommand 缺失: " + must, core.contains(must));
        }

        String sdk = readFile(SDK_CMD_SRC);
        Assert.assertTrue("sdk ordinal 版 P2PCommand 缺 DB_ROW_AGG", sdk.contains("DB_ROW_AGG,"));
        Assert.assertTrue("sdk ordinal 版 P2PCommand 缺 R_OK_DB_ROW_AGG", sdk.contains("R_OK_DB_ROW_AGG"));

        String ordinals = readFile(ORDINALS_SRC);
        for (String must : new String[]{
            "Map.entry(\"RPC_UNARY\", 177)", "Map.entry(\"RPC_EVENT\", 182)",
            "Map.entry(\"DB_ROW_AGG\", 183)", "Map.entry(\"R_OK_DB_ROW_AGG\", 184)"}) {
            Assert.assertTrue("P2PCommandOrdinals 缺失: " + must, ordinals.contains(must));
        }
    }

    // ============== Y4 Facade 单调不减 ==============
    @Test(timeout = 30000)
    public void y4_FacadeMonotonic_UpstreamIntactAndNewClassContract() throws Exception {
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
        Assert.assertEquals("P51 wordnet/ 仍 3 类", 3, countJava(WORDNET_SRC));
        Assert.assertEquals("P39 agg/ 仍 6 类", 6, countJava(AGG_SRC));
        Assert.assertEquals("P41 groupby/ 仍 5 类", 5, countJava(GROUPBY_SRC));
        Assert.assertEquals("P52 columnar/pushdown/ 仍 2 类", 2, countJava(PUSHDOWN_SRC));

        String fts = readFile(FULLTEXT_STORE_SRC);
        Assert.assertFalse("P21 FulltextStore 不应引入 P53 aggpushdown",
                fts.contains("ColumnAggPushdown") || fts.contains("aggpushdown"));

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

        // P50/P52 Facade 契约保留。
        Assert.assertTrue("DbQueryToQueryWrapper 必须 final",
                Modifier.isFinal(DbQueryToQueryWrapper.class.getModifiers()));
        Set<String> pubW = publicMethodNames(DbQueryToQueryWrapper.class);
        Assert.assertTrue("convert 存在: " + pubW, pubW.contains("convert"));
        Assert.assertTrue("hasPredicate 存在: " + pubW, pubW.contains("hasPredicate"));
        Assert.assertTrue("filterIdsByPredicate 存在: " + pubW, pubW.contains("filterIdsByPredicate"));

        Assert.assertTrue("ColumnScanPushdown 必须 final",
                Modifier.isFinal(ColumnScanPushdown.class.getModifiers()));
        Assert.assertEquals("ColumnScanPushdown 仅 1 个私有构造器", 1,
                ColumnScanPushdown.class.getDeclaredConstructors().length);
        Assert.assertTrue("ColumnScanPushdown 构造器必须私有",
                Modifier.isPrivate(ColumnScanPushdown.class.getDeclaredConstructors()[0].getModifiers()));
        Assert.assertEquals("ColumnScanPushdown 零实例字段", 0,
                instanceFieldCount(ColumnScanPushdown.class));
        Set<String> pubS = publicMethodNames(ColumnScanPushdown.class);
        Assert.assertTrue("scan 存在: " + pubS, pubS.contains("scan"));
        Assert.assertTrue("filter 存在: " + pubS, pubS.contains("filter"));
        Assert.assertTrue("buildColumnDefs 存在（P53 复用源，须保持公开）: " + pubS,
                pubS.contains("buildColumnDefs"));

        // P53 新类契约：final + 私有构造 + 0 实例字段 + compute/encode/decode 公开静态方法。
        Assert.assertTrue("ColumnAggPushdown 必须 final",
                Modifier.isFinal(ColumnAggPushdown.class.getModifiers()));
        Assert.assertEquals("ColumnAggPushdown 仅 1 个私有构造器", 1,
                ColumnAggPushdown.class.getDeclaredConstructors().length);
        Assert.assertTrue("ColumnAggPushdown 构造器必须私有",
                Modifier.isPrivate(ColumnAggPushdown.class.getDeclaredConstructors()[0].getModifiers()));
        Assert.assertEquals("ColumnAggPushdown 零实例字段", 0,
                instanceFieldCount(ColumnAggPushdown.class));
        Set<String> pubA = publicMethodNames(ColumnAggPushdown.class);
        for (String m : Arrays.asList("compute", "encode", "decode")) {
            Assert.assertTrue("ColumnAggPushdown 公开方法 missing: " + m + " (have: " + pubA + ")",
                    pubA.contains(m));
        }

        // 受改类契约保留。
        Assert.assertEquals("DbRowAggServerHandler.process 仍 1 参", 1,
                method(javax.net.p2p.server.handler.DbRowAggServerHandler.class, "process")
                        .getParameterCount());
        Assert.assertEquals("DsDatabaseClusterClient.queryAgg 需含 2 参重载", 2,
                findMethodByParamCount(DsDatabaseClusterClient.class, "queryAgg", 2).getParameterCount());
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

    // ============== Y6 P21~P53 编译兼容 ==============
    @Test(timeout = 30000)
    public void y6_P21ToP53CompileCompat_ClassesExist() {
        String[] classes = {
            "com.q3lives.ds.database.fulltext.FulltextSegmenter",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser",
            "com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource",
            "com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor",
            "com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor",
            "com.q3lives.ds.database.fulltext.segment.DsIndexSegment",
            "com.q3lives.ds.database.fulltext.compact.SegmentCompactor",
            "com.q3lives.ds.database.fulltext.layered.LayeredInvertedView",
            "com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource",
            "com.q3lives.ds.database.fulltext.sched.CompactionScheduler",
            "com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook",
            "com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetExporter",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView",
            "com.q3lives.ds.database.columnar.DsDatabaseClusterClient",
            "com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper",
            "com.q3lives.ds.database.columnar.pushdown.ColumnScanPushdown",
            "com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown",
            "javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler",
            "javax.net.p2p.server.handler.DbRowAggServerHandler",
            "javax.net.p2p.model.DbAggFn",
            "javax.net.p2p.model.DbAggItem",
            "javax.net.p2p.model.DbRowAggRequest",
            "javax.net.p2p.model.DbRowAggResponse",
            "javax.net.p2p.model.DbNodeQueryIdsRequest",
            "javax.net.p2p.model.DbRowQueryIdsResponse",
            "javax.net.p2p.model.DbQuery"
        };
        for (String c : classes) {
            try {
                Class.forName(c);
            } catch (ClassNotFoundException cnfe) {
                Assert.fail("class should exist: " + c);
            }
        }
    }

    // ============== Y7 DbQuery 模型字段 0 改 ==============
    @Test(timeout = 30000)
    public void y7_QueryModelFieldsIntact() throws Exception {
        Assert.assertEquals("DbQuery 字段集 0 改",
                new HashSet<>(Arrays.asList("where", "anyOf", "orderBy")),
                fieldNames(DbQuery.class));
        Assert.assertEquals("DbQueryOrder 字段集 0 改",
                new HashSet<>(Arrays.asList("name", "asc")),
                fieldNames(DbQueryOrder.class));
        Assert.assertEquals("DbQueryCriterion 字段集 0 改",
                new HashSet<>(Arrays.asList("op", "name", "a", "b", "list")),
                fieldNames(DbQueryCriterion.class));
    }

    // ============== Y8 段格式 0 变更 ==============
    @Test(timeout = 30000)
    public void y8_SegmentFormatVersionIntact() throws Exception {
        byte[] magic = (byte[]) DsIndexSegment.class.getField("MAGIC").get(null);
        Assert.assertArrayEquals("MAGIC 必须为 'DIDX'", new byte[]{'D', 'I', 'D', 'X'}, magic);
        Assert.assertEquals("VERSION 必须为 1（严禁 VERSION=2）", 1,
                DsIndexSegment.class.getField("VERSION").get(null));
        String seg = readFile(SEGMENT_SRC + "/DsIndexSegment.java");
        Assert.assertFalse("段格式严禁升版 VERSION=2", seg.contains("VERSION = 2"));
    }

    // ============== Y9 0 TODO/FIXME/HACK + 0 新线程 ==============
    @Test(timeout = 30000)
    public void y9_NoTodoFixmeHackAndNoNewThread() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(AGGRUN_SRC), javaFiles);
        for (String rel : new String[]{HANDLER_SRC + "/DbRowAggServerHandler.java", CLIENT_SRC}) {
            javaFiles.add(new File(projectRoot(), rel));
        }
        for (File f : javaFiles) {
            Assert.assertTrue("受检源文件不存在: " + f, f.isFile());
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
            Assert.assertFalse("P53 严禁新线程: " + f.getName(),
                    content.contains("new Thread(") || content.contains("Executors.new"));
        }
    }

    // ============== helpers ==============
    private static File projectRoot() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (d != null) {
            if (new File(d, "p2p-db").isDirectory() && new File(d, "p2p-core").isDirectory()) {
                return d;
            }
            d = d.getParentFile();
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static String readFile(String rel) throws Exception {
        File f = new File(projectRoot(), rel);
        Assert.assertTrue("源文件不存在: " + f.getAbsolutePath(), f.isFile());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static int instanceFieldCount(Class<?> c) {
        int n = 0;
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> publicMethodNames(Class<?> c) {
        Set<String> s = new HashSet<>();
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                s.add(m.getName());
            }
        }
        return s;
    }

    private static Set<String> fieldNames(Class<?> c) {
        Set<String> s = new HashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                s.add(f.getName());
            }
        }
        return s;
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
        File dir = new File(projectRoot(), rel);
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
