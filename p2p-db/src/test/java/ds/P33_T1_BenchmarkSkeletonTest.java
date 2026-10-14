package ds;

import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import com.q3lives.ds.database.fulltext.cluster.benchmark.DslCorpus;
import com.q3lives.ds.database.fulltext.cluster.benchmark.TpcHQuery;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * P33 T1: TpcHQuery + DslCorpus + BenchmarkReport 骨架。 TR 总数 4：TR1.1 TpcHQuery
 * 22 条全在；TR1.2 DslCorpus 100 条全在；TR1.3 BenchmarkReport 不可变；TR1.4 状态可观测。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T1_BenchmarkSkeletonTest {

    @Before
    public void setUp() {
        // no DB 依赖
    }

    // ============== TR1.1 TpcHQuery 22 条全在 ==============
    @Test(timeout = 30000)
    public void tr1_1_TpcHQuery_Has22Queries() {
        Assert.assertEquals("COUNT should be 22", 22, TpcHQuery.COUNT);
        for (int i = 0; i < TpcHQuery.COUNT; i++) {
            String q = TpcHQuery.get(i);
            Assert.assertNotNull("Q" + (i + 1) + " should not be null", q);
            Assert.assertFalse("Q" + (i + 1) + " should not be empty", q.isEmpty());
        }
        String[] all = TpcHQuery.all();
        Assert.assertEquals("all() should return 22 queries", 22, all.length);
        for (String name : new String[]{"Q1", "Q2", "Q3", "Q10", "Q22"}) {
            String q = TpcHQuery.getByName(name);
            Assert.assertNotNull("getByName(" + name + ") should not be null", q);
            Assert.assertEquals("getByName(" + name + ") should match get(" + (Integer.parseInt(name.substring(1)) - 1) + ")",
                    TpcHQuery.get(Integer.parseInt(name.substring(1)) - 1), q);
        }
        // 边界检查
        try {
            TpcHQuery.get(-1);
            Assert.fail("expected IOOBE");
        } catch (IndexOutOfBoundsException expected) {
        }
        try {
            TpcHQuery.get(22);
            Assert.fail("expected IOOBE");
        } catch (IndexOutOfBoundsException expected) {
        }
        try {
            TpcHQuery.getByName("Q23");
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        try {
            TpcHQuery.getByName(null);
            Assert.fail("expected NPE");
        } catch (NullPointerException expected) {
        }
    }

    // ============== TR1.2 DslCorpus 100 条全在 ==============
    @Test(timeout = 30000)
    public void tr1_2_DslCorpus_Has100Queries() {
        Assert.assertEquals("COUNT should be 100", 100, DslCorpus.COUNT);
        List<String> all = DslCorpus.all();
        Assert.assertEquals("all() should return 100 queries", 100, all.size());
        for (int i = 0; i < DslCorpus.COUNT; i++) {
            String q = DslCorpus.get(i);
            Assert.assertNotNull("DslCorpus[" + i + "] should not be null", q);
            Assert.assertFalse("DslCorpus[" + i + "] should not be empty", q.isEmpty());
        }
        // 不可变性
        try {
            all.set(0, "x");
            Assert.fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            /* OK */ }
        // 边界
        try {
            DslCorpus.get(-1);
            Assert.fail("expected IOOBE");
        } catch (IndexOutOfBoundsException expected) {
        }
        try {
            DslCorpus.get(100);
            Assert.fail("expected IOOBE");
        } catch (IndexOutOfBoundsException expected) {
        }
    }

    // ============== TR1.3 BenchmarkReport 不可变 ==============
    @Test(timeout = 30000)
    public void tr1_3_BenchmarkReport_Immutable_And_Validation() {
        // 合法构造
        BenchmarkReport r = new BenchmarkReport(
                100.0, 500.0, 0.90, 300.0,
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 5.0);
        Assert.assertEquals(100.0, r.getTpcHP50Ms(), 1e-9);
        Assert.assertEquals(500.0, r.getTpcHP95Ms(), 1e-9);
        Assert.assertEquals(0.90, r.getTpcHRecallAt10(), 1e-9);
        Assert.assertEquals(300.0, r.getDslP95Ms(), 1e-9);
        Assert.assertEquals(5.0, r.getResilienceOverheadPct(), 1e-9);
        Assert.assertTrue("acceptable for low P50/P95 + high recall", r.isAcceptable());

        // 越界校验
        try {
            new BenchmarkReport(-1, 0, 0, 0, "a", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new BenchmarkReport(0, -1, 0, 0, "a", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new BenchmarkReport(0, 0, 1.5, 0, "a", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new BenchmarkReport(0, 0, 0, -1, "a", 0);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new BenchmarkReport(0, 0, 0, 0, null, 0);
            Assert.fail("expected NPE");
        } catch (NullPointerException expected) {
        }

        // 不可变：final 字段
        for (java.lang.reflect.Field f : BenchmarkReport.class.getDeclaredFields()) {
            Assert.assertTrue("field " + f.getName() + " must be final",
                    Modifier.isFinal(f.getModifiers()));
        }

        // 不可接受（超阈值）
        BenchmarkReport high = new BenchmarkReport(
                1000.0, 5000.0, 0.50, 2000.0,
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 50.0);
        Assert.assertFalse("high values should not be acceptable", high.isAcceptable());
    }

    // ============== TR1.4 状态可观测 + BenchmarkRunner 工具方法 ==============
    @Test(timeout = 30000)
    public void tr1_4_StateObservability_And_Utils() {
        // percentile（nearest-rank：idx = ceil(N*p) - 1）
        double[] sorted = {10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0};
        // N=10, p=0.5 → idx=ceil(5)-1=4 → sorted[4]=50
        Assert.assertEquals(50.0, BenchmarkRunner.percentile(sorted, 0.50), 1e-9);
        // p=0.9 → idx=ceil(9)-1=8 → sorted[8]=90
        Assert.assertEquals(90.0, BenchmarkRunner.percentile(sorted, 0.90), 1e-9);
        // p=1.0 → idx=ceil(10)-1=9 → sorted[9]=100
        Assert.assertEquals(100.0, BenchmarkRunner.percentile(sorted, 1.0), 1e-9);
        // p=0.0 → idx=ceil(0)-1=-1 → clamped 0 → sorted[0]=10
        Assert.assertEquals(10.0, BenchmarkRunner.percentile(sorted, 0.0), 1e-9);
        try {
            BenchmarkRunner.percentile(sorted, 1.5);
            Assert.fail("expected IAE");
        } catch (IllegalArgumentException expected) {
        }
        // 空数组
        Assert.assertEquals(0.0, BenchmarkRunner.percentile(new double[0], 0.5), 1e-9);

        // sha256Hex
        String hash1 = BenchmarkRunner.sha256Hex("hello");
        Assert.assertEquals(64, hash1.length()); // SHA-256 = 32 bytes = 64 hex chars
        String hash2 = BenchmarkRunner.sha256Hex("hello");
        Assert.assertEquals("sha256 should be deterministic", hash1, hash2);
        String hash3 = BenchmarkRunner.sha256Hex("world");
        Assert.assertNotEquals("different inputs should yield different hashes", hash1, hash3);

        // 构造器不可外部 new
        Constructor<?>[] ctors = TpcHQuery.class.getDeclaredConstructors();
        for (Constructor<?> c : ctors) {
            Assert.assertTrue("TpcHQuery ctor must be private", Modifier.isPrivate(c.getModifiers()));
        }
        ctors = DslCorpus.class.getDeclaredConstructors();
        for (Constructor<?> c : ctors) {
            Assert.assertTrue("DslCorpus ctor must be private", Modifier.isPrivate(c.getModifiers()));
        }
        ctors = BenchmarkRunner.class.getDeclaredConstructors();
        for (Constructor<?> c : ctors) {
            Assert.assertTrue("BenchmarkRunner ctor must be private", Modifier.isPrivate(c.getModifiers()));
        }

        // toString 包含关键字段
        BenchmarkReport r = new BenchmarkReport(100.0, 500.0, 0.90, 300.0,
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 5.0);
        String s = r.toString();
        Assert.assertTrue("toString should include tpcH_p50_ms", s.contains("tpcH_p50_ms"));
        Assert.assertTrue("toString should include tpcH_p95_ms", s.contains("tpcH_p95_ms"));
        Assert.assertTrue("toString should include dsl_fingerprint", s.contains("dsl_fingerprint"));
    }
}
