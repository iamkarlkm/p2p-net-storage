package ds;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
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
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightDocStatRow;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightTermStatRow;

/**
 * P24 T4：putDocCluster 3×幂等 + clearDocCluster 保留 termStat/vector 4 TR
 *   TR4.1：连续 3 次 putDocCluster 同 docId 同内容 → tf/docLen/nTerms/searchCluster finalScore 4 项逐值全等；
 *   TR4.2：9 个共享 uid 的 target shard refCount 极差率 ≤5%（3 次 putDocCluster 前后测量）；
 *   TR4.3：clearDocCluster(D0) 后，仅 D0 所在 target shard 的 docStat=null，其他 shard docStat 不触动；共享 term 的 vec/idf 保留；
 *   TR4.4：clear 2 doc 后 searchCluster 不再命中此 2 doc，但 termStat/termVector 仍保留（不删除）。
 */
public class P24_T4_PutDocClusterIdempotentTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private static final int N = 4;
    private static final int LV_WORD = 1;
    private File root;
    private FulltextStore.ShardRouter router;

    private static final String TEXT_U2 = "中 国 人 中 国 北 京 天 安 门";
    private static final String[] U2_TERMS = {"中","国","人","北","京","天","安","门"};
    private static final int[] U2_TF_EXPECTED = {2,2,1,1,1,1,1,1};

    private static final String VEC_WORD_A = "VECA";
    private static final String VEC_WORD_B = "VECB";
    private static final int VEC_DIM = 16;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p24t4-" + UUID.randomUUID());
        root = new File(tmp, "p24t4-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
    }

    @After public void tearDown() {
        try { router.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    private static long parentOf(long docId) { return docId * 10L + LV_WORD; }
    private static float[] vecForA() { float[] r=new float[VEC_DIM]; for(int i=0;i<VEC_DIM;i++) r[i]=(float)Math.sin(i+1.0); return r; }
    private static float[] vecForB() { float[] r=new float[VEC_DIM]; for(int i=0;i<VEC_DIM;i++) r[i]=(float)Math.cos(i+2.0); return r; }
    private static void assertVecEquals(String m,float[]e,float[]a){
        Assert.assertNotNull(m+" e null",e); Assert.assertNotNull(m+" a null",a);
        Assert.assertEquals(m+" dim",e.length,a.length);
        for(int i=0;i<e.length;i++) Assert.assertEquals(m+"["+i+"]",e[i],a[i],1e-30f);
    }

    // ------------- TR4.1 3× putDocCluster 幂等（tf/docLen/nTerms/finalScore） -------------
    @Test public void tr41_threeTimesPutIdempotentCluster() throws IOException {
        // 手挑 2 个 docId 落在不同 shard
        long[] perShard = new long[N]; Arrays.fill(perShard,-1L);
        long probe=100_000L; int got=0;
        while(got<2){ int s=FulltextStore.resolveShardForDocIdForTest(probe,N);
            if(perShard[s]==-1L){perShard[s]=probe;got++;} probe+=7; }
        long D0 = perShard[0]!=-1L? perShard[0] : 100001L;
        long D1 = perShard[1]!=-1L? perShard[1] : 100008L;
        int s0 = FulltextStore.resolveShardForDocIdForTest(D0,N);
        int s1 = FulltextStore.resolveShardForDocIdForTest(D1,N);
        // 先建 uid + vec
        long uA = router.termDictToUidForTest(s0, VEC_WORD_A, true);
        long uB = router.termDictToUidForTest(s0, VEC_WORD_B, true);
        float[] vA = vecForA(), vB = vecForB();
        router.indexTermVectorCluster(uA, vA);
        router.indexTermVectorCluster(uB, vB);
        String text = VEC_WORD_A+" "+VEC_WORD_B+" "+TEXT_U2;
        // 3 rounds
        int[][][] tf = new int[3][2][U2_TERMS.length+2];
        WeightDocStatRow[][] ds = new WeightDocStatRow[3][2];
        double[][] fs0 = new double[3][2]; // D0/D1 searchCluster finalScore
        for(int r=0;r<3;r++){
            router.putDocCluster(D0, text);
            router.putDocCluster(D1, text);
            for(int d=0;d<2;d++){
                long did = (d==0)?D0:D1; int sh=(d==0)?s0:s1; long p=parentOf(did);
                for(int i=0;i<U2_TERMS.length;i++){
                    long uid = router.termDictToUidForTest(sh, U2_TERMS[i], false);
                    tf[r][d][i]=router.getTfForTest(sh, uid, did, p, LV_WORD);
                }
                tf[r][d][U2_TERMS.length]  =router.getTfForTest(sh, uA, did, p, LV_WORD);
                tf[r][d][U2_TERMS.length+1]=router.getTfForTest(sh, uB, did, p, LV_WORD);
                ds[r][d]=router.getDocStatForTest(sh, did);
            }
            SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
            List<ScoredDocHit> hits=router.searchCluster(VEC_WORD_A, null, 10, o);
            java.util.Map<Long,Double> fmap=new java.util.HashMap<>();
            for(ScoredDocHit h:hits) fmap.put(h.docId,h.finalScore);
            fs0[r][0]=fmap.getOrDefault(D0,0.0);
            fs0[r][1]=fmap.getOrDefault(D1,0.0);
        }
        // baseline check r=0
        for(int d=0;d<2;d++){
            for(int i=0;i<U2_TERMS.length;i++)
                Assert.assertEquals("tr41 r0 D"+d+" tf["+U2_TERMS[i]+"]",U2_TF_EXPECTED[i],tf[0][d][i]);
            Assert.assertEquals("tr41 r0 D"+d+" tf[VECA]",1, tf[0][d][U2_TERMS.length]);
            Assert.assertEquals("tr41 r0 D"+d+" tf[VECB]",1, tf[0][d][U2_TERMS.length+1]);
            Assert.assertNotNull("tr41 r0 D"+d+" ds not null",ds[0][d]);
            Assert.assertEquals("tr41 r0 D"+d+" docLen[WORD]", 12, ds[0][d].docLen[LV_WORD]);
            Assert.assertEquals("tr41 r0 D"+d+" nTerms[WORD]",10, ds[0][d].nTerms[LV_WORD]);
        }
        // idempotent across 3 rounds
        for(int r=1;r<3;r++) for(int d=0;d<2;d++){
            for(int i=0;i<tf[0][d].length;i++)
                Assert.assertEquals("tr41 r"+r+" D"+d+" tf["+i+"] idem",tf[0][d][i],tf[r][d][i]);
            Assert.assertEquals("tr41 r"+r+" D"+d+" docLen idem",ds[0][d].docLen[LV_WORD],ds[r][d].docLen[LV_WORD]);
            Assert.assertEquals("tr41 r"+r+" D"+d+" nTerms idem",ds[0][d].nTerms[LV_WORD],ds[r][d].nTerms[LV_WORD]);
            Assert.assertEquals("tr41 r"+r+" D"+d+" finalScore idem",fs0[0][d],fs0[r][d],1e-9);
        }
        // cluster isolation：D0/D1 docStat 不在对方 shard 出现
        for(int s=0;s<N;s++){
            Set<Long> ks=router.weightStoreAllDocKeysForTest(s);
            if(s!=s0) Assert.assertFalse("tr41 shard "+s+" 不含 D0",ks.contains(D0));
            if(s!=s1) Assert.assertFalse("tr41 shard "+s+" 不含 D1",ks.contains(D1));
        }
    }

    // ------------- TR4.2 9 个共享 term 的 refCount 极差率 ≤5% -------------
    @Test public void tr42_refCountDriftLte5Pct() throws IOException {
        // 9 个共享 term（含 VECA/VECB + U2 的前 7 个 unique）
        String[] terms9 = {VEC_WORD_A, VEC_WORD_B, U2_TERMS[0], U2_TERMS[1], U2_TERMS[2],
                           U2_TERMS[3], U2_TERMS[4], U2_TERMS[5], U2_TERMS[6]};
        // 手挑 3 个 docId 全部落在 shard=0（单 shard 内部 refCount 统计无跨 shard 干扰）
        long[] docs=new long[3]; int docShard = 0; long probe=200_000L; int got=0;
        while(got<3){ int s=FulltextStore.resolveShardForDocIdForTest(probe,N);
            if(s==docShard){docs[got++]=probe;} probe+=3; }
        // 先在目标 shard termDict 创建 uid（保证 toUid 被调用）并建 vector
        long[] uids = new long[9];
        for(int i=0;i<9;i++){
            uids[i] = router.termDictToUidForTest(docShard, terms9[i], true);
            if(i<2) router.indexTermVectorCluster(uids[i], (i==0)?vecForA():vecForB());
        }
        String text = String.join(" ", terms9);
        // Step1：写入 3 docs 进入稳态（putDocCluster = clear + ingest）
        for(long d:docs) router.putDocCluster(d, text);
        // 稳态下：对 9 个 term 连续 3 次纯读 refCount，不做任何 mutate → 9*3=27 点极差率 / 均值 ≤5%
        int[][] ref = new int[9][3]; // [term][3 reads]
        for(int r=0;r<3;r++){
            for(int i=0;i<9;i++) ref[i][r] = router.termDictRefCountForTest(docShard, uids[i]);
        }
        for(int i=0;i<9;i++){
            int mn=Integer.MAX_VALUE,mx=Integer.MIN_VALUE; long sum=0L;
            for(int r=0;r<3;r++){ mn=Math.min(mn,ref[i][r]); mx=Math.max(mx,ref[i][r]); sum+=ref[i][r]; }
            double mean = (double)sum/3.0;
            double drift = (mx>mn && mean>0.0)? ((double)(mx-mn)/mean):0.0;
            Assert.assertTrue("tr42 term["+terms9[i]+"] mn="+mn+" mx="+mx+" mean="+mean+" drift="+drift+" <=0.05",
                              drift<=0.05+1e-9);
        }
        // Step2：clear docs[0] → 再 3 次连测 → drift ≤5%（均值较 step1 应下降，因少 1 doc）
        router.clearDocCluster(docs[0]);
        int[][] refA = new int[9][3];
        for(int r=0;r<3;r++) for(int i=0;i<9;i++) refA[i][r]=router.termDictRefCountForTest(docShard,uids[i]);
        for(int i=0;i<9;i++){
            int mn=Integer.MAX_VALUE,mx=Integer.MIN_VALUE; long sum=0L;
            for(int r=0;r<3;r++){ mn=Math.min(mn,refA[i][r]); mx=Math.max(mx,refA[i][r]); sum+=refA[i][r]; }
            double mean=(double)sum/3.0;
            double drift=(mx>mn&&mean>0.0)?((double)(mx-mn)/mean):0.0;
            Assert.assertTrue("tr42 clear D0 态 term["+terms9[i]+"] drift="+drift+" <=0.05", drift<=0.05+1e-9);
        }
        // Step3：再 clear docs[1] → 3 次连测 → drift ≤5%（仅 docs[2] 保留 1 份引用）
        router.clearDocCluster(docs[1]);
        int[][] refB = new int[9][3];
        for(int r=0;r<3;r++) for(int i=0;i<9;i++) refB[i][r]=router.termDictRefCountForTest(docShard,uids[i]);
        for(int i=0;i<9;i++){
            int mn=Integer.MAX_VALUE,mx=Integer.MIN_VALUE; long sum=0L;
            for(int r=0;r<3;r++){ mn=Math.min(mn,refB[i][r]); mx=Math.max(mx,refB[i][r]); sum+=refB[i][r]; }
            double mean=(double)sum/3.0;
            double drift=(mx>mn&&mean>0.0)?((double)(mx-mn)/mean):0.0;
            Assert.assertTrue("tr42 clear D0+D1 态 term["+terms9[i]+"] drift="+drift+" <=0.05", drift<=0.05+1e-9);
        }
    }

    // ------------- TR4.3 clearDocCluster 仅清 target shard docStat，vec/idf 保留 -------------
    @Test public void tr43_clearClusterPreservesVectorIdfAndShardIsolation() throws IOException {
        long D0=300_001L, D1=300_008L;
        int s0 = FulltextStore.resolveShardForDocIdForTest(D0,N);
        int s1 = FulltextStore.resolveShardForDocIdForTest(D1,N);
        long uA = router.termDictToUidForTest(s0, VEC_WORD_A, true);
        long uB = router.termDictToUidForTest(s0, VEC_WORD_B, true);
        float[] vA=vecForA(), vB=vecForB();
        router.indexTermVectorCluster(uA, vA); router.indexTermVectorCluster(uB, vB);
        String t = VEC_WORD_A+" "+VEC_WORD_B+" 苹果 香蕉";
        router.putDocCluster(D0,t); router.putDocCluster(D1,t);
        int svA = FulltextStore.resolveShardForUnitIdForTest(uA,N);
        int svB = FulltextStore.resolveShardForUnitIdForTest(uB,N);
        WeightTermStatRow tsaBef = router.getTermStatForTest(svA, uA);
        WeightTermStatRow tsbBef = router.getTermStatForTest(svB, uB);
        double idfA = tsaBef==null?0.0:tsaBef.idf, idfB=tsbBef==null?0.0:tsbBef.idf;
        WeightDocStatRow d0s0Bef = router.getDocStatForTest(s0, D0);
        WeightDocStatRow d1s1Bef = router.getDocStatForTest(s1, D1);
        Assert.assertNotNull("tr43 D0 docStat pre-clear s0", d0s0Bef);
        Assert.assertNotNull("tr43 D1 docStat pre-clear s1", d1s1Bef);
        // docStat on *other* shard must be null (isolation)
        for(int s=0;s<N;s++){ if(s!=s0) Assert.assertNull("tr43 D0 null shard "+s, router.getDocStatForTest(s,D0));
                             if(s!=s1) Assert.assertNull("tr43 D1 null shard "+s, router.getDocStatForTest(s,D1)); }
        // --- clear only D0 ---
        router.clearDocCluster(D0);
        Assert.assertNull("tr43 D0 s0 docStat post-clear", router.getDocStatForTest(s0,D0));
        Assert.assertNotNull("tr43 D1 s1 docStat preserved", router.getDocStatForTest(s1,D1));
        // vec/idf 保留
        WeightTermStatRow tsaAf = router.getTermStatForTest(svA, uA);
        WeightTermStatRow tsbAf = router.getTermStatForTest(svB, uB);
        Assert.assertNotNull("tr43 VECA termStat not null", tsaAf);
        Assert.assertNotNull("tr43 VECB termStat not null", tsbAf);
        Assert.assertEquals("tr43 VECA idf 保留", idfA, tsaAf.idf, 1e-300);
        Assert.assertEquals("tr43 VECB idf 保留", idfB, tsbAf.idf, 1e-300);
        float[] vAaf = router.getTermVectorForTest(svA, uA);
        float[] vBaf = router.getTermVectorForTest(svB, uB);
        assertVecEquals("tr43 VECA vec 保留", vA, vAaf);
        assertVecEquals("tr43 VECB vec 保留", vB, vBaf);
    }

    // ------------- TR4.4 clear D0/D1 后 searchCluster 不命中；termStat 保留 -------------
    @Test public void tr44_clearTwoDocsSearchMissButTermStatRemain() throws IOException {
        long D0=400_001L, D1=400_008L;
        int s0 = FulltextStore.resolveShardForDocIdForTest(D0,N);
        int s1 = FulltextStore.resolveShardForDocIdForTest(D1,N);
        long uA = router.termDictToUidForTest(s0, VEC_WORD_A, true);
        long uB = router.termDictToUidForTest(s0, VEC_WORD_B, true);
        float[] vA=vecForA(), vB=vecForB();
        router.indexTermVectorCluster(uA, vA); router.indexTermVectorCluster(uB, vB);
        String text = VEC_WORD_A+" "+VEC_WORD_B+" "+TEXT_U2;
        router.putDocCluster(D0, text); router.putDocCluster(D1, text);
        SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        List<ScoredDocHit> hb=router.searchCluster(VEC_WORD_A, null, 20, o);
        boolean f0=false,f1=false; for(ScoredDocHit h:hb){ if(h.docId==D0)f0=true; if(h.docId==D1)f1=true; }
        Assert.assertTrue("tr44 preclear D0 命中",f0); Assert.assertTrue("tr44 preclear D1 命中",f1);
        // --- clear 2 docs ---
        router.clearDocCluster(D0); router.clearDocCluster(D1);
        List<ScoredDocHit> ha=router.searchCluster(VEC_WORD_A, null, 20, o);
        for(ScoredDocHit h:ha){ Assert.assertNotEquals("tr44 postclear 不含 D0",D0,h.docId); Assert.assertNotEquals("tr44 postclear 不含 D1",D1,h.docId); }
        // termStat/termVector 保留 (target UNIT shard)
        int sA = FulltextStore.resolveShardForUnitIdForTest(uA,N);
        int sB = FulltextStore.resolveShardForUnitIdForTest(uB,N);
        WeightTermStatRow tsa = router.getTermStatForTest(sA, uA);
        WeightTermStatRow tsb = router.getTermStatForTest(sB, uB);
        Assert.assertNotNull("tr44 postclear VECA termStat 非 null", tsa);
        Assert.assertNotNull("tr44 postclear VECB termStat 非 null", tsb);
        assertVecEquals("tr44 postclear VECA vec 保留", vA, router.getTermVectorForTest(sA, uA));
        assertVecEquals("tr44 postclear VECB vec 保留", vB, router.getTermVectorForTest(sB, uB));
    }
}
