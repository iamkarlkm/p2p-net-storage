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
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

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
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByResult;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec.HavingOp;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P45 T3：{@link FulltextAggRowSource} 与聚合执行器集成 3 TR 0F0E。
 *
 * <p>
 * TR3.1 {@link AggExecutor} 端到端（FT 条件 COUNT/SUM）；TR3.2 {@link GroupByExecutor}（FT 条件分组 +
 * HAVING）；TR3.3 minterm 重叠 → union 按 PK 去重。执行器源码 0 改，仅依赖 {@link AggRowSource} 接口。
 */
public class P45_T3_ExecutorIntegrationTest {

    /** 测试实体。 */
    public static class Row extends DsTableAdapter {
        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public Row() {
        }
    }

    /** 可编程 FT 客户端（query → docId 列表）。 */
    static class MapFtClient extends DsFulltextClusterClient {
        final Map<String, List<Long>> hits = new HashMap<>();

        MapFtClient(String root) throws IOException {
            super(2, null, root, "shard");
        }

        @Override
        public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) {
            List<ScoredDocHit> out = new ArrayList<>();
            for (long id : hits.getOrDefault(q, Collections.emptyList())) {
                out.add(new ScoredDocHit(id, 0L, 1, 0, 0L, 1.0, 1.0, 0.0, 0.0, 1));
            }
            return out;
        }
    }

    private static final Map<String, ToDoubleFunction<Row>> AGG_COLS =
            Collections.singletonMap("price", r -> r.price == null ? Double.NaN : r.price);
    private static final Map<String, Function<Row, String>> GROUP_COLS =
            Collections.singletonMap("tag", r -> r.tag);

    private File tmpRoot;
    private String oldHome;
    private MapFtClient ftClient;
    private GenericManager<Row> gm;
    private AggRowSource<Row> src;
    private long id1;
    private long id2;
    private long id3;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p45_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        File ftRoot = new File(tmpRoot, "ft");
        ftRoot.mkdirs();
        ftClient = new MapFtClient(ftRoot.getAbsolutePath());
        gm = new GenericManager<>(Row.class);
        id1 = save("eng", 150.0);
        id2 = save("eng", 50.0);
        id3 = save("ops", 200.0);
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

    private static AggQueryPlan plan(DnfClause clause, AggSpec... aggs) {
        return new AggQueryPlan(clause, Arrays.asList(aggs));
    }

    private static DnfClause clause(List<ConditionNode>... minterms) {
        List<List<ConditionNode>> list = new ArrayList<>();
        for (List<ConditionNode> m : minterms) {
            list.add(m);
        }
        return new DnfClause(list);
    }

    // ============== TR3.1 AggExecutor 端到端（FT 条件 COUNT/SUM） ==============

    @Test(timeout = 60000)
    public void tr31_aggExecutorFtCountSum() {
        ftClient.hits.put("java", Arrays.asList(id1, id2));
        AggQueryPlan p = plan(clause(Collections.singletonList(ConditionNode.ft("java"))),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        AggResult r = new AggExecutor<Row>().execute(p, src, AGG_COLS);
        Assert.assertEquals("2 聚合项", 2, r.size());
        Assert.assertEquals("union 行数 = 2", 2L, r.unionRowCount);
        Assert.assertEquals("COUNT(price) = 2", 2L, r.value(0).count);
        Assert.assertEquals("SUM(price) = 200", 200.0, r.value(1).value, 1e-9);
    }

    // ============== TR3.2 GroupByExecutor（FT 条件分组 + HAVING） ==============

    @Test(timeout = 60000)
    public void tr32_groupByExecutorFtGroupHaving() {
        ftClient.hits.put("java", Arrays.asList(id1, id2, id3));
        AggQueryPlan p = plan(clause(Collections.singletonList(ConditionNode.ft("java"))),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        // 无 HAVING：eng(2,200) + ops(1,200)，按 tag 字典序 eng < ops
        GroupBySpec noHv = new GroupBySpec(p, Collections.singletonList("tag"),
                Collections.emptyList(), Collections.emptyList());
        GroupByResult rAll = new GroupByExecutor<Row>().execute(noHv, src, GROUP_COLS, AGG_COLS);
        Assert.assertEquals("total 行数 = 3", 3L, rAll.totalRowCount);
        Assert.assertEquals("2 桶", 2, rAll.size());
        Assert.assertEquals("桶0 key=eng", Collections.singletonList("eng"), rAll.bucket(0).key);
        Assert.assertEquals("桶0 COUNT=2", 2.0, rAll.bucket(0).values.get(0).value, 0.0);
        Assert.assertEquals("桶0 SUM=200", 200.0, rAll.bucket(0).values.get(1).value, 0.0);
        Assert.assertEquals("桶1 key=ops", Collections.singletonList("ops"), rAll.bucket(1).key);
        Assert.assertEquals("桶1 COUNT=1", 1.0, rAll.bucket(1).values.get(0).value, 0.0);

        // HAVING COUNT(price) >= 2：剔除 ops（count=1）
        GroupBySpec withHv = new GroupBySpec(p, Collections.singletonList("tag"),
                Collections.emptyList(),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 2.0)));
        GroupByResult rHv = new GroupByExecutor<Row>().execute(withHv, src, GROUP_COLS, AGG_COLS);
        Assert.assertEquals("HAVING 后 1 桶", 1, rHv.size());
        Assert.assertEquals("HAVING 保留 eng", Collections.singletonList("eng"), rHv.bucket(0).key);
    }

    // ============== TR3.3 minterm 重叠 → union 按 PK 去重 ==============

    @Test(timeout = 60000)
    public void tr33_overlappingMintermsUnionDedup() {
        ftClient.hits.put("java", Arrays.asList(id1, id2));
        ftClient.hits.put("vm", Arrays.asList(id2, id3));
        AggQueryPlan p = plan(
                clause(Collections.singletonList(ConditionNode.ft("java")),
                        Collections.singletonList(ConditionNode.ft("vm"))),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        AggResult r = new AggExecutor<Row>().execute(p, src, AGG_COLS);
        // {id1,id2} ∪ {id2,id3} = {id1,id2,id3}，id2 只计 1 次
        Assert.assertEquals("union 去重后行数 = 3", 3L, r.unionRowCount);
        Assert.assertEquals("COUNT(price) = 3", 3L, r.value(0).count);
        Assert.assertEquals("SUM(price) = 400", 400.0, r.value(1).value, 1e-9);
    }
}
