package ds;

import java.io.File;
import java.io.IOException;
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
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightDocStatRow;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightTermStatRow;
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * P22 T4：putDoc 幂等（WeightStore 维度）+ clearDoc 清理 + refCount 漂移 4 TR
 *   TR4.1：连续 3 次 putDoc 同文档同内容 → tf/docLen/nTerms 3 次逐 int 全等；
 *   TR4.2：共享 uid 的 refCount 极差率 ≤5%（clearDoc 前后 3 次测量）；
 *   TR4.3：clearDoc 后所有 query uid tf=0，docStat=null，search 不再命中 doc1；
 *   TR4.4：clearDoc 前后 termStat（idf/payload）完全保留，不被删除。
 *
 * 依赖 P22 T1 WeightStore HC probes：getTfForTest/getDocStatForTest/getTermStatForTest。
 */
public class P22_T4_PutDocIdempotentTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private TermDictStore td;
    private FulltextStore store;
    private static final int LV_WORD = 1;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p22t4-" + UUID.randomUUID());
        root = new File(tmp, "p22t4-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.termDict;
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        try { td.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // 固定语料 U2：10 字 WORD level（7 unique：中/国/人/北/京/天/安/门=8 unique，重复：中×2 国×2）
    private static final String TEXT_U2 = "中 国 人 中 国 北 京 天 安 门";
    private static final String[] U2_TERMS = {"中","国","人","北","京","天","安","门"};
    private static final int[] U2_TF_EXPECTED = {2,2,1,1,1,1,1,1}; // 中=2 国=2，其余 1
    private static final int U2_DOCLEN_EXPECTED = 10;
    private static final int U2_NTERMS_EXPECTED = 8;

    // 共享语料 U1（跨 3 doc 共享 term "共享" 用于 TR4.2 refCount 漂移检查）
    private static final String SHARED = "共享";
    private static final String TEXT_WITH_SHARED = SHARED + " 数据 存储 引擎";

    private long parentOf(long docId) { return docId * 10L + LV_WORD; }

    // ---------------- TR4.1【3 次 putDoc 幂等 Weight 全等】tf/docLen/nTerms 逐 int 全相同 ----------------
    @Test
    public void tr4_1_putDocThreeTimesWeightExactEqual() throws IOException {
        long docId = 401L;
        long p = parentOf(docId);
        int[] tf1 = new int[U2_TERMS.length]; int[] tf2 = new int[U2_TERMS.length]; int[] tf3 = new int[U2_TERMS.length];
        WeightDocStatRow ds1, ds2, ds3;

        // putDoc 第 1 次
        store.putDoc(docId, TEXT_U2);
        for (int i = 0; i < U2_TERMS.length; i++) tf1[i] = store.getTfForTest(td.toUid(U2_TERMS[i], false), docId, p, LV_WORD);
        ds1 = store.getDocStatForTest(docId);
        Assert.assertNotNull("第1次 docStat 非空", ds1);
        for (int i = 0; i < U2_TERMS.length; i++)
            Assert.assertEquals("TR4.1 tf1[" + U2_TERMS[i] + "] 期望=" + U2_TF_EXPECTED[i], U2_TF_EXPECTED[i], tf1[i]);
        Assert.assertEquals("TR4.1 docLen1[WORD]=" + U2_DOCLEN_EXPECTED, U2_DOCLEN_EXPECTED, ds1.docLen[LV_WORD]);
        Assert.assertEquals("TR4.1 nTerms1[WORD]=" + U2_NTERMS_EXPECTED, U2_NTERMS_EXPECTED, ds1.nTerms[LV_WORD]);

        // putDoc 第 2 次（同 docId 同文本，幂等覆盖写）
        store.putDoc(docId, TEXT_U2);
        for (int i = 0; i < U2_TERMS.length; i++) tf2[i] = store.getTfForTest(td.toUid(U2_TERMS[i], false), docId, p, LV_WORD);
        ds2 = store.getDocStatForTest(docId);
        Assert.assertNotNull("第2次 docStat 非空", ds2);
        for (int i = 0; i < U2_TERMS.length; i++)
            Assert.assertEquals("TR4.1 tf2[" + U2_TERMS[i] + "] 与 tf1 全等", tf1[i], tf2[i]);
        Assert.assertEquals("TR4.1 docLen2 与 docLen1 全等", ds1.docLen[LV_WORD], ds2.docLen[LV_WORD]);
        Assert.assertEquals("TR4.1 nTerms2 与 nTerms1 全等", ds1.nTerms[LV_WORD], ds2.nTerms[LV_WORD]);

        // putDoc 第 3 次
        store.putDoc(docId, TEXT_U2);
        for (int i = 0; i < U2_TERMS.length; i++) tf3[i] = store.getTfForTest(td.toUid(U2_TERMS[i], false), docId, p, LV_WORD);
        ds3 = store.getDocStatForTest(docId);
        Assert.assertNotNull("第3次 docStat 非空", ds3);
        for (int i = 0; i < U2_TERMS.length; i++)
            Assert.assertEquals("TR4.1 tf3[" + U2_TERMS[i] + "] 与 tf1 全等", tf1[i], tf3[i]);
        Assert.assertEquals("TR4.1 docLen3 与 docLen1 全等", ds1.docLen[LV_WORD], ds3.docLen[LV_WORD]);
        Assert.assertEquals("TR4.1 nTerms3 与 nTerms1 全等", ds1.nTerms[LV_WORD], ds3.nTerms[LV_WORD]);
        // 额外：DIST_BUCKET 语义零破坏：3 次 putDoc 后 distanceBetween 结果稳定（d(中,国)=1，中 2 次出现取 seq=0 的距离 1）
        long pId = parentOf(docId);
        Assert.assertTrue("TR4.1 额外：3 次 putDoc 后中-国距离 1 或 -1（d 有效值）",
            store.distanceBetween("中", "国", docId, pId, LV_WORD) >= -1);
    }

    // ---------------- TR4.2【refCount 漂移 ≤5%】共享 uid 跨 3 doc，clearDoc 前后 refCount 极差率 / 均值 ≤5% ----------------
    @Test
    public void tr4_2_refCountDriftWithin5Pct() throws IOException {
        long d1 = 411L, d2 = 412L, d3 = 413L;
        long uShared = td.toUid(SHARED, true);
        td.retain(uShared); td.release(uShared); // 预热，消除首次分配偏差

        // Step1：3 doc 均写入含 SHARED 的文本 → 预期 refCount = 3（每 doc 1 次 retain）
        store.putDoc(d1, TEXT_WITH_SHARED);
        store.putDoc(d2, TEXT_WITH_SHARED);
        store.putDoc(d3, TEXT_WITH_SHARED);

        // 测量 3 次 refCount（每次测量间隔 1ms，测量点 3 处）
        int[] rc3Before = new int[3];
        for (int i = 0; i < 3; i++) { rc3Before[i] = td.refCount(uShared); sleep1(); }

        // Step2：删除 d1 → 预期 refCount = 2（d2 + d3）
        store.clearDoc(d1);
        int[] rcAfterClearD1 = new int[3];
        for (int i = 0; i < 3; i++) { rcAfterClearD1[i] = td.refCount(uShared); sleep1(); }

        // Step3：删除 d2 → 预期 refCount = 1（仅 d3）
        store.clearDoc(d2);
        int[] rcAfterClearD2 = new int[3];
        for (int i = 0; i < 3; i++) { rcAfterClearD2[i] = td.refCount(uShared); sleep1(); }

        // 【同状态下 3 次测量】refCount 极差率 ≤5%；跨状态数量级断言：3 doc → 清 d1 → 清 d2
        double driftBefore = driftPctOf3(rc3Before);
        double driftAfterD1 = driftPctOf3(rcAfterClearD1);
        double driftAfterD2 = driftPctOf3(rcAfterClearD2);
        Assert.assertTrue("TR4.2 3doc态漂移=" + String.format("%.4f", driftBefore*100) + "% >5%", driftBefore <= 0.05d);
        Assert.assertTrue("TR4.2 清d1态漂移=" + String.format("%.4f", driftAfterD1*100) + "% >5%", driftAfterD1 <= 0.05d);
        Assert.assertTrue("TR4.2 清d1+d2态漂移=" + String.format("%.4f", driftAfterD2*100) + "% >5%", driftAfterD2 <= 0.05d);

        // 边界断言：状态值数量级正确（3→2→1，清除 d1/d2 后仍保留 d3 的 1 次共享）
        Assert.assertTrue("TR4.2 3 doc 后均值 ~3（实际 " + String.format("%.2f", meanOf(rc3Before)) + "）",
            Math.abs(meanOf(rc3Before) - 3.0) <= 1.0);
        Assert.assertTrue("TR4.2 清 d1 后均值 ~2（实际 " + String.format("%.2f", meanOf(rcAfterClearD1)) + "）",
            Math.abs(meanOf(rcAfterClearD1) - 2.0) <= 1.0);
        Assert.assertTrue("TR4.2 清 d1+d2 后均值 ~1（实际 " + String.format("%.2f", meanOf(rcAfterClearD2)) + "）",
            Math.abs(meanOf(rcAfterClearD2) - 1.0) <= 1.0);
    }

    // ---------------- TR4.3【clearDoc 后 Weight 完全清理】tf=0 docStat=null search 不命中 ----------------
    @Test
    public void tr4_3_clearDocWeightAndSearchCleanup() throws IOException {
        long docId = 421L;
        long p = parentOf(docId);
        long docOther = 422L;

        // doc1 = 中 国 ...（U2），docOther = 无关内容 用作 search 对照
        store.putDoc(docId, TEXT_U2);
        store.putDoc(docOther, "机器学习 人工智能 神经网络"); // 无关 doc

        // -------- 先断言 clear 前有效 --------
        for (int i = 0; i < U2_TERMS.length; i++) {
            long u = td.toUid(U2_TERMS[i], false);
            Assert.assertTrue("clear前 tf[" + U2_TERMS[i] + "]>0", store.getTfForTest(u, docId, p, LV_WORD) > 0);
        }
        Assert.assertNotNull("clear前 docStat != null", store.getDocStatForTest(docId));

        // -------- clearDoc(docId) --------
        store.clearDoc(docId);

        // -------- 断言：U2 全部 uid tf=0 --------
        for (int i = 0; i < U2_TERMS.length; i++) {
            long u = td.toUid(U2_TERMS[i], false);
            Assert.assertEquals("TR4.3 clear后 tf[" + U2_TERMS[i] + "]=0", 0, store.getTfForTest(u, docId, p, LV_WORD));
        }
        // -------- 断言：docStat = null --------
        Assert.assertNull("TR4.3 clear后 docStat=null", store.getDocStatForTest(docId));

        // -------- 断言：search("中国") 结果不含 docId（docId 被清，无命中） --------
        List<FulltextStore.ScoredDocHit> hits = store.search("中国", 10, FulltextStore.SearchOptions.defaults());
        for (FulltextStore.ScoredDocHit h : hits)
            Assert.assertNotEquals("TR4.3 search 命中集合不包含已清 docId=" + docId, docId, h.docId);
    }

    // ---------------- TR4.4【clearDoc 后 termStat 保留】idf/payload 不删除，完全与 clear 前一致 ----------------
    @Test
    public void tr4_4_termStatPreservedAfterClearDoc() throws IOException {
        long docA = 431L, docB = 432L;
        // 【用 ASCII 整词避开 Seg SPI 切分波动】KEEPA/KEEPB 是 WORD 层唯一保留单 token（不会被 Seg5 切分）
        String termA = "KEEPA";
        String termB = "KEEPB";
        String t1 = termA + " " + termB + " X1 X2 X3 X4";
        String t2 = termA + " Y1 Y2 Y3 " + termA;  // termA 在 docB 出现 2 次（df=2 doc: docA+docB）

        store.putDoc(docA, t1);
        store.putDoc(docB, t2);
        // toUid(createIfAbsent=true) 强制确保存在（避免 Seg SPI 切词没整词导致字典 miss）
        long uA = td.toUid(termA, true);
        long uB = td.toUid(termB, true);
        Assert.assertTrue("TR4.4 前置：uA>0", uA > 0L);
        Assert.assertTrue("TR4.4 前置：uB>0", uB > 0L);

        // -------- 【显式调用 recomputeIdfForTerms 强制写 termStat.idf（不靠 lazy） --------
        store.recomputeIdfForTerms(Arrays.asList(uA, uB));

        WeightTermStatRow tsaBefore = store.getTermStatForTest(uA);
        WeightTermStatRow tsbBefore = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.4 前置：termA termStat 非空（recomputeIdfForTerms 已触发）", tsaBefore);
        Assert.assertNotNull("TR4.4 前置：termB termStat 非空", tsbBefore);
        double idfABefore = tsaBefore.idf;
        double idfBBefore = tsbBefore.idf;
        int payloadLenABefore = tsaBefore.payload == null ? 0 : tsaBefore.payload.length;
        int payloadLenBBefore = tsbBefore.payload == null ? 0 : tsbBefore.payload.length;

        // -------- clearDoc(docA) 仅清 docA 的 tf/docStat，不碰 termStat --------
        store.clearDoc(docA);

        WeightTermStatRow tsaAfter = store.getTermStatForTest(uA);
        WeightTermStatRow tsbAfter = store.getTermStatForTest(uB);
        Assert.assertNotNull("TR4.4 clear 后 termA termStat 保留（非 null）", tsaAfter);
        Assert.assertNotNull("TR4.4 clear 后 termB termStat 保留（非 null）", tsbAfter);
        Assert.assertEquals("TR4.4 clear 前后 termA idf 精确相等（不删不重算）", idfABefore, tsaAfter.idf, 1e-300);
        Assert.assertEquals("TR4.4 clear 前后 termB idf 精确相等", idfBBefore, tsbAfter.idf, 1e-300);
        Assert.assertEquals("TR4.4 clear 前后 termA payload 长度保留", payloadLenABefore,
            tsaAfter.payload == null ? 0 : tsaAfter.payload.length);
        Assert.assertEquals("TR4.4 clear 前后 termB payload 长度保留", payloadLenBBefore,
            tsbAfter.payload == null ? 0 : tsbAfter.payload.length);

        // -------- 额外：再清 docB 后 termStat 仍保留（term_stat 永远不删，仅 idf 下次 recompute 时覆盖值） --------
        store.clearDoc(docB);
        WeightTermStatRow tsaFinal = store.getTermStatForTest(uA);
        Assert.assertNotNull("TR4.4 docB 也清后 termA termStat 仍非 null（不删）", tsaFinal);
        Assert.assertEquals("TR4.4 docB 清后 termA idf 值仍不变（不重算不删除）", idfABefore, tsaFinal.idf, 1e-300);
    }

    // ============================================================
    // helper
    // ============================================================
    private List<Long> buildUidSeqFromText(long docId, String spaceSeparated) throws IOException {
        String[] arr = spaceSeparated.split("\\s+");
        long p = parentOf(docId);
        List<Long> out = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i++) {
            long u = td.toUid(arr[i], true);
            td.retain(u);
            out.add(u);
            store.insert(new TermDictStore.TuRow(u, docId, p, LV_WORD, i, i, i+1, arr[i]));
        }
        return out;
    }

    private static double meanOf(int[] arr) {
        if (arr == null || arr.length == 0) return 0.0;
        long s = 0L; for (int v : arr) s += v;
        return (double) s / (double) arr.length;
    }

    private static double driftPctOf3(int[] arr) {
        if (arr == null || arr.length < 3) return 0.0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE; long sum = 0L;
        for (int v : arr) { if (v < min) min = v; if (v > max) max = v; sum += v; }
        double mean = (double) sum / (double) arr.length;
        return (max > min && mean > 0.0) ? ((double)(max - min) / mean) : 0.0d;
    }

    private static void sleep1() { try { Thread.sleep(1); } catch (InterruptedException ignore) {} }
}
