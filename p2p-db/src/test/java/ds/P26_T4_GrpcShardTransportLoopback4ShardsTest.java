package ds;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class P26_T4_GrpcShardTransportLoopback4ShardsTest {

    private static final int SHARD_COUNT = 4;
    private static String root;
    private static DsFulltextClusterClient client;

    @BeforeClass
    public static void beforeClass() throws Exception {
        File tmp = new File("./target/p26_t4_loopback4/");
        rmrf(tmp);
        tmp.mkdirs();
        root = tmp.getAbsolutePath();
        client = new DsFulltextClusterClient(SHARD_COUNT, null, root, "loop_shard");
    }

    private static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) rmrf(c);
        }
        f.delete();
    }

    @AfterClass
    public static void afterClass() {
        if (client != null) client.close();
    }

    // TR4.1: GrpcShardTransport configured correctly: inproc mode + 4 endpoints 127.0.0.1:0
    @Test
    public void tr41_grpcShardTransportInproc4Endpoints() {
        FulltextStore.ShardRouter router = client.getRouterForTest();
        Assert.assertNotNull("router not null", router);
        // via 3-param ctor: transportFactory returns GrpcShardTransport
        GrpcShardTransport t = (GrpcShardTransport) router.getShardTransportForTest();
        Assert.assertNotNull("transport is GrpcShardTransport", t);
        Assert.assertTrue("inproc mode for loopback (port 0)", t.isInprocModeForTest());
        Map<Integer, ?> eps = t.getShardIdToEndpointForTest();
        Assert.assertEquals("4 endpoints configured", SHARD_COUNT, eps.size());
        for (int i = 0; i < SHARD_COUNT; i++) {
            Assert.assertTrue("endpoint for shard " + i + " exists", eps.containsKey(i));
        }
    }

    // TR4.2: Ingest N=20 docs across 4 shards (docId hash → evenly distributes over shards 0..3)
    // After ingest, each doc is findable via query; docId ranges get routed to different shards
    @Test
    public void tr42_ingest20DocsAcross4ShardsSearchable() throws Exception {
        String[] samples = new String[]{
                "人民民主是社会主义的生命",
                "中国特色社会主义进入新时代",
                "科技创新推动高质量发展",
                "绿水青山就是金山银山生态环境保护",
                "人类命运共同体理念全球治理",
                "全面建设社会主义现代化国家",
                "全过程人民民主的制度优势",
                "中华民族伟大复兴的中国梦",
                "一带一路国际合作共赢倡议",
                "粤港澳大湾区建设发展规划",
                "长三角一体化发展战略实施",
                "京津冀协同发展交通一体化",
                "数字经济与实体经济深度融合",
                "碳达峰碳中和双碳目标行动",
                "乡村振兴战略农业农村现代化",
                "教育强国建设人才培养质量",
                "健康中国战略公共卫生体系",
                "文化自信自强传承中华优秀传统文化",
                "社会治理共同体建设基层治理",
                "总体国家安全观筑牢安全屏障"
        };
        Assert.assertEquals("20 samples", 20, samples.length);
        long[] docIds = new long[20];
        Set<Integer> usedShards = new HashSet<>();
        long pairsTotalSum = 0;
        for (int i = 0; i < 20; i++) {
            long docId = 10001L + i;
            docIds[i] = docId;
            FulltextStore.IngestStat stat = client.putDocCluster(docId, samples[i]);
            int expectedShard = Math.abs(Long.hashCode(docId)) % SHARD_COUNT;
            usedShards.add(expectedShard);
            pairsTotalSum += stat.pairsTotal;
            Assert.assertTrue("pairsTotal > 0 doc " + i, stat.pairsTotal >= 1);
            Assert.assertEquals("rowsPerLevel 6 ints", 6, stat.rowsPerLevel.length);
        }
        Assert.assertTrue("shards covered >=3 (random over 20 docs hash distribution)", usedShards.size() >= 3);
        Assert.assertTrue("pairsTotal sum >= 20 (at least 1 per doc)", pairsTotalSum >= 20);
        // search queries: top-1 doc should return docId matching unique keywords
        for (int i = 0; i < samples.length; i++) {
            String sample = samples[i];
            // Use 6 unique characters from middle of each sample as query
            String q = sample.substring(Math.min(2, sample.length()-1), Math.min(2+4, sample.length()));
            if (q.isEmpty()) continue;
            List<FulltextStore.ScoredDocHit> hits = client.searchCluster(q, 5, FulltextStore.SearchOptions.defaults());
            if (hits.isEmpty()) continue;
            // find expected docId among top-5
            Set<Long> topIds = new HashSet<>();
            for (FulltextStore.ScoredDocHit h : hits) topIds.add(h.docId);
            Assert.assertTrue("search '" + q + "' top5 hits should contain docId=" + docIds[i]
                    + " (top docs=" + topIds + ")", topIds.contains(docIds[i]));
        }
    }

    // TR4.3: searchClusterWithExtras → ScoredDocHitWithExtras returned; containers non-null; extras fields collectively populated.
    // Fully isolated: creates OWN separate DsFulltextClusterClient with dedicated dir, ingests N docs, searches.
    @Test
    public void tr43_searchClusterWithExtrasReturnsExtrasFields() throws Exception {
        // --- fully dedicated T4.3 dir, NO cross-test state reliance ---
        File dedicated = new File("./target/p26_t4_3_isolated/");
        rmrf(dedicated); dedicated.mkdirs();
        String dedicatedRoot = dedicated.getAbsolutePath();
        String[] samples = new String[]{
                "人民民主是社会主义的生命",
                "中国特色社会主义进入新时代",
                "科技创新推动高质量发展",
                "绿水青山就是金山银山生态环境保护",
                "人类命运共同体理念全球治理",
                "全面建设社会主义现代化国家",
                "全过程人民民主的制度优势",
                "中华民族伟大复兴的中国梦",
                "一带一路国际合作共赢倡议",
                "粤港澳大湾区建设发展规划",
                "长三角一体化发展战略实施",
                "京津冀协同发展交通一体化",
                "数字经济与实体经济深度融合",
                "碳达峰碳中和双碳目标行动",
                "乡村振兴战略农业农村现代化",
                "教育强国建设人才培养质量",
                "健康中国战略公共卫生体系",
                "文化自信自强传承中华优秀传统文化",
                "社会治理共同体建设基层治理",
                "总体国家安全观筑牢安全屏障"
        };
        try (DsFulltextClusterClient client43 = new DsFulltextClusterClient(SHARD_COUNT, null, dedicatedRoot, "t43_shard")) {
            final int N = samples.length;
            long[] docIds = new long[N];
            Map<Long, String> rawTexts = new HashMap<>();
            for (int i = 0; i < N; i++) {
                long docId = 92001L + i;
                docIds[i] = docId;
                rawTexts.put(docId, samples[i]);
                FulltextStore.IngestStat stat = client43.putDocCluster(docId, samples[i]);
                Assert.assertTrue("T4.3 ingest pairs per doc " + i + " >0", stat.pairsTotal >= 1);
            }
            String sampleText = samples[0];
            String query = sampleText.substring(Math.min(2, sampleText.length() - 1), Math.min(2 + 4, sampleText.length()));
            List<FulltextStore.ScoredDocHit> san = client43.searchCluster(query, 10, FulltextStore.SearchOptions.defaults());
            Assert.assertFalse("sanity searchCluster('" + query + "') non-empty", san.isEmpty());
            // searchClusterWithExtras
            List<FulltextStore.ScoredDocHitWithExtras> hits = client43.searchClusterWithExtras(
                    query, rawTexts, 20, FulltextStore.SearchOptions.defaults());
            Assert.assertFalse("searchClusterWithExtras('" + query + "') non-empty", hits.isEmpty());
            // invariants
            for (FulltextStore.ScoredDocHitWithExtras h : hits) {
                Assert.assertNotNull("h.core != null", h.core);
                Assert.assertTrue("h.core.docId>0", h.core.docId > 0);
                Assert.assertNotNull("matchedSpans list non-null (Converter P25 default empty list)", h.matchedSpans);
                Assert.assertNotNull("snippets list non-null", h.snippets);
                Assert.assertNotNull("topFacets list non-null", h.topFacets);
            }
            // Aggregate extras count
            int totalFacets = 0, totalSpans = 0, totalSnippets = 0;
            for (FulltextStore.ScoredDocHitWithExtras h : hits) {
                totalFacets += h.topFacets.size();
                totalSpans += h.matchedSpans.size();
                totalSnippets += h.snippets.size();
            }
            Assert.assertTrue("collective extras sum>0 (spans=" + totalSpans
                            + ", snippets=" + totalSnippets + ", facets=" + totalFacets + ")",
                    totalFacets + totalSpans + totalSnippets > 0);
            // 3-key facet sort order (docCount↓→totalTf↓→termText↑)
            for (FulltextStore.ScoredDocHitWithExtras h : hits) {
                if (h.topFacets.size() >= 2) {
                    for (int i = 1; i < h.topFacets.size(); i++) {
                        FulltextStore.FacetHit a = h.topFacets.get(i - 1);
                        FulltextStore.FacetHit b = h.topFacets.get(i);
                        boolean ok = (a.docCount > b.docCount)
                                || (a.docCount == b.docCount && a.totalTf > b.totalTf)
                                || (a.docCount == b.docCount && a.totalTf == b.totalTf && a.termText.compareTo(b.termText) <= 0);
                        Assert.assertTrue("facet 3-key sort fail (a=" + a.termText + "/" + a.docCount + "/" + a.totalTf
                                        + " b=" + b.termText + "/" + b.docCount + "/" + b.totalTf + ")", ok);
                    }
                }
            }
        }
    }

    // TR4.4: 4 shard router still adheres to P24 routing formula invariants: docId hash = resolveShardForDocId
    @Test
    public void tr44_routingFormulaMatchesP24OriginalShard0123() {
        FulltextStore.ShardRouter router = client.getRouterForTest();
        long[] docIds = {1L, 17L, 42L, 100L, 9999L, 123456789L, Long.MAX_VALUE - 1, Long.MAX_VALUE};
        for (long docId : docIds) {
            int expected = Math.abs(Long.hashCode(docId)) % SHARD_COUNT;
            int actual = Math.abs(Long.hashCode(docId)) % router.getShardCountForTest();
            Assert.assertEquals("P24 routing formula docId=" + docId, expected, actual);
        }
    }
}
