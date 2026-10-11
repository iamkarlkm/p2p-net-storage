package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
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
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.LtrSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsedHybridQuery;
import com.q3lives.ds.database.fulltext.cluster.parser.WeightSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P30 T4 Inproc E2E 6TR：DSL → parse → DHQC 三选一路径 结果 vs 手写 Java 参数 fingerprint 全等。
 * 复用 P29_T3 语料（30 doc，a=100/200/300×10，CHERRY_COMMON 公共词）+ 相同 before/after。
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T4_InprocE2eHybridParserTest {

    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "a", length = 8) public long a;
        @DsField(name = "b", length = 16) public String b;
        @DsField(name = "c", length = 4) public int c;
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
    private DsFulltextClusterClient ftClient;
    private GenericManager<AbcEntity> gm;
    private Map<Long, String> rawDocTexts;
    private List<Long> entityIds;
    private DsHybridQueryClient<AbcEntity> dhqc;

    private static final Map<String, ToDoubleFunction<AbcEntity>> W_MAP;
    private static final Map<String, ToDoubleFunction<AbcEntity>> L_MAP;
    static {
        W_MAP = new HashMap<>();
        W_MAP.put("a", e -> (double) e.a);
        W_MAP.put("c", e -> (double) e.c);
        L_MAP = new HashMap<>();
        L_MAP.put("a", e -> (double) e.a * 0.01);
        L_MAP.put("c", e -> (double) e.c * 0.02);
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p30_t4_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll"); m.setAccessible(true); m.invoke(null);
        } catch (Throwable ignore) {}
        File ftRoot = new File(tmpRoot, "ft_root");
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
        StringBuilder sb = new StringBuilder(hits.size() * 80);
        for (HybridHit<AbcEntity> h : hits) {
            sb.append(h.docId).append('|');
            sb.append(String.format("%.15f", h.finalHybridScore)).append('|');
            sb.append(String.format("%.15f", h.ftx.core.finalScore)).append('|');
            sb.append(h.entity.a).append('|').append(h.entity.c).append('|');
            sb.append(h.ftx.core.shardId).append(',');
        }
        return md5(sb.toString());
    }

    /* --- helpers: columnWeights Map for 8-param --- */
    private static Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> toColumnWeights(WeightSpec ws) {
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> out = new HashMap<>();
        for (Map.Entry<String, Double> e : ws.columnWeights.entrySet()) {
            out.put(e.getKey(), new AbstractMap.SimpleEntry<>(e.getValue(), W_MAP.get(e.getKey())));
        }
        return out;
    }

    /* ================ TR4.1 5 参 searchHybrid Q-ALL ================ */
    @Test
    public void TR4_1_FiveParamSearchHybrid_QAll_DslVsHand_FingerprintEqual() throws Exception {
        final String ftQ = "透气";
        // A: 手写
        QueryWrapper<AbcEntity> wa = new QueryWrapper<>();
        wa.like("name", "%透气%");
        wa.ge("c", Long.valueOf(1L));
        wa.le("c", Long.valueOf(30L));
        List<HybridHit<AbcEntity>> a = dhqc.searchHybrid(wa, ftQ, rawDocTexts, 20, null);

        // B: DSL
        String dsl = "name LIKE '%透气%' AND c >= 1 AND c <= 30 AND FT('透气')";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        Assert.assertTrue("TR4.1 DeepEquals ormCond match",
                ParsedHybridQuery.deepEquals(new ParsedHybridQuery<>(wa, ftQ, WeightSpec.DEFAULT, null), p));
        List<HybridHit<AbcEntity>> b = dhqc.searchHybrid(p.ormCond, p.ftQuery, rawDocTexts, 20, null);

        Assert.assertEquals("TR4.1 size equal", a.size(), b.size());
        Assert.assertEquals("TR4.1 fingerprint md5 same", hybridFingerprint(a), hybridFingerprint(b));
    }

    /* ================ TR4.2 5 参 Q-FILTER a=100 ================ */
    @Test
    public void TR4_2_FiveParamSearchHybrid_QFilterA100_DslVsHand() throws Exception {
        final String ftQ = "透气 跑步鞋 java 并发";
        QueryWrapper<AbcEntity> wa = new QueryWrapper<>();
        wa.eq("a", Long.valueOf(100L));
        List<HybridHit<AbcEntity>> a = dhqc.searchHybrid(wa, ftQ, rawDocTexts, 10, null);

        String dsl = "a = 100 AND FT('透气 跑步鞋 java 并发')";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        List<HybridHit<AbcEntity>> b = dhqc.searchHybrid(p.ormCond, p.ftQuery, rawDocTexts, 10, null);

        Assert.assertEquals("TR4.2 size=10 组 size equal", 10, a.size());
        Assert.assertEquals("TR4.2 size equal", a.size(), b.size());
        for (HybridHit<AbcEntity> h : b) {
            Assert.assertEquals("TR4.2 所有命中 a==100", 100L, h.entity.a);
        }
        Assert.assertEquals("TR4.2 fingerprint same", hybridFingerprint(a), hybridFingerprint(b));
    }

    /* ================ TR4.3 8 参 searchHybridWithWeights Q-ALL WEIGHT(α,β,2列) ================ */
    @Test
    public void TR4_3_EightParamWeighted_QAll_DslVsHand_FingerprintEqual() throws Exception {
        final String ftQ = "跑步鞋";
        final double alpha = 0.3, beta = 0.7;
        QueryWrapper<AbcEntity> wa = new QueryWrapper<>();
        wa.like("name", "%鞋%");
        wa.ge("c", Long.valueOf(1L));
        wa.le("c", Long.valueOf(30L));
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        cw.put("a", new AbstractMap.SimpleEntry<>(0.01, x -> (double) x.a));
        cw.put("c", new AbstractMap.SimpleEntry<>(0.02, x -> (double) x.c));
        List<HybridHit<AbcEntity>> a = dhqc.searchHybridWithWeights(
                wa, ftQ, rawDocTexts, 20, null, alpha, beta, cw);

        String dsl = "name LIKE '%鞋%' AND c >= 1 AND c <= 30 AND FT('跑步鞋')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02)";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        Assert.assertEquals("TR4.3 alpha", alpha, p.weightSpec.alpha, 1e-12);
        Assert.assertEquals("TR4.3 beta", beta, p.weightSpec.beta, 1e-12);
        List<HybridHit<AbcEntity>> b = dhqc.searchHybridWithWeights(
                p.ormCond, p.ftQuery, rawDocTexts, 20, null,
                p.weightSpec.alpha, p.weightSpec.beta, toColumnWeights(p.weightSpec));

        Assert.assertEquals("TR4.3 size equal", a.size(), b.size());
        Assert.assertEquals("TR4.3 fingerprint same", hybridFingerprint(a), hybridFingerprint(b));
    }

    /* ================ TR4.4 8 参 Q-FILTER a=100 + between c 1-10 ================ */
    @Test
    public void TR4_4_EightParamWeighted_QFilterA100_BetweenC_DslVsHand() throws Exception {
        final String ftQ = "大模型";
        QueryWrapper<AbcEntity> wa = new QueryWrapper<>();
        wa.eq("a", Long.valueOf(100L));
        wa.between("c", Long.valueOf(1L), Long.valueOf(10L));
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = new HashMap<>();
        cw.put("a", new AbstractMap.SimpleEntry<>(0.5, x -> (double) x.a));
        List<HybridHit<AbcEntity>> a = dhqc.searchHybridWithWeights(
                wa, ftQ, rawDocTexts, 15, null, 0.5, 0.5, cw);

        String dsl = "a = 100 AND c BETWEEN 1 AND 10 AND FT('大模型') WEIGHT(0.5,0.5,a=0.5)";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        Assert.assertTrue("TR4.4 条件顺序 DeepEquals", ParsedHybridQuery.deepEquals(
                new ParsedHybridQuery<>(wa, ftQ,
                        new WeightSpec(0.5, 0.5, Collections.singletonMap("a", 0.5)), null), p));
        List<HybridHit<AbcEntity>> b = dhqc.searchHybridWithWeights(
                p.ormCond, p.ftQuery, rawDocTexts, 15, null,
                p.weightSpec.alpha, p.weightSpec.beta, toColumnWeights(p.weightSpec));

        Assert.assertEquals("TR4.4 docId 集 size 相等",
                a.stream().map(h -> h.docId).distinct().count(),
                b.stream().map(h -> h.docId).distinct().count());
        Assert.assertEquals("TR4.4 fingerprint same", hybridFingerprint(a), hybridFingerprint(b));
    }

    /* ================ TR4.5 7 参 searchHybridWithLtr Q-ALL LTR(2 特征) ================ */
    @Test
    public void TR4_5_SevenParamLtr_QAll_DslVsHand_SeparateScoringVsRecall() throws Exception {
        final String ftQ = "现代化";
        QueryWrapper<AbcEntity> wa1 = new QueryWrapper<>();
        wa1.like("name", "%透气%");
        wa1.ge("c", Long.valueOf(1L));
        wa1.le("c", Long.valueOf(30L));
        // 7 参 baseline 手写
        List<ToDoubleFunction<AbcEntity>> fns = new ArrayList<>();
        fns.add(x -> (double) x.a * 0.01);
        fns.add(x -> (double) x.c * 0.02);
        double[] ws = { 0.5, 0.5 };
        List<HybridHit<AbcEntity>> a = dhqc.searchHybridWithLtr(
                wa1, ftQ, rawDocTexts, 20, null, fns, ws);

        // DSL LTR 写法
        String dsl = "name LIKE '%透气%' AND c >= 1 AND c <= 30 AND FT('现代化')"
                + " LTR(a=0.5,c=0.5)";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        LtrSpec<AbcEntity> ls = p.ltrSpec;
        Assert.assertNotNull("TR4.5 ltrSpec not null", ls);
        Assert.assertEquals("TR4.5 2 features", 2, ls.featureExtractors.size());
        List<HybridHit<AbcEntity>> b = dhqc.searchHybridWithLtr(
                p.ormCond, p.ftQuery, rawDocTexts, 20, null,
                new ArrayList<>(ls.featureExtractors), ls.weights.clone());
        Assert.assertEquals("TR4.5 size equal", a.size(), b.size());

        // Scoring vs Recall 分离：TR4.1 5 参 docId 集 same
        QueryWrapper<AbcEntity> wa2 = new QueryWrapper<>();
        wa2.like("name", "%透气%");
        wa2.ge("c", Long.valueOf(1L));
        wa2.le("c", Long.valueOf(30L));
        List<HybridHit<AbcEntity>> baseline = dhqc.searchHybrid(wa2, ftQ, rawDocTexts, 20, null);
        Assert.assertEquals("TR4.5 7 参 vs 5 参 docId 集 size 相等（Scoring 不改 Recall）",
                baseline.stream().map(h -> h.docId).distinct().count(),
                b.stream().map(h -> h.docId).distinct().count());
        Assert.assertEquals("TR4.5 fingerprint same", hybridFingerprint(a), hybridFingerprint(b));
    }

    /* ================ TR4.6 Q-EMPTY 空集 0 异常 ================ */
    @Test
    public void TR4_6_QEmptyDslReturnsZero_ZeroException() throws Exception {
        String dsl = "a = 99999 AND FT('xyz_not_exist')";
        ParsedHybridQuery<AbcEntity> p = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        List<HybridHit<AbcEntity>> h5 = dhqc.searchHybrid(p.ormCond, p.ftQuery, rawDocTexts, 10, null);
        Assert.assertEquals("TR4.6 5 参 Q-EMPTY size 0", 0, h5.size());
        List<HybridHit<AbcEntity>> h8 = dhqc.searchHybridWithWeights(
                p.ormCond, p.ftQuery, rawDocTexts, 10, null, 0.5, 0.5, Collections.emptyMap());
        Assert.assertEquals("TR4.6 8 参 Q-EMPTY size 0", 0, h8.size());
        List<HybridHit<AbcEntity>> h7 = dhqc.searchHybridWithLtr(
                p.ormCond, p.ftQuery, rawDocTexts, 10, null,
                Collections.singletonList(W_MAP.get("a")), new double[]{ 0.0 });
        Assert.assertEquals("TR4.6 7 参 Q-EMPTY size 0", 0, h7.size());
    }
}
