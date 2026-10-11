package ds;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer.TokenEntry;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParseException;
import com.q3lives.ds.database.fulltext.cluster.parser.LtrSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsePos;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.parser.WeightSpec;

/**
 * P30 T1 骨架连通 + Tokenizer 测试（4 TR）：
 *   TR1.1 7 生产类子包路径 + ZeroLeak3=3（根包 top-level count=3）
 *   TR1.2 DslTokenizer 9 Token + 12 关键字 精确扫描 31 Token 顺序
 *   TR1.3 ParsePos 行列号 + HybridQueryParseException 未闭合 STRING 位置
 *   TR1.4 WeightSpec αβ/列 weight 范围 IAE + LtrSpec 长度 IAE + 不可变 UOE
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T1_TokenizerAndSkeletonTest {

    @Before public void before() {}
    @After  public void after()  {}

    @Test
    public void TR1_1_SevenClassesInParserSubpackageAndZeroLeak3Equals3() throws Exception {
        Class<?>[] expected = new Class<?>[] {
                DslToken.class, ParsePos.class, HybridQueryParseException.class,
                WeightSpec.class, LtrSpec.class, ParsedHybridQuery.class, DslTokenizer.class
        };
        for (Class<?> c : expected) {
            String pn = c.getPackageName();
            Assert.assertTrue(c.getSimpleName() + " must in parser subpackage, actual=" + pn,
                    pn.endsWith(".fulltext.cluster.parser"));
            int mod = c.getModifiers();
            if (c != HybridQueryParseException.class) {
                Assert.assertTrue(c.getSimpleName() + " must be final/enum",
                        Modifier.isFinal(mod) || c.isEnum() || c.isRecord());
            }
        }
        Path root = Path.of("src/main/java/com/q3lives/ds/database/fulltext");
        long topLevel;
        try (Stream<Path> s = Files.list(root)) {
            topLevel = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !Files.isDirectory(p)).count();
        }
        Assert.assertEquals("ZeroLeak3=3 top-level in fulltext root", 3L, topLevel);
    }

    @Test
    public void TR1_2_Tokenizer9Types12KeywordsExactScanOrder() {
        String dsl = "-- header comment \n a != 123 AND b IN (1,'x')"
                + " FT('hello\\'world') WEIGHT(0.6,0.4,a=0.5) LTR(a=0.1)";
        DslTokenizer tz = new DslTokenizer(dsl);
        List<TokenEntry> tokens = new ArrayList<>();
        for (TokenEntry e; (e = tz.nextToken()) != null; ) tokens.add(e);
        Object[] expected = new Object[] {
                DslToken.IDENT, "a",
                DslToken.OP_NE, "!=",
                DslToken.NUMBER, 123.0,
                DslToken.KW_AND, "AND",
                DslToken.IDENT, "b",
                DslToken.KW_IN, "IN",
                DslToken.LPAREN, "(",
                DslToken.NUMBER, 1.0,
                DslToken.COMMA, ",",
                DslToken.STRING, "x",
                DslToken.RPAREN, ")",
                DslToken.KW_FT, "FT",
                DslToken.LPAREN, "(",
                DslToken.STRING, "hello'world",
                DslToken.RPAREN, ")",
                DslToken.KW_WEIGHT, "WEIGHT",
                DslToken.LPAREN, "(",
                DslToken.NUMBER, 0.6,
                DslToken.COMMA, ",",
                DslToken.NUMBER, 0.4,
                DslToken.COMMA, ",",
                DslToken.IDENT, "a",
                DslToken.OP_EQ, "=",
                DslToken.NUMBER, 0.5,
                DslToken.RPAREN, ")",
                DslToken.KW_LTR, "LTR",
                DslToken.LPAREN, "(",
                DslToken.IDENT, "a",
                DslToken.OP_EQ, "=",
                DslToken.NUMBER, 0.1,
                DslToken.RPAREN, ")"
        };
        Assert.assertEquals("31 tokens", 31, tokens.size());
        for (int i = 0; i < 31; i++) {
            TokenEntry t = tokens.get(i);
            Assert.assertSame("token#" + i + " type", expected[i * 2], t.type);
            Object ev = expected[i * 2 + 1];
            if (ev instanceof Double de) {
                Assert.assertEquals("token#" + i + " value", de.doubleValue(), ((Number) t.value).doubleValue(), 1e-12);
            } else {
                Assert.assertEquals("token#" + i + " value", ev, t.value);
            }
        }
    }

    @Test
    public void TR1_3_ParsePosLineColAndUnclosedStringPosition() {
        String dsl = "a = 'unclosed";
        try {
            DslTokenizer tz = new DslTokenizer(dsl);
            while (tz.nextToken() != null) { }
            Assert.fail("expect HybridQueryParseException for unclosed STRING");
        } catch (HybridQueryParseException e) {
            ParsePos p = e.getPos();
            Assert.assertEquals("startLine=1", 1, p.startLine());
            Assert.assertEquals("startCol=5 (quote at col 5 for 'a = '|)", 5, p.startCol());
            Assert.assertTrue("msg contains 'line 1, col 5': " + e.getMessage(),
                    e.getMessage().contains("line 1, col 5"));
            Assert.assertTrue("fragment contains substring",
                    e.getDslFragment() != null && !e.getDslFragment().isBlank());
        }
    }

    @Test
    public void TR1_4_WeightSpecLtrSpecRangeIAEAndImmutableUOE() {
        assertThrows(IllegalArgumentException.class, () -> new WeightSpec(-0.1, 0, Collections.emptyMap()),
                "alpha<0");
        assertThrows(IllegalArgumentException.class, () -> new WeightSpec(1, 100.001, Collections.emptyMap()),
                "beta>100");
        assertThrows(IllegalArgumentException.class, () -> new WeightSpec(0.6, 0.4, Map.of("a", 101.0)),
                "weight>100");
        WeightSpec ws = new WeightSpec(0.6, 0.4, Map.of("a", 0.5));
        assertThrows(UnsupportedOperationException.class, () -> ws.columnWeights.put("x", 0.1),
                "columnWeights immutable UOE");
        List<ToDoubleFunction<Object>> fns = List.of(x -> 1.0, x -> 2.0);
        assertThrows(IllegalArgumentException.class, () -> new LtrSpec<>(fns, new double[] { 0.1 }),
                "LtrSpec len mismatch");
        LtrSpec<Object> ltr = new LtrSpec<>(fns, new double[] { 0.5, 0.5 });
        assertThrows(UnsupportedOperationException.class, () -> ltr.featureExtractors.add(x -> 3.0),
                "featureExtractors immutable");
        assertThrows(NullPointerException.class, () -> new WeightSpec(0, 0, null),
                "null WeightSpec.columnWeights");
        assertThrows(NullPointerException.class, () -> new LtrSpec<>(null, new double[0]),
                "null LtrSpec extractors");
    }
}
