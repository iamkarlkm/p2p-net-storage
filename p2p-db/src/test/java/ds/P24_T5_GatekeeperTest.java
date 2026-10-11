package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
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
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * P24 T5：Gatekeeper 4 TR（严格串行依赖 T1~T4 0F0E）
 *   TR5.1：P21 bucketAccessCounter 在 searchCluster 路径绝对零污染（每个 shard 独立 counter 总和=0）
 *   TR5.2：ZeroLeak3 = fulltext 独立类 3（ShardRouter/LocalShardTransport 全内嵌 FulltextStore）
 *   TR5.3：NG-3 11 CNF（P23 9 + netty/grpc 两实现未引入）
 *   TR5.4：Facade≥44（P23 ≥36 非递减 + P24 ShardRouter API 新增 putCluster/clearCluster/searchCluster 等）
 */
public class P24_T5_GatekeeperTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int N = 4;
    private File root;
    private FulltextStore.ShardRouter router;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p24t5-" + UUID.randomUUID());
        root = new File(tmp, "p24t5-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        router = new FulltextStore.ShardRouter(N, root.getAbsolutePath());
    }
    @After public void tearDown() {
        try { router.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ---------- TR5.1 searchCluster 每个 shard bucketAccessCounter 绝对零污染 ----------
    @Test public void tr51_searchClusterEachShardCounterZeroPollution() throws Exception {
        // 构造 4 shard 各 1 doc：公共词 CHERRY 使 searchCluster 扫 4 shard 全路径
        long[] onePerShard = new long[N]; Arrays.fill(onePerShard,-1L); long probe=800_000L; int got=0;
        while(got<N){ int s=FulltextStore.resolveShardForDocIdForTest(probe,N);
            if(onePerShard[s]==-1L){onePerShard[s]=probe;got++;} probe+=13; }
        for(int i=0;i<N;i++) router.putDocCluster(onePerShard[i], "CHERRY APPLE BANANA ORANGE PEAR GRAPE");
        SearchOptions o=SearchOptions.defaults(); o.vectorRecall=false; o.neighborRecallD=0; o.phraseBoost=false;
        // 反射获取每个 shard 的 bucketAccessCounter 字段并清零
        Field shardsF = FulltextStore.ShardRouter.class.getDeclaredField("shards");
        shardsF.setAccessible(true);
        FulltextStore[] shards = (FulltextStore[])shardsF.get(router);
        for(int s=0;s<N;s++){ Arrays.fill(shards[s].bucketAccessCounter, 0); }
        // 100 次 searchCluster（反复调用，应稳定无副作用）
        for(int i=0;i<100;i++){
            Assert.assertFalse("tr51 result non empty iter "+i,
                router.searchCluster("CHERRY", null, 20, o).isEmpty());
        }
        // 每个 shard counter 总和=0
        for(int s=0;s<N;s++){
            int sum=0; for(int d=1;d<=1024;d++) sum += shards[s].bucketAccessCounter[d];
            Assert.assertEquals("tr51 shard="+s+" counter 总和必须=0（严禁 P21 topK 外路径触碰）", 0, sum);
        }
    }

    // ---------- TR5.2 ZeroLeak3 独立类精确=3 ----------
    @Test public void tr52_zeroLeak3IndependentClassesNotIncreased() throws Exception {
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
        Assert.assertEquals("tr52 ZeroLeak 独立类数=3（ShardRouter 等必须内嵌 FulltextStore，不允许新增 top-level 文件）", 3, topLevel.size());
        Assert.assertEquals("tr52 ZeroLeak 独立类集合精确全等", expected, topLevel);
    }

    // ---------- TR5.3 NG-3 11 CNF ----------
    @Test public void tr53_ng3ElevenCnfAllClassNotFound() {
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
        int cnf=0; java.util.List<String> failed = new java.util.ArrayList<>();
        for(String cn:forbiddenCnf){ try { Class.forName(cn); failed.add(cn+" loaded"); }
            catch(ClassNotFoundException ok){cnf++;} catch(Throwable t){failed.add(cn+" "+t.getClass().getSimpleName());} }
        // 9 基础类必须 CNF
        Assert.assertTrue("tr53 NG-3 基础 9 CNF 失败: "+failed, failed.isEmpty());
        Assert.assertEquals("tr53 NG-3 9 基础类全 CNF（P21~P23 基线）", 9, cnf);

        // P24 +2 netty/grpc：fulltext 包 0 引用符号（反射扫描 FulltextStore 及其内嵌类所有签名类型）
        Class<?>[] probeClasses = {
            FulltextStore.class, TermDictStore.class,
            FulltextStore.ShardRouter.class, FulltextStore.ShardTransport.class,
            FulltextStore.LocalShardTransport.class
        };
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        for(Class<?> c:probeClasses) collectTypes(c, seen);
        boolean nettyRef=false, grpcRef=false;
        for(Class<?> c:seen){
            String n = c.getName();
            if(n.startsWith("io.netty")) nettyRef=true;
            if(n.startsWith("io.grpc")) grpcRef=true;
        }
        Assert.assertFalse("tr53 P24 fulltext 代码禁止引用 io.netty.*（ShardRouter/LocalShardTransport 只能存根接口直连，不许接 netty）", nettyRef);
        Assert.assertFalse("tr53 P24 fulltext 代码禁止引用 io.grpc.*（ShardTransport 接口解耦，不许直接依赖 grpc）", grpcRef);
    }

    private static void collectTypes(Class<?> c, java.util.Set<Class<?>> seen) {
        if(c==null || seen.contains(c) || c.isPrimitive() || c.getName().startsWith("java.")) return;
        seen.add(c);
        try { for(java.lang.reflect.Field f:c.getDeclaredFields()) collectTypes(f.getType(), seen); } catch(Throwable ignore){}
        try { for(java.lang.reflect.Method m:c.getDeclaredMethods()){
                collectTypes(m.getReturnType(), seen);
                for(Class<?> p:m.getParameterTypes()) collectTypes(p, seen);
            } } catch(Throwable ignore){}
        try { for(java.lang.reflect.Constructor<?> k:c.getDeclaredConstructors()){
                for(Class<?> p:k.getParameterTypes()) collectTypes(p, seen);
            } } catch(Throwable ignore){}
        try { for(Class<?> d:c.getDeclaredClasses()) collectTypes(d, seen); } catch(Throwable ignore){}
        Class<?> s = c.getSuperclass(); if(s!=null) collectTypes(s,seen);
        for(Class<?> i:c.getInterfaces()) collectTypes(i,seen);
    }

    // ---------- TR5.4 Facade≥44（P21 baseline + P22/P23 非递减 + P24 新增 ShardRouter） ----------
    @Test public void tr54_facadeNonDecreasingAtLeast44() throws Exception {
        // P21/P22/P23 baseline
        Set<String> p21TD = new HashSet<>(Arrays.asList("toUid","toText","retain","release","refCount","close"));
        Set<String> p22FS = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","ingestDoc","insert","deleteByPk","selectByParentLevel",
            "selectByDocLevel","selectByUnit","buildDistForSeq","distanceBetween","topKNeighbors",
            "neighborsWithin","close","search","recomputeIdfForTerms"));
        Set<String> p23FSNew = new HashSet<>(Arrays.asList(
            "getTermVectorForTest","indexTermVector","cosineSimForTest","l2DistForTest",
            "knnSearchForTest","getHnswMetaForTest","searchWithVectorForTest"));
        Set<String> p24FSNew = new HashSet<>(Arrays.asList(
            "resolveShardForDocIdForTest","resolveShardForUnitIdForTest","weightStoreAllDocKeysForTest"));
        Set<String> p24ShardRouterNew = new HashSet<>(Arrays.asList(
            "putDocCluster","clearDocCluster","indexTermVectorCluster","searchCluster","scatterGatherForTest","close",
            "getHnswMetaForTest","getTermVectorForTest","getTfForTest","getDocStatForTest","getTermStatForTest",
            "weightStoreAllDocKeysForTest","termDictToUidForTest","termDictRefCountForTest"));

        Method[] tdM = TermDictStore.class.getDeclaredMethods();
        Method[] fsM = FulltextStore.class.getDeclaredMethods();
        Method[] srM = FulltextStore.ShardRouter.class.getDeclaredMethods();
        Set<String> tdPub=new HashSet<>(); for(Method m:tdM) if(Modifier.isPublic(m.getModifiers())) tdPub.add(m.getName());
        Set<String> fsPub=new HashSet<>(); for(Method m:fsM) if(Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());
        Set<String> srPub=new HashSet<>(); for(Method m:srM) if(Modifier.isPublic(m.getModifiers())) srPub.add(m.getName());

        for(String n:p21TD) Assert.assertTrue("tr54 P21 TD baseline: "+n, tdPub.contains(n));
        for(String n:p22FS) Assert.assertTrue("tr54 P22 FS baseline: "+n, fsPub.contains(n));
        for(String n:p23FSNew) Assert.assertTrue("tr54 P23 FS新增: "+n, fsPub.contains(n));
        for(String n:p24FSNew) Assert.assertTrue("tr54 P24 FS新增: "+n+" 当前="+fsPub, fsPub.contains(n));
        for(String n:p24ShardRouterNew) Assert.assertTrue("tr54 P24 ShardRouter新增: "+n+" 当前="+srPub, srPub.contains(n));

        // 结构存在性：ScoredDocHit 10/11/12 三构造 + ShardMode(AUTO,UNIT,DOC) + SearchOptions concurrentScatter/localTopKFactor/shardMode
        long cons = Arrays.stream(ScoredDocHit.class.getConstructors()).mapToInt(Constructor::getParameterCount)
                              .filter(n -> n==10||n==11||n==12).distinct().count();
        Assert.assertTrue("tr54 ScoredDocHit 至少 3 构造(10/11/12): "+Arrays.toString(Arrays.stream(ScoredDocHit.class.getConstructors()).mapToInt(Constructor::getParameterCount).toArray()),
                          cons >= 3);
        Assert.assertEquals("tr54 ShardMode enum 3 值(AUTO,UNIT,DOC)", 3, ShardMode.values().length);
        Field csF = SearchOptions.class.getDeclaredField("concurrentScatter");
        Field lfF = SearchOptions.class.getDeclaredField("localTopKFactor");
        Field smF = SearchOptions.class.getDeclaredField("shardMode");
        Assert.assertEquals("tr54 concurrentScatter default false", Boolean.TYPE, csF.getType());
        Assert.assertEquals("tr54 localTopKFactor default int=3", Integer.TYPE, lfF.getType());
        Assert.assertSame("tr54 shardMode type=ShardMode", ShardMode.class, smF.getType());

        // 总计 TD≥6 + FS≥30 + SR≥8 = 44
        int tdC=tdPub.size(), fsC=fsPub.size(), srC=srPub.size();
        int tot = tdC + fsC + srC;
        Assert.assertTrue("tr54 TD≥6 actual="+tdC, tdC>=6);
        Assert.assertTrue("tr54 FulltextStore≥30 actual="+fsC, fsC>=30);
        Assert.assertTrue("tr54 ShardRouter public≥8 actual="+srC, srC>=8);
        Assert.assertTrue("tr54 Facade 总计≥44 actual="+tot+" (TD="+tdC+",FS="+fsC+",SR="+srC+")", tot>=44);
    }
}
