package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.api.P2PCommand;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.*;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import com.q3lives.ds.database.fulltext.cluster.ProtoWireConverters;

/**
 * P27 T6: NFR + Quality/Code Rubric 5 TR 全绿。
 *
 * TR6.1 Ingest ≤208s （P27 TCP 真实模式，60 docs 目标）
 * TR6.2 Search P95 ≤28.5s（100 次 searchWithExtras P95）
 * TR6.3 指纹全等（inproc baseline vs cross-process TCP 模式 Set.size=1）
 * TR6.4 Quality Rubric ≥ 4 / 5
 * TR6.5 Code Rubric ≥ 4 / 5
 */
public class P27_T6_NfrQualityRubricTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int SHARD_COUNT = 2;   // 跨 JVM process 2 shard，避免子 JVM 过多

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
    private static final int DOCS_TOTAL = 60;

    private File root;
    private DsFulltextClusterClient inprocClient;   // F0 inproc baseline（2 shards）
    private DsFulltextClusterClient crossTcpClient; // F1 cross-process TCP
    private Process[] children;   // N=2 child processes
    private int[] childPorts;
    private File tmpDir;

    @Before public void setUp() throws Exception {
        tmpDir = tf.newFolder("p27t6-" + UUID.randomUUID());
        root = new File(tmpDir, "p27t6-root");
        root.mkdirs();
        System.setProperty("p2p.db.home", tmpDir.getAbsolutePath());

        // --- F0 inproc 2 shards ---
        inprocClient = new DsFulltextClusterClient(SHARD_COUNT, null,
                new File(root, "f0_inproc").getAbsolutePath(), "f0_shard");

        // --- F1 2 child JVM processes + TCP client ---
        String cp = System.getProperty("java.class.path");
        children = new Process[SHARD_COUNT];
        childPorts = new int[SHARD_COUNT];
        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        File f1P0 = new File(root, "f1_p0");
        File f1P1 = new File(root, "f1_p1");
        File[] roots = new File[]{f1P0, f1P1};
        for (int i = 0; i < SHARD_COUNT; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe(), "-Xmx256m",
                    "-Djava.io.tmpdir=" + tmpDir.getAbsolutePath(),
                    "-cp", cp,
                    "javax.net.p2p.server.fulltext.FulltextShardTcpServer",
                    "0", roots[i].getAbsolutePath(), String.valueOf(SHARD_COUNT)
            );
            pb.redirectErrorStream(true);
            pb.directory(tmpDir);
            Process p = pb.start();
            children[i] = p;
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
            }, "p27t6-drain-" + i);
            dr.setDaemon(true);
            dr.start();
            Assert.assertTrue("F1 child[" + i + "] listen at port success, got=" + port, port > 0);
            childPorts[i] = port;
            eps.put(i, new InetSocketAddress("127.0.0.1", port));
        }
        crossTcpClient = new DsFulltextClusterClient(SHARD_COUNT, eps,
                new File(root, "f1_remote").getAbsolutePath(), "f1_shard");
    }

    @After public void tearDown() {
        try { if (crossTcpClient != null) crossTcpClient.close(); } catch (Exception ignore) {}
        try { if (inprocClient != null) inprocClient.close(); } catch (Exception ignore) {}
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        for (Process p : children) if (p != null && p.isAlive())
            try { p.destroyForcibly().waitFor(5, TimeUnit.SECONDS); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    private static String javaExe() {
        String home = System.getProperty("java.home");
        File bin = new File(new File(home, "bin"), "java");
        String cmd = bin.getAbsolutePath();
        if (!cmd.toLowerCase().endsWith(".exe")) cmd += ".exe";
        return cmd;
    }

    private static String docText(int i) {
        String template = CORPUS[i % CORPUS.length];
        return template + " T6P27ID_" + i + " 人民群众 创新 奋斗 高质量 发展 新质生产力 人工智能";
    }

    // ====================================================================
    // TR6.1 Ingest 60 docs ≤ 208s （cross-TCP 模式真实慢路径，弹性 208s 上限）
    // ====================================================================
    @Test public void tr61_ingest60DocsLte208Seconds() throws Exception {
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < DOCS_TOTAL; i++) {
            long docId = 9_900_000L + i;
            String text = docText(i);
            IngestStat st = crossTcpClient.putDocCluster(docId, text);
            Assert.assertTrue("ingest doc#" + i + " pairs>0, got=" + st.pairsTotal, st.pairsTotal > 0);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[P27 TR6.1 Ingest TCP] " + DOCS_TOTAL + " docs = " + ms + " ms (≤208000ms baseline)");
        Assert.assertTrue("TR6.1 ingest " + ms + "ms ≤ 208000ms", ms <= 208_000L);
    }

    // ====================================================================
    // TR6.2 P95 searchWithExtras ≤28.5s（100 次）
    // ====================================================================
    @Test public void tr62_searchWithExtrasP95Lte28500Ms() throws Exception {
        Map<Long, String> raw = new HashMap<>();
        for (int i = 0; i < DOCS_TOTAL; i++) {
            long docId = 9_900_000L + i;
            String text = docText(i);
            crossTcpClient.putDocCluster(docId, text);
            raw.put(docId, text);
        }
        SearchOptions o = SearchOptions.defaults();
        o.vectorRecall = false; o.neighborRecallD = 0; o.phraseBoost = false;
        final int ITERS = 100;
        long[] samples = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t0 = System.nanoTime();
            String q = CORPUS[i % CORPUS.length];
            crossTcpClient.searchClusterWithExtras(q, raw, 20, o);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        Arrays.sort(samples);
        long p95 = samples[(int)(samples.length * 0.95)];
        System.out.println("[P27 TR6.2 P95 TCP] " + p95 + "ms, max=" + samples[samples.length - 1]
                + "ms, med=" + samples[samples.length / 2] + "ms (≤28500ms)");
        Assert.assertTrue("TR6.2 P95 " + p95 + "ms ≤ 28500ms", p95 <= 28_500L);
    }

    // ====================================================================
    // TR6.3 fingerprint 全等（inproc F0 vs cross-TCP F1，同一 DOC_IDS 输入 → Set.size=1）
    // ====================================================================
    @Test public void tr63_crossTcpFingerprintEqInprocBaseline() throws Exception {
        long[] DOC_IDS = new long[DOCS_TOTAL];
        for (int i = 0; i < DOCS_TOTAL; i++) DOC_IDS[i] = 9_950_000L + i;
        Map<Long, String> rawAll = new HashMap<>();
        for (int i = 0; i < DOCS_TOTAL; i++) {
            String text = docText(i);
            inprocClient.putDocCluster(DOC_IDS[i], text);
            crossTcpClient.putDocCluster(DOC_IDS[i], text);
            rawAll.put(DOC_IDS[i], text);
        }
        TimeUnit.MILLISECONDS.sleep(400);
        // 指纹搜索 3 query
        String[] queries = new String[]{"人民", "创新", "人工智能"};
        for (String q : queries) {
            List<ScoredDocHit> f0 = inprocClient.searchCluster(q, 30, SearchOptions.defaults());
            List<ScoredDocHit> f1 = crossTcpClient.searchCluster(q, 30, SearchOptions.defaults());
            Assert.assertNotNull("F0 " + q, f0);
            Assert.assertNotNull("F1 " + q, f1);
            Assert.assertEquals("query[" + q + "] size eq, F0=" + f0.size() + " F1=" + f1.size(),
                    f0.size(), f1.size());
            Set<String> fp = new HashSet<>();
            fp.add(fp(f0));
            fp.add(fp(f1));
            Assert.assertEquals("TR6.3 query[" + q + "] inproc vs cross-TCP 指纹全等 Set.size=1 (hits=" + f0.size() + ")",
                    1, fp.size());
        }
    }
    private static String fp(List<ScoredDocHit> hs) {
        StringBuilder sb = new StringBuilder(hs.size() * 80);
        for (ScoredDocHit h : hs) {
            sb.append(h.docId).append('@').append(h.shardId).append('|')
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
    // TR6.4 Quality Rubric ≥ 4 / 5
    // ====================================================================
    @Test public void tr64_qualityRubricAtLeast4() throws Exception {
        int score = 0;
        // (1) 4 unique doc 每个 shard 1 个 + 公共词 搜索 size ≥ 2（至少各 shard 命中）
        long[] per = new long[SHARD_COUNT]; Arrays.fill(per, -1L); long probe = 9_300_000L; int got = 0;
        while (got < SHARD_COUNT) {
            int s = Math.abs(Long.hashCode(probe)) % SHARD_COUNT;
            if (per[s] == -1L) { per[s] = probe; got++; }
            probe += 11;
        }
        String[] unique = new String[]{"P27QA_UK0_SHARD0_XYZ1","P27QA_UK1_SHARD1_XYZ2"};
        Map<Long, String> rawQ = new HashMap<>();
        for (int i = 0; i < SHARD_COUNT; i++) {
            String t = "P27_COMMONWORD_QA_3344 " + unique[i] + " 人民 创新";
            inprocClient.putDocCluster(per[i], t);
            crossTcpClient.putDocCluster(per[i], t);
            rawQ.put(per[i], t);
        }
        SearchOptions o = SearchOptions.defaults();
        o.vectorRecall = false; o.neighborRecallD = 0; o.phraseBoost = false;
        List<ScoredDocHit> hCommon = crossTcpClient.searchCluster("P27_COMMONWORD_QA_3344", 10, o);
        Set<Integer> shSet = new HashSet<>();
        for (ScoredDocHit h : hCommon) { if (h.shardId >= 0) shSet.add(h.shardId); }
        if (hCommon.size() >= SHARD_COUNT || shSet.size() == SHARD_COUNT) { score++; }
        System.out.println("[TR6.4] (1)2-shards: common hits=" + hCommon.size() + " shards=" + shSet);
        // (2) 2 unique 各 → top1 == per[i]
        boolean allOk = true;
        for (int i = 0; i < SHARD_COUNT; i++) {
            List<ScoredDocHit> hi = crossTcpClient.searchCluster(unique[i], 5, o);
            if (hi.isEmpty() || hi.get(0).docId != per[i]) { allOk = false; break; }
        }
        if (allOk) score++;
        System.out.println("[TR6.4] (2)unique top1 each: " + allOk);
        // (3) searchWithExtras 任意 snippet / span
        List<ScoredDocHitWithExtras> hEx = crossTcpClient.searchClusterWithExtras("P27_COMMONWORD_QA_3344", rawQ, 10, o);
        boolean anySnip = false, anySpan = false;
        for (ScoredDocHitWithExtras e : hEx) {
            if (e.snippets != null && !e.snippets.isEmpty()) anySnip = true;
            if (e.matchedSpans != null && !e.matchedSpans.isEmpty()) anySpan = true;
        }
        boolean p3 = anySnip || anySpan;
        if (p3) score++;
        System.out.println("[TR6.4] (3)anySnip=" + anySnip + " anySpan=" + anySpan + " →" + p3);
        // (4) Facet 非空 或 总 span+snip>0
        boolean p4 = false;
        if (!hEx.isEmpty()) {
            int totalFacets = 0, totalSp = 0, totalSn = 0;
            for (ScoredDocHitWithExtras e : hEx) {
                if (e.topFacets != null) totalFacets += e.topFacets.size();
                if (e.matchedSpans != null) totalSp += e.matchedSpans.size();
                if (e.snippets != null) totalSn += e.snippets.size();
            }
            p4 = (totalFacets > 0) || (totalSp + totalSn > 0);
        }
        if (p4) score++;
        System.out.println("[TR6.4] (4)facet/spans non-empty fallback: " + p4);
        // (5) 7 handler cmd ID 24354~60 全部无重号
        Set<Integer> ids = new HashSet<>();
        String[] handlers = new String[]{
                "javax.net.p2p.server.handler.fulltext.FulltextShardPutDocHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardClearDocHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardIndexVectorHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardSearchVectorHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardSearchHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardSearchExtrasHandler",
                "javax.net.p2p.server.handler.fulltext.FulltextShardCloseHandler"
        };
        boolean hOk = true;
        for (String h : handlers) {
            try {
                Class<?> c = Class.forName(h);
                Constructor<?> ctor = c.getDeclaredConstructor(); ctor.setAccessible(true);
                Object inst = ctor.newInstance();
                P2PCommand cmd = (P2PCommand) c.getMethod("getCommand").invoke(inst);
                int id = cmd.getValue();
                if (id < 24354 || id > 24360) hOk = false;
                if (!ids.add(id)) hOk = false;
            } catch (Throwable t) { hOk = false; }
        }
        if (hOk && ids.size() == 7) score++;
        System.out.println("[TR6.4] (5)7handlers unique id 24354-60: " + hOk + " ids=" + ids);
        System.out.println("[P27 TR6.4 Quality] score=" + score + " / 5");
        Assert.assertTrue("TR6.4 quality rubric " + score + " >=4", score >= 4);
    }

    // ====================================================================
    // TR6.5 Code Rubric ≥ 4 / 5
    // ====================================================================
    @Test public void tr65_codeRubricAtLeast4() throws Exception {
        int score = 0;
        // (1) ProtoWireConverters final / private ctor / 0 mutable static / ≥12 static methods
        {
            int mods = ProtoWireConverters.class.getModifiers();
            boolean finalCls = Modifier.isFinal(mods);
            boolean hasPvt = false;
            for (Constructor<?> c : ProtoWireConverters.class.getDeclaredConstructors())
                if (Modifier.isPrivate(c.getModifiers())) hasPvt = true;
            int mutStatic = 0;
            for (Field f : ProtoWireConverters.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) && !Modifier.isFinal(m)) mutStatic++;
            }
            int staticMs = 0;
            for (Method m : ProtoWireConverters.class.getDeclaredMethods())
                if (Modifier.isStatic(m.getModifiers())) staticMs++;
            if (finalCls && hasPvt && mutStatic == 0 && staticMs >= 12) score++;
        }
        // (2) GrpcShardTransport 0 mutable static / non-static fields final
        {
            int mutStatic = 0;
            for (Field f : GrpcShardTransport.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) && !Modifier.isFinal(m)) mutStatic++;
            }
            boolean allF = true;
            for (Field f : GrpcShardTransport.class.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m)) continue;
                if (!Modifier.isFinal(m)) allF = false;
            }
            if (mutStatic == 0 && allF) score++;
        }
        // (3) 14 fulltext 枚举 24354-60 + 24454-60 无重号
        {
            Set<Integer> ids = new HashSet<>();
            String[] names = new String[]{
                    "FULLTEXT_SHARD_PUT_DOC","FULLTEXT_SHARD_CLEAR_DOC","FULLTEXT_SHARD_INDEX_VECTOR",
                    "FULLTEXT_SHARD_SEARCH_VECTOR","FULLTEXT_SHARD_SEARCH","FULLTEXT_SHARD_SEARCH_EXTRAS","FULLTEXT_SHARD_CLOSE",
                    "R_OK_FULLTEXT_SHARD_PUT_DOC_ACK","R_OK_FULLTEXT_SHARD_CLEAR_DOC_ACK","R_OK_FULLTEXT_SHARD_INDEX_VECTOR_ACK",
                    "R_OK_FULLTEXT_SHARD_SEARCH_VECTOR_ACK","R_OK_FULLTEXT_SHARD_SEARCH_ACK","R_OK_FULLTEXT_SHARD_SEARCH_EXTRAS_ACK","R_OK_FULLTEXT_SHARD_CLOSE_ACK"
            };
            boolean ok = true;
            for (String n : names) {
                try {
                    P2PCommand cmd = P2PCommand.valueOf(n);
                    int id = cmd.getValue();
                    if (!((id >= 24354 && id <= 24360) || (id >= 24454 && id <= 24460))) ok = false;
                    if (!ids.add(id)) ok = false;
                } catch (Throwable t) { ok = false; }
            }
            if (ok && ids.size() == 14) score++;
        }
        // (4) ShardTransport 5原方法 + 3 default ≥ 8 methods
        {
            Set<String> w5 = new HashSet<>(Arrays.asList("putDoc","clearDoc","searchWithVector","indexTermVector","close"));
            Set<String> w3 = new HashSet<>(Arrays.asList("search","searchWithExtras","putDocBatch"));
            Set<String> actual = new HashSet<>();
            for (Method m : FulltextStore.ShardTransport.class.getDeclaredMethods()) actual.add(m.getName());
            boolean a5 = actual.containsAll(w5);
            boolean a3 = actual.containsAll(w3);
            if (a5 && a3 && actual.size() >= 8) score++;
        }
        // (5) FulltextShardTcpServer boundPort / close / isAlive 三 API 且继承 AbstractP2PServer / AutoCloseable
        {
            boolean ok = true;
            Class<?> cls = Class.forName("javax.net.p2p.server.fulltext.FulltextShardTcpServer");
            try {
                cls.getMethod("getBoundPort");
                cls.getMethod("close");
                cls.getMethod("isServerAliveForTest");
                if (!AutoCloseable.class.isAssignableFrom(cls)) ok = false;
                Class<?> superC = cls.getSuperclass();
                boolean superMatch = false;
                while (superC != null) {
                    if (superC.getName().contains("AbstractP2PServer")) { superMatch = true; break; }
                    superC = superC.getSuperclass();
                }
                if (!superMatch) ok = false;
            } catch (Throwable t) { ok = false; }
            if (ok) score++;
        }
        System.out.println("[P27 TR6.5 Code] score=" + score + " / 5");
        Assert.assertTrue("TR6.5 code rubric " + score + " >=4", score >= 4);
    }
}
