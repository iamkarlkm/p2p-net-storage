package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask;

/**
 * P38 T2：MintermAggTask + AggQueryPlan 6 TR。
 *
 * <p>TR2.1 MintermAggTask 5 IAE + 不可变
 * <br>TR2.2 tasks() 笛卡尔积 N + 顺序（minterm 序 × agg 声明序）
 * <br>TR2.3 taskCount() = minterms × aggs
 * <br>TR2.4 fingerprint 64-hex + 跨调用稳定
 * <br>TR2.5 deepEquals
 * <br>TR2.6 aggs 不可变 + AggQueryPlan 5 IAE
 */
public class P38_T2_TaskPlanTest {

    private static DnfClause clause2() {
        // minterm0 = [a=1], minterm1 = [b=2]
        return new DnfClause(Arrays.asList(
                Arrays.asList(ConditionNode.orm("a", DslToken.OP_EQ, 1L)),
                Arrays.asList(ConditionNode.orm("b", DslToken.OP_EQ, 2L))));
    }

    /* -------- TR2.1 MintermAggTask 5 IAE -------- */
    @Test
    public void tr21_mintermTaskFiveIae() {
        List<ConditionNode> mt = Arrays.asList(ConditionNode.orm("a", DslToken.OP_EQ, 1L));
        MintermAggTask ok = new MintermAggTask(0, mt, new AggSpec(AggFn.COUNT, "id"));
        Assert.assertEquals(0, ok.mintermIndex);
        Assert.assertEquals(1, ok.minterm.size());
        Assert.assertEquals(AggFn.COUNT, ok.aggSpec.fn);
        // 不可变
        try { ok.minterm.clear(); Assert.fail("minterm 不可变"); }
        catch (UnsupportedOperationException ignore) {}
        // IAE 1: index < 0
        try { new MintermAggTask(-1, mt, new AggSpec(AggFn.COUNT, "id")); Assert.fail("index<0 IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 2: minterm null
        try { new MintermAggTask(0, null, new AggSpec(AggFn.COUNT, "id")); Assert.fail("minterm null IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 3: minterm empty
        try { new MintermAggTask(0, new ArrayList<>(), new AggSpec(AggFn.COUNT, "id")); Assert.fail("minterm empty IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 4: minterm 含 null
        List<ConditionNode> bad = new ArrayList<>();
        bad.add(null);
        try { new MintermAggTask(0, bad, new AggSpec(AggFn.COUNT, "id")); Assert.fail("minterm null 节点 IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 5: aggSpec null
        try { new MintermAggTask(0, mt, null); Assert.fail("aggSpec null IAE"); }
        catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR2.2 tasks() 笛卡尔积 N + 顺序 -------- */
    @Test
    public void tr22_tasksCartesianOrder() {
        List<AggSpec> aggs = Arrays.asList(
                new AggSpec(AggFn.COUNT, "id"),
                new AggSpec(AggFn.SUM, "price"));
        AggQueryPlan plan = new AggQueryPlan(clause2(), aggs);
        List<MintermAggTask> tasks = plan.tasks();
        Assert.assertEquals("N = 2 × 2 = 4", 4, tasks.size());
        // 顺序：minterm 序 × agg 声明序
        Assert.assertEquals(0, tasks.get(0).mintermIndex);
        Assert.assertEquals(AggFn.COUNT, tasks.get(0).aggSpec.fn);
        Assert.assertEquals(0, tasks.get(1).mintermIndex);
        Assert.assertEquals(AggFn.SUM, tasks.get(1).aggSpec.fn);
        Assert.assertEquals(1, tasks.get(2).mintermIndex);
        Assert.assertEquals(AggFn.COUNT, tasks.get(2).aggSpec.fn);
        Assert.assertEquals(1, tasks.get(3).mintermIndex);
        Assert.assertEquals(AggFn.SUM, tasks.get(3).aggSpec.fn);
        // minterm 节点与 clause 对应
        Assert.assertEquals("a", tasks.get(0).minterm.get(0).ormCol);
        Assert.assertEquals("b", tasks.get(2).minterm.get(0).ormCol);
        // tasks() 不可变
        try { tasks.clear(); Assert.fail("tasks 不可变"); }
        catch (UnsupportedOperationException ignore) {}
    }

    /* -------- TR2.3 taskCount() -------- */
    @Test
    public void tr23_taskCount() {
        AggQueryPlan p0 = new AggQueryPlan(clause2(), new ArrayList<>());
        Assert.assertEquals("无 agg → 0", 0, p0.taskCount());
        Assert.assertTrue("无 agg tasks 空", p0.tasks().isEmpty());
        AggQueryPlan p1 = new AggQueryPlan(clause2(), Arrays.asList(new AggSpec(AggFn.MIN, "c")));
        Assert.assertEquals("2 × 1 = 2", 2, p1.taskCount());
        AggQueryPlan p2 = new AggQueryPlan(clause2(), Arrays.asList(
                new AggSpec(AggFn.MIN, "c"), new AggSpec(AggFn.MAX, "d"), new AggSpec(AggFn.AVG, "e")));
        Assert.assertEquals("2 × 3 = 6", 6, p2.taskCount());
        Assert.assertEquals(p2.taskCount(), p2.tasks().size());
    }

    /* -------- TR2.4 fingerprint 64-hex + 稳定 -------- */
    @Test
    public void tr24_fingerprintStable64Hex() {
        List<AggSpec> aggs = Arrays.asList(new AggSpec(AggFn.COUNT, "id"));
        AggQueryPlan p = new AggQueryPlan(clause2(), aggs);
        String fp = p.fingerprint();
        Assert.assertEquals("64-char hex", 64, fp.length());
        Assert.assertTrue("hex", fp.matches("[0-9a-f]{64}"));
        for (int i = 0; i < 10; i++) {
            Assert.assertEquals("跨调用稳定", fp, p.fingerprint());
        }
        // 同 clause + 同 aggs 的新实例 fingerprint 一致
        AggQueryPlan p2 = new AggQueryPlan(clause2(), Arrays.asList(new AggSpec(AggFn.COUNT, "id")));
        Assert.assertEquals("同输入 fp 一致", fp, p2.fingerprint());
        // agg 声明序不同 → fp 不同
        AggQueryPlan p3 = new AggQueryPlan(clause2(), Arrays.asList(
                new AggSpec(AggFn.SUM, "id"), new AggSpec(AggFn.COUNT, "id")));
        Assert.assertNotEquals("顺序影响 fp", fp, p3.fingerprint());
    }

    /* -------- TR2.5 deepEquals -------- */
    @Test
    public void tr25_deepEquals() {
        List<AggSpec> aggs = Arrays.asList(new AggSpec(AggFn.COUNT, "id"), new AggSpec(AggFn.AVG, "p"));
        AggQueryPlan a = new AggQueryPlan(clause2(), aggs);
        AggQueryPlan b = new AggQueryPlan(clause2(), Arrays.asList(
                new AggSpec(AggFn.COUNT, "id"), new AggSpec(AggFn.AVG, "p")));
        Assert.assertTrue("全等", AggQueryPlan.deepEquals(a, b));
        Assert.assertTrue("自反", AggQueryPlan.deepEquals(a, a));
        Assert.assertFalse("null", AggQueryPlan.deepEquals(a, null));
        Assert.assertFalse("null 2", AggQueryPlan.deepEquals(null, a));
        AggQueryPlan c = new AggQueryPlan(clause2(), Arrays.asList(new AggSpec(AggFn.AVG, "p")));
        Assert.assertFalse("agg 数不同", AggQueryPlan.deepEquals(a, c));
        Assert.assertFalse("clause 不同", AggQueryPlan.deepEquals(a,
                new AggQueryPlan(new DnfClause(Arrays.asList(
                        Arrays.asList(ConditionNode.orm("z", DslToken.OP_EQ, 9L)))), aggs)));
    }

    /* -------- TR2.6 aggs 不可变 + AggQueryPlan 5 IAE -------- */
    @Test
    public void tr26_planIaeAndImmutability() {
        List<AggSpec> mutable = new ArrayList<>();
        mutable.add(new AggSpec(AggFn.COUNT, "id"));
        AggQueryPlan p = new AggQueryPlan(clause2(), mutable);
        mutable.add(new AggSpec(AggFn.SUM, "x"));
        Assert.assertEquals("防御性拷贝：外部改动不影响", 1, p.aggs.size());
        try { p.aggs.clear(); Assert.fail("aggs 不可变"); }
        catch (UnsupportedOperationException ignore) {}
        // IAE 1: clause null
        try { new AggQueryPlan(null, mutable); Assert.fail("clause null IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 2: aggs null
        try { new AggQueryPlan(clause2(), null); Assert.fail("aggs null IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 3: aggs 含 null
        List<AggSpec> withNull = new ArrayList<>();
        withNull.add(null);
        try { new AggQueryPlan(clause2(), withNull); Assert.fail("aggs null 元素 IAE"); }
        catch (IllegalArgumentException ignore) {}
        // IAE 4: aggs.size > MAX_AGG(8)
        List<AggSpec> tooMany = new ArrayList<>();
        for (int i = 0; i < 9; i++) tooMany.add(new AggSpec(AggFn.COUNT, "c" + i));
        try { new AggQueryPlan(clause2(), tooMany); Assert.fail(">MAX_AGG IAE"); }
        catch (IllegalArgumentException ignore) {}
        Assert.assertEquals(8, AggQueryPlan.MAX_AGG);
    }
}
