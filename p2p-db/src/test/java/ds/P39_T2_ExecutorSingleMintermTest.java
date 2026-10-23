package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P39 T2：AggExecutor 单 minterm 6 TR 0F0E。
 *
 * <p>
 * TR2.1 COUNT 非 NULL 计数（忽略 NULL）<br>
 * TR2.2 SUM（含全 NULL → 0.0, count=0）<br>
 * TR2.3 AVG = Σsum/count（count=0 → 0.0）<br>
 * TR2.4 MIN<br>
 * TR2.5 MAX<br>
 * TR2.6 4 IAE + 2 ISE + aggs 空短路 0 fetch
 */
public class P39_T2_ExecutorSingleMintermTest {

    /**
     * 测试实体：price 可为 null（NULL 语义）。
     */
    public static class Row extends DsTableAdapter {

        final Double price;

        public Row(long id, Double price) {
            setId(id);
            this.price = price;
        }

        /**
         * 无主键行（id 保持 null，用于 ISE 场景）。
         */
        public Row(Double price) {
            this.price = price;
        }
    }

    private static final Map<String, ToDoubleFunction<Row>> COLS
            = Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    private static AggQueryPlan planOf(AggFn... fns) {
        List<AggSpec> aggs = new ArrayList<>();
        for (AggFn f : fns) {
            aggs.add(new AggSpec(f, "price"));
        }
        return new AggQueryPlan(singleMinterm(), aggs);
    }

    private static DnfClause singleMinterm() {
        return new DnfClause(Collections.singletonList(
                Collections.singletonList(ConditionNode.orm("price", DslToken.OP_GE, 0))));
    }

    private static AggRowSource<Row> sourceOf(List<Row> rows) {
        return minterm -> rows;
    }

    private static List<Row> rows(boolean withNull) {
        List<Row> rs = new ArrayList<>();
        rs.add(new Row(1L, 10.0));
        rs.add(new Row(2L, 20.0));
        if (withNull) {
            rs.add(new Row(3L, null));
        }
        rs.add(new Row(4L, 40.0));
        return rs;
    }

    private static AggValue single(AggResult r) {
        Assert.assertEquals(1, r.size());
        return r.value(0);
    }

    // ============== TR2.1 COUNT ==============
    @Test
    public void tr21_CountIgnoresNull() {
        AggResult r = new AggExecutor<Row>().execute(planOf(AggFn.COUNT), sourceOf(rows(true)), COLS);
        AggValue v = single(r);
        Assert.assertEquals(3L, v.count);
        Assert.assertEquals(0.0, v.sum, 0.0);
        Assert.assertEquals(3.0, v.value, 0.0);
        Assert.assertEquals(4L, r.unionRowCount);
    }

    // ============== TR2.2 SUM ==============
    @Test
    public void tr22_SumAndAllNull() {
        AggResult r = new AggExecutor<Row>().execute(planOf(AggFn.SUM), sourceOf(rows(true)), COLS);
        AggValue v = single(r);
        Assert.assertEquals(3L, v.count);
        Assert.assertEquals(70.0, v.sum, 0.0);
        Assert.assertEquals(70.0, v.value, 0.0);

        // 全 NULL → sum=0.0, count=0, value=0.0
        List<Row> allNull = Arrays.asList(new Row(1L, null), new Row(2L, null));
        AggResult r2 = new AggExecutor<Row>().execute(planOf(AggFn.SUM), sourceOf(allNull), COLS);
        AggValue v2 = single(r2);
        Assert.assertEquals(0L, v2.count);
        Assert.assertEquals(0.0, v2.sum, 0.0);
        Assert.assertEquals(0.0, v2.value, 0.0);
        Assert.assertEquals(2L, r2.unionRowCount);
    }

    // ============== TR2.3 AVG ==============
    @Test
    public void tr23_AvgIsSumOverCount() {
        AggResult r = new AggExecutor<Row>().execute(planOf(AggFn.AVG), sourceOf(rows(true)), COLS);
        AggValue v = single(r);
        Assert.assertEquals(3L, v.count);
        Assert.assertEquals(70.0, v.sum, 0.0);
        Assert.assertEquals(70.0 / 3.0, v.value, 1e-12);

        // count=0 → 0.0
        AggResult r2 = new AggExecutor<Row>().execute(planOf(AggFn.AVG),
                sourceOf(Collections.singletonList(new Row(9L, null))), COLS);
        Assert.assertEquals(0.0, single(r2).value, 0.0);
    }

    // ============== TR2.4 MIN ==============
    @Test
    public void tr24_Min() {
        AggResult r = new AggExecutor<Row>().execute(planOf(AggFn.MIN), sourceOf(rows(true)), COLS);
        AggValue v = single(r);
        Assert.assertEquals(3L, v.count);
        Assert.assertEquals(10.0, v.value, 0.0);
    }

    // ============== TR2.5 MAX ==============
    @Test
    public void tr25_Max() {
        AggResult r = new AggExecutor<Row>().execute(planOf(AggFn.MAX), sourceOf(rows(true)), COLS);
        AggValue v = single(r);
        Assert.assertEquals(3L, v.count);
        Assert.assertEquals(40.0, v.value, 0.0);
    }

    // ============== TR2.6 4 IAE + 2 ISE + 短路 ==============
    @Test
    public void tr26_IaeIseAndShortCircuit() {
        AggExecutor<Row> ex = new AggExecutor<>();
        AggQueryPlan plan = planOf(AggFn.COUNT);
        AggRowSource<Row> src = sourceOf(rows(false));

        expectIae(() -> ex.execute(null, src, COLS), "plan null");
        expectIae(() -> ex.execute(plan, null, COLS), "source null");
        expectIae(() -> ex.execute(plan, src, null), "cols null");
        // 未知列（plan 用 score，COLS 只有 price）
        AggQueryPlan unknownCol = new AggQueryPlan(singleMinterm(),
                Collections.singletonList(new AggSpec(AggFn.COUNT, "score")));
        expectIae(() -> ex.execute(unknownCol, src, COLS), "未知列");

        // ISE: fetch 返回 null
        expectIse(() -> ex.execute(plan, minterm -> null, COLS), "fetch null");
        // ISE: getId() == null
        Row noId = new Row((Double) 10.0);
        Assert.assertNull(noId.getId());
        expectIse(() -> ex.execute(plan, sourceOf(Collections.singletonList(noId)), COLS), "id null");

        // 短路：aggs 空 → 0 次 fetch，values 空，unionRowCount=0
        AtomicInteger fetchCount = new AtomicInteger();
        AggQueryPlan emptyAggs = new AggQueryPlan(singleMinterm(), Collections.emptyList());
        AggResult r = ex.execute(emptyAggs, minterm -> {
            fetchCount.incrementAndGet();
            return rows(false);
        }, COLS);
        Assert.assertEquals(0, fetchCount.get());
        Assert.assertEquals(0, r.size());
        Assert.assertEquals(0L, r.unionRowCount);
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
