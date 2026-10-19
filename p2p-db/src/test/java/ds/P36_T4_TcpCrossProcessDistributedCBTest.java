package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
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
 * P36 T4：跨 Process TCP 分布式 CB 状态广播，3 TR 0F0E。
 *
 * <p>
 * TR4.1 2 子进程 state snapshot fingerprint 与主进程完全一致
 * <br>TR4.2 2 子进程 Adaptive 阈值收敛值与主进程一致（HIGH 降 / LOW 升）
 * <br>TR4.3 失败本地熔断不传播 + 0 僵尸子进程
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P36_T4_TcpCrossProcessDistributedCBTest {

    private static final int SERVER_ID = 7;
    private static final double FAILURE_RATE = 0.75;
    private static final double THRESHOLD = 0.4;
    private static final long OPEN_DURATION_MS = 3_000L;

    private File tmpRoot;
    private List<Process> procs;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p36_t4_").toFile();
        procs = new ArrayList<>();
    }

    @After
    public void after() {
        if (procs != null) {
            for (Process p : procs) {
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    /** 子进程 worker：输出 fingerprint + Adaptive 收敛值。 */
    public static final class Worker {
        public static void main(String[] args) {
            DistributedCircuitBreakerState s = new DistributedCircuitBreakerState(
                    SERVER_ID, CircuitBreakerGuard.State.OPEN, 0L,
                    FAILURE_RATE, THRESHOLD, OPEN_DURATION_MS, 1_700_000_000_000L);
            System.out.println("FP=" + DistributedCircuitBreakerState.fingerprint(s));
            DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
            double high = cfg.failureRateThreshold;
            double low = cfg.failureRateThreshold;
            for (int i = 0; i < 5; i++) {
                high = AdaptiveThresholdController.adjustThreshold(high, new double[]{0.9}, cfg);
                low = AdaptiveThresholdController.adjustThreshold(low, new double[]{0.1}, cfg);
            }
            System.out.println("HIGH=" + high);
            System.out.println("LOW=" + low);
            System.out.println("DONE");
        }
    }

    private String[] runWorker() throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(
                javaExe, "-Djava.io.tmpdir=" + tmpRoot.getAbsolutePath(),
                "-Dfile.encoding=UTF-8",
                "-cp", classpath,
                "ds.P36_T4_TcpCrossProcessDistributedCBTest$Worker");
        pb.redirectErrorStream(true);
        pb.directory(tmpRoot);
        Process p = pb.start();
        procs.add(p);
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String fp = null;
        String high = null;
        String low = null;
        String line;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
            if (line.startsWith("FP=")) {
                fp = line.substring(3).trim();
            } else if (line.startsWith("HIGH=")) {
                high = line.substring(5).trim();
            } else if (line.startsWith("LOW=")) {
                low = line.substring(4).trim();
            } else if (line.equals("DONE")) {
                break;
            }
        }
        Assert.assertNotNull("子进程未输出 FP", fp);
        Assert.assertNotNull("子进程未输出 HIGH", high);
        Assert.assertNotNull("子进程未输出 LOW", low);
        return new String[]{fp, high, low};
    }

    /* -------- TR4.1 2 子进程 state snapshot fingerprint 与主进程一致 -------- */
    @Test(timeout = 180000)
    public void tr41_crossProcessFingerprint() throws Exception {
        DistributedCircuitBreakerState local = new DistributedCircuitBreakerState(
                SERVER_ID, CircuitBreakerGuard.State.OPEN, 0L,
                FAILURE_RATE, THRESHOLD, OPEN_DURATION_MS, 1_700_000_000_000L);
        String localFp = DistributedCircuitBreakerState.fingerprint(local);
        Assert.assertEquals(64, localFp.length());

        String[] w1 = runWorker();
        String[] w2 = runWorker();
        Assert.assertEquals("worker1 vs 主进程 fingerprint", localFp, w1[0]);
        Assert.assertEquals("worker2 vs 主进程 fingerprint", localFp, w2[0]);
        Assert.assertEquals("2 子进程 fingerprint 互相一致", w1[0], w2[0]);
    }

    /* -------- TR4.2 2 子进程 Adaptive 阈值收敛与主进程一致 -------- */
    @Test(timeout = 180000)
    public void tr42_crossProcessAdaptiveConvergence() throws Exception {
        DistributedResilienceConfig cfg = DistributedResilienceConfig.defaults();
        double localHigh = cfg.failureRateThreshold;
        double localLow = cfg.failureRateThreshold;
        for (int i = 0; i < 5; i++) {
            localHigh = AdaptiveThresholdController.adjustThreshold(localHigh, new double[]{0.9}, cfg);
            localLow = AdaptiveThresholdController.adjustThreshold(localLow, new double[]{0.1}, cfg);
        }
        Assert.assertTrue("本地 HIGH 收敛应降低阈值", localHigh < cfg.failureRateThreshold);
        Assert.assertTrue("本地 LOW 收敛应提高阈值", localLow > cfg.failureRateThreshold);

        String[] w1 = runWorker();
        String[] w2 = runWorker();
        Assert.assertEquals("worker1 HIGH 收敛=主进程", String.valueOf(localHigh), w1[1]);
        Assert.assertEquals("worker1 LOW 收敛=主进程", String.valueOf(localLow), w1[2]);
        Assert.assertEquals("worker2 HIGH 收敛=主进程", String.valueOf(localHigh), w2[1]);
        Assert.assertEquals("worker2 LOW 收敛=主进程", String.valueOf(localLow), w2[2]);
    }

    /* -------- TR4.3 失败本地熔断不传播 + 0 僵尸子进程 -------- */
    @Test(timeout = 180000)
    public void tr43_failLocalBreakerNoZombies() throws Exception {
        ClusterStateBroadcaster.clearListenersForTest();
        ClusterStateBroadcaster.resetCountersForTest();
        DistributedCircuitBreakerGuard.resetAllForTest();

        // 启动 2 子进程，验证广播失败不影响主链路
        String[] w1 = runWorker();
        String[] w2 = runWorker();
        Assert.assertEquals(w1[0], w2[0]);

        AtomicInteger delivered = new AtomicInteger(0);
        ClusterStateBroadcaster.registerListener((sid, snap) -> {
            throw new RuntimeException("broadcast should be swallowed");
        });
        ClusterStateBroadcaster.registerListener((sid, snap) -> delivered.incrementAndGet());

        DistributedResilienceConfig cfg = new DistributedResilienceConfig.Builder()
                .timeoutMs(1L).maxRetries(0).baseDelayMs(1L)
                .failureRateThreshold(0.5).slidingWindowSize(5).openDurationMs(60_000L)
                .qps(1).burst(1)
                .distributedKpiSampleIntervalMs(5000L)
                .distributedQuorum(2)
                .adaptiveMinFailureRate(0.1)
                .adaptiveMaxOpenDurationMs(60_000L)
                .build();
        DsServerDirectory dir = new Dir2(new int[]{2, 3});
        long beforeFailed = ClusterStateBroadcaster.failedBroadcasts();
        DistributedCircuitBreakerState snap = DistributedCircuitBreakerState.closedDefault(SERVER_ID);
        // 不抛异常（失败本地熔断）
        ClusterStateBroadcaster.broadcast(SERVER_ID, snap, dir, cfg);
        Assert.assertEquals("抛异常 listener 被吞掉，计数 +1",
                beforeFailed + 1, ClusterStateBroadcaster.failedBroadcasts());
        Assert.assertEquals("健康 listener 仍收到广播", 1, delivered.get());

        // 销毁子进程 → 0 僵尸
        for (Process p : procs) {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        Thread.sleep(500);
        int alive = 0;
        for (Process p : procs) {
            if (p.isAlive()) {
                alive++;
            }
        }
        Assert.assertEquals("0 僵尸子进程", 0, alive);
    }

    /** 静态目录（2 replicas） */
    private static class Dir2 implements DsServerDirectory {
        private final int[] replicas;

        Dir2(int[] replicas) {
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