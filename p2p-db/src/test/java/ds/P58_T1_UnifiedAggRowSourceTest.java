package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.fulltext.cluster.rowsource.UnifiedAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P58 T1：{@link UnifiedAggRowSource} 统一 FT/列存聚合行源边界 4 TR 0F0E。
 *
 * <p>
 * TR1 入参 IAE 校验（ctor gm/ftClient/ormSource=null、ftK&lt;1、fetch minterm null/空/含
 * null、opt null→defaults）；TR2 主路径与异常（纯 FT / FT+ORM AND 交集 / 多 FT 交集 / FT 无命中 /
 * FT 命中无实体 ISE / 纯 ORM 委托 / docId 升序）；TR3 幂等与兼容（同 minterm 重复 fetch 结果全等、纯 ORM ≢
 * 委托一致、 FT+ORM ≡ {@link FulltextAggRowSource}、多轮无意外异常）；TR4 ZeroLeak
 * 审计（rowsource 源文件数=3、 import 白名单、零裸打印）。
 */
public class P58_T1_UnifiedAggRowSourceTest {

    /**
     * 测试实体：tag 过滤列 + price 数值列。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public Row() {
        }
    }

    /**
     * 可编程 FT 客户端：{@code query → docId 列表}（覆写 {@code searchCluster}，不触真实索引）。
     */
    static class MapFtClient extends DsFulltextClusterClient {

        final AtomicInteger searchCalls = new AtomicInteger();
        final Map<String, List<Long>> table = new HashMap<>();

        MapFtClient(String root) throws IOException {
            super(2, null, root, "shard");
        }

        @Override
        public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) {
            searchCalls.incrementAndGet();
            List<ScoredDocHit> out = new ArrayList<>();
            for (long id : table.getOrDefault(q, Collections.emptyList())) {
                out.add(new ScoredDocHit(id, 0L, 1, 0, 0L, 1.0, 1.0, 0.0, 0.0, 1));
            }
            return out;
        }
    }

    private File tmpRoot;
    private String oldHome;
    private MapFtClient ftClient;
    private GenericManager<Row> gm;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p58_t1_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        File ftRoot = new File(tmpRoot, "ft");
        ftRoot.mkdirs();
        ftClient = new MapFtClient(ftRoot.getAbsolutePath());
        gm = new GenericManager<>(Row.class);
    }

    @After
    public void after() {
        try {
            if (ftClient != null) {
                ftClient.close();
            }
        } catch (Exception ignore) {
        }
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

    private long save(String tag, double price) {
        Row r = new Row();
        r.tag = tag;
        r.price = price;
        gm.saveOrUpdate(r);
        return r.getId();
    }

    private UnifiedAggRowSource<Row> sut() {
        return new UnifiedAggRowSource<>(gm, ftClient,
                new GenericManagerRowSource<>(gm), 20, null);
    }

    private static List<Long> ids(List<Row> rows) {
        List<Long> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.getId());
        }
        return out;
    }

    private static void expectIae(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException ok) {
            return;
        }
        Assert.fail("expected IllegalArgumentException");
    }

    // ============== TR1 入参 IAE 校验 ==============
    @Test(timeout = 30000)
    public void tr11_ctorNullArgsThrowIae() {
        GenericManagerRowSource<Row> orm = new GenericManagerRowSource<>(gm);
        expectIae(() -> new UnifiedAggRowSource<>(null, ftClient, orm, 10, null));
        expectIae(() -> new UnifiedAggRowSource<>(gm, null, orm, 10, null));
        expectIae(() -> new UnifiedAggRowSource<>(gm, ftClient, null, 10, null));
        expectIae(() -> new UnifiedAggRowSource<Row>(gm, ftClient, orm, 0, null));
        expectIae(() -> new UnifiedAggRowSource<Row>(gm, ftClient, orm, -1, null));
        Assert.assertNotNull("ftK=1 为合法下界", new UnifiedAggRowSource<>(gm, ftClient, orm, 1, null));
    }

    @Test(timeout = 30000)
    public void tr12_fetchIaeMatrix() {
        UnifiedAggRowSource<Row> src = sut();
        expectIae(() -> src.fetch(null));
        expectIae(() -> src.fetch(new ArrayList<>()));
        List<ConditionNode> withNull = new ArrayList<>();
        withNull.add(ConditionNode.orm("tag", DslToken.OP_EQ, "x"));
        withNull.add(null);
        expectIae(() -> src.fetch(withNull));
        Assert.assertEquals("IAE 前置校验 0 次 FT 调用", 0, ftClient.searchCalls.get());
    }

    @Test(timeout = 30000)
    public void tr13_optNullFallsBackToDefaults() throws Exception {
        UnifiedAggRowSource<Row> src = sut();
        Field optField = UnifiedAggRowSource.class.getDeclaredField("opt");
        optField.setAccessible(true);
        Assert.assertNotNull("opt=null 必须回落到 SearchOptions.defaults()", optField.get(src));

        SearchOptions custom = SearchOptions.defaults();
        UnifiedAggRowSource<Row> src2 = new UnifiedAggRowSource<>(gm, ftClient,
                new GenericManagerRowSource<>(gm), 7, custom);
        Assert.assertSame("显式 opt 必须原样保留（不复制）", custom, optField.get(src2));
    }

    // ============== TR2 主路径与异常 ==============
    @Test(timeout = 30000)
    public void tr21_pureFtMaterializesHits() {
        long a = save("x", 1.0);
        long b = save("y", 2.0);
        save("z", 3.0);
        ftClient.table.put("q1", list(a, b));

        List<Row> rows = sut().fetch(Collections.singletonList(ConditionNode.ft("q1")));
        Assert.assertEquals("纯 FT 命中 2 行", list(a, b), ids(rows));
        Assert.assertEquals("FT 检索 1 次", 1, ftClient.searchCalls.get());
    }

    @Test(timeout = 30000)
    public void tr22_ftPlusOrmAndIntersection() {
        long x1 = save("x", 1.0);
        long x2 = save("x", 2.0);
        long y1 = save("y", 3.0);
        long y2 = save("y", 4.0);
        ftClient.table.put("q1", list(x1, x2, y1));

        List<ConditionNode> minterm = list(ConditionNode.ft("q1"),
                ConditionNode.orm("tag", DslToken.OP_EQ, "x"));
        Assert.assertEquals("FT ∩ ORM(tag=x) = {x1,x2}", list(x1, x2), ids(sut().fetch(minterm)));
        Assert.assertNotEquals("y2 不在 FT 命中内", Boolean.TRUE, ids(sut().fetch(minterm)).contains(y2));
    }

    @Test(timeout = 30000)
    public void tr23_multiFtNodesIntersect() {
        long a = save("x", 1.0);
        long b = save("x", 2.0);
        long c = save("x", 3.0);
        save("x", 4.0);
        ftClient.table.put("q1", list(a, b, c));
        ftClient.table.put("q2", list(b, c));

        List<Row> rows = sut().fetch(list(ConditionNode.ft("q1"), ConditionNode.ft("q2")));
        Assert.assertEquals("多 FT 取 AND 交集 = {b,c}", list(b, c), ids(rows));
        Assert.assertEquals("2 个 FT 节点各检索 1 次", 2, ftClient.searchCalls.get());
    }

    @Test(timeout = 30000)
    public void tr24_ftNoHitReturnsEmpty() {
        save("x", 1.0);
        ftClient.table.put("q1", Collections.emptyList());
        Assert.assertTrue("FT 无命中 → 空列表", sut().fetch(
                Collections.singletonList(ConditionNode.ft("q1"))).isEmpty());
    }

    @Test(timeout = 30000)
    public void tr25_ftHitMissingEntityThrowsIse() {
        long a = save("x", 1.0);
        // ghost：分配有效 id 后删除 → getById 返回 null（FT 命中但无实体，违反强一致）
        long ghost = save("ghost", 9.0);
        gm.removeById(ghost);
        Assert.assertNull("删除后 getById 必须 null", gm.getById(ghost));
        ftClient.table.put("q1", list(a, ghost));
        try {
            sut().fetch(Collections.singletonList(ConditionNode.ft("q1")));
            Assert.fail("FT 命中无实体必须抛 ISE");
        } catch (IllegalStateException ex) {
            Assert.assertTrue("miss docId 抛 ISE，不静默",
                    ex.getMessage().contains(String.valueOf(ghost)));
        }
    }

    @Test(timeout = 30000)
    public void tr26_pureOrmDelegatesToOrmSource() {
        long x1 = save("x", 1.0);
        save("y", 2.0);
        List<ConditionNode> minterm = list(ConditionNode.orm("tag", DslToken.OP_EQ, "x"));

        UnifiedAggRowSource<Row> unified = new UnifiedAggRowSource<>(gm, ftClient,
                new ColumnarAggRowSource<>(gm, null, null), 20, null);
        List<Row> viaUnified = unified.fetch(minterm);
        List<Row> viaGeneric = new GenericManagerRowSource<>(gm).fetch(minterm);
        Assert.assertEquals("纯 ORM 委托 ormSource（列存本地双模）≡ GenericManagerRowSource",
                ids(viaGeneric), ids(viaUnified));
        Assert.assertEquals("纯 ORM 路径 0 次 FT 调用", 0, ftClient.searchCalls.get());
        Assert.assertEquals("命中 x1", list(x1), ids(viaUnified));
    }

    @Test(timeout = 30000)
    public void tr27_resultAscendingByDocId() {
        long c = save("x", 3.0);
        long a = save("x", 1.0);
        long b = save("x", 2.0);
        ftClient.table.put("q1", list(c, a, b));
        List<Long> got = ids(sut().fetch(Collections.singletonList(ConditionNode.ft("q1"))));
        List<Long> sorted = new ArrayList<>(got);
        Collections.sort(sorted);
        Assert.assertEquals("FT 命中结果必须按 docId 升序", sorted, got);
    }

    // ============== TR3 幂等与兼容 ==============
    @Test(timeout = 30000)
    public void tr31_repeatedFetchIsIdempotent() {
        long a = save("x", 1.0);
        long b = save("x", 2.0);
        ftClient.table.put("q1", list(a, b));
        UnifiedAggRowSource<Row> src = sut();
        List<ConditionNode> minterm = list(ConditionNode.ft("q1"),
                ConditionNode.orm("tag", DslToken.OP_EQ, "x"));
        List<Long> first = ids(src.fetch(minterm));
        for (int i = 0; i < 20; i++) {
            Assert.assertEquals("同 minterm 重复 fetch 结果必须全等（第 " + i + " 次）", first,
                    ids(src.fetch(minterm)));
        }
    }

    @Test(timeout = 30000)
    public void tr32_ftPlusOrmEquivToFulltextAggRowSource() {
        long x1 = save("x", 1.0);
        long x2 = save("x", 2.0);
        long y1 = save("y", 3.0);
        ftClient.table.put("q1", list(x1, x2, y1));
        List<ConditionNode> minterm = list(ConditionNode.ft("q1"),
                ConditionNode.orm("tag", DslToken.OP_EQ, "x"));

        List<Long> viaUnified = ids(sut().fetch(minterm));
        List<Long> viaFulltext = ids(new FulltextAggRowSource<>(gm, ftClient, 20, null).fetch(minterm));
        Assert.assertEquals("FT+ORM 路径 ≡ FulltextAggRowSource", viaFulltext, viaUnified);
        Assert.assertEquals("命中 {x1,x2}", list(x1, x2), viaUnified);
    }

    @Test(timeout = 30000)
    public void tr33_multiRoundNoUnexpectedException() {
        long a = save("x", 1.0);
        save("y", 2.0);
        ftClient.table.put("q1", list(a));
        ftClient.table.put("none", Collections.emptyList());
        UnifiedAggRowSource<Row> src = sut();
        int unexpected = 0;
        for (int i = 0; i < 50; i++) {
            try {
                Assert.assertNotNull(src.fetch(Collections.singletonList(ConditionNode.ft("q1"))));
                Assert.assertNotNull(src.fetch(list(ConditionNode.ft("q1"),
                        ConditionNode.orm("tag", DslToken.OP_EQ, "x"))));
                Assert.assertTrue(src.fetch(Collections.singletonList(ConditionNode.ft("none"))).isEmpty());
                Assert.assertEquals(1, ormCount("x"));
            } catch (Throwable t) {
                unexpected++;
            }
        }
        Assert.assertEquals("6 场景 ×50 迭代 意外异常 = 0", 0, unexpected);
    }

    private int ormCount(String tag) {
        return new GenericManagerRowSource<Row>(gm)
                .fetch(list(ConditionNode.orm("tag", DslToken.OP_EQ, tag))).size();
    }

    // ============== TR4 ZeroLeak 审计 ==============
    @Test(timeout = 30000)
    public void tr41_rowsourceFileCountIsThree() {
        Path dir = new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource").toPath();
        Assert.assertTrue("rowsource 源目录必须存在", Files.isDirectory(dir));
        int n = 0;
        try {
            n = (int) Files.list(dir).filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java")).count();
        } catch (IOException e) {
            Assert.fail("列出 rowsource 失败: " + e.getMessage());
        }
        Assert.assertEquals("rowsource 源文件数（P45 1 + P46 1 + P58 1）", 3, n);
    }

    @Test(timeout = 30000)
    public void tr42_importsJdkAndFirstPartyOnly() throws Exception {
        Path p = new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource/UnifiedAggRowSource.java")
                .toPath();
        Assert.assertTrue("UnifiedAggRowSource.java 必须存在", Files.isRegularFile(p));
        int bad = 0;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!t.startsWith("import ")) {
                continue;
            }
            boolean ok = t.startsWith("import java.") || t.contains("com.q3lives.ds.");
            if (!ok) {
                bad++;
            }
        }
        Assert.assertEquals("只允许 JDK + first-party import", 0, bad);
    }

    @Test(timeout = 30000)
    public void tr43_noBarePrint() throws Exception {
        Path dir = new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource").toPath();
        int bare = 0;
        for (Path f : Files.list(dir).filter(x -> x.toString().endsWith(".java")).toList()) {
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = ln.trim();
                if (t.contains("System.out.print") || t.contains("System.err.print")
                        || t.contains("printStackTrace")) {
                    bare++;
                }
            }
        }
        Assert.assertEquals("rowsource 零裸打印/堆栈打印", 0, bare);
    }

    private static <E> List<E> list(E... items) {
        List<E> out = new ArrayList<>();
        Collections.addAll(out, items);
        return out;
    }
}
