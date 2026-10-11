package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.FulltextSegmenter;

public class P25_T5_GatekeeperTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int N = 4;
    private File root;
    private FulltextStore.ShardRouter router;
    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p25t5-" + UUID.randomUUID());
        root = new File(tmp, "p25t5-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
    }
    @After public void tearDown() {
        try { router.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ---------- TR5.1 P21 铁律 counter 0 污染（P25 searchWithExtras/searchCluster 都不触碰） ----------
    @Test public void tr51_bucketAccessCounterZeroPollution() throws Exception {
        long[] onePerShard = new long[N]; Arrays.fill(onePerShard,-1L); long probe=700_000L; int got=0;
        while(got<N){ int s=FulltextStore.resolveShardForDocIdForTest(probe,N);
            if(onePerShard[s]==-1L){onePerShard[s]=probe;got++;} probe+=11; }
        for(int i=0;i<N;i++) router.putDocCluster(onePerShard[i], "CHERRY APPLE BANANA ORANGE PEAR GRAPE");
        SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        Field shardsF = ShardRouter.class.getDeclaredField("shards");
        shardsF.setAccessible(true);
        FulltextStore[] shards = (FulltextStore[])shardsF.get(router);
        for(int s=0;s<N;s++){ Arrays.fill(shards[s].bucketAccessCounter, 0); }
        // 50 次 searchCluster + 50 次 searchWithExtras
        java.util.Map<Long,String> rawMap = new java.util.HashMap<>();
        for(long d:onePerShard) rawMap.put(d, "CHERRY APPLE BANANA ORANGE PEAR GRAPE");
        for(int i=0;i<50;i++){
            Assert.assertFalse("searchCluster not empty iter "+i, router.searchCluster("CHERRY", null, 20, o).isEmpty());
            Assert.assertNotNull("searchWithExtras ok iter "+i, router.searchClusterWithExtras("CHERRY", rawMap, 20, o));
        }
        for(int s=0;s<N;s++){
            int sum=0; for(int d=1;d<=1024;d++) sum += shards[s].bucketAccessCounter[d];
            Assert.assertEquals("P25 tr51 shard="+s+" counter 总和=0（P25 searchWithExtras 严禁触碰 P21 TOPK 计数器）",0,sum);
        }
    }

    // ---------- TR5.2 ZeroLeak3=3（P25 PositionSpan/SnippetHit/FacetHit/ScoredDocHitWithExtras 必须内嵌 $ 名） ----------
    @Test public void tr52_zeroLeak3StillThree() throws Exception {
        Set<String> classes = P23_T5_GatekeeperTest.P22_T5_GatekeeperTestHelper.scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext");
        final String PKG = "com.q3lives.ds.database.fulltext";
        Set<String> topLevel = new HashSet<>();
        for (String cn : classes) {
            if (cn.contains("$")) continue;
            if (!cn.startsWith(PKG + ".")) continue;
            String tail = cn.substring(PKG.length() + 1);
            if (tail.contains(".")) continue; // 跳过 cluster / handler 等子包类
            topLevel.add(cn);
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.FulltextSegmenter"
        ));
        Assert.assertEquals("ZeroLeak3=3", 3, topLevel.size());
        Assert.assertEquals("ZeroLeak3 set 全等", expected, topLevel);
        // 额外断言：4 个内嵌类名 带 $
        Class<?>[] needInner = { FulltextStore.PositionSpan.class, FulltextStore.SnippetHit.class,
                                 FulltextStore.FacetHit.class, FulltextStore.ScoredDocHitWithExtras.class };
        for (Class<?> c : needInner) Assert.assertTrue("必须内嵌 $ 类: "+c.getName(), c.getName().contains("$"));
    }

    // ---------- TR5.3 NG-3 9 CNFE + 0 netty/grpc 引用 ----------
    @Test public void tr53_ng3BaselineNineCnfPlusZeroNettyGrpcRef() throws Exception {
        String[] forbiddenCnf = {
            "com.huaban.analysis.jieba.JiebaSegmenter",
            "org.wltea.analyzer.lucene.IKAnalyzer",
            "com.hankcs.hanlp.HanLP",
            "spacy.Spacy",
            "org.apache.lucene.search.similarities.BM25Similarity",
            "org.apache.lucene.util.hnsw.HnswGraph",
            "com.github.jelmerk.knn.hnsw.HnswIndex",
            "org.nd4j.linalg.factory.Nd4j",
            "org.apache.commons.math3.ml.distance.EuclideanDistance"
        };
        int cnf=0; List<String> failed = new ArrayList<>();
        for(String cn:forbiddenCnf){ try { Class.forName(cn); failed.add(cn+" loaded"); }
            catch(ClassNotFoundException ok){cnf++;} catch(Throwable t){failed.add(cn+" "+t.getClass().getSimpleName());} }
        Assert.assertTrue("tr53 NG-3 9 CNF 失败: "+failed, failed.isEmpty());
        Assert.assertEquals("tr53 NG-3 9 基础类全 CNF（P25 保持单调）", 9, cnf);
        // P25 新内嵌类 4 个 + 老的 全扫：net/grpc 0 ref
        Class<?>[] probes = {
            FulltextStore.class, TermDictStore.class, FulltextSegmenter.class,
            ShardRouter.class, FulltextStore.ShardTransport.class, FulltextStore.LocalShardTransport.class,
            FulltextStore.PositionSpan.class, FulltextStore.SnippetHit.class,
            FulltextStore.FacetHit.class, FulltextStore.ScoredDocHitWithExtras.class
        };
        Set<Class<?>> seen = new HashSet<>();
        for(Class<?> c:probes) collectTypes(c, seen);
        boolean netty=false,grpc=false;
        for(Class<?> c:seen){ String n=c.getName(); if(n.startsWith("io.netty"))netty=true; if(n.startsWith("io.grpc"))grpc=true; }
        Assert.assertFalse("NG-3 禁 io.netty.* 引用", netty);
        Assert.assertFalse("NG-3 禁 io.grpc.* 引用", grpc);
    }
    private static void collectTypes(Class<?> c, Set<Class<?>> seen) {
        if(c==null || seen.contains(c) || c.isPrimitive() || c.getName().startsWith("java.")) return;
        seen.add(c);
        try { for(Field f:c.getDeclaredFields()) collectTypes(f.getType(), seen); } catch(Throwable ignore){}
        try { for(Method m:c.getDeclaredMethods()){ collectTypes(m.getReturnType(), seen);
            for(Class<?> p:m.getParameterTypes()) collectTypes(p, seen); } } catch(Throwable ignore){}
        try { for(Constructor<?> k:c.getDeclaredConstructors()){
            for(Class<?> p:k.getParameterTypes()) collectTypes(p, seen); } } catch(Throwable ignore){}
        try { for(Class<?> d:c.getDeclaredClasses()) collectTypes(d, seen); } catch(Throwable ignore){}
        Class<?> s = c.getSuperclass(); if(s!=null) collectTypes(s,seen);
        for(Class<?> i:c.getInterfaces()) collectTypes(i,seen);
    }

    // ---------- TR5.4 Facade ≥ 52（P24≥50 + P25 新增 searchWithExtras/3 probes/6 字段 default 构造/4 内嵌 → 累积+8≥58） ----------
    @Test public void tr54_facadeNonDecreasingAtLeast52() throws Exception {
        Set<String> baselineMust = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","search","close","ingestDoc","recomputeIdfForTerms",
            "searchWithVectorForTest","getTermVectorForTest","indexTermVector","cosineSimForTest",
            "searchCluster","putDocCluster","clearDocCluster","indexTermVectorCluster",
            "weightStoreAllDocKeysForTest","resolveShardForDocIdForTest","resolveShardForUnitIdForTest",
            "searchWithExtras","buildSnippetsForTest","sortFacetsForTest","computeFacetsForTest",
            "mergeSpansForTest","getTermPositionsForTest"));
        Set<String> tdMust = new HashSet<>(Arrays.asList("toUid","toText","retain","release","refCount","close"));

        Method[] tdM = TermDictStore.class.getDeclaredMethods();
        Method[] fsM = FulltextStore.class.getDeclaredMethods();
        Method[] srM = ShardRouter.class.getDeclaredMethods();
        Field[]  fsF = FulltextStore.class.getDeclaredFields();
        Set<String> tdPub=new HashSet<>(); for(Method m:tdM) if(Modifier.isPublic(m.getModifiers())) tdPub.add(m.getName());
        Set<String> fsPub=new HashSet<>(); for(Method m:fsM) if(Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());
        Set<String> srPub=new HashSet<>(); for(Method m:srM) if(Modifier.isPublic(m.getModifiers())) srPub.add(m.getName());
        Set<String> fsPubF=new HashSet<>(); for(Field f:fsF) if(Modifier.isPublic(f.getModifiers())) fsPubF.add(f.getName());

        for(String n:tdMust) Assert.assertTrue("TD 基线丢失: "+n, tdPub.contains(n));
        for(String n:baselineMust) {
            if (srPub.contains(n)) continue;
            Assert.assertTrue("FS/SR 基线丢失: "+n, fsPub.contains(n));
        }
        // 计算 Facade：TD public methods + FS public methods+fields + SR public methods 总数（P24 50 → P25 ≥52）
        int facade = tdPub.size() + fsPub.size() + fsPubF.size() + srPub.size();
        Assert.assertTrue("P25 Facade="+facade+" ≥52（单调不删）", facade >= 52);
    }
}
