package ds;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.LtrSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.parser.WeightSpec;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P30 T2 Parser 主 parse + DeepEquals 8 场景（4 TR）：
 *   TR2.1 8 场景手写 Java QueryWrapper + WeightSpec/LtrSpec vs Parser 输出 DeepEquals 全等
 *   TR2.2 WEIGHT 默认回退 alpha=1/beta=0、LTR=null、ftQuery 空串回退
 *   TR2.3 1000 次同 DSL parse hashCode Set.size=1 + DeepEquals 全等
 *   TR2.4 parse 四参数 entityClass/dsl/weightMap/ltrMap 任一 null 立即 NPE
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T2_ParserDeepEqualsTest {

    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "a", length = 8)    public long a;
        @DsField(name = "b", length = 16)   public String b;
        @DsField(name = "c", length = 4)    public int c;
        public AbcEntity() {}
    }

    private static final Map<String, ToDoubleFunction<AbcEntity>> W_MAP;
    private static final Map<String, ToDoubleFunction<AbcEntity>> L_MAP;
    static {
        Map<String, ToDoubleFunction<AbcEntity>> wm = new LinkedHashMap<>();
        wm.put("a", e -> e.a);
        wm.put("c", e -> e.c);
        W_MAP = Collections.unmodifiableMap(wm);
        Map<String, ToDoubleFunction<AbcEntity>> lm = new LinkedHashMap<>();
        lm.put("a", e -> e.a * 0.01);
        lm.put("c", e -> e.c * 0.02);
        L_MAP = Collections.unmodifiableMap(lm);
    }

    @Before public void before() {}
    @After  public void after()  {}

    @Test
    public void TR2_1_EightScenesDeepEqualsTrue() {
        // 场景 ① 仅 EQ + FT
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 100L);
            return new ParsedHybridQuery<>(w, "透气", WeightSpec.DEFAULT, null);
        }, "a = 100 AND FT('透气')");

        // 场景 ② EQ + GT + LT + FT
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 100L);
            w.gt("c", Long.valueOf(0L));
            w.lt("c", Long.valueOf(1000L));
            return new ParsedHybridQuery<>(w, "跑步鞋", WeightSpec.DEFAULT, null);
        }, "a = 100 AND c > 0 AND c < 1000 AND FT('跑步鞋')");

        // 场景 ③ BETWEEN + IN + FT
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.between("a", Long.valueOf(50L), Long.valueOf(150L));
            w.in("c", List.of(Long.valueOf(1L), Long.valueOf(5L), Long.valueOf(10L)));
            return new ParsedHybridQuery<>(w, "java", WeightSpec.DEFAULT, null);
        }, "a BETWEEN 50 AND 150 AND c IN (1,5,10) AND FT('java')");

        // 场景 ④ LIKE + IS_NULL + IS_NOT_NULL + FT
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.like("name", "%透气%");
            w.isNull("b");
            w.isNotNull("a");
            return new ParsedHybridQuery<>(w, "透气跑步鞋", WeightSpec.DEFAULT, null);
        }, "name LIKE '%透气%' AND b IS NULL AND a IS NOT NULL AND FT('透气跑步鞋')");

        // 场景 ⑤ EQ + NE + NOT_IN + FT
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 200L);
            w.ne("c", Long.valueOf(5L));
            w.notIn("c", List.of(Long.valueOf(99L), Long.valueOf(100L)));
            return new ParsedHybridQuery<>(w, "并发", WeightSpec.DEFAULT, null);
        }, "a = 200 AND c != 5 AND c NOT IN (99,100) AND FT('并发')");

        // 场景 ⑥ 全默认无 WEIGHT（alpha=1 beta=0 empty列）
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 300L);
            return new ParsedHybridQuery<>(w, "人", WeightSpec.DEFAULT, null);
        }, "a = 300 AND FT('人')");

        // 场景 ⑦ WEIGHT alpha beta 无列
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.ge("c", Long.valueOf(1L));
            w.le("c", Long.valueOf(30L));
            return new ParsedHybridQuery<>(w, "透气", new WeightSpec(0.6, 0.4, Collections.emptyMap()), null);
        }, "c >= 1 AND c <= 30 AND FT('透气') WEIGHT(0.6,0.4)");

        // 场景 ⑧ WEIGHT(αβ+2列) + LTR(2列)
        assertDeep(() -> {
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 100L);
            w.between("c", Long.valueOf(1L), Long.valueOf(10L));
            Map<String, Double> cols = new LinkedHashMap<>();
            cols.put("a", 0.01);
            cols.put("c", 0.02);
            WeightSpec weightSpec = new WeightSpec(0.3, 0.7, cols);
            List<ToDoubleFunction<AbcEntity>> fns = new ArrayList<>();
            fns.add(L_MAP.get("a"));
            fns.add(L_MAP.get("c"));
            double[] ltrWeights = { 0.5, 0.5 };
            LtrSpec<AbcEntity> ltr = new LtrSpec<>(fns, ltrWeights);
            return new ParsedHybridQuery<>(w, "跑步鞋 透气", weightSpec, ltr);
        }, "a = 100 AND c BETWEEN 1 AND 10 AND FT('跑步鞋 透气')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)");
    }

    @Test
    public void TR2_2_DefaultFallbackAlphaBetaEmptyAndNullLtrAndBlankFtQuery() {
        ParsedHybridQuery<AbcEntity> r = HybridQueryParser.parse(
                AbcEntity.class, "a = 100", W_MAP, L_MAP);
        Assert.assertEquals("ftQuery empty", "", r.ftQuery);
        Assert.assertEquals("alpha default 1", 1.0, r.weightSpec.alpha, 1e-12);
        Assert.assertEquals("beta default 0", 0.0, r.weightSpec.beta, 1e-12);
        Assert.assertTrue("columnWeights empty", r.weightSpec.columnWeights.isEmpty());
        Assert.assertNull("LTR spec null", r.ltrSpec);

        ParsedHybridQuery<AbcEntity> r2 = HybridQueryParser.parse(
                AbcEntity.class, "a = 100 AND FT('x')", W_MAP, L_MAP);
        Assert.assertEquals("WEIGHT no declaration → DEFAULT", 1.0, r2.weightSpec.alpha, 1e-12);
        Assert.assertEquals("WEIGHT beta default 0", 0.0, r2.weightSpec.beta, 1e-12);
    }

    @Test
    public void TR2_3_1000TimesHashCodeSetSize1AndDeepEquals() {
        String dsl = "name LIKE '%鞋%' AND a = 100 AND c BETWEEN 1 AND 10 AND FT('透气 跑步鞋 java 并发')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)";
        Set<Integer> codes = new HashSet<>();
        ParsedHybridQuery<AbcEntity> first = null;
        for (int i = 0; i < 1000; i++) {
            ParsedHybridQuery<AbcEntity> r = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            codes.add(r.hashCode());
            if (first == null) first = r;
            else Assert.assertTrue("deepEquals #" + i, ParsedHybridQuery.deepEquals(first, r));
        }
        Assert.assertEquals("1000 hashCode Set.size=1", 1, codes.size());
        System.gc();
        for (int i = 0; i < 100; i++) {
            ParsedHybridQuery<AbcEntity> r = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            Assert.assertEquals("post-GC hashCode equal", first.hashCode(), r.hashCode());
        }
    }

    @Test
    public void TR2_4_ParseFourArgsAnyNullThrowsNpe() {
        assertThrows(NullPointerException.class, () ->
                HybridQueryParser.parse(null, "a=1", W_MAP, L_MAP), "entityClass null");
        assertThrows(NullPointerException.class, () ->
                HybridQueryParser.parse(AbcEntity.class, null, W_MAP, L_MAP), "dsl null");
        assertThrows(NullPointerException.class, () ->
                HybridQueryParser.parse(AbcEntity.class, "a=1", null, L_MAP), "weightMap null");
        assertThrows(NullPointerException.class, () ->
                HybridQueryParser.parse(AbcEntity.class, "a=1", W_MAP, null), "ltrMap null");
    }

    /* --------- helpers --------- */
    @FunctionalInterface
    interface ExpectedFactory {
        ParsedHybridQuery<AbcEntity> create();
    }
    private void assertDeep(ExpectedFactory expected, String dsl) {
        ParsedHybridQuery<AbcEntity> a = expected.create();
        ParsedHybridQuery<AbcEntity> b = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        Assert.assertTrue("DeepEquals fail for DSL: " + dsl, ParsedHybridQuery.deepEquals(a, b));
    }
}
