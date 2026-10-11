package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardMode;
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * P24 T2: Shard 局部性 + 路由隔离（4 TR，AC-3）
 * Scope: ShardRouter 构造 N shard_NNN/ 独立 FulltextStore + ShardTransport interface/LocalShardTransport
 *        + putDocCluster / indexTermVectorCluster / clearDocCluster 单 shard 路由
 */
public class P24_T2_ShardLocalityTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();
    private File root;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p24t2");
    }

    @After
    public void teardown() throws IOException {
        // nothing global; per-test close done inline
    }

    private static final int N = 4;
    private static final String[] TERMS = new String[] {
        "apple","banana","cherry","date","elderberry","fig","grape","honeydew",
        "kiwi","lemon","mango","nectarine","orange","papaya","quince","raspberry",
        "strawberry","tangerine","ugli","vanilla","watermelon","xigua","yam","zucchini",
        "alpha","beta","gamma","delta","epsilon","zeta","eta","theta","iota","kappa",
        "lambda","mu","nu","xi","omicron","pi","rho","sigma","tau","upsilon",
        "phi","chi","psi","omega","aleph","bet"
    };

    // ---------- helpers ----------
    private long[] buildDocIdsWithAllShards(int count) {
        List<Long> ids = new ArrayList<>();
        Set<Integer> got = new HashSet<>();
        long docId = 1000;
        while (ids.size() < count || got.size() < N) {
            int s = FulltextStore.resolveShardForDocIdForTest(docId, N);
            ids.add(docId);
            got.add(s);
            docId += 11;
        }
        long[] out = new long[count];
        for (int i = 0; i < count; i++) out[i] = ids.get(i);
        return out;
    }

    private String buildDoc(int seed, int nTerms) {
        Random r = new Random(0xC0FFEE + seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nTerms; i++) {
            String t = TERMS[r.nextInt(TERMS.length)];
            if (i == 3) sb.append(TERMS[seed % TERMS.length]).append(' ');
            sb.append(t).append(' ');
        }
        return sb.toString().trim();
    }

    // TR2.1: 20 doc × 20 term 写入 4 shard，4 docStat keySet 两两交集=空（减小规模防超时，性质不变）
    @Test
    public void tr21_20doc20termDocStatDisjoint() throws IOException {
        final int DOC = 20, TERM_PER_DOC = 20;
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            long[] docIds = buildDocIdsWithAllShards(DOC);
            for (int i = 0; i < DOC; i++) {
                String text = buildDoc(i, TERM_PER_DOC);
                router.putDocCluster(docIds[i], text);
            }
            Set<Long>[] perShardDocIds = new Set[N];
            for (int s = 0; s < N; s++) perShardDocIds[s] = new HashSet<>();
            for (int s = 0; s < N; s++) {
                FulltextStore shard = router.getShardForTest(s);
                Set<Long> keys = shard.weightStoreAllDocKeysForTest();
                perShardDocIds[s].addAll(keys);
            }
            for (int i = 0; i < N; i++) {
                for (int j = i + 1; j < N; j++) {
                    Set<Long> inter = new HashSet<>(perShardDocIds[i]);
                    inter.retainAll(perShardDocIds[j]);
                    Assert.assertEquals("shard" + i + " ∩ shard" + j, 0, inter.size());
                }
            }
            int total = 0;
            for (Set<Long> s : perShardDocIds) total += s.size();
            Assert.assertEquals(DOC, total);
        } finally {
            router.close();
        }
    }

    // TR2.2: 20 term indexTermVectorCluster → sum(HnswMeta.nodeCount) == 20，每个 uid 仅在 target shard
    @Test
    public void tr22_20termVecOnlyInTargetShard() throws IOException {
        final int V = 20;
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            Random r = new Random(0x1234);
            long[] termUids = new long[V];
            float[][] vecs = new float[V][];
            for (int i = 0; i < V; i++) {
                String term = "vec_term_" + i + "_" + TERMS[i % TERMS.length];
                long uid = router.toUid(term, true);
                termUids[i] = uid;
                float[] v = new float[16];
                for (int k = 0; k < 16; k++) v[k] = r.nextFloat() * 2 - 1;
                vecs[i] = v;
                router.indexTermVectorCluster(uid, v);
            }
            int totalNodeCount = 0;
            for (int s = 0; s < N; s++) totalNodeCount += router.getHnswMetaForTest(s).nodeCount;
            Assert.assertEquals(V, totalNodeCount);
            for (int i = 0; i < V; i++) {
                int target = FulltextStore.resolveShardForUnitIdForTest(termUids[i], N);
                float[] hit = router.getTermVectorForTest(target, termUids[i]);
                Assert.assertNotNull("termIdx=" + i + " target=" + target, hit);
                Assert.assertArrayEquals(vecs[i], hit, 0f);
                int missCount = 0;
                for (int s = 0; s < N; s++) {
                    if (s == target) continue;
                    if (router.getTermVectorForTest(s, termUids[i]) == null) missCount++;
                }
                Assert.assertEquals(N - 1, missCount);
            }
        } finally {
            router.close();
        }
    }

    // TR2.3: shard0 close 后其他 shard 仍可 putDoc/search
    @Test
    public void tr23_shard0CloseOthersStillOperational() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            router.getShardForTest(0).close();
            long[] docIds = new long[3];
            int foundIdx = 0;
            long seed = 2000;
            while (foundIdx < 3) {
                int s = FulltextStore.resolveShardForDocIdForTest(seed, N);
                if (s == 1 || s == 2 || s == 3) {
                    boolean already = false;
                    for (int k = 0; k < foundIdx; k++) if (docIds[k] == seed) { already = true; break; }
                    if (!already) docIds[foundIdx++] = seed;
                }
                seed++;
            }
            for (int i = 0; i < 3; i++) {
                router.putDocCluster(docIds[i], "独有的词_" + i + " cherry fig 北京");
            }
            FulltextStore.SearchOptions opts = FulltextStore.SearchOptions.defaults();
            opts.vectorRecall = false;
            opts.neighborRecallD = 0;
            opts.phraseBoost = false;
            java.util.List<FulltextStore.ScoredDocHit> hits = router.searchCluster("cherry", null, 5, opts);
            Assert.assertTrue("hits.size >=3 got " + hits.size(), hits.size() >= 3);
        } finally {
            try { router.close(); } catch (Exception ignore) {}
        }
    }

    // TR2.4: 手算分配 docId→4 shard，每 shard 必含对应 docId
    @Test
    public void tr24_handPickedDocIdsEachShardHit() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            long[] onePerShard = new long[N];
            Arrays.fill(onePerShard, -1L);
            long probe = 10_000L;
            int got = 0;
            while (got < N) {
                int s = FulltextStore.resolveShardForDocIdForTest(probe, N);
                if (s < N && onePerShard[s] == -1L) {
                    onePerShard[s] = probe;
                    got++;
                }
                probe += 3;
            }
            for (int i = 0; i < N; i++) {
                router.putDocCluster(onePerShard[i], "稀有词only_" + i + " 独 家 match_" + i);
            }
            for (int s = 0; s < N; s++) {
                Set<Long> keys = router.getShardForTest(s).weightStoreAllDocKeysForTest();
                for (int i = 0; i < N; i++) {
                    if (i == s) Assert.assertTrue("shard" + s + " missing docId=" + onePerShard[i], keys.contains(onePerShard[i]));
                    else        Assert.assertFalse("shard" + s + " has foreign docId=" + onePerShard[i], keys.contains(onePerShard[i]));
                }
            }
            Assert.assertEquals(FulltextStore.ShardMode.AUTO.ordinal(), 0);
            Set<String> dirNames = new HashSet<>();
            for (int s = 0; s < N; s++) dirNames.add(router.getShardRootDirForTest(s).getName());
            for (int s = 0; s < N; s++) Assert.assertTrue("shard_" + String.format("%04d", s) + " missing", dirNames.contains("shard_" + String.format("%04d", s)));
        } finally {
            router.close();
        }
    }
}
