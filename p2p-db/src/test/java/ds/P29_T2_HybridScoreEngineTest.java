package ds;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine;

/**
 * P29 T2 HybridScoreEngine 实现验证（4 TR）：
 *   TR2.1 linearScore 手算全等式样 0.6*0.5 + 0.4*(0.001*100 + 0.02*5) = 0.3+0.08=0.38 1e-9 全等
 *   TR2.2 ltrLinearScore 点积手算全等 + 长度不匹配 IAE
 *   TR2.3 extractColumnFeatures 顺序严格对齐 extractors 顺序
 *   TR2.4 α=1.0 β=0.0 任意 weights => linearScore 恒等于 ftScore（50 组）
 */
public class P29_T2_HybridScoreEngineTest {

    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 96)
        public String name;
        @DsField(name = "a", length = 8)
        public long a;
        @DsField(name = "b", length = 16)
        public String b;
        @DsField(name = "c", length = 4)
        public int c;
        public AbcEntity() {}
    }

    @Before
    public void before() {}

    @After
    public void after() {}

    /**
     * TR2.1：ftScore=0.5, alpha=0.6, entity.a=100, entity.c=5,
     *   columnWeights = {"a": (0.001, e->e.a), "c":(0.02, e->e.c)}，beta=0.4
     *   手算：0.6 * 0.5 = 0.3；列部分 sum = 0.001*100 + 0.02*5 = 0.1+0.1 = 0.2；
     *         beta * sum = 0.4 * 0.2 = 0.08；total = 0.3+0.08 = 0.38
     */
    @Test
    public void TR2_1_LinearScoreHandCalculationExact() {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        e.a = 100L;
        e.c = 5;
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        cw.put("a", new AbstractMap.SimpleEntry<>(0.001, x -> x.a));
        cw.put("c", new AbstractMap.SimpleEntry<>(0.02, x -> x.c));
        double actual = HybridScoreEngine.linearScore(0.5, 0.6, e, 0.4, cw);
        double expected = 0.38d;
        Assert.assertEquals("TR2.1 线性融合手算全等式样 0.38", expected, actual, 1e-12);

        try {
            HybridScoreEngine.linearScore(Double.NaN, 0.0, e, 0.0, Collections.emptyMap());
            Assert.fail("expect IAE ftScore NaN");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg finite", iae.getMessage().contains("finite"));
        }
        try {
            HybridScoreEngine.linearScore(Double.POSITIVE_INFINITY, 0.0, e, 0.0, Collections.emptyMap());
            Assert.fail("expect IAE ftScore Infinite");
        } catch (IllegalArgumentException iae) { /* ok */ }
        try {
            HybridScoreEngine.linearScore(0.5, -1.0, e, 0.0, Collections.emptyMap());
            Assert.fail("expect IAE alpha<0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains alpha", iae.getMessage().contains("alpha"));
        }
        try {
            HybridScoreEngine.linearScore(0.5, 0.0, null, 0.0, Collections.emptyMap());
            Assert.fail("expect NPE entity null");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            HybridScoreEngine.linearScore(0.5, 0.0, e, 0.0, null);
            Assert.fail("expect NPE columnWeights null");
        } catch (NullPointerException npe) { /* ok */ }
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> badCw = new HashMap<>();
        badCw.put("  ", new AbstractMap.SimpleEntry<>(1.0, x -> 0.0));
        try {
            HybridScoreEngine.linearScore(0.5, 0.0, e, 0.0, badCw);
            Assert.fail("expect IAE blank key");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains key blank", iae.getMessage().contains("key"));
        }
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> badCw2 = new HashMap<>();
        badCw2.put("bad", new AbstractMap.SimpleEntry<>(-1.0, x -> 0.0));
        try {
            HybridScoreEngine.linearScore(0.5, 0.0, e, 0.0, badCw2);
            Assert.fail("expect IAE weight<0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains weight", iae.getMessage().contains("weight"));
        }
    }

    @Test
    public void TR2_2_LtrLinearScoreDotProduct() {
        double[] f = new double[]{0.5, 0.3, 0.2};
        double[] w = new double[]{1.0, 2.0, 3.0};
        double expected = 0.5*1.0 + 0.3*2.0 + 0.2*3.0; // 0.5 + 0.6 + 0.6 = 1.7
        double actual = HybridScoreEngine.ltrLinearScore(f, w);
        Assert.assertEquals("TR2.2 dot product = 1.7", 1.7d, actual, 1e-12);
        Assert.assertEquals("TR2.2 手算 exact 1.7", expected, actual, 1e-12);

        try {
            HybridScoreEngine.ltrLinearScore(new double[]{0.5, 0.3, 0.2}, new double[]{1.0, 2.0});
            Assert.fail("expect IAE length mismatch");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg length", iae.getMessage().contains("length"));
        }
        try {
            HybridScoreEngine.ltrLinearScore(new double[0], new double[]{1.0});
            Assert.fail("expect IAE len=0");
        } catch (IllegalArgumentException iae) { /* ok */ }
        try {
            HybridScoreEngine.ltrLinearScore(null, new double[]{1.0});
            Assert.fail("expect NPE f null");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            HybridScoreEngine.ltrLinearScore(new double[]{1.0}, null);
            Assert.fail("expect NPE w null");
        } catch (NullPointerException npe) { /* ok */ }
        double[] bad = new double[]{Double.NaN, 1.0};
        try {
            HybridScoreEngine.ltrLinearScore(bad, new double[]{1.0, 2.0});
            Assert.fail("expect IAE NaN");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg non-finite", iae.getMessage().contains("non-finite"));
        }
    }

    @Test
    public void TR2_3_ExtractColumnFeaturesOrderStrict() {
        AbcEntity e = new AbcEntity();
        e.setId(7L);
        e.a = 100L;
        e.c = 7;
        List<ToDoubleFunction<AbcEntity>> extractors = new ArrayList<>();
        extractors.add(x -> x.a);            // #0 -> 100
        extractors.add(x -> x.c);            // #1 -> 7
        extractors.add(x -> x.a * 1.0d);     // #2 -> 100
        double[] v = HybridScoreEngine.extractColumnFeatures(e, extractors);
        double[] expected = new double[]{100.0d, 7.0d, 100.0d};
        Assert.assertArrayEquals("TR2.3 顺序严格对齐 extractors 顺序", expected, v, 1e-12);

        try {
            HybridScoreEngine.extractColumnFeatures(null, extractors);
            Assert.fail("expect NPE entity null");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            HybridScoreEngine.extractColumnFeatures(e, null);
            Assert.fail("expect NPE list null");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            HybridScoreEngine.extractColumnFeatures(e, Collections.emptyList());
            Assert.fail("expect IAE list empty");
        } catch (IllegalArgumentException iae) { /* ok */ }
        List<ToDoubleFunction<AbcEntity>> withNull = Arrays.asList(x -> x.a, null);
        try {
            HybridScoreEngine.extractColumnFeatures(e, withNull);
            Assert.fail("expect NPE extractor[1] null");
        } catch (NullPointerException npe) {
            Assert.assertTrue("msg index 1", npe.getMessage().contains("1"));
        }
    }

    /**
     * TR2.4：α=1.0 β=0.0 任意 weights => linearScore == ftScore（50 组 1e-9）
     *         α=0.0 β=1.0 纯列分 20 组手算 1e-9 全等
     */
    @Test
    public void TR2_4_Alpha1Beta0IdentityAndAlpha0Beta1() {
        for (int i = 0; i < 50; i++) {
            double ftScore = 0.01 * i;
            AbcEntity e = new AbcEntity();
            e.setId(1000L + i);
            e.a = 100L + i;
            e.c = 1 + i;
            Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
            cw.put("a", new AbstractMap.SimpleEntry<>(0.01 * i, x -> x.a));
            cw.put("c", new AbstractMap.SimpleEntry<>(0.02 * i, x -> x.c));
            double s = HybridScoreEngine.linearScore(ftScore, 1.0, e, 0.0, cw);
            Assert.assertEquals("TR2.4 α=1.0 β=0.0 组" + i + " 恒等式 ftScore", ftScore, s, 1e-12);
        }
        for (int i = 0; i < 20; i++) {
            double ftScore = 0.5d + i;
            AbcEntity e = new AbcEntity();
            e.setId(2000L + i);
            e.a = 10L * (i + 1); // 10,20,30,...
            e.c = i + 1;
            Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
            double wa = 0.1;
            double wc = 1.0;
            cw.put("a", new AbstractMap.SimpleEntry<>(wa, x -> x.a));
            cw.put("c", new AbstractMap.SimpleEntry<>(wc, x -> x.c));
            double expected = 0.0 * ftScore + 1.0 * (wa * e.a + wc * e.c);
            double s = HybridScoreEngine.linearScore(ftScore, 0.0, e, 1.0, cw);
            Assert.assertEquals("TR2.4 α=0 β=1 纯列分组" + i, expected, s, 1e-12);
        }
    }
}
