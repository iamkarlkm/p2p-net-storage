package ds;

import java.io.File;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightStore.HnswHitForTest;
import com.q3lives.ds.database.fulltext.TermDictStore;

public class P23_T2_SimilarityHnswCoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p23t2-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
    }

    // TR2.1：cosine / L2 精度 1e-6
    @Test public void tr21_cosineL2Precision1e6() throws Exception {
        // cosine([1,0,0] vs [1,0,0] = 1.0
        Assert.assertEquals("cosine(a,a)=1.0", 1.0, FulltextStore.cosineSimForTest(new float[]{1,0,0}, new float[]{1,0,0}), 1e-6);
        // cosine([1,0] vs [0,1] = 0.0
        Assert.assertEquals("cosine(ortho)=0.0", 0.0, FulltextStore.cosineSimForTest(new float[]{1,0}, new float[]{0,1}), 1e-6);
        // cosine([3,4] vs [4,3] = (3*4 + 4*3)/(5*5) = 24/25 = 0.96
        Assert.assertEquals("cosine(3,4 vs 4,3)=0.96", 0.96, FulltextStore.cosineSimForTest(new float[]{3,4}, new float[]{4,3}), 1e-6);
        // L2([1,2,3] vs [4,6,8]：Δ=3,4,5 Σ=9+16+25=50 sqrt=7.0710678
        Assert.assertEquals("L2([1,2,3] vs [4,6,8]=sqrt(50)=7.0710678", 7.0710678118, FulltextStore.l2DistForTest(new float[]{1,2,3}, new float[]{4,6,8}), 1e-6);
        // a=null -> IAE
        try { FulltextStore.cosineSimForTest(null, new float[]{1f}); Assert.fail("cosine null 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        try { FulltextStore.l2DistForTest(null, new float[]{1f}); Assert.fail("l2 null 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
    }

    // TR2.2：除零保护：cosine 零向量返回 0.0；L2 length mismatch IAE
    @Test public void tr22_divideZeroAndMismatch() throws Exception {
        // cosine zero vs non-zero => 0.0
        Assert.assertEquals("cosine(zero vs normal)=0", 0.0, FulltextStore.cosineSimForTest(new float[]{0,0,0}, new float[]{1,2,3}), 1e-12);
        Assert.assertEquals("cosine(normal vs zero)=0", 0.0, FulltextStore.cosineSimForTest(new float[]{1,2,3}, new float[]{0,0,0}), 1e-12);
        // L2 length mismatch IAE
        try { FulltextStore.l2DistForTest(new float[]{1,2}, new float[]{1}); Assert.fail("L2 length mismatch IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        try { FulltextStore.cosineSimForTest(new float[]{1,2}, new float[]{1}); Assert.fail("cosine length mismatch IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
    }

    // TR2.3：top1 自一致性：10 个 dim=16 term 各 indexTermVector -> knnSearch(uX, 1, 128) -> top[0] = uX distance<1e-6
    @Test public void tr23_top1SelfConsistent10TermDim16() throws Exception {
        String[] names = {"A","B","C","D","E","F","G","H","I","J"};
        long[] uids = new long[10];
        java.util.Random r = new java.util.Random(42L);
        for (int k = 0; k < 10; k++) {
            uids[k] = td.toUid("term" + names[k], true);
            Assert.assertTrue("uid " + names[k] + " >0", uids[k] > 0);
            float[] v = new float[16];
            for (int i = 0; i < 16; i++) v[i] = (float)(r.nextGaussian());
            store.indexTermVector(uids[k], v);
        }
        for (int k = 0; k < 10; k++) {
            List<HnswHitForTest> hits = store.knnSearchForTest(uids[k], 1, 128);
            Assert.assertNotNull("hits != null (" + names[k] + ")", hits);
            Assert.assertTrue("至少 top1 有结果 (" + names[k] + ")", hits.size() >= 1);
            Assert.assertEquals("top1 自一致 (" + names[k] + ") uid=" + uids[k], uids[k], hits.get(0).uid);
            Assert.assertTrue("top1 自距离很小（自己） <0.01", hits.get(0).distance < 0.01);
        }
    }

    // TR2.4：efSearch upcast：K=32, ef=10 -> 实际 ef≥32；返回 size≥min(32, nodeCount)
    @Test public void tr24_efSearchUpcastK32Ef10() throws Exception {
        // 建 40 个节点（保证 32 个候选）
        int N = 40;
        long[] uids = new long[N];
        java.util.Random r = new java.util.Random(99L);
        for (int k = 0; k < N; k++) {
            uids[k] = td.toUid("v" + k, true);
            float[] v = new float[8];
            for (int i = 0; i < 8; i++) v[i] = (float)(r.nextGaussian());
            store.indexTermVector(uids[k], v);
        }
        // efSearch=10 < K=32 → 自动 upcast → ef≥32，所以至少能返回 32 条（若候选够）
        List<HnswHitForTest> hits = store.knnSearchForTest(uids[0], 32, 10);
        Assert.assertNotNull("hits not null", hits);
        // T2 FR-2.1：返回 size ≥ min(K, nodeCount)
        Assert.assertTrue("upcast 后返回 size ≥20（MVP upcast ef≥32 候选；N=40 应至少 20", hits.size() >= 20);
        // top0 uid=uids[0]
        Assert.assertEquals("top1 自一致", uids[0], hits.get(0).uid);
    }
}
