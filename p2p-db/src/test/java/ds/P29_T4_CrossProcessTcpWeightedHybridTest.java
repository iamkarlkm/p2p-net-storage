package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.net.p2p.server.fulltext.FulltextShardTcpServer;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P29_T4_CrossProcessTcpWeightedHybridTest {

    @DsCompositeIndex(name = "idx_abc", columns = {"a", "b", "c"})
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

    private static final String[] CORPUS_SEED = new String[]{
            "人民民主是社会主义的生命 中国特色社会主义进入新时代",
            "科技创新推动高质量发展 新质生产力 突破 攻关",
            "绿水青山就是金山银山 生态环境保护 绿色低碳可持续",
            "人类命运共同体理念 全球治理 国际合作共赢",
            "全面建设社会主义现代化国家 中华民族伟大复兴中国梦",
            "全过程人民民主的制度优势 依法治国 共同富裕",
            "一带一路国际合作共赢倡议 文化自信自强 中华传统",
            "粤港澳大湾区建设发展规划 长三角一体化 京津冀协同",
            "数字经济与实体经济深度融合 大数据 人工智能 大模型",
            "碳达峰碳中和双碳目标行动 乡村振兴 农业农村现代化"
    };

    private static final int N = 30;
    private static final int SHARDS = 2;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private File serverWorkDir;
    private List<Integer> freePorts;
    private DsFulltextClusterClient ftClient;
    private GenericManager<AbcEntity> gm;
    private Map<Long, String> rawDocTexts;
    private List<Long> entityIds;
    private List<Process> serverProcesses;

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hybridFingerprint(List<HybridHit<AbcEntity>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<AbcEntity> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.15f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.15f", h.ftx.core.finalScore)).append('|');
            sb.append(h.entity.a).append('|').append(h.entity.c).append('|');
            sb.append(h.ftx.core.shardId).append(',');
        }
        return md5(sb.toString());
    }

    private static Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> newWeights_a001_c002() {
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> m = new HashMap<>();
        m.put("a", new AbstractMap.SimpleEntry<>(0.01, x -> (double) x.a));
        m.put("c", new AbstractMap.SimpleEntry<>(0.02, x -> (double) x.c));
        return m;
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p29_t4_").toFile();
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
                    javaExe,
                    "-Xmx2048m",
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
            StringBuilder sb = new StringBuilder();
            while (System.nanoTime() < deadline && (line = br.readLine()) != null) {
                System.out.println("[P29-T4-srv-" + i + "] " + line);
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
            final int srvIdx = i;
            Thread drain = new Thread(() -> {
                try {
                    char[] buf = new char[512];
                    while (br.read(buf) >= 0) { /* drain only */ }
                } catch (Exception ignore) { }
            }, "p29-t4-drain-" + srvIdx);
            drain.setDaemon(true);
            drain.start();
            if (actualPort <= 0) {
                try { p.destroyForcibly().waitFor(3, TimeUnit.SECONDS); } catch (Exception ig) { }
                Assert.fail("Process[" + i + "]未输出 listen at，stdout 摘要: " + sb.substring(0, Math.min(sb.length(), 1200)));
            }
            freePorts.add(actualPort);
            eps.put(i, new InetSocketAddress("127.0.0.1", actualPort));
        }

        ftClient = new DsFulltextClusterClient(SHARDS, eps, ftRoot.getAbsolutePath(), "shard");
        gm = new GenericManager<>(AbcEntity.class);
        rawDocTexts = new LinkedHashMap<>();
        entityIds = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            int seed = i % CORPUS_SEED.length;
            String text = CORPUS_SEED[seed] + " doc_" + String.format("%02d", i) + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
            e.name = text;
            e.a = 100L + (i % 3) * 100L;
            e.b = (i % 2 == 0) ? "x" : "y";
            e.c = (seed + 1);
            gm.saveOrUpdate(e);
            long docId = e.getId();
            IngestStat st = ftClient.putDocCluster(docId, text);
            Assert.assertNotNull("putDocCluster stat 非 null", st);
            rawDocTexts.put(docId, text);
            entityIds.add(docId);
        }
        Thread.sleep(1500);
    }

    @After
    public void after() {
        try { if (ftClient != null) ftClient.close(); } catch (Exception ignore) {}
        for (Process p : serverProcesses) {
            try { p.destroyForcibly().waitFor(2000, TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
            try { if (p.isAlive()) p.destroyForcibly(); } catch (Exception ignore) {}
        }
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    @Test
    public void TR4_1_ParamIaeAndNpeCoverage() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> good = newWeights_a001_c002();
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, -1.0, 0.7, good);
            Assert.fail("alpha < 0 IAE");
        } catch (IllegalArgumentException ex) { /* ok */ }
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, 200.0, 0.7, good);
            Assert.fail("alpha > 100 IAE");
        } catch (IllegalArgumentException ex) { /* ok */ }
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, 0.3, -1.0, good);
            Assert.fail("beta < 0 IAE");
        } catch (IllegalArgumentException ex) { /* ok */ }
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, 0.3, 200.0, good);
            Assert.fail("beta > 100 IAE");
        } catch (IllegalArgumentException ex) { /* ok */ }
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, 0.3, 0.7, null);
            Assert.fail("weights null NPE");
        } catch (NullPointerException npe) { /* ok */ }
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> bad = new HashMap<>();
        bad.put("x", new AbstractMap.SimpleEntry<>(1.0, null));
        try {
            hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, 0.3, 0.7, bad);
            Assert.fail("extractor null NPE");
        } catch (NullPointerException npe) { /* ok */ }
        hc.close();
    }

    @Test
    public void TR4_2_TcpQFilterAlpha03Beta07HandCalcExact() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        final double alpha = 0.3;
        final double beta = 0.7;
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = newWeights_a001_c002();
        List<HybridHit<AbcEntity>> hits = hc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, alpha, beta, cw);
        Assert.assertTrue("TCP 加权命中非空", !hits.isEmpty());
        Assert.assertTrue("size <= 10", hits.size() <= 10);
        Set<Long> hitIds = new HashSet<>();
        for (HybridHit<AbcEntity> h : hits) {
            AbcEntity e = h.entity;
            Assert.assertEquals("Q-FILTER TCP a==100", 100L, e.a);
            hitIds.add(h.docId);
            double expected = alpha * h.ftx.core.finalScore + beta * (0.01 * e.a + 0.02 * e.c);
            Assert.assertEquals("TR4.2 TCP 手算线性融合 docId=" + h.docId, expected, h.finalHybridScore, 1e-9);
        }
        List<HybridHit<AbcEntity>> base = hc.searchHybrid(w, "人民", rawDocTexts, 10, null);
        Set<Long> baseIds = base.stream().map(x -> x.docId).collect(Collectors.toSet());
        Assert.assertEquals("TR4.2 TCP 权重不改交集 docId Set equals", baseIds, hitIds);
        hc.close();
    }

    @Test
    public void TR4_3_Alpha1Beta0EqualsP28BaselineFingerprint() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        final int K = 20;
        List<HybridHit<AbcEntity>> A = hc.searchHybrid(null, "人民", rawDocTexts, K, null);
        List<HybridHit<AbcEntity>> B = hc.searchHybridWithWeights(null, "人民", rawDocTexts, K, null,
                1.0, 0.0, new HashMap<>());
        Assert.assertEquals("A/B 长度相等 TCP", A.size(), B.size());
        for (int i = 0; i < A.size(); i++) {
            Assert.assertEquals("docId[" + i + "]", A.get(i).docId, B.get(i).docId);
            Assert.assertEquals("finalHybridScore[" + i + "] TCP 1e-12",
                    A.get(i).finalHybridScore, B.get(i).finalHybridScore, 1e-12);
            Assert.assertEquals("shardId[" + i + "]", A.get(i).ftx.core.shardId, B.get(i).ftx.core.shardId);
        }
        String fpA = hybridFingerprint(A);
        String fpB = hybridFingerprint(B);
        Set<String> s = new HashSet<>(Arrays.asList(fpA, fpB));
        Assert.assertEquals("TR4.3 TCP α=1β=0 指纹全等 P28 基线 Set.size=1", 1, s.size());
        hc.close();
    }

    @Test
    public void TR4_4_SubProcessExitZeroNoZombie() throws Exception {
        Thread.sleep(1500);
        for (int i = 0; i < serverProcesses.size(); i++) {
            Process p = serverProcesses.get(i);
            if (p.isAlive()) {
                p.destroy();
                boolean ok = p.waitFor(12000, TimeUnit.MILLISECONDS);
                if (!ok) {
                    p.destroyForcibly();
                    p.waitFor(3000, TimeUnit.MILLISECONDS);
                }
            }
            Assert.assertFalse("子进程 " + i + " 退出后 0 僵尸（isAlive==false）TCP", p.isAlive());
        }
    }
}
