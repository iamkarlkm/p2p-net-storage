package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P45 T2：{@link FulltextAggRowSource} 三条路径等价性 6 TR 0F0E。
 *
 * <p>
 * TR2.1 纯 ORM minterm ≡ {@link GenericManagerRowSource} 逐字段全等（复用 Q1=A 的
 * {@code toWrapper}）；TR2.2 纯 FT 命中 → docId → {@code getById}；TR2.3 FT+ORM
 * 混合取交集； TR2.4 多 FT 节点取交集（Q2）；TR2.5 FT 无命中 → 空集；TR2.6 FT 命中但无 ORM 实体 → ISE（强一致
 * R7）。
 */
public class P45_T2_PathEquivalenceTest {

    /**
     * 测试实体。
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
     * 可编程 FT 客户端：query → docId 列表（不触真实索引，保证路径语义可精确断言）。
     */
    static class MapFtClient extends DsFulltextClusterClient {

        final Map<String, List<Long>> hits = new HashMap<>();
        final List<String> queries = new ArrayList<>();

        MapFtClient(String root) throws IOException {
            super(2, null, root, "shard");
        }

        @Override
        public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) {
            queries.add(q);
            List<ScoredDocHit> out = new ArrayList<>();
            for (long id : hits.getOrDefault(q, Collections.emptyList())) {
                out.add(new ScoredDocHit(id, 0L, 1, 0, 0L, 1.0, 1.0, 0.0, 0.0, 1));
            }
            return out;
        }
    }

    private File tmpRoot;
    private String oldHome;
    private MapFtClient ftClient;
    private GenericManager<Row> gm;
    private FulltextAggRowSource<Row> src;
    private long id1;
    private long id2;
    private long id3;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p45_t2_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        File ftRoot = new File(tmpRoot, "ft");
        ftRoot.mkdirs();
        ftClient = new MapFtClient(ftRoot.getAbsolutePath());
        gm = new GenericManager<>(Row.class);
        id1 = save("x", 150.0);
        id2 = save("x", 50.0);
        id3 = save("y", 200.0);
        src = new FulltextAggRowSource<>(gm, ftClient, 100, SearchOptions.defaults());
    }

    private long save(String tag, Double price) {
        Row e = new Row();
        e.tag = tag;
        e.price = price;
        gm.saveOrUpdate(e);
        return e.getId();
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

    private static List<Long> ids(List<Row> rows) {
        List<Long> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(r.getId());
        }
        return out;
    }

    private static void assertSameContent(Row a, Row b) {
        Assert.assertEquals("id 全等", a.getId(), b.getId());
        Assert.assertEquals("tag 全等", a.tag, b.tag);
        Assert.assertEquals("price 全等", a.price, b.price);
    }

    // ============== TR2.1 纯 ORM ≡ GenericManagerRowSource ==============
    @Test(timeout = 30000)
    public void tr21_pureOrmEquivalentToGenericManagerRowSource() {
        List<ConditionNode> minterm = Collections.singletonList(
                ConditionNode.orm("tag", DslToken.OP_EQ, "x"));
        List<Row> expected = new GenericManagerRowSource<>(gm).fetch(minterm);
        List<Row> actual = src.fetch(minterm);

        Assert.assertEquals("纯 ORM 命中行数全等", expected.size(), actual.size());
        Assert.assertEquals("纯 ORM docId 升序顺序全等",
                ids(expected), ids(actual));
        for (int i = 0; i < expected.size(); i++) {
            assertSameContent(expected.get(i), actual.get(i));
        }
        Assert.assertTrue("纯 ORM 路径 0 次 FT 调用", ftClient.queries.isEmpty());
    }

    // ============== TR2.2 纯 FT 命中 ==============
    @Test(timeout = 30000)
    public void tr22_pureFtHitsResolveEntities() {
        ftClient.hits.put("java", Arrays.asList(id1, id2));
        List<Row> rows = src.fetch(Collections.singletonList(ConditionNode.ft("java")));

        Assert.assertEquals("FT(java) 命中 2 行", 2, rows.size());
        Assert.assertEquals("按 docId 升序", Arrays.asList(id1, id2), ids(rows));
        for (Row r : rows) {
            assertSameContent(gm.getById(r.getId()), r);
        }
        Assert.assertEquals("仅 1 次 FT 检索", Collections.singletonList("java"), ftClient.queries);
    }

    // ============== TR2.3 FT + ORM 混合取交集 ==============
    @Test(timeout = 30000)
    public void tr23_ftOrmMixedIntersection() {
        ftClient.hits.put("java", Arrays.asList(id1, id2));
        List<ConditionNode> minterm = Arrays.asList(
                ConditionNode.ft("java"),
                ConditionNode.orm("price", DslToken.OP_GT, 100.0));
        List<Row> rows = src.fetch(minterm);

        // FT {id1,id2} ∩ ORM(price>100) {id1(150),id3(200)} = {id1}
        Assert.assertEquals("混合交集命中 1 行", 1, rows.size());
        Assert.assertEquals(Arrays.asList(id1), ids(rows));
        Assert.assertEquals("交集行 content 全等", 150.0, rows.get(0).price, 0.0);
    }

    // ============== TR2.4 多 FT 节点取交集（Q2） ==============
    @Test(timeout = 30000)
    public void tr24_multiFtNodesIntersection() {
        ftClient.hits.put("java", Arrays.asList(id1, id2));
        ftClient.hits.put("vm", Arrays.asList(id1, id3));
        List<ConditionNode> minterm = Arrays.asList(
                ConditionNode.ft("java"),
                ConditionNode.ft("vm"));
        List<Row> rows = src.fetch(minterm);

        // {id1,id2} ∩ {id1,id3} = {id1}
        Assert.assertEquals("多 FT 节点取交集 1 行", 1, rows.size());
        Assert.assertEquals(Arrays.asList(id1), ids(rows));
        Assert.assertEquals("两个 FT 查询均执行且按序", Arrays.asList("java", "vm"), ftClient.queries);
    }

    // ============== TR2.5 FT 无命中 → 空集 ==============
    @Test(timeout = 30000)
    public void tr25_ftNoHitReturnsEmpty() {
        List<Row> rows = src.fetch(Collections.singletonList(ConditionNode.ft("no_such_term")));
        Assert.assertTrue("FT 无命中 → 空列表", rows.isEmpty());
        Assert.assertEquals(Collections.singletonList("no_such_term"), ftClient.queries);
    }

    // ============== TR2.6 FT 命中但无 ORM 实体 → ISE（强一致 R7） ==============
    @Test(timeout = 30000)
    public void tr26_ftHitButNoEntityThrowsIse() {
        // 构造「有效 id 槽位但实体已被删除」：先分配 id 再 removeById，getById 返回 null
        long ghostId = save("z", 1.0);
        gm.removeById(ghostId);
        Assert.assertNull("删除后 getById 必须 null", gm.getById(ghostId));
        ftClient.hits.put("ghost", Collections.singletonList(ghostId));
        try {
            src.fetch(Collections.singletonList(ConditionNode.ft("ghost")));
            Assert.fail("FT 命中 docId 无对应 ORM 实体 必须抛 ISE 不静默");
        } catch (IllegalStateException ex) {
            Assert.assertTrue("强一致 ISE: " + ex.getMessage(),
                    ex.getMessage().contains(String.valueOf(ghostId)));
        }
    }

}
