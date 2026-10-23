package ds;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P39 T1：AggValue + AggResult 骨架 5 TR 0F0E。
 *
 * <p>
 * TR1.1 AggValue 4 IAE（spec null / count&lt;0 / sum NaN·Inf / value NaN·Inf）
 * <br>TR1.2 AggValue count/sum/value 语义 + equals/hashCode/toString（{@code "COUNT(price)=12.0"}）
 * <br>TR1.3 AggResult 3 IAE（values null / 含 null / unionRowCount&lt;0）+ values 不可变
 * <br>TR1.4 AggResult size / value(int) / 越界 IOOBE / 顺序 = aggs 声明序
 * <br>TR1.5 AggResult fingerprint() 64-hex 稳定 + deepEquals
 */
public class P39_T1_AggValueResultTest {

    private static AggSpec spec(AggFn fn, String col) {
        return new AggSpec(fn, col);
    }

    // ============== TR1.1 AggValue 4 IAE ==============

    @Test
    public void tr11_AggValueFourIae() {
        AggSpec s = spec(AggFn.COUNT, "price");
        expectIae(() -> new AggValue(null, 1, 1.0, 1.0), "spec null");
        expectIae(() -> new AggValue(s, -1, 0.0, 0.0), "count<0");
        expectIae(() -> new AggValue(s, 1, Double.NaN, 1.0), "sum NaN");
        expectIae(() -> new AggValue(s, 1, Double.POSITIVE_INFINITY, 1.0), "sum Inf");
        expectIae(() -> new AggValue(s, 1, 0.0, Double.NaN), "value NaN");
        expectIae(() -> new AggValue(s, 1, 0.0, Double.NEGATIVE_INFINITY), "value Inf");
        // 边界合法：count=0
        AggValue zero = new AggValue(s, 0, 0.0, 0.0);
        Assert.assertEquals(0L, zero.count);
    }

    // ============== TR1.2 AggValue 语义 + equals/hashCode/toString ==============

    @Test
    public void tr12_AggValueSemanticsAndEquality() {
        AggSpec sumSpec = spec(AggFn.SUM, "price");
        AggValue a = new AggValue(sumSpec, 3, 12.0, 12.0);
        AggValue b = new AggValue(spec(AggFn.SUM, "price"), 3, 12.0, 12.0);
        Assert.assertEquals(3L, a.count);
        Assert.assertEquals(12.0, a.sum, 0.0);
        Assert.assertEquals(12.0, a.value, 0.0);

        Assert.assertEquals(a, b);
        Assert.assertEquals(a.hashCode(), b.hashCode());
        Assert.assertNotEquals(a, new AggValue(sumSpec, 3, 12.0, 13.0));
        Assert.assertNotEquals(a, new AggValue(spec(AggFn.COUNT, "price"), 3, 12.0, 12.0));
        Assert.assertNotEquals(a, null);
        Assert.assertNotEquals(a, "notAggValue");

        // toString: "COUNT(price)=12.0"
        Assert.assertEquals("COUNT(price)=12.0", new AggValue(spec(AggFn.COUNT, "price"), 12, 0.0, 12.0).toString());
        Assert.assertEquals("SUM(price)=12.0", a.toString());
    }

    // ============== TR1.3 AggResult 3 IAE + 不可变 ==============

    @Test
    public void tr13_AggResultThreeIaeAndUnmodifiable() {
        expectIae(() -> new AggResult(null, 0), "values null");
        expectIae(() -> new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 1, 0.0, 1.0), null), 1), "values 含 null");
        expectIae(() -> new AggResult(new ArrayList<>(), -1), "unionRowCount<0");

        AggResult r = new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 1, 0.0, 1.0)), 1);
        try {
            r.values.add(new AggValue(spec(AggFn.COUNT, "price"), 1, 0.0, 1.0));
            Assert.fail("values 应为不可变");
        } catch (UnsupportedOperationException expected) {
            // OK
        }
        // 空 values 合法
        Assert.assertEquals(0, new AggResult(new ArrayList<>(), 0).size());
    }

    // ============== TR1.4 size / value(int) / 越界 / 顺序 ==============

    @Test
    public void tr14_AccessorsAndOrder() {
        AggValue c = new AggValue(spec(AggFn.COUNT, "price"), 4, 0.0, 4.0);
        AggValue s = new AggValue(spec(AggFn.SUM, "price"), 4, 40.0, 40.0);
        AggValue a = new AggValue(spec(AggFn.AVG, "price"), 4, 40.0, 10.0);
        AggResult r = new AggResult(Arrays.asList(c, s, a), 4);

        Assert.assertEquals(3, r.size());
        Assert.assertEquals(4L, r.unionRowCount);
        // 顺序 = 声明序
        Assert.assertSame(c, r.value(0));
        Assert.assertSame(s, r.value(1));
        Assert.assertSame(a, r.value(2));

        expectIoobe(() -> r.value(3));
        expectIoobe(() -> r.value(-1));
    }

    // ============== TR1.5 fingerprint 64-hex 稳定 + deepEquals ==============

    @Test
    public void tr15_FingerprintAndDeepEquals() {
        AggResult r1 = new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 4, 0.0, 4.0),
                new AggValue(spec(AggFn.AVG, "price"), 4, 40.0, 10.0)), 4);
        AggResult r2 = new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 4, 0.0, 4.0),
                new AggValue(spec(AggFn.AVG, "price"), 4, 40.0, 10.0)), 4);
        AggResult r3 = new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 4, 0.0, 4.0),
                new AggValue(spec(AggFn.AVG, "price"), 4, 40.0, 10.0)), 5);

        String fp = r1.fingerprint();
        Assert.assertEquals(64, fp.length());
        Assert.assertTrue("需为 64-hex: " + fp, fp.matches("[0-9a-f]{64}"));
        // 稳定 + 跨实例一致
        Assert.assertEquals(fp, r1.fingerprint());
        Assert.assertEquals(fp, r2.fingerprint());

        Assert.assertTrue(AggResult.deepEquals(r1, r2));
        Assert.assertTrue(AggResult.deepEquals(r1, r1));
        Assert.assertFalse(AggResult.deepEquals(r1, r3));
        Assert.assertFalse(AggResult.deepEquals(r1, null));
        Assert.assertFalse(AggResult.deepEquals(null, r1));

        // 不同 value → 不同 fingerprint
        AggResult diff = new AggResult(Arrays.asList(
                new AggValue(spec(AggFn.COUNT, "price"), 5, 0.0, 5.0),
                new AggValue(spec(AggFn.AVG, "price"), 4, 40.0, 10.0)), 4);
        Assert.assertNotEquals(fp, diff.fingerprint());

        // equals 与 deepEquals 对齐
        Assert.assertEquals(r1, r2);
        Assert.assertEquals(r1.hashCode(), r2.hashCode());
        Assert.assertNotEquals(r1, r3);
    }

    /* ================ helpers ================ */

    private static void expectIae(Runnable r, String label) {
        try {
            r.run();
            Assert.fail("期望 IAE: " + label);
        } catch (IllegalArgumentException expected) {
            // OK
        }
    }

    private static void expectIoobe(Runnable r) {
        try {
            r.run();
            Assert.fail("期望 IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {
            // OK
        }
    }
}
