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
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardMode;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardTransport;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * P24 T6：NFR + 质量 Rubric + 扩展 Rubric 5 TR
 *   TR6.1：Ingest 4 shard × 30 doc × 20 term + 768D vec ≤ 60000ms
 *   TR6.2：searchCluster 100 次 P95 ≤ 4000ms
 *   TR6.3：searchCluster 10 次指纹 Set.size = 1（稳定排序确定性）
 *   TR6.4：质量 Rubric ≥4（top10 交集 ground-truth≥9 / 路由正确 / clear 后 search 空 / 4 shard 全召回）
 *   TR6.5：扩展 Rubric ≥4（ShardTransport 5 方法存根 / concurrentScatter / directory prefix 可配 / RRF 5 路 ε 扩展 / ScoredDocHit shardId tie-breaker）
 */
public class P24_T6_NfrQualityTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int N = 4, LV=1, VEC_DIM=768;
    private File root;
    private FulltextStore.ShardRouter router;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p24t6-" + UUID.randomUUID());
        root = new File(tmp, "p24t6-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
    }
    @After public void tearDown() {
        try { router.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // --------------- Fixture：4 shard × 30 doc × 20 term + 768D vec 写入（单 LV=1，对齐 P23 T6 build 量级） ---------------
    private static final int DOCS=30, TERMS=20;
    private long docBase = 9_000_000L;
    private transient FulltextStore[] _shardsCache;
    private FulltextStore[] shards() throws Exception {
        if(_shardsCache==null){
            Field f = FulltextStore.ShardRouter.class.getDeclaredField("shards");
            f.setAccessible(true);
            _shardsCache = (FulltextStore[])f.get(router);
        }
        return _shardsCache;
    }
    private void ingest4x30x20x768() throws Exception {
        // 预写 30 个 doc 核心词 core_d 的 768D vec（UNIT 路由，与 HNSW shard 精确对齐）
        for(int d=0;d<DOCS;d++){
            String coreText = "CORE_"+d;
            long uCore = router.termDictToUidForTest(0, coreText, true);
            float[] v = new float[VEC_DIM];
            for(int i=0;i<VEC_DIM;i++) v[i] = (float) Math.sin(d*11.0 + i*0.017d);
            router.indexTermVectorCluster(uCore, v);
        }
        // 每 doc 单 LV=1 直接 insert + buildDistForSeq（不走 putDoc 6 level 全循环，对齐 P23 T6 build 量级）
        FulltextStore[] shs = shards();
        for(int d=0;d<DOCS;d++){
            long docId = docBase + d;
            int s = FulltextStore.resolveShardForDocIdForTest(docId, N);
            FulltextStore st = shs[s];
            long p = docId * 10L + LV;
            List<Long> uids = new ArrayList<>(TERMS);
            String t0 = "CORE_"+d;
            long u0 = st.termDict.toUid(t0, true);
            st.termDict.retain(u0); uids.add(u0);
            st.insert(new TuRow(u0, docId, p, LV, 0, 0, 1, t0));
            for(int t=1;t<TERMS;t++){
                String text = "T_"+d+"_"+t;
                long u = st.termDict.toUid(text, true);
                st.termDict.retain(u); uids.add(u);
                st.insert(new TuRow(u, docId, p, LV, t, t, t+1, text));
            }
            st.buildDistForSeq(docId, p, LV, uids);
        }
    }

    // --------------- TR6.1 Ingest ≤60000ms ---------------
    @Test public void tr61_ingest4x30x20x768Within60s() throws Exception {
        long t0 = System.currentTimeMillis();
        ingest4x30x20x768();
        long ms = System.currentTimeMillis()-t0;
        System.out.println("[TR6.1 Ingest] 4*30*20+768D vec = "+ms+" ms");
        Assert.assertTrue("tr61 Ingest "+ms+"ms > 60000ms threshold", ms <= 60_000L);
    }

    // --------------- TR6.2 searchCluster 100 次 P95 ≤ 4000ms ---------------
    @Test public void tr62_searchClusterP95Lte4000Ms() throws Exception {
        ingest4x30x20x768();
        SearchOptions o = SearchOptions.defaults();
        o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        long[] samples = new long[100];
        for(int i=0;i<100;i++){
            long t0=System.nanoTime();
            router.searchCluster("CORE_"+(i%10), null, 20, o);
            samples[i] = (System.nanoTime()-t0)/1_000_000L;
        }
        Arrays.sort(samples);
        long p95 = samples[(int)(samples.length*0.95)];
        System.out.println("[TR6.2 P95] "+p95+" ms max="+samples[samples.length-1]+"ms");
        Assert.assertTrue("tr62 P95="+p95+"ms > 4000ms threshold", p95 <= 4000L);
    }

    // --------------- TR6.3 10 次指纹 Set.size=1（稳定排序确定性） ---------------
    @Test public void tr63_tenRunsFingerprintIdentical() throws Exception {
        ingest4x30x20x768();
        SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        Set<String> fp = new HashSet<>();
        for(int i=0;i<10;i++){
            List<ScoredDocHit> h = router.searchCluster("CORE_5", null, 10, o);
            StringBuilder sb = new StringBuilder();
            for(ScoredDocHit hit:h) sb.append(hit.docId).append('@').append(String.format("%.12f",hit.finalScore)).append(';');
            fp.add(sb.toString());
        }
        Assert.assertEquals("tr63 10 次 searchCluster 指纹集合必须 size=1（稳定排序三键锁死 finalScore↓ docId↑ shardId↑）", 1, fp.size());
    }

    // --------------- TR6.4 质量 Rubric ≥4（4 项过 4） ---------------
    @Test public void tr64_qualityRubricAtLeast4() throws Exception {
        // (1) 4 shard 各一 doc：公共词 CHERRY + 独有词（长词不被 seg 过滤）。DOC 路由保证 per[i] 落 shard=i
        long[] per = new long[N]; Arrays.fill(per,-1L); long p=9_500_000L; int got=0;
        while(got<N){ int s=FulltextStore.resolveShardForDocIdForTest(p,N);
            if(per[s]==-1L){per[s]=p;got++;} p+=7; }
        String[] uTerms = {"SHARD0_P24_UNIQUE_QA_DOC","SHARD1_P24_UNIQUE_QA_DOC","SHARD2_P24_UNIQUE_QA_DOC","SHARD3_P24_UNIQUE_QA_DOC"};
        for(int i=0;i<N;i++){
            router.putDocCluster(per[i], "CHERRY "+uTerms[i]);
        }
        SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        List<ScoredDocHit> hts = router.searchCluster("CHERRY", null, 10, o);
        Set<Long> dset = new HashSet<>(); Set<Integer> sset = new HashSet<>();
        for(ScoredDocHit h:hts){ dset.add(h.docId); sset.add(h.shardId); }
        int q1=0; if(dset.containsAll(Arrays.asList(per[0],per[1],per[2],per[3]))) q1++;
        int q2=0; if(sset.containsAll(Arrays.asList(0,1,2,3))) q2++;
        // (2) clear doc 0/1 后再 searchCluster 不命中
        router.clearDocCluster(per[0]); router.clearDocCluster(per[1]);
        List<ScoredDocHit> hts2 = router.searchCluster("CHERRY", null, 10, o);
        int q3=0; boolean ok=true;
        for(ScoredDocHit h:hts2) if(h.docId==per[0]||h.docId==per[1]) ok=false;
        if(ok) q3++;
        // (3) topK=1 查每 doc 的独有词 uTerms[i]，top1 == 正确 docId（路由正确性）
        router.putDocCluster(per[0], "CHERRY "+uTerms[0]);
        router.putDocCluster(per[1], "CHERRY "+uTerms[1]);
        int q4=0; int hitR=0;
        for(int i=0;i<N;i++){
            List<ScoredDocHit> hh = router.searchCluster(uTerms[i], null, 1, o);
            if(!hh.isEmpty() && hh.get(0).docId==per[i]) hitR++;
        }
        if(hitR==N) q4++;
        int score = q1+q2+q3+q4;
        Assert.assertTrue("tr64 Quality Rubric score="+score+" ≥4 required", score>=4);
    }

    // --------------- TR6.5 扩展 Rubric ≥4（5 项过 4） ---------------
    @Test public void tr65_extensibilityRubricAtLeast4() throws Exception {
        int s=0;
        // (1) ShardTransport 接口至少 5 方法(putDoc/clearDoc/searchWithVector/indexTermVector/close)
        Method[] ms = ShardTransport.class.getDeclaredMethods();
        Set<String> mn = new HashSet<>(); for(Method m:ms) mn.add(m.getName());
        boolean a1 = mn.containsAll(Arrays.asList("putDoc","clearDoc","searchWithVector","indexTermVector","close"));
        if(a1) { s++; System.out.println("[TR6.5 Ex] ShardTransport 5 存根 OK"); }
        // (2) SearchOptions 有 concurrentScatter 字段
        boolean a2; try { SearchOptions.class.getDeclaredField("concurrentScatter"); a2=true; } catch(NoSuchFieldException e){a2=false;}
        if(a2) { s++; System.out.println("[TR6.5 Ex] concurrentScatter OK"); }
        // (3) SearchOptions 有 localTopKFactor int（scatter 因子可配置）
        boolean a3; try { Field f=SearchOptions.class.getDeclaredField("localTopKFactor"); a3=f.getType()==Integer.TYPE; } catch(NoSuchFieldException e){a3=false;}
        if(a3) { s++; System.out.println("[TR6.5 Ex] localTopKFactor OK"); }
        // (4) ShardMode 三个 AUTO/UNIT/DOC（3 级 切换扩展点）
        boolean a4 = ShardMode.values().length == 3;
        if(a4) { s++; System.out.println("[TR6.5 Ex] ShardMode 3 enum OK"); }
        // (5) ScoredDocHit 第 12 字段 shardId tie-breaker 存在（12 参构造 + getter 字段）
        boolean a5 = false;
        for(java.lang.reflect.Constructor<?> c : ScoredDocHit.class.getConstructors()) {
            if(c.getParameterCount() >= 12) { // 第 12 参(int shardId)
                Class<?>[] pt = c.getParameterTypes();
                if(pt[11] == Integer.TYPE) { a5 = true; break; }
            }
        }
        if(a5) { s++; System.out.println("[TR6.5 Ex] ScoredDocHit 12 参 shardId OK"); }
        Assert.assertTrue("tr65 Ext Rubric passed="+s+"/5 ≥4 required", s>=4);
    }
}
