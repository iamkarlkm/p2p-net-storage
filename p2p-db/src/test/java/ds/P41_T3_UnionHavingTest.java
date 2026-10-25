package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
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
 * P41 T3：union 去重语义 + HAVING 剔除 + minterm 顺序无关 3 TR 0F0E。
 *
 * <p>
 * TR3.1 重叠 minterm（[eng] [eng,nyc] [ops] [ops,nyc]）先按 PK 去重再分组 vs 手写真值
 * <br>TR3.2 HAVING 剔除整桶（COUNT &ge; 3 只留 eng）
 * <br>TR3.3 minterm 顺序无关（倒序 minterm → 同 fingerprint，R7 长尾）
 */
public class P41_T3_UnionHavingTest {

    public static class Row extends DsTableAdapter {

        final String dept;
        final Double price;

        public Row(long id, String dept, Double price) {
            setId(id);
            this.dept = dept;
            this.price = price;
        }
    }

    private static final Map<String, Function<Row, String>> GROUP_COLS = Map.of("dept", r -> r.dept);
    private static final Map<String, ToDoubleFunction<Row>> AGG_COLS
            = Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    private static Row r(long id, String dept, double price) {
        return new Row(id, dept, price);
    }

    private static List<ConditionNode> m(String col, Object v) {
        return Collections.singletonList(ConditionNode.orm(col, DslToken.OP_EQ, v));
    }

    private static List<ConditionNode> m2(String c1, Object v1, String c2, Object v2) {
        return Arrays.asList(ConditionNode.orm(c1, DslToken.OP_EQ, v1),
                ConditionNode.orm(c2, DslToken.OP_EQ, v2));
    }

    private static String sig(List<ConditionNode> mt) {
        StringBuilder sb = new StringBuilder();
        for (ConditionNode n : mt) {
            sb.append(n.ormCol).append('=').append(n.ormValueA).append(';');
        }
        return sb.toString();
    }

    /** 按 minterm 签名分派行集（可让不同 minterm 返回重叠行）。 */
    private static AggRowSource<Row> source(Map<String, List<Row>> byMinterm) {
        return minterm -> {
            List<Row> rs = byMinterm.get(sig(minterm));
            return rs == null ? Collections.emptyList() : rs;
        };
    }

    private static Map<String, List<Row>> overlaps() {
        Map<String, List<Row>> mp = new HashMap<>();
        mp.put("dept=eng;", Arrays.asList(r(1L, "eng", 10.0), r(2L, "eng", 20.0), r(3L, "eng", 30.0)));
        mp.put("dept=eng;city=nyc;", Collections.singletonList(r(3L, "eng", 30.0)));
        mp.put("dept=ops;", Arrays.asList(r(4L, "ops", 40.0), r(5L, "ops", 50.0)));
        mp.put("dept=ops;city=nyc;", Collections.singletonList(r(5L, "ops", 50.0)));
        return mp;
    }

    private static DnfClause clause(boolean reverse) {
        List<List<ConditionNode>> ms = new ArrayList<>();
        ms.add(m("dept", "eng"));
        ms.add(m2("dept", "eng", "city", "nyc"));
        ms.add(m("dept", "ops"));
        ms.add(m2("dept", "ops", "city", "nyc"));
        if (reverse) {
            Collections.reverse(ms);
        }
        return new DnfClause(ms);
    }

    private static GroupBySpec spec(DnfClause clause, List<HavingSpec> havings) {
        AggQueryPlan plan = new AggQueryPlan(clause, Arrays.asList(
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price")));
        return new GroupBySpec(plan, Collections.singletonList("dept"),
                Collections.emptyList(), havings);
    }

    // ============== TR3.1 重叠 minterm union 去重 ==============

    @Test
    public void tr31_OverlappingMintermUnionDedup() {
        GroupByResult r = new GroupByExecutor<Row>().execute(
                spec(clause(false), Collections.emptyList()), source(overlaps()), GROUP_COLS, AGG_COLS);

        // 全局 PK 去重后 {1,2,3,4,5} = 5 行（若不去重则 eng=4 / ops=3）
        Assert.assertEquals(5L, r.totalRowCount);
        Assert.assertEquals(2, r.size());

        Assert.assertEquals(Collections.singletonList("eng"), r.bucket(0).key);
        Assert.assertEquals(3L, r.bucket(0).rowCount);
        Assert.assertEquals(3.0, r.bucket(0).values.get(0).value, 1e-12);
        Assert.assertEquals(60.0, r.bucket(0).values.get(1).value, 1e-12);

        Assert.assertEquals(Collections.singletonList("ops"), r.bucket(1).key);
        Assert.assertEquals(2L, r.bucket(1).rowCount);
        Assert.assertEquals(2.0, r.bucket(1).values.get(0).value, 1e-12);
        Assert.assertEquals(90.0, r.bucket(1).values.get(1).value, 1e-12);
    }

    // ============== TR3.2 HAVING 剔除整桶 ==============

    @Test
    public void tr32_HavingRemovesWholeBucket() {
        GroupByResult r = new GroupByExecutor<Row>().execute(
                spec(clause(false), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 3.0))),
                source(overlaps()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(1, r.size());
        Assert.assertEquals(Collections.singletonList("eng"), r.bucket(0).key);
        // 总行数不受 HAVING 影响（union 去重后 5）
        Assert.assertEquals(5L, r.totalRowCount);

        // HAVING SUM > 100 → 全剔除，空结果
        GroupByResult r2 = new GroupByExecutor<Row>().execute(
                spec(clause(false), Collections.singletonList(new HavingSpec(1, HavingOp.GT, 100.0))),
                source(overlaps()), GROUP_COLS, AGG_COLS);
        Assert.assertEquals(0, r2.size());
        Assert.assertEquals(5L, r2.totalRowCount);
    }

    // ============== TR3.3 minterm 顺序无关 ==============

    @Test
    public void tr33_MintermOrderIrrelevant() {
        GroupByResult forward = new GroupByExecutor<Row>().execute(
                spec(clause(false), Collections.emptyList()), source(overlaps()), GROUP_COLS, AGG_COLS);
        GroupByResult backward = new GroupByExecutor<Row>().execute(
                spec(clause(true), Collections.emptyList()), source(overlaps()), GROUP_COLS, AGG_COLS);

        Assert.assertEquals(forward.fingerprint(), backward.fingerprint());
        Assert.assertTrue(GroupByResult.deepEquals(forward, backward));
        Assert.assertEquals(5L, backward.totalRowCount);
    }
}
