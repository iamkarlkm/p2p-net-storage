package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;

public class P26_T5_GatekeeperTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int SHARD_COUNT = 4;
    private File root;
    private DsFulltextClusterClient client;
    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p26t5-" + UUID.randomUUID());
        root = new File(tmp, "p26t5-root");
        root.mkdirs();
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        client = new DsFulltextClusterClient(SHARD_COUNT, null, root.getAbsolutePath(), "p26t5_shard");
    }
    @After public void tearDown() {
        try { client.close(); } catch (Exception ignore) {}
        // Clear server registry so other tests clean
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            java.lang.reflect.Method m = c.getDeclaredMethod("clearAll");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ---------- TR5.1 ZeroLeak3=3（fulltext 顶级类恒3：TermDictStore + FulltextStore + FulltextSegmenter；不含子包.cluster等） ----------
    @Test public void tr51_zeroLeak3StillThreeP26() throws Exception {
        Set<String> classes = P23_T5_GatekeeperTest.P22_T5_GatekeeperTestHelper.scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext");
        final String prefix = "com.q3lives.ds.database.fulltext.";
        Set<String> topLevel = new HashSet<>();
        for (String cn : classes) {
            if (cn.contains("$")) continue;
            if (!cn.startsWith(prefix)) continue;
            String tail = cn.substring(prefix.length());
            if (tail.indexOf('.') >= 0) continue; // skip sub-packages (cluster, etc.)
            topLevel.add(cn);
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.FulltextSegmenter"
        ));
        Assert.assertEquals("ZeroLeak3=3 (fulltext 顶级类不含子包)", 3, topLevel.size());
        Assert.assertEquals("ZeroLeak3 set 全等（P26 cluster/handler 全在子包，不进根包）", expected, topLevel);
    }

    // ---------- TR5.2 NG-3：9 CNFE（禁第三方） + fulltext根包 0 netty/grpc/javax.net.p2p.client 符号引用 ----------
    @Test public void tr52_ng3NineCnfPlusZeroNettyGrpcClientRootRef() throws Exception {
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
        int cnf = 0;
        List<String> failed = new ArrayList<>();
        for (String cn : forbiddenCnf) { try { Class.forName(cn); failed.add(cn + " loaded"); }
            catch (ClassNotFoundException ok) { cnf++; } catch (Throwable t) { failed.add(cn + " " + t.getClass().getSimpleName()); } }
        Assert.assertTrue("tr52 NG-3 9 CNF 失败: " + failed, failed.isEmpty());
        Assert.assertEquals("tr52 NG-3 9 基础类全 CNF（P26 保持单调不增）", 9, cnf);
        Class<?>[] rootProbes = {
            FulltextStore.class, TermDictStore.class, FulltextSegmenter.class,
            ShardRouter.class, FulltextStore.ShardTransport.class, FulltextStore.LocalShardTransport.class,
            FulltextStore.PositionSpan.class, FulltextStore.SnippetHit.class,
            FulltextStore.FacetHit.class, FulltextStore.ScoredDocHitWithExtras.class,
            FulltextStore.ScoredDocHit.class, FulltextStore.SearchOptions.class
        };
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> c : rootProbes) collectTypes(c, seen);
        boolean netty = false, grpc = false, p2pclient = false;
        for (Class<?> c : seen) {
            String n = c.getName();
            if (n.startsWith("io.netty")) netty = true;
            if (n.startsWith("io.grpc")) grpc = true;
            if (n.startsWith("javax.net.p2p.client")) p2pclient = true;
        }
        Assert.assertFalse("NG-3 fulltext根包 禁 io.netty.* 符号引用", netty);
        Assert.assertFalse("NG-3 fulltext根包 禁 io.grpc.* 符号引用", grpc);
        Assert.assertFalse("NG-3 fulltext根包 禁 javax.net.p2p.client.* 符号引用（GRPC impl 在 cluster 子包）", p2pclient);
    }
    private static void collectTypes(Class<?> c, Set<Class<?>> seen) {
        if (c == null || seen.contains(c) || c.isPrimitive() || c.getName().startsWith("java.")) return;
        seen.add(c);
        try { for (Field f : c.getDeclaredFields()) collectTypes(f.getType(), seen); } catch (Throwable ignore) {}
        try { for (Method m : c.getDeclaredMethods()) { collectTypes(m.getReturnType(), seen);
            for (Class<?> p : m.getParameterTypes()) collectTypes(p, seen); } } catch (Throwable ignore) {}
        try { for (Constructor<?> k : c.getDeclaredConstructors()) {
            for (Class<?> p : k.getParameterTypes()) collectTypes(p, seen); } } catch (Throwable ignore) {}
        try { for (Class<?> d : c.getDeclaredClasses()) collectTypes(d, seen); } catch (Throwable ignore) {}
        Class<?> s = c.getSuperclass(); if (s != null) collectTypes(s, seen);
        for (Class<?> i : c.getInterfaces()) collectTypes(i, seen);
    }

    // ---------- TR5.3 TOPK bucketAccessCounter 永久=0（P26 loopback GRPC scatter-gather 路径零污染） ----------
    @Test public void tr53_bucketAccessCounterZeroPollutionP26Loopback() throws Exception {
        long[] onePerShard = new long[SHARD_COUNT]; Arrays.fill(onePerShard, -1L); long probe = 900_000L; int got = 0;
        while (got < SHARD_COUNT) {
            int s = Math.abs(Long.hashCode(probe)) % SHARD_COUNT;
            if (onePerShard[s] == -1L) { onePerShard[s] = probe; got++; }
            probe += 11;
        }
        for (int i = 0; i < SHARD_COUNT; i++) client.putDocCluster(onePerShard[i], "CHERRY APPLE BANANA ORANGE PEAR GRAPE PINEAPPLE");
        SearchOptions o = SearchOptions.defaults(); o.vectorRecall = false; o.neighborRecallD = 0; o.phraseBoost = false;
        // reset shard-level counters via reflection (client-side router internal shards)
        ShardRouter router = client.getRouterForTest();
        Field shardsF = ShardRouter.class.getDeclaredField("shards"); shardsF.setAccessible(true);
        FulltextStore[] shards = (FulltextStore[]) shardsF.get(router);
        for (int s = 0; s < SHARD_COUNT; s++) Arrays.fill(shards[s].bucketAccessCounter, 0);
        // Also reset server-side registry router counters
        try {
            Class<?> reg = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Field routersF = reg.getDeclaredField("ROUTERS"); routersF.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, ShardRouter> routers =
                (java.util.concurrent.ConcurrentHashMap<String, ShardRouter>) routersF.get(null);
            for (ShardRouter sr : routers.values()) {
                FulltextStore[] srvShards = (FulltextStore[]) shardsF.get(sr);
                if (srvShards != null) for (int s = 0; s < srvShards.length; s++) Arrays.fill(srvShards[s].bucketAccessCounter, 0);
            }
        } catch (Throwable ignore) {}
        java.util.Map<Long, String> rawMap = new HashMap<>();
        for (long d : onePerShard) rawMap.put(d, "CHERRY APPLE BANANA ORANGE PEAR GRAPE PINEAPPLE");
        for (int i = 0; i < 50; i++) {
            List<ScoredDocHit> hs = client.searchCluster("CHERRY", 20, o);
            Assert.assertFalse("searchCluster not empty iter " + i, hs.isEmpty());
            Assert.assertNotNull("searchWithExtras not null iter " + i, client.searchClusterWithExtras("CHERRY", rawMap, 20, o));
        }
        // sum client and server counter
        long sum = 0L;
        for (int s = 0; s < SHARD_COUNT; s++) for (int d = 1; d <= 1024; d++) sum += shards[s].bucketAccessCounter[d];
        try {
            Class<?> reg = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Field routersF = reg.getDeclaredField("ROUTERS"); routersF.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, ShardRouter> routers =
                (java.util.concurrent.ConcurrentHashMap<String, ShardRouter>) routersF.get(null);
            for (ShardRouter sr : routers.values()) {
                FulltextStore[] srvShards = (FulltextStore[]) shardsF.get(sr);
                if (srvShards != null) for (int s = 0; s < srvShards.length; s++)
                    for (int d = 1; d <= 1024; d++) sum += srvShards[s].bucketAccessCounter[d];
            }
        } catch (Throwable ignore) {}
        Assert.assertEquals("P26 loopback GRPC path bucketAccessCounter 总和=0（TOPK 零污染铁律）", 0L, sum);
    }

    // ---------- TR5.4 Facade 单调≥70（P25 实际基线 + P26 新增 ShardTransport 3 default/Server单shard直通等，累积只增不删） ----------
    @Test public void tr54_facadeMonotonicNonDecreasingP26() throws Exception {
        Set<String> fsSrMust = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","close","ingestDoc","recomputeIdfForTerms",
            "searchWithVectorForTest","getTermVectorForTest","indexTermVector","cosineSimForTest",
            "searchCluster","putDocCluster","clearDocCluster","indexTermVectorCluster",
            "weightStoreAllDocKeysForTest","resolveShardForDocIdForTest","resolveShardForUnitIdForTest",
            "searchWithExtras","buildSnippetsForTest","sortFacetsForTest","computeFacetsForTest",
            "mergeSpansForTest","getTermPositionsForTest",
            "getShardCountForTest","getShardTransportForTest","getShardForTest",
            "getShardRootDirForTest","getShardModeForTest","toUid","search","searchWithVector"
        ));
        Set<String> tdMust = new HashSet<>(Arrays.asList("toUid","toText","retain","release","refCount","close"));
        Method[] tdM = TermDictStore.class.getDeclaredMethods();
        Method[] fsM = FulltextStore.class.getDeclaredMethods();
        Method[] srM = ShardRouter.class.getDeclaredMethods();
        Field[]  fsF = FulltextStore.class.getDeclaredFields();
        Set<String> tdPub = new HashSet<>(); for (Method m : tdM) if (Modifier.isPublic(m.getModifiers())) tdPub.add(m.getName());
        Set<String> fsPub = new HashSet<>(); for (Method m : fsM) if (Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());
        Set<String> srPub = new HashSet<>(); for (Method m : srM) if (Modifier.isPublic(m.getModifiers())) srPub.add(m.getName());
        Set<String> fsPubF = new HashSet<>(); for (Field f : fsF) if (Modifier.isPublic(f.getModifiers())) fsPubF.add(f.getName());
        for (String n : tdMust) Assert.assertTrue("TD 基线丢失: "+n+" tdPub="+tdPub, tdPub.contains(n));
        for (String n : fsSrMust) {
            if (srPub.contains(n)) continue;
            if (fsPub.contains(n)) continue;
            Assert.fail("FS/SR 基线丢失: "+n);
        }
        // P26 新增 ShardTransport 3 default 方法显式存在
        Method searchDef = FulltextStore.ShardTransport.class.getMethod("search", int.class, String.class, int.class, SearchOptions.class);
        Assert.assertNotNull("ShardTransport.search default存在", searchDef);
        Method extrasDef = FulltextStore.ShardTransport.class.getMethod("searchWithExtras", int.class, String.class, java.util.Map.class, int.class, SearchOptions.class);
        Assert.assertNotNull("ShardTransport.searchWithExtras default存在", extrasDef);
        Method batchDef = FulltextStore.ShardTransport.class.getMethod("putDocBatch", int.class, java.util.Map.class);
        Assert.assertNotNull("ShardTransport.putDocBatch default存在", batchDef);
        // IngestStat zeroForTest/mergeWith 存在
        Method zf = FulltextStore.IngestStat.class.getMethod("zeroForTest");
        Assert.assertNotNull("IngestStat.zeroForTest 存在", zf);
        Method mw = FulltextStore.IngestStat.class.getMethod("mergeWith", FulltextStore.IngestStat.class);
        Assert.assertNotNull("IngestStat.mergeWith 存在", mw);
        // Facade 总数（TD + FS + SR 公共方法字段）
        int facade = tdPub.size() + fsPub.size() + fsPubF.size() + srPub.size();
        Assert.assertTrue("P26 Facade="+facade+" ≥70（单调不删）", facade >= 70);
    }
}
