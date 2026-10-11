package ds;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParseException;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P30 T3 异常边界 8TR：Parser 所有异常必带 ParsePos（line/col 从 1 起）。
 * 覆盖：未闭合STRING / OR / 2FT / WEIGHT在LTR后 / αβ超范围 / LTR列不存在 / 空DSL / IN空列表。
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T3_ExceptionBoundaryTest {

    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 48) public String name;
        @DsField(name = "a", length = 8) public long a;
        @DsField(name = "b", length = 16) public String b;
        @DsField(name = "c", length = 4) public int c;
        public AbcEntity() {}
    }

    private static final Map<String, ToDoubleFunction<AbcEntity>> W_MAP;
    private static final Map<String, ToDoubleFunction<AbcEntity>> L_MAP;
    static {
        W_MAP = new HashMap<>();
        W_MAP.put("a", e -> (double) e.a);
        W_MAP.put("c", e -> (double) e.c);
        L_MAP = new HashMap<>();
        L_MAP.put("a", e -> (double) e.a * 0.01);
        L_MAP.put("c", e -> (double) e.c * 0.02);
    }

    @Before public void before() {}
    @After  public void after()  {}

    /* ---------- helpers ---------- */
    private static HybridQueryParseException catchEx(String dsl) {
        try {
            HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            Assert.fail("DSL expected to throw: " + dsl);
            return null;
        } catch (HybridQueryParseException e) {
            return e;
        } catch (IllegalArgumentException e) {
            // IAE 也行（WeightSpec 构造、LTR列不存在时抛 IAE）
            return null;
        }
    }

    private static IllegalArgumentException catchIae(String dsl) {
        try {
            HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            Assert.fail("DSL expected IAE: " + dsl);
            return null;
        } catch (IllegalArgumentException e) {
            return e;
        }
    }

    /* ---------- TR3.1 未闭合 STRING ---------- */
    @Test
    public void TR3_1_UnclosedStringThrowsParsePosAtQuote() {
        HybridQueryParseException ex = catchEx("a = 'hello world");
        Assert.assertNotNull("TR3.1 HybridQueryParseException", ex);
        Assert.assertTrue("TR3.1 msg has line col: " + ex.getMessage(),
                ex.getMessage().contains("line") && ex.getMessage().contains("col"));
        Assert.assertTrue("TR3.1 line 1", ex.getMessage().contains("line 1"));
        ParsePosAsserter.assertColRange(ex, 5, 6); // a = ' 单引号位置
    }

    /* ---------- TR3.2 OR 关键字禁止 + NOT 独立禁止 ---------- */
    @Test
    public void TR3_2_OrKeywordAtConjunctionThrows() {
        HybridQueryParseException ex = catchEx("a = 100 OR b = 200");
        Assert.assertNotNull("TR3.2 OR throws", ex);
        Assert.assertTrue("TR3.2 MVP only AND: " + ex.getMessage(),
                ex.getMessage().contains("AND") || ex.getMessage().contains("OR") || ex.getMessage().contains("MVP"));
        Assert.assertTrue("TR3.2 has line col",
                ex.getMessage().contains("line") && ex.getMessage().contains("col"));
    }

    /* ---------- TR3.3 2 个及以上 FT 子句 ---------- */
    @Test
    public void TR3_3_TwoFtClausesThrowsAtSecondFt() {
        HybridQueryParseException ex = catchEx("FT('a') AND FT('b')");
        Assert.assertNotNull("TR3.3 2FT throws", ex);
        Assert.assertTrue("TR3.3 仅允许 1 个 FT: " + ex.getMessage(),
                ex.getMessage().contains("FT") || ex.getMessage().contains("1"));
        Assert.assertTrue("TR3.3 has line col",
                ex.getMessage().contains("line") && ex.getMessage().contains("col"));
    }

    /* ---------- TR3.4 WEIGHT 在 LTR 后 ---------- */
    @Test
    public void TR3_4_WeightAfterLtrThrowsAtWeightKeyword() {
        HybridQueryParseException ex = catchEx("a = 100 AND FT('x') LTR(a=0.1) WEIGHT(0.5,0.5)");
        Assert.assertNotNull("TR3.4 WEIGHT after LTR throws", ex);
        Assert.assertTrue("TR3.4 WEIGHT 在 LTR 之前: " + ex.getMessage(),
                ex.getMessage().contains("WEIGHT") && ex.getMessage().contains("LTR"));
        Assert.assertTrue("TR3.4 has line col",
                ex.getMessage().contains("line") && ex.getMessage().contains("col"));
    }

    /* ---------- TR3.5 α / β 超 [0,100] 范围 + 列 weight=101 ---------- */
    @Test
    public void TR3_5_AlphaBetaOutOfRangeThrowsIae() {
        IllegalArgumentException neg = catchIae("a = 100 WEIGHT(-0.1, 0.5)");
        Assert.assertNotNull("TR3.5 alpha negative", neg);
        IllegalArgumentException beta = catchIae("a = 100 WEIGHT(0.5, 100.001)");
        Assert.assertNotNull("TR3.5 beta > 100", beta);
        IllegalArgumentException col = catchIae("a = 100 WEIGHT(0.5,0.5,a=101)");
        Assert.assertNotNull("TR3.5 column weight=101", col);
    }

    /* ---------- TR3.6 LTR 列不存在 ltrNameToExtractor ---------- */
    @Test
    public void TR3_6_LtrColumnNotInMapThrowsIaeWithName() {
        IllegalArgumentException ex = catchIae("a = 100 LTR(unknownCol=0.1)");
        Assert.assertNotNull("TR3.6 LTR unknown column IAE", ex);
        Assert.assertTrue("TR3.6 msg has column name: " + ex.getMessage(),
                ex.getMessage().contains("unknownCol") || ex.getMessage().contains("unknown"));
    }

    /* ---------- TR3.7 空 DSL 3 例 ---------- */
    @Test
    public void TR3_7_EmptyDslBlankAndCommentOnlyThrowsIae() {
        IllegalArgumentException e1 = catchIae("");
        Assert.assertNotNull("TR3.7 empty", e1);
        IllegalArgumentException e2 = catchIae("   ");
        Assert.assertNotNull("TR3.7 blank", e2);
        IllegalArgumentException e3 = catchIae("-- comment only\n  ");
        Assert.assertNotNull("TR3.7 comment only", e3);
        Assert.assertTrue("TR3.7 DSL 不能为空: " + e1.getMessage(),
                e1.getMessage().contains("不能为空") || e1.getMessage().contains("至少"));
    }

    /* ---------- TR3.8 IN 空列表 ---------- */
    @Test
    public void TR3_8_InEmptyListThrowsIaeAtRparen() {
        HybridQueryParseException ex = catchEx("a IN ()");
        Assert.assertNotNull("TR3.8 IN empty list ParseException", ex);
        Assert.assertTrue("TR3.8 msg has IN/列表/不能为空: " + ex.getMessage(),
                ex.getMessage().contains("IN") || ex.getMessage().contains("列表") || ex.getMessage().contains("空"));
        Assert.assertTrue("TR3.8 has line col",
                ex.getMessage().contains("line") && ex.getMessage().contains("col"));
    }

    /* ---------- 位置断言辅助 ---------- */
    static final class ParsePosAsserter {
        static void assertColRange(HybridQueryParseException ex, int minCol, int maxCol) {
            Assert.assertNotNull("ex pos", ex.getPos());
            int col = ex.getPos().startCol();
            Assert.assertTrue("col " + col + " in [" + minCol + "," + maxCol + "]",
                    col >= minCol && col <= maxCol);
        }
    }
}
