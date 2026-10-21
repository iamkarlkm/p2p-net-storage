package ds;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask;

/**
 * P38 T3：AggParser 解析 3 TR。
 *
 * <p>TR3.1 AGG 切分防误命中（字符串内 AGG / 括号内 AGG / 无 AGG → aggs 空）
 * <br>TR3.2 5 aggFn 全解析 + 多 agg 声明序 + tasks 数
 * <br>TR3.3 边界 IAE / HybridQueryParseException（空表达式 / 非法函数 / 缺 '(' / 多余 token / maxAggCount 越界 / 非法列 / null 入参）
 */
public class P38_T3_AggParserTest {

    private static AggQueryPlan parse(String dsl, int maxAgg) {
        return AggParser.parse(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, maxAgg);
    }

    /* -------- TR3.1 AGG 切分防误命中 -------- */
    @Test
    public void tr31_splitAtAggNoFalsePositive() {
        // 无 AGG → aggs 空 + taskCount 0
        AggQueryPlan p0 = parse("a = 1 OR b = 2", 8);
        Assert.assertEquals("无 AGG → 2 minterms", 2, p0.clause.size());
        Assert.assertTrue("无 AGG → aggs 空", p0.aggs.isEmpty());
        Assert.assertEquals("无 AGG → taskCount 0", 0, p0.taskCount());
        Assert.assertTrue(p0.tasks().isEmpty());
        // 字符串内 AGG 不被识别
        AggQueryPlan p1 = parse("a = 1 AND FT('AGG(COUNT(x))')", 8);
        Assert.assertTrue("字符串内 AGG 不触发切分", p1.aggs.isEmpty());
        Assert.assertEquals(0, p1.taskCount());
        // 括号深度 > 0 的 AGG 不被识别（作为列名/表达式）
        AggQueryPlan p2 = parse("(a = 1 OR b = 2)", 8);
        Assert.assertTrue("括号内无 top-level AGG", p2.aggs.isEmpty());
        Assert.assertEquals(2, p2.clause.size());
        // 独立 AGG 但后随非 '(' → 普通标识符（列名 agg），不切分
        AggQueryPlan p3 = parse("agg = 1", 8);
        Assert.assertTrue("agg 作列名不切分", p3.aggs.isEmpty());
        Assert.assertEquals(1, p3.clause.size());
        Assert.assertEquals("agg", p3.clause.getMiniTerm(0).get(0).ormCol);
        // 真正 AGG 子句：top-level + 后随 '('
        AggQueryPlan p4 = parse("a = 1 OR b = 2 AGG(COUNT(id))", 8);
        Assert.assertEquals("AGG 被识别", 1, p4.aggs.size());
        Assert.assertEquals(AggFn.COUNT, p4.aggs.get(0).fn);
        Assert.assertEquals("id", p4.aggs.get(0).column);
    }

    /* -------- TR3.2 5 aggFn + 声明序 + tasks 数 -------- */
    @Test
    public void tr32_fiveAggFnAndOrder() {
        AggQueryPlan p = parse("a = 1 OR b = 2 AGG(COUNT(id), SUM(price), AVG(score), MIN(c), MAX(d))", 8);
        List<AggSpec> aggs = p.aggs;
        Assert.assertEquals(5, aggs.size());
        Assert.assertEquals(AggFn.COUNT, aggs.get(0).fn);
        Assert.assertEquals("id", aggs.get(0).column);
        Assert.assertEquals(AggFn.SUM, aggs.get(1).fn);
        Assert.assertEquals(AggFn.AVG, aggs.get(2).fn);
        Assert.assertEquals(AggFn.MIN, aggs.get(3).fn);
        Assert.assertEquals(AggFn.MAX, aggs.get(4).fn);
        Assert.assertEquals("声明序保持", "MAX(d)", aggs.get(4).toString());
        Assert.assertEquals("N = 2 × 5 = 10", 10, p.taskCount());
        List<MintermAggTask> tasks = p.tasks();
        Assert.assertEquals(10, tasks.size());
        // 顺序：minterm0 × 5 aggs, 再 minterm1 × 5 aggs
        for (int i = 0; i < 5; i++) {
            Assert.assertEquals(0, tasks.get(i).mintermIndex);
            Assert.assertEquals(aggs.get(i), tasks.get(i).aggSpec);
        }
        for (int i = 5; i < 10; i++) {
            Assert.assertEquals(1, tasks.get(i).mintermIndex);
            Assert.assertEquals(aggs.get(i - 5), tasks.get(i).aggSpec);
        }
        // 小写函数名亦可
        AggQueryPlan p2 = parse("a = 1 AGG(count(id), sum(x))", 8);
        Assert.assertEquals(AggFn.COUNT, p2.aggs.get(0).fn);
        Assert.assertEquals(AggFn.SUM, p2.aggs.get(1).fn);
    }

    /* -------- TR3.3 边界 IAE / HybridQueryParseException -------- */
    @Test
    public void tr33_boundaryErrors() {
        // null 入参 → IAE（spec §4.5：entityClass/dsl → IAE）
        try {
            AggParser.parse(null, "a = 1", Map.of(), Map.of(), 256, 8);
            Assert.fail("entityClass null IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            AggParser.parse(DsTableAdapter.class, null, Map.of(), Map.of(), 256, 8);
            Assert.fail("dsl null IAE");
        } catch (IllegalArgumentException ignore) {}
        // maxAggCount 越界
        try { parse("a = 1", 0); Assert.fail("maxAggCount=0 IAE"); }
        catch (IllegalArgumentException ignore) {}
        try { parse("a = 1", 9); Assert.fail("maxAggCount>8 IAE"); }
        catch (IllegalArgumentException ignore) {}
        // 空表达式段（只有 AGG）
        try { parse("AGG(COUNT(id))", 8); Assert.fail("空表达式段 IAE"); }
        catch (IllegalArgumentException ignore) {}
        try { parse("   AGG(COUNT(id))", 8); Assert.fail("空白表达式段 IAE"); }
        catch (IllegalArgumentException ignore) {}
        // 非法函数名 → IAE（AggFn.fromName 抛）
        try { parse("a = 1 AGG(MEDIAN(id))", 8); Assert.fail("非法函数名 IAE"); }
        catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("未知 AggFn name"));
        }
        // 缺 '('
        try { parse("a = 1 AGG COUNT(id)", 8); Assert.fail("多 AGG 无括号语法非法"); }
        catch (RuntimeException ignore) {}
        // 括号不匹配
        try { parse("a = 1 AGG(COUNT(id)", 8); Assert.fail("括号不匹配"); }
        catch (RuntimeException ignore) {}
        // 多余 token（AGG 后残留）
        try { parse("a = 1 AGG(COUNT(id)) AND b = 2", 8); Assert.fail("AGG 后多余 token"); }
        catch (RuntimeException e) {
            Assert.assertTrue("多余 token 消息", e.getMessage().contains("多余 token"));
        }
        // 非法列（含非法字符 → AggSpec IAE）
        try { parse("a = 1 AGG(COUNT(a-b))", 8); Assert.fail("非法列"); }
        catch (RuntimeException ignore) {}
        // maxAggCount 小于实际聚合项数
        try { parse("a = 1 AGG(COUNT(id), SUM(x))", 1); Assert.fail("聚合项超 maxAggCount IAE"); }
        catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("maxAggCount"));
        }
        // 合法：AGG 前为括号表达式
        AggQueryPlan ok = parse("(a = 1 OR b = 2) AGG(COUNT(id))", 8);
        Assert.assertEquals(2, ok.clause.size());
        Assert.assertEquals(1, ok.aggs.size());
        Assert.assertEquals(Arrays.asList(0, 1),
                Arrays.asList(ok.tasks().get(0).mintermIndex, ok.tasks().get(1).mintermIndex));
    }
}
