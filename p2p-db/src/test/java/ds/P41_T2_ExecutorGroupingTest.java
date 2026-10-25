package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBucket;
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

/**
 * P41 T2：GroupByExecutor 内存行源分组 6 TR 0F0E。
 *
 * <p>
 * TR2.1 单列 GROUP BY + 5 聚合 vs 手算真值
 * <br>TR2.2 双列 GROUP BY + 桶序字典序确定性
 * <br>TR2.3 COUNT(DISTINCT col) 去重计数
 * <br>TR2.4 空 aggs → SELECT DISTINCT 分组列
 * <br>TR2.5 HAVING 单谓词 + 多谓词 AND
 * <br>TR2.6 ≥6 IAE + 2 ISE
 */
public class P41_T2_ExecutorGroupingTest {

    /**
     * 测试实体：dept/city 分组列，price 聚合列（可 NULL）。
     */
    public static class Row extends DsTableAdapter {

        final String dept;
        final String city;
        final Double price;

        public Row(long id, String dept, String city, Double price) {
            setId(id);
            this.dept = dept;
            this.city = city;
            this.price = price;
        }

        /**
         * 无主键行（id 保持 null，用于 ISE 场景）。
         */
        public Row(String dept, String city, Double price) {
            this.dept = dept;
            this.city = city;
            this.price = price;
        }
    }

    private static final Map<String, Function<Row, String>> GROUP_COLS
            = Map.of("dept", r -> r.dept, "city", r -> r.city);

    private static final Map<String, ToDoubleFunction<Row>> AGG_COLS
            = Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    private static AggSpec a(AggFn fn) {
        return new AggSpec(fn, "price");
    }

    private static AggQueryPlan plan(AggSpec... aggs) {
        return new AggQueryPlan(singleMinterm(), Arrays.asList(aggs));
    }

    private static DnfClause singleMinterm() {
        return new DnfClause(Collections.singletonList(
                Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GE, 0))));
    }

    private static AggRowSource<Row> source(List<Row> rows) {
        return minterm -> rows;
    }

    private static GroupBySpec spec(AggQueryPlan p, List<String> cols) {
        return new GroupBySpec(p, cols, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * 6 行测试数据（按主键 1..6）。
     */
    private static List<Row> rows() {
        List<Row> rs = new ArrayList<>();
        rs.add(new Row(1L, "eng", "sfo", 10.0));
        rs.add(new Row(2L, "eng", "sfo", 20.0));
        rs.add(new Row(3L, "eng", "nyc", 30.0));
        rs.add(new Row(4L, "ops", "sfo", 40.0));
        rs.add(new Row(5L, "ops", "nyc", 50.0));
        rs.add(new Row(6L, "eng", "sfo", 10.0));
        return rs;
    }

    private static void assertAgg(AggValue v, AggFn fn, long count, double sum, double val) {
        Assert.assertEquals(fn, v.spec.fn);
        Assert.assertEquals(count, v.count);
        Assert.assertEquals(sum, v.sum, 1e-12);
        Assert.assertEquals(val, v.value, 1e-12);
    }

    // ============== TR2.1 单列 GROUP BY + 5 聚合 ==============
    @Test
    public void tr21_SingleColumnFiveAggs() {
        GroupBySpec s = spec(plan(a(AggFn.COUNT), a(AggFn.SUM), a(AggFn.AVG), a(AggFn.MIN), a(AggFn.MAX)),
                Collections.singletonList("dept"));
        GroupByResult r = new GroupByExecutor<Row>().execute(s, source(rows()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(2, r.size());
        Assert.assertEquals(6L, r.totalRowCount);

        GroupBucket eng = r.bucket(0);
        Assert.assertEquals(Collections.singletonList("eng"), eng.key);
        Assert.assertEquals(4L, eng.rowCount);
        Assert.assertEquals(5, eng.size());
        assertAgg(eng.values.get(0), AggFn.COUNT, 4, 0.0, 4.0);
        assertAgg(eng.values.get(1), AggFn.SUM, 4, 70.0, 70.0);
        assertAgg(eng.values.get(2), AggFn.AVG, 4, 70.0, 17.5);
        assertAgg(eng.values.get(3), AggFn.MIN, 4, 70.0, 10.0);
        assertAgg(eng.values.get(4), AggFn.MAX, 4, 70.0, 30.0);

        GroupBucket ops = r.bucket(1);
        Assert.assertEquals(Collections.singletonList("ops"), ops.key);
        Assert.assertEquals(2L, ops.rowCount);
        assertAgg(ops.values.get(0), AggFn.COUNT, 2, 0.0, 2.0);
        assertAgg(ops.values.get(1), AggFn.SUM, 2, 90.0, 90.0);
        assertAgg(ops.values.get(2), AggFn.AVG, 2, 90.0, 45.0);
        assertAgg(ops.values.get(3), AggFn.MIN, 2, 90.0, 40.0);
        assertAgg(ops.values.get(4), AggFn.MAX, 2, 90.0, 50.0);
    }

    // ============== TR2.2 双列 GROUP BY + 桶序字典序 ==============
    @Test
    public void tr22_TwoColumnGroupingAndOrder() {
        GroupBySpec s = spec(plan(a(AggFn.COUNT), a(AggFn.SUM)), Arrays.asList("dept", "city"));
        GroupByResult r = new GroupByExecutor<Row>().execute(s, source(rows()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(4, r.size());
        // 桶序 = 逐元素字典序：[eng,nyc] < [eng,sfo] < [ops,nyc] < [ops,sfo]
        Assert.assertEquals(Arrays.asList("eng", "nyc"), r.bucket(0).key);
        Assert.assertEquals(Arrays.asList("eng", "sfo"), r.bucket(1).key);
        Assert.assertEquals(Arrays.asList("ops", "nyc"), r.bucket(2).key);
        Assert.assertEquals(Arrays.asList("ops", "sfo"), r.bucket(3).key);

        Assert.assertEquals(1L, r.bucket(0).rowCount);
        Assert.assertEquals(3L, r.bucket(1).rowCount);
        Assert.assertEquals(1L, r.bucket(2).rowCount);
        Assert.assertEquals(1L, r.bucket(3).rowCount);
        assertAgg(r.bucket(1).values.get(1), AggFn.SUM, 3, 40.0, 40.0);

        // 确定性：同输入两跑 → 同 fingerprint
        GroupByResult r2 = new GroupByExecutor<Row>().execute(s, source(rows()), GROUP_COLS, AGG_COLS);
        Assert.assertEquals(r.fingerprint(), r2.fingerprint());
        Assert.assertTrue(GroupByResult.deepEquals(r, r2));
    }

    // ============== TR2.3 COUNT(DISTINCT) ==============
    @Test
    public void tr23_CountDistinct() {
        // aggs=[COUNT(price)]，distinctAggIndexes=[0] → eng {10,20,30}=3 / ops {40,50}=2
        GroupBySpec s = new GroupBySpec(plan(a(AggFn.COUNT)), Collections.singletonList("dept"),
                Collections.singletonList(0), Collections.emptyList());
        GroupByResult r = new GroupByExecutor<Row>().execute(s, source(rows()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(2, r.size());
        assertAgg(r.bucket(0).values.get(0), AggFn.COUNT, 3, 0.0, 3.0);
        assertAgg(r.bucket(1).values.get(0), AggFn.COUNT, 2, 0.0, 2.0);
        // COUNT(DISTINCT) < 普通 COUNT（eng 4 vs 3）
        Assert.assertEquals(4L, r.bucket(0).rowCount);
    }

    // ============== TR2.4 空 aggs → SELECT DISTINCT ==============
    @Test
    public void tr24_EmptyAggsSelectDistinct() {
        GroupBySpec s = spec(plan(), Arrays.asList("dept", "city"));
        GroupByResult r = new GroupByExecutor<Row>().execute(s, source(rows()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(4, r.size());
        Assert.assertEquals(6L, r.totalRowCount);
        for (int i = 0; i < r.size(); i++) {
            GroupBucket b = r.bucket(i);
            Assert.assertEquals(0, b.size());
            Assert.assertTrue(b.values.isEmpty());
        }
        // rowCount = 组内 distinct-PK 行数
        Assert.assertEquals(3L, r.bucket(1).rowCount);
        Assert.assertEquals(Arrays.asList("eng", "sfo"), r.bucket(1).key);
    }

    // ============== TR2.5 HAVING 单谓词 + 多谓词 AND ==============
    @Test
    public void tr25_HavingSingleAndMulti() {
        // HAVING COUNT(price) >= 3 → 只留 eng
        GroupBySpec single = new GroupBySpec(plan(a(AggFn.COUNT)), Collections.singletonList("dept"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0)));
        GroupByResult r1 = new GroupByExecutor<Row>().execute(single, source(rows()), GROUP_COLS, AGG_COLS);
        Assert.assertEquals(1, r1.size());
        Assert.assertEquals(Collections.singletonList("eng"), r1.bucket(0).key);
        Assert.assertEquals(6L, r1.totalRowCount);

        // HAVING COUNT(price) >= 2 AND AVG(price) <= 20 → eng 过（4, 17.5）；ops 剔除（AVG 45）
        GroupBySpec multi = new GroupBySpec(plan(a(AggFn.COUNT), a(AggFn.AVG)),
                Collections.singletonList("dept"), Collections.emptyList(),
                Arrays.asList(new HavingSpec(0, HavingOp.GE, 2.0), new HavingSpec(1, HavingOp.LE, 20.0)));
        GroupByResult r2 = new GroupByExecutor<Row>().execute(multi, source(rows()), GROUP_COLS, AGG_COLS);
        Assert.assertEquals(1, r2.size());
        Assert.assertEquals(Collections.singletonList("eng"), r2.bucket(0).key);

        // HAVING 全剔除 → 空结果，totalRowCount 仍为 union 去重后总数
        GroupBySpec none = new GroupBySpec(plan(a(AggFn.SUM)), Collections.singletonList("dept"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GT, 1000.0)));
        GroupByResult r3 = new GroupByExecutor<Row>().execute(none, source(rows()), GROUP_COLS, AGG_COLS);
        Assert.assertEquals(0, r3.size());
        Assert.assertEquals(6L, r3.totalRowCount);
    }

    // ============== TR2.6 ≥6 IAE + 2 ISE ==============
    @Test
    public void tr26_IaeIse() {
        GroupByExecutor<Row> ex = new GroupByExecutor<>();
        GroupBySpec s = spec(plan(a(AggFn.COUNT)), Collections.singletonList("dept"));
        AggRowSource<Row> src = source(rows());

        expectIae(() -> ex.execute(null, src, GROUP_COLS, AGG_COLS), "spec null");
        expectIae(() -> ex.execute(s, null, GROUP_COLS, AGG_COLS), "source null");
        expectIae(() -> ex.execute(s, src, null, AGG_COLS), "groupCols null");
        expectIae(() -> ex.execute(s, src, GROUP_COLS, null), "aggCols null");

        // 分组列无 extractor（region 不在 map）
        GroupBySpec noGroup = spec(plan(a(AggFn.COUNT)), Collections.singletonList("region"));
        expectIae(() -> ex.execute(noGroup, src, GROUP_COLS, AGG_COLS), "分组列无 extractor");
        // 聚合列无 extractor（score 不在 map）
        GroupBySpec noAgg = new GroupBySpec(
                new AggQueryPlan(singleMinterm(), Collections.singletonList(new AggSpec(AggFn.COUNT, "score"))),
                Collections.singletonList("dept"), Collections.emptyList(), Collections.emptyList());
        expectIae(() -> ex.execute(noAgg, src, GROUP_COLS, AGG_COLS), "聚合列无 extractor");
        // groupColumns 空 → GroupBySpec 构造期 IAE
        expectIae(() -> new GroupBySpec(plan(a(AggFn.COUNT)), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList()), "groupColumns 空");
        // DISTINCT 非 COUNT → GroupBySpec 构造期 IAE
        expectIae(() -> new GroupBySpec(plan(a(AggFn.SUM)), Collections.singletonList("dept"),
                Collections.singletonList(0), Collections.emptyList()), "DISTINCT 非 COUNT");

        // ISE：fetch 返回 null
        expectIse(() -> ex.execute(s, minterm -> null, GROUP_COLS, AGG_COLS), "fetch null");
        // ISE：getId() == null
        Row noId = new Row("eng", "sfo", 10.0);
        Assert.assertNull(noId.getId());
        expectIse(() -> ex.execute(s, source(Collections.singletonList(noId)), GROUP_COLS, AGG_COLS), "id null");
    }

    /* ================ helpers ================ */
    private static void expectIae(Runnable r, String label) {
        try {
            r.run();
            Assert.fail("期望 IAE: " + label);
        } catch (IllegalArgumentException expected) {
            // OK
        }
    }

    private static void expectIse(Runnable r, String label) {
        try {
            r.run();
            Assert.fail("期望 ISE: " + label);
        } catch (IllegalStateException expected) {
            // OK
        }
    }
}
