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

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.net.p2p.server.fulltext.FulltextShardTcpServer;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P31_T4_TcpCrossProcessJoinTest {

    static class RelT4 extends DsTableAdapter {
        @DsField(name = "main_id", length = 8) public Long mainId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    static class MainT4 extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "ft_text", length = 1024) public String ftText;
        @DsOneToOne(joinProp = "mainId", props = "id") public RelT4 rel;
    }

    private static final String[] CORPUS = new String[]{
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
    private GenericManager<MainT4> mainGm;
    private GenericManager<RelT4> relGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private List<Long> allMainIds;
    private Map<Long, RelT4> relByMainId;
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

    private static String joinFingerprint(List<HybridHit<MainT4>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<MainT4> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.15f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.15f", h.ftx.core.finalScore)).append('|');
            sb.append(h.ftx.core.shardId).append('|');
            RelT4 r = (RelT4) h.joinFetch.get(RelT4.class);
            sb.append(r == null ? "null" : r.price + ":" + r.qty).append(',');
        }
        return md5(sb.toString());
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p31_t4_").toFile();
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
                System.out.println("[P31-T4-srv-" + i + "] " + line);
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
            }, "p31-t4-drain-" + srvIdx);
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
        mainGm = new GenericManager<>(MainT4.class);
        relGm = new GenericManager<>(RelT4.class);
        relatedGms = new HashMap<>();
        relatedGms.put(RelT4.class, relGm);
        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();
        relByMainId = new HashMap<>();

        for (int i = 0; i < N; i++) {
            MainT4 m = new MainT4();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + String.format("%02d", i) + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
            m.name = "订单商品_" + String.format("%02d", i);
            m.ftText = text;
            RelT4 r = new RelT4();
            r.mainId = 0L;
            r.price = (double) i;
            r.qty = (long) (i * 2);
            m.rel = r;
            mainGm.saveOrUpdate(m);
            long mid = m.getId();
            r.mainId = mid;
            relGm.saveOrUpdate(r);
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull("putDocCluster stat 非null", st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
            relByMainId.put(mid, r);
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

    @Test(timeout = 180000)
    public void TR4_1_HJC_iaeAndNpe_Coverage() throws Exception {
        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        HybridJoinClient<MainT4> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT4> orm = new QueryWrapper<>();
            QueryWrapper<RelT4> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT4> js = new JoinSpec<>(RelT4.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            try {
                hjc.searchHybridJoin(orm, joins, null, rawDocTexts, 10, SearchOptions.defaults());
                Assert.fail("ftQ null NPE");
            } catch (NullPointerException npe) { /* ok */ }
            try {
                hjc.searchHybridJoin(orm, joins, "  \t  ", rawDocTexts, 10, SearchOptions.defaults());
                Assert.fail("ftQ blank IAE");
            } catch (IllegalArgumentException ex) { /* ok */ }
            try {
                hjc.searchHybridJoin(orm, joins, "科技", null, 10, SearchOptions.defaults());
                Assert.fail("rawDocTexts null NPE");
            } catch (NullPointerException npe) { /* ok */ }
            try {
                hjc.searchHybridJoin(orm, joins, "科技", rawDocTexts, 0, SearchOptions.defaults());
                Assert.fail("K<1 IAE");
            } catch (IllegalArgumentException ex) { /* ok */ }
            List<JoinSpec<?>> badJoins = new ArrayList<>();
            badJoins.add(null);
            try {
                hjc.searchHybridJoin(orm, badJoins, "科技", rawDocTexts, 10, SearchOptions.defaults());
                Assert.fail("joins element null IAE");
            } catch (IllegalArgumentException ex) { /* ok */ }
            Map<Class<?>, GenericManager<?>> noRelGm = new HashMap<>();
            HybridJoinClient<MainT4> hjc2 = new HybridJoinClient<>(dhqc, noRelGm, mainGm);
            try {
                hjc2.searchHybridJoin(orm, joins, "科技", rawDocTexts, 10, SearchOptions.defaults());
                Assert.fail("relatedGms missing RelT4 IAE");
            } catch (IllegalArgumentException ex) { /* ok */ }
            hjc2.close();
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void TR4_2_5argsTCPJoin_BasicJoinFetchCorrect_Subset() throws Exception {
        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        HybridJoinClient<MainT4> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT4> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            QueryWrapper<RelT4> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT4> js = new JoinSpec<>(RelT4.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            final int K = 15;
            List<HybridHit<MainT4>> jHits = hjc.searchHybridJoin(orm, joins, "人民 科技 绿色 共赢", rawDocTexts, K, SearchOptions.defaults());
            Assert.assertNotNull("TCP 5参 join hits 非null", jHits);
            Assert.assertTrue("hits.size <= K (" + jHits.size() + "/" + K + ")", jHits.size() <= K);
            Set<Long> jIds = new HashSet<>();
            for (HybridHit<MainT4> h : jHits) {
                jIds.add(h.docId);
                RelT4 r = (RelT4) h.joinFetch.get(RelT4.class);
                Assert.assertNotNull("TCP joinFetch RelT4 非null docId=" + h.docId, r);
                Assert.assertTrue("TCP price>10 100% (docId=" + h.docId + " price=" + r.price + ")", r.price > 10.0);
                RelT4 expected = relByMainId.get(h.docId);
                Assert.assertNotNull("relByMainId 对照存在 docId=" + h.docId, expected);
                Assert.assertEquals("joinFetch price 对照 relByMainId 全等", expected.price, r.price, 1e-12);
                Assert.assertEquals("joinFetch qty 对照 relByMainId 全等", expected.qty, r.qty);
            }
            List<HybridHit<MainT4>> base = dhqc.searchHybrid(orm, "人民 科技 绿色 共赢", rawDocTexts, K, SearchOptions.defaults());
            Set<Long> baseIds = new HashSet<>();
            for (HybridHit<MainT4> h : base) baseIds.add(h.docId);
            Assert.assertTrue("TCP Join 结果 ⊆ Baseline (SubSet)", baseIds.containsAll(jIds));
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void TR4_3_8argsTCPJoinWithWeights_Score1e9_FpStable() throws Exception {
        DsHybridQueryClient<MainT4> dhqc = DsHybridQueryClient.ofLocal(MainT4.class, mainGm, ftClient);
        HybridJoinClient<MainT4> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<RelT4> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT4> js = new JoinSpec<>(RelT4.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            Map<String, Map.Entry<Double, ToDoubleFunction<MainT4>>> colW = new HashMap<>();
            ToDoubleFunction<MainT4> qtyFn = e -> {
                RelT4 r = relByMainId.get(e.getId());
                return r != null ? r.qty.doubleValue() : 0.0;
            };
            colW.put("qty", new AbstractMap.SimpleEntry<>(0.2, qtyFn));
            QueryWrapper<MainT4> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            final double alpha = 0.7;
            final double beta = 0.3;
            final int K = 20;
            List<HybridHit<MainT4>> base = dhqc.searchHybridWithWeights(orm, "现代化 大模型 乡村振兴 绿色", rawDocTexts, K, SearchOptions.defaults(), alpha, beta, colW);
            Map<Long, Double> baseScore = new HashMap<>();
            for (HybridHit<MainT4> h : base) baseScore.put(h.docId, h.finalHybridScore);
            List<HybridHit<MainT4>> jh = hjc.searchHybridJoinWithWeights(orm, joins, "现代化 大模型 乡村振兴 绿色", rawDocTexts, K, SearchOptions.defaults(), alpha, beta, colW);
            for (HybridHit<MainT4> h : jh) {
                Double bs = baseScore.get(h.docId);
                if (bs != null) {
                    double diff = Math.abs(bs - h.finalHybridScore);
                    Assert.assertTrue("TCP 加权 交集 docId score diff ≤1e-9 (" + diff + ")", diff <= 1e-9);
                }
                RelT4 r = (RelT4) h.joinFetch.get(RelT4.class);
                Assert.assertNotNull("TCP 加权 joinFetch 非null docId=" + h.docId, r);
                Assert.assertTrue("TCP 加权 price>10 (" + r.price + ")", r.price > 10.0);
            }
            Set<String> fps = new HashSet<>();
            for (int t = 0; t < 2; t++) {
                List<HybridHit<MainT4>> rp = hjc.searchHybridJoinWithWeights(orm, joins, "现代化 大模型 乡村振兴 绿色", rawDocTexts, K, SearchOptions.defaults(), alpha, beta, colW);
                fps.add(joinFingerprint(rp));
            }
            Assert.assertEquals("TCP 8参 Join 2次重跑指纹稳定 (Set.size=1)", 1, fps.size());
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void TR4_4_SubProcessExit2x2_NoZombie_plus_ORMlistIds50() throws Exception {
        for (int i = 0; i < 50; i++) {
            QueryWrapper<MainT4> w = new QueryWrapper<>();
            w.likeLeft("name", "订单商品");
            List<Long> ids = mainGm.listIds(w);
            Assert.assertEquals("listIds 50× 稳定 (N=" + N + " iter=" + i + ")", N, ids.size());
        }
        Thread.sleep(500);
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
            Assert.assertFalse("子进程 " + i + " 退出后 0 僵尸（TCP 2×2）", p.isAlive());
        }
        Assert.assertEquals("serverProcesses.size==SHARDS (" + serverProcesses.size() + " vs " + SHARDS + ")", SHARDS, serverProcesses.size());
    }
}
