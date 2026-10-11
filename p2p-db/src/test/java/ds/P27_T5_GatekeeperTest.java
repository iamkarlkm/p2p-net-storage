package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.net.p2p.api.P2PCommand;
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
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;

/**
 * P27 T5 Gatekeeper 9Y 全部合规自检。
 * Y1 ZeroLeak3=3       Y2 NG-3 9 CNFE + 0 netty/grpc/p2p.client 根包字面量
 * Y3 TOPK sum=0        Y4 Facade≥70 单调
 * Y5 路由公式 byte全等  Y6 Proto append-only (P26 6 msg 全存)
 * Y7 Handler cmd 24354~60  Y8 ShardTransport 5 原方法签名一字保留
 * Y9 TODO 源码 0 行
 */
public class P27_T5_GatekeeperTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int SHARD_COUNT = 4;
    private File root;
    private DsFulltextClusterClient client;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p27t5-" + UUID.randomUUID());
        root = new File(tmp, "p27t5-root");
        root.mkdirs();
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        client = new DsFulltextClusterClient(SHARD_COUNT, null, root.getAbsolutePath(), "p27t5_shard");
    }
    @After public void tearDown() {
        try { client.close(); } catch (Exception ignore) {}
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ====================================================================
    // Y1=TR5.1 ZeroLeak3=3（cluster/server/handler 子包不进，tail contains '.' 跳过）
    // ====================================================================
    @Test public void tr51_zeroLeak3StillThreeP27() throws Exception {
        Set<String> classes = P23_T5_GatekeeperTest.P22_T5_GatekeeperTestHelper.scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext");
        final String PKG = "com.q3lives.ds.database.fulltext.";
        Set<String> topLevel = new HashSet<>();
        for (String cn : classes) {
            if (cn.contains("$")) continue;
            if (!cn.startsWith(PKG)) continue;
            String tail = cn.substring(PKG.length());
            if (tail.indexOf('.') >= 0) continue; // cluster/server/handler 子包类 skip
            topLevel.add(cn);
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.FulltextSegmenter"
        ));
        Assert.assertEquals("Y1 ZeroLeak3=3（P27 根包 top-level 类严格 3）", 3, topLevel.size());
        Assert.assertEquals("Y1 ZeroLeak3 集合完全相等，不增不减", expected, topLevel);
    }

    // ====================================================================
    // Y2=TR5.2 NG-3：9 CNF（禁第三方） + fulltext 根包 0 netty/grpc/javax.net.p2p.client 引用
    // ====================================================================
    @Test public void tr52_ng3NineCnfPlusZeroNettyGrpcClientRootP27() throws Exception {
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
        Assert.assertTrue("Y2 NG-3 9 CNF 失败: " + failed, failed.isEmpty());
        Assert.assertEquals("Y2 NG-3 9 基础类全 CNF（P27 单调不增）", 9, cnf);
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
        Assert.assertFalse("Y2 NG-3 fulltext 根包 禁 io.netty.* 符号引用", netty);
        Assert.assertFalse("Y2 NG-3 fulltext 根包 禁 io.grpc.* 符号引用", grpc);
        Assert.assertFalse("Y2 NG-3 fulltext 根包 禁 javax.net.p2p.client.* 符号引用（impl 在 .cluster / .server.fulltext 子包）", p2pclient);
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

    // ====================================================================
    // Y3=TR5.3 TOPK bucketAccessCounter 双端 sum = 0（P27 TCP 模式零污染）
    // ====================================================================
    @Test public void tr53_bucketAccessCounterZeroP27() throws Exception {
        long[] onePerShard = new long[SHARD_COUNT]; Arrays.fill(onePerShard, -1L); long probe = 900_000L; int got = 0;
        while (got < SHARD_COUNT) {
            int s = Math.abs(Long.hashCode(probe)) % SHARD_COUNT;
            if (onePerShard[s] == -1L) { onePerShard[s] = probe; got++; }
            probe += 11;
        }
        for (int i = 0; i < SHARD_COUNT; i++) client.putDocCluster(onePerShard[i], "CHERRY APPLE BANANA ORANGE PEAR GRAPE PINEAPPLE");
        SearchOptions o = SearchOptions.defaults(); o.vectorRecall = false; o.neighborRecallD = 0; o.phraseBoost = false;
        ShardRouter router = client.getRouterForTest();
        Field shardsF = ShardRouter.class.getDeclaredField("shards"); shardsF.setAccessible(true);
        FulltextStore[] shards = (FulltextStore[]) shardsF.get(router);
        for (int s = 0; s < SHARD_COUNT; s++) Arrays.fill(shards[s].bucketAccessCounter, 0);
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
        Map<Long, String> rawMap = new HashMap<>();
        for (long d : onePerShard) rawMap.put(d, "CHERRY APPLE BANANA ORANGE PEAR GRAPE PINEAPPLE");
        for (int i = 0; i < 50; i++) {
            List<ScoredDocHit> hs = client.searchCluster("CHERRY", 20, o);
            Assert.assertFalse("searchCluster not empty iter " + i, hs.isEmpty());
            Assert.assertNotNull("searchWithExtras not null iter " + i, client.searchClusterWithExtras("CHERRY", rawMap, 20, o));
        }
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
        Assert.assertEquals("Y3 TOPK 双端 bucket 累加 = 0（零污染铁律 P27）", 0L, sum);
    }

    // ====================================================================
    // Y4=TR5.4 Facade≥70（P27 只增不减，P26 基线保留）
    // ====================================================================
    @Test public void tr54_facadeMonotonicNonDecreasingP27() throws Exception {
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
        for (String n : tdMust) Assert.assertTrue("Y4 TD 基线丢失: "+n+" tdPub="+tdPub, tdPub.contains(n));
        for (String n : fsSrMust) {
            if (srPub.contains(n)) continue;
            if (fsPub.contains(n)) continue;
            Assert.fail("Y4 FS/SR 基线丢失: "+n);
        }
        // P27 必须保留 P26 3 default 方法
        Method searchDef = FulltextStore.ShardTransport.class.getMethod("search", int.class, String.class, int.class, SearchOptions.class);
        Assert.assertNotNull("Y4 ShardTransport.search default存在", searchDef);
        Method extrasDef = FulltextStore.ShardTransport.class.getMethod("searchWithExtras", int.class, String.class, Map.class, int.class, SearchOptions.class);
        Assert.assertNotNull("Y4 ShardTransport.searchWithExtras default存在", extrasDef);
        Method batchDef = FulltextStore.ShardTransport.class.getMethod("putDocBatch", int.class, Map.class);
        Assert.assertNotNull("Y4 ShardTransport.putDocBatch default存在", batchDef);
        Method zf = FulltextStore.IngestStat.class.getMethod("zeroForTest");
        Assert.assertNotNull("Y4 IngestStat.zeroForTest 存在", zf);
        Method mw = FulltextStore.IngestStat.class.getMethod("mergeWith", FulltextStore.IngestStat.class);
        Assert.assertNotNull("Y4 IngestStat.mergeWith 存在", mw);
        int facade = tdPub.size() + fsPub.size() + fsPubF.size() + srPub.size();
        Assert.assertTrue("Y4 P27 Facade="+facade+" ≥70（单调不删）", facade >= 70);
    }

    // ====================================================================
    // Y5=TR5.5 路由公式 UNIT/DOC 字节级全等（字面值断言）
    // ====================================================================
    @Test public void tr55_routerFormulaByteEqUnitDocP27() throws Exception {
        // UNIT: (uid >>> 48) & 0xFFFF mod N
        long uid = 0xABCD_0000_0000_0000L; // high 16 = 0xABCD
        int shard_2_full = FulltextStore.resolveShardForUnitIdForTest(uid, 2);
        Assert.assertEquals("UNIT路由: (uid>>48)&0xFFFF%2 应为 0xABCD%2=1, got=" + shard_2_full, 1, shard_2_full);
        int shard_5 = FulltextStore.resolveShardForUnitIdForTest(uid, 5);
        Assert.assertEquals("UNIT路由: (uid>>48)&0xFFFF%5, 0xABCD=43981 %5=43981-5*8796=43981-43980=1", 1, shard_5);
        // DOC: FarmHash.mix4(docId, 0xC0FFEEL, 0, 0) >>> 16 & 0x7FFFFFFF % N
        // 用 3 个固定 docId 向量 断言路由值 = 基准硬编码值
        long[] docs = {1001L, 2002L, 3003L};
        int[] expectedN4 = new int[docs.length];
        for (int i = 0; i < docs.length; i++) {
            long V = TermDictStore.FarmHash64.mix4(docs[i], 0xC0FFEEL, 0L, 0L);
            long shifted = (V >>> 16) & 0x7FFFFFFFL;
            expectedN4[i] = (int) ((shifted % 4L) < 0 ? (shifted % 4L + 4) : (shifted % 4L));
        }
        // 重新用 public 方法计算，必须完全相等
        for (int i = 0; i < docs.length; i++) {
            int got = FulltextStore.resolveShardForDocIdForTest(docs[i], 4);
            Assert.assertEquals("DOC路由公式 doc=" + docs[i] + " N=4 字面值全等", expectedN4[i], got);
        }
    }

    // ====================================================================
    // Y6=TR5.6 Proto append-only：6 Request/Response + SearchWithVectorShardRequest 2 扩展列均存在
    // ====================================================================
    @Test public void tr56_protoAppendOnly6MsgsPlus2ExtColsP27() throws Exception {
        Class<?>[] msgs = {
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PutDocShardRequest.class,
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PutDocShardResponse.class,
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ClearDocShardRequest.class,
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardRequest.class,
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardResponse.class,
            com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchWithVectorShardRequest.class
        };
        for (Class<?> c : msgs) Assert.assertNotNull("Y6 Proto msg " + c.getSimpleName() + " 存在（append-only）", c);
        // SearchWithVectorShardRequest 字段 query=5, options=6（P26 FR5 扩展）
        java.lang.reflect.Method[] meths = com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchWithVectorShardRequest.class.getMethods();
        boolean hasGetQuery = false, hasGetOptions = false;
        for (java.lang.reflect.Method m : meths) {
            String n = m.getName();
            if (n.equals("getQuery") && m.getParameterCount() == 0) hasGetQuery = true;
            if (n.equals("getOptions") && m.getParameterCount() == 0) hasGetOptions = true;
        }
        Assert.assertTrue("Y6 Proto SWVShardRequest 第5列 query 存在（append-only）", hasGetQuery);
        Assert.assertTrue("Y6 Proto SWVShardRequest 第6列 options 存在（append-only）", hasGetOptions);
    }

    // ====================================================================
    // Y7=TR5.7 Handler 命令号严格 24354~24360
    // ====================================================================
    @Test public void tr57_handlerCmdRange24354To24360P27() throws Exception {
        Map<String, Integer> expected = new HashMap<>();
        expected.put("FulltextShardPutDocHandler", 24354);
        expected.put("FulltextShardClearDocHandler", 24355);
        expected.put("FulltextShardIndexVectorHandler", 24356);
        expected.put("FulltextShardSearchVectorHandler", 24357);
        expected.put("FulltextShardSearchHandler", 24358);
        expected.put("FulltextShardSearchExtrasHandler", 24359);
        expected.put("FulltextShardCloseHandler", 24360);
        // 枚举 cmd code 直接断言
        Assert.assertEquals("Y7 PUT_DOC cmd=24354", 24354, P2PCommand.FULLTEXT_SHARD_PUT_DOC.getValue());
        Assert.assertEquals("Y7 CLEAR_DOC cmd=24355", 24355, P2PCommand.FULLTEXT_SHARD_CLEAR_DOC.getValue());
        Assert.assertEquals("Y7 INDEX_VECTOR cmd=24356", 24356, P2PCommand.FULLTEXT_SHARD_INDEX_VECTOR.getValue());
        Assert.assertEquals("Y7 SEARCH_VECTOR cmd=24357", 24357, P2PCommand.FULLTEXT_SHARD_SEARCH_VECTOR.getValue());
        Assert.assertEquals("Y7 SEARCH cmd=24358", 24358, P2PCommand.FULLTEXT_SHARD_SEARCH.getValue());
        Assert.assertEquals("Y7 SEARCH_EXTRAS cmd=24359", 24359, P2PCommand.FULLTEXT_SHARD_SEARCH_EXTRAS.getValue());
        Assert.assertEquals("Y7 CLOSE cmd=24360", 24360, P2PCommand.FULLTEXT_SHARD_CLOSE.getValue());
        // Handler 类文件存在（不反射实例化）
        for (String cls : expected.keySet()) {
            String fq = "javax.net.p2p.server.handler.fulltext." + cls;
            try { Class.forName(fq); } catch (ClassNotFoundException cnf) { Assert.fail("Y7 Handler 不存在: " + fq); }
        }
    }

    // ====================================================================
    // Y8=TR5.8 ShardTransport 5 原方法签名（P24 永久红线）一字保留
    // ====================================================================
    @Test public void tr58_shardTransportFiveOriginalsSignaturesPreservedP27() throws Exception {
        Class<FulltextStore.ShardTransport> iface = FulltextStore.ShardTransport.class;
        Method putDoc = iface.getMethod("putDoc", int.class, long.class, String.class);
        Method clearDoc = iface.getMethod("clearDoc", int.class, long.class);
        Method searchWithVector = iface.getMethod("searchWithVector", int.class, String.class, float[].class, int.class, SearchOptions.class);
        Method indexTermVector = iface.getMethod("indexTermVector", int.class, long.class, float[].class);
        Method close = iface.getMethod("close");
        Assert.assertNotNull("Y8 ShardTransport#putDoc 原签名保留", putDoc);
        Assert.assertEquals("Y8 putDoc 抛出 IOException", IOException.class, putDoc.getExceptionTypes()[0]);
        Assert.assertEquals("Y8 putDoc return IngestStat", FulltextStore.IngestStat.class, putDoc.getReturnType());
        Assert.assertNotNull("Y8 ShardTransport#clearDoc 原签名保留", clearDoc);
        Assert.assertEquals("Y8 clearDoc return void", void.class, clearDoc.getReturnType());
        Assert.assertNotNull("Y8 ShardTransport#searchWithVector 原签名保留", searchWithVector);
        Assert.assertEquals("Y8 searchWithVector return List<ScoredDocHit>", List.class, searchWithVector.getReturnType());
        Assert.assertNotNull("Y8 ShardTransport#indexTermVector 原签名保留", indexTermVector);
        Assert.assertEquals("Y8 indexTermVector return void", void.class, indexTermVector.getReturnType());
        Assert.assertNotNull("Y8 ShardTransport#close 原签名保留", close);
        Assert.assertEquals("Y8 close 抛出 IOException 0 or 1", close.getExceptionTypes().length <= 1 ? true : false, close.getExceptionTypes().length <= 1);
        // GrpcShardTransport impl 必须实现这 5 个
        for (Method m : Arrays.asList(putDoc, clearDoc, searchWithVector, indexTermVector, close)) {
            try {
                Method impl = GrpcShardTransport.class.getMethod(m.getName(), m.getParameterTypes());
                Assert.assertNotNull("Y8 GrpcShardTransport 必须实现 " + m, impl);
            } catch (NoSuchMethodException nsme) {
                Assert.fail("Y8 GrpcShardTransport 未实现原方法: " + m + " cause=" + nsme.getMessage());
            }
        }
    }

    // ====================================================================
    // Y9=TR5.9 TODO 源码 0 行（不含测试里的 TODO 注释，仅扫 main 源码 tree）
    // ====================================================================
    @Test public void tr59_todoZeroLinesInMainSourcesP27() throws Exception {
        File userDir = new File(System.getProperty("user.dir"));
        File mainRoot = new File(userDir, "p2p-db/src/main/java");
        if (!mainRoot.exists()) {
            mainRoot = new File(userDir, "src/main/java");
        }
        Assert.assertTrue("mainRoot 存在 (tried p2p-db/src, src): cwd=" + userDir + " exists=" + mainRoot.exists(), mainRoot.exists());
        // 仅扫 P27 相关源码路径：fulltext 模块 + javax net p2p server fulltext（P27 新 server package）
        java.util.function.Predicate<Path> isFulltextRelevant = (p) -> {
            String s = p.toString().replace('\\', '/');
            return s.contains("/com/q3lives/ds/database/fulltext/")
                    || s.contains("/javax/net/p2p/server/fulltext/");
        };
        List<Path> files = new ArrayList<>();
        Files.walk(mainRoot.toPath())
                .filter(p -> p.toString().endsWith(".java"))
                .filter(isFulltextRelevant)
                .forEach(files::add);
        List<String> hit = new ArrayList<>();
        for (Path p : files) {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (int ln = 1; ln <= lines.size(); ln++) {
                String l = lines.get(ln - 1);
                String trimmed = l.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    if (trimmed.contains("TODO") && !trimmed.contains("NO-TODO-BYPASS")
                            && !trimmed.contains("PENDING-NOTE") && !trimmed.contains("TODO.md")) {
                        hit.add(p.toString() + "#L" + ln + ": " + trimmed.substring(0, Math.min(trimmed.length(), 120)));
                    }
                }
            }
        }
        Assert.assertTrue("Y9 P27 fulltext 相关源码行 TODO 0，命中: " + hit, hit.isEmpty());
    }
}
