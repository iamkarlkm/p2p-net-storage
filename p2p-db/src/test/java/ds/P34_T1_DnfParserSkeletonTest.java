package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * P34 T1：DnfClause + ConditionNode + parseOr 骨架 4 TR。
 *
 * <p>TR1.1 DnfClause 8 IAE + 不可变：构造参数合法性（null/empty/超 1024）+ minterms 不可变
 * <br>TR1.2 ConditionNode 8 IAE：kind=null/FT空/ORM字段错/BETWEEN 校验
 * <br>TR1.3 parseOr 6 参基本形态：空 DSL/无 OR 走 LEGACY_COMPAT/单 FT
 * <br>TR1.4 maxDnfSize ≤ 0 IAE + 256 硬上限
 */
public class P34_T1_DnfParserSkeletonTest {

    /* -------- TR1.1 DnfClause 8 IAE + 不可变 -------- */
    @Test
    public void tr11_dnfClause_immutableAndIae() {
        // 正常构造
        ConditionNode a = ConditionNode.ft("hello");
        ConditionNode b = ConditionNode.orm("col1", DslToken.OP_EQ, 100L);
        DnfClause d = new DnfClause(Arrays.asList(Arrays.asList(a, b)));
        Assert.assertEquals(1, d.size());
        Assert.assertEquals(2, d.getMiniTerm(0).size());
        // 不可变
        try {
            d.minterms.clear();
            Assert.fail("minterms 不可变");
        } catch (UnsupportedOperationException ignore) {
            // 期望
        }
        try {
            d.getMiniTerm(0).clear();
            Assert.fail("minterm 不可变");
        } catch (UnsupportedOperationException ignore) {
            // 期望
        }
        // IAE 1: null
        try { new DnfClause(null); Assert.fail("null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 2: empty
        try { new DnfClause(Collections.emptyList()); Assert.fail("empty IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 3: minterm null
        List<List<ConditionNode>> mtNull = new ArrayList<>();
        mtNull.add(null);
        try { new DnfClause(mtNull); Assert.fail("mt null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 4: minterm empty
        try { new DnfClause(Arrays.asList(Collections.emptyList())); Assert.fail("mt empty IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 5: condition null
        List<ConditionNode> badMt = new ArrayList<>();
        badMt.add(null);
        try { new DnfClause(Arrays.asList(badMt)); Assert.fail("cond null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 6: maxSize 越界
        try { d.getMiniTerm(99); Assert.fail("getMiniTerm IOOBE"); } catch (IndexOutOfBoundsException ignore) {}
        // IAE 7: minterm 长度 > 1024
        List<ConditionNode> tooLong = new ArrayList<>();
        for (int i = 0; i < 1025; i++) tooLong.add(ConditionNode.orm("c" + i, DslToken.OP_EQ, i));
        try { new DnfClause(Arrays.asList(tooLong)); Assert.fail(">1024 IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 8: isAcceptable 上限
        Assert.assertTrue(d.isAcceptable(1));
        Assert.assertFalse(d.isAcceptable(0));
    }

    /* -------- TR1.2 ConditionNode 8 IAE -------- */
    @Test
    public void tr12_conditionNodeIae() {
        // 正常
        ConditionNode ft = ConditionNode.ft("q1");
        Assert.assertEquals(ConditionNode.Kind.FT, ft.kind);
        Assert.assertEquals("q1", ft.ftQuery);
        Assert.assertNull(ft.ormCol);
        ConditionNode orm = ConditionNode.orm("col1", DslToken.OP_EQ, 42L);
        Assert.assertEquals(ConditionNode.Kind.ORM, orm.kind);
        Assert.assertNull(orm.ftQuery);
        ConditionNode btw = ConditionNode.between("age", 10L, 20L);
        Assert.assertEquals(DslToken.KW_BETWEEN, btw.ormOp);
        // IAE 1: kind null
        try { new ConditionNode(null, "x", null, null, null, null); Assert.fail("kind null"); } catch (IllegalArgumentException ignore) {}
        // IAE 2: FT ftQuery null/blank
        try { new ConditionNode(ConditionNode.Kind.FT, null, null, null, null, null); Assert.fail("ft null"); } catch (IllegalArgumentException ignore) {}
        try { new ConditionNode(ConditionNode.Kind.FT, "  ", null, null, null, null); Assert.fail("ft blank"); } catch (IllegalArgumentException ignore) {}
        // IAE 3: FT 不允许 orm 字段
        try { new ConditionNode(ConditionNode.Kind.FT, "x", "c", DslToken.OP_EQ, 1, null); Assert.fail("ft orm col"); } catch (IllegalArgumentException ignore) {}
        // IAE 4: ORM ftQuery != null
        try { new ConditionNode(ConditionNode.Kind.ORM, "bad", "c", DslToken.OP_EQ, 1, null); Assert.fail("orm ftQuery"); } catch (IllegalArgumentException ignore) {}
        // IAE 5: ORM ormCol null
        try { new ConditionNode(ConditionNode.Kind.ORM, null, null, DslToken.OP_EQ, 1, null); Assert.fail("orm col null"); } catch (IllegalArgumentException ignore) {}
        // IAE 6: ORM ormOp null
        try { new ConditionNode(ConditionNode.Kind.ORM, null, "c", null, 1, null); Assert.fail("orm op null"); } catch (IllegalArgumentException ignore) {}
        // IAE 7: BETWEEN ormValueB null
        try { new ConditionNode(ConditionNode.Kind.ORM, null, "c", DslToken.KW_BETWEEN, 1, null); Assert.fail("btw b null"); } catch (IllegalArgumentException ignore) {}
        // IAE 8: 非 BETWEEN ormValueB != null
        try { new ConditionNode(ConditionNode.Kind.ORM, null, "c", DslToken.OP_EQ, 1, 2); Assert.fail("non-btw b"); } catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR1.3 parseOr 6 参基本形态 -------- */
    @Test
    public void tr13_parseOrBasic() {
        // 单 FT 段无 OR：1 minterm
        DnfClause d1 = DnfParser.parseOr(DsTableAdapter.class, "FT('hello')",
                Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
        Assert.assertEquals(1, d1.size());
        Assert.assertEquals(1, d1.getMiniTerm(0).size());
        Assert.assertEquals(ConditionNode.Kind.FT, d1.getMiniTerm(0).get(0).kind);
        // 单 ORM：无 OR
        DnfClause d2 = DnfParser.parseOr(DsTableAdapter.class, "col1 = 100",
                Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
        Assert.assertEquals(1, d2.size());
        // 简单 OR：2 minterm
        DnfClause d3 = DnfParser.parseOr(DsTableAdapter.class, "a = 1 OR b = 2",
                Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
        Assert.assertEquals(2, d3.size());
        // LEGACY_COMPAT：含 OR 必抛 HybridQueryParseException（P30 字节级）
        try {
            DnfParser.parseOr(DsTableAdapter.class, "a = 1 OR b = 2",
                    Map.of(), Map.of(), 256, DnfReturnMode.LEGACY_COMPAT);
            Assert.fail("LEGACY_COMPAT 应抛 IAE");
        } catch (Exception expected) {
            // 期望 P30 抛 IAE
        }
    }

    /* -------- TR1.4 maxDnfSize 边界 -------- */
    @Test
    public void tr14_maxDnfSizeIae() {
        try {
            DnfParser.parseOr(DsTableAdapter.class, "a = 1", Map.of(), Map.of(), 0, DnfReturnMode.DNF_ONLY);
            Assert.fail("maxDnfSize=0 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            DnfParser.parseOr(DsTableAdapter.class, "a = 1", Map.of(), Map.of(), -1, DnfReturnMode.DNF_ONLY);
            Assert.fail("maxDnfSize=-1 IAE");
        } catch (IllegalArgumentException ignore) {}
        try {
            DnfParser.parseOr(DsTableAdapter.class, "a = 1", Map.of(), Map.of(), 257, DnfReturnMode.DNF_ONLY);
            Assert.fail("maxDnfSize>256 IAE");
        } catch (IllegalArgumentException ignore) {}
        // 入参 null
        try {
            DnfParser.parseOr(null, "a = 1", Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
            Assert.fail("entityClass null IAE");
        } catch (NullPointerException ignore) {}
        try {
            DnfParser.parseOr(DsTableAdapter.class, null, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
            Assert.fail("dsl null NPE");
        } catch (NullPointerException ignore) {}
        try {
            DnfParser.parseOr(DsTableAdapter.class, "a = 1", Map.of(), Map.of(), 256, null);
            Assert.fail("mode null NPE");
        } catch (NullPointerException ignore) {}
    }
}
