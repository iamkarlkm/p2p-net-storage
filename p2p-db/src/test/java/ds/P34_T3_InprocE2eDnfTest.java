package ds;

import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * P34 T3：Inproc E2E 3 TR。
 *
 * <p>
 * TR3.1 5 场景：纯 OR / OR+AND / 括号嵌套 / FT+ORM 混合 / maxDnfSize 控制
 * <br>TR3.2 DnfClause 深等：fingerprint / deepEquals 跨调用稳定
 * <br>TR3.3 与 P30 parse 共存：DNF_ONLY 解析 vs P30 parse 互不干扰
 */
public class P34_T3_InprocE2eDnfTest {

    private static DnfClause parseDnf(String dsl) {
        return DnfParser.parseOr(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
    }

    /* -------- TR3.1 5 场景 E2E -------- */
    @Test
    public void tr31_fiveScenarios() {
        // 场景 1: 纯 OR-only
        DnfClause s1 = parseDnf("a = 1 OR b = 2");
        Assert.assertEquals(2, s1.size());
        // 场景 2: OR+AND 混合
        DnfClause s2 = parseDnf("(a = 1 OR b = 2) AND c = 3");
        Assert.assertEquals(2, s2.size());
        // 场景 3: 括号嵌套多层
        DnfClause s3 = parseDnf("a = 1 AND (b = 2 OR (c = 3 AND d = 4))");
        // (b OR (c AND d)) = 2 minterm; a AND (...) = 2 minterm
        Assert.assertEquals(2, s3.size());
        // 场景 4: FT + ORM 混合
        DnfClause s4 = parseDnf("FT('hello') AND a = 1 OR b = 2");
        // AND > OR: (FT('hello') AND a = 1) OR b = 2
        Assert.assertEquals(2, s4.size());
        // 场景 5: maxDnfSize 控制
        DnfClause s5 = DnfParser.parseOr(DsTableAdapter.class,
                "(a = 1 OR b = 2) AND (c = 3 OR d = 4) AND (e = 5 OR f = 6)",
                Map.of(), Map.of(), 8, DnfReturnMode.DNF_ONLY);
        Assert.assertEquals(8, s5.size());
    }

    /* -------- TR3.2 DnfClause 指纹稳定 + deepEquals -------- */
    @Test
    public void tr32_fingerprintStable() {
        String dsl = "(a = 1 OR b = 2) AND (c = 3 OR d = 4)";
        DnfClause d1 = parseDnf(dsl);
        DnfClause d2 = parseDnf(dsl);
        DnfClause d3 = parseDnf(" ( a = 1 OR b = 2 ) AND ( c = 3 OR d = 4 ) ");
        // fingerprint 跨多次解析稳定
        Assert.assertEquals(d1.fingerprint(), d2.fingerprint());
        Assert.assertEquals(d1.fingerprint(), d3.fingerprint());
        // deepEquals 跨调用稳定
        Assert.assertTrue(DnfClause.deepEquals(d1, d2));
        Assert.assertTrue(DnfClause.deepEquals(d1, d3));
        // 64 hex chars
        Assert.assertEquals(64, d1.fingerprint().length());
        // 不同 DSL 不同指纹
        DnfClause d4 = parseDnf("a = 1 OR b = 2");
        Assert.assertNotEquals(d1.fingerprint(), d4.fingerprint());
    }

    /* -------- TR3.3 与 P30 parse 共存 -------- */
    @Test
    public void tr33_coexistsWithP30Parse() {
        // P34 DNF_ONLY：OR 解析成功
        DnfClause dnf = parseDnf("a = 1 OR b = 2");
        Assert.assertEquals(2, dnf.size());
        // P30 HybridQueryParser.parse + OR → 抛 IAE（行为字节级保持）
        try {
            com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser.parse(
                    DsTableAdapter.class, "a = 1 OR b = 2", Map.of(), Map.of());
            Assert.fail("P30 parse OR 应抛 IAE");
        } catch (Exception expected) {
            // 期望：HybridQueryParseException（"MVP v1.0 仅支持 AND"）
        }
        // P30 parse + 纯 AND：正常
        var p30 = com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser.parse(
                DsTableAdapter.class, "a = 1 AND b = 2", Map.of(), Map.of());
        Assert.assertNotNull(p30);
        Assert.assertEquals(2, p30.ormCond.criteria().size());
        // P34 parseOr 纯 AND：1 minterm
        DnfClause dnf2 = parseDnf("a = 1 AND b = 2");
        Assert.assertEquals(1, dnf2.size());
        Assert.assertEquals(2, dnf2.getMiniTerm(0).size());
    }
}
