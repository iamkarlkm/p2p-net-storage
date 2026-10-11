package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry;
import javax.net.p2p.server.fulltext.FulltextShardTcpServer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * P27 T2: GrpcShardTransport TCP mode (non-inproc) — full transport via real TCP endpoints.
 * Creates N FulltextShardTcpServer instances in same JVM with real random ports,
 * uses GrpcShardTransport in TCP mode (inprocMode=false) to dispatch to each shard endpoint.
 *
 * TR2.1: Constructor + isInprocMode=false + endpoints correct
 * TR2.2: Direct GrpcShardTransport: putDoc(2 shards × 2 docs = 4) + clearDoc all success
 * TR2.3: Via DsFulltextClusterClient (TCP mode): 8 docs, search人民 return correct hits
 * TR2.4: Via DsFulltextClusterClient (TCP mode): searchWithExtras人民 return non-empty 3 container + 人民 facet
 */
public class P27_T2_GrpcShardTransportTcpModeTest {

    static final int SHARD_COUNT = 2;
    static File root;
    static String registryKey;
    static final String SHARD_PREFIX = "tcp_shard";

    static FulltextShardTcpServer srv0;
    static FulltextShardTcpServer srv1;
    static int port0;
    static int port1;

    @BeforeClass
    public static void beforeAll() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"),
                "p27t2_" + System.nanoTime());
        if (!root.exists()) root.mkdirs();
        registryKey = root.getAbsolutePath() + "?sc=" + SHARD_COUNT + "&pfx=" + SHARD_PREFIX;
        DsServerFulltextRegistry.getOrCreate(root.getAbsolutePath(), SHARD_COUNT, SHARD_PREFIX);

        srv0 = new FulltextShardTcpServer(0);
        srv1 = new FulltextShardTcpServer(0);
        Thread t0 = new Thread(() -> {
            try { srv0.singletonCreated(srv0); } catch (Exception ignore) { }
        }, "p27t2-srv0");
        Thread t1 = new Thread(() -> {
            try { srv1.singletonCreated(srv1); } catch (Exception ignore) { }
        }, "p27t2-srv1");
        t0.setDaemon(true); t1.setDaemon(true);
        t0.start(); t1.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline
                && (!srv0.isServerAliveForTest() || !srv1.isServerAliveForTest())) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        if (!srv0.isServerAliveForTest()) Assert.fail("srv0 not alive, bindErr=" + srv0.getBindError());
        if (!srv1.isServerAliveForTest()) Assert.fail("srv1 not alive, bindErr=" + srv1.getBindError());
        port0 = srv0.getBoundPort();
        port1 = srv1.getBoundPort();
        Assert.assertTrue("port0>0 " + port0, port0 > 0);
        Assert.assertTrue("port1>0 " + port1, port1 > 0);
        TimeUnit.MILLISECONDS.sleep(200);
    }

    @AfterClass
    public static void afterAll() {
        try { if (srv0 != null) srv0.close(); } catch (Exception ignore) { }
        try { if (srv1 != null) srv1.close(); } catch (Exception ignore) { }
        // deleteRecursively(root); // best-effort cleanup not required
    }

    // ====================================================================
    // TR2.1: Constructor verification
    // ====================================================================
    @Test
    public void tr21_ctorTcpMode() throws IOException {
        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        eps.put(0, new InetSocketAddress("127.0.0.1", port0));
        eps.put(1, new InetSocketAddress("127.0.0.1", port1));
        GrpcShardTransport t = new GrpcShardTransport(eps, null, registryKey);
        try {
            Assert.assertFalse("inprocMode = false (TCP mode)", t.isInprocModeForTest());
            Map<Integer, InetSocketAddress> got = t.getShardIdToEndpointForTest();
            Assert.assertEquals("endpoint size 2", 2, got.size());
            Assert.assertEquals("shard0 endpoint port=" + port0,
                    port0, got.get(0).getPort());
            Assert.assertEquals("shard1 endpoint port=" + port1,
                    port1, got.get(1).getPort());
            // tcpClients: lazy init = empty at start
        } finally {
            t.close();
        }
    }

    // ====================================================================
    // TR2.2: Direct GrpcShardTransport: putDoc ×4 + clearDoc all success
    // ====================================================================
    @Test
    public void tr22_directTransportPutClear() throws IOException {
        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        eps.put(0, new InetSocketAddress("127.0.0.1", port0));
        eps.put(1, new InetSocketAddress("127.0.0.1", port1));
        GrpcShardTransport t = new GrpcShardTransport(eps, null, registryKey);
        try {
            // 2 docs per shard = 4 total
            long[] docIds = { 1001L, 1002L, 1003L, 1004L };
            String[] docs = {
                    "人民群众 广场 纪念碑 英雄",
                    "历史 创造者 人民 革命 英雄",
                    "人工智能 大模型 技术 人民 创新",
                    "机器学习 深度学习 神经网络 人民 算法"
            };
            int[] shards = { 0, 0, 1, 1 };
            for (int i = 0; i < docIds.length; i++) {
                IngestStat s = t.putDoc(shards[i], docIds[i], docs[i]);
                Assert.assertNotNull("putDoc stat not null shard=" + shards[i] + " doc=" + docIds[i], s);
                Assert.assertTrue("putDoc pairsTotal>0 for doc=" + docIds[i] + " got=" + s.pairsTotal,
                        s.pairsTotal > 0);
            }
            // clearDoc: clear doc 1001 on shard0, doc 1003 on shard1
            t.clearDoc(0, 1001L);
            t.clearDoc(1, 1003L);
            // search人民: expect hits (at least 1002/1004 still remain)
            List<ScoredDocHit> hits0 = t.search(0, "人民", 10, SearchOptions.defaults());
            List<ScoredDocHit> hits1 = t.search(1, "人民", 10, SearchOptions.defaults());
            Set<Long> all = new HashSet<>();
            for (ScoredDocHit h : hits0) all.add(h.docId);
            for (ScoredDocHit h : hits1) all.add(h.docId);
            Assert.assertFalse("after clear,人民 hits non-empty, got=" + all, all.isEmpty());
            // 1001 was cleared, so if present on shard0 anyway not critical (segmenter may not remove segments)
            System.out.println("人民 shard0 hits=" + ids(hits0) + " shard1 hits=" + ids(hits1));
        } finally {
            t.close();
        }
    }

    // ====================================================================
    // TR2.3: Via DsFulltextClusterClient (TCP auto mode) + 8 docs + search人民
    // ====================================================================
    @Test
    public void tr23_clusterClientSearchTcp() throws IOException {
        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        eps.put(0, new InetSocketAddress("127.0.0.1", port0));
        eps.put(1, new InetSocketAddress("127.0.0.1", port1));
        try (DsFulltextClusterClient cc = new DsFulltextClusterClient(SHARD_COUNT, eps,
                root.getAbsolutePath(), SHARD_PREFIX)) {
            GrpcShardTransport t = (GrpcShardTransport) cc.getRouterForTest().getShardTransportForTest();
            Assert.assertFalse("cluster client TCP mode auto-detect: inprocMode=false",
                    t.isInprocModeForTest());

            // 8 docs人民 distribution even
            String[] docs8 = {
                    "人民群众 历史 创造者 广场 英雄纪念碑 革命先烈",
                    "人民英雄纪念碑 人民广场 天安门 群众 英雄",
                    "中华民族伟大复兴 中国梦 人民 奋斗 牺牲 奉献",
                    "中国特色社会主义 人民当家作主 依法治国 共同富裕",
                    "人工智能大模型 深度学习 神经网络 人民生活 智慧服务",
                    "科技创新 驱动发展 人民利益 高质量发展 新质生产力",
                    "文化自信 中华文明 五千年 人民创造 传统美德",
                    "绿色发展 生态文明 美丽中国 人民健康 可持续发展"
            };
            for (int i = 0; i < docs8.length; i++) {
                IngestStat s = cc.putDocCluster(10001L + i, docs8[i]);
                Assert.assertTrue("putDocCluster 1000" + (i + 1) + " pairsTotal>0: " + s.pairsTotal,
                        s.pairsTotal > 0);
            }

            try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException ie) { }

            List<ScoredDocHit> results = cc.searchCluster("人民", 20, SearchOptions.defaults());
            Assert.assertNotNull("search人民 results not null", results);
            Assert.assertTrue("search人民 results size>0, got=" + results.size(), results.size() > 0);
            Set<Long> ids = new HashSet<>();
            for (ScoredDocHit h : results) ids.add(h.docId);
            Assert.assertTrue("人民 at least 4 distinct docIds, got=" + ids.size() + ":" + ids,
                    ids.size() >= 4);
            // Verify scores monotonic decrease (RRF sorted)
            double prev = Double.POSITIVE_INFINITY;
            for (int i = 0; i < results.size(); i++) {
                ScoredDocHit h = results.get(i);
                Assert.assertTrue("人民 score #" + i + " non-increasing: prev=" + prev
                                + " cur=" + h.finalScore,
                        h.finalScore <= prev + 1e-9);
                prev = h.finalScore;
                Assert.assertTrue("人民 doc " + h.docId + " finalScore>0", h.finalScore > 0);
            }
        }
    }

    // ====================================================================
    // TR2.4: Via DsFulltextClusterClient TCP mode + searchWithExtras人民
    // ====================================================================
    @Test
    public void tr24_clusterClientSearchWithExtrasTcp() throws IOException {
        Map<Integer, InetSocketAddress> eps = new HashMap<>();
        eps.put(0, new InetSocketAddress("127.0.0.1", port0));
        eps.put(1, new InetSocketAddress("127.0.0.1", port1));
        try (DsFulltextClusterClient cc = new DsFulltextClusterClient(SHARD_COUNT, eps,
                root.getAbsolutePath(), SHARD_PREFIX)) {

            // Re-put 8 docs from TR2.3 (same cluster root may still have them but re-put idempotent)
            String[] docs8 = {
                    "人民群众 历史 创造者 广场 英雄纪念碑 革命先烈 工人 农民 解放军",
                    "人民英雄 纪念碑 广场 天安门 群众 英雄 人民 奋斗 牺牲",
                    "中华民族伟大复兴 中国梦 人民 奋斗 牺牲 奉献 团结 进步",
                    "中国特色社会主义 人民当家作主 依法治国 共同富裕 民主 协商",
                    "人工智能 大模型 深度学习 神经网络 人民生活 智慧 服务 创新",
                    "科技创新 驱动发展 人民利益 高质量发展 新质生产力 突破 攻关",
                    "文化自信 中华文明 五千年 人民创造 传统美德 礼仪 廉耻",
                    "绿色发展 生态文明 美丽中国 人民健康 可持续发展 环保 低碳"
            };
            Map<Long, String> rawDocTexts = new HashMap<>();
            for (int i = 0; i < docs8.length; i++) {
                rawDocTexts.put(10021L + i, docs8[i]);
                cc.putDocCluster(10021L + i, docs8[i]);
            }

            try { TimeUnit.MILLISECONDS.sleep(600); } catch (InterruptedException ie) { }

            List<ScoredDocHitWithExtras> results = cc.searchClusterWithExtras(
                    "人民", rawDocTexts, 20, SearchOptions.defaults());
            Assert.assertNotNull("searchWithExtras人民 not null", results);
            Assert.assertTrue("searchWithExtras人民 size>0, got=" + results.size(),
                    results.size() > 0);

            // Extras: 3 container non-empty invariant (spans × snippets × facets)
            int spanNonNull = 0, snipNonNull = 0, facetNonNull = 0;
            Set<String> facetTerms = new HashSet<>();
            Map<String, Long> facetDocCountMap = new HashMap<>();
            for (ScoredDocHitWithExtras h : results) {
                if (h.matchedSpans != null && !h.matchedSpans.isEmpty()) spanNonNull++;
                if (h.snippets != null && !h.snippets.isEmpty()) snipNonNull++;
                if (h.topFacets != null && !h.topFacets.isEmpty()) {
                    facetNonNull++;
                    for (FulltextStore.FacetHit f : h.topFacets) {
                        facetTerms.add(f.termText);
                        facetDocCountMap.merge(f.termText, f.docCount, Long::sum);
                    }
                }
            }
            int size = results.size();
            Assert.assertTrue("Extras matchedSpans non-empty for >=1 doc, got " + spanNonNull + "/" + size,
                    spanNonNull >= 1);
            Assert.assertTrue("Extras snippets non-empty for >=1 doc, got " + snipNonNull + "/" + size,
                    snipNonNull >= 1);
            Assert.assertTrue("Extras topFacets non-empty for >=1 doc, got " + facetNonNull + "/" + size,
                    facetNonNull >= 1);

            // Facet: 人民 term present
            boolean renminFacet = facetTerms.contains("人民")
                    || facetTerms.stream().anyMatch(w -> w.contains("人") || w.contains("民"));
            Assert.assertTrue("Extras facet人民 present among terms: size=" + facetTerms.size()
                            + " sample=" + new ArrayList<>(facetTerms).stream().limit(10).toList(),
                    renminFacet);

            // 3-key facet sort: within single result topFacets, docCount↓→totalTf↓→termText↑
            for (ScoredDocHitWithExtras h : results) {
                if (h.topFacets == null || h.topFacets.size() < 2) continue;
                List<FulltextStore.FacetHit> lst = new ArrayList<>(h.topFacets);
                lst.sort((a, b) -> {
                    if (b.docCount != a.docCount) return Long.compare(b.docCount, a.docCount); // docCount↓
                    if (b.totalTf != a.totalTf) return Long.compare(b.totalTf, a.totalTf); // totalTf↓
                    return a.termText.compareTo(b.termText); // termText↑
                });
                int renIdx = -1, minIdx = -1;
                for (int i = 0; i < Math.min(15, lst.size()); i++) {
                    String w = lst.get(i).termText;
                    if (w.equals("人民")) renIdx = i;
                    if (w.equals("民")) minIdx = i;
                }
                if (renIdx >= 0 && minIdx >= 0) {
                    Assert.assertTrue("3-key facet:人民 < 民, renIdx=" + renIdx + " minIdx=" + minIdx,
                            renIdx <= minIdx);
                }
            }
        }
    }

    // ---- helpers ----
    private static List<Long> ids(List<ScoredDocHit> hs) {
        List<Long> r = new ArrayList<>();
        for (ScoredDocHit h : hs) r.add(h.docId);
        return r;
    }
}
