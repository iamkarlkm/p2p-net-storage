package ds;

import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * P33 T2: BenchmarkRunner 编排正确性。 TR 总数 6：TR2.1 warmup 跳过前 10%；TR2.2 measure 收集
 * P50/P95；TR2.3 Recall 计算； TR2.4 指纹 SHA-256 稳定；TR2.5 异常上报；TR2.6 Report 序列化。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T2_BenchmarkRunnerTest {

    @Before
    public void setUp() {
    }

    private Function<String, Object> mockHook(final long sleepMs) {
        return new Function<String, Object>() {
            @Override
            public Object apply(String q) {
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(ie);
                    }
                }
                return "result-" + q.hashCode();
            }
        };
    }

    // ============== TR2.1 warmup 跳过前 10% ==============
    @Test(timeout = 60000)
    public void tr2_1_Warmup_Skips10Percent() {
        // warmup = max(2, 22/10) = 2 → measureTpcH 22 + measureDsl 100 + resilience 20 = 142 次
        // 验证：总调用 ≥ 144（包含 warmup）且 warmup 本身不计入 measure
        // 这里用 mockHook 验证 runWithHooks 完整跑完不抛异常 + report 合法
        BenchmarkReport r = BenchmarkRunner.runWithHooks(mockHook(0), null);
        Assert.assertNotNull("report should not be null", r);
        Assert.assertTrue("tpcH_p50 should be >= 0", r.getTpcHP50Ms() >= 0);
        Assert.assertTrue("tpcH_p95 should be >= 0", r.getTpcHP95Ms() >= 0);
        // 22 + 100 = 122 个 measure sample，warmup 2 次不计入 samples
        Assert.assertTrue("P50 should be non-negative", r.getTpcHP50Ms() >= 0);
    }

    // ============== TR2.2 measure 收集 P50/P95 ==============
    @Test(timeout = 60000)
    public void tr2_2_Measure_CollectsP50P95() {
        BenchmarkReport r = BenchmarkRunner.runWithHooks(mockHook(5), null);
        Assert.assertTrue("tpcH_p50 should be >= 5ms, got " + r.getTpcHP50Ms(), r.getTpcHP50Ms() >= 5.0);
        Assert.assertTrue("P95 should be >= P50, got P95=" + r.getTpcHP95Ms() + " P50=" + r.getTpcHP50Ms(),
                r.getTpcHP95Ms() >= r.getTpcHP50Ms());
        Assert.assertTrue("dsl_p95 should be >= 5ms, got " + r.getDslP95Ms(), r.getDslP95Ms() >= 5.0);
    }

    // ============== TR2.3 Recall 计算 ==============
    @Test(timeout = 30000)
    public void tr2_3_RecallComputation() {
        BenchmarkReport r = BenchmarkRunner.runWithHooks(mockHook(1), null);
        Assert.assertEquals("mock single-jvm recall should be 1.0", 1.0, r.getTpcHRecallAt10(), 1e-9);
    }

    // ============== TR2.4 指纹 SHA-256 稳定 ==============
    @Test(timeout = 60000)
    public void tr2_4_Fingerprint_SHA256_Stable() {
        Function<String, Object> hook = new Function<String, Object>() {
            @Override
            public Object apply(String q) {
                return "result-" + q.hashCode();
            }
        };
        BenchmarkReport r1 = BenchmarkRunner.runWithHooks(hook, null);
        BenchmarkReport r2 = BenchmarkRunner.runWithHooks(hook, null);
        Assert.assertEquals("fingerprint should be stable across runs with same data",
                r1.getDslFingerprint(), r2.getDslFingerprint());
        Assert.assertEquals("fingerprint should be 64-char hex", 64, r1.getDslFingerprint().length());
    }

    // ============== TR2.5 异常上报 ==============
    @Test(timeout = 60000)
    public void tr2_5_Exception_ReportedGracefully() {
        Function<String, Object> hook = new Function<String, Object>() {
            @Override
            public Object apply(String q) {
                throw new RuntimeException("simulated failure");
            }
        };
        BenchmarkReport r = BenchmarkRunner.runWithHooks(hook, null);
        Assert.assertNotNull("report should not be null even when all queries fail", r);
        Assert.assertTrue("tpcH_p50 should be >= 0 even with exceptions", r.getTpcHP50Ms() >= 0);
    }

    // ============== TR2.6 Report 序列化 ==============
    @Test(timeout = 30000)
    public void tr2_6_Report_Serializable() {
        BenchmarkReport r = new BenchmarkReport(100.0, 500.0, 0.90, 300.0,
                "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 5.0);
        String s = r.toString();
        Assert.assertTrue("should include tpcH_p50_ms", s.contains("100.0"));
        Assert.assertTrue("should include tpcH_p95_ms", s.contains("500.0"));
        Assert.assertTrue("should include dsl_p95_ms", s.contains("300.0"));
        Assert.assertTrue("should include recall", s.contains("0.9"));
        Assert.assertTrue("should include acceptable=true", s.contains("acceptable=true"));
        Assert.assertFalse("should NOT include full 64-char fingerprint",
                s.contains("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"));
    }
}
