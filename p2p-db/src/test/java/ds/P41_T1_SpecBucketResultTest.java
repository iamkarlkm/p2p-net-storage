package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBucket;
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
 * P41 T1：HavingSpec + GroupBySpec + GroupBucket + GroupByResult 骨架 5 TR 0F0E。
 *
 * <p>
 * TR1.1 HavingSpec 3 IAE + test 6 算子语义 + test(null) IAE
 * <br>TR1.2 GroupBySpec 6 IAE（plan·groupColumns·重复·distinctAggIndexes·havings）
 * <br>TR1.3 GroupBySpec fingerprint() 64-hex 稳定 + 不同配置不同指纹
 * <br>TR1.4 GroupBucket 3 IAE + fingerprint + equals/hashCode/toString + 不可变
 * <br>TR1.5 GroupByResult 2 IAE + size/bucket 越界 + deepEquals + fingerprint + 不可变
 */
public class P41_T1_SpecBucketResultTest {

    private static AggSpec spec(AggFn fn, String col) {
        return new AggSpec(fn, col);
    }

    private static AggQueryPlan plan(AggSpec... aggs) {
        List<List<ConditionNode>> minterms = new ArrayList<>();
        minterms.add(Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "eng")));
        return new AggQueryPlan(new DnfClause(minterms), Arrays.asList(aggs));
    }

    private static AggValue value(AggFn fn, String col, long count, double sum, double val) {
        return new AggValue(spec(fn, col), count, sum, val);
    }

    // ============== TR1.1 HavingSpec 3 IAE + test 6 算子 + test(null) IAE ==============

    @Test
    public void tr11_HavingSpecIaeAndOps() {
        expectIae(() -> new HavingSpec(-1, HavingOp.GT, 1.0), "aggIndex<0");
        expectIae(() -> new HavingSpec(0, null, 1.0), "op null");
        expectIae(() -> new HavingSpec(0, HavingOp.GE, Double.NaN), "threshold NaN");
        expectIae(() -> new HavingSpec(0, HavingOp.GE, Double.POSITIVE_INFINITY), "threshold Inf");

        // 边界合法：aggIndex=0
        Assert.assertEquals(0, new HavingSpec(0, HavingOp.EQ, 5.0).aggIndex);

        AggValue v = value(AggFn.COUNT, "price", 3, 0.0, 3.0);
        Assert.assertFalse(new HavingSpec(0, HavingOp.GT, 3.0).test(v));
        Assert.assertTrue(new HavingSpec(0, HavingOp.GE, 3.0).test(v));
        Assert.assertFalse(new HavingSpec(0, HavingOp.LT, 3.0).test(v));
        Assert.assertTrue(new HavingSpec(0, HavingOp.LE, 3.0).test(v));
        Assert.assertTrue(new HavingSpec(0, HavingOp.EQ, 3.0).test(v));
        Assert.assertFalse(new HavingSpec(0, HavingOp.NE, 3.0).test(v));
        Assert.assertTrue(new HavingSpec(0, HavingOp.NE, 4.0).test(v));

        expectIae(() -> new HavingSpec(0, HavingOp.GT, 1.0).test(null), "test null");
    }

    // ============== TR1.2 GroupBySpec 6 IAE ==============

    @Test
    public void tr12_GroupBySpecSixIae() {
        AggQueryPlan p = plan(spec(AggFn.COUNT, "price"), spec(AggFn.SUM, "price"));
        List<String> cols = Collections.singletonList("dept");

        // 1) plan null
        expectIae(() -> new GroupBySpec(null, cols, Collections.emptyList(), Collections.emptyList()), "plan null");
        // 2) groupColumns null / 空
        expectIae(() -> new GroupBySpec(p, null, Collections.emptyList(), Collections.emptyList()), "groupColumns null");
        expectIae(() -> new GroupBySpec(p, Collections.emptyList(), Collections.emptyList(), Collections.emptyList()),
                "groupColumns 空");
        // 3) groupColumns 含 null / 空白
        expectIae(() -> new GroupBySpec(p, Arrays.asList("dept", null), Collections.emptyList(),
                Collections.emptyList()), "groupColumns 含 null");
        expectIae(() -> new GroupBySpec(p, Collections.singletonList("  "), Collections.emptyList(),
                Collections.emptyList()), "groupColumns 空白");
        // 4) groupColumns 重复
        expectIae(() -> new GroupBySpec(p, Arrays.asList("dept", "dept"), Collections.emptyList(),
                Collections.emptyList()), "groupColumns 重复");
        // 5a) distinctAggIndexes 含 null
        expectIae(() -> new GroupBySpec(p, cols, Arrays.asList(new Integer[] {null}), Collections.emptyList()),
                "distinct 含 null");
        // 5b) distinctAggIndexes 越界
        expectIae(() -> new GroupBySpec(p, cols, Collections.singletonList(2), Collections.emptyList()),
                "distinct 越界");
        // 5c) distinctAggIndexes 对应非 COUNT
        expectIae(() -> new GroupBySpec(p, cols, Collections.singletonList(1), Collections.emptyList()),
                "distinct 非 COUNT");
        // 6a) havings 含 null
        expectIae(() -> new GroupBySpec(p, cols, Collections.emptyList(), Collections.singletonList(null)),
                "havings 含 null");
        // 6b) havings aggIndex 越界
        expectIae(() -> new GroupBySpec(p, cols, Collections.emptyList(),
                Collections.singletonList(new HavingSpec(2, HavingOp.GT, 1.0))), "havings 越界");

        // 非法构造不产生副作用：合法构造 OK（aggIndex 上界内）
        GroupBySpec ok = new GroupBySpec(p, cols, Collections.singletonList(0),
                Collections.singletonList(new HavingSpec(1, HavingOp.GE, 3.0)));
        Assert.assertEquals(1, ok.distinctAggIndexes.size());
        Assert.assertTrue(ok.isDistinct(0));
        Assert.assertFalse(ok.isDistinct(1));
    }

    // ============== TR1.3 GroupBySpec fingerprint 64-hex 稳定 ==============

    @Test
    public void tr13_GroupBySpecFingerprint() {
        AggQueryPlan p = plan(spec(AggFn.COUNT, "price"), spec(AggFn.SUM, "price"));
        GroupBySpec a = new GroupBySpec(p, Collections.singletonList("dept"), Collections.singletonList(0),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0)));
        // 同配置（新实例）→ 同指纹
        GroupBySpec b = new GroupBySpec(plan(spec(AggFn.COUNT, "price"), spec(AggFn.SUM, "price")),
                Collections.singletonList("dept"), Collections.singletonList(0),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0)));
        String fp = a.fingerprint();
        Assert.assertEquals(64, fp.length());
        Assert.assertTrue("需为 64-hex: " + fp, fp.matches("[0-9a-f]{64}"));
        Assert.assertEquals(fp, a.fingerprint());
        Assert.assertEquals(fp, b.fingerprint());

        // 不同分组列 → 不同指纹
        GroupBySpec diffCol = new GroupBySpec(p, Collections.singletonList("city"), Collections.singletonList(0),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0)));
        Assert.assertNotEquals(fp, diffCol.fingerprint());
        // 不同 HAVING 阈值 → 不同指纹
        GroupBySpec diffHv = new GroupBySpec(p, Collections.singletonList("dept"), Collections.singletonList(0),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 4.0)));
        Assert.assertNotEquals(fp, diffHv.fingerprint());
        // 不同 DISTINCT 标记 → 不同指纹
        GroupBySpec diffDistinct = new GroupBySpec(p, Collections.singletonList("dept"), Collections.emptyList(),
                Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0)));
        Assert.assertNotEquals(fp, diffDistinct.fingerprint());
    }

    // ============== TR1.4 GroupBucket 3 IAE + fingerprint + 不可变 ==============

    @Test
    public void tr14_GroupBucketIaeAndFingerprint() {
        List<String> key = Collections.singletonList("eng");
        List<AggValue> values = Collections.singletonList(value(AggFn.COUNT, "price", 3, 0.0, 3.0));

        expectIae(() -> new GroupBucket(null, values, 3), "key null");
        expectIae(() -> new GroupBucket(Arrays.asList("eng", null), values, 3), "key 含 null");
        expectIae(() -> new GroupBucket(key, null, 3), "values null");
        expectIae(() -> new GroupBucket(key, Arrays.asList(values.get(0), null), 3), "values 含 null");
        expectIae(() -> new GroupBucket(key, values, -1), "rowCount<0");

        // 边界合法：values 空（SELECT DISTINCT）+ rowCount=0
        GroupBucket empty = new GroupBucket(key, Collections.emptyList(), 0);
        Assert.assertEquals(0, empty.size());
        Assert.assertEquals(0L, empty.rowCount);
        Assert.assertEquals(1, empty.key.size());

        GroupBucket gb = new GroupBucket(key, values, 3);
        String fp = gb.fingerprint();
        Assert.assertEquals(64, fp.length());
        Assert.assertTrue("需为 64-hex: " + fp, fp.matches("[0-9a-f]{64}"));
        Assert.assertEquals(fp, gb.fingerprint());
        Assert.assertEquals(gb, new GroupBucket(key, values, 3));
        Assert.assertEquals(gb.hashCode(), new GroupBucket(key, values, 3).hashCode());
        Assert.assertNotEquals(gb, new GroupBucket(key, values, 4));
        Assert.assertNotEquals(gb, new GroupBucket(Collections.singletonList("ops"), values, 3));
        // 不同 value → 不同 fingerprint
        Assert.assertNotEquals(fp, new GroupBucket(key,
                Collections.singletonList(value(AggFn.COUNT, "price", 4, 0.0, 4.0)), 3).fingerprint());

        Assert.assertTrue(gb.toString().contains("eng"));
        Assert.assertTrue(gb.toString().contains("COUNT(price)=3.0"));

        try {
            gb.key.add("x");
            Assert.fail("key 应为不可变");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
        try {
            gb.values.clear();
            Assert.fail("values 应为不可变");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
    }

    // ============== TR1.5 GroupByResult 2 IAE + 越界 + deepEquals + 不可变 ==============

    @Test
    public void tr15_GroupByResultIaeAndDeepEquals() {
        GroupBucket b1 = new GroupBucket(Collections.singletonList("eng"),
                Collections.singletonList(value(AggFn.COUNT, "price", 3, 0.0, 3.0)), 3);
        GroupBucket b2 = new GroupBucket(Collections.singletonList("ops"),
                Collections.singletonList(value(AggFn.COUNT, "price", 2, 0.0, 2.0)), 2);

        expectIae(() -> new GroupByResult(null, 5), "buckets null");
        expectIae(() -> new GroupByResult(Arrays.asList(b1, null), 5), "buckets 含 null");
        expectIae(() -> new GroupByResult(Collections.singletonList(b1), -1), "totalRowCount<0");

        // 边界合法：空桶（HAVING 全剔除）
        Assert.assertEquals(0, new GroupByResult(Collections.emptyList(), 5).size());

        GroupByResult r1 = new GroupByResult(Arrays.asList(b1, b2), 5);
        GroupByResult r2 = new GroupByResult(Arrays.asList(b1, b2), 5);
        GroupByResult r3 = new GroupByResult(Arrays.asList(b1, b2), 6);
        Assert.assertEquals(2, r1.size());
        Assert.assertEquals(5L, r1.totalRowCount);
        Assert.assertSame(b1, r1.bucket(0));
        Assert.assertSame(b2, r1.bucket(1));
        expectIoobe(() -> r1.bucket(2));
        expectIoobe(() -> r1.bucket(-1));

        String fp = r1.fingerprint();
        Assert.assertEquals(64, fp.length());
        Assert.assertTrue("需为 64-hex: " + fp, fp.matches("[0-9a-f]{64}"));
        Assert.assertEquals(fp, r2.fingerprint());
        Assert.assertNotEquals(fp, r3.fingerprint());

        Assert.assertTrue(GroupByResult.deepEquals(r1, r2));
        Assert.assertTrue(GroupByResult.deepEquals(r1, r1));
        Assert.assertFalse(GroupByResult.deepEquals(r1, r3));
        Assert.assertFalse(GroupByResult.deepEquals(r1, null));
        Assert.assertFalse(GroupByResult.deepEquals(null, r1));
        Assert.assertEquals(r1, r2);
        Assert.assertEquals(r1.hashCode(), r2.hashCode());
        Assert.assertNotEquals(r1, r3);

        try {
            r1.buckets.add(b1);
            Assert.fail("buckets 应为不可变");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
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

    private static void expectIoobe(Runnable r) {
        try {
            r.run();
            Assert.fail("期望 IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // OK
        }
    }
}
