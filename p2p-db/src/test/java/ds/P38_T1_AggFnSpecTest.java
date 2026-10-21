package ds;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P38 T1：AggFn + AggSpec 骨架 5 TR。
 *
 * <p>TR1.1 AggFn 5 值 + code 唯一 + 名称
 * <br>TR1.2 AggFn code 往返（code() ↔ fromCode 全 5 值 + 非法 IAE）
 * <br>TR1.3 AggFn fromName 大小写不敏感 + 4 IAE（fromCode 非法 / fromName null / blank / 非法名）
 * <br>TR1.4 AggSpec 6 IAE
 * <br>TR1.5 AggSpec equals/hashCode/toString
 */
public class P38_T1_AggFnSpecTest {

    /* -------- TR1.1 AggFn 5 值 + code 唯一 -------- */
    @Test
    public void tr11_aggFnFiveValuesUniqueCodes() {
        AggFn[] vs = AggFn.values();
        Assert.assertEquals("5 个聚合函数", 5, vs.length);
        Set<Character> codes = new HashSet<>();
        for (AggFn f : vs) {
            Assert.assertTrue("code 唯一: " + f, codes.add(f.code()));
        }
        Assert.assertEquals("COUNT", AggFn.COUNT.name());
        Assert.assertEquals("SUM", AggFn.SUM.name());
        Assert.assertEquals("AVG", AggFn.AVG.name());
        Assert.assertEquals("MIN", AggFn.MIN.name());
        Assert.assertEquals("MAX", AggFn.MAX.name());
        Assert.assertEquals('C', AggFn.COUNT.code());
        Assert.assertEquals('S', AggFn.SUM.code());
        Assert.assertEquals('A', AggFn.AVG.code());
        Assert.assertEquals('M', AggFn.MIN.code());
        Assert.assertEquals('X', AggFn.MAX.code());
    }

    /* -------- TR1.2 code 往返 5 值全等 -------- */
    @Test
    public void tr12_fromCodeRoundTripAllFive() {
        for (AggFn f : AggFn.values()) {
            Assert.assertEquals("往返 " + f, f, AggFn.fromCode(f.code()));
        }
        // 非法 code
        try {
            AggFn.fromCode('Z');
            Assert.fail("非法 code 应 IAE");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("未知 AggFn code"));
        }
        try {
            AggFn.fromCode('c');
            Assert.fail("小写 code 应 IAE（code 大小写敏感）");
        } catch (IllegalArgumentException expected) {
            // 期望
        }
    }

    /* -------- TR1.3 fromName 大小写不敏感 + 4 IAE -------- */
    @Test
    public void tr13_fromNameCaseInsensitiveAndIae() {
        Assert.assertEquals(AggFn.COUNT, AggFn.fromName("COUNT"));
        Assert.assertEquals(AggFn.COUNT, AggFn.fromName("count"));
        Assert.assertEquals(AggFn.SUM, AggFn.fromName(" Sum "));
        Assert.assertEquals(AggFn.AVG, AggFn.fromName("avg"));
        Assert.assertEquals(AggFn.MIN, AggFn.fromName("Min"));
        Assert.assertEquals(AggFn.MAX, AggFn.fromName("mAx"));
        // IAE 1: null
        try { AggFn.fromName(null); Assert.fail("name null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 2: blank
        try { AggFn.fromName("   "); Assert.fail("name blank IAE"); } catch (IllegalArgumentException ignore) {}
        try { AggFn.fromName(""); Assert.fail("name empty IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 3: 非法名
        try { AggFn.fromName("MEDIAN"); Assert.fail("非法名 IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 4: fromCode 非法（合并断言）
        try { AggFn.fromCode('Q'); Assert.fail("code 非法 IAE"); } catch (IllegalArgumentException ignore) {}
    }

    /* -------- TR1.4 AggSpec 6 IAE -------- */
    @Test
    public void tr14_aggSpecSixIae() {
        AggSpec ok = new AggSpec(AggFn.COUNT, "id");
        Assert.assertEquals(AggFn.COUNT, ok.fn);
        Assert.assertEquals("id", ok.column);
        // IAE 1: fn null
        try { new AggSpec(null, "id"); Assert.fail("fn null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 2: column null
        try { new AggSpec(AggFn.SUM, null); Assert.fail("column null IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 3: column blank
        try { new AggSpec(AggFn.SUM, "  "); Assert.fail("column blank IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 4: column 含空白
        try { new AggSpec(AggFn.SUM, "a b"); Assert.fail("column 含空白 IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 5: column 含非法字符
        try { new AggSpec(AggFn.SUM, "a-b"); Assert.fail("column 非法字符 IAE"); } catch (IllegalArgumentException ignore) {}
        try { new AggSpec(AggFn.SUM, "a.b"); Assert.fail("column 非法字符 IAE"); } catch (IllegalArgumentException ignore) {}
        // IAE 6: 保留字 AGG 冲突
        try { new AggSpec(AggFn.SUM, "AGG"); Assert.fail("AGG 保留字 IAE"); } catch (IllegalArgumentException ignore) {}
        try { new AggSpec(AggFn.SUM, "agg"); Assert.fail("agg 保留字 IAE 大小写不敏感"); } catch (IllegalArgumentException ignore) {}
        // 边界合法：下划线 / 数字结尾
        Assert.assertEquals("c_1", new AggSpec(AggFn.MIN, "c_1").column);
    }

    /* -------- TR1.5 AggSpec equals/hashCode/toString -------- */
    @Test
    public void tr15_aggSpecEqualsHashToString() {
        AggSpec a = new AggSpec(AggFn.COUNT, "id");
        AggSpec b = new AggSpec(AggFn.COUNT, "id");
        AggSpec c = new AggSpec(AggFn.SUM, "id");
        AggSpec d = new AggSpec(AggFn.COUNT, "uid");
        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
        Assert.assertNotEquals(a, c);
        Assert.assertNotEquals(a, d);
        Assert.assertNotEquals(a, null);
        Assert.assertEquals("COUNT(id)", a.toString());
        Assert.assertEquals("MAX(price)", new AggSpec(AggFn.MAX, "price").toString());
    }
}
