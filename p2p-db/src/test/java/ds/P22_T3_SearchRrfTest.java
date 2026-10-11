package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Ranker;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

public class P22_T3_SearchRrfTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore dict;

    private long u北京, u大学, u学生, u清华, u北大, u哈佛, u教师, u校园;
    private static final int LV = FulltextStore.DS1_WORD;
    private static final long DOC_A = 101L;
    private static final long DOC_B = 102L;
    private static final long DOC_C = 103L;
    private static final long DOC_D = 104L;
    private static long parentOf(long docId) { return docId * 10L + LV; } // P21 惯例：docId*10+level >0 避免 parentId<=0 IAE
    private List<Long> Q3;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p22t3-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        dict = new TermDictStore(root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        u北京 = dict.toUid("北京", true); dict.retain(u北京);
        u大学 = dict.toUid("大学", true); dict.retain(u大学);
        u学生 = dict.toUid("学生", true); dict.retain(u学生);
        u清华 = dict.toUid("清华", true); dict.retain(u清华);
        u北大 = dict.toUid("北大", true); dict.retain(u北大);
        u哈佛 = dict.toUid("哈佛", true); dict.retain(u哈佛);
        u教师 = dict.toUid("教师", true); dict.retain(u教师);
        u校园 = dict.toUid("校园", true); dict.retain(u校园);
        Q3 = Arrays.asList(u北京, u大学, u学生);
        writeDoc(DOC_A, Arrays.asList("北京","清华","大学","学生"), Arrays.asList(u北京, u清华, u大学, u学生));
        writeDoc(DOC_B, Arrays.asList("北京","北大","大学","学生","教师","校园"), Arrays.asList(u北京, u北大, u大学, u学生, u教师, u校园));
        writeDoc(DOC_C, Arrays.asList("哈佛","学生","教师"), Arrays.asList(u哈佛, u学生, u教师));
        writeDoc(DOC_D, Arrays.asList("哈佛","校园","北京","学生"), Arrays.asList(u哈佛, u校园, u北京, u学生));
    }

    private void writeDoc(long docId, List<String> texts, List<Long> uids) throws Exception {
        long p = parentOf(docId);
        for (int i = 0; i < texts.size(); i++) {
            store.insert(new TuRow(uids.get(i), docId, p, LV, i, i, i+1, texts.get(i)));
        }
        store.buildDistForSeq(docId, p, LV, uids);
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
        try { dict.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // TR-3.1：RRF 融合精确排序（默认 BM25 + RRF + phraseBoost）— 前 4 doc A/B 在 3 term；D=2 term；C=1 term；rrfScore 精确公式 1e-12
    @Test public void tr31_rrfExactRankingAndRrfFormula1e12() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.BM25;
        opts.rrfK = 60;
        opts.alpha = 0.6; opts.beta = 0.3; opts.gamma = 0.1;
        opts.phraseBoost = true;
        opts.phraseBoostWeight = 0.1;
        opts.neighborRecallD = 2;
        List<ScoredDocHit> hits = store.searchByUidsForTest(Q3, 10, opts);
        Assert.assertTrue("至少 >=4 doc 命中（R1 byTerm 覆盖 A/B/C/D）", hits.size() >= 4);
        for (int i = 0; i + 1 < hits.size(); i++) {
            Assert.assertTrue("finalScore desc: hits[" + i + "]=" + hits.get(i).finalScore + " >= hits["+(i+1)+"]="+hits.get(i+1).finalScore,
                    hits.get(i).finalScore + 1e-15 >= hits.get(i+1).finalScore);
        }
        List<Long> top4DocIds = new ArrayList<>();
        for (int i = 0; i < 4 && i < hits.size(); i++) top4DocIds.add(hits.get(i).docId);
        int idxA = top4DocIds.indexOf(DOC_A), idxB = top4DocIds.indexOf(DOC_B), idxD = top4DocIds.indexOf(DOC_D), idxC = top4DocIds.indexOf(DOC_C);
        Assert.assertTrue("DOC_A 在 top4（3 term hit）", idxA >= 0);
        Assert.assertTrue("DOC_B 在 top4（3 term hit）", idxB >= 0);
        Assert.assertTrue("DOC_D 在 top4（2 term hit）", idxD >= 0);
        Assert.assertTrue("DOC_C 在 top4（1 term hit）", idxC >= 0);
        ScoredDocHit a = find(hits, DOC_A), b = find(hits, DOC_B), d = find(hits, DOC_D), c = find(hits, DOC_C);
        Assert.assertTrue("A/B rrfScore > 0（3 term，R1 rank 前列）", a.rrfScore > 0 && b.rrfScore > 0);
        // RRF 单分量精确
        Assert.assertEquals("rrfRankToScore(1,60)=1/61 1e-12", 1.0 / 61.0, FulltextStore.rrfRankToScore(1, 60), 1e-12);
        Assert.assertEquals("rrfRankToScore(4,60)=1/64 1e-12", 1.0 / 64.0, FulltextStore.rrfRankToScore(4, 60), 1e-12);
        Assert.assertEquals("rrfRankToScore(0,60)=0", 0.0, FulltextStore.rrfRankToScore(0, 60), 1e-12);
        Assert.assertEquals("rrfRankToScore(1,0)=1/(60+1)=1/61 (fallback rrfK)", 1.0/61.0, FulltextStore.rrfRankToScore(1, 0), 1e-12);
    }

    // TR-3.2：搜索打分降序 + matchedQueryTerms 正确（3 term(B,A) > 2 term(D) > 1 term(C)）
    @Test public void tr32_sortDescAndMatchedTermsCorrect() throws Exception {
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.TF;
        opts.phraseBoost = false; opts.neighborRecallD = 0;
        opts.alpha = 1.0; opts.beta = 0.0; opts.gamma = 0.0;
        List<ScoredDocHit> hits = store.searchByUidsForTest(Q3, 10, opts);
        Assert.assertTrue("至少 >=4 命中", hits.size() >= 4);
        ScoredDocHit a = find(hits, DOC_A), b = find(hits, DOC_B), d = find(hits, DOC_D), c = find(hits, DOC_C);
        Assert.assertEquals("DOC_A 匹配 3 个 query term", 3, a.matchedQueryTerms);
        Assert.assertEquals("DOC_B 匹配 3 个 query term", 3, b.matchedQueryTerms);
        Assert.assertEquals("DOC_D 匹配 2 个 query term", 2, d.matchedQueryTerms);
        Assert.assertEquals("DOC_C 匹配 1 个 query term", 1, c.matchedQueryTerms);
        Assert.assertEquals("A sortScore = tf sum = 3", 3.0, a.sortScore, 1e-12);
        Assert.assertEquals("B sortScore = tf sum = 3", 3.0, b.sortScore, 1e-12);
        Assert.assertEquals("D sortScore = tf sum = 2", 2.0, d.sortScore, 1e-12);
        Assert.assertEquals("C sortScore = tf sum = 1", 1.0, c.sortScore, 1e-12);
        Assert.assertTrue("A(101) 和 B(102) 同分 → docId 小的 A 在前稳定排序", hits.indexOf(a) < hits.indexOf(b));
    }

    // TR-3.3：phraseBoost 开关 —— 开=true A/B=0.5（相邻对 大学-学生 d=1 命中；北京-大学 d=2 未命中；1/2），D/C=0；关=false 全 0
    @Test public void tr33_phraseBoostOnOffSwitchCorrect() throws Exception {
        SearchOptions optsOn = SearchOptions.defaults();
        optsOn.ranker = Ranker.BOOL; optsOn.neighborRecallD = 0;
        optsOn.alpha = 0.0; optsOn.beta = 0.0; optsOn.gamma = 1.0;
        optsOn.phraseBoost = true; optsOn.phraseBoostWeight = 1.0;
        SearchOptions optsOff = SearchOptions.defaults();
        optsOff.ranker = Ranker.BOOL; optsOff.neighborRecallD = 0;
        optsOff.alpha = 0.0; optsOff.beta = 0.0; optsOff.gamma = 1.0;
        optsOff.phraseBoost = false; optsOff.phraseBoostWeight = 1.0;
        List<ScoredDocHit> hitsOn = store.searchByUidsForTest(Q3, 10, optsOn);
        List<ScoredDocHit> hitsOff = store.searchByUidsForTest(Q3, 10, optsOff);
        ScoredDocHit aOn = find(hitsOn, DOC_A), bOn = find(hitsOn, DOC_B);
        ScoredDocHit dOn = find(hitsOn, DOC_D), cOn = find(hitsOn, DOC_C);
        ScoredDocHit aOff = find(hitsOff, DOC_A), bOff = find(hitsOff, DOC_B);
        ScoredDocHit dOff = find(hitsOff, DOC_D), cOff = find(hitsOff, DOC_C);
        Assert.assertEquals("phraseBoost(on) A=0.5", 0.5, aOn.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(on) B=0.5", 0.5, bOn.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(on) D=0（缺大学）", 0.0, dOn.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(on) C=0（仅学生命中）", 0.0, cOn.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(off) A=0", 0.0, aOff.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(off) B=0", 0.0, bOff.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(off) D=0", 0.0, dOff.phraseBoostScore, 1e-12);
        Assert.assertEquals("phraseBoost(off) C=0", 0.0, cOff.phraseBoostScore, 1e-12);
    }

    // TR-3.4：空 query 安全（null / "" / K=0 / K<0 / null options / null uids / all-0 uids）不抛异常，全返回空
    @Test public void tr34_emptyQuerySafeNoException() throws Exception {
        SearchOptions o = SearchOptions.defaults();
        Assert.assertTrue("null query → empty", store.search(null, 10, o).isEmpty());
        Assert.assertTrue("empty query → empty", store.search("", 10, o).isEmpty());
        Assert.assertTrue("K=0 → empty", store.search("any", 0, o).isEmpty());
        Assert.assertTrue("K<0 → empty", store.search("any", -5, o).isEmpty());
        Assert.assertTrue("options=null empty query → empty（不 NPE）", store.search("", 10, null).isEmpty());
        Assert.assertTrue("searchByUids: null uids → empty", store.searchByUidsForTest(null, 10, o).isEmpty());
        Assert.assertTrue("searchByUids: empty uids → empty", store.searchByUidsForTest(new ArrayList<>(), 10, o).isEmpty());
        Assert.assertTrue("searchByUids: 全 0/-1 → empty", store.searchByUidsForTest(Arrays.asList(0L, 0L, -1L), 10, o).isEmpty());
    }

    private static ScoredDocHit find(List<ScoredDocHit> xs, long docId) {
        if (xs == null) return null;
        for (ScoredDocHit h : xs) if (h.docId == docId) return h;
        return null;
    }
}
