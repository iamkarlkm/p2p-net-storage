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
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;

import javax.net.p2p.model.DbAggFn;
import javax.net.p2p.model.DbAggItem;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbQueryOrder;

/**
 * P53 T3：{@link ColumnAggPushdown} 服务端 ORDER BY 单测 5 TR 0F0E。
 *
 * <p>
 * TR3.1 ORDER BY 分组键 asc/desc；TR3.2 ORDER BY 聚合别名（{@code SUM(col)}）asc/desc；TR3.3 多键 ORDER
 * BY + tie-break 稳定（回落默认分组键升序，R6 全序）；TR3.4 offset/limit 施加于「HAVING 过滤 + 排序后」
 * 结果；TR3.5 orderBy 为空 → 默认按分组键升序。
 */
public class P53_T3_OrderByServerTest {

    /**
     * 测试实体：tag/region 分组列 + num 聚合列。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "region", length = 16)
        public String region;
        @DsField(name = "num", length = 4)
        public int num;

        public Row() {
        }
    }

    private File tmpRoot;
    private ColumnarStore cs;
    private List<Long> idList;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p53_t3_").toFile();
        new TableMetaStore(tmpRoot).ensureMeta(Row.class);
        cs = new ColumnarStore(tmpRoot);

        List<Long> ids = new ArrayList<>();
        ids.add(addRow(1, "g1", "r1", 10));
        ids.add(addRow(2, "g1", "r1", 20));
        ids.add(addRow(3, "g1", "r2", 30));
        ids.add(addRow(4, "g2", "r1", 5));
        ids.add(addRow(5, "g2", "r1", null));
        ids.add(addRow(6, "g2", "r2", 15));
        ids.add(addRow(7, "g3", "r1", null));
        ids.add(addRow(8, "g4", "r1", 1));
        ids.add(addRow(9, "g4", "r1", 2));
        cs.close();
        idList = ids;
    }

    @After
    public void after() {
        try {
            if (cs != null) {
                cs.close();
            }
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
    }

    private long addRow(long id, String tag, String region, Integer num) throws IOException {
        String ent = Row.class.getName();
        cs.putValue(ent, "tag", id, pad(tag, 16));
        cs.putValue(ent, "region", id, pad(region, 16));
        if (num != null) {
            cs.putValue(ent, "num", id, ByteBuffer.allocate(4).putInt(num).array());
        }
        return id;
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

    private static DbQueryOrder ord(String name, boolean asc) {
        return new DbQueryOrder(name, asc);
    }

    private static DbQueryCriterion crit(DbQueryOp op, String name, String a) {
        return new DbQueryCriterion(op, name, a, null, null);
    }

    private List<Object[]> compute(List<DbAggItem> aggs, List<String> groupBy,
            List<DbQueryCriterion> having, List<DbQueryOrder> orderBy, int offset, int limit) {
        return ColumnAggPushdown.compute(tmpRoot, Row.class.getName(), idList,
                aggs, groupBy, having, orderBy, offset, limit);
    }

    private static List<String> firstCol(List<Object[]> rows) {
        List<String> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(String.valueOf(r[0]));
        }
        return out;
    }

    private static List<String> composite(List<Object[]> rows) {
        List<String> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(r[0] + "/" + r[1]);
        }
        return out;
    }

    // ============== TR3.1 ORDER BY 分组键 asc/desc ==============
    @Test(timeout = 30000)
    public void tr31_orderByGroupKey() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.SUM, "num"));
        List<Object[]> asc = compute(aggs, List.of("tag"), null, List.of(ord("tag", true)), 0, 100);
        Assert.assertEquals("ORDER BY tag asc", Arrays.asList("g1", "g2", "g3", "g4"), firstCol(asc));

        List<Object[]> desc = compute(aggs, List.of("tag"), null, List.of(ord("tag", false)), 0, 100);
        Assert.assertEquals("ORDER BY tag desc", Arrays.asList("g4", "g3", "g2", "g1"), firstCol(desc));
    }

    // ============== TR3.2 ORDER BY 聚合别名 asc/desc ==============
    @Test(timeout = 30000)
    public void tr32_orderByAggAlias() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.SUM, "num"));
        List<Object[]> asc = compute(aggs, List.of("tag"), null, List.of(ord("SUM(num)", true)), 0, 100);
        Assert.assertEquals("SUM asc → g3(0),g4(3),g2(20),g1(60)",
                Arrays.asList("g3", "g4", "g2", "g1"), firstCol(asc));

        List<Object[]> desc = compute(aggs, List.of("tag"), null, List.of(ord("SUM(num)", false)), 0, 100);
        Assert.assertEquals("SUM desc", Arrays.asList("g1", "g2", "g4", "g3"), firstCol(desc));
    }

    // ============== TR3.3 多键 ORDER BY + tie-break 稳定 ==============
    @Test(timeout = 30000)
    public void tr33_multiKeyOrderByAndStableTieBreak() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.COUNT, "num"));
        List<String> groupBy = List.of("tag", "region");

        // 单键 region：同 region 内稳定回落默认分组键序
        List<Object[]> tie = compute(aggs, groupBy, null, List.of(ord("region", true)), 0, 100);
        Assert.assertEquals("region asc + tie-break 稳定",
                Arrays.asList("g1/r1", "g2/r1", "g3/r1", "g4/r1", "g1/r2", "g2/r2"),
                composite(tie));

        // 多键：region asc, tag desc
        List<Object[]> multi = compute(aggs, groupBy, null,
                Arrays.asList(ord("region", true), ord("tag", false)), 0, 100);
        Assert.assertEquals("region asc + tag desc",
                Arrays.asList("g4/r1", "g3/r1", "g2/r1", "g1/r1", "g2/r2", "g1/r2"),
                composite(multi));
    }

    // ============== TR3.4 offset/limit 施加于 HAVING + 排序后 ==============
    @Test(timeout = 30000)
    public void tr34_offsetLimitAfterHavingAndSort() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.SUM, "num"));
        List<DbQueryCriterion> having = List.of(crit(DbQueryOp.GT, "SUM(num)", "0"));
        List<DbQueryOrder> order = List.of(ord("SUM(num)", false));

        List<Object[]> all = compute(aggs, List.of("tag"), having, order, 0, 100);
        Assert.assertEquals("HAVING SUM>0 + desc → g1,g2,g4",
                Arrays.asList("g1", "g2", "g4"), firstCol(all));

        List<Object[]> page = compute(aggs, List.of("tag"), having, order, 1, 1);
        Assert.assertEquals("offset=1 limit=1 → g2", List.of("g2"), firstCol(page));
    }

    // ============== TR3.5 orderBy 为空 → 默认分组键升序 ==============
    @Test(timeout = 30000)
    public void tr35_emptyOrderByFallsBackToGroupKeyAsc() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.SUM, "num"));
        List<Object[]> nul = compute(aggs, List.of("tag"), null, null, 0, 100);
        Assert.assertEquals("orderBy=null → 分组键升序",
                Arrays.asList("g1", "g2", "g3", "g4"), firstCol(nul));

        List<Object[]> empty = compute(aggs, List.of("tag"), null, List.of(), 0, 100);
        Assert.assertEquals("orderBy=[] → 分组键升序",
                Arrays.asList("g1", "g2", "g3", "g4"), firstCol(empty));
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
