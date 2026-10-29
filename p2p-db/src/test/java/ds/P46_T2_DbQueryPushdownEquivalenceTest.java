package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;

/**
 * P46 T2：{@link ColumnarAggRowSource} {@code DbQuery} 下推等价性 6 TR 0F0E。
 *
 * <p>
 * TR2.1 minterm → {@code DbQuery} 逐 op 映射全等（EQ/NE/GT/GE/LT/LE/BETWEEN/IN/IS_NULL/IS_NOT_NULL）；
 * TR2.2 双模本地路径 ≡ {@link GenericManagerRowSource} 逐字段全等；TR2.3 服务端路径 id 集合正确；
 * TR2.4 空结果（非 null）；TR2.5 列存命中 id 无实体 → ISE；TR2.6 结果按 docId 升序。
 */
public class P46_T2_DbQueryPushdownEquivalenceTest {

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

    /** 可编程列存客户端：记录 entity/query 并返回预设 id 列表。 */
    static class MockColClient extends DsDatabaseClusterClient {

        final List<Long> nextIds = new ArrayList<>();
        String lastEntity;
        DbQuery lastQuery;
        int calls;

        MockColClient(DsPartitionManager m, DsHashMapIdAllocator a) {
            super(1, m, a, EMPTY_DIR);
        }

        @Override
        public List<Long> queryIds(String entityClassName, DbQuery query, int offset, int limit) {
            calls++;
            lastEntity = entityClassName;
            lastQuery = query;
            return new ArrayList<>(nextIds);
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private MockColClient mock;
    private long id1;
    private long id2;
    private long id3;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t2_").toFile();
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
        mock = new MockColClient(manager, allocator);
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
        if (mock != null) {
            mock.close();
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

    private static void assertCriterion(DbQueryCriterion c, DbQueryOp op, String name,
            String a, String b, int listSize) {
        Assert.assertEquals("op", op, c.op);
        Assert.assertEquals("name", name, c.name);
        Assert.assertEquals("a", a, c.a);
        Assert.assertEquals("b", b, c.b);
        Assert.assertEquals("list size", listSize, c.list.size());
    }

    // ============== TR2.1 minterm → DbQuery 逐 op 映射全等 ==============

    @Test
    public void tr21_mintermToDbQueryOpMapping() {
        List<ConditionNode> m = Arrays.asList(
                ConditionNode.orm("a", DslToken.OP_EQ, 1),
                ConditionNode.orm("b", DslToken.OP_NE, 2),
                ConditionNode.orm("c", DslToken.OP_GT, 3),
                ConditionNode.orm("d", DslToken.OP_GE, 4),
                ConditionNode.orm("e", DslToken.OP_LT, 5),
                ConditionNode.orm("f", DslToken.OP_LE, 6),
                ConditionNode.between("g", 10, 20),
                ConditionNode.orm("h", DslToken.KW_IN, Arrays.asList("x", "y")),
                ConditionNode.isNull("i"),
                ConditionNode.isNotNull("j"));

        DbQuery q = ColumnarAggRowSource.toDbQuery(m);
        Assert.assertEquals("where 条目数", 10, q.where.size());
        Assert.assertTrue("anyOf 空", q.anyOf.isEmpty());
        Assert.assertTrue("orderBy 空", q.orderBy.isEmpty());
        assertCriterion(q.where.get(0), DbQueryOp.EQ, "a", "1", null, 0);
        assertCriterion(q.where.get(1), DbQueryOp.NE, "b", "2", null, 0);
        assertCriterion(q.where.get(2), DbQueryOp.GT, "c", "3", null, 0);
        assertCriterion(q.where.get(3), DbQueryOp.GE, "d", "4", null, 0);
        assertCriterion(q.where.get(4), DbQueryOp.LT, "e", "5", null, 0);
        assertCriterion(q.where.get(5), DbQueryOp.LE, "f", "6", null, 0);
        assertCriterion(q.where.get(6), DbQueryOp.BETWEEN, "g", "10", "20", 0);
        assertCriterion(q.where.get(7), DbQueryOp.IN, "h", null, null, 2);
        Assert.assertEquals(Arrays.asList("x", "y"), q.where.get(7).list);
        assertCriterion(q.where.get(8), DbQueryOp.IS_NULL, "i", null, null, 0);
        assertCriterion(q.where.get(9), DbQueryOp.IS_NOT_NULL, "j", null, null, 0);
    }

    // ============== TR2.2 双模本地路径 ≡ GenericManagerRowSource 逐字段全等 ==============

    @Test
    public void tr22_localModeEqualsGenericManagerRowSource() {
        List<ConditionNode> m = Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, 0.0));
        ColumnarAggRowSource<Row> local = new ColumnarAggRowSource<>(gm, null, null);
        GenericManagerRowSource<Row> base = new GenericManagerRowSource<>(gm);

        List<Row> a = local.fetch(m);
        List<Row> b = base.fetch(m);
        Assert.assertEquals("行数全等", b.size(), a.size());
        for (int i = 0; i < a.size(); i++) {
            Assert.assertEquals("id 全等", b.get(i).getId(), a.get(i).getId());
            Assert.assertEquals("tag 全等", b.get(i).tag, a.get(i).tag);
            Assert.assertEquals("price 全等", b.get(i).price, a.get(i).price);
        }
        Assert.assertEquals("本地模式 0 次列存调用", 0, mock.calls);
    }

    // ============== TR2.3 服务端路径 id 集合正确 ==============

    @Test
    public void tr23_serverModeQueryIdsAndMaterialize() {
        mock.nextIds.add(id1);
        mock.nextIds.add(id3);
        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        List<ConditionNode> m = Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, 0.0));

        List<Row> rows = srv.fetch(m);
        Assert.assertEquals("queryIds 调用 1 次", 1, mock.calls);
        Assert.assertEquals("entityClassName 透传", Row.class.getName(), mock.lastEntity);
        Assert.assertEquals("谓词下推 1 条", 1, mock.lastQuery.where.size());
        assertCriterion(mock.lastQuery.where.get(0), DbQueryOp.GT, "price", "0.0", null, 0);
        Assert.assertEquals("物化 2 行", 2, rows.size());
        Assert.assertEquals(Long.valueOf(id1), rows.get(0).getId());
        Assert.assertEquals(Long.valueOf(id3), rows.get(1).getId());
    }

    // ============== TR2.4 空结果（非 null） ==============

    @Test
    public void tr24_serverModeEmptyResult() {
        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        List<ConditionNode> m = Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, 1e9));
        List<Row> rows = srv.fetch(m);
        Assert.assertNotNull("空结果非 null", rows);
        Assert.assertTrue("空结果", rows.isEmpty());
    }

    // ============== TR2.5 列存命中 id 无实体 → ISE ==============

    @Test
    public void tr25_serverModeMissingEntityThrowsIse() {
        long ghost = save("ghost", 1.0);
        Assert.assertEquals("删除 ghost 行", 1, gm.removeById(ghost));
        Assert.assertNull("ghost 已不存在", gm.getById(ghost));

        mock.nextIds.add(ghost);
        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        List<ConditionNode> m = Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, 0.0));
        try {
            srv.fetch(m);
            Assert.fail("expected IllegalStateException");
        } catch (IllegalStateException ok) {
            Assert.assertTrue("异常信息含 id", ok.getMessage().contains(String.valueOf(ghost)));
        }
    }

    // ============== TR2.6 结果按 docId 升序 ==============

    @Test
    public void tr26_serverModeResultAscendingByDocId() {
        mock.nextIds.add(id3);
        mock.nextIds.add(id1);
        mock.nextIds.add(id2);
        ColumnarAggRowSource<Row> srv = new ColumnarAggRowSource<>(gm, mock, Row.class.getName());
        List<ConditionNode> m = Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GT, 0.0));

        List<Row> rows = srv.fetch(m);
        long[] expected = {id1, id2, id3};
        Arrays.sort(expected);
        Assert.assertEquals("行数", 3, rows.size());
        for (int i = 0; i < 3; i++) {
            Assert.assertEquals("第 " + i + " 行 docId 升序", expected[i], rows.get(i).getId().longValue());
        }
    }
}
