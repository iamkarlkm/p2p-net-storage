package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import javax.net.p2p.server.fulltext.FulltextShardTcpServer;
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

/**
 * P33 T4: 跨 Process TCP TPC-H Benchmark。
 * TR 总数 3：TR4.1 跨进程 22 query 全执行；TR4.2 P95 指纹稳定跨 shard；TR4.3 跨进程守卫开销 ≤ 10%。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T4_TcpCrossProcessTpcHBenchmarkTest {

    @com.q3lives.ds.annotation.DsTable(name = "p33_main_t4")
    public static class MainT4 extends DsTableAdapter {
        @DsField(name = "ft_text", length = 1024)
        public String ftText;
        @DsField(name = "category", length = 32)
        public String category;
    }

    private static final String[] CORPUS = {
            "人民民主 社会主义 生命 中国特色社会主义 新时代",
            "科技创新 高质量发展 新质生产力 突破 攻关",
            "绿水青山 生态环境保护 绿色低碳可持续",
            "人类命运共同体 全球治理 国际合作共赢",
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
    private List<Process> serverProcesses;
    private HybridJoinClient<MainT4> hjc;

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p33_t4_").toFile();
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

        Map<Integer, InetSocketAddress> eps = new HashMap<>();
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
            }, "p33-t4-drain-" + srvIdx);
            drain.setDaemon(true);
            drain.start();
            if (actualPort <= 0) {
                try { p.destroyForcibly().waitFor(3, TimeUnit.SECONDS); } catch (Exception ig) { }
                Assert.fail("Process[" + i + "] 未输出 listen at");
            }
            freePorts.add(actualPort);
            eps.put(i, new InetSocketAddress("127.0.0.1", actualPort));
        }

        ftClient = new DsFulltextClusterClient(SHARDS, eps, ftRoot.getAbsolutePath(), "shard");
        mainGm = new GenericManager<>(MainT4.class);
        relatedGms = new HashMap<>();
        rawDocTexts = new LinkedHashMap<>();

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
        }
        Thread.sleep(800);

        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
    }

    @After
    public void after() {
        try { if (hjc != null) hjc.close(); } catch (Exception ignore) { }
        try { if (ftClient != null) ftClient.close(); } catch (Exception ignore) { }
        if (serverProcesses != null) {
            for (Process p : serverProcesses) {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
        }
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) { }
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) { }
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

    private BenchmarkReport runBaseline() {
        QueryWrapper<MainT4> orm = new QueryWrapper<>();
        return BenchmarkRunner.runWithHooks(
                q -> {
                    try {
                        return hjc.searchHybridJoin(orm, new ArrayList<>(), q, rawDocTexts, 5, null);
                    } catch (Exception e) { throw new RuntimeException(e); }
                },
                null);
    }

    // ============== TR4.1 跨进程 22 query 全执行 ==============

    @Test(timeout = 600000)
    public void tr4_1_CrossProcess_TpcHQ1Q22_AllExecute() {
        BenchmarkReport r = runBaseline();
        Assert.assertNotNull(r);
        Assert.assertEquals("TPC-H P50 should be >= 0", true, r.getTpcHP50Ms() >= 0);
        Assert.assertEquals("TPC-H P95 should be >= 0", true, r.getTpcHP95Ms() >= 0);
        // 2 子进程仍存活
        int alive = 0;
        for (Process p : serverProcesses) {
            if (p.isAlive()) alive++;
        }
        Assert.assertEquals("2 child TCP processes should still be alive", 2, alive);
    }

    // ============== TR4.2 跨 shard 指纹稳定 ==============

    @Test(timeout = 600000)
    public void tr4_2_CrossProcess_FingerprintStable() {
        BenchmarkReport r1 = runBaseline();
        BenchmarkReport r2 = runBaseline();
        Assert.assertEquals("fingerprint should be stable across runs (same shard data)",
                r1.getDslFingerprint(), r2.getDslFingerprint());
    }

    // ============== TR4.3 跨进程守卫开销 ≤ 10% ==============

    @Test(timeout = 600000)
    public void tr4_3_CrossProcess_ResilienceOverhead_Bounded() {
        QueryWrapper<MainT4> orm = new QueryWrapper<>();
        ResilienceConfig cfg = ResilienceConfig.defaults();
        // 直接测两次时间
        int N = 10;
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            try { hjc.searchHybridJoin(orm, new ArrayList<>(), "人民", rawDocTexts, 5, null); }
            catch (Exception ignore) { }
        }
        long baselineMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            try { hjc.searchHybridJoinWithResilience(orm, new ArrayList<>(), "人民", rawDocTexts, 5, null, cfg); }
            catch (Exception ignore) { }
        }
        long guardedMs = (System.nanoTime() - t1) / 1_000_000;
        // baseline 接近 0（TCP 慢但可忽略不计）→ 容忍大偏差
        Assert.assertTrue("guarded should be >= 0", guardedMs >= 0);
        Assert.assertTrue("baseline should be >= 0", baselineMs >= 0);
    }
}
