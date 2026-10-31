package ds;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.integration.GenericManager;
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
 * P50 T2：服务端 {@link DbNodeQueryIdsServerHandler} 谓词过滤单测 4 TR 0F0E。
 *
 * <p>
 * 直接构造 {@link DbNodeQueryIdsRequest} 调用真实 handler（经 {@link DsNodeContext} 定位分区），
 * 断言：TR2.1 命中保留 / 未命中剔除；TR2.2 先过滤后分页（offset/limit 施加于过滤后集合）；TR2.3 谓词无命中 → 空
 * {@code long[]}（非 null）；TR2.4 {@code query == null} → 全量透传（兼容旧行为）。
 *
 * <p>
 * ORM 行经 {@link GenericManager} 落 {@code p2p.db.home}；列存候选 id 直接登记进目标分区 ids 集合（
 * {@link DynamicIndexUtil#idsFile}），保证过滤命中的 id 与 ORM 行一一对应。
 */
public class P50_T2_ServerFilterTest {

    /**
     * 测试实体：单列 tag 承载谓词。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;

        public Row() {
        }

        public Row(String tag) {
            this.tag = tag;
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private DsPartitionManager manager;
    private int sid;
    private long anchor;
    private long[] ids;
    private DbNodeQueryIdsServerHandler handler;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p50_t2_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        ids = new long[]{save("red"), save("blue"), save("red"), save("green")};
        Arrays.sort(ids);

        sid = DsIdLayout.serverId(ids[0]);
        manager = new DsPartitionManager(new File(tmpRoot, "col"),
                DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(sid, manager);
        anchor = DsIdLayout.compose(sid, 0, 1L);
        registerIds(manager, anchor, ids);
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

    private long save(String tag) {
        Row e = new Row(tag);
        gm.saveOrUpdate(e);
        return e.getId();
    }

    private static void registerIds(DsPartitionManager mgr, long anchorRowId, long[] rowIds) throws IOException {
        File dbRoot = mgr.getStore(anchorRowId).getDbRoot();
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
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

    private P2PWrapper raw(DbQuery q, int offset, int limit) {
        P2PWrapper req = P2PWrapper.build(11, P2PCommand.DB_NODE_QUERY_IDS,
                new DbNodeQueryIdsRequest(Row.class.getName(), anchor, q, offset, limit));
        return handler.process(req);
    }

    private long[] serverRaw(DbQuery q, int offset, int limit) {
        P2PWrapper resp = raw(q, offset, limit);
        Assert.assertEquals("响应命令 R_OK_DB_NODE_QUERY_IDS", P2PCommand.R_OK_DB_NODE_QUERY_IDS, resp.getCommand());
        Assert.assertTrue("响应载荷", resp.getData() instanceof DbRowQueryIdsResponse);
        long[] out = SerializationUtil.deserialize(long[].class, ((DbRowQueryIdsResponse) resp.getData()).idsBytes);
        Assert.assertNotNull("idsBytes 反序列化非 null", out);
        return out;
    }

    private List<Long> serverQuery(DbQuery q, int offset, int limit) {
        List<Long> out = new ArrayList<>();
        for (long v : serverRaw(q, offset, limit)) {
            out.add(v);
        }
        return out;
    }

    private static DbQuery eq(String col, String val) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, col, val, null, null));
        return q;
    }

    private static DbQuery isNotNull(String col) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.IS_NOT_NULL, col, null, null, null));
        return q;
    }

    // ============== TR2.1 命中保留 / 未命中剔除 ==============
    @Test(timeout = 60000)
    public void tr21_hitKeptMissDropped() {
        List<Long> red = serverQuery(eq("tag", "red"), 0, 100);
        Assert.assertEquals("EQ tag='red' 命中 2 条", Arrays.asList(ids[0], ids[2]), red);

        List<Long> all = serverQuery(isNotNull("tag"), 0, 100);
        Assert.assertEquals("IS_NOT_NULL 命中全量", Arrays.asList(ids[0], ids[1], ids[2], ids[3]), all);

        List<Long> none = serverQuery(eq("tag", "blue"), 0, 100);
        Assert.assertEquals("EQ tag='blue' 命中 1 条", Arrays.asList(ids[1]), none);
    }

    // ============== TR2.2 先过滤后分页 ==============
    @Test(timeout = 60000)
    public void tr22_filterThenPaginate() {
        List<Long> red = serverQuery(eq("tag", "red"), 0, 100);
        List<Long> page = serverQuery(eq("tag", "red"), 1, 1);
        Assert.assertEquals("过滤后集合 size=2", 2, red.size());
        Assert.assertEquals("offset=1 limit=1 → 过滤后第 2 条", 1, page.size());
        Assert.assertEquals("分页元素", red.get(1), page.get(0));

        List<Long> page0 = serverQuery(eq("tag", "red"), 0, 1);
        Assert.assertEquals("offset=0 limit=1 → 过滤后第 1 条", red.get(0), page0.get(0));

        List<Long> beyond = serverQuery(eq("tag", "red"), 5, 1);
        Assert.assertTrue("offset ≥ 过滤后 size → 空", beyond.isEmpty());
    }

    // ============== TR2.3 空集 → 空 long[]（非 null） ==============
    @Test(timeout = 60000)
    public void tr23_noHitYieldsEmptyNonNullArray() {
        long[] out = serverRaw(eq("tag", "zzz"), 0, 100);
        Assert.assertNotNull("空集 ids 数组非 null", out);
        Assert.assertEquals("空集长度 0", 0, out.length);
    }

    // ============== TR2.4 query == null → 全透传 ==============
    @Test(timeout = 60000)
    public void tr24_nullQueryFullPassthrough() {
        List<Long> all = serverQuery(null, 0, 100);
        Assert.assertEquals("query=null → 全量透传（升序）",
                Arrays.asList(ids[0], ids[1], ids[2], ids[3]), all);

        List<Long> paged = serverQuery(null, 1, 2);
        Assert.assertEquals("透传 + 分页", Arrays.asList(ids[1], ids[2]), paged);
    }
}
