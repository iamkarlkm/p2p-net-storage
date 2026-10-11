package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * P27 T4: 跨 Process 多 JVM loopback 指纹全等（ProcessBuilder 启 FulltextShardTcpServer.main）。
 *
 * Baseline F0: 父 JVM 内 DsFulltextClusterClient(inproc mode, 2 shards, same DOCS corpus)
 *
 * Remote FR: 2 个子 JVM (ProcessBuilder) 独立 rootDir、shardCount=1、子进程 stdout 解析 listen at actualPort
 *   → shardId 0 ↔ Process0 port, shardId1 ↔ Process1 port → 父 JVM DsFulltextClusterClient(TCP mode, N=2)
 *
 * TR4.1: 2 Process 构造 + 解析 listen at 均成功 + TCP Echo 连通
 * TR4.2: 跨 Process TCP 模式 8 docs ingest 均 >0 pairs 搜索人民 hits≥1
 * TR4.3: 跨 Process TCP 模式 搜索人民/英雄纪念碑 fingerprint == F0 inproc baseline fingerprint
 * TR4.4: destroyForcibly → 0 僵尸 → 再启 2 Process 同 DOCS → 指纹全等（幂等）
 */
public class P27_T4_CrossProcessFingerprintTest {

    static final int N = 2;
    static File tmp;
    static File rootBaseline;      // F0 inproc 2-shard
    static File rootProc0;         // Process 0 shardCount=1
    static File rootProc1;         // Process 1 shardCount=1
    static File rootProc0r;        // 幂等 Run 重新 Process 0
    static File rootProc1r;        // 幂等 Run 重新 Process 1

    static Process[] procs = new Process[N];
    static int[] ports = new int[N];
    static Map<Integer, InetSocketAddress> eps;

    static String[] DOCS;
    static long[] DOC_IDS;

    static DsFulltextClusterClient baselineInproc;  // F0 (同 JVM inproc 2 shards)
    static DsFulltextClusterClient remoteTcp;       // 跨 Process TCP 模式 (N=2)

    @BeforeClass
    public static void beforeAll() throws Exception {
        tmp = new File(System.getProperty("java.io.tmpdir"));
        long t = System.nanoTime();
        rootBaseline = new File(tmp, "p27t4_baseline_" + t);
        rootProc0 = new File(tmp, "p27t4_p0_" + t);
        rootProc1 = new File(tmp, "p27t4_p1_" + t);
        rootProc0r = new File(tmp, "p27t4_p0r_" + t);
        rootProc1r = new File(tmp, "p27t4_p1r_" + t);
        for (File r : Arrays.asList(rootBaseline, rootProc0, rootProc1, rootProc0r, rootProc1r))
            if (!r.exists()) r.mkdirs();

        DOCS = new String[] {
                "人民群众是历史的创造者 广场 英雄纪念碑 革命 先烈 工人 农民",
                "人民英雄 永垂不朽 纪念碑 人民广场 天安门 群众 大合唱",
                "中华民族 伟大复兴 中国梦 人民 奋斗 牺牲 奉献 团结 进步 意志",
                "中国特色社会主义 人民当家作主 依法治国 共同富裕 全过程人民民主",
                "人工智能 大模型 深度学习 神经网络 人民生活 智慧服务 创新 科技",
                "科技创新 驱动发展 人民利益 高质量发展 新质生产力 突破 攻关",
                "文化自信 中华文明 五千年 人民创造 传统美德 礼仪 廉耻 四维",
                "绿色发展 生态文明 美丽中国 人民健康 可持续发展 环保 低碳 生态"
        };
        DOC_IDS = new long[DOCS.length];
        for (int i = 0; i < DOCS.length; i++) DOC_IDS[i] = 40001L + i;

        // ---- F0 Baseline inproc 2 shards (same JVM, baseline fingerprint) ----
        baselineInproc = new DsFulltextClusterClient(N, null, rootBaseline.getAbsolutePath(), "bl_shard");
        for (int i = 0; i < DOCS.length; i++) {
            IngestStat st = baselineInproc.putDocCluster(DOC_IDS[i], DOCS[i]);
            Assert.assertTrue("baseline ingest doc#" + i + " pairs>0, got=" + st.pairsTotal, st.pairsTotal > 0);
        }
        TimeUnit.MILLISECONDS.sleep(500);

        // ---- 跨 Process 子 JVM 启动：2 个独立 rootDir，每个 shardCount=1 ----
        String classpath = System.getProperty("java.class.path");
        for (int i = 0; i < N; i++) {
            File rootDir = (i == 0) ? rootProc0 : rootProc1;
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe(),
                    "-Xmx256m",
                    "-Djava.io.tmpdir=" + tmp.getAbsolutePath(),
                    "-cp", classpath,
                    "javax.net.p2p.server.fulltext.FulltextShardTcpServer",
                    "0",
                    rootDir.getAbsolutePath(),
                    String.valueOf(N)
            );
            pb.redirectErrorStream(true);
            pb.directory(tmp);
            Process p = pb.start();
            procs[i] = p;
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            int actualPort = -1;
            StringBuilder sb = new StringBuilder();
            while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                sb.append(line).append('\n');
                int idx = line.indexOf("FulltextShardTcpServer listen at 127.0.0.1:");
                if (idx >= 0) {
                    String portStr = line.substring(idx + "FulltextShardTcpServer listen at 127.0.0.1:".length()).trim();
                    StringBuilder digits = new StringBuilder();
                    for (char c : portStr.toCharArray()) if (Character.isDigit(c)) digits.append(c);
                    actualPort = Integer.parseInt(digits.toString());
                    break;
                }
            }
            // 启动 daemon 线程持续排空子进程剩余 stdout，避免缓冲区满导致子 JVM 阻塞
            Thread drain = new Thread(() -> {
                try {
                    char[] buf = new char[512];
                    while (br.read(buf) >= 0) { /* drain only */ }
                } catch (Exception ignore) { }
            }, "p27t4-drain-" + i);
            drain.setDaemon(true);
            drain.start();
            if (actualPort <= 0) {
                try { p.destroyForcibly().waitFor(3, TimeUnit.SECONDS); } catch (Exception ig) { }
                Assert.fail("Process[" + i + "]未输出 listen at，stdout 摘要: " + sb.substring(0, Math.min(sb.length(), 1200)));
            }
            ports[i] = actualPort;
            Assert.assertTrue("p[" + i + "] port=" + actualPort + " > 0", actualPort > 0);
        }
        eps = new HashMap<>();
        for (int i = 0; i < N; i++) eps.put(i, new InetSocketAddress("127.0.0.1", ports[i]));
        remoteTcp = new DsFulltextClusterClient(N, eps,
                (new File(tmp, "p27t4_remoteClnt_" + t)).getAbsolutePath(), "rmt_shard");
        {
            GrpcShardTransport transport = (GrpcShardTransport) remoteTcp.getRouterForTest().getShardTransportForTest();
            Assert.assertFalse("remoteTcp inproc=false (real TCP cross-process)", transport.isInprocModeForTest());
        }
    }

    @AfterClass
    public static void afterAll() {
        try { if (remoteTcp != null) remoteTcp.close(); } catch (Exception ig) {}
        try { if (baselineInproc != null) baselineInproc.close(); } catch (Exception ig) {}
        for (Process p : procs) {
            if (p != null && p.isAlive()) {
                try {
                    p.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } catch (Exception ig) {}
            }
        }
    }

    // ---- helpers ----
    private static String javaExe() {
        String home = System.getProperty("java.home");
        File bin = new File(new File(home, "bin"), "java");
        String cmd = bin.getAbsolutePath();
        if (!cmd.toLowerCase().endsWith(".exe")) cmd += ".exe";
        return cmd;
    }

    private static String fp(List<ScoredDocHit> hs) {
        StringBuilder sb = new StringBuilder(hs.size() * 80);
        for (ScoredDocHit h : hs) {
            sb.append(h.docId).append('|').append(h.shardId).append('|')
                    .append(String.format("%.12f", h.finalScore)).append('|')
                    .append(String.format("%.12f", h.sortScore)).append('|')
                    .append(String.format("%.12f", h.rrfScore)).append('|')
                    .append(String.format("%.12f", h.phraseBoostScore)).append('|')
                    .append(h.matchedQueryTerms).append('|')
                    .append(String.format("%.12f", h.vectorScore)).append(';');
        }
        return sb.toString();
    }

    // ====================================================================
    // TR4.1: 2 Process 启动 + 解析 listen at 成功 + TCP 连通
    // ====================================================================
    @Test
    public void tr41_twoCrossProcessStart() throws Exception {
        for (int i = 0; i < N; i++) {
            Assert.assertTrue("Process[" + i + "] isAlive", procs[i].isAlive());
            Assert.assertTrue("p[" + i + "] port=" + ports[i] + " > 0", ports[i] > 0);
        }
        Map<Integer, InetSocketAddress> eps2 = new HashMap<>();
        for (int i = 0; i < N; i++) eps2.put(i, new InetSocketAddress("127.0.0.1", ports[i]));
        DsFulltextClusterClient c = new DsFulltextClusterClient(N, eps2,
                (new File(tmp, "p27t4_smoke_" + System.nanoTime())).getAbsolutePath(), "smk_shard");
        try {
            IngestStat s = c.putDocCluster(40100L, "人民万岁 英雄赞歌 广场 集会 群众 游行 奋斗");
            Assert.assertTrue("smoke putDoc pairs>0: " + s.pairsTotal, s.pairsTotal > 0);
            List<ScoredDocHit> hs = c.searchCluster("人民", 10, SearchOptions.defaults());
            Assert.assertTrue("smoke人民 search non-empty, size=" + hs.size(), hs.size() >= 1);
        } finally {
            try { c.close(); } catch (Exception ig) {}
        }
    }

    // ====================================================================
    // TR4.2: 跨 Process TCP 模式 8 docs ingest 均 >0 + 搜索人民 hits≥1
    // ====================================================================
    @Test
    public void tr42_ingestAll8Docs() throws Exception {
        for (int i = 0; i < DOCS.length; i++) {
            IngestStat st = remoteTcp.putDocCluster(DOC_IDS[i], DOCS[i]);
            Assert.assertTrue("remoteTcp ingest doc#" + i + " id=" + DOC_IDS[i] + " pairs>0, got=" + st.pairsTotal,
                    st.pairsTotal > 0);
        }
        TimeUnit.MILLISECONDS.sleep(800);
        List<ScoredDocHit> h = remoteTcp.searchCluster("人民", 30, SearchOptions.defaults());
        Assert.assertNotNull("remoteTcp人民 search not null", h);
        Assert.assertTrue("remoteTcp人民 search size=" + h.size() + " ≥ 1", h.size() >= 1);
        System.out.println("INFO TR4.2 remote人民 hits=" + h.size());
    }

    // ====================================================================
    // TR4.3: 跨 Process TCP 模式 fingerprint === inproc baseline fingerprint
    // ====================================================================
    @Test
    public void tr43_crossProcessFingerprintEq() throws Exception {
        // 若 TR4.2 测试用例在 beforeAll 未 ingest，这里再 ingest 一次以保证 test 独立
        boolean anyZero = false;
        for (int i = 0; i < DOCS.length; i++) {
            IngestStat st = remoteTcp.putDocCluster(DOC_IDS[i], DOCS[i]);
            if (st == null || st.pairsTotal <= 0) anyZero = true;
        }
        TimeUnit.MILLISECONDS.sleep(300);
        // Baseline fingerprint
        List<ScoredDocHit> b0 = baselineInproc.searchCluster("人民", 30, SearchOptions.defaults());
        List<ScoredDocHit> b1 = baselineInproc.searchCluster("英雄纪念碑", 20, SearchOptions.defaults());
        Assert.assertTrue("Baseline人民 size≥1, got=" + b0.size(), b0.size() >= 1);

        // Remote fingerprint
        List<ScoredDocHit> r0 = remoteTcp.searchCluster("人民", 30, SearchOptions.defaults());
        List<ScoredDocHit> r1 = remoteTcp.searchCluster("英雄纪念碑", 20, SearchOptions.defaults());
        Assert.assertNotNull("r0 not null", r0);
        Assert.assertNotNull("r1 not null", r1);

        Assert.assertEquals("inproc baseline vs cross-process人民 size", b0.size(), r0.size());
        Assert.assertEquals("inproc baseline vs cross-process英雄纪念碑 size", b1.size(), r1.size());

        Set<String> setRenmin = new HashSet<>(Arrays.asList(fp(b0), fp(r0)));
        Set<String> setHero   = new HashSet<>(Arrays.asList(fp(b1), fp(r1)));
        Assert.assertEquals("TR4.3 跨 Process人民指纹=inproc baseline (Set.size=1, size=" + b0.size() + ")",
                1, setRenmin.size());
        Assert.assertEquals("TR4.3 跨 Process英雄纪念碑指纹=inproc baseline (Set.size=1, size=" + b1.size() + ")",
                1, setHero.size());
    }

    // ====================================================================
    // TR4.4: destroyForcibly 0 僵尸 + 重新 2 Process 指纹全等 幂等
    // ====================================================================
    @Test
    public void tr44_destroyAndRebuildIdempotent() throws Exception {
        // 先 destroy
        for (int i = 0; i < N; i++) {
            if (procs[i] != null) {
                if (procs[i].isAlive()) procs[i].destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                Assert.assertFalse("Process[" + i + "] destroyed 成功，应 !alive, exit=" + (procs[i].isAlive() ? "stillAlive" : procs[i].exitValue()),
                        procs[i].isAlive());
            }
        }
        TimeUnit.MILLISECONDS.sleep(400);

        // 重新构造 2 个 Process
        Process[] run2 = new Process[N];
        int[] ports2 = new int[N];
        Map<Integer, InetSocketAddress> eps2 = new HashMap<>();
        String cp = System.getProperty("java.class.path");
        for (int i = 0; i < N; i++) {
            File dir = (i == 0) ? rootProc0r : rootProc1r;
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe(), "-Xmx256m",
                    "-Djava.io.tmpdir=" + tmp.getAbsolutePath(),
                    "-cp", cp,
                    "javax.net.p2p.server.fulltext.FulltextShardTcpServer",
                    "0", dir.getAbsolutePath(), String.valueOf(N)
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            run2[i] = p;
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            String line;
            int port = -1;
            while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                int idx = line.indexOf("FulltextShardTcpServer listen at 127.0.0.1:");
                if (idx >= 0) {
                    String ps = line.substring(idx + "FulltextShardTcpServer listen at 127.0.0.1:".length()).trim();
                    StringBuilder digits = new StringBuilder();
                    for (char c : ps.toCharArray()) if (Character.isDigit(c)) digits.append(c);
                    port = Integer.parseInt(digits.toString());
                    break;
                }
            }
            Thread dr = new Thread(() -> {
                try { char[] buf = new char[512]; while (br.read(buf) >= 0) {} } catch (Exception ignore) {}
            }, "p27t4-drainR2-" + i);
            dr.setDaemon(true);
            dr.start();
            Assert.assertTrue("Run2 Process[" + i + "] port parse ok, got=" + port, port > 0);
            ports2[i] = port;
            eps2.put(i, new InetSocketAddress("127.0.0.1", port));
        }

        DsFulltextClusterClient remoteTcp2 = null;
        try {
            remoteTcp2 = new DsFulltextClusterClient(N, eps2,
                    (new File(tmp, "p27t4_remote2_" + System.nanoTime())).getAbsolutePath(), "r2_shard");
            // Ingest 相同 DOC_IDS + DOCS 入 Run2
            for (int i = 0; i < DOCS.length; i++) {
                IngestStat st = remoteTcp2.putDocCluster(DOC_IDS[i], DOCS[i]);
                Assert.assertTrue("Run2 ingest doc#" + i + " pairs>0: " + st.pairsTotal, st.pairsTotal > 0);
            }
            TimeUnit.MILLISECONDS.sleep(500);

            // 指纹 baseline vs remoteTcp2人民/英雄
            List<ScoredDocHit> b0 = baselineInproc.searchCluster("人民", 30, SearchOptions.defaults());
            List<ScoredDocHit> r2 = remoteTcp2.searchCluster("人民", 30, SearchOptions.defaults());
            Assert.assertEquals("Run2 人民 size 等于 baseline, b=" + b0.size() + " r2=" + r2.size(),
                    b0.size(), r2.size());
            Assert.assertEquals("TR4.4 Idempotent人民指纹 baseline===Run2",
                    fp(b0), fp(r2));

            List<ScoredDocHit> b1 = baselineInproc.searchCluster("英雄纪念碑", 20, SearchOptions.defaults());
            List<ScoredDocHit> r21 = remoteTcp2.searchCluster("英雄纪念碑", 20, SearchOptions.defaults());
            Assert.assertEquals("Run2 英雄 size, b=" + b1.size() + " r2=" + r21.size(),
                    b1.size(), r21.size());
            Assert.assertEquals("TR4.4 Idempotent英雄指纹 baseline===Run2",
                    fp(b1), fp(r21));
        } finally {
            try { if (remoteTcp2 != null) remoteTcp2.close(); } catch (Exception ig) {}
            for (Process p : run2) if (p != null && p.isAlive())
                try { p.destroyForcibly().waitFor(5, TimeUnit.SECONDS); } catch (Exception ig) {}
        }
    }
}
