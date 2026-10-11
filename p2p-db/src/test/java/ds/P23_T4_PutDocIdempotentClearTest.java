package ds;

import java.io.File;
import java.io.IOException;
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
import com.q3lives.ds.database.fulltext.FulltextStore.WeightDocStatRow;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightTermStatRow;
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * P23 T4：putDoc 幂等（含 Term Vector）+ clearDoc 保留 vector/idf 4 TR
 *   TR4.1：连续 3 次 putDoc 同 doc 同内容 → tf/docLen/nTerms/termVector 4 项 3 次逐值全等；
 *   TR4.2：putDoc 改内容（term 集合变化）→ 已写入的 term vector（termStat.payload）保持不变（只改 tf/docStat）；
 *   TR4.3：clearDoc 后 termStat（idf/payload=vector）完全保留，getTermVector 返回数组引用与 clear 前值全等；
 *   TR4.4：putDoc(docId, "") 等价于 clearDoc(docId) —— 清理后 tf=0、docStat=null、但 termStat/termVector 仍保留。
 *
 * 依赖 P22 T4 + P23 T1 HC probes：
 *   getTfForTest / getDocStatForTest / getTermStatForTest / getTermVectorForTest / indexTermVector
 */
public class P23_T4_PutDocIdempotentClearTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private TermDictStore td;
    private FulltextStore store;
    private static final int LV_WORD = 1;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p23t4-" + UUID.randomUUID());
        root = new File(tmp, "p23t4-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.termDict;
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        try { td.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // 固定语料 U2（与 P22_T4 同，便于比较不回归）
    private static final String TEXT_U2 = "中 国 人 中 国 北 京 天 安 门";
    private static final String[] U2_TERMS = {"中","国","人","北","京","天","安","门"};
    private static final int[] U2_TF_EXPECTED = {2,2,1,1,1,1,1,1};

    // ASCII 整词：Seg5 SPI 下英文整词 1 token，不被切分
    private static final String VEC_WORD_A = "VECA";
    private static final String VEC_WORD_B = "VECB";
    private static final int VEC_DIM = 16;

    private long parentOf(long docId) { return docId * 10L + LV_WORD; }

    private static float[] vecForA() {
        float[] r = new float[VEC_DIM];
        for (int i = 0; i < VEC_DIM; i++) r[i] = (float) Math.sin(i + 1.0);
        return r;
    }
    private static float[] vecForB() {
        float[] r = new float[VEC_DIM];
        for (int i = 0; i < VEC_DIM; i++) r[i] = (float) Math.cos(i + 2.0);
        return r;
    }
    private static void assertVecEquals(String msg, float[] expected, float[] actual) {
        Assert.assertNotNull(msg + ": expected not null", expected);
        Assert.assertNotNull(msg + ": actual not null", actual);
        Assert.assertEquals(msg + ": dim", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++)
            Assert.assertEquals(msg + "[" + i + "]", expected[i], actual[i], 1e-30f);
    }

    // ---------------- TR4.1【3 次 putDoc 幂等：tf / docLen / nTerms / termVector 4 项全等】 ----------------
    @Test
    public void tr4_1_putDocThreeTimesInclVectorExactEqual() throws IOException {
        long docId = 501L;
        long p = parentOf(docId);

        // 先写 term vector（写入 VECA / VECB 的 16 维 float payload）
        long uA = td.toUid(VEC_WORD_A, true);
        long uB = td.toUid(VEC_WORD_B, true);
        float[] vecA = vecForA();
        float[] vecB = vecForB();
        store.indexTermVector(uA, vecA);
        store.indexTermVector(uB, vecB);
        // 构造 doc：VECA VECB + U2 的中/国/人... 混合
        String text = VEC_WORD_A + " " + VEC_WORD_B + " " + TEXT_U2;

        int[][] tf = new int[3][U2_TERMS.length + 2];
        WeightDocStatRow[] ds = new WeightDocStatRow[3];
        float[][] vecARead = new float[3][];
        float[][] vecBRead = new float[3][];

        for (int round = 0; round < 3; round++) {
            store.putDoc(docId, text);
            for (int i = 0; i < U2_TERMS.length; i++)
                tf[round][i] = store.getTfForTest(td.toUid(U2_TERMS[i], false), docId, p, LV_WORD);
            tf[round][U2_TERMS.length] = store.getTfForTest(uA, docId, p, LV_WORD);
            tf[round][U2_TERMS.length + 1] = store.getTfForTest(uB, docId, p, LV_WORD);
            ds[round] = store.getDocStatForTest(docId);
            vecARead[round] = store.getTermVectorForTest(uA);
            vecBRead[round] = store.getTermVectorForTest(uB);
        }

        // (1) 基准 tf 期望：U2 按 U2_TF_EXPECTED，VECA/VECB 各 1
        for (int i = 0; i < U2_TERMS.length; i++)
            Assert.assertEquals("TR4.1 第1轮 tf[" + U2_TERMS[i] + "] 基准", U2_TF_EXPECTED[i], tf[0][i]);
        Assert.assertEquals("TR4.1 第1轮 tf[VECA]=1", 1, tf[0][U2_TERMS.length]);
        Assert.assertEquals("TR4.1 第1轮 tf[VECB]=1", 1, tf[0][U2_TERMS.length + 1]);
        // (2) 3 轮 tf 逐值全等
        for (int r = 1; r < 3; r++)
            for (int i = 0; i < tf[0].length; i++)
                Assert.assertEquals("TR4.1 第" + (r+1) + "轮 tf[" + i + "] 与第1轮全等", tf[0][i], tf[r][i]);
        // (3) 3 轮 docStat 全等
        for (int r = 1; r < 3; r++) {
            Assert.assertNotNull("TR4.1 ds[" + r + "] 非空", ds[r]);
            Assert.assertEquals("TR4.1 第" + (r+1) + "轮 docLen[WORD] 全等", ds[0].docLen[LV_WORD], ds[r].docLen[LV_WORD]);
            Assert.assertEquals("TR4.1 第" + (r+1) + "轮 nTerms[WORD] 全等", ds[0].nTerms[LV_WORD], ds[r].nTerms[LV_WORD]);
        }
        // (4) 3 轮 termVector 全等（16 维逐 float）
        for (int r = 0; r < 3; r++) {
            assertVecEquals("TR4.1 第" + (r+1) + "轮 VECB 读取", vecA, vecARead[r]);
            assertVecEquals("TR4.1 第" + (r+1) + "轮 VECB 读取", vecB, vecBRead[r]);
        }
    }

    // ---------------- TR4.2【putDoc 改内容，term vector 不被修改】 ----------------
    @Test
    public void tr4_2_putDocChangeContentPreservesTermVector() throws IOException {
        long docA = 511L, docB = 512L;
        long uA = td.toUid(VEC_WORD_A, true);
        long uB = td.toUid(VEC_WORD_B, true);
        float[] vecA = vecForA();
        float[] vecB = vecForB();
        store.indexTermVector(uA, vecA);
        store.indexTermVector(uB, vecB);

        // 第 1 次 putDoc：VECA VECB X1 X2
        String t1 = VEC_WORD_A + " " + VEC_WORD_B + " X1 X2";
        store.putDoc(docA, t1);
        store.putDoc(docB, VEC_WORD_A + " Y1 Y2 Y3 Y4");
        store.recomputeIdfForTerms(Arrays.asList(uA, uB));
        WeightTermStatRow tsaBefore = store.getTermStatForTest(uA);
        WeightTermStatRow tsbBefore = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.2 前置 termA stat", tsaBefore);
        Assert.assertNotNull("TR4.2 前置 termB stat", tsbBefore);
        double idfABefore = tsaBefore.idf;
        double idfBBefore = tsbBefore.idf;
        float[] vecABefore = store.getTermVectorForTest(uA);
        float[] vecBBefore = store.getTermVectorForTest(uB);

        // 第 2 次 putDoc(docA, 新内容)：只保留 VECA，去掉 VECB，替换为 NEW1 NEW2
        String t2 = VEC_WORD_A + " NEW1 NEW2 NEW3";
        store.putDoc(docA, t2);

        // 断言：VECA tf 仍=1，VECB tf=0（因为在新 docA 中不出现），但 VECB 的 termVector 不被删除
        long pA = parentOf(docA);
        Assert.assertEquals("TR4.2 改内容后 docA VECA tf=1", 1, store.getTfForTest(uA, docA, pA, LV_WORD));
        Assert.assertEquals("TR4.2 改内容后 docA VECB tf=0（docA 不再含 VECB）", 0, store.getTfForTest(uB, docA, pA, LV_WORD));

        // 【核心断言】term vector（termStat.payload）的值不变，idf 也不重算（保留原数值）
        float[] vecAAfter = store.getTermVectorForTest(uA);
        float[] vecBAfter = store.getTermVectorForTest(uB);
        assertVecEquals("TR4.2 VECA vector 不改内容", vecA, vecAAfter);
        assertVecEquals("TR4.2 VECB vector 不被删除且值不变", vecB, vecBAfter);
        WeightTermStatRow tsaAfter = store.getTermStatForTest(uA);
        WeightTermStatRow tsbAfter = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.2 VECA termStat 保留", tsaAfter);
        Assert.assertNotNull("TR4.2 VECB termStat 保留（不随 docA 删 VECB 而删除）", tsbAfter);
        Assert.assertEquals("TR4.2 VECA idf 保留（不重算）", idfABefore, tsaAfter.idf, 1e-300);
        Assert.assertEquals("TR4.2 VECB idf 保留（不重算）", idfBBefore, tsbAfter.idf, 1e-300);

        // 再额外：docB 的 VECA tf 不受 docA 改写影响（独立 doc 隔离）
        long pB = parentOf(docB);
        Assert.assertEquals("TR4.2 独立 docB VECA tf=1（不受 docA 改写影响）", 1,
            store.getTfForTest(uA, docB, pB, LV_WORD));
    }

    // ---------------- TR4.3【clearDoc 保留 termStat / termVector：逐 float 全等】 ----------------
    @Test
    public void tr4_3_clearDocPreservesTermVectorAndIdf() throws IOException {
        long docA = 521L, docB = 522L;
        long uA = td.toUid(VEC_WORD_A, true);
        long uB = td.toUid(VEC_WORD_B, true);
        float[] vecA = vecForA();
        float[] vecB = vecForB();
        store.indexTermVector(uA, vecA);
        store.indexTermVector(uB, vecB);

        store.putDoc(docA, VEC_WORD_A + " " + VEC_WORD_B + " A1 A2 A3");
        store.putDoc(docB, VEC_WORD_A + " B1 B2 " + VEC_WORD_A);
        store.recomputeIdfForTerms(Arrays.asList(uA, uB));

        WeightTermStatRow tsaBefore = store.getTermStatForTest(uA);
        WeightTermStatRow tsbBefore = store.getTermStatForTest(uB);
        double idfA = tsaBefore.idf, idfB = tsbBefore.idf;
        float[] vecABefore = store.getTermVectorForTest(uA);
        float[] vecBBefore = store.getTermVectorForTest(uB);
        int pLenA = tsaBefore.payload == null ? 0 : tsaBefore.payload.length;
        int pLenB = tsbBefore.payload == null ? 0 : tsbBefore.payload.length;

        // clearDoc(docA) → 仅清 docA 的 tf/docStat，termStat/vec 完全保留
        store.clearDoc(docA);

        WeightTermStatRow tsaAfter = store.getTermStatForTest(uA);
        WeightTermStatRow tsbAfter = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.3 clear docA 后 VECA termStat 非 null", tsaAfter);
        Assert.assertNotNull("TR4.3 clear docA 后 VECB termStat 非 null", tsbAfter);
        Assert.assertEquals("TR4.3 VECA idf 精确不变", idfA, tsaAfter.idf, 1e-300);
        Assert.assertEquals("TR4.3 VECB idf 精确不变", idfB, tsbAfter.idf, 1e-300);
        Assert.assertEquals("TR4.3 VECA payload 字节长度不变", pLenA,
            tsaAfter.payload == null ? 0 : tsaAfter.payload.length);
        Assert.assertEquals("TR4.3 VECB payload 字节长度不变", pLenB,
            tsbAfter.payload == null ? 0 : tsbAfter.payload.length);

        float[] vecAAfter = store.getTermVectorForTest(uA);
        float[] vecBAfter = store.getTermVectorForTest(uB);
        assertVecEquals("TR4.3 VECA vector 值逐 float 全等（clear 不修改）", vecA, vecAAfter);
        assertVecEquals("TR4.3 VECB vector 值逐 float 全等（clear 不修改）", vecB, vecBAfter);

        // 再清 docB → termStat/termVector 仍保留（term_stat 永远不删，仅 idf 不主动重算）
        store.clearDoc(docB);
        WeightTermStatRow tsaFinal = store.getTermStatForTest(uA);
        WeightTermStatRow tsbFinal = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.3 clear docB 后 VECA termStat 仍非 null（不删除）", tsaFinal);
        Assert.assertNotNull("TR4.3 clear docB 后 VECB termStat 仍非 null（不删除）", tsbFinal);
        Assert.assertEquals("TR4.3 docB 清后 VECA idf 不变", idfA, tsaFinal.idf, 1e-300);
        Assert.assertEquals("TR4.3 docB 清后 VECB idf 不变", idfB, tsbFinal.idf, 1e-300);
        assertVecEquals("TR4.3 docB 清后 VECA vector 不变", vecA, store.getTermVectorForTest(uA));
        assertVecEquals("TR4.3 docB 清后 VECB vector 不变", vecB, store.getTermVectorForTest(uB));
    }

    // ---------------- TR4.4【putDoc(docId, "") = clearDoc：tf=0 docStat=null，termStat/vec 仍保留】 ----------------
    @Test
    public void tr4_4_putDocEmptyEqualsClearDocPreservesVector() throws IOException {
        long docId = 531L;
        long p = parentOf(docId);
        long uA = td.toUid(VEC_WORD_A, true);
        long uB = td.toUid(VEC_WORD_B, true);
        float[] vecA = vecForA();
        float[] vecB = vecForB();
        store.indexTermVector(uA, vecA);
        store.indexTermVector(uB, vecB);

        String text = VEC_WORD_A + " " + VEC_WORD_B + " P1 P2 P3 P4";
        store.putDoc(docId, text);
        store.recomputeIdfForTerms(Arrays.asList(uA, uB));
        double idfA = store.getTermStatForTest(uA).idf;
        double idfB = store.getTermStatForTest(uB).idf;

        // clear 前有效
        Assert.assertTrue("TR4.4 clear 前 VECA tf>0", store.getTfForTest(uA, docId, p, LV_WORD) > 0);
        Assert.assertTrue("TR4.4 clear 前 VECB tf>0", store.getTfForTest(uB, docId, p, LV_WORD) > 0);
        Assert.assertNotNull("TR4.4 clear 前 docStat != null", store.getDocStatForTest(docId));
        List<FulltextStore.ScoredDocHit> hitsBefore = store.search(VEC_WORD_A, 10, FulltextStore.SearchOptions.defaults());
        boolean foundBefore = hitsBefore.stream().anyMatch(h -> h.docId == docId);
        Assert.assertTrue("TR4.4 clear 前 search 命中 docId", foundBefore);

        // 【核心操作】putDoc(docId, "") —— 语义等价 clearDoc
        store.putDoc(docId, "");

        // 断言：tf=0 / docStat=null / search 不命中
        Assert.assertEquals("TR4.4 putDoc(\"\") 后 VECA tf=0", 0, store.getTfForTest(uA, docId, p, LV_WORD));
        Assert.assertEquals("TR4.4 putDoc(\"\") 后 VECB tf=0", 0, store.getTfForTest(uB, docId, p, LV_WORD));
        Assert.assertNull("TR4.4 putDoc(\"\") 后 docStat=null", store.getDocStatForTest(docId));
        List<FulltextStore.ScoredDocHit> hitsAfter = store.search(VEC_WORD_A, 10, FulltextStore.SearchOptions.defaults());
        for (FulltextStore.ScoredDocHit h : hitsAfter)
            Assert.assertNotEquals("TR4.4 putDoc(\"\") 后 search 不包含已清 docId", docId, h.docId);

        // 【关键额外断言】termStat 和 termVector 仍然保留（不删除不改写）
        WeightTermStatRow tsa = store.getTermStatForTest(uA);
        WeightTermStatRow tsb = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.4 putDoc(\"\") 后 VECA termStat 非 null（保留）", tsa);
        Assert.assertNotNull("TR4.4 putDoc(\"\") 后 VECB termStat 非 null（保留）", tsb);
        Assert.assertEquals("TR4.4 putDoc(\"\") 后 VECA idf 不变", idfA, tsa.idf, 1e-300);
        Assert.assertEquals("TR4.4 putDoc(\"\") 后 VECB idf 不变", idfB, tsb.idf, 1e-300);
        assertVecEquals("TR4.4 putDoc(\"\") 后 VECA vector 不被删除且值不变", vecA, store.getTermVectorForTest(uA));
        assertVecEquals("TR4.4 putDoc(\"\") 后 VECB vector 不被删除且值不变", vecB, store.getTermVectorForTest(uB));
    }
}
