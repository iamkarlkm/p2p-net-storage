package ds;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.error.P2PErrorCode;
import javax.net.p2p.error.P2PErrors;
import javax.net.p2p.model.DbAggFn;
import javax.net.p2p.model.DbAggItem;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbRowAggRequest;
import javax.net.p2p.model.DbRowAggResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbRowAggServerHandler;

/**
 * P53 T4：{@code DB_ROW_AGG} 服务端 handler 单测 5 TR 0F0E。
 *
 * <p>
 * TR4.1 命令校验（{@code DB_ROW_AGG} 路由不匹配 → {@code ROUTING_HANDLER_MISMATCH}）+ 非法载荷错误码（
 * {@code INVALID_REQUEST}）；TR4.2 {@code entityClassName} 空白 / {@code query == null} 边界；TR4.3 正常聚合往返
 * （{@link DbRowAggRequest} → {@link DbRowAggResponse#payload} 解码）；TR4.4 复用 P52 列扫描下推取命中
 * id（命中保留 / 未命中剔除）；TR4.5 {@code aggs} 空 → 显式 {@code INVALID_REQUEST}（非崩溃）。
 *
 * <p>
 * 夹具：{@code p2p.db.home} = tmpRoot，ORM 行（{@link GenericManager}）+ 列元（{@link TableMetaStore}）+
 * 列值（{@link ColumnarStore}）+ id 集合（{@link DynamicIndexUtil#idsFile}）**同根双写**；handler 内
 * {@link DsDatabaseLocal#load()} 自行解析根目录。
 */
public class P53_T4_ServerHandlerTest {

    /**
     * 测试实体：tag 谓词列 + num 聚合列。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "num", length = 4)
        public int num;

        public Row() {
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private ColumnarStore cs;
    private DbRowAggServerHandler handler;
    private long[] ids;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p53_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        new TableMetaStore(tmpRoot).ensureMeta(Row.class);
        cs = new ColumnarStore(tmpRoot);

        List<Long> all = new ArrayList<>();
        all.add(addRow("aa", 10));
        all.add(addRow("bb", 20));
        all.add(addRow("aa", 30));
        all.add(addRow("cc", 40));
        cs.close();
        ids = new long[all.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = all.get(i);
        }
        Arrays.sort(ids);
        registerIds(ids);
        handler = new DbRowAggServerHandler();
    }

    @After
    public void after() {
        try {
            if (cs != null) {
                cs.close();
            }
        } catch (Throwable ignore) {
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

    private long addRow(String tag, int num) throws IOException {
        Row e = new Row();
        e.tag = tag;
        e.num = num;
        gm.saveOrUpdate(e);
        long id = e.getId();
        cs.putValue(Row.class.getName(), "tag", id, pad(tag, 16));
        cs.putValue(Row.class.getName(), "num", id, ByteBuffer.allocate(4).putInt(num).array());
        return id;
    }

    private void registerIds(long[] rowIds) throws IOException {
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(tmpRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
        }
    }

    private static byte[] pad(String s, int len) {
        byte[] raw = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, len));
        return out;
    }

    private static DbAggItem agg(DbAggFn fn, String column) {
        return new DbAggItem(fn, column);
    }

    private static List<DbAggItem> aggList(DbAggItem... items) {
        return Arrays.asList(items);
    }

    private static DbQuery eq(String col, String val) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, col, val, null, null));
        return q;
    }

    private P2PWrapper send(P2PCommand cmd, Object payload) {
        return handler.process(P2PWrapper.build(11, cmd, payload));
    }

    private P2PWrapper agg(DbQuery query, List<DbAggItem> aggs) {
        return send(P2PCommand.DB_ROW_AGG,
                new DbRowAggRequest(Row.class.getName(), query, aggs, null, null, null, 0, 100));
    }

    private static List<Object[]> decode(P2PWrapper resp) {
        Assert.assertEquals("响应命令 R_OK_DB_ROW_AGG", P2PCommand.R_OK_DB_ROW_AGG, resp.getCommand());
        Assert.assertTrue("响应载荷 DbRowAggResponse", resp.getData() instanceof DbRowAggResponse);
        return ColumnAggPushdown.decode(((DbRowAggResponse) resp.getData()).payload);
    }

    private static int errCode(P2PWrapper resp) {
        Assert.assertEquals("错误响应命令 STD_ERROR", P2PCommand.STD_ERROR, resp.getCommand());
        return P2PErrors.asStdError(resp.getData()).getCode();
    }

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    // ============== TR4.1 命令校验 + 非法载荷 ==============
    @Test(timeout = 60000)
    public void tr41_commandMismatchAndInvalidPayload() {
        P2PWrapper wrongCmd = send(P2PCommand.DB_NODE_QUERY_IDS,
                new DbRowAggRequest(Row.class.getName(), null,
                        aggList(agg(DbAggFn.COUNT, "num")), null, null, null, 0, 100));
        Assert.assertEquals("命令不匹配 → ROUTING_HANDLER_MISMATCH",
                P2PErrorCode.ROUTING_HANDLER_MISMATCH.code(), errCode(wrongCmd));

        P2PWrapper badPayload = send(P2PCommand.DB_ROW_AGG, "not-a-request");
        Assert.assertEquals("非法载荷 → INVALID_REQUEST",
                P2PErrorCode.INVALID_REQUEST.code(), errCode(badPayload));
    }

    // ============== TR4.2 entityClassName 空 / query == null 边界 ==============
    @Test(timeout = 60000)
    public void tr42_blankEntityAndNullQuery() {
        P2PWrapper noEntity = send(P2PCommand.DB_ROW_AGG,
                new DbRowAggRequest("", null, aggList(agg(DbAggFn.COUNT, "num")),
                        null, null, null, 0, 100));
        Assert.assertEquals("空 entityClassName → INVALID_REQUEST",
                P2PErrorCode.INVALID_REQUEST.code(), errCode(noEntity));

        List<Object[]> rows = decode(agg(null, aggList(agg(DbAggFn.COUNT, "num"))));
        Assert.assertEquals("query=null → 全局聚合 1 行", 1, rows.size());
        Assert.assertEquals("query=null → 全量 COUNT=4", 4L, (long) d(rows.get(0)[0]));
    }

    // ============== TR4.3 正常聚合往返 ==============
    @Test(timeout = 60000)
    public void tr43_normalAggRoundTrip() {
        List<Object[]> rows = decode(agg(eq("tag", "aa"),
                aggList(agg(DbAggFn.COUNT, "num"), agg(DbAggFn.SUM, "num"))));
        Assert.assertEquals("全局聚合 1 行", 1, rows.size());
        Assert.assertEquals("行宽 = 0 + 2", 2, rows.get(0).length);
        Assert.assertEquals("COUNT(num) tag='aa'", 2L, (long) d(rows.get(0)[0]));
        Assert.assertEquals("SUM(num) tag='aa'", 40.0, d(rows.get(0)[1]), 1e-9);
    }

    // ============== TR4.4 复用列扫描下推取 id（命中保留 / 未命中剔除） ==============
    @Test(timeout = 60000)
    public void tr44_reuseColumnScanFilter() {
        Assert.assertEquals("全量 COUNT=4",
                4L, (long) d(decode(agg(null, aggList(agg(DbAggFn.COUNT, "num")))).get(0)[0]));
        Assert.assertEquals("命中 tag='aa' COUNT=2",
                2L, (long) d(decode(agg(eq("tag", "aa"), aggList(agg(DbAggFn.COUNT, "num")))).get(0)[0]));
        Assert.assertEquals("未命中 tag='zzz' → 空组 COUNT=0",
                0L, (long) d(decode(agg(eq("tag", "zzz"), aggList(agg(DbAggFn.COUNT, "num")))).get(0)[0]));
    }

    // ============== TR4.5 aggs 空 → 显式错误码 ==============
    @Test(timeout = 60000)
    public void tr45_emptyAggsExplicitError() {
        P2PWrapper resp = send(P2PCommand.DB_ROW_AGG,
                new DbRowAggRequest(Row.class.getName(), null, new ArrayList<>(),
                        null, null, null, 0, 100));
        Assert.assertEquals("aggs 空 → INVALID_REQUEST",
                P2PErrorCode.INVALID_REQUEST.code(), errCode(resp));
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
