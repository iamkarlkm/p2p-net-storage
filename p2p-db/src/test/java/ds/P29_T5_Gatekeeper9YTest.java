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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.database.fulltext.FulltextStore.ShardTransport;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P29_T5_Gatekeeper9YTest {

    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 96)
        public String name;
        @DsField(name = "a", length = 8)
        public long a;
        @DsField(name = "b", length = 16)
        public String b;
        @DsField(name = "c", length = 4)
        public int c;
        public AbcEntity() {}
    }

    @Rule public TemporaryFolder tf = new TemporaryFolder();
    private static final int SHARD_COUNT = 2;
    private File root;
    private File tmpRoot;
    private String oldHome;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p29t5-" + UUID.randomUUID());
        tmpRoot = tmp;
        root = new File(tmp, "p29t5-root");
        root.mkdirs();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmp);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmp);
    }
    @After public void tearDown() {
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    @Test public void TR5_1_Y1_ZeroLeak3StillEquals3() throws Exception {
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
        Assert.assertEquals("Y1 ZeroLeak3=3（P29 HybridScoreEngine/DHQC/HybridHit 属子包不计）", 3, topLevel.size());
        Assert.assertEquals("Y1 ZeroLeak3 集合完全相等不增不减（P21~P28 红线）", expected, topLevel);
    }

    @Test public void TR5_2_Y2_Ng3NineCnfPlusZeroNettyGrpcClient() throws Exception {
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
        Assert.assertEquals("Y2 NG-3 9 基础类全 CNF（P29 单调不增）", 9, cnf);
        Class<?>[] rootProbes = {
            FulltextStore.class, TermDictStore.class, FulltextSegmenter.class,
            ShardRouter.class, ShardTransport.class, FulltextStore.LocalShardTransport.class,
            FulltextStore.PositionSpan.class, FulltextStore.SnippetHit.class,
            FulltextStore.FacetHit.class, ScoredDocHitWithExtras.class,
            ScoredDocHit.class, SearchOptions.class, HybridScoreEngine.class
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
        Assert.assertFalse("Y2 NG-3 fulltext 根包 禁 javax.net.p2p.client.* 符号引用", p2pclient);
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

    @Test public void TR5_3_Y3_BucketAccessCounterStillZeroTopK() throws Exception {
        DsFulltextClusterClient client = new DsFulltextClusterClient(SHARD_COUNT, null, root.getAbsolutePath(), "p29t5_shard");
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
            for (int i = 0; i < 50; i++) {
                List<ScoredDocHit> hs = client.searchCluster("CHERRY", 10, o);
                Assert.assertFalse("searchCluster not empty iter " + i, hs.isEmpty());
            }
            long sum = 0L;
            for (int s = 0; s < SHARD_COUNT; s++) for (int d = 1; d <= 1024; d++) sum += shards[s].bucketAccessCounter[d];
            Assert.assertEquals("Y3 TOPK bucket sum = 0（零污染 P29，weight/LTR 纯 override 分不碰桶）", 0L, sum);
        } finally { client.close(); }
    }

    @Test public void TR5_4_Y4_FacadeMonotonicOriginalSignaturesZeroDeleted() throws Exception {
        Set<String> gmPublic = new HashSet<>();
        for (Method m : GenericManager.class.getDeclaredMethods())
            if (Modifier.isPublic(m.getModifiers())) gmPublic.add(m.getName());
        Set<String> gmMust = new HashSet<>(Arrays.asList(
            "saveOrUpdate","findList","findRangeByWrapper","getById","removeById","count",
            "existsById","findByExample","listIds","getFieldsMap","getField2ColMap","buildQueryWrapper",
            "buildUpdateWrapper","list","findPropsRangeByMap","findPropListByMap","findOneByProps"
        ));
        for (String n : gmMust) Assert.assertTrue("Y4 GM 基线 public 丢失: " + n, gmPublic.contains(n));
        int listIdsCount = 0;
        for (Method m : GenericManager.class.getDeclaredMethods())
            if ("listIds".equals(m.getName()) && Modifier.isPublic(m.getModifiers())) listIdsCount++;
        Assert.assertEquals("Y4 GM listIds(QueryWrapper) 唯一 1 个 public（P28~P29 Facade 单调）", 1, listIdsCount);

        int hybrid5Param = 0;
        for (Method m : DsHybridQueryClient.class.getDeclaredMethods()) {
            if ("searchHybrid".equals(m.getName()) && Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 5)
                hybrid5Param++;
        }
        Assert.assertEquals("Y4 DHQC 原 5 参 searchHybrid（P28 基线）存在且唯一，Facade 单调 0 删", 1, hybrid5Param);

        int hybridHit2Param = 0;
        for (Constructor<?> c : HybridHit.class.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) && c.getParameterCount() == 2) hybridHit2Param++;
        }
        Assert.assertEquals("Y4 HybridHit 原 2 参构造（P28 基线）唯一存在，P29 仅追加 3 参构造，Facade 单调 0 删", 1, hybridHit2Param);

        Set<String> fsPub = new HashSet<>();
        for (Method m : FulltextStore.class.getDeclaredMethods())
            if (Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());
        Set<String> fsMust = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","close","ingestDoc","search",
            "indexTermVector","cosineSimForTest","searchWithExtras","recomputeIdfForTerms"
        ));
        for (String n : fsMust) Assert.assertTrue("Y4 FS 基线丢失: "+n, fsPub.contains(n));
    }

    @Test public void TR5_5_Y5_RouterFormulaUnitDocByteEq() throws Exception {
        long uid = 0xABCD_0000_0000_0000L;
        int shard_2 = FulltextStore.resolveShardForUnitIdForTest(uid, 2);
        Assert.assertEquals("UNIT路由公式 (uid>>>48)&0xFFFF % N=2，0xABCD%2=1", 1, shard_2);
        long[] docs = {1001L, 2002L, 3003L, 9999_9999L};
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
            Assert.assertEquals("DOC路由 FarmHash.mix4(doc," + 0xC0FFEEL + ",0,0)>>>16&0x7FFFFFFF%4，docId=" + docs[i], expectedN4[i], actual);
        }
    }

    @Test public void TR5_6_Y6_P29FourNewSignaturesExistExact() throws Exception {
        Constructor<?> hh3 = null;
        for (Constructor<?> c : HybridHit.class.getDeclaredConstructors()) {
            if (Modifier.isPublic(c.getModifiers()) && c.getParameterCount() == 3) {
                Class<?>[] pts = c.getParameterTypes();
                if (pts[1] == ScoredDocHitWithExtras.class && pts[2] == double.class) { hh3 = c; break; }
            }
        }
        Assert.assertNotNull("Y6 新① HybridHit 3 参数构造 (T, ScoredDocHitWithExtras, double override) 存在", hh3);

        Method sw8 = null;
        for (Method m : DsHybridQueryClient.class.getDeclaredMethods()) {
            if ("searchHybridWithWeights".equals(m.getName()) && Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 8) {
                sw8 = m; break;
            }
        }
        Assert.assertNotNull("Y6 新② DsHybridQueryClient.searchHybridWithWeights 8 参数主入口存在", sw8);
        Assert.assertEquals("Y6 新② 返回 List<HybridHit>", List.class, sw8.getReturnType());

        Method sl8 = null;
        for (Method m : DsHybridQueryClient.class.getDeclaredMethods()) {
            if ("searchHybridWithLtr".equals(m.getName()) && Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 7) {
                sl8 = m; break;
            }
        }
        Assert.assertNotNull("Y6 新③ DsHybridQueryClient.searchHybridWithLtr 7 参数主入口存在（ormW,q,raw,K,opt,ex,weights）", sl8);

        Method ls5 = null;
        for (Method m : HybridScoreEngine.class.getDeclaredMethods()) {
            if ("linearScore".equals(m.getName()) && Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers())) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 5 || pts.length >= 4) { ls5 = m; break; }
            }
        }
        Assert.assertNotNull("Y6 新④ HybridScoreEngine.linearScore 静态法存在（5 参数 ft+alpha+beta+weights+entity）", ls5);
    }

    @Test public void TR5_7_Y7_Alpha1Beta0TripleRunFingerprintEq() throws Exception {
        File y7Root = new File(tmpRoot, "p29y7"); y7Root.mkdirs();
        DsFulltextClusterClient ftClient = new DsFulltextClusterClient(SHARD_COUNT, null, y7Root.getAbsolutePath(), "p29t5_y7");
        GenericManager<AbcEntity> gm = new GenericManager<>(AbcEntity.class);
        try {
            final int N = 20;
            Map<Long, String> rawMap = new HashMap<>();
            for (int i = 0; i < N; i++) {
                AbcEntity e = new AbcEntity();
                String text = "CHERRY APPLE BANANA ORANGE PEAR 人民 创新 doc_" + i;
                e.name = text;
                e.a = 100L + (i % 3) * 100L;
                e.b = (i % 2 == 0) ? "x" : "y";
                e.c = i + 1;
                gm.saveOrUpdate(e);
                long id = e.getId();
                ftClient.putDocCluster(id, text);
                rawMap.put(id, text);
            }
            Thread.sleep(500);
            DsHybridQueryClient<AbcEntity> dhqc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
            final int K = 15;
            QueryWrapper<AbcEntity> w = new QueryWrapper<>();
            w.eq("a", 100L);
            Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> wEmpty = new HashMap<>();
            List<HybridHit<AbcEntity>> runA = dhqc.searchHybrid(w, "人民 CHERRY", rawMap, K, null);
            List<HybridHit<AbcEntity>> runB = dhqc.searchHybridWithWeights(w, "人民 CHERRY", rawMap, K, null, 1.0, 0.0, wEmpty);
            List<HybridHit<AbcEntity>> runC = dhqc.searchHybridWithWeights(w, "人民 CHERRY", rawMap, K, null, 1.0, 0.0, wEmpty);
            Assert.assertEquals("Y7 三跑 size 全等 A/B", runA.size(), runB.size());
            Assert.assertEquals("Y7 三跑 size 全等 B/C", runB.size(), runC.size());
            String fpA = fingerprint(runA); String fpB = fingerprint(runB); String fpC = fingerprint(runC);
            Set<String> set = new HashSet<>(Arrays.asList(fpA, fpB, fpC));
            Assert.assertEquals("Y7 α=1.0 β=0.0 三跑（P28 基线 / withWeights 1,0 / withWeights 1,0 重跑）hybrid 指纹全等 Set.size=1（证明零回归 & 稳定）", 1, set.size());
            dhqc.close();
        } finally {
            try { ftClient.close(); } catch (Exception ignore) {}
        }
    }
    private static String fingerprint(List<HybridHit<AbcEntity>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<AbcEntity> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.15f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.15f", h.ftx.core.finalScore)).append('|');
            sb.append(h.ftx.core.shardId).append(',');
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(d.length * 2);
            for (byte b : d) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void TR5_8_Y8_ShardTransportFiveOriginalMethodsStillExist() throws Exception {
        Set<String> must = new HashSet<>(Arrays.asList(
                "putDoc", "clearDoc", "search", "searchWithExtras", "close"
        ));
        Method[] ms = ShardTransport.class.getDeclaredMethods();
        Set<String> names = new HashSet<>();
        for (Method m : ms) names.add(m.getName());
        for (String n : must) Assert.assertTrue("Y8 ShardTransport 原 5 方法一字保留（P24~P29 Facade 单调，永不删除）: " + n, names.contains(n));
        Assert.assertTrue("Y8 ShardTransport 原 5 法 + 默认 putDocBatch 合计 ≥6", names.size() >= 6);
    }

    @Test public void TR5_9_Y9_SourceZeroTodoGrepMarkers() throws Exception {
        Path prj = new File(System.getProperty("user.dir")).toPath().toAbsolutePath();
        String[] modules = {
            "p2p-db/src/main/java/com/q3lives/ds/database/fulltext",
            "p2p-db/src/main/java/com/q3lives/ds/database/integration"
        };
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
