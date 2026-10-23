package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P39 T3：union 去重 3 TR 0F0E。
 *
 * <p>
 * DSL 例：{@code (a = 1 OR b = 2) AND (a = 1 OR c = 3)} → 4 minterm
 * {@code [a1,a1] [a1,c3] [b2,a1] [b2,c3]}（DNF 允许重叠）。
 *
 * <p>
 * TR3.1 重叠 minterm 去重后 5 聚合 = 手写「distinct PK 集合」真值（含 price=NULL 行）
 * <br>TR3.2 minterm 顺序无关：同数据不同 minterm 排列 → 同 fingerprint + deepEquals
 * <br>TR3.3 unionRowCount = distinct PK 数（≠ Σ 各 minterm 命中数）
 */
public class P39_T3_UnionDedupTest {

    public static class Row extends DsTableAdapter {
        final long a;
        final long b;
        final long c;
        final Double price;

        public Row(long id, long a, long b, long c, Double price) {
            setId(id);
            this.a = a;
            this.b = b;
            this.c = c;
            this.price = price;
        }
    }

    private static final Map<String, ToDoubleFunction<Row>> COLS =
            Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    /** 5 行 + 1 行 price=NULL（r6 命中多条 minterm，用于验证去重 + NULL 忽略）。 */
    private static List<Row> data() {
        return Arrays.asList(
                new Row(1L, 1, 1, 7, 10.0),
                new Row(2L, 1, 2, 6, 20.0),
                new Row(3L, 2, 1, 5, 30.0),
                new Row(4L, 2, 2, 4, 40.0),
                new Row(5L, 3, 1, 3, 50.0),
                new Row(6L, 1, 9, 3, null));
    }

    private static ConditionNode a1() {
        return ConditionNode.orm("a", DslToken.OP_EQ, 1L);
    }

    private static ConditionNode b2() {
        return ConditionNode.orm("b", DslToken.OP_EQ, 2L);
    }

    private static ConditionNode c3() {
        return ConditionNode.orm("c", DslToken.OP_EQ, 3L);
    }

    /** (a=1 OR b=2) AND (a=1 OR c=3) → 4 minterms（顺序见类注释）。 */
    private static DnfClause clause4() {
        List<List<ConditionNode>> mts = new ArrayList<>();
        mts.add(Arrays.asList(a1(), a1()));
        mts.add(Arrays.asList(a1(), c3()));
        mts.add(Arrays.asList(b2(), a1()));
        mts.add(Arrays.asList(b2(), c3()));
        return new DnfClause(mts);
    }

    private static DnfClause clause4Reversed() {
        List<List<ConditionNode>> mts = new ArrayList<>();
        mts.add(Arrays.asList(b2(), c3()));
        mts.add(Arrays.asList(b2(), a1()));
        mts.add(Arrays.asList(a1(), c3()));
        mts.add(Arrays.asList(a1(), a1()));
        return new DnfClause(mts);
    }

    private static AggQueryPlan plan(DnfClause clause) {
        return new AggQueryPlan(clause, Arrays.asList(
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"),
                new AggSpec(AggFn.AVG, "price"), new AggSpec(AggFn.MIN, "price"),
                new AggSpec(AggFn.MAX, "price")));
    }

    /** AND 语义逐 minterm 过滤（内存模拟 WHERE）。 */
    private static AggRowSource<Row> source() {
        List<Row> all = data();
        return minterm -> {
            List<Row> out = new ArrayList<>();
            for (Row r : all) {
                boolean ok = true;
                for (ConditionNode n : minterm) {
                    if (!match(n, r)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    out.add(r);
                }
            }
            return out;
        };
    }

    private static boolean match(ConditionNode n, Row r) {
        long v = col(r, n.ormCol);
        if (n.ormOp == DslToken.OP_EQ) {
            return v == ((Number) n.ormValueA).longValue();
        }
        throw new IllegalStateException("未支持的 op: " + n.ormOp);
    }

    private static long col(Row r, String name) {
        switch (name) {
            case "a":
                return r.a;
            case "b":
                return r.b;
            case "c":
                return r.c;
            default:
                throw new IllegalStateException("未知列: " + name);
        }
    }

    // ============== TR3.1 去重后 5 聚合 = 手写真值 ==============

    @Test
    public void tr31_DedupFiveAggsEqualHandTruth() {
        AggResult r = new AggExecutor<Row>().execute(plan(clause4()), source(), COLS);
        Assert.assertEquals(5, r.size());
        // union = {r1(10), r2(20), r6(null)} → COUNT=2, SUM=30, AVG=15, MIN=10, MAX=20
        Assert.assertEquals(2L, r.value(0).count);
        Assert.assertEquals(2.0, r.value(0).value, 0.0);
        Assert.assertEquals(30.0, r.value(1).value, 0.0);
        Assert.assertEquals(15.0, r.value(2).value, 0.0);
        Assert.assertEquals(10.0, r.value(3).value, 0.0);
        Assert.assertEquals(20.0, r.value(4).value, 0.0);

        // 若不去重：COUNT=5, SUM=30+0+20+0=... 显式反证（Σ 各 minterm：m0={r1,r2,r6}=30, m2={r2}=20 → 50）
        double naiveSum = 30.0 + 20.0;
        Assert.assertNotEquals(naiveSum, r.value(1).value, 0.0);
    }

    // ============== TR3.2 minterm 顺序无关 ==============

    @Test
    public void tr32_MintermOrderIrrelevant() {
        AggResult forward = new AggExecutor<Row>().execute(plan(clause4()), source(), COLS);
        AggResult reversed = new AggExecutor<Row>().execute(plan(clause4Reversed()), source(), COLS);
        Assert.assertTrue(AggResult.deepEquals(forward, reversed));
        Assert.assertEquals(forward.fingerprint(), reversed.fingerprint());
        Assert.assertEquals(forward.unionRowCount, reversed.unionRowCount);
    }

    // ============== TR3.3 unionRowCount = distinct PK 数 ==============

    @Test
    public void tr33_UnionRowCountIsDistinctPk() {
        AggResult r = new AggExecutor<Row>().execute(plan(clause4()), source(), COLS);
        // 各 minterm 命中数：m0={1,2,6}=3, m1={6}=1, m2={2}=1, m3={}=0 → Σ=5，distinct={1,2,6}=3
        Assert.assertEquals(3L, r.unionRowCount);
        // unionRowCount < Σ 各 minterm 命中数（去重生效的硬证据）
        Assert.assertTrue("unionRowCount 必须 < 未去重命中数之和 5", r.unionRowCount < 5L);
    }
}
