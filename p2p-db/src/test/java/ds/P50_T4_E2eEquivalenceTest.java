package ds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.columnar.pushdown.DbQueryToQueryWrapper;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbNodeQueryIdsRequest;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler;
import javax.net.p2p.utils.SerializationUtil;

/**
 * P50 T4：真实 DB + 真实列存 + 真实 handler 端到端等价 4 TR 0F0E。
 *
 * <p>
 * TR4.1 服务端（{@link DbNodeQueryIdsServerHandler}）过滤结果 ≡ 本地全量物化过滤结果（独立
 * {@link GenericManager#getById} 逐行物化 + 字段比对，R12 谓词语义全等）；TR4.2 与 P46
 * {@link ColumnarAggRowSource} 端到端一致（{@code AggParser.parse → AggExecutor.execute} vs 手算真值，
 * 证明谓词过滤真实生效）；TR4.3 多分区路由（跨 partitionId 的 id 聚合 + 过滤）；TR4.4 强一致契约（过滤命中
 * id 的 {@code getById} 必非 null + 无 ORM 行的伪造 id 必被过滤剔除）。
 *
 * <p>
 * 双写对齐（绕开根目录不一致）：ORM 行经 {@link GenericManager} 落 {@code p2p.db.home}；列存候选 id 直接
 * 登记进目标分区 ids 集合（{@link DynamicIndexUtil#idsFile}），使服务端/本地过滤命中的 id 与 ORM 行一一对应。
 */
public class P50_T4_E2eEquivalenceTest {

    /**
     * 测试实体：tag 为谓词列；price 以文本列承载（供聚合；null 可被真实存储往返）。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price_txt", length = 16)
        public String priceTxt;

        public Row() {
        }
    }

    /**
     * 列存条件 + 2 聚合（COUNT/SUM）：仅命中 tag='x' 的行参与聚合。
     */
    private static final String DSL = "tag = 'x' AGG(COUNT(price), SUM(price))";

    /**
     * 手算真值（tag='x' 行 price=150/50/200/null）：union=4, COUNT=3, SUM=400（tag='y' 行必须被过滤）。
     */
    private static final long EXPECT_UNION = 4L;
    private static final long EXPECT_COUNT = 3L;
    private static final double EXPECT_SUM = 400.0;

    private static final Map<String, ToDoubleFunction<Row>> COLS
            = Collections.singletonMap("price",
                    r -> r.priceTxt == null ? Double.NaN : Double.parseDouble(r.priceTxt));

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private DsPartitionManager manager;
    private DbNodeQueryIdsServerHandler handler;
    private int sid;
    private long id1;
    private long id2;
    private long id3;
    private long id4;
    private long id5;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p50_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        id1 = save("x", "150");
        id2 = save("x", "50");
        id3 = save("y", "999");
        id4 = save("x", "200");
        id5 = save("x", null);

        sid = DsIdLayout.serverId(id1);
        manager = new DsPartitionManager(new File(tmpRoot, "col"),
                DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(sid, manager);
        handler = new DbNodeQueryIdsServerHandler();
    }

    @After
    public void after() {
        DsNodeContext.clear();
        if (manager != null) {
            try {
                manager.close();
            } catch (IOException ignore) {
            }
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private long save(String tag, String priceTxt) {
        Row e = new Row();
        e.tag = tag;
        e.priceTxt = priceTxt;
        gm.saveOrUpdate(e);
        return e.getId();
    }

    /**
     * 把 rowId 登记进指定分区（serverId 固定为 {@link #sid}）的实体 ids 集合。
     */
    private void registerIds(int partitionId, long... rowIds) throws IOException {
        long anchor = DsIdLayout.compose(sid, partitionId, 1L);
        File dbRoot = manager.getStore(anchor).getDbRoot();
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
        }
    }

    private long[] serverHits(DbQuery q, int offset, int limit) {
        long anchor = DsIdLayout.compose(sid, 0, 1L);
        P2PWrapper req = P2PWrapper.build(11, P2PCommand.DB_NODE_QUERY_IDS,
                new DbNodeQueryIdsRequest(Row.class.getName(), anchor, q, offset, limit));
        P2PWrapper resp = handler.process(req);
        Assert.assertEquals("响应命令 R_OK_DB_NODE_QUERY_IDS", P2PCommand.R_OK_DB_NODE_QUERY_IDS, resp.getCommand());
        long[] out = SerializationUtil.deserialize(long[].class,
                ((DbRowQueryIdsResponse) resp.getData()).idsBytes);
        Assert.assertNotNull("idsBytes 反序列化非 null", out);
        return out;
    }

    private static List<Long> box(long[] v) {
        List<Long> out = new ArrayList<>(v.length);
        for (long x : v) {
            out.add(x);
        }
        return out;
    }

    private static DbQuery eq(String col, String val) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, col, val, null, null));
        return q;
    }

    /**
     * 本地全量物化过滤（独立实现）：逐 id {@link GenericManager#getById} 物化 + 字段比对，不经谓词引擎。
     */
    private List<Long> localMaterialize(long[] full, String tag) {
        List<Long> out = new ArrayList<>();
        for (long id : full) {
            Row r = gm.getById(id);
            if (r != null && tag.equals(r.tag)) {
                out.add(id);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Long> localEngine(DbQuery q, List<Long> full) {
        QueryWrapper<Row> w = (QueryWrapper<Row>) DbQueryToQueryWrapper.convert(q);
        List<Long> matched = gm.listIds(w);
        Collections.sort(matched);
        List<Long> out = new ArrayList<>();
        for (Long id : full) {
            if (matched.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    // ============== TR4.1 服务端过滤 ≡ 本地全量物化过滤（逐条等价，R12） ==============
    @Test(timeout = 120000)
    public void tr41_serverFilterEqualsLocalMaterialize() throws Exception {
        registerIds(0, id1, id2, id3, id4, id5);
        long[] full = new long[]{id1, id2, id3, id4, id5};
        Arrays.sort(full);
        List<Long> fullList = box(full);

        for (String tag : new String[]{"x", "y"}) {
            DbQuery q = eq("tag", tag);
            List<Long> server = box(serverHits(q, 0, 100));
            List<Long> materialized = localMaterialize(full, tag);
            List<Long> engine = localEngine(q, fullList);

            Assert.assertEquals("TR4.1 服务端 == 本地物化（tag=" + tag + "）", materialized, server);
            Assert.assertEquals("TR4.1 服务端 == 谓词引擎本地过滤（tag=" + tag + "）", engine, server);
            List<Long> sorted = new ArrayList<>(server);
            Collections.sort(sorted);
            Assert.assertEquals("R11 输出升序确定性", sorted, server);
        }
    }

    // ============== TR4.2 与 P46 ColumnarAggRowSource 端到端一致 ==============
    @Test(timeout = 120000)
    public void tr42_columnarAggRowSourceEndToEnd() throws Exception {
        registerIds(0, id1, id2, id3, id4, id5);
        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        DsDatabaseClusterClient col = newClient(allocator);
        try {
            AggQueryPlan plan = AggParser.parse(Row.class, DSL, Map.of(), Map.of(), 256, 8);
            Assert.assertEquals("1 minterm", 1, plan.clause.size());
            Assert.assertEquals("minterm0 含 1 个 ORM 节点", 1, plan.clause.getMiniTerm(0).size());

            AggRowSource<Row> src = new ColumnarAggRowSource<>(gm, col, Row.class.getName());
            AggResult r = new AggExecutor<Row>().execute(plan, src, COLS);

            Assert.assertEquals("2 聚合项", 2, r.size());
            Assert.assertEquals("tag='x' 命中行数（tag='y' 必须被过滤）", EXPECT_UNION, r.unionRowCount);
            Assert.assertEquals("COUNT(price)", EXPECT_COUNT, r.value(0).count);
            Assert.assertEquals("SUM(price)", EXPECT_SUM, r.value(1).value, 1e-9);
        } finally {
            col.close();
        }
    }

    // ============== TR4.3 多分区路由（跨 partitionId 聚合 + 过滤） ==============
    @Test(timeout = 120000)
    public void tr43_multiPartitionRouting() throws Exception {
        registerIds(0, id1, id2, id3);
        registerIds(1, id4, id5);

        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        DsDatabaseClusterClient col = newClient(allocator);
        try {
            List<Long> all = col.queryIds(Row.class.getName(), null, 0, 100);
            List<Long> expectedAll = box(new long[]{id1, id2, id3, id4, id5});
            Collections.sort(expectedAll);
            Assert.assertEquals("跨分区全量聚合", expectedAll, all);

            List<Long> x = col.queryIds(Row.class.getName(), eq("tag", "x"), 0, 100);
            Assert.assertEquals("跨分区谓词过滤", box(sort(new long[]{id1, id2, id4, id5})), x);
        } finally {
            col.close();
        }
    }

    // ============== TR4.4 强一致契约（命中 getById 非 null + 无 ORM 行必剔除） ==============
    @Test(timeout = 120000)
    public void tr44_strongConsistency() throws Exception {
        long bogus = DsIdLayout.compose(sid, 0, 999_999L);
        registerIds(0, id1, id2, id3, id4, id5, bogus);

        DbQuery q = eq("tag", "x");
        long[] hits = serverHits(q, 0, 100);
        Assert.assertTrue("过滤命中集非空", hits.length > 0);
        for (long id : hits) {
            Assert.assertNotEquals("无 ORM 行的伪造 id 必须被过滤剔除", bogus, id);
            Row r = gm.getById(id);
            Assert.assertNotNull("R8 过滤命中 id 的 getById 必非 null: " + id, r);
            Assert.assertEquals("命中断言字段一致", "x", r.tag);
        }
        Assert.assertEquals("过滤命中数 = tag='x' 行数", EXPECT_UNION, hits.length);
    }

    private DsDatabaseClusterClient newClient(DsHashMapIdAllocator allocator) {
        DsServerDirectory directory = new DsServerDirectory() {
            @Override
            public int[] listServerIds() {
                return new int[]{sid};
            }

            @Override
            public Endpoint endpoint(int serverId) {
                return null;
            }
        };
        return new DsDatabaseClusterClient(sid, manager, allocator, directory);
    }

    private static long[] sort(long[] v) {
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        return c;
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
}
