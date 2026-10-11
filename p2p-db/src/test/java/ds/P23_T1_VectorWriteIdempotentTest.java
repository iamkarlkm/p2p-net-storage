package ds;

import java.io.File;
import java.util.Arrays;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Similarity;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightStore.HnswMetaForTest;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightTermStatRow;
import com.q3lives.ds.database.fulltext.TermDictStore;

public class P23_T1_VectorWriteIdempotentTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p23t1-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
    }

    // TR1.1：vec768 3× indexTermVector 幂等；逐元素 1e-9 全等；HnswMeta.nodeCount==1（没膨胀）
    @Test public void tr11_vec768ThreeTimesIdempotent() throws Exception {
        long uA = td.toUid("termA", true);
        Assert.assertTrue("uA > 0 USER-HC-0", uA > 0);
        float[] vec768 = new float[768];
        for (int i = 0; i < vec768.length; i++) vec768[i] = (float)((i % 37) - 18) / 7.0f;
        // 3× put 幂等
        store.indexTermVector(uA, vec768);
        store.indexTermVector(uA, vec768);
        store.indexTermVector(uA, vec768);
        float[] got = store.getTermVectorForTest(uA);
        Assert.assertNotNull("getTermVectorForTest(uA) != null", got);
        Assert.assertEquals("dim=768 exact", 768, got.length);
        for (int i = 0; i < 768; i++) {
            Assert.assertEquals("vec768[" + i + "] 1e-9 全等", vec768[i], got[i], 1e-9);
        }
        HnswMetaForTest meta = store.getHnswMetaForTest();
        Assert.assertNotNull("meta != null", meta);
        Assert.assertEquals("M=16 冻结", 16, meta.M);
        Assert.assertEquals("maxLevel=16 冻结", 16, meta.maxLevel);
        Assert.assertEquals("efConstruction=128 默认", 128, meta.efConstruction);
        Assert.assertEquals("nodeCount == 1（3× 幂等没膨胀）", 1, meta.nodeCount);
        Assert.assertEquals("entryPointUid == uA", uA, meta.entryPointUid);
    }

    // TR1.2：dim=4096 max 正常；dim=0/8192/uid=0/uid=-1/null vec IAE
    @Test public void tr12_dimMaxAndIllegalIae() throws Exception {
        long uB = td.toUid("termB", true);
        // dim=4096 上限正常
        float[] v4096 = new float[4096];
        for (int i = 0; i < 4096; i++) v4096[i] = (float)Math.sin(i * 0.01);
        store.indexTermVector(uB, v4096);
        float[] got4096 = store.getTermVectorForTest(uB);
        Assert.assertEquals("dim=4096 exact", 4096, got4096.length);
        for (int i = 0; i < 4096; i++) Assert.assertEquals("v4096[" + i + "]", v4096[i], got4096[i], 1e-9);
        // 非法：dim=0
        try { store.indexTermVector(uB, new float[0]); Assert.fail("dim=0 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        // 非法：dim=8192 > 4096
        try { store.indexTermVector(uB, new float[8192]); Assert.fail("dim=8192 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        // 非法：uid=0
        try { store.indexTermVector(0L, new float[]{1.0f, 2.0f}); Assert.fail("uid=0 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        // 非法：uid=-1
        try { store.indexTermVector(-1L, new float[]{1.0f}); Assert.fail("uid=-1 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
        // 非法：null vec
        try { store.indexTermVector(uB, null); Assert.fail("null vec 必须 IAE"); }
        catch (IllegalArgumentException e) { /* expected */ }
    }

    // TR1.3：idf 保留不被 vector 写入覆盖
    @Test public void tr13_idfPreservedAfterIndexVector() throws Exception {
        long uA = td.toUid("wordA", true);
        long uB = td.toUid("wordB", true);
        final long DOC1 = 1L, DOC2 = 2L, PARENT = 0L; final int LV = TermDictStore.DS1_WORD;
        // 先写 doc1: [uA, uB]，doc2: [uA]，使 uA 的 df=2 N=2 -> idf = log((2-2+0.5)/(2+0.5)+1) = log(0.5/2.5+1)=log(1.2)
        store.buildDistForSeq(DOC1, PARENT, LV, Arrays.asList(uA, uB));
        store.buildDistForSeq(DOC2, PARENT, LV, Arrays.asList(uA));
        store.recomputeIdfForTerms(Arrays.asList(uA, uB));
        WeightTermStatRow statBefore = store.getTermStatForTest(uA);
        Assert.assertNotNull("statBefore != null", statBefore);
        double idfBefore = statBefore.idf;
        Assert.assertTrue("uA idf > 0（df=1/2）", idfBefore > 0.0);
        // 写 uA vector
        float[] v = new float[]{1.0f, 2.0f, 3.0f};
        store.indexTermVector(uA, v);
        WeightTermStatRow statAfter = store.getTermStatForTest(uA);
        Assert.assertNotNull("statAfter != null", statAfter);
        Assert.assertEquals("idf 1e-9 全等（indexTermVector 绝不覆盖 idf）", idfBefore, statAfter.idf, 1e-9);
        float[] got = store.getTermVectorForTest(uA);
        Assert.assertArrayEquals("vector 保留写入", v, got, 1e-9f);
        // 再 recomputeIdf 一次：vector payload 保留
        store.recomputeIdfForTerms(Arrays.asList(uA));
        float[] got2 = store.getTermVectorForTest(uA);
        Assert.assertArrayEquals("recomputeIdf 后 vector 依旧保留", v, got2, 1e-9f);
    }

    // TR1.4：空 WeightStore 探针不崩；HNSW meta 默认值
    @Test public void tr14_emptyStoreNoCrashAndMetaDefaults() throws Exception {
        float[] got = store.getTermVectorForTest(12345L);
        Assert.assertNull("空 store getTermVector(12345)=null", got);
        HnswMetaForTest meta = store.getHnswMetaForTest();
        Assert.assertNotNull("空 meta != null", meta);
        Assert.assertEquals("空 M=16", 16, meta.M);
        Assert.assertEquals("空 maxLevel=16", 16, meta.maxLevel);
        Assert.assertEquals("空 efConstruction=128", 128, meta.efConstruction);
        Assert.assertEquals("空 nodeCount=0", 0, meta.nodeCount);
        Assert.assertEquals("空 entryPointUid=0", 0L, meta.entryPointUid);
        // Similarity enum 存在 COSINE/L2（合成 values 方法，Facade 计数 +2）
        Assert.assertNotNull("Similarity.COSINE != null", Similarity.COSINE);
        Assert.assertNotNull("Similarity.L2 != null", Similarity.L2);
        Assert.assertEquals("Similarity 枚举 size=2", 2, Similarity.values().length);
    }
}
