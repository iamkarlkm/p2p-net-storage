package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByResult;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec.HavingOp;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.model.DbQuery;

/**
 * P46 T3：{@link ColumnarAggRowSource} 与聚合执行器集成 3 TR 0F0E。
 *
 * <p>
 * TR3.1 {@link AggExecutor} 端到端（列存条件 COUNT/SUM）；TR3.2 {@link GroupByExecutor}（列存条件
 * 分组 + HAVING）；TR3.3 minterm 重叠 → union 按 PK 去重。执行器源码 0 改，仅依赖 {@code AggRowSource}
 * 接口。
 */
public class P46_T3_ExecutorIntegrationTest {

    /** 测试实体。 */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public Row() {
        }
    }

    private static final DsServerDirectory EMPTY_DIR = new DsServerDirectory() {
        @Override
        public int[] listServerIds() {
            return new int[0];
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return null;
        }
    };

    /** 可编程列存客户端：按 minterm 序弹出预设 id 列表（无剩余 → 空）。 */
    static class QueueColClient extends DsDatabaseClusterClient {

        final Deque<List<Long>> results = new ArrayDeque<>();

        QueueColClient(DsPartitionManager m, DsHashMapIdAllocator a) {
            super(1, m, a, EMPTY_DIR);
        }

        void enqueue(Long... ids) {
            results.add(Arrays.asList(ids));
        }

        @Override
        public List<Long> queryIds(String entityClassName, DbQuery query, int offset, int limit) {
            List<Long> next = results.poll();
            return next == null ? new ArrayList<>() : new ArrayList<>(next);
        }
    }

    private static final java.util.Map<String, ToDoubleFunction<Row>> AGG_COLS =
            Collections.singletonMap("price", r -> r.price == null ? Double.NaN : r.price);
    private static final java.util.Map<String, Function<Row, String>> GROUP_COLS =
            Collections.singletonMap("tag", r -> r.tag);

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private QueueColClient col;
    private ColumnarAggRowSource<Row> src;
    private long id1;
    private long id2;
    private long id3;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        id1 = save("eng", 150.0);
        id2 = save("eng", 50.0);
        id3 = save("ops", 200.0);

        DsPartitionManager manager =
                new DsPartitionManager(new File(tmpRoot, "pm"), DsPartitionRouter.maxPartitionCount(),
                        new String[0]);
        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        col = new QueueColClient(manager, allocator);
        src = new ColumnarAggRowSource<>(gm, col, Row.class.getName());
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
        if (col != null) {
            col.close();
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

    @SafeVarargs
    private static DnfClause clause(List<ConditionNode>... minterms) {
        return new DnfClause(new ArrayList<>(Arrays.asList(minterms)));
    }

    private static List<ConditionNode> priceGt(double v) {
        return Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, v));
    }

    // ============== TR3.1 AggExecutor 端到端（列存条件 COUNT/SUM） ==============

    @Test
    public void tr31_aggExecutorColumnarCountSum() {
        col.enqueue(id1, id2);
        AggQueryPlan p = plan(clause(priceGt(0.0)),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        AggResult r = new AggExecutor<Row>().execute(p, src, AGG_COLS);
        Assert.assertEquals("2 聚合项", 2, r.size());
        Assert.assertEquals("union 行数 = 2", 2L, r.unionRowCount);
        Assert.assertEquals("COUNT(price) = 2", 2L, r.value(0).count);
        Assert.assertEquals("SUM(price) = 200", 200.0, r.value(1).value, 1e-9);
    }

    // ============== TR3.2 GroupByExecutor（列存条件分组 + HAVING） ==============

    @Test
    public void tr32_groupByExecutorColumnarGroupHaving() {
        col.enqueue(id1, id2, id3);
        AggQueryPlan p = plan(clause(priceGt(0.0)),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        GroupBySpec noHv = new GroupBySpec(p, Collections.singletonList("tag"),
                Collections.emptyList(), Collections.emptyList());
        GroupByResult rAll = new GroupByExecutor<Row>().execute(noHv, src, GROUP_COLS, AGG_COLS);
        Assert.assertEquals("total 行数 = 3", 3L, rAll.totalRowCount);
        Assert.assertEquals("2 桶", 2, rAll.size());
        Assert.assertEquals("桶0 key=eng", Collections.singletonList("eng"), rAll.bucket(0).key);
        Assert.assertEquals("桶0 COUNT=2", 2.0, rAll.bucket(0).values.get(0).value, 0.0);
        Assert.assertEquals("桶0 SUM=200", 200.0, rAll.bucket(0).values.get(1).value, 0.0);
        Assert.assertEquals("桶1 key=ops", Collections.singletonList("ops"), rAll.bucket(1).key);

        col.enqueue(id1, id2, id3);
        GroupBySpec withHv = new GroupBySpec(p, Collections.singletonList("tag"),
                Collections.emptyList(),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 2.0)));
        GroupByResult rHv = new GroupByExecutor<Row>().execute(withHv, src, GROUP_COLS, AGG_COLS);
        Assert.assertEquals("HAVING 后 1 桶", 1, rHv.size());
        Assert.assertEquals("HAVING 保留 eng", Collections.singletonList("eng"), rHv.bucket(0).key);
    }

    // ============== TR3.3 minterm 重叠 → union 按 PK 去重 ==============

    @Test
    public void tr33_overlappingMintermsUnionDedup() {
        col.enqueue(id1, id2);
        col.enqueue(id2, id3);
        AggQueryPlan p = plan(clause(priceGt(0.0), priceGt(100.0)),
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"));

        AggResult r = new AggExecutor<Row>().execute(p, src, AGG_COLS);
        // {id1,id2} ∪ {id2,id3} = {id1,id2,id3}，id2 只计 1 次
        Assert.assertEquals("union 去重后行数 = 3", 3L, r.unionRowCount);
        Assert.assertEquals("COUNT(price) = 3", 3L, r.value(0).count);
        Assert.assertEquals("SUM(price) = 400", 400.0, r.value(1).value, 1e-9);
    }
}
