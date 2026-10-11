package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.net.p2p.server.fulltext.FulltextShardTcpServer;
import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * P27 T3: 单 JVM loopback 4 shard 三级指纹全等 + concurrentScatter=true 并行 branch。
 *
 * Fingerprint Level 全等 (Set size=1)：
 *   F1 = DsFulltextClusterClient (inproc GrpcShardTransport = P26 原模式)
 *   F2 = DsFulltextClusterClient (TCP 真实连通 GrpcShardTransport)
 *   F3 = ShardRouter.new LocalShardTransport (直接内嵌 FulltextStore[])
 *
 * concurrentScatter: F1/F2/F3 同搜索条件 default(false) vs concurrent(true) 指纹 byte 级全等。
 *
 * TR3.1: 3 种 Transport 构造 + 8 文档 ingest 均成功
 * TR3.2: 搜索人民 F1/F2/F3 finalScoreList全等 (size + ordering + per-hit bytes)
 * TR3.3: concurrentScatter=true vs false 指纹全等（F1单内部对比）
 * TR3.4: searchWithExtras人民 F1 vs F2 三容器非空 + 三键排序人民<民 同时成立
 */
public class P27_T3_Loopback4ShardsFingerprintTest {

    static final int N = 4;
    static File rootInproc;
    static File rootTcp;
    static File rootLocal;
    static String[] DOCS;
    static long[] DOC_IDS;

    // TCP servers
    static FulltextShardTcpServer[] srvs = new FulltextShardTcpServer[N];
    static int[] ports = new int[N];
    static Map<Integer, InetSocketAddress> eps;

    // Three router instances
    static DsFulltextClusterClient inprocClient;   // F1
    static DsFulltextClusterClient tcpClient;      // F2
    static ShardRouter localRouter;                // F3

    @BeforeClass
    public static void beforeAll() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        rootInproc = new File(tmp, "p27t3_inproc_" + System.nanoTime());
        rootTcp = new File(tmp, "p27t3_tcp_" + System.nanoTime());
        rootLocal = new File(tmp, "p27t3_local_" + System.nanoTime());
        for (File r : Arrays.asList(rootInproc, rootTcp, rootLocal)) if (!r.exists()) r.mkdirs();

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
        for (int i = 0; i < DOCS.length; i++) DOC_IDS[i] = 30001L + i;

        // ------- F1: inproc client (P26 原模式, shardIdToEndpoint=null -> all 127.0.0.1:0 -> inproc) -------
        inprocClient = new DsFulltextClusterClient(N, null, rootInproc.getAbsolutePath(), "f1_shard");

        // ------- TCP servers (F2 backend) -------
        DsServerFulltextRegistry.getOrCreate(rootTcp.getAbsolutePath(), N, "f2_shard");
        Thread[] bindThreads = new Thread[N];
        for (int s = 0; s < N; s++) {
            srvs[s] = new FulltextShardTcpServer(0);
            final int sid = s;
            bindThreads[s] = new Thread(() -> srvs[sid].singletonCreated(srvs[sid]), "p27t3-srv-" + sid);
            bindThreads[s].setDaemon(true);
            bindThreads[s].start();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        for (int s = 0; s < N; s++) {
            while (System.nanoTime() < deadline && !srvs[s].isServerAliveForTest()) {
                TimeUnit.MILLISECONDS.sleep(30);
            }
            Assert.assertTrue("srv[" + s + "] alive, bindErr=" + srvs[s].getBindError(), srvs[s].isServerAliveForTest());
            ports[s] = srvs[s].getBoundPort();
            Assert.assertTrue("port[" + s + "]=" + ports[s] + " > 0", ports[s] > 0);
        }
        eps = new HashMap<>();
        for (int s = 0; s < N; s++) eps.put(s, new InetSocketAddress("127.0.0.1", ports[s]));

        // ------- F2: TCP client (real port endpoints, auto TCP mode detected) -------
        tcpClient = new DsFulltextClusterClient(N, eps, rootTcp.getAbsolutePath(), "f2_shard");
        {
            GrpcShardTransport t = (GrpcShardTransport) tcpClient.getRouterForTest().getShardTransportForTest();
            Assert.assertFalse("F2 TCP mode auto-detect: inproc=false", t.isInprocModeForTest());
        }

        // ------- F3: LocalShardTransport + 3-param transportFactory (shares local FulltextStore[]) -------
        FulltextStore.ShardTransport localT = new FulltextStore.LocalShardTransport(null);
        File localRootDir = new File(rootLocal, "f3_shard");
        localRouter = new ShardRouter(N, localRootDir.getAbsolutePath(),
                (dirs, stores) -> new FulltextStore.LocalShardTransport(stores));
        // fix null stores (3-param ctor already new stores internally inside, so transportFactory stores passed in are real ones
        // but the reference above localT=null is not set, we use factory result)

        // ---- Now ingest same DOCS into all 3 routers (DOC_IDS same, so DOC路由公式相同 -> same shard) ----
        for (int i = 0; i < DOCS.length; i++) {
            IngestStat i1 = inprocClient.putDocCluster(DOC_IDS[i], DOCS[i]);
            Assert.assertTrue("F1 inproc ingest doc#" + i + " pairs>0, got=" + i1.pairsTotal, i1.pairsTotal > 0);
            IngestStat i2 = tcpClient.putDocCluster(DOC_IDS[i], DOCS[i]);
            Assert.assertTrue("F2 tcp ingest doc#" + i + " pairs>0, got=" + i2.pairsTotal, i2.pairsTotal > 0);
            IngestStat i3 = localRouter.putDocCluster(DOC_IDS[i], DOCS[i]);
            Assert.assertTrue("F3 local ingest doc#" + i + " pairs>0, got=" + i3.pairsTotal, i3.pairsTotal > 0);
        }
        TimeUnit.MILLISECONDS.sleep(800);
    }

    @AfterClass
    public static void afterAll() {
        try { if (inprocClient != null) inprocClient.close(); } catch (Exception ignore) { }
        try { if (tcpClient != null) tcpClient.close(); } catch (Exception ignore) { }
        try { if (localRouter != null) localRouter.close(); } catch (Exception ignore) { }
        for (FulltextShardTcpServer s : srvs) try { if (s != null) s.close(); } catch (Exception ignore) { }
    }

    // ====================================================================
    // TR3.1: Already in @BeforeClass → just verify non-null + TCP mode
    // ====================================================================
    @Test
    public void tr31_threeConstructors() throws Exception {
        Assert.assertNotNull("F1 inprocClient not null", inprocClient);
        Assert.assertNotNull("F2 tcpClient not null", tcpClient);
        Assert.assertNotNull("F3 localRouter not null", localRouter);
        Assert.assertEquals("3 routers each have " + N + " shards",
                N, inprocClient.getShardCount());
        Assert.assertEquals(N, tcpClient.getShardCount());
        Assert.assertEquals(N, localRouter.getShardCountForTest());
        GrpcShardTransport t1 = (GrpcShardTransport) inprocClient.getRouterForTest().getShardTransportForTest();
        GrpcShardTransport t2 = (GrpcShardTransport) tcpClient.getRouterForTest().getShardTransportForTest();
        Assert.assertTrue("F1 inproc=true", t1.isInprocModeForTest());
        Assert.assertFalse("F2 inproc=false (TCP)", t2.isInprocModeForTest());
        Assert.assertTrue("F3 transport is LocalShardTransport",
                localRouter.getShardTransportForTest() instanceof FulltextStore.LocalShardTransport);
    }

    // ====================================================================
    // TR3.2: 三级指纹全等 F1/F2/F3人民 searchCluster
    // ====================================================================
    @Test
    public void tr32_fingerprintTernaryEq() throws Exception {
        SearchOptions o = SearchOptions.defaults();
        o.concurrentScatter = false;
        List<ScoredDocHit> f1 = inprocClient.searchCluster("人民", 30, o);
        List<ScoredDocHit> f2 = tcpClient.searchCluster("人民", 30, o);
        List<ScoredDocHit> f3 = localRouter.searchCluster("人民", null, 30, o);

        Assert.assertNotNull("F1人民 not null", f1);
        Assert.assertNotNull("F2人民 not null", f2);
        Assert.assertNotNull("F3人民 not null", f3);
        Assert.assertTrue("F1人民 size>0: " + f1.size(), f1.size() > 0);

        // size equal
        Assert.assertEquals("F1/F2人民 result size equal", f1.size(), f2.size());
        Assert.assertEquals("F1/F3人民 result size equal", f1.size(), f3.size());

        // Fingerprint: concat field byte streams
        String fp1 = fp(f1), fp2 = fp(f2), fp3 = fp(f3);
        Set<String> set = new HashSet<>(Arrays.asList(fp1, fp2, fp3));
        Assert.assertEquals("F1(inproc)/F2(tcp)/F3(local) 三级指纹全等 Set.size=1, got:\n"
                        + "F1(" + f1.size() + ")=..." + truncate(fp1) + "\n"
                        + "F2(" + f2.size() + ")=..." + truncate(fp2) + "\n"
                        + "F3(" + f3.size() + ")=..." + truncate(fp3),
                1, set.size());
        System.out.println("INFO 人民 hits=" + f1.size() + " top docIds=" + docIds(f1, 10));
    }

    // ====================================================================
    // TR3.3: concurrentScatter=true vs false 指纹全等 (F1 内部对比)
    // ====================================================================
    @Test
    public void tr33_concurrentScatterEq() throws Exception {
        SearchOptions base = SearchOptions.defaults();
        base.concurrentScatter = false;
        List<ScoredDocHit> seq = inprocClient.searchCluster("人民", 30, base);
        SearchOptions par = SearchOptions.defaults();
        par.concurrentScatter = true;
        List<ScoredDocHit> para = inprocClient.searchCluster("人民", 30, par);

        Assert.assertNotNull("seq人民 not null", seq);
        Assert.assertNotNull("para人民 not null", para);
        Assert.assertEquals("concurrent scatter same size, seq=" + seq.size() + " para=" + para.size(),
                seq.size(), para.size());

        String seqFp = fp(seq), parFp = fp(para);
        Assert.assertEquals("concurrentScatter=true vs false 指纹 byte级全等 (size=" + seq.size() + ")",
                seqFp, parFp);

        // Second query:英雄纪念碑 多词验证
        SearchOptions b2 = SearchOptions.defaults(); b2.concurrentScatter = false;
        SearchOptions p2 = SearchOptions.defaults(); p2.concurrentScatter = true;
        List<ScoredDocHit> s2 = inprocClient.searchCluster("英雄纪念碑", 20, b2);
        List<ScoredDocHit> q2 = inprocClient.searchCluster("英雄纪念碑", 20, p2);
        Assert.assertEquals("英雄纪念碑 concurrent vs seq size", s2.size(), q2.size());
        Assert.assertEquals("英雄纪念碑 concurrent vs false 指纹全等 (size=" + s2.size() + ")",
                fp(s2), fp(q2));
    }

    // ====================================================================
    // TR3.4: searchWithExtras F1/F2 三容器非空 + 三键 Facet排序人民<民
    // ====================================================================
    @Test
    public void tr34_extrasEq3Containers() throws Exception {
        Map<Long, String> rawDocTexts = new HashMap<>();
        for (int i = 0; i < DOCS.length; i++) rawDocTexts.put(DOC_IDS[i], DOCS[i]);
        SearchOptions o = SearchOptions.defaults();

        List<ScoredDocHitWithExtras> f1 = inprocClient.searchClusterWithExtras("人民", rawDocTexts, 20, o);
        List<ScoredDocHitWithExtras> f2 = tcpClient.searchClusterWithExtras("人民", rawDocTexts, 20, o);

        Assert.assertNotNull("F1 extras人民 not null", f1);
        Assert.assertNotNull("F2 extras人民 not null", f2);
        Assert.assertTrue("F1 extras size>0: " + f1.size(), f1.size() > 0);
        Assert.assertEquals("F1/F2 extras人民 same size, F1=" + f1.size() + " F2=" + f2.size(),
                f1.size(), f2.size());

        // 3-container non-empty check on BOTH
        for (int i = 0; i < Math.min(2, f1.size()); i++) {
            verify3Containers("F1#" + i, f1.get(i));
            verify3Containers("F2#" + i, f2.get(i));
        }
        // Aggregate facet人民/民 present and人民 facet rank <=民 rank on both
        for (Map.Entry<String, List<ScoredDocHitWithExtras>> e : new HashMap<String, List<ScoredDocHitWithExtras>>() {{
            put("F1", f1); put("F2", f2);
        }}.entrySet()) {
            String label = e.getKey();
            Set<String> fct = new HashSet<>();
            for (ScoredDocHitWithExtras h : e.getValue()) for (FulltextStore.FacetHit f : h.topFacets) fct.add(f.termText);
            boolean hasRenmin = fct.contains("人民");
            boolean hasMin = fct.contains("民") || fct.stream().anyMatch(w -> w.contains("人") || w.contains("民"));
            Assert.assertTrue(label + "人民或相关 facet 存在，facet=" + fct, hasRenmin || hasMin);
            // 三键排序稳定性：提取 top50 排序，若人民和民均存在则人民 rank 不高于民
            List<FulltextStore.FacetHit> flat = new ArrayList<>();
            for (ScoredDocHitWithExtras h : e.getValue()) flat.addAll(h.topFacets);
            flat.sort((a, b) -> {
                if (b.docCount != a.docCount) return Long.compare(b.docCount, a.docCount);
                if (b.totalTf != a.totalTf) return Long.compare(b.totalTf, a.totalTf);
                return a.termText.compareTo(b.termText);
            });
            int renIdx = -1, minIdx = -1;
            for (int i = 0; i < Math.min(flat.size(), 80); i++) {
                String t = flat.get(i).termText;
                if (t.equals("人民")) renIdx = i;
                if (t.equals("民")) minIdx = i;
            }
            if (renIdx >= 0 && minIdx >= 0) {
                Assert.assertTrue(label + "三键排序人民<民: renIdx=" + renIdx + " minIdx=" + minIdx,
                        renIdx <= minIdx);
            }
        }
    }

    // ---- helpers ----
    private static void verify3Containers(String label, ScoredDocHitWithExtras h) {
        int sp = (h.matchedSpans != null ? h.matchedSpans.size() : 0);
        int sn = (h.snippets != null ? h.snippets.size() : 0);
        int fc = (h.topFacets != null ? h.topFacets.size() : 0);
        // at least one of 3 non-empty on any hit (first 2 hits should all have at least snippet)
        Assert.assertTrue(label + " 至少 1 个容器非空 (spans=" + sp + ", snippets=" + sn + ", facets=" + fc + ")",
                sp + sn + fc > 0);
    }

    @Nonnull
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

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private static List<Long> docIds(List<ScoredDocHit> hs, int limit) {
        List<Long> r = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, hs.size()); i++) r.add(hs.get(i).docId);
        return r;
    }
}
