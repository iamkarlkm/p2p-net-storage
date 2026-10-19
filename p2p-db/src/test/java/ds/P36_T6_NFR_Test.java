package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.AdaptiveThresholdController;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.ClusterStateBroadcaster;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerGuard;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedCircuitBreakerState;
import com.q3lives.ds.database.fulltext.cluster.resilience.distributed.DistributedResilienceConfig;

/**
 * P36 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 broadcast P95 ≤ 50ms（50× 扇出广播）
 * <br>TR6.2 5 新类 Javadoc ≥ 10 行
 * <br>TR6.3 CPD 重复 ≤ 1（K-gram 窗口匹配）
 * <br>TR6.4 0 println/printStackTrace + IAE ≥ 8
 * <br>TR6.5 6 场景 20× 100% 触发
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T6_NFR_Test {

    private static final String PKG_DIST = "com/q3lives/ds/database/fulltext/cluster/resilience/distributed";

    @Before
    public void reset() {
        ClusterStateBroadcaster.clearListenersForTest();
        ClusterStateBroadcaster.resetCountersForTest();
        DistributedCircuitBreakerGuard.resetAllForTest();
    }

    // ============== TR6.1 broadcast P95 ≤ 50ms ==============

    @Test(timeout = 120000)
    public void tr6_1_BroadcastP95_Under50ms() throws Exception {
        final int RUNS = 50;
        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1000L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(20).openDurationMs(1000L)
                .qps(100000).burst(100000)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(2)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new DirN(new int[]{2, 3});
        ClusterStateBroadcaster.registerListener((sid, snap) -> { /* no-op */ });
        DistributedCircuitBreakerState snap = DistributedCircuitBreakerState.closedDefault(1);
        long[] samples = new long[RUNS];
        // 预热
        for (int i = 0; i < 5; i++) {
            ClusterStateBroadcaster.broadcast(1, snap, dir, cfg);
        }
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            ClusterStateBroadcaster.broadcast(1, snap, dir, cfg);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        Arrays.sort(samples);
        long p95 = samples[(int) Math.floor(RUNS * 0.95) - 1];
        Assert.assertTrue("broadcast P95 should be <= 50ms, actual=" + p95 + "ms", p95 <= 50);
    }

    // ============== TR6.2 5 新类 Javadoc ≥10 行 ==============

    @Test(timeout = 30000)
    public void tr6_2_FiveNewClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] classes = {
                "DistributedCircuitBreakerState",
                "DistributedCircuitBreakerGuard",
                "AdaptiveThresholdController",
                "ClusterStateBroadcaster",
                "DistributedResilienceConfig"
        };
        Pattern p = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", Pattern.MULTILINE);
        for (String cn : classes) {
            File javaFile = new File(srcRoot, PKG_DIST + "/" + cn + ".java");
            Assert.assertTrue("java source for " + cn + " not found at " + javaFile, javaFile.exists());
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            Matcher m = p.matcher(content);
            int maxLines = 0;
            while (m.find()) {
                int lines = m.group(1).split("\n").length;
                if (lines > maxLines) {
                    maxLines = lines;
                }
            }
            Assert.assertTrue(cn + " Javadoc should have at least 10 lines, got " + maxLines, maxLines >= 10);
        }
    }

    // ============== TR6.3 CPD 重复 ≤ 1 ==============

    @Test(timeout = 30000)
    public void tr6_3_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] classes = {
                "DistributedCircuitBreakerState",
                "DistributedCircuitBreakerGuard",
                "AdaptiveThresholdController",
                "ClusterStateBroadcaster",
                "DistributedResilienceConfig"
        };
        List<String> bodies = new ArrayList<>();
        for (String cn : classes) {
            File javaFile = new File(srcRoot, PKG_DIST + "/" + cn + ".java");
            if (!javaFile.exists()) {
                continue;
            }
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            content = content.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            content = content.replaceAll("import\\s+[^;]+;", "");
            content = content.replaceAll("\\s+", "");
            bodies.add(cn + "::" + content);
        }
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                String a = bodies.get(i);
                String b = bodies.get(j);
                int hitCount = 0;
                int totalChecked = 0;
                for (int s = 0; s + 160 <= a.length() && totalChecked < 5; s += Math.max(1, a.length() / 10)) {
                    if (b.contains(a.substring(s, s + 160))) {
                        hitCount++;
                    }
                    totalChecked++;
                }
                if (hitCount >= 3) {
                    Assert.fail("CPD violation: " + a.substring(0, a.indexOf("::")) + " <-> "
                            + b.substring(0, b.indexOf("::")) + " hits=" + hitCount + "/" + totalChecked);
                }
            }
        }
    }

    // ============== TR6.4 0 println/printStackTrace + IAE ≥ 8 ==============

    @Test(timeout = 30000)
    public void tr6_4_NoStdoutPrint_AtLeast8IAE() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        File pkgDir = new File(srcRoot, PKG_DIST);
        File[] javaFiles = pkgDir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(javaFiles);
        Assert.assertTrue("distributed 子包应有 5 个源文件", javaFiles.length >= 5);
        int iaeCount = 0;
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String codeOnly = content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
            Assert.assertFalse("no println in " + f.getName(), codeOnly.contains("System.out.println"));
            Assert.assertFalse("no printStackTrace in " + f.getName(), codeOnly.contains("printStackTrace"));
            int from = 0;
            while ((from = content.indexOf("throw new IllegalArgumentException", from)) >= 0) {
                iaeCount++;
                from += 1;
            }
        }
        Assert.assertTrue("expected at least 8 IAE, got " + iaeCount, iaeCount >= 8);
    }

    // ============== TR6.5 6 场景 20× 100% 触发 ==============

    @Test(timeout = 120000)
    public void tr6_5_SixScenes_100PercentTrigger() throws Exception {
        final int RUNS = 20;

        // 场景1: DistributedCircuitBreakerState 5 IAE 边界
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            int hit = 0;
            try {
                new DistributedCircuitBreakerState(-1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 1000L, 0L);
            } catch (IllegalArgumentException e) {
                hit++;
            }
            try {
                new DistributedCircuitBreakerState(1, null, 0L, 0.0, 0.5, 1000L, 0L);
            } catch (IllegalArgumentException e) {
                hit++;
            }
            try {
                new DistributedCircuitBreakerState(1, CircuitBreakerGuard.State.CLOSED, 0L, 1.5, 0.5, 1000L, 0L);
            } catch (IllegalArgumentException e) {
                hit++;
            }
            try {
                new DistributedCircuitBreakerState(1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 2.0, 1000L, 0L);
            } catch (IllegalArgumentException e) {
                hit++;
            }
            try {
                new DistributedCircuitBreakerState(1, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 0L, 0L);
            } catch (IllegalArgumentException e) {
                hit++;
            }
            if (hit == 5) {
                s1++;
            }
        }
        Assert.assertEquals("state 5 IAE 场景 100%", RUNS, s1);

        // 场景2: DistributedResilienceConfig 4 IAE 边界
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            int hit = 0;
            long[][] longs = {{500L, 1, 0, 60_000L}, {5000L, 0, 0, 60_000L}, {5000L, 1, 0, 500L}};
            for (long[] v : longs) {
                try {
                    new DistributedResilienceConfig.Builder()
                            .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                            .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                            .qps(1).burst(1)
                            .distributedKpiSampleIntervalMs(v[0])
                            .distributedQuorum((int) v[1])
                            .adaptiveMinFailureRate(0.1)
                            .adaptiveMaxOpenDurationMs(v[3])
                            .build();
                } catch (IllegalArgumentException e) {
                    hit++;
                }
            }
            try {
                new DistributedResilienceConfig.Builder()
                        .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                        .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1L)
                        .qps(1).burst(1)
                        .distributedKpiSampleIntervalMs(5000L)
                        .distributedQuorum(1)
                        .adaptiveMinFailureRate(1.5)
                        .adaptiveMaxOpenDurationMs(60_000L)
                        .build();
            } catch (IllegalArgumentException e) {
                hit++;
            }
            if (hit == 4) {
                s2++;
            }
        }
        Assert.assertEquals("config 4 IAE 场景 100%", RUNS, s2);

        // 场景3: broadcast quorum > replicas 抛 IAE
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                    .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                    .failureRateThreshold(0.5).slidingWindowSize(1).openDurationMs(1000L)
                    .qps(1).burst(1)
                    .distributedKpiSampleIntervalMs(5000L)
                    .distributedQuorum(5)
                    .adaptiveMinFailureRate(0.1)
                    .adaptiveMaxOpenDurationMs(60_000L)
                    .build();
            try {
                ClusterStateBroadcaster.broadcast(1, DistributedCircuitBreakerState.closedDefault(1),
                        new DirN(new int[]{2}), cfg);
            } catch (IllegalArgumentException e) {
                s3++;
            }
        }
        Assert.assertEquals("broadcast quorum IAE 场景 100%", RUNS, s3);

        // 场景4: listener 抛异常被本地熔断吞掉（主链路不传播）
        int s4 = 0;
        for (int i = 0; i < RUNS; i++) {
            ClusterStateBroadcaster.clearListenersForTest();
            ClusterStateBroadcaster.resetCountersForTest();
            ClusterStateBroadcaster.registerListener((sid, snap) -> {
                throw new RuntimeException("boom");
            });
            DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
            long before = ClusterStateBroadcaster.failedBroadcasts();
            try {
                ClusterStateBroadcaster.broadcast(1, DistributedCircuitBreakerState.closedDefault(1),
                        new DirN(new int[]{2}), cfg);
                if (ClusterStateBroadcaster.failedBroadcasts() == before + 1) {
                    s4++;
                }
            } catch (Throwable ignore) {
                // 传播即失败
            }
        }
        Assert.assertEquals("失败本地熔断场景 100%", RUNS, s4);

        // 场景5: Adaptive HIGH/LOW/稳态三路径
        int s5 = 0;
        for (int i = 0; i < RUNS; i++) {
            DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
            double high = cfg.failureRateThreshold;
            double low = cfg.failureRateThreshold;
            double stable = cfg.failureRateThreshold;
            for (int k = 0; k < 5; k++) {
                high = AdaptiveThresholdController.adjustThreshold(high, new double[]{0.9}, cfg);
                low = AdaptiveThresholdController.adjustThreshold(low, new double[]{0.1}, cfg);
                stable = AdaptiveThresholdController.adjustThreshold(stable, new double[]{0.5}, cfg);
            }
            if (high < cfg.failureRateThreshold
                    && low > cfg.failureRateThreshold
                    && Math.abs(stable - cfg.failureRateThreshold) < 1e-9) {
                s5++;
            }
        }
        Assert.assertEquals("Adaptive 三路径场景 100%", RUNS, s5);

        // 场景6: fingerprint 确定性（同输入 64-hex 一致）
        int s6 = 0;
        for (int i = 0; i < RUNS; i++) {
            DistributedCircuitBreakerState a = new DistributedCircuitBreakerState(
                    7, CircuitBreakerGuard.State.OPEN, 0L, 0.75, 0.4, 3000L, 1L);
            DistributedCircuitBreakerState b = new DistributedCircuitBreakerState(
                    7, CircuitBreakerGuard.State.OPEN, 0L, 0.75, 0.4, 3000L, 1L);
            String fa = DistributedCircuitBreakerState.fingerprint(a);
            if (fa.length() == 64 && fa.equals(DistributedCircuitBreakerState.fingerprint(b))) {
                s6++;
            }
        }
        Assert.assertEquals("fingerprint 确定性场景 100%", RUNS, s6);
    }

    /** 静态目录 */
    private static class DirN implements DsServerDirectory {
        private final int[] replicas;

        DirN(int[] replicas) {
            this.replicas = replicas;
        }

        @Override
        public int[] listServerIds() {
            return new int[]{1, 2, 3};
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return new Endpoint("127.0.0.1", 9000 + serverId, 100);
        }

        @Override
        public int[] replicas(int serverId) {
            return replicas;
        }
    }
}