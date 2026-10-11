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

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.GrpcShardTransport;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P28_T5_Gatekeeper9YTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int SHARD_COUNT = 2;
    private File root;
    private File tmpRoot;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p28t5-" + UUID.randomUUID());
        tmpRoot = tmp;
        root = new File(tmp, "p28t5-root");
        root.mkdirs();
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
    }
    @After public void tearDown() {
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        System.clearProperty("p2p.db.home");
    }

    @Test public void tr51_y1_zeroLeak3Equals3() throws Exception {
        Set<String> classes = P23_T5_GatekeeperTest.P22_T5_GatekeeperTestHelper.scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext");
        final String PKG = "com.q3lives.ds.database.fulltext.";
        Set<String> topLevel = new HashSet<>();
        for (String cn : classes) {
            if (cn.contains("$")) continue;
            if (!cn.startsWith(PKG)) continue;
            String tail = cn.substring(PKG.length());
            if (tail.indexOf('.') >= 0) continue;
            topLevel.add(cn);
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.FulltextSegmenter"
        ));
        Assert.assertEquals("Y1 ZeroLeak3=3（P28 根包 top-level 类严格 3，HybridHit/DsHybridQueryClient 属子包不计）", 3, topLevel.size());
        Assert.assertEquals("Y1 ZeroLeak3 集合完全相等，不增不减", expected, topLevel);
    }

    @Test public void tr52_y2_ng3NineCnfPlusZeroNettyGrpcClientRoot() throws Exception {
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
        Assert.assertEquals("Y2 NG-3 9 基础类全 CNF（P28 单调不增）", 9, cnf);
        Class<?>[] rootProbes = {
            FulltextStore.class, TermDictStore.class, FulltextSegmenter.class,
            ShardRouter.class, FulltextStore.ShardTransport.class, FulltextStore.LocalShardTransport.class,
            FulltextStore.PositionSpan.class, FulltextStore.SnippetHit.class,
            FulltextStore.FacetHit.class, ScoredDocHitWithExtras.class,
            ScoredDocHit.class, SearchOptions.class
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

    @Test public void tr53_y3_bucketAccessCounterStillZeroTopK() throws Exception {
        DsFulltextClusterClient client = new DsFulltextClusterClient(SHARD_COUNT, null, root.getAbsolutePath(), "p28t5_shard");
        try {
            long[] onePerShard = new long[SHARD_COUNT]; Arrays.fill(onePerShard, -1L); long probe = 900_000L; int got = 0;
            while (got < SHARD_COUNT) {
                int s = Math.abs(Long.hashCode(probe)) % SHARD_COUNT;
                if (onePerShard[s] == -1L) { onePerShard[s] = probe; got++; }
                probe += 11;
            }
            for (int i = 0; i < SHARD_COUNT; i++) client.putDocCluster(onePerShard[i], "CHERRY APPLE BANANA ORANGE PEAR");
            SearchOptions o = SearchOptions.defaults(); o.vectorRecall = false; o.neighborRecallD = 0; o.phraseBoost = false;
            ShardRouter router = client.getRouterForTest();
            Field shardsF = ShardRouter.class.getDeclaredField("shards"); shardsF.setAccessible(true);
            FulltextStore[] shards = (FulltextStore[]) shardsF.get(router);
            for (int s = 0; s < SHARD_COUNT; s++) Arrays.fill(shards[s].bucketAccessCounter, 0);
            Map<Long, String> rawMap = new HashMap<>();
            for (long d : onePerShard) rawMap.put(d, "CHERRY APPLE BANANA ORANGE PEAR");
            for (int i = 0; i < 30; i++) {
                List<ScoredDocHit> hs = client.searchCluster("CHERRY", 10, o);
                Assert.assertFalse("searchCluster not empty iter " + i, hs.isEmpty());
            }
            long sum = 0L;
            for (int s = 0; s < SHARD_COUNT; s++) for (int d = 1; d <= 1024; d++) sum += shards[s].bucketAccessCounter[d];
            Assert.assertEquals("Y3 TOPK bucket sum = 0（零污染 P28）", 0L, sum);
        } finally { client.close(); }
    }

    @Test public void tr54_y4_facadeMonotonicGenericManagerAddOnly() throws Exception {
        Set<String> gmMustPublic = new HashSet<>(Arrays.asList(
            "saveOrUpdate","findList","findRangeByWrapper","getById","removeById","count",
            "existsById","findByExample","listIds","getFieldsMap","getField2ColMap","buildQueryWrapper",
            "buildUpdateWrapper","list","findPropsRangeByMap","findPropListByMap","findOneByProps"
        ));
        Method[] gmM = GenericManager.class.getDeclaredMethods();
        Set<String> pubGm = new HashSet<>();
        for (Method m : gmM) if (Modifier.isPublic(m.getModifiers())) pubGm.add(m.getName());
        for (String n : gmMustPublic) Assert.assertTrue("Y4 GM 基线 public 丢失: " + n + " pub=" + pubGm, pubGm.contains(n));
        Assert.assertTrue("Y4 GM 单调不减 新增 listIds（P28 FR-1）", pubGm.contains("listIds"));
        int listIdsCount = 0;
        for (Method m : gmM) {
            if (m.getName().equals("listIds") && Modifier.isPublic(m.getModifiers())) listIdsCount++;
        }
        Assert.assertEquals("Y4 GM listIds(QueryWrapper) 唯一新增 1 个 public 方法（Facade 单调）", 1, listIdsCount);

        Method[] fsM = FulltextStore.class.getDeclaredMethods();
        Method[] tdM = TermDictStore.class.getDeclaredMethods();
        Set<String> tdPub = new HashSet<>(); for (Method m : tdM) if (Modifier.isPublic(m.getModifiers())) tdPub.add(m.getName());
        Set<String> fsPub = new HashSet<>(); for (Method m : fsM) if (Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());
        Set<String> tdMust = new HashSet<>(Arrays.asList("toUid","toText","retain","release","refCount","close"));
        for (String n : tdMust) Assert.assertTrue("Y4 TD 基线丢失: "+n, tdPub.contains(n));
        Set<String> fsMust = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","close","ingestDoc","search",
            "indexTermVector","cosineSimForTest","searchWithExtras","recomputeIdfForTerms"
        ));
        for (String n : fsMust) Assert.assertTrue("Y4 FS 基线丢失: "+n, fsPub.contains(n));
    }

    @Test public void tr55_y5_routerFormulaUnitDocByteEq() throws Exception {
        long uid = 0xABCD_0000_0000_0000L;
        int shard_2 = FulltextStore.resolveShardForUnitIdForTest(uid, 2);
        Assert.assertEquals("UNIT路由 (uid>>48)&0xFFFF % 2，应为 0xABCD%2 = 1", 1, shard_2);
        long[] docs = {1001L, 2002L, 3003L};
        int[] expectedN4 = new int[docs.length];
        for (int i = 0; i < docs.length; i++) {
            long V = TermDictStore.FarmHash64.mix4(docs[i], 0xC0FFEEL, 0L, 0L);
            long shifted = (V >>> 16) & 0x7FFFFFFFL;
            long r = shifted % 4L;
            if (r < 0) r += 4L;
            expectedN4[i] = (int) r;
        }
        for (int i = 0; i < docs.length; i++) {
            int actual = FulltextStore.resolveShardForDocIdForTest(docs[i], 4);
            Assert.assertEquals("DOC路由 FarmHash.mix4(doc," + 0xC0FFEEL + ",0,0) >>>16 & 0x7FFFFFFF % 4，docId=" + docs[i], expectedN4[i], actual);
        }
    }

    @Test public void tr56_y6_facadeHybridQueryClientSignature() throws Exception {
        Method searchHybrid = null;
        for (Method m : DsHybridQueryClient.class.getDeclaredMethods()) {
            if ("searchHybrid".equals(m.getName()) && Modifier.isPublic(m.getModifiers())) {
                searchHybrid = m;
                break;
            }
        }
        Assert.assertNotNull("Y6 DsHybridQueryClient.searchHybrid 存在（P28 FR-3 Facade）", searchHybrid);
        Class<?>[] pts = searchHybrid.getParameterTypes();
        Assert.assertEquals("Y6 searchHybrid 参数 5 个：ormCondition、fulltextQuery、rawDocTexts、K、searchOptions", 5, pts.length);
        int listIdsCount = 0;
        for (Method m : GenericManager.class.getDeclaredMethods()) {
            if ("listIds".equals(m.getName()) && Modifier.isPublic(m.getModifiers())) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0].getName().endsWith("QueryWrapper")) listIdsCount++;
            }
        }
        Assert.assertTrue("Y6 GenericManager.listIds(QueryWrapper) 新增 ≥1 个", listIdsCount >= 1);

        Constructor<?> hhPub = null;
        for (Constructor<?> c : HybridHit.class.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) && c.getParameterCount() == 2) { hhPub = c; break; }
        }
        Assert.assertNotNull("Y6 HybridHit 公开 2 参数构造（entity, ftx）", hhPub);
        Assert.assertTrue("Y6 HybridHit 4 个 public final 字段",
                HybridHit.class.getFields().length >= 4);
    }

    @Test public void tr57_y7_fingerprintHybridHitInproc10DocEq() throws Exception {
        DsFulltextClusterClient client = new DsFulltextClusterClient(SHARD_COUNT, null, new File(tmpRoot, "p28y7").getAbsolutePath(), "p28t5_y7");
        try {
            final int N = 10;
            String[] docs = new String[N];
            Map<Long, String> rawMap = new HashMap<>();
            long[] ids = new long[N];
            for (int i = 0; i < N; i++) {
                ids[i] = 100001L + i;
                docs[i] = "CHERRY APPLE BANANA ORANGE PEAR GRAPE PINEAPPLE doc " + i + " 人民 创新";
                rawMap.put(ids[i], docs[i]);
                client.putDocCluster(ids[i], docs[i]);
            }
            List<ScoredDocHitWithExtras> r1 = client.searchClusterWithExtras("CHERRY 人民", rawMap, 20, SearchOptions.defaults());
            Thread.sleep(200);
            List<ScoredDocHitWithExtras> r2 = client.searchClusterWithExtras("CHERRY 人民", rawMap, 20, SearchOptions.defaults());
            Assert.assertEquals("Y7 FT search 结果 size 稳定一致（RRF 三键排序字节级全等）", r1.size(), r2.size());
            for (int i = 0; i < r1.size(); i++) {
                Assert.assertEquals("Y7 FT hit[" + i + "] docId 一致", r1.get(i).core.docId, r2.get(i).core.docId);
                Assert.assertEquals("Y7 FT hit[" + i + "] finalScore 一致（浮点字节全等）",
                        String.format("%.12f", r1.get(i).core.finalScore), String.format("%.12f", r2.get(i).core.finalScore));
            }
        } finally { client.close(); }
    }

    @Test public void tr58_y8_shardTransportFiveOriginalMethodsStillExist() throws Exception {
        Set<String> must = new HashSet<>(Arrays.asList(
                "putDoc", "clearDoc", "search", "searchWithExtras", "close"
        ));
        Method[] ms = FulltextStore.ShardTransport.class.getDeclaredMethods();
        Set<String> names = new HashSet<>();
        for (Method m : ms) names.add(m.getName());
        for (String n : must) Assert.assertTrue("Y8 ShardTransport 原 5 方法一字保留（P28 Facade 单调，永不删除）: " + n, names.contains(n));
        Assert.assertTrue("Y8 ShardTransport 原 5 法+默认 putDocBatch 合计 ≥6", names.size() >= 6);
    }

    @Test public void tr59_y9_sourceZeroTodoGrepMarkers() throws Exception {
        Path prj = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        String[] modules = {"p2p-db/src/main/java/com/q3lives/ds/database/fulltext",
                            "p2p-db/src/main/java/com/q3lives/ds/database/integration"};
        List<Path> files = new ArrayList<>();
        for (String mod : modules) {
            Path p = prj.resolve(mod);
            if (!Files.isDirectory(p)) continue;
            Files.walk(p).filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(files::add);
        }
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains("TODO") || l.contains("FIXME") || l.contains("HACK")) {
                    hits.add(f.getFileName() + " L" + (i+1) + ": " + l.trim());
                }
            }
        }
        Assert.assertTrue("Y9 源码 grep TODO/FIXME/HACK = 0 行（实际命中:\n" + String.join("\n  ", hits) + "）", hits.isEmpty());
    }
}
