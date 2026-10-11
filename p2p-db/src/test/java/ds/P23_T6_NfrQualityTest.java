package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.Similarity;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * P23 T6：NFR + 质量 Rubric + 扩展 Rubric 5 TR
 *   TR6.1：30 doc × 20 term + 核心词 768 维 vec Ingest ≤ 40s，Bloat ∈ [100KB, 2GB]
 *   TR6.2：searchWithVector 20 次 P95 ≤ 3000ms（MVP 暴力 KNN，量级可控）
 *   TR6.3：searchWithVector 10 次 top10 docId 列表全全等（三路 RRF 稳定排序）
 *   TR6.4：质量 Rubric(AC-7) ≥4 分：top2={DOC强,DOC次} 且顺序正确（三路 + δ=0.15 向量融合）
 *   TR6.5：扩展 Rubric(AC-8) ≥4 分：HnswStore 内嵌类存在 + SearchOptions 5 个向量字段 + Similarity enum + RRF 三路 + vectorScore 字段
 */
public class P23_T6_NfrQualityTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private FulltextStore store;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p23t6-" + UUID.randomUUID());
        root = new File(tmp, "p23t6-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ================== Fixture A：30 doc × 20 term + 每 doc 核心词 768 维 vec ==================
    private static final int DOCS_A = 30, TERMS_A = 20, LV = 1, VEC_DIM = 768;
    private void build30x20x768Performance() throws IOException {
        TermDictStore td = store.termDict;
        // 预写 30 个 doc 的核心词 vec（每 doc 1 个 "C_<docIdx>"，768 维 float）
        for (int d = 0; d < DOCS_A; d++) {
            String coreText = "C_" + d;
            long uCore = td.toUid(coreText, true);
            float[] v = new float[VEC_DIM];
            for (int i = 0; i < VEC_DIM; i++) v[i] = (float) Math.sin(d * 7 + i * 0.013d);
            store.indexTermVector(uCore, v);
        }
        // 再逐 doc 写 20 term（1 个 C_d core + 19 个 T_d_t unique）
        for (int d = 0; d < DOCS_A; d++) {
            long docId = 7_000_000L + d;
            long p = docId * 10L + LV;
            List<Long> uids = new ArrayList<>(TERMS_A);
            // t=0 为 core（已写 vec）
            String t0 = "C_" + d;
            long u0 = td.toUid(t0, true);
            td.retain(u0); uids.add(u0);
            store.insert(new TuRow(u0, docId, p, LV, 0, 0, 1, t0));
            // t=1..19
            for (int t = 1; t < TERMS_A; t++) {
                String text = String.format("T_%d_%02d", d, t);
                long u = td.toUid(text, true);
                td.retain(u); uids.add(u);
                store.insert(new TuRow(u, docId, p, LV, t, t, t+1, text));
            }
            store.buildDistForSeq(docId, p, LV, uids);  // 写 DIST_BUCKET + WeightStore
        }
    }

    // ================== Fixture B：质量 Rubric 主题语料（强 / 次 / 无关） ==================
    private static final long DOC_STRONG = 7_100_001L;
    private static final long DOC_SECOND = 7_100_002L;
    private static final int VD = 128;
    private void buildThematicForQualityRubric() throws IOException {
        TermDictStore td = store.termDict;
        // 3 个主题核心词：主题 A(向量化检索) / B(天气) / C(体育)
        String coreA = "COREA";
        String coreB = "COREB";
        String coreC = "COREC";
        long uA = td.toUid(coreA, true);
        long uB = td.toUid(coreB, true);
        long uC = td.toUid(coreC, true);
        // coreA / coreB / coreC 各写 128 维 vec：A 和 B 相近（夹角小）；C 完全不同
        float[] vA = new float[VD], vB = new float[VD], vC = new float[VD];
        for (int i = 0; i < VD; i++) {
            vA[i] = (i % 2 == 0) ? 1f : -1f;
            vB[i] = (i % 2 == 0) ? 0.95f : -0.90f;  // ≈ cos 0.95
            vC[i] = (float) Math.cos(i * 0.37d);     // 与 A/B 几乎正交
        }
        store.indexTermVector(uA, vA);
        store.indexTermVector(uB, vB);
        store.indexTermVector(uC, vC);

        // DOC_STRONG：8 × coreA + 2 × 扩展 A1~A12（共 20 term）
        StringBuilder strong = new StringBuilder();
        for (int i = 0; i < 8; i++) strong.append(coreA).append(' ');
        for (int i = 1; i <= 12; i++) strong.append("A").append(i).append(' ');
        store.putDoc(DOC_STRONG, strong.toString().trim());

        // DOC_SECOND：3 × coreA + 5 × coreB + 12 × B1~B12（coreA 词频次高 + vecB 近邻 vecA）
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 3; i++) second.append(coreA).append(' ');
        for (int i = 0; i < 5; i++) second.append(coreB).append(' ');
        for (int i = 1; i <= 12; i++) second.append("B").append(i).append(' ');
        store.putDoc(DOC_SECOND, second.toString().trim());

        // DOC3~DOC10：无关（核心词 coreC × N + Cxxx 填充）
        for (int d = 3; d <= 10; d++) {
            long docId = 7_100_000L + d;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(coreC).append(' ');
            for (int i = 1; i <= 10; i++) sb.append("C").append(d).append("_").append(i).append(' ');
            store.putDoc(docId, sb.toString().trim());
        }
        store.recomputeIdfForTerms(Arrays.asList(uA, uB, uC));
    }

    // ---------------- TR6.1【Ingest】30×20 + 768 维 vec 写入 ≤40s + Bloat 合理 ----------------
    @Test
    public void tr6_1_ingest30x20x768Within40SecAndBloatRange() throws IOException {
        long t0 = System.currentTimeMillis();
        build30x20x768Performance();
        long t1 = System.currentTimeMillis();
        long ms = t1 - t0;
        System.out.println("[TR6.1 Ingest] " + DOCS_A + "*" + TERMS_A + "+" + VEC_DIM + "D vec = " + ms + "ms");
        Assert.assertTrue("TR6.1 Ingest = " + ms + "ms > 40000ms（量级退化 MVP）", ms <= 40_000L);

        long total = dirSize(root);
        final long MIN = 100L * 1024L;              // 100 KB（至少 WeightStore + HNSW + 30× DIST_BUCKET）
        final long MAX = 2L * 1024L * 1024L * 1024L;  // 2 GB
        System.out.println("[TR6.1 Bloat] " + total + " B = " + (total/1024L/1024L) + " MB");
        Assert.assertTrue("TR6.1 Bloat = " + total + " B < 100KB（漏写 vec/DIST/Weight）", total >= MIN);
        Assert.assertTrue("TR6.1 Bloat = " + total + " B > 2GB（异常膨胀）", total <= MAX);

        // 抽样 10 doc WORD rows=20
        for (int d = 0; d < 10; d++) {
            long docId = 7_000_000L + d;
            int rows = store.selectByDocLevel(docId, LV).size();
            Assert.assertEquals("TR6.1 docId=" + docId + " WORD rows=20", 20, rows);
        }
        // 抽样 C_0 / C_1 两个核心词 vec 返回非空且 768 维
        TermDictStore td = store.termDict;
        float[] v0 = store.getTermVectorForTest(td.toUid("C_0", false));
        float[] v1 = store.getTermVectorForTest(td.toUid("C_1", false));
        Assert.assertNotNull("TR6.1 C_0 vector not null", v0);
        Assert.assertNotNull("TR6.1 C_1 vector not null", v1);
        Assert.assertEquals("TR6.1 C_0 vec dim=768", 768, v0.length);
        Assert.assertEquals("TR6.1 C_1 vec dim=768", 768, v1.length);
    }

    // ---------------- TR6.2【searchWithVector P95 ≤ 3000ms】 ----------------
    @Test
    public void tr6_2_searchVectorP95Within3000ms() throws IOException {
        build30x20x768Performance();
        store.recomputeIdfForTerms(allCoreTermsFor30x20());
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.BM25;
        opts.neighborRecallD = 0;       // 关 R2 避免非必要 I/O
        opts.phraseBoost = false;
        opts.charLevelSearch = false;
        opts.vectorEfSearch = 16;
        opts.vectorTopK = 5;
        opts.vectorSim = Similarity.COSINE;

        final int Q = 20;
        long[] times = new long[Q];
        for (int q = 0; q < Q; q++) {
            int d = q % DOCS_A;
            String query = "C_" + d + " T_" + d + "_05";
            long t0 = System.nanoTime();
            List<ScoredDocHit> hits = store.searchWithVectorForTest(query, null, 10, opts);
            long t1 = System.nanoTime();
            times[q] = t1 - t0;
            Assert.assertFalse("TR6.2 q=" + q + " query=" + query + " hits 非空", hits.isEmpty());
        }
        Arrays.sort(times);
        long p95Nanos = times[(int)(Q * 0.95)];
        long p95Ms = p95Nanos / 1_000_000L;
        System.out.println("[TR6.2 searchWithVector P95] " + p95Ms + "ms (median " + (times[Q/2]/1_000_000L) + "ms)");
        Assert.assertTrue("TR6.2 searchWithVector P95 = " + p95Ms + "ms > 3000ms（MVP 暴力 KNN 退化）",
            p95Ms <= 3000L);
    }

    // ---------------- TR6.3【searchWithVector 稳定排序】10 次 top10 docId 序列指纹=1 ----------------
    @Test
    public void tr6_3_stableSort10RunsIdenticalList() throws IOException {
        build30x20x768Performance();
        store.recomputeIdfForTerms(allCoreTermsFor30x20());
        String fixedQuery = "C_0 T_0_03 T_0_15";
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.BM25;
        opts.neighborRecallD = 0;
        opts.phraseBoost = false;
        opts.charLevelSearch = false;
        opts.vectorEfSearch = 16;
        opts.vectorTopK = 5;
        opts.vectorSim = Similarity.COSINE;

        final int RUNS = 10, K = 10;
        Set<String> fps = new HashSet<>();
        for (int r = 0; r < RUNS; r++) {
            List<ScoredDocHit> hits = store.searchWithVectorForTest(fixedQuery, null, K, opts);
            List<Long> ids = new ArrayList<>(hits.size());
            for (ScoredDocHit h : hits) ids.add(h.docId);
            fps.add(ids.toString());
        }
        Assert.assertEquals("TR6.3 searchWithVector 10 次 top10 指纹数必须=1（稳定排序三路 RRF + 同分 docId 升序）",
            1, fps.size());
    }

    // ---------------- TR6.4【质量 Rubric(AC-7)】score ≥4 ----------------
    @Test
    public void tr6_4_qualityRubricAtLeast4() throws IOException {
        buildThematicForQualityRubric();
        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.BM25;
        opts.vectorEfSearch = 16;
        opts.vectorTopK = 5;
        opts.vectorSim = Similarity.COSINE;
        // query：COREA（文本 + vecA，三路召回都命中 DOC_STRONG / DOC_SECOND，vecB 近邻 vecA，R3 也命中 DOC_SECOND）
        List<ScoredDocHit> hits = store.searchWithVectorForTest("COREA", null, 5, opts);
        Assert.assertTrue("TR6.4 quality hits ≥2（至少 DOC_STRONG + DOC_SECOND）", hits.size() >= 2);
        long top1Id = hits.get(0).docId;
        long top2Id = hits.get(1).docId;

        // 评分 anchors：① top2 集合 = {强,次} +3 分；② 顺序 DOC_STRONG 第一 DOC_SECOND 第二 +2 分 → 满分 5
        int score = 0;
        Set<Long> top2 = new HashSet<>(Arrays.asList(top1Id, top2Id));
        boolean setOk = top2.contains(DOC_STRONG) && top2.contains(DOC_SECOND);
        boolean orderOk = (top1Id == DOC_STRONG && top2Id == DOC_SECOND);
        if (setOk) score += 3;
        if (orderOk) score += 2;

        System.out.println("[TR6.4 Quality Rubric] score=" + score
            + " top2=[" + top1Id + "," + top2Id + "] (expect[" + DOC_STRONG + "," + DOC_SECOND + "])"
            + " setOk=" + setOk + " orderOk=" + orderOk);
        for (int i = 0; i < Math.min(5, hits.size()); i++) {
            ScoredDocHit h = hits.get(i);
            System.out.println("  top " + (i+1) + " docId=" + h.docId + " finalScore=" + String.format("%.6f", h.finalScore)
                + " bm25Sort=" + String.format("%.4f", h.sortScore) + " rrf=" + String.format("%.4f", h.rrfScore)
                + " phrase=" + String.format("%.4f", h.phraseBoostScore) + " vector=" + String.format("%.4f", h.vectorScore));
        }
        Assert.assertTrue("TR6.4 质量 Rubric score=" + score + " < 4（threshold≥4）", score >= 4);
    }

    // ---------------- TR6.5【扩展 Rubric(AC-8)】score ≥4（6 项 5+ 分） ----------------
    @Test
    public void tr6_5_extensibilityRubricAtLeast4() throws Exception {
        int score = 0;
        // ① Similarity enum ≥2 值（COSINE + L2）→ +1
        Similarity[] sv = Similarity.values();
        if (sv != null && sv.length >= 2) { score++; check("① Similarity enum ≥2（COSINE/L2）", true); }

        // ② SearchOptions 包含 5 个向量字段（vectorSim/vectorRecall/vectorEfSearch/vectorTopK/delta）public → +1
        Set<String> optNeed = new HashSet<>(Arrays.asList("vectorSim","vectorRecall","vectorEfSearch","vectorTopK","delta"));
        Field[] optFs = SearchOptions.class.getDeclaredFields();
        Set<String> optHave = new HashSet<>();
        for (Field f : optFs) if (java.lang.reflect.Modifier.isPublic(f.getModifiers())) optHave.add(f.getName());
        boolean ok2 = optHave.containsAll(optNeed);
        if (ok2) { score++; check("② SearchOptions 5 个向量字段齐全: " + optNeed, true); }

        // ③ HnswStore 内嵌类存在 + 字段 ≥2（nodes map + per-level 邻居）→ +1
        Class<?> hnswCls = null;
        for (Class<?> c : FulltextStore.class.getDeclaredClasses())
            if ("HnswStore".equals(c.getSimpleName())) { hnswCls = c; break; }
        boolean ok3 = hnswCls != null && hnswCls.getDeclaredFields().length >= 2;
        if (ok3) { score++; check("③ HnswStore 内嵌类存在（" + (hnswCls==null?0:hnswCls.getDeclaredFields().length) + " 字段≥2）", true); }

        // ④ 三路 RRF：searchWithVectorCore / rrfRankToScore 两个方法独立存在（rrfRankToScore public static）→ +1
        Method cs = null;
        for (Method m : FulltextStore.class.getDeclaredMethods())
            if ("searchWithVectorCore".equals(m.getName())) { cs = m; break; }
        Method rrf = FulltextStore.class.getMethod("rrfRankToScore", int.class, int.class);
        boolean ok4 = cs != null && rrf != null && java.lang.reflect.Modifier.isStatic(rrf.getModifiers());
        if (ok4) { score++; check("④ searchWithVectorCore + rrfRankToScore 独立方法存在", true); }

        // ⑤ ScoredDocHit 含 vectorScore（≥11 参数构造 或 字段 public）→ +1
        boolean hasVec11Ctor = false;
        for (java.lang.reflect.Constructor<?> c : ScoredDocHit.class.getConstructors())
            if (c.getParameterCount() == 11) { hasVec11Ctor = true; break; }
        Field vf = null;
        try { vf = ScoredDocHit.class.getField("vectorScore"); } catch (NoSuchFieldException ignore) {}
        boolean ok5 = hasVec11Ctor || (vf != null && java.lang.reflect.Modifier.isPublic(vf.getModifiers()));
        if (ok5) { score++; check("⑤ ScoredDocHit vectorScore 字段存在或 11 参数构造", true); }

        System.out.println("[TR6.5 Extensibility Rubric] score=" + score + "/5（threshold≥4）");
        Assert.assertTrue("TR6.5 扩展 Rubric score=" + score + " < 4（threshold≥4）", score >= 4);
    }

    // ============================================================
    // helpers
    // ============================================================
    private List<Long> allCoreTermsFor30x20() throws IOException {
        List<Long> out = new ArrayList<>(DOCS_A);
        TermDictStore td = store.termDict;
        for (int d = 0; d < DOCS_A; d++)
            out.add(td.toUid("C_" + d, true));
        return out;
    }
    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long s = 0L;
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        for (File f : fs) s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }
    private static void check(String label, boolean ok) {
        if (ok) System.out.println("  [PASS] " + label);
        else System.out.println("  [FAIL] " + label);
    }
}
