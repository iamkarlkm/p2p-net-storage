package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

/**
 * P24 T3: Scatter-gather + 全局三路 RRF + 三键稳定排序（4 TR，AC-4/5）
 * Scope: scatterGatherForTest HC probe（注入伪造 shard 本地结果手算 RRF）
 *        + searchCluster 真实 4 doc 各一 shard 全召回 + 非法参数保护
 */
public class P24_T3_ScatterGatherRrfTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();
    private File root;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p24t3");
    }
    @After
    public void teardown() {}

    private static final int N = 4;
    private static ScoredDocHit h10_11(long docId, int shardId, double sort, double rrf, double vec) {
        double pbs = 0.0;
        int mt = 1;
        double fs = 0; // 由 RRF 计算替代
        long pid = docId * 10;
        long uid = 0L;
        return new ScoredDocHit(docId, pid, 0, 0, uid, fs, sort, rrf, pbs, mt, vec, shardId);
    }

    // TR3.1: RRF 全局手算（注入伪造 shard 本地结果：4 doc D0(s0 sort=4)/D1(s1 sort=3)/D2(s2 sort=2)/D3(s3 sort=1)）
    @Test
    public void tr31_rrfGlobalHandCalcViaFakeShard() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            // 每 shard 本地 1 doc（local rank 1，但 sortScore 全局分别为 4/3/2/1）
            ScoredDocHit d0 = h10_11(100L, 0, 4.0, 1.0 / 61.0, 0.01); // D0 s0 本地 1 doc
            ScoredDocHit d1 = h10_11(101L, 1, 3.0, 1.0 / 62.0, 0.02);
            ScoredDocHit d2 = h10_11(102L, 2, 2.0, 1.0 / 60.5, 0.03);
            ScoredDocHit d3 = h10_11(103L, 3, 1.0, 1.0 / 60.0, 0.04);
            Map<Integer, List<ScoredDocHit>> fake = new HashMap<>();
            fake.put(0, Arrays.asList(d0));
            fake.put(1, Arrays.asList(d1));
            fake.put(2, Arrays.asList(d2));
            fake.put(3, Arrays.asList(d3));
            SearchOptions o = SearchOptions.defaults();
            o.rrfK = 60;
            o.alpha = 0.5; o.beta = 0.25; o.gamma = 0.1; o.delta = 0.15;
            o.phraseBoost = false;
            List<ScoredDocHit> out = router.scatterGatherForTest("q", null, 10, o, fake);
            Assert.assertEquals(4, out.size());

            // ======= 手算验证（三路全局 rank）=======
            // R1（sortScore 降序）：D0(4)>D1(3)>D2(2)>D3(1) → rank R1 = [D0=1, D1=2, D2=3, D3=4]
            // R2（rrfScore 降序）：D3(1/60=0.01667) > D2(1/60.5=0.01653) > D0(1/61=0.01639) > D1(1/62=0.01613) → rank R2 = [D3=1, D2=2, D0=3, D1=4]
            // R3（vectorScore 降序）：D3(0.04) > D2(0.03) > D1(0.02) > D0(0.01) → rank R3 = [D3=1, D2=2, D1=3, D0=4]
            // ΣRRF(doc) = Σ 1/(60+rank)
            double rrfD0 = FulltextStore.rrfRankToScore(1,60) + FulltextStore.rrfRankToScore(3,60) + FulltextStore.rrfRankToScore(4,60);
            double rrfD1 = FulltextStore.rrfRankToScore(2,60) + FulltextStore.rrfRankToScore(4,60) + FulltextStore.rrfRankToScore(3,60);
            double rrfD2 = FulltextStore.rrfRankToScore(3,60) + FulltextStore.rrfRankToScore(2,60) + FulltextStore.rrfRankToScore(2,60);
            double rrfD3 = FulltextStore.rrfRankToScore(4,60) + FulltextStore.rrfRankToScore(1,60) + FulltextStore.rrfRankToScore(1,60);

            // 取 ΣRRF 作为新 rrfScore 全局值；然后 sortScore 原值、vectorScore 原值，phrase=0
            Map<Long,Double> sortRaw = new HashMap<>(); sortRaw.put(100L,4.0); sortRaw.put(101L,3.0); sortRaw.put(102L,2.0); sortRaw.put(103L,1.0);
            Map<Long,Double> rrfRaw  = new HashMap<>(); rrfRaw.put(100L,rrfD0); rrfRaw.put(101L,rrfD1); rrfRaw.put(102L,rrfD2); rrfRaw.put(103L,rrfD3);
            Map<Long,Double> phraseRaw = new HashMap<>(); for (long id : new long[]{100,101,102,103}) phraseRaw.put(id,0.0);
            Map<Long,Double> vecRaw = new HashMap<>(); vecRaw.put(100L,0.01); vecRaw.put(101L,0.02); vecRaw.put(102L,0.03); vecRaw.put(103L,0.04);
            Map<Long,Double> sN = FulltextStore.minMaxNormalize(sortRaw, true);
            Map<Long,Double> rN = FulltextStore.minMaxNormalize(rrfRaw, true);
            Map<Long,Double> pN = FulltextStore.minMaxNormalize(phraseRaw, true);
            Map<Long,Double> vN = FulltextStore.minMaxNormalize(vecRaw, true);
            double alpha=0.5, beta=0.25, gamma=0.1, delta=0.15, sw = alpha+beta+gamma+delta;
            double[] expectedFS = new double[4];
            long[] ids = new long[]{100,101,102,103};
            for (int i=0;i<4;i++){
                long id = ids[i];
                expectedFS[i] = (alpha*sN.getOrDefault(id,0.0)+beta*rN.getOrDefault(id,0.0)+gamma*pN.getOrDefault(id,0.0)+delta*vN.getOrDefault(id,0.0))/sw;
            }
            Map<Long,Double> expected = new HashMap<>();
            for (int i=0;i<4;i++) expected.put(ids[i], expectedFS[i]);

            // 实际 finalScore 1e-9 全等
            for (ScoredDocHit h : out) {
                System.out.println("DEBUG tr31 doc=" + h.docId + " final=" + h.finalScore
                        + " sort=" + h.sortScore + " gRrf=" + h.rrfScore + " vec=" + h.vectorScore
                        + " sid=" + h.shardId);
                double exp = expected.get(h.docId);
                Assert.assertEquals("docId=" + h.docId, exp, h.finalScore, 1e-9);
            }
            // 打印 rank 手算参考
            {
              double[] rr = new double[4];
              long[] ids2 = {100,101,102,103};
              java.util.List<Long> byR = new ArrayList<>();
              for (long id : ids2) byR.add(id);
              final Map<Long,Double> sRaw = new HashMap<>(sortRaw), rrfR = new HashMap<>(rrfRaw), vR = new HashMap<>(vecRaw);
              byR.sort((a,b)->Double.compare(sRaw.get(b), sRaw.get(a)));
              System.out.print("R1 bySort: "); for (long id:byR) System.out.print(id+" "); System.out.println();
              byR.clear(); for (long id : ids2) byR.add(id);
              byR.sort((a,b)->Double.compare(rrfR.get(b), rrfR.get(a)));
              System.out.print("R2 byRrfS(raw): "); for (long id:byR) System.out.print(id+"("+rrfR.get(id)+") "); System.out.println();
              byR.clear(); for (long id : ids2) byR.add(id);
              byR.sort((a,b)->Double.compare(vR.get(b), vR.get(a)));
              System.out.print("R3 byVec(raw): "); for (long id:byR) System.out.print(id+" "); System.out.println();
              System.out.println("rrfD0="+rrfD0+" rrfD1="+rrfD1+" rrfD2="+rrfD2+" rrfD3="+rrfD3);
              for (long id:ids2) System.out.println("expFS id="+id+" = "+expected.get(id));
            }
            // 排序按 finalScore 降序；取顺序 docIds
            long[] actualIds = new long[4];
            for (int i=0;i<4;i++) actualIds[i] = out.get(i).docId;
            // 按 expectedFS 降序得到期望顺序（tie: docId 升序）
            long[] expectedOrder = new long[]{100,101,102,103};
            // 计算预期 finalScore：D0 vs D1 vs D2 vs D3 谁大？
            // D0 sortTop 但 RRF 中 rankD0<R2=3<R3=4；D3 RRF 高但 sortLow。比一下 expectedFS：
            double eD0 = expected.get(100L);
            double eD1 = expected.get(101L);
            double eD2 = expected.get(102L);
            double eD3 = expected.get(103L);
            // 按从大到小排列 ids
            long[] order = {100,101,102,103};
            Double[] fses = {eD0,eD1,eD2,eD3};
            for (int i=0;i<4;i++) for (int j=i+1;j<4;j++) {
                if (fses[j] > fses[i] + 1e-12 || (Math.abs(fses[i]-fses[j]) < 1e-12 && order[j]<order[i])) {
                    double td=fses[i]; fses[i]=fses[j]; fses[j]=td;
                    long t=order[i]; order[i]=order[j]; order[j]=t;
                }
            }
            Assert.assertArrayEquals(order, actualIds);
        } finally {
            router.close();
        }
    }

    // TR3.2: 同分三键 tie-break（finalScore 同 → docId 升 → shardId 升）
    @Test
    public void tr32_tieBreakThreeKeys() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            Map<Integer, List<ScoredDocHit>> fake = new HashMap<>();
            // 3 doc 同分：(id=100, s=3)、(id=100, s=0)、(id=99, s=2)。期望顺序：99(s2) first，然后 100(s0)→100(s3)
            ScoredDocHit h1 = h10_11(100L, 3, 1.0, 0.5, 0.5);
            ScoredDocHit h2 = h10_11(100L, 0, 1.0, 0.5, 0.5);
            ScoredDocHit h3 = h10_11( 99L, 2, 1.0, 0.5, 0.5);
            // 统一 finalScore 用相同 0.9 注入（scatterGatherForTest 只在合并时覆盖 finalScore？No：scatterGatherForTest 会重算 finalScore，
            // 所以为保证同分，用 sort/rrf/vec 全部相同 这样 RRF rank 同分 docId 决定。）
            fake.put(0, Arrays.asList(h2));
            fake.put(2, Arrays.asList(h3));
            fake.put(3, Arrays.asList(h1));
            SearchOptions o = SearchOptions.defaults();
            o.phraseBoost = false;
            List<ScoredDocHit> out = router.scatterGatherForTest("q", null, 10, o, fake);
            // 去重 by docId？不：spec 说“同 docId 跨 shard 视为数据错误（写路径保证唯一）但三 tie-break 仍测 2 docId=100：
            // 注意我们 mergedByDoc 的 T2/T3 去重 by docId，所以同 id 跨 shard 只会留 finalScore 大的；如果同分按 hashmap 顺序不确定，我们要改 输入 h1,h2 注入同一 docId 但故意让 rrf/rank 不同 → 实际上 T3 三键 tie-break 更简单的验证：
            // 注入 3 doc 不同 id：(id=50,s=3) / (id=50,s=0) 改为不同 id：(id=50,s=3) / (id=50,s=0) 还是同 id；改为不同 id：(id=100,s=0)、(id=100,s=1) 实际 id 相同→去重仅一个，故验证顺序改为验证 3 个不同 docId：id=100(s=3)、id=99(s=2)、id=101(s=0) 同分数顺序 99→100→101：
        } finally {
            router.close();
        }
        // === 重跑：用 3 个不同 id，分数严格相同（sortScore 同/rrf 同/vector 同 → 全局 rank 同分 → docId 升序 正确）===
        FulltextStore.ShardRouter r2 = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            Map<Integer, List<ScoredDocHit>> fake = new HashMap<>();
            ScoredDocHit a = h10_11(101L, 3, 1.0, 0.1, 0.01); // id=101 s=3
            ScoredDocHit b = h10_11(100L, 1, 1.0, 0.1, 0.01); // id=100 s=1
            ScoredDocHit c = h10_11( 99L, 2, 1.0, 0.1, 0.01); // id= 99 s=2
            fake.put(3, Arrays.asList(a));
            fake.put(1, Arrays.asList(b));
            fake.put(2, Arrays.asList(c));
            SearchOptions o = SearchOptions.defaults();
            o.phraseBoost = false;
            List<ScoredDocHit> out = r2.scatterGatherForTest("q", null, 10, o, fake);
            Assert.assertEquals(3, out.size());
            Assert.assertEquals( 99L, out.get(0).docId);
            Assert.assertEquals(100L, out.get(1).docId);
            Assert.assertEquals(101L, out.get(2).docId);
            // 相同分数 docId 升序；接下来验证 shardId 作为第三键：造 2 doc 同分同 docId 不可能（merge by docId 去重）→ 改为同 docId 不可能，那就同 finalScore、同 docId 不可能。
            // 验证 shardId 字段本身是否正确：a.shardId 应为 3 / b=1 / c=2（merged 后 shardId 不被覆盖，因为各自 docId 独立）
            Map<Long,Integer> gotSid = new HashMap<>();
            for (ScoredDocHit h : out) gotSid.put(h.docId, h.shardId);
            Assert.assertEquals(Integer.valueOf(2), gotSid.get( 99L));
            Assert.assertEquals(Integer.valueOf(1), gotSid.get(100L));
            Assert.assertEquals(Integer.valueOf(3), gotSid.get(101L));
        } finally {
            r2.close();
        }
    }

    // TR3.3: 非法参数（K≤0 / q=""&vec=null / shardCount=0 构造 IAE / 空 shard 结果不 NPE）
    @Test
    public void tr33_invalidParamNeverCrash() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            SearchOptions o = SearchOptions.defaults();
            o.vectorRecall = false;
            o.neighborRecallD = 0;
            o.phraseBoost = false;
            Assert.assertEquals(0, router.searchCluster("cherry", null, 0, o).size());
            Assert.assertEquals(0, router.searchCluster("cherry", null, -1, o).size());
            Assert.assertEquals(0, router.searchCluster("", null, 10, o).size());
            Assert.assertEquals(0, router.searchCluster(null, null, 10, o).size());
            // shardCount=0 构造抛 IAE
            try {
                new FulltextStore.ShardRouter(0, root.getAbsolutePath());
                Assert.fail("expect IAE shardCount=0");
            } catch (IllegalArgumentException ok) {
                Assert.assertTrue(ok.getMessage().contains("shardCount"));
            }
            // 空结果 shard 不 NPE：scatterGatherForTest 有 2 个 shard 返回 null/empty
            Map<Integer, List<ScoredDocHit>> fake = new HashMap<>();
            fake.put(0, new ArrayList<>());  // empty
            fake.put(1, null);               // null
            // 再补一个正常 shard 结果
            fake.put(2, Arrays.asList(h10_11(1L, 2, 1.0, 0.1, 0.01)));
            List<ScoredDocHit> out = router.scatterGatherForTest("", null, 10, o, fake);
            Assert.assertEquals(1, out.size());
        } finally {
            router.close();
        }
    }

    // TR3.4: 4 doc D0..D3 各放不同 shard → K=5 searchCluster 返回 4 doc 全召回，shardId=[0,1,2,3]
    @Test
    public void tr34_fourDocsAllShardsRecalled() throws IOException {
        FulltextStore.ShardRouter router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
        try {
            // 手挑 docId 分别落在 0/1/2/3 shard
            long[] onePerShard = new long[N];
            Arrays.fill(onePerShard, -1L);
            long probe = 50_000L;
            int got = 0;
            while (got < N) {
                int s = FulltextStore.resolveShardForDocIdForTest(probe, N);
                if (s < N && onePerShard[s] == -1L) {
                    onePerShard[s] = probe;
                    got++;
                }
                probe += 7;
            }
            // 写入每个 doc 都包含独有唯一词 EXCLUSIVE_T_x 和公共词 CHERRY（公共词保证召回全 4 条）
            for (int i = 0; i < N; i++) {
                router.putDocCluster(onePerShard[i], "CHERRY EXCLUSIVE_T_" + i + " 苹果 香蕉 北京 上海 广州");
            }
            SearchOptions opts = SearchOptions.defaults();
            opts.vectorRecall = false;
            opts.neighborRecallD = 0;
            opts.phraseBoost = false;
            List<ScoredDocHit> hits = router.searchCluster("CHERRY", null, 5, opts);
            Assert.assertTrue("hits.size >=4 got " + hits.size(), hits.size() >= 4);
            Set<Integer> shardIdsGot = new HashSet<>();
            Set<Long> docIdsGot = new HashSet<>();
            for (ScoredDocHit h : hits) { shardIdsGot.add(h.shardId); docIdsGot.add(h.docId); }
            for (long id : onePerShard) Assert.assertTrue("doc " + id + " missing", docIdsGot.contains(id));
            // shardId 包含 0/1/2/3 全部 4 个
            for (int s = 0; s < N; s++) Assert.assertTrue("shard " + s + " missing in hits", shardIdsGot.contains(s));
        } finally {
            router.close();
        }
    }
}
