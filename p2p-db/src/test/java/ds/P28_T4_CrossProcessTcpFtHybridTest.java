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

public class P28_T4_CrossProcessTcpFtHybridTest {

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

    private static String entityFingerprint(AbcEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getId()).append('|');
        sb.append(e.name == null ? "null" : e.name).append('|');
        sb.append(e.a).append('|').append(e.b).append('|').append(e.c);
        return md5(sb.toString());
    }

    private static String hitFingerprint(HybridHit<AbcEntity> h) {
        var c = h.ftx.core;
        StringBuilder sb = new StringBuilder();
        sb.append(h.docId).append('|');
        sb.append(entityFingerprint(h.entity)).append('|');
        sb.append(String.format("%.12f", c.finalScore)).append('|');
        sb.append(String.format("%.12f", c.sortScore)).append('|');
        sb.append(String.format("%.12f", c.rrfScore)).append('|');
        sb.append(String.format("%.12f", c.phraseBoostScore)).append('|');
        sb.append(c.matchedQueryTerms).append('|');
        sb.append(String.format("%.12f", c.vectorScore));
        return md5(sb.toString());
    }

    private static String hitsFingerprint(List<HybridHit<AbcEntity>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<AbcEntity> h : hits) sb.append(hitFingerprint(h)).append(',');
        return md5(sb.toString());
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p28_t4_").toFile();
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
                System.out.println("[P28-T4-srv-" + i + "] " + line);
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
            }, "p28-t4-drain-" + srvIdx);
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
            try { p.destroyForcibly().waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Exception ignore) {}
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
    public void tr41_nullQueryNegativeKThrowIae() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        try {
            hc.searchHybrid(null, "   ", rawDocTexts, 20, SearchOptions.defaults());
            Assert.fail("blank query 抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("OK", true);
        }
        try {
            hc.searchHybrid(null, "人民", rawDocTexts, -5, SearchOptions.defaults());
            Assert.fail("K=-5 抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("OK", true);
        }
        hc.close();
    }

    @Test
    public void tr42_qAllHybridEqualsHandMadeFtSearchTcp() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        final int K = 20;
        List<HybridHit<AbcEntity>> clientHits = hc.searchHybrid(null, "人民", rawDocTexts, K, SearchOptions.defaults());
        Assert.assertNotNull("clientHits", clientHits);

        List<Long> ormIds = gm.listIds(null);
        Set<Long> ormSet = new HashSet<>(ormIds);
        int ftK = K * 3;
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras("人民", rawDocTexts, ftK, SearchOptions.defaults());
        List<ScoredDocHitWithExtras> filtered = new ArrayList<>();
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormSet.contains(h.core.docId)) continue;
            filtered.add(h);
            if (filtered.size() >= K) break;
        }
        Map<Long, AbcEntity> entMap = new HashMap<>();
        for (ScoredDocHitWithExtras h : filtered) {
            long id = h.core.docId;
            AbcEntity e = gm.getById(id);
            if (e != null) entMap.put(id, e);
        }
        List<HybridHit<AbcEntity>> handMade = new ArrayList<>();
        for (ScoredDocHitWithExtras h : filtered) {
            AbcEntity e = entMap.get(h.core.docId);
            if (e != null) handMade.add(new HybridHit<>(e, h));
        }
        String fpClient = hitsFingerprint(clientHits);
        String fpHand = hitsFingerprint(handMade);
        Set<String> set = new HashSet<>(Arrays.asList(fpClient, fpHand));
        Assert.assertEquals("TCP 跨进程 Q-ALL 合查 指纹 全等 Set.size=1", 1, set.size());
        hc.close();
    }

    @Test
    public void tr43_qFilterAeq100SizeMax10AllAeq100() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        List<HybridHit<AbcEntity>> hits = hc.searchHybrid(w, "人民", rawDocTexts, 20, SearchOptions.defaults());
        Assert.assertNotNull("hits", hits);
        Assert.assertTrue("size <= 10，实际 " + hits.size(), hits.size() <= 10);
        for (HybridHit<AbcEntity> h : hits) {
            Assert.assertEquals("hit " + h.docId + " entity.a==100，0 误匹配", 100L, h.entity.a);
        }
        hc.close();
    }

    @Test
    public void tr44_subProcessExitZeroNoZombie() throws Exception {
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
            Assert.assertFalse("子进程 " + i + " 退出后 0 僵尸（isAlive==false）", p.isAlive());
        }
    }
}
