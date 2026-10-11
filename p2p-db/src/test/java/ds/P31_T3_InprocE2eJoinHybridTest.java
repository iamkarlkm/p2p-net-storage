package ds;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P31_T3_InprocE2eJoinHybridTest {

    static class RelT3 extends DsTableAdapter {
        @DsField(name = "main_id", length = 8) public Long mainId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    static class MainT3 extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "ft_text", length = 1024) public String ftText;
        @DsOneToOne(joinProp = "mainId", props = "id") public RelT3 rel;
    }

    private static final String[] CORPUS = new String[]{
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

    private static final int N = 40;
    private static final int SHARDS = 2;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT3> mainGm;
    private GenericManager<RelT3> relGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private List<Long> allMainIds;
    private Map<Long, RelT3> relByMainId;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p31_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");

        mainGm = new GenericManager<>(MainT3.class);
        relGm = new GenericManager<>(RelT3.class);
        relatedGms = new HashMap<>();
        relatedGms.put(RelT3.class, relGm);
        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();
        relByMainId = new HashMap<>();

        for (int i = 0; i < N; i++) {
            MainT3 e = new MainT3();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + String.format("%03d", i)
                    + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
            e.name = "订单商品_" + String.format("%03d", i);
            e.ftText = text;
            mainGm.saveOrUpdate(e);
            long mid = e.getId();
            RelT3 r = new RelT3();
            r.mainId = mid;
            r.price = (double) i;
            r.qty = (long) (i * 3 + 1);
            relGm.saveOrUpdate(r);
            relByMainId.put(mid, r);
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull("putDocCluster stat 非null", st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
        }
        Assert.assertEquals("主表 " + N + " 行", N, allMainIds.size());
        Thread.sleep(500);
    }

    @After
    public void after() {
        try { ftClient.close(); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot); } catch (Exception ignore) {}
        try { DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot); } catch (Exception ignore) {}
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

    @Test(timeout = 180000)
    public void tr3_1_searchHybridJoin_5args_basicAndSubset() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            QueryWrapper<RelT3> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT3> js = new JoinSpec<>(RelT3.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            List<HybridHit<MainT3>> jHits = hjc.searchHybridJoin(orm, joins, "科技 发展", rawDocTexts, 20, SearchOptions.defaults());
            Assert.assertNotNull("join hits 非null", jHits);
            Set<Long> jIds = new HashSet<>();
            for (HybridHit<MainT3> h : jHits) {
                jIds.add(h.docId);
                RelT3 r = (RelT3) h.joinFetch.get(RelT3.class);
                Assert.assertNotNull("joinFetch RelT3 非null (price>10 命中)", r);
                Assert.assertTrue("price>10 100% (" + r.price + ")", r.price > 10.0);
            }
            List<HybridHit<MainT3>> bHits = dhqc.searchHybrid(orm, "科技 发展", rawDocTexts, 20, SearchOptions.defaults());
            Set<Long> bIds = new HashSet<>();
            for (HybridHit<MainT3> h : bHits) bIds.add(h.docId);
            Assert.assertTrue("Join 结果 ⊆ Baseline (SubSet)", bIds.containsAll(jIds));
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void tr3_2_searchHybridJoinWithWeights_8args_scoreEq() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<RelT3> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT3> js = new JoinSpec<>(RelT3.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            Map<String, Map.Entry<Double, ToDoubleFunction<MainT3>>> colW = new HashMap<>();
            ToDoubleFunction<MainT3> qtyFn = e -> {
                RelT3 r = relByMainId.get(e.getId());
                return r != null ? r.qty.doubleValue() : 0.0;
            };
            colW.put("qty", new AbstractMap.SimpleEntry<>(0.2, qtyFn));
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            orm.in("id", new ArrayList<>(allMainIds));
            List<HybridHit<MainT3>> base = dhqc.searchHybridWithWeights(orm, "科技 发展 人工智能", rawDocTexts, 40, SearchOptions.defaults(), 0.7, 0.3, colW);
            Map<Long, Double> baseScore = new HashMap<>();
            for (HybridHit<MainT3> h : base) baseScore.put(h.docId, h.finalHybridScore);
            List<HybridHit<MainT3>> jh = hjc.searchHybridJoinWithWeights(orm, joins, "科技 发展 人工智能", rawDocTexts, 40, SearchOptions.defaults(), 0.7, 0.3, colW);
            for (HybridHit<MainT3> h : jh) {
                Double bs = baseScore.get(h.docId);
                if (bs != null) {
                    double diff = Math.abs(bs - h.finalHybridScore);
                    Assert.assertTrue("交集 docId score diff ≤ 1e-9 (" + diff + ")", diff <= 1e-9);
                }
            }
            long[] jRank = new long[jh.size()];
            for (int i = 0; i < jh.size(); i++) {
                HybridHit<MainT3> h = jh.get(i);
                long s = (h.ftx.core.shardId & 0xFFFFL);
                long did = h.docId & 0x0000FFFFFFFFFFFFL;
                long sc = Double.doubleToLongBits(h.finalHybridScore);
                jRank[i] = (sc << 20) ^ (did << 4) ^ s;
            }
            Set<String> fps = new HashSet<>();
            for (int t = 0; t < 2; t++) {
                List<HybridHit<MainT3>> repeat = hjc.searchHybridJoinWithWeights(orm, joins, "科技 发展 人工智能", rawDocTexts, 40, SearchOptions.defaults(), 0.7, 0.3, colW);
                StringBuilder sb = new StringBuilder();
                for (HybridHit<MainT3> h : repeat) sb.append(h.docId).append('|');
                fps.add(md5(sb.toString()));
            }
            Assert.assertTrue("2 次重跑排序指纹唯一 (稳定)", fps.size() == 1);
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void tr3_3_searchHybridJoinWithLtr_7args_featureReachable() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<RelT3> sub = new QueryWrapper<>();
            sub.gt("price", 10.0);
            JoinSpec<RelT3> js = new JoinSpec<>(RelT3.class, "id", "mainId", sub);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            ToDoubleFunction<MainT3> ePrice = e -> {
                RelT3 r = relByMainId.get(e.getId());
                return r != null ? r.price : 0.0;
            };
            ToDoubleFunction<MainT3> eQty = e -> {
                RelT3 r = relByMainId.get(e.getId());
                return r != null ? r.qty.doubleValue() : 0.0;
            };
            List<ToDoubleFunction<MainT3>> extractors = Arrays.asList(ePrice, eQty);
            double[] ws = new double[]{0.5, 0.5};
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.in("id", new ArrayList<>(allMainIds));
            List<HybridHit<MainT3>> base = dhqc.searchHybridWithLtr(orm, "绿色 低碳 生态 乡村", rawDocTexts, 30, SearchOptions.defaults(), extractors, ws);
            Map<Long, Double> bsMap = new HashMap<>();
            for (HybridHit<MainT3> h : base) bsMap.put(h.docId, h.finalHybridScore);
            List<HybridHit<MainT3>> jh = hjc.searchHybridJoinWithLtr(orm, joins, "绿色 低碳 生态 乡村", rawDocTexts, 30, SearchOptions.defaults(), extractors, ws);
            for (HybridHit<MainT3> h : jh) {
                Double sv = bsMap.get(h.docId);
                if (sv != null) {
                    double diff = Math.abs(sv - h.finalHybridScore);
                    Assert.assertTrue("LTR 交集 score diff ≤1e-9 (" + diff + ")", diff <= 1e-9);
                }
                RelT3 r = (RelT3) h.joinFetch.get(RelT3.class);
                Assert.assertNotNull("joinFetch.RelT3 可读取 price/qty", r);
                Assert.assertTrue("price value reachable: " + r.price, r.price != null && !Double.isNaN(r.price));
                Assert.assertTrue("qty value reachable: " + r.qty, r.qty != null && r.qty >= 0L);
            }
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void tr3_4_reflectJoins_vs_manualJoins_setEq() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            List<JoinSpec<?>> reflectJoins0 = JoinSpec.fromDsOneToOneAnnotations(MainT3.class);
            Assert.assertEquals("反射 JoinSpec 数 ==1", 1, reflectJoins0.size());
            QueryWrapper<RelT3> sub = new QueryWrapper<>();
            sub.gt("price", 5.0);
            List<JoinSpec<?>> reflectJoins = new ArrayList<>();
            for (JoinSpec<?> j0 : reflectJoins0) {
                @SuppressWarnings("unchecked")
                JoinSpec<RelT3> jc = (JoinSpec<RelT3>) j0;
                reflectJoins.add(jc.withRelatedCond(sub));
            }
            JoinSpec<RelT3> mj = new JoinSpec<>(RelT3.class, "id", "mainId", sub);
            List<JoinSpec<?>> manualJoins = Collections.singletonList(mj);
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            List<HybridHit<MainT3>> rHits = hjc.searchHybridJoin(orm, reflectJoins, "现代化 共赢 大模型", rawDocTexts, 30, SearchOptions.defaults());
            List<HybridHit<MainT3>> mHits = hjc.searchHybridJoin(orm, manualJoins, "现代化 共赢 大模型", rawDocTexts, 30, SearchOptions.defaults());
            Set<Long> rIds = new HashSet<>();
            for (HybridHit<MainT3> h : rHits) rIds.add(h.docId);
            Set<Long> mIds = new HashSet<>();
            for (HybridHit<MainT3> h : mHits) mIds.add(h.docId);
            Assert.assertEquals("反射 joins vs 手动 joins: Set equals", mIds, rIds);
            Set<Class<?>> rKeys = new HashSet<>();
            for (HybridHit<MainT3> h : rHits) rKeys.addAll(h.joinFetch.keySet());
            Set<Class<?>> mKeys = new HashSet<>();
            for (HybridHit<MainT3> h : mHits) mKeys.addAll(h.joinFetch.keySet());
            Assert.assertEquals("joinFetch keySet 相等", mKeys, rKeys);
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 180000)
    public void tr3_5_strongFilter_hitsLessThanK_noNpe() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            final int K = 20;
            QueryWrapper<RelT3> strong = new QueryWrapper<>();
            strong.gt("price", 99999.0);
            JoinSpec<RelT3> js = new JoinSpec<>(RelT3.class, "id", "mainId", strong);
            List<JoinSpec<?>> joins = Collections.singletonList(js);
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.in("id", new ArrayList<>(allMainIds));
            List<HybridHit<MainT3>> hits = hjc.searchHybridJoin(orm, joins, "人民 科技 绿色", rawDocTexts, K, SearchOptions.defaults());
            Assert.assertTrue("强过滤后 hits<K 自动截断 (hits=" + hits.size() + " K=" + K + ")", hits.size() < K);
            int itCount = 0;
            for (HybridHit<MainT3> h : hits) {
                Assert.assertNotNull("hit 非null", h);
                Assert.assertNotNull("joinFetch 非null", h.joinFetch);
                for (Map.Entry<Class<?>, Object> en : h.joinFetch.entrySet()) itCount++;
            }
            Assert.assertTrue("joinFetch 遍历不 NPE (count=" + itCount + ")", true);
        } finally {
            hjc.close();
        }
    }

    @Test(timeout = 240000)
    public void tr3_6_tieBreak_3keyComparator_rankFp() throws Exception {
        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        HybridJoinClient<MainT3> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT3> orm = new QueryWrapper<>();
            orm.in("id", new ArrayList<>(allMainIds));
            List<JoinSpec<?>> joins = Collections.singletonList(new JoinSpec<>(RelT3.class, "id", "mainId", null));
            Set<String> fpSet = new HashSet<>();
            for (int t = 0; t < 10; t++) {
                List<HybridHit<MainT3>> hits = hjc.searchHybridJoin(orm, joins, "发展 现代化 绿色 科技", rawDocTexts, 50, SearchOptions.defaults());
                for (int i = 1; i < hits.size(); i++) {
                    HybridHit<MainT3> a = hits.get(i - 1);
                    HybridHit<MainT3> b = hits.get(i);
                    int cmp = Double.compare(b.finalHybridScore, a.finalHybridScore);
                    if (cmp == 0) {
                        cmp = Long.compare(a.docId, b.docId);
                        if (cmp == 0) cmp = Integer.compare(a.ftx.core.shardId, b.ftx.core.shardId);
                    }
                    Assert.assertTrue("三键排序: score↓ docId↑ shardId↑ (cmp=" + cmp + ")", cmp <= 0);
                }
                StringBuilder sb = new StringBuilder();
                for (HybridHit<MainT3> h : hits) sb.append(h.docId).append('|').append(h.ftx.core.shardId).append(',');
                fpSet.add(md5(sb.toString()));
            }
            Assert.assertEquals("10 次重跑排序指纹稳定 (set.size=1)", 1, fpSet.size());
        } finally {
            hjc.close();
        }
    }
}
