package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P29 T3 单 JVM inproc 模式加权合查 end2end（6 TR）：
 *   TR3.1 searchHybridWithWeights 参数 NPE/IAE 9 组
 *   TR3.2 手算 linearScore 与合查结果逐项 1e-9（Q-FILTER a=100）
 *   TR3.3 α=1.0 β=0 与 P28 searchHybrid 字节级 fingerprint 全等
 *   TR3.4 LTR MVP 4 特征线性模型手算逐项 1e-9
 *   TR3.5 同分 tie-break 顺序稳定（强制 override 同 score 10 次跑顺序 md5 Set.size=1）
 *   TR3.6 Q-EMPTY 返回空 List 不抛异常
 */
public class P29_T3_SingleJvmInprocHybridWeightTest {

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

    private static final String[] CORPUS_SEED = new String[]{
            "人民民主是社会主义的生命 中国特色社会主义进入新时代",
            "科技创新推动高质量发展 新质生产力 突破 攻关",
            "绿水青山就是金山银山 生态环境保护 绿色低碳可持续",
            "人类命运共同体理念 全球治理 国际合作共赢",
            "全面建设社会主义现代化国家 中华民族伟大复兴中国梦",
            "全过程人民民主的制度优势 依法治国 共同富裕",
            "一带一路国际合作共赢倡议 文化自信自强 中华传统",
            "粤港澳大湾区建设发展规划 长三角一体化 京津冀协同",
            "数字经济与实体经济深度融合 大数据 人工智能 大模型",
            "碳达峰碳中和双碳目标行动 乡村振兴 农业农村现代化"
    };

    private static final int N = 30;
    private static final int SHARDS = 2;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private DsFulltextClusterClient ftClient;
    private GenericManager<AbcEntity> gm;
    private Map<Long, String> rawDocTexts;
    private List<Long> entityIds;
    private DsHybridQueryClient<AbcEntity> dhqc;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p29_t3_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        ftRoot = new File(tmpRoot, "ft_root");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, Collections.emptyMap(),
                ftRoot.getAbsolutePath(), "shard");
        gm = new GenericManager<>(AbcEntity.class);
        rawDocTexts = new HashMap<>();
        entityIds = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            int seed = i % CORPUS_SEED.length;
            String text = CORPUS_SEED[seed] + " doc_" + String.format("%02d", i)
                    + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
            e.name = text;
            e.a = 100L + (i % 3) * 100L;
            e.b = (i % 2 == 0) ? "x" : "y";
            e.c = (seed + 1);
            gm.saveOrUpdate(e);
            long docId = e.getId();
            IngestStat st = ftClient.putDocCluster(docId, text);
            Assert.assertNotNull("putDocCluster stat 非 null", st);
            rawDocTexts.put(docId, text);
            entityIds.add(docId);
        }
        Assert.assertEquals("写入 30 条 AbcEntity + FT doc", N, entityIds.size());
        dhqc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        Thread.sleep(500);
    }

    @After
    public void after() {
        try { if (dhqc != null) dhqc.close(); } catch (Exception ignore) {}
        try { if (ftClient != null) ftClient.close(); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        deleteRecursive(tmpRoot);
        if (oldHome == null) System.clearProperty("p2p.db.home");
        else System.setProperty("p2p.db.home", oldHome);
    }

    private static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String hybridFingerprint(List<HybridHit<AbcEntity>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<AbcEntity> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.15f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.15f", h.ftx.core.finalScore)).append('|');
            sb.append(h.entity.a).append('|').append(h.entity.c).append('|');
            sb.append(h.ftx.core.shardId).append(',');
        }
        return md5(sb.toString());
    }

    private static Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> newWeights_a001_c002() {
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        cw.put("a", new AbstractMap.SimpleEntry<>(0.01, x -> x.a));
        cw.put("c", new AbstractMap.SimpleEntry<>(0.02, x -> x.c));
        return cw;
    }

    @Test
    public void TR3_1_WeightsParamNpeIaeAll9Defense() throws Exception {
        try {
            dhqc.searchHybridWithWeights(null, null, rawDocTexts, 10, null, 0.3, 0.7,
                    Collections.emptyMap());
            Assert.fail("expect NPE fulltextQuery null");
        } catch (NullPointerException npe) {
            Assert.assertTrue("msg fulltextQuery", npe.getMessage().contains("fulltextQuery"));
        }
        try {
            dhqc.searchHybridWithWeights(null, "   \t\n", rawDocTexts, 10, null, 0.3, 0.7,
                    Collections.emptyMap());
            Assert.fail("expect IAE blank");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg blank", iae.getMessage().contains("blank"));
        }
        try {
            dhqc.searchHybridWithWeights(null, "人民", null, 10, null, 0.3, 0.7,
                    Collections.emptyMap());
            Assert.fail("NPE rawDocTextsByDocId");
        } catch (NullPointerException npe) { /* ok */ }
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 0, null, 0.3, 0.7,
                    Collections.emptyMap());
            Assert.fail("IAE K<=0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg K", iae.getMessage().contains("K"));
        }
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 10, null, -1.0, 0.7,
                    Collections.emptyMap());
            Assert.fail("IAE alpha<0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg alpha", iae.getMessage().contains("alpha"));
        }
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 10, null, 1.0, -0.5,
                    Collections.emptyMap());
            Assert.fail("IAE beta<0");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg beta", iae.getMessage().contains("beta"));
        }
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 10, null, 1.0, 0.5, null);
            Assert.fail("NPE columnWeights null");
        } catch (NullPointerException npe) { /* ok */ }
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> bad1 = new HashMap<>();
        bad1.put("  ", new AbstractMap.SimpleEntry<>(1.0, x -> 0.0));
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 10, null, 1.0, 0.5, bad1);
            Assert.fail("IAE key blank");
        } catch (IllegalArgumentException iae) {
            Assert.assertTrue("msg key", iae.getMessage().contains("key"));
        }
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> bad2 = new HashMap<>();
        bad2.put("x", new AbstractMap.SimpleEntry<>(1.0, null));
        try {
            dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, 10, null, 1.0, 0.5, bad2);
            Assert.fail("NPE extractor null");
        } catch (NullPointerException npe) { /* ok */ }
    }

    @Test
    public void TR3_2_QFilterHandCalcScoresExact() throws Exception {
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = newWeights_a001_c002();
        final double alpha = 0.3;
        final double beta = 0.7;
        List<HybridHit<AbcEntity>> hits = dhqc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null, alpha, beta, cw);
        Assert.assertTrue("命中 size >0，size=" + hits.size(), !hits.isEmpty());
        Assert.assertTrue("size ≤10 (K=10)", hits.size() <= 10);
        Set<Long> hitDocIds = new HashSet<>();
        for (HybridHit<AbcEntity> h : hits) {
            AbcEntity e = h.entity;
            Assert.assertEquals("Q-FILTER a==100", 100L, e.a);
            long docId = h.docId;
            hitDocIds.add(docId);
            double expected = alpha * h.ftx.core.finalScore + beta * (0.01 * e.a + 0.02 * e.c);
            Assert.assertEquals("TR3.2 手算线性融合 docId=" + docId, expected, h.finalHybridScore, 1e-9);
        }
        List<HybridHit<AbcEntity>> baseP28Hits = dhqc.searchHybrid(w, "人民", rawDocTexts, 10, null);
        Set<Long> baseIds = baseP28Hits.stream().map(h -> h.docId).collect(Collectors.toSet());
        Assert.assertEquals("TR3.2 权重不改交集，docId Set equals", baseIds, hitDocIds);
    }

    /**
     * TR3.3：α=1,β=0 与 P28 searchHybrid (原 5 参数) 对比 fingerprint 全等 1e-9。
     *   说明：P28 searchHybrid 内部 HybridHit 2 参数构造 finalHybridScore=ftx.core.finalScore；
     *   P29 α=1β=0 overrideHybridScore=linearScore(1*ft + 0*列)=ftx.core.finalScore；
     *   结果排序同规则 (finalHybridScore↓ docId↑ shardId↑) → md5 指纹一致。
     */
    @Test
    public void TR3_3_Alpha1Beta0ZeroRegressionFingerprint() throws Exception {
        final int K = 20;
        List<HybridHit<AbcEntity>> A = dhqc.searchHybrid(null, "人民", rawDocTexts, K, null);
        List<HybridHit<AbcEntity>> B = dhqc.searchHybridWithWeights(null, "人民", rawDocTexts, K, null,
                1.0, 0.0, new HashMap<>());
        Assert.assertEquals("A/B size 相等", A.size(), B.size());
        for (int i = 0; i < A.size(); i++) {
            HybridHit<AbcEntity> a = A.get(i);
            HybridHit<AbcEntity> b = B.get(i);
            Assert.assertEquals("docId[" + i + "]", a.docId, b.docId);
            Assert.assertEquals("finalHybridScore[" + i + "]", a.finalHybridScore, b.finalHybridScore, 1e-12);
            Assert.assertEquals("ftx.core.finalScore[" + i + "]", a.ftx.core.finalScore, b.ftx.core.finalScore, 1e-12);
            Assert.assertEquals("shardId[" + i + "]", a.ftx.core.shardId, b.ftx.core.shardId);
        }
        String fpA = hybridFingerprint(A);
        String fpB = hybridFingerprint(B);
        Set<String> set = new HashSet<>();
        set.add(fpA);
        set.add(fpB);
        Assert.assertEquals("TR3.3 双指纹 Set.size=1 字节级全等", 1, set.size());
    }

    /**
     * TR3.4：LTR 4 特征 MVP：features = [ft.finalScore, a*0.01, c*0.02, (b=="x"?1:0)]
     *   weights = [0.5, 0.3, 0.15, 0.05]。手算每条 finalHybridScore。
     */
    @Test
    public void TR3_4_LtrLinearModel4FeaturesHandCalc() throws Exception {
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        List<HybridHit<AbcEntity>> baseHits = dhqc.searchHybrid(w, "人民", rawDocTexts, 10, null);
        double[] weights = new double[]{0.5, 0.3, 0.15, 0.05};
        List<ToDoubleFunction<AbcEntity>> ex = new ArrayList<>();
        ex.add(__unused -> 0.0); // 占位，后面手动补
        ex.add(x -> x.a * 0.01);
        ex.add(x -> x.c * 0.02);
        ex.add(x -> ("x".equals(x.b)) ? 1.0 : 0.0);
        // 先手动算 expected map 映射 docId->expected score
        Map<Long, Double> expected = new HashMap<>();
        for (HybridHit<AbcEntity> h : baseHits) {
            AbcEntity e = h.entity;
            double[] fv = new double[4];
            fv[0] = h.ftx.core.finalScore;
            fv[1] = e.a * 0.01;
            fv[2] = e.c * 0.02;
            fv[3] = ("x".equals(e.b)) ? 1.0 : 0.0;
            double s = 0;
            for (int i = 0; i < 4; i++) s += fv[i] * weights[i];
            expected.put(h.docId, s);
        }
        // 用 DHQC 的 ofLocal 子类化重写 extractors[0]：直接改 searchHybridWithLtr 的 extractors list，
        // 让 extractors[0] 返回 h.ftx.core.finalScore（但 extractors 输入是 entity，拿不到 ftx）。
        // 正确做法：把 ftx.core.finalScore 作为 extractors 调用时临时写入 ThreadLocal；或直接 hand calc。
        // MVP T3：直接用 searchHybridWithWeights 路径替代，手算 LTR。
        // 更标准：手动提取 features（不依赖 DHQC 内部）跑 HybridScoreEngine.ltrLinearScore。
        for (HybridHit<AbcEntity> h : baseHits) {
            AbcEntity e = h.entity;
            double[] fv = new double[]{
                    h.ftx.core.finalScore,
                    e.a * 0.01,
                    e.c * 0.02,
                    ("x".equals(e.b)) ? 1.0 : 0.0
            };
            double ltr = com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine.ltrLinearScore(fv, weights);
            Assert.assertEquals("TR3.4 手算 LTR dot product docId=" + h.docId,
                    expected.get(h.docId).doubleValue(), ltr, 1e-12);
        }
        // 跑真正的 searchHybridWithLtr 端到端：使用 ThreadLocal 临时注入 ftx。
        ThreadLocal<Map<Long, Double>> ftScoreMapLocal = new ThreadLocal<>();
        Map<Long, Double> fts = new HashMap<>();
        for (HybridHit<AbcEntity> h : baseHits) fts.put(h.docId, h.ftx.core.finalScore);
        ftScoreMapLocal.set(fts);
        try {
            List<ToDoubleFunction<AbcEntity>> ltrEx = new ArrayList<>();
            ltrEx.add(x -> {
                Double v = ftScoreMapLocal.get().get(x.getId());
                return v == null ? 0.0 : v;
            });
            ltrEx.add(x -> x.a * 0.01);
            ltrEx.add(x -> x.c * 0.02);
            ltrEx.add(x -> ("x".equals(x.b)) ? 1.0 : 0.0);
            List<HybridHit<AbcEntity>> ltrHits = dhqc.searchHybridWithLtr(
                    w, "人民", rawDocTexts, 10, null, ltrEx, weights);
            Assert.assertFalse("LTR 命中非空", ltrHits.isEmpty());
            for (HybridHit<AbcEntity> h : ltrHits) {
                double exp = expected.get(h.docId);
                Assert.assertEquals("TR3.4 LTR end2end finalHybridScore docId=" + h.docId, exp, h.finalHybridScore, 1e-9);
            }
            Set<Long> ltrIds = ltrHits.stream().map(h -> h.docId).collect(Collectors.toSet());
            Set<Long> baseIds = baseHits.stream().map(h -> h.docId).collect(Collectors.toSet());
            Assert.assertEquals("TR3.4 LTR 不改交集 docId Set equals", baseIds, ltrIds);
        } finally {
            ftScoreMapLocal.remove();
        }
    }

    /**
     * TR3.5：同分 tie-break 顺序稳定。手动构造 5 条 ftx 同 finalHybridScore，排序按 docId↑ shardId↑。
     */
    @Test
    public void TR3_5_SameScoreTieBreakStable() throws Exception {
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        // 所有列权重 0，alpha=0 → overrideScore 恒=0（同分）。注意 beta=0，或 alpha=0 beta=0 所有 finalHybridScore=0。
        List<HybridHit<AbcEntity>> base = dhqc.searchHybrid(w, "人民", rawDocTexts, 20, null);
        int n = Math.min(5, base.size());
        Set<String> orderFps = new HashSet<>();
        for (int run = 0; run < 10; run++) {
            List<HybridHit<AbcEntity>> hits = dhqc.searchHybridWithWeights(w, "人民", rawDocTexts, 20, null,
                    0.0, 0.0, cw);
            Assert.assertTrue("至少命中 ≥" + n, hits.size() >= n);
            StringBuilder sb = new StringBuilder();
            for (HybridHit<AbcEntity> h : hits) {
                sb.append(h.docId).append('_').append(h.ftx.core.shardId).append(',');
            }
            orderFps.add(md5(sb.toString()));
        }
        Assert.assertEquals("TR3.5 同分 tie-break 连续 10 次顺序稳定 md5 Set.size=1", 1, orderFps.size());
    }

    @Test
    public void TR3_6_QEmptyReturnsEmpty() throws Exception {
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 99999L);
        List<HybridHit<AbcEntity>> hits = dhqc.searchHybridWithWeights(w, "人民", rawDocTexts, 10, null,
                0.3, 0.7, newWeights_a001_c002());
        Assert.assertEquals("Q-EMPTY 返回 size=0", 0, hits.size());
        hits = dhqc.searchHybridWithLtr(w, "人民", rawDocTexts, 10, null,
                Arrays.asList(x -> x.a), new double[]{1.0});
        Assert.assertEquals("Q-EMPTY LTR 返回 size=0", 0, hits.size());
    }

}
