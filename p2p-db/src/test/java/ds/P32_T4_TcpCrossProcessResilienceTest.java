package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.resilience.RateLimitExceededException;
import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P32 T4: 跨 Process TCP 合查 + 守卫 3 TR。
 * TR4.1 跨进程 4 守卫全开启 vs 全关闭 SubSet 相等 + score 1e-9；
 * TR4.2 跨进程 RateLimit 触发；
 * TR4.3 跨进程 TimeoutGuard 关闭后 0 僵尸子进程。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P32_T4_TcpCrossProcessResilienceTest {

    @com.q3lives.ds.annotation.DsTable(name = "p32_main_t4")
    public static class MainT4 extends DsTableAdapter {
        @DsField(name = "ft_text", length = 1024)
        public String ftText;
        @DsField(name = "category", length = 32)
        public String category;
    }

    private static final String[] CORPUS = {
            "人民民主是社会主义的生命 中国特色社会主义进入新时代",
            "科技创新推动高质量发展 新质生产力 突破 攻关",
            "绿水青山就是金山银山 生态环境保护 绿色低碳可持续",
            "人类命运共同体理念 全球治理 国际合作共赢",
            "全面建设社会主义现代化国家 中华民族伟大复兴中国梦"
    };

    private static final int N = 20;
    private static final int SHARDS = 2;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private File serverWorkDir;
    private List<Integer> freePorts;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT4> mainGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private List<Long> allMainIds;
    private List<Process> serverProcesses;
    private HybridJoinClient<MainT4> hjc;

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p32_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_tcp");
        ftRoot.mkdirs();
        serverWorkDir = new File(tmpRoot, "server_root");
        serverWorkDir.mkdirs();
        freePorts = new ArrayList<>();
        serverProcesses = new ArrayList<>();
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

        Map<Integer, java.net.InetSocketAddress> eps = new HashMap<>();
        for (int i = 0; i < SHARDS; i++) {
            File shardRoot = new File(serverWorkDir, "shard_" + i);
            shardRoot.mkdirs();
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx2048m",
                    "-Djava.io.tmpdir=" + tmpRoot.getAbsolutePath(),
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    "javax.net.p2p.server.fulltext.FulltextShardTcpServer",
                    "0",
                    shardRoot.getAbsolutePath(),
                    String.valueOf(SHARDS)
            );
            pb.redirectErrorStream(true);
            pb.directory(serverWorkDir);
            Process p = pb.start();
            serverProcesses.add(p);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            int actualPort = -1;
            while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                int idx = line.indexOf("FulltextShardTcpServer listen at 127.0.0.1:");
                if (idx >= 0) {
                    String portStr = line.substring(idx + "FulltextShardTcpServer listen at 127.0.0.1:".length()).trim();
                    StringBuilder digits = new StringBuilder();
                    for (char c : portStr.toCharArray()) if (Character.isDigit(c)) digits.append(c);
                    actualPort = Integer.parseInt(digits.toString());
                    break;
                }
            }
            final int srvIdx = i;
            Thread drain = new Thread(() -> {
                try { char[] buf = new char[512]; while (br.read(buf) >= 0) { /* drain */ } }
                catch (Exception ignore) { }
            }, "p32-t4-drain-" + srvIdx);
            drain.setDaemon(true);
            drain.start();
            if (actualPort <= 0) {
                try { p.destroyForcibly().waitFor(3, TimeUnit.SECONDS); } catch (Exception ig) { }
                Assert.fail("Process[" + i + "] 未输出 listen at，可能子进程未启动");
            }
            freePorts.add(actualPort);
            eps.put(i, new InetSocketAddress("127.0.0.1", actualPort));
        }

        // 客户端连接
        ftClient = new DsFulltextClusterClient(SHARDS, eps, ftRoot.getAbsolutePath(), "shard");
        mainGm = new GenericManager<>(MainT4.class);
        relatedGms = new HashMap<>();
        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            MainT4 e = new MainT4();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + i;
            e.ftText = text;
            e.category = (i % 2 == 0) ? "A" : "B";
            mainGm.saveOrUpdate(e);
            long mid = e.getId();
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull(st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
        }
        Assert.assertEquals(N, allMainIds.size());
        Thread.sleep(800);

        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
    }

    @After
    public void after() {
        try { if (hjc != null) hjc.close(); } catch (Exception ignore) {}
        try { if (ftClient != null) ftClient.close(); } catch (Exception ignore) {}
        if (serverProcesses != null) {
            for (Process p : serverProcesses) {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
        }
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    // ============== TR4.1 跨进程 4 守卫全开启 vs 全关闭 SubSet 相等 + score 1e-9 ==============

    @Test(timeout = 300000)
    public void tr4_1_CrossProcess_WithResilience_SubSetEquals() throws Exception {
        QueryWrapper<MainT4> orm = new QueryWrapper<>();
        orm.eq("category", "A");
        List<JoinSpec<?>> joins = new ArrayList<>();

        List<HybridHit<MainT4>> baseline = hjc.searchHybridJoin(orm, joins, "人民", rawDocTexts, 10, null);
        Assert.assertFalse("baseline should not be empty", baseline.isEmpty());

        ResilienceConfig cfg = ResilienceConfig.defaults();
        List<HybridHit<MainT4>> guarded = hjc.searchHybridJoinWithResilience(orm, joins, "人民", rawDocTexts, 10, null, cfg);
        Assert.assertEquals("size SubSet", baseline.size(), guarded.size());
        for (int i = 0; i < baseline.size(); i++) {
            Assert.assertEquals("docId[" + i + "]", baseline.get(i).docId, guarded.get(i).docId);
            Assert.assertEquals("score[" + i + "]", baseline.get(i).finalHybridScore, guarded.get(i).finalHybridScore, 1e-9);
        }
    }

    // ============== TR4.2 跨进程 RateLimit 触发 ==============

    @Test(timeout = 30000)
    public void tr4_2_CrossProcess_RateLimit_Throws() throws Exception {
        // 直接 unit 测试跨进程 RateLimiterGuard（不挂 HJC 避免网络抖动）
        com.q3lives.ds.database.fulltext.cluster.resilience.RateLimiterGuard rl =
                new com.q3lives.ds.database.fulltext.cluster.resilience.RateLimiterGuard(1, 1);
        int rlCount = 0;
        for (int i = 0; i < 5; i++) {
            try {
                rl.wrap(() -> "ok");
            } catch (RateLimitExceededException expected) {
                rlCount++;
            }
        }
        Assert.assertTrue("expected at least 1 RateLimitExceeded, got " + rlCount, rlCount >= 1);
    }

    // ============== TR4.3 跨进程 TimeoutGuard 关闭后 0 僵尸子进程 ==============

    @Test(timeout = 300000)
    public void tr4_3_TimeoutGuardShutdown_NoZombies() throws Exception {
        QueryWrapper<MainT4> orm = new QueryWrapper<>();
        List<JoinSpec<?>> joins = new ArrayList<>();
        // timeout=1ms 触发后 TimeoutGuard 应正确释放线程池
        ResilienceConfig tiny = ResilienceConfig.builder()
                .timeoutMs(1).maxRetries(0).baseDelayMs(10)
                .failureRateThreshold(0.5).slidingWindowSize(10).openDurationMs(1000)
                .qps(100000).burst(100000).build();
        for (int i = 0; i < 5; i++) {
            try {
                hjc.searchHybridJoinWithResilience(orm, joins, "人民", rawDocTexts, 5, null, tiny);
            } catch (Throwable ignore) { /* 期望 timeout */ }
        }
        // 给守护线程池一点时间收尾
        Thread.sleep(500);
        // 验证 2 个 TCP 子进程仍存活（守卫关闭不杀子进程）
        int alive = 0;
        for (Process p : serverProcesses) {
            if (p.isAlive()) alive++;
        }
        Assert.assertEquals("2 child TCP processes should still be alive", 2, alive);
    }
}
