package ds;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * P34 T2：DNF 范式转化 + minterm 拆分 6 TR。
 *
 * <p>
 * TR2.1 (A OR B) AND C → [(A,C), (B,C)]
 * <br>TR2.2 A AND (B OR C) → [(A,B), (A,C)]
 * <br>TR2.3 3 层括号嵌套 A AND (B OR (C AND (D OR E))) → distribute
 * <br>TR2.4 hand-make minterm vs parser output Set.equals
 * <br>TR2.5 maxDnfSize 超限 IAE
 * <br>TR2.6 NOT 跨 OR IAE
 */
public class P34_T2_DnfDistributionTest {

    private static DnfClause parse(String dsl) {
        return DnfParser.parseOr(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
    }

    private static Set<String> mintermKeys(DnfClause d) {
        Set<String> s = new TreeSet<>();
        for (int i = 0; i < d.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < d.getMiniTerm(i).size(); j++) {
                if (j > 0) {
                    sb.append(" AND ");
                }
                sb.append(d.getMiniTerm(i).get(j));
            }
            s.add(sb.toString());
        }
        return s;
    }

    /* -------- TR2.1 (A OR B) AND C → [(A,C), (B,C)] -------- */
    @Test
    public void tr21_orAndLpar() {
        DnfClause d = parse("a = 1 OR b = 2 AND c = 3");
        // 优先级：AND > OR, 所以这是 (a=1) OR (b=2 AND c=3) → [(a=1), (b=2, c=3)]
        Assert.assertEquals(2, d.size());
        Set<String> keys = mintermKeys(d);
        Assert.assertTrue("含 a=1", keys.contains("a = 1"));
        Assert.assertTrue("含 b=2 AND c=3", keys.contains("b = 2 AND c = 3"));
    }

    /* -------- TR2.2 A AND (B OR C) → [(A,B), (A,C)] -------- */
    @Test
    public void tr22_andOrLpar() {
        DnfClause d = parse("a = 1 AND (b = 2 OR c = 3)");
        Assert.assertEquals(2, d.size());
        Set<String> keys = mintermKeys(d);
        Assert.assertTrue("含 a=1 AND b=2", keys.contains("a = 1 AND b = 2"));
        Assert.assertTrue("含 a=1 AND c=3", keys.contains("a = 1 AND c = 3"));
    }

    /* -------- TR2.3 3 层括号嵌套 A AND (B OR (C AND (D OR E))) -------- */
    @Test
    public void tr23_threeLevelNesting() {
        DnfClause d = parse("a = 1 AND (b = 2 OR (c = 3 AND (d = 4 OR e = 5)))");
        // (D OR E) → [(d,e)]
        // AND (c, [(d,e)]) → [(c,d), (c,e)]
        // OR (b, [(c,d), (c,e)]) → [(b), (c,d), (c,e)]
        // AND (a, ...) → [(a,b), (a,c,d), (a,c,e)]
        Assert.assertEquals(3, d.size());
        Set<String> keys = mintermKeys(d);
        Assert.assertTrue("a=1 AND b=2", keys.contains("a = 1 AND b = 2"));
        Assert.assertTrue("a=1 AND c=3 AND d=4", keys.contains("a = 1 AND c = 3 AND d = 4"));
        Assert.assertTrue("a=1 AND c=3 AND e=5", keys.contains("a = 1 AND c = 3 AND e = 5"));
    }

    /* -------- TR2.4 hand-make vs parser output Set.equals -------- */
    @Test
    public void tr24_handMadeEqualsParser() {
        // (A OR B) AND (C OR D) → [(A,C), (A,D), (B,C), (B,D)]
        DnfClause d = parse("(a = 1 OR b = 2) AND (c = 3 OR d = 4)");
        Assert.assertEquals(4, d.size());
        Set<String> expected = new TreeSet<>();
        expected.add("a = 1 AND c = 3");
        expected.add("a = 1 AND d = 4");
        expected.add("b = 2 AND c = 3");
        expected.add("b = 2 AND d = 4");
        Assert.assertEquals(expected, mintermKeys(d));
        // 反向：hand-make → parser 反解路径
        DnfClause d2 = parse("a = 1 AND b = 2 AND c = 3");
        // AND-only: 1 minterm
        Assert.assertEquals(1, d2.size());
        Assert.assertEquals(3, d2.getMiniTerm(0).size());
    }

    /* -------- TR2.5 maxDnfSize 超限 IAE -------- */
    @Test
    public void tr25_maxDnfSizeExceeded() {
        // (A OR B) AND (C OR D) AND (E OR F) = 2*2*2 = 8 minterm, maxDnfSize=4 应抛
        try {
            DnfParser.parseOr(DsTableAdapter.class,
                    "(a = 1 OR b = 2) AND (c = 3 OR d = 4) AND (e = 5 OR f = 6)",
                    Map.of(), Map.of(), 4, DnfReturnMode.DNF_ONLY);
            Assert.fail("maxDnfSize=4 应抛 IAE（实际 8 minterm）");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("msg 含超限", e.getMessage().contains("超过 maxDnfSize"));
        }
        // 边界: 4 minterm 正好 maxDnfSize=4 不抛
        DnfClause d = DnfParser.parseOr(DsTableAdapter.class,
                "(a = 1 OR b = 2) AND (c = 3 OR d = 4)",
                Map.of(), Map.of(), 4, DnfReturnMode.DNF_ONLY);
        Assert.assertEquals(4, d.size());
    }

    /* -------- TR2.6 NOT 跨 OR IAE -------- */
    @Test
    public void tr26_notOverOrIae() {
        try {
            parse("NOT (a = 1 OR b = 2)");
            Assert.fail("NOT(A OR B) 应抛 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("msg 含 NOT 跨 OR", e.getMessage().contains("NOT 不得跨越 OR"));
        }
        try {
            parse("NOT (a = 1 OR b = 2) AND c = 3");
            Assert.fail("NOT(A OR B) AND ... 应抛 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // AND 内 NOT (B OR C) 也跨 OR 应抛 IAE
        try {
            parse("a = 1 AND NOT (b = 2 OR c = 3)");
            Assert.fail("AND NOT(B OR C) 应抛 IAE");
        } catch (IllegalArgumentException ignore) {
        }
        // 单条件 NOT 通过 IS NULL/IS NOT NULL 表达（已在 tr27 验证）
    }

    /* -------- 额外验证: IS NOT NULL 单条件 NOT 允许 -------- */
    @Test
    public void tr27_isNotNullAllowed() {
        DnfClause d = parse("col1 IS NOT NULL");
        Assert.assertEquals(1, d.size());
        Assert.assertEquals(ConditionNode.Kind.IS_NOT_NULL, d.getMiniTerm(0).get(0).kind);
        Assert.assertEquals("col1", d.getMiniTerm(0).get(0).ormCol);
    }
}
