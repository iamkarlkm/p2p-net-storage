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
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.model.DbNodeQueryIdsRequest;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.utils.SerializationUtil;

/**
 * P50 T3：{@link DsDatabaseClusterClient#queryIds} 客户端方法单测 5 TR 0F0E。
 *
 * <p>
 * TR3.1 本地路径谓词生效（先过滤后分页）；TR3.2 远端分支 {@code query} 透传不变（wire round-trip 保真 +
 * 无 endpoint 时确实走远端分支）；TR3.3 isLocal 判定（本地 serverId 命中走本地 / 未命中走远端）；TR3.4
 * offset/limit 边界（0 / ≥size / 负）；TR3.5 异常路径（本地非法谓词 → IAE，远端缺 endpoint → IOException）。
 */
public class P50_T3_ClientQueryIdsTest {

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
    private DsDatabaseClusterClient col;
    private int sid;
    private long anchor;
    private long[] ids;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p50_t3_").toFile();
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
        anchor = DsIdLayout.compose(sid, 0, 1L);
        registerIds(manager, anchor, ids);

        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
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
        col = new DsDatabaseClusterClient(sid, manager, allocator, directory);
    }

    @After
    public void after() {
        if (col != null) {
            col.close();
        }
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

    private static DbQuery eq(String col, String val) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, col, val, null, null));
        return q;
    }

    // ============== TR3.1 本地路径谓词生效 ==============
    @Test(timeout = 60000)
    public void tr31_localPathAppliesPredicate() throws Exception {
        List<Long> red = col.queryIds(Row.class.getName(), anchor, eq("tag", "red"), 0, 100);
        Assert.assertEquals("本地路径过滤 EQ tag='red'", Arrays.asList(ids[0], ids[2]), red);
        Assert.assertEquals("谓词不同结果不同", Arrays.asList(ids[1]),
                col.queryIds(Row.class.getName(), anchor, eq("tag", "blue"), 0, 100));
    }

    // ============== TR3.2 远端分支 query 透传不变 ==============
    @Test(timeout = 60000)
    public void tr32_remoteBranchPassesQueryThroughWire() throws Exception {
        long remoteAnchor = DsIdLayout.compose(sid + 7, 0, 1L);
        DbQuery q = eq("tag", "red");
        DbNodeQueryIdsRequest req = new DbNodeQueryIdsRequest(Row.class.getName(), remoteAnchor, q, 3, 9);

        DbNodeQueryIdsRequest back = SerializationUtil.deserialize(
                DbNodeQueryIdsRequest.class, SerializationUtil.serialize(req));
        Assert.assertNotNull("wire 反序列化非 null", back);
        Assert.assertEquals("entityClassName 保真", Row.class.getName(), back.entityClassName);
        Assert.assertEquals("anchorRowId 保真", remoteAnchor, back.anchorRowId);
        Assert.assertEquals("offset 保真", 3, back.offset);
        Assert.assertEquals("limit 保真", 9, back.limit);
        Assert.assertNotNull("query 透传不丢失", back.query);
        Assert.assertEquals("query.where 大小保真", 1, back.query.where.size());
        Assert.assertEquals("query op 保真", DbQueryOp.EQ, back.query.where.get(0).op);
        Assert.assertEquals("query 列名保真", "tag", back.query.where.get(0).name);
        Assert.assertEquals("query 值保真", "red", back.query.where.get(0).a);
    }

    // ============== TR3.3 isLocal 判定 ==============
    @Test(timeout = 60000)
    public void tr33_isLocalJudgement() throws Exception {
        // 本地 serverId（sid）→ 走本地路径，返回真实数据
        List<Long> local = col.queryIds(Row.class.getName(), anchor, null, 0, 100);
        Assert.assertEquals("本地 serverId 走本地路径", Arrays.asList(ids[0], ids[1], ids[2], ids[3]), local);

        // 非本地 serverId → 走远端分支（endpoint 缺失 → IOException）
        long foreign = DsIdLayout.compose(sid + 3, 0, 1L);
        try {
            col.queryIds(Row.class.getName(), foreign, null, 0, 100);
            Assert.fail("非本地 serverId 应走远端分支并抛 IOException");
        } catch (IOException e) {
            Assert.assertTrue("远端分支错误信息: " + e.getMessage(), e.getMessage().contains("no endpoint"));
        }
    }

    // ============== TR3.4 offset/limit 边界 ==============
    @Test(timeout = 60000)
    public void tr34_offsetLimitBoundary() throws Exception {
        String e = Row.class.getName();
        Assert.assertEquals("offset=0 limit=100 → 全量", 4, col.queryIds(e, anchor, null, 0, 100).size());
        Assert.assertEquals("offset=2 → 尾部 2 条", Arrays.asList(ids[2], ids[3]),
                col.queryIds(e, anchor, null, 2, 100));
        Assert.assertTrue("offset ≥ size → 空", col.queryIds(e, anchor, null, 10, 100).isEmpty());
        Assert.assertTrue("offset 负 → 视作 0", !col.queryIds(e, anchor, null, -1, 100).isEmpty());
        Assert.assertEquals("limit ≤ 0 → 默认 100", 4, col.queryIds(e, anchor, null, 0, 0).size());
        Assert.assertEquals("limit 负 → 默认 100", 4, col.queryIds(e, anchor, null, 0, -5).size());
        Assert.assertEquals("limit=1 → 1 条", 1, col.queryIds(e, anchor, null, 0, 1).size());
    }

    // ============== TR3.5 异常路径 ==============
    @Test(timeout = 60000)
    public void tr35_exceptionPaths() throws Exception {
        // 本地路径非法谓词（op==null）→ IAE 上抛
        DbQuery bad = new DbQuery();
        bad.where.add(new DbQueryCriterion(null, "tag", "x", null, null));
        try {
            col.queryIds(Row.class.getName(), anchor, bad, 0, 100);
            Assert.fail("本地非法谓词应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }

        // 远端缺 endpoint → IOException 上抛
        long foreign = DsIdLayout.compose(sid + 9, 0, 1L);
        try {
            col.queryIds(Row.class.getName(), foreign, eq("tag", "red"), 0, 100);
            Assert.fail("远端缺 endpoint 应抛 IOException");
        } catch (IOException expected) {
            Assert.assertTrue("IOException 信息: " + expected.getMessage(),
                    expected.getMessage().contains("no endpoint"));
        }
    }
}
