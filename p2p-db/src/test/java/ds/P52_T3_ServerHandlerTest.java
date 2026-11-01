package ds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.columnar.TableMetaStore;
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
 * P52 T3：{@code DB_NODE_QUERY_IDS} 服务端 handler 走列扫描下推 4 TR 0F0E。
 *
 * <p>
 * 直接构造 {@link DbNodeQueryIdsRequest} 调用真实 {@link DbNodeQueryIdsServerHandler}（经
 * {@link DsNodeContext} 定位分区），断言：TR3.1 命中保留 / 未命中剔除（列索引扫描路径）；TR3.2 先过滤后分页
 * （offset/limit 施加于过滤后集合）；TR3.3 谓词无命中 → 空 {@code long[]}（非 null）；TR3.4
 * {@code query == null} → 全量透传（兼容旧行为，0 变更）。</p>
 *
 * <p>
 * P52 起过滤改走 {@code ColumnScanPushdown}（列索引候选 + 列值残差），故夹具在同一分区根**双写**列元
 * （{@link TableMetaStore#ensureMeta}）与 tag
 * 列值（{@link ColumnarStore#putValue}）；期望值由 raw 行集 计算，不依赖 id 排序位置。
 * </p>
 */
public class P52_T3_ServerHandlerTest {

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
    private DbNodeQueryIdsServerHandler handler;
    private int sid;
    private long anchor;
    private long[] rawIds;
    private String[] rawTags;
    private long[] sortedIds;
    private final Map<Long, String> tagById = new HashMap<>();

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p52_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        rawTags = new String[]{"aa", "bb", "aa", "cc", "aa", "dd"};
        rawIds = new long[rawTags.length];
        for (int i = 0; i < rawTags.length; i++) {
            rawIds[i] = save(rawTags[i]);
        }
        sortedIds = Arrays.copyOf(rawIds, rawIds.length);
        Arrays.sort(sortedIds);

        sid = DsIdLayout.serverId(sortedIds[0]);
        manager = new DsPartitionManager(new File(tmpRoot, "col"),
                DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(sid, manager);
        anchor = DsIdLayout.compose(sid, 0, 1L);
        registerIds(anchor, sortedIds);
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
        long id = e.getId();
        tagById.put(id, tag);
        return id;
    }

    /**
     * 登记紧凑 id 集合并**同根双写**列元 + tag 列值（P52 列扫描下推所需）。
     */
    private void registerIds(long anchorRowId, long[] rowIds) throws Exception {
        File dbRoot = manager.getStore(anchorRowId).getDbRoot();
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
        }
        new TableMetaStore(dbRoot).ensureMeta(Row.class);
        ColumnarStore cs = new ColumnarStore(dbRoot);
        for (long id : rowIds) {
            cs.putValue(Row.class.getName(), "tag", id, pad(tagById.get(id), 16));
        }
    }

    private static byte[] pad(String s, int len) {
        byte[] raw = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, len));
        return out;
    }

    private long[] serverRaw(DbQuery q, int offset, int limit) {
        P2PWrapper req = P2PWrapper.build(11, P2PCommand.DB_NODE_QUERY_IDS,
                new DbNodeQueryIdsRequest(Row.class.getName(), anchor, q, offset, limit));
        P2PWrapper resp = handler.process(req);
        Assert.assertEquals("响应命令 R_OK_DB_NODE_QUERY_IDS",
                P2PCommand.R_OK_DB_NODE_QUERY_IDS, resp.getCommand());
        Assert.assertTrue("响应载荷", resp.getData() instanceof DbRowQueryIdsResponse);
        long[] out = SerializationUtil.deserialize(long[].class,
                ((DbRowQueryIdsResponse) resp.getData()).idsBytes);
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

    /**
     * 期望命中集：由 raw 行集按 tag 精确匹配后升序（不依赖 id 分配顺序）。
     */
    private List<Long> expectTag(String tag) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < rawIds.length; i++) {
            if (tag.equals(rawTags[i])) {
                out.add(rawIds[i]);
            }
        }
        Collections.sort(out);
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

    // ============== TR3.1 命中保留 / 未命中剔除 ==============
    @Test(timeout = 60000)
    public void tr31_hitKeptMissDropped() {
        List<Long> aa = serverQuery(eq("tag", "aa"), 0, 100);
        Assert.assertEquals("EQ tag='aa' 命中 3 条", expectTag("aa"), aa);
        Assert.assertEquals("命中 3 条", 3, aa.size());

        List<Long> all = serverQuery(isNotNull("tag"), 0, 100);
        Assert.assertEquals("IS_NOT_NULL 命中全量", boxed(sortedIds), all);

        List<Long> bb = serverQuery(eq("tag", "bb"), 0, 100);
        Assert.assertEquals("EQ tag='bb' 命中 1 条", expectTag("bb"), bb);
    }

    // ============== TR3.2 先过滤后分页 ==============
    @Test(timeout = 60000)
    public void tr32_filterThenPaginate() {
        List<Long> aa = serverQuery(eq("tag", "aa"), 0, 100);
        Assert.assertEquals("过滤后集合 size=3", 3, aa.size());

        List<Long> page = serverQuery(eq("tag", "aa"), 1, 1);
        Assert.assertEquals("offset=1 limit=1 → 过滤后第 2 条", 1, page.size());
        Assert.assertEquals("分页元素", aa.get(1), page.get(0));

        List<Long> page0 = serverQuery(eq("tag", "aa"), 0, 1);
        Assert.assertEquals("offset=0 limit=1 → 过滤后第 1 条", aa.get(0), page0.get(0));

        List<Long> beyond = serverQuery(eq("tag", "aa"), 5, 1);
        Assert.assertTrue("offset ≥ 过滤后 size → 空", beyond.isEmpty());
    }

    // ============== TR3.3 空集 → 空 long[]（非 null） ==============
    @Test(timeout = 60000)
    public void tr33_noHitYieldsEmptyNonNullArray() {
        long[] out = serverRaw(eq("tag", "zzz"), 0, 100);
        Assert.assertNotNull("空集 ids 数组非 null", out);
        Assert.assertEquals("空集长度 0", 0, out.length);
    }

    // ============== TR3.4 query == null → 全量透传（0 变更） ==============
    @Test(timeout = 60000)
    public void tr34_nullQueryFullPassthrough() {
        List<Long> all = serverQuery(null, 0, 100);
        Assert.assertEquals("query=null → 全量透传（升序）", boxed(sortedIds), all);

        List<Long> paged = serverQuery(null, 1, 2);
        Assert.assertEquals("透传 + 分页",
                Arrays.asList(sortedIds[1], sortedIds[2]), paged);
    }

    private static List<Long> boxed(long[] v) {
        List<Long> out = new ArrayList<>(v.length);
        for (long x : v) {
            out.add(x);
        }
        return out;
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
