package ds;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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

/**
 * P53 T2：{@link ColumnAggPushdown} 聚合语义单测 7 TR 0F0E（对齐 P39 {@code AggExecutor}
 * oracle）。
 *
 * <p>
 * TR2.1 {@code COUNT(col)} 忽略 NULL、空组为 0；TR2.2 {@code SUM} 忽略 NULL、全 NULL 组为
 * 0.0；TR2.3 {@code AVG} 先累加后除（非逐项平均）；TR2.4 {@code MIN}/{@code MAX} 忽略
 * NULL；TR2.5 单键 GROUP BY 分桶 + 分组键升序；TR2.6 双键 GROUP BY 复合键；TR2.7 HAVING（分组键 +
 * {@code FN(col)} 聚合别名两类左值）。
 *
 * <p>
 * 夹具：行 id 显式给定（1..N，&gt; 0），在 {@code p2p.db.home} 根**同根双写**列元
 * （{@link TableMetaStore#ensureMeta}）与列值（{@link ColumnarStore#putValue}）；{@code num}
 * 为 {@code int} 列，NULL 列不写字节即表达 SQL NULL（保「忽略 NULL」语义）。
 */
public class P53_T2_AggSemanticsTest {

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
        tmpRoot = Files.createTempDirectory("dsdb_p53_t2_").toFile();
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

    private static DbQueryCriterion crit(DbQueryOp op, String name, String a) {
        return new DbQueryCriterion(op, name, a, null, null);
    }

    private List<Object[]> compute(List<DbAggItem> aggs, List<String> groupBy) {
        return ColumnAggPushdown.compute(tmpRoot, Row.class.getName(), idList,
                aggs, groupBy, null, null, 0, 100);
    }

    private List<Object[]> computeHaving(List<DbAggItem> aggs, List<String> groupBy,
            List<DbQueryCriterion> having) {
        return ColumnAggPushdown.compute(tmpRoot, Row.class.getName(), idList,
                aggs, groupBy, having, null, 0, 100);
    }

    private static Object[] byKey(List<Object[]> rows, Object... key) {
        for (Object[] r : rows) {
            boolean hit = true;
            for (int i = 0; i < key.length; i++) {
                if (!Objects.equals(r[i], key[i])) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return r;
            }
        }
        Assert.fail("未找到分组键 " + Arrays.toString(key) + "，现有: " + render(rows));
        return null;
    }

    private static List<Object> firstCol(List<Object[]> rows) {
        List<Object> out = new ArrayList<>();
        for (Object[] r : rows) {
            out.add(r[0]);
        }
        return out;
    }

    private static String render(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : rows) {
            sb.append('[');
            for (Object o : r) {
                sb.append(o).append(',');
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    // ============== TR2.1 COUNT 忽略 NULL / 空组 = 0 ==============
    @Test(timeout = 30000)
    public void tr21_countIgnoresNullAndEmptyGroupIsZero() {
        List<Object[]> rows = compute(aggList(agg(DbAggFn.COUNT, "num")), List.of("tag"));
        Assert.assertEquals("4 组", 4, rows.size());
        Assert.assertEquals("g1 COUNT", 3L, (long) d(byKey(rows, "g1")[1]));
        Assert.assertEquals("g2 COUNT（含 NULL 被忽略）", 2L, (long) d(byKey(rows, "g2")[1]));
        Assert.assertEquals("g3 COUNT 空组 = 0", 0L, (long) d(byKey(rows, "g3")[1]));
        Assert.assertEquals("g4 COUNT", 2L, (long) d(byKey(rows, "g4")[1]));
    }

    // ============== TR2.2 SUM 忽略 NULL / 全 NULL 组 = 0.0 ==============
    @Test(timeout = 30000)
    public void tr22_sumIgnoresNullAllNullGroupIsZero() {
        List<Object[]> rows = compute(aggList(agg(DbAggFn.SUM, "num")), List.of("tag"));
        Assert.assertEquals("g1 SUM", 60.0, d(byKey(rows, "g1")[1]), 1e-9);
        Assert.assertEquals("g2 SUM（忽略 NULL）", 20.0, d(byKey(rows, "g2")[1]), 1e-9);
        Assert.assertEquals("g3 全 NULL SUM = 0.0", 0.0, d(byKey(rows, "g3")[1]), 1e-9);
        Assert.assertEquals("g4 SUM", 3.0, d(byKey(rows, "g4")[1]), 1e-9);
    }

    // ============== TR2.3 AVG 先累加后除 ==============
    @Test(timeout = 30000)
    public void tr23_avgAccumulateThenDivide() {
        List<Object[]> rows = compute(aggList(agg(DbAggFn.AVG, "num")), List.of("tag"));
        Assert.assertEquals("g1 AVG = 60/3", 20.0, d(byKey(rows, "g1")[1]), 1e-9);
        Assert.assertEquals("g2 AVG = 20/2", 10.0, d(byKey(rows, "g2")[1]), 1e-9);
        Assert.assertEquals("g3 AVG 空组 = 0.0", 0.0, d(byKey(rows, "g3")[1]), 1e-9);
        Assert.assertEquals("g4 AVG = 3/2（非逐项平均）", 1.5, d(byKey(rows, "g4")[1]), 1e-9);
    }

    // ============== TR2.4 MIN/MAX 忽略 NULL ==============
    @Test(timeout = 30000)
    public void tr24_minMaxIgnoreNull() {
        List<Object[]> rows = compute(aggList(
                agg(DbAggFn.MIN, "num"), agg(DbAggFn.MAX, "num")), List.of("tag"));
        Object[] g1 = byKey(rows, "g1");
        Assert.assertEquals("g1 MIN", 10.0, d(g1[1]), 1e-9);
        Assert.assertEquals("g1 MAX", 30.0, d(g1[2]), 1e-9);
        Object[] g2 = byKey(rows, "g2");
        Assert.assertEquals("g2 MIN（忽略 NULL）", 5.0, d(g2[1]), 1e-9);
        Assert.assertEquals("g2 MAX", 15.0, d(g2[2]), 1e-9);
        Object[] g3 = byKey(rows, "g3");
        Assert.assertEquals("g3 空组 MIN = 0.0", 0.0, d(g3[1]), 1e-9);
        Assert.assertEquals("g3 空组 MAX = 0.0", 0.0, d(g3[2]), 1e-9);
        Object[] g4 = byKey(rows, "g4");
        Assert.assertEquals("g4 MIN", 1.0, d(g4[1]), 1e-9);
        Assert.assertEquals("g4 MAX", 2.0, d(g4[2]), 1e-9);
    }

    // ============== TR2.5 单键 GROUP BY 分桶 + 分组键升序 ==============
    @Test(timeout = 30000)
    public void tr25_singleKeyGroupingAndAscendingOrder() {
        List<Object[]> rows = compute(aggList(agg(DbAggFn.COUNT, "num")), List.of("tag"));
        Assert.assertEquals("默认序 = 分组键升序", Arrays.asList("g1", "g2", "g3", "g4"), firstCol(rows));
        // 每行长度 = 分组键 1 + 聚合 1
        Assert.assertEquals("行宽 = g + n", 2, rows.get(0).length);
    }

    // ============== TR2.6 双键 GROUP BY 复合键 ==============
    @Test(timeout = 30000)
    public void tr26_twoKeyCompositeGrouping() {
        List<Object[]> rows = compute(aggList(
                agg(DbAggFn.COUNT, "num"), agg(DbAggFn.SUM, "num")), List.of("tag", "region"));
        Assert.assertEquals("6 个复合组", 6, rows.size());
        Assert.assertEquals("行宽 = 2 + 2", 4, rows.get(0).length);

        Object[] a = byKey(rows, "g1", "r1");
        Assert.assertEquals("(g1,r1) COUNT", 2L, (long) d(a[2]));
        Assert.assertEquals("(g1,r1) SUM", 30.0, d(a[3]), 1e-9);
        Object[] b = byKey(rows, "g1", "r2");
        Assert.assertEquals("(g1,r2) COUNT", 1L, (long) d(b[2]));
        Assert.assertEquals("(g1,r2) SUM", 30.0, d(b[3]), 1e-9);
        Object[] c = byKey(rows, "g2", "r1");
        Assert.assertEquals("(g2,r1) COUNT（含 NULL 被忽略）", 1L, (long) d(c[2]));
        Assert.assertEquals("(g2,r1) SUM（NULL 忽略）", 5.0, d(c[3]), 1e-9);
        Object[] e = byKey(rows, "g3", "r1");
        Assert.assertEquals("(g3,r1) COUNT 空组", 0L, (long) d(e[2]));
        Assert.assertEquals("(g3,r1) SUM 空组", 0.0, d(e[3]), 1e-9);

        // 复合键默认序：(g1,r1)<(g1,r2)<(g2,r1)<(g2,r2)<(g3,r1)<(g4,r1)
        List<Object> keys = new ArrayList<>();
        for (Object[] r : rows) {
            keys.add(r[0] + "/" + r[1]);
        }
        Assert.assertEquals("复合键升序",
                Arrays.asList("g1/r1", "g1/r2", "g2/r1", "g2/r2", "g3/r1", "g4/r1"), keys);
    }

    // ============== TR2.7 HAVING（分组键 + 聚合别名两类左值） ==============
    @Test(timeout = 30000)
    public void tr27_havingOnGroupKeyAndAggAlias() {
        List<DbAggItem> aggs = aggList(agg(DbAggFn.COUNT, "num"));

        List<Object[]> byKey = computeHaving(aggs, List.of("tag"),
                List.of(crit(DbQueryOp.GE, "tag", "g2")));
        Assert.assertEquals("HAVING tag >= 'g2'", Arrays.asList("g2", "g3", "g4"), firstCol(byKey));

        List<Object[]> byAlias = computeHaving(aggs, List.of("tag"),
                List.of(crit(DbQueryOp.GT, "COUNT(num)", "1")));
        Assert.assertEquals("HAVING COUNT(num) > 1", Arrays.asList("g1", "g2", "g4"), firstCol(byAlias));
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
