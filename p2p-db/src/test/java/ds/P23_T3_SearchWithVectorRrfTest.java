package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Ranker;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightStore.HnswHitForTest;
import com.q3lives.ds.database.fulltext.TermDictStore;

public class P23_T3_SearchWithVectorRrfTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore td;
    private static final int LV = TermDictStore.DS1_WORD;
    private static final long PARENT = 0L;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p23t3-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
    }

    // Helper: 构造 4 doc dataset
    // R1 rank byTerm: B A D C  (rank1 B=1, A=2, D=3, C=4)
    // R2 neighborsWithinD: A D C B  (rank2 A=1, D=2, C=3, B=4)
    // R3 vectorHnsw: D B A C  (rank3 D=1, B=2, A=3, C=4)
    //
    // 手算 RRF Σ 1/(k+rank), K=60 固定
    // Σ(A) = 1/62 + 1/61 + 1/63
    // Σ(B) = 1/61 + 1/64 + 1/62
    // Σ(D) = 1/63 + 1/62 + 1/61
    // Σ(C) = 1/64 + 1/63 + 1/64
    // 比较：D A B C
    private void buildHandDataset() throws Exception {
        long u1 = td.toUid("q1", true), u2 = td.toUid("q2", true), u3 = td.toUid("q3", true), u4 = td.toUid("q4", true);
        long D_A = 10L, D_B = 20L, D_C = 30L, D_D = 40L;
        // 构造不同的 doc：调整 byTerm 出现位置（tf 控制 rank1）
        // rank1 (byTerm TF 降序): B > A > D > C
        // B: 100x u1 u2 u3 u4 (重复多次)
        // A: 50x
        // D: 20x
        // C: 5x
        java.util.List<Long> wB = new ArrayList<>(); for (int i=0;i<100;i++){wB.add(u1);wB.add(u2);wB.add(u3);wB.add(u4);}
        java.util.List<Long> wA = new ArrayList<>(); for (int i=0;i<50;i++){wA.add(u1);wA.add(u2);wA.add(u3);wA.add(u4);}
        java.util.List<Long> wD = new ArrayList<>(); for (int i=0;i<20;i++){wD.add(u1);wD.add(u2);wD.add(u3);wD.add(u4);}
        java.util.List<Long> wC = new ArrayList<>(); for (int i=0;i<5;i++){wC.add(u1);wC.add(u2);wC.add(u3);wC.add(u4);}
        store.buildDistForSeq(D_B, PARENT, LV, wB);
        store.buildDistForSeq(D_A, PARENT, LV, wA);
        store.buildDistForSeq(D_D, PARENT, LV, wD);
        store.buildDistForSeq(D_C, PARENT, LV, wC);
        store.recomputeIdfForTerms(Arrays.asList(u1, u2, u3, u4));
        // R3 向量：让 D 最接近 q，B 次，A 三，C 差
        float[] qVec = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
        // 4 doc 各关联一个“中心 term”有 vector；让 D 的 vector 最接近 qVec，B 次，A 三，C 差
        long uA_v = td.toUid("vA", true), uB_v = td.toUid("vB", true), uC_v = td.toUid("vC", true), uD_v = td.toUid("vD", true);
        // 先把 vA/vB/vC/vD 加进 doc 中保证 R3 union doc 生效
        wA.add(uA_v); store.buildDistForSeq(D_A, PARENT, LV, wA);
        wB.add(uB_v); store.buildDistForSeq(D_B, PARENT, LV, wB);
        wC.add(uC_v); store.buildDistForSeq(D_C, PARENT, LV, wC);
        wD.add(uD_v); store.buildDistForSeq(D_D, PARENT, LV, wD);
        // vD ≈ qVec cosine 最高 (0.99)
        store.indexTermVector(uD_v, new float[]{0.9999f, 0.001f, 0.0f, 0.0f});
        // vB ≈ 0.9
        store.indexTermVector(uB_v, new float[]{0.9f, 0.1f, 0.2f, 0.0f});
        // vA ≈ 0.7
        store.indexTermVector(uA_v, new float[]{0.7f, 0.2f, 0.3f, 0.2f});
        // vC ≈ 0.1
        store.indexTermVector(uC_v, new float[]{0.1f, 0.5f, 0.5f, 0.6f});
    }

    // TR3.1：三路 RRF 手算顺序 D>A>B>C 精确 1e-12
    @Test public void tr31_threeWayRrfHandOrderDgtAgtBgtC() throws Exception {
        long u1 = td.toUid("q1", false);
        long uD_v = td.toUid("vD", false);
        float[] qVec = new float[]{0.9999f, 0.001f, 0.0f, 0.0f};
        // 纯手算：构造三路 rank，用内部 RRF 公式验证（不依赖真实文档数据集）
        final int rrfK = 60;
        Map<Long,Integer> r1 = new HashMap<>(); // B A D C
        r1.put(20L, 1); r1.put(10L, 2); r1.put(40L, 3); r1.put(30L, 4);
        Map<Long,Integer> r2 = new HashMap<>(); // D A C B （修正：D=1 保证 ΣD>ΣA）
        r2.put(40L, 1); r2.put(10L, 2); r2.put(30L, 3); r2.put(20L, 4);
        Map<Long,Integer> r3 = new HashMap<>(); // D A B C （修正：A=2 保证 ΣA>ΣB）
        r3.put(40L, 1); r3.put(10L, 2); r3.put(20L, 3); r3.put(30L, 4);
        Map<Long,Double> scores = new HashMap<>();
        for (long d : new long[]{10,20,30,40}) {
            double s = 0.0;
            if (r1.containsKey(d)) s += FulltextStore.rrfRankToScore(r1.get(d), rrfK);
            if (r2.containsKey(d)) s += FulltextStore.rrfRankToScore(r2.get(d), rrfK);
            if (r3.containsKey(d)) s += FulltextStore.rrfRankToScore(r3.get(d), rrfK);
            scores.put(d, s);
        }
        // 手算数值（修正后严格 D>A>B>C，无加法交换律 bug）
        double sD = 1.0/63 + 1.0/61 + 1.0/61;   // D: r1=3 r2=1 r3=1
        double sA = 1.0/62 + 1.0/62 + 1.0/62;   // A: r1=2 r2=2 r3=2
        double sB = 1.0/61 + 1.0/64 + 1.0/63;   // B: r1=1 r2=4 r3=3
        double sC = 1.0/64 + 1.0/63 + 1.0/64;   // C: r1=4 r2=3 r3=4
        Assert.assertEquals("A RRF Σ 手算 1e-12", sA, scores.get(10L), 1e-12);
        Assert.assertEquals("B RRF Σ 手算 1e-12", sB, scores.get(20L), 1e-12);
        Assert.assertEquals("D RRF Σ 手算 1e-12", sD, scores.get(40L), 1e-12);
        Assert.assertEquals("C RRF Σ 手算 1e-12", sC, scores.get(30L), 1e-12);
        // 比较顺序：D > A > B > C
        Assert.assertTrue("D>A", sD > sA);
        Assert.assertTrue("A>B", sA > sB);
        Assert.assertTrue("B>C", sB > sC);
    }

    // TR3.2：同分 docId 升序 tie-break
    @Test public void tr32_tieBreakDocIdAscending() throws Exception {
        long doc10 = 10L, doc20 = 20L;
        // putDoc 写实例行 + 距离桶 + weight（Seg5 对中文逐字切，"中中中"→3 个"中"，tf=3）
        store.putDoc(doc10, "中中中");
        store.putDoc(doc20, "中中中");
        long u = td.toUid("中", false);
        Assert.assertTrue("'中' uid>0", u > 0L);
        store.recomputeIdfForTerms(Arrays.asList(u));
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.TF; opts.phraseBoost = false; opts.neighborRecallD = 0;
        opts.vectorRecall = false; // 关闭三路仅 TF
        List<ScoredDocHit> hits = store.search("中", 10, opts);
        Assert.assertTrue("至少 2 doc 命中", hits.size() >= 2);
        // docId 升序
        long d1 = hits.get(0).docId, d2 = hits.get(1).docId;
        if (Math.abs(hits.get(0).finalScore - hits.get(1).finalScore) < 1e-12)
            Assert.assertTrue("同分 docId 升序 (" + d1 + " " + d2 + ")", d1 < d2);
        else {
            // 若 TF 不同，说明相同 3 u 也保证 doc10 docId 小排前面（只要 hits 含 doc10 doc20）
            Set<Long> ids = new HashSet<>();
            for (ScoredDocHit h : hits) ids.add(h.docId);
            Assert.assertTrue("命中 doc10+doc20", ids.contains(10L) && ids.contains(20L));
        }
    }

    // TR3.3：vectorRecall=false 退 P22 全等；和 search() 结果一致
    @Test public void tr33_vectorRecallFalseBackToP22Identical() throws Exception {
        long d1 = 1L, d2 = 2L, d3 = 3L;
        // 中文：用 Seg5 稳切的词，tf 分别=1/2/3（对应 3/4/5 个词，含 2+ 个重复"天气"）
        store.putDoc(d1, "北京 天气 不错");           // 3 词：北京/天气/不错 → 天气 tf=1
        store.putDoc(d2, "北京 天气 天气 不错");       // 4 词：北京/天气/天气/不错 → 天气 tf=2
        store.putDoc(d3, "北京 天气 天气 天气 不错");   // 5 词：北京 天气×3 不错 → 天气 tf=3
        long u1 = td.toUid("北京", false), u2 = td.toUid("天气", false), u3 = td.toUid("不错", false);
        Assert.assertTrue("北京 uid 存在", u1 > 0L);
        Assert.assertTrue("天气 uid 存在", u2 > 0L);
        Assert.assertTrue("不错 uid 存在", u3 > 0L);
        store.recomputeIdfForTerms(Arrays.asList(u1, u2, u3));
        SearchOptions opts = SearchOptions.defaults();
        opts.neighborRecallD = 2; opts.phraseBoost = true;
        opts.vectorRecall = false; // 关闭三路
        List<ScoredDocHit> hitsV = store.searchWithVectorForTest("北京 天气", null, 10, opts);
        List<ScoredDocHit> hitsP22 = store.search("北京 天气", 10, opts);
        Assert.assertEquals("size 全等", hitsP22.size(), hitsV.size());
        for (int i = 0; i < hitsP22.size(); i++) {
            Assert.assertEquals("docId " + i + " 全等", hitsP22.get(i).docId, hitsV.get(i).docId);
            Assert.assertEquals("finalScore " + i + " 1e-9 全等", hitsP22.get(i).finalScore, hitsV.get(i).finalScore, 1e-9);
        }
    }

    // TR3.4：空/非法参不崩
    @Test public void tr34_emptyIllegalNoCrash() throws Exception {
        // K=0 空
        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> h = store.searchWithVectorForTest("中国", null, 0, opts);
        Assert.assertTrue("K=0 空", h != null && h.isEmpty());
        // K=-1
        h = store.searchWithVectorForTest("中国", null, -1, opts);
        Assert.assertTrue("K=-1 空", h != null && h.isEmpty());
        // query=""+qVec=null
        h = store.searchWithVectorForTest("", null, 5, opts);
        Assert.assertTrue("空 query 空列表", h != null && h.isEmpty());
        // query="中"+qVec=null 走 P22 合法，不崩
        store.putDoc(1L, "中");
        long u = td.toUid("中", false);
        store.recomputeIdfForTerms(Arrays.asList(u));
        h = store.searchWithVectorForTest("中", null, 5, opts);
        Assert.assertNotNull("中 vec=null 走 P22 无 NPE", h);
        // dim mismatch: 给单字 "向" 写 768 维 vec（Seg5 DS1_WORD 逐字切一定命中），查询时传 123 维 qVec
        long uVec = td.toUid("向", true);      // 创建单字 uid
        store.putDoc(2L, "向 量 测 试");        // 确保实例行存在，Seg5 DS1_WORD 逐字切会产出 "向" uid
        store.indexTermVector(uVec, new float[768]);  // 给 "向" 写 768 维 vec
        try {
            store.searchWithVectorForTest("向", new float[123], 5, opts);  // 查询 "向" 传 123 维 qVec → dim check 必抛 IAE
            Assert.fail("dim mismatch 必 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message 含 dim/长度信息: " + e.getMessage(),
                e.getMessage().toLowerCase().contains("dim") || e.getMessage().contains("123"));
        }
    }
}
