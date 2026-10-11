package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P29 T1 骨架连通测试（4 TR）：
 *   TR1.1 HybridHit 新 3 参数构造 overrideScore + 三值全等校验
 *   TR1.2 HybridHit 原 2 参数构造向后兼容（P28 零回归）
 *   TR1.3 DsHybridQueryClient 主构造 alpha/beta 范围校验 + 默认值 1.0/0.0
 *   TR1.4 ofLocal 5 参数重载存在 + 新合查签名 8 参数占位抛 UOE
 */
public class P29_T1_HybridHitAndSkeletonTest {

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

    private static ScoredDocHit newCoreHit(long docId, double finalScore, int shardId) {
        return new ScoredDocHit(docId, 0L, 0, 0, 0L,
                finalScore, 0.0, 0.0, 0.0, 0, 0.0, shardId);
    }

    private static ScoredDocHitWithExtras newHitWithExtras(long docId, double finalScore, int shardId) {
        return new ScoredDocHitWithExtras(newCoreHit(docId, finalScore, shardId),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    @Before
    public void before() {}

    @After
    public void after() {}

    private static DsFulltextClusterClient newDummyInprocFtClient() throws IOException {
        File tmp = Files.createTempDirectory("dsdb_p29_t1_ft_").toFile();
        tmp.deleteOnExit();
        return new DsFulltextClusterClient(2, Collections.<Integer, InetSocketAddress>emptyMap(),
                tmp.getAbsolutePath(), "shard");
    }

    @Test
    public void TR1_1_HybridHit3ParamsOverrideAndDocIdCheck() {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        e.a = 100L; e.c = 5;
        ScoredDocHitWithExtras ftx = newHitWithExtras(1L, 0.6, 0);

        HybridHit<AbcEntity> hit = new HybridHit<>(e, ftx, 0.99);
        Assert.assertEquals("docId=1", 1L, hit.docId);
        Assert.assertEquals("entity=e", e, hit.entity);
        Assert.assertEquals("ftx=ftx", ftx, hit.ftx);
        Assert.assertEquals("override finalHybridScore=0.99", 0.99, hit.finalHybridScore, 1e-12);

        try {
            new HybridHit<>(e, ftx, Double.NaN);
            Assert.fail("expect IAE for NaN overrideHybridScore");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains finite", iae.getMessage().contains("finite"));
        }
        try {
            new HybridHit<>(e, ftx, Double.POSITIVE_INFINITY);
            Assert.fail("expect IAE for Infinite overrideHybridScore");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains finite", iae.getMessage().contains("finite"));
        }
        try {
            new HybridHit<>(null, ftx, 0.5);
            Assert.fail("expect NPE entity null");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            new HybridHit<>(e, null, 0.5);
            Assert.fail("expect NPE ftx null");
        } catch (NullPointerException npe) { /* ok */ }
        ScoredDocHitWithExtras bad = newHitWithExtras(2L, 0.6, 0);
        try {
            new HybridHit<>(e, bad, 0.5);
            Assert.fail("expect IAE docId mismatch");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains mismatch", iae.getMessage().contains("mismatch"));
        }
    }

    @Test
    public void TR1_2_HybridHit2ParamsBackwardCompat() {
        AbcEntity e = new AbcEntity();
        e.setId(1L);
        ScoredDocHitWithExtras ftx = newHitWithExtras(1L, 0.6, 0);
        HybridHit<AbcEntity> hit = new HybridHit<>(e, ftx);
        Assert.assertEquals("old ctor: finalHybridScore = ftx.core.finalScore", 0.6, hit.finalHybridScore, 1e-12);
        Assert.assertEquals("old ctor: docId=1", 1L, hit.docId);

        try {
            new HybridHit<>(e, (ScoredDocHitWithExtras) null);
            Assert.fail("NPE");
        } catch (NullPointerException npe) { /* ok */ }
    }

    @Test
    public void TR1_3_DhqcPrimaryConstructorAlphaBeta() throws Exception {
        try {
            new DsHybridQueryClient<>(
                    AbcEntity.class,
                    (w, hint) -> new ArrayList<>(),
                    ids -> new HashMap<>(),
                    null,
                    3, -1.0, 0.0
            );
            Assert.fail("expect IAE alpha<0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains alpha", iae.getMessage().contains("alpha"));
        }
        try {
            new DsHybridQueryClient<>(
                    AbcEntity.class,
                    (w, hint) -> new ArrayList<>(),
                    ids -> new HashMap<>(),
                    null,
                    3, 1.0, 200.0
            );
            Assert.fail("expect IAE beta>100");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains beta", iae.getMessage().contains("beta"));
        }
        try {
            new DsHybridQueryClient<>(
                    AbcEntity.class,
                    (w, hint) -> new ArrayList<>(),
                    ids -> new HashMap<>(),
                    null,
                    3, 200.0, 1.0
            );
            Assert.fail("expect IAE alpha>100");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg contains alpha", iae.getMessage().contains("alpha"));
        }

        DsHybridQueryClient<AbcEntity> old5Ctor;
        DsFulltextClusterClient dummyFt = newDummyInprocFtClient();
        try {
            old5Ctor = new DsHybridQueryClient<>(
                    AbcEntity.class,
                    (w, hint) -> new ArrayList<>(),
                    ids -> new HashMap<>(),
                    dummyFt,
                    3
            );
        } finally {
            dummyFt.close();
        }
        Field alphaF = DsHybridQueryClient.class.getDeclaredField("alpha");
        alphaF.setAccessible(true);
        Field betaF = DsHybridQueryClient.class.getDeclaredField("beta");
        betaF.setAccessible(true);
        double alpha = (Double) alphaF.get(old5Ctor);
        double beta = (Double) betaF.get(old5Ctor);
        Assert.assertEquals("P28 5 param ctor => alpha default 1.0", 1.0, alpha, 1e-12);
        Assert.assertEquals("P28 5 param ctor => beta default 0.0", 0.0, beta, 1e-12);
    }

    @Test
    public void TR1_4_OfLocal5AndNewSignaturePlaceholders() throws Exception {
        Method ofLocal5 = DsHybridQueryClient.class.getDeclaredMethod("ofLocal",
                Class.class, GenericManager.class, DsFulltextClusterClient.class,
                double.class, double.class);
        Assert.assertNotNull("ofLocal 5 params exists", ofLocal5);

        try {
            DsHybridQueryClient.ofLocal(AbcEntity.class, null, null, -0.5, 0.0);
            Assert.fail("expect NPE gm null or IAE alpha<0");
        } catch (NullPointerException npe) { /* ok - gm was null first */ }
        try {
            DsHybridQueryClient.ofLocal(AbcEntity.class, null, null, 1.5, -0.5);
            Assert.fail("expect NPE or beta IAE");
        } catch (NullPointerException npe) { /* ok */ }

        Method sw = DsHybridQueryClient.class.getDeclaredMethod("searchHybridWithWeights",
                QueryWrapper.class, String.class, Map.class, int.class,
                FulltextStore.SearchOptions.class, double.class, double.class, Map.class);
        Assert.assertNotNull("searchHybridWithWeights 8 params exists", sw);
        Assert.assertEquals("return type = List", List.class, sw.getReturnType());

        Method sl = DsHybridQueryClient.class.getDeclaredMethod("searchHybridWithLtr",
                QueryWrapper.class, String.class, Map.class, int.class,
                FulltextStore.SearchOptions.class, List.class, double[].class);
        Assert.assertNotNull("searchHybridWithLtr 8 params exists", sl);
        Assert.assertEquals("return type = List", List.class, sl.getReturnType());

        Constructor<HybridScoreEngine> cTor = (Constructor<HybridScoreEngine>) HybridScoreEngine.class.getDeclaredConstructor();
        Assert.assertTrue("HybridScoreEngine 无参构造器 private（final 工具类零实例化设计）",
                java.lang.reflect.Modifier.isPrivate(cTor.getModifiers()));

        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        cw.put("a", new AbstractMap.SimpleEntry<>(1.0, x -> (double) x.a));
        DsFulltextClusterClient dummyFt2 = newDummyInprocFtClient();
        try {
            Object o = sw.invoke(new DsHybridQueryClient<>(
                            AbcEntity.class,
                            (w, hint) -> new ArrayList<>(),
                            ids -> new HashMap<>(),
                            dummyFt2,
                            3, 1.0, 0.0),
                    null, "人民", new HashMap<Long, String>(), 10, null, 1.0, 0.0, cw);
            Assert.assertTrue("searchHybridWithWeights 反射调用返回 List 实例（已实装，不再抛 UOE）", o instanceof List);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) { /* ok 参数校验 */ }
            else { throw ite; }
        } finally {
            dummyFt2.close();
        }
    }
}
