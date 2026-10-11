package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P28_T3_SingleJvmInprocHybridSearchTest {

    @DsCompositeIndex(name = "idx_abc", columns = {"a", "b", "c"})
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

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p28_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");

        gm = new GenericManager<>(AbcEntity.class);
        rawDocTexts = new LinkedHashMap<>();
        entityIds = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            AbcEntity e = new AbcEntity();
            int seed = i % CORPUS_SEED.length;
            String text = CORPUS_SEED[seed] + " doc_" + String.format("%02d", i) + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
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
        Thread.sleep(500);
    }

    @After
    public void after() {
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

    private static String entityFingerprint(AbcEntity e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getId()).append('|');
        sb.append(e.name == null ? "null" : e.name).append('|');
        sb.append(e.a).append('|').append(e.b).append('|').append(e.c);
        return md5(sb.toString());
    }

    private static String hitFingerprint(HybridHit<AbcEntity> h) {
        ScoredDocHit c = h.ftx.core;
        StringBuilder sb = new StringBuilder();
        sb.append(h.docId).append('|');
        sb.append(entityFingerprint(h.entity)).append('|');
        sb.append(String.format("%.12f", c.finalScore)).append('|');
        sb.append(String.format("%.12f", c.sortScore)).append('|');
        sb.append(String.format("%.12f", c.rrfScore)).append('|');
        sb.append(String.format("%.12f", c.phraseBoostScore)).append('|');
        sb.append(c.matchedQueryTerms).append('|');
        sb.append(String.format("%.12f", c.vectorScore));
        return md5(sb.toString());
    }

    private static String hitsFingerprint(List<HybridHit<AbcEntity>> hits) {
        StringBuilder sb = new StringBuilder();
        for (HybridHit<AbcEntity> h : hits) sb.append(hitFingerprint(h)).append(',');
        return md5(sb.toString());
    }

    @Test
    public void tr31_nullBlankFulltextQueryThrowIae() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        try {
            hc.searchHybrid(null, null, rawDocTexts, 20, SearchOptions.defaults());
            Assert.fail("query=null 必须抛 IAE");
        } catch (IllegalArgumentException | NullPointerException ex) {
            Assert.assertTrue("query null 抛异常", true);
        }
        try {
            hc.searchHybrid(null, "   \t  ", rawDocTexts, 20, SearchOptions.defaults());
            Assert.fail("query=blank 必须抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("query blank 抛 IAE", true);
        }
        hc.close();
    }

    @Test
    public void tr32_negativeKNullRawTextsThrowIae() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        try {
            hc.searchHybrid(null, "人民", rawDocTexts, -1, SearchOptions.defaults());
            Assert.fail("K=-1 必须抛 IAE");
        } catch (IllegalArgumentException ex) {
            Assert.assertTrue("K<0 抛 IAE", true);
        }
        try {
            hc.searchHybrid(null, "人民", null, 20, SearchOptions.defaults());
            Assert.fail("rawDocTexts=null 必须抛 IAE/NPE");
        } catch (IllegalArgumentException | NullPointerException ex) {
            Assert.assertTrue("rawDocTexts null 抛异常", true);
        }
        hc.close();
    }

    @Test
    public void tr33_qAllVsHandMadeHybridHitFingerprintEquals() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        final int K = 20;
        List<HybridHit<AbcEntity>> clientHits = hc.searchHybrid(null, "人民", rawDocTexts, K, SearchOptions.defaults());
        Assert.assertNotNull("clientHits 非 null", clientHits);

        List<Long> ormIds = gm.listIds(null);
        Set<Long> ormSet = new HashSet<>(ormIds);
        int ftK = K * 3;
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras("人民", rawDocTexts, ftK, SearchOptions.defaults());
        List<ScoredDocHitWithExtras> filtered = new ArrayList<>();
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormSet.contains(h.core.docId)) continue;
            filtered.add(h);
            if (filtered.size() >= K) break;
        }
        Map<Long, AbcEntity> entMap = new HashMap<>();
        for (ScoredDocHitWithExtras h : filtered) {
            long id = h.core.docId;
            AbcEntity e = gm.getById(id);
            if (e != null) entMap.put(id, e);
        }
        List<HybridHit<AbcEntity>> handMade = new ArrayList<>();
        for (ScoredDocHitWithExtras h : filtered) {
            AbcEntity e = entMap.get(h.core.docId);
            if (e != null) handMade.add(new HybridHit<>(e, h));
        }
        String fpClient = hitsFingerprint(clientHits);
        String fpHand = hitsFingerprint(handMade);
        Set<String> set = new HashSet<>(Arrays.asList(fpClient, fpHand));
        Assert.assertEquals("Q-ALL 合查 与 手算 指纹 Set.size = 1（100% 全等）", 1, set.size());
        hc.close();
    }

    @Test
    public void tr34_qFilterAeq100SizeMax10AndEntityAeq100() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 100L);
        List<HybridHit<AbcEntity>> hits = hc.searchHybrid(w, "人民", rawDocTexts, 20, SearchOptions.defaults());
        Assert.assertNotNull("hits 非 null", hits);
        Assert.assertTrue("合查结果 size ≤ 10（a=100 仅 10 条），实际 size=" + hits.size(), hits.size() <= 10);
        for (HybridHit<AbcEntity> h : hits) {
            Assert.assertEquals("hit id=" + h.docId + " 的 entity.a 必须 = 100，0 误匹配", 100L, h.entity.a);
        }
        hc.close();
    }

    @Test
    public void tr35_qEmptyAeq99999ReturnEmptyList() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        QueryWrapper<AbcEntity> w = new QueryWrapper<>();
        w.eq("a", 99999L);
        List<Long> ormEmptyIds = gm.listIds(w);
        Assert.assertTrue("预条件：gm.listIds(a=99999) 必须为空，实际 size=" + ormEmptyIds.size(),
                ormEmptyIds == null || ormEmptyIds.isEmpty());
        List<HybridHit<AbcEntity>> hits = hc.searchHybrid(w, "人民", rawDocTexts, 20, SearchOptions.defaults());
        Assert.assertNotNull("hits 非 null", hits);
        Assert.assertEquals("空过滤条件合查 size=0，无命中", 0, hits.size());
        hc.close();
    }

    @Test
    public void tr36_orderInheritsFtNoSecondarySort() throws Exception {
        DsHybridQueryClient<AbcEntity> hc = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
        final int K = 20;
        int ftK = K * 3;
        List<Long> ormIds = gm.listIds(null);
        Set<Long> ormSet = new HashSet<>(ormIds);
        List<ScoredDocHitWithExtras> ftAll = ftClient.searchClusterWithExtras("人民", rawDocTexts, ftK, SearchOptions.defaults());
        List<Long> expectedDocIds = new ArrayList<>();
        for (ScoredDocHitWithExtras h : ftAll) {
            if (h == null || h.core == null) continue;
            if (!ormSet.contains(h.core.docId)) continue;
            expectedDocIds.add(h.core.docId);
            if (expectedDocIds.size() >= K) break;
        }
        List<HybridHit<AbcEntity>> actual = hc.searchHybrid(null, "人民", rawDocTexts, K, SearchOptions.defaults());
        Assert.assertEquals("合查结果 size 应相等", expectedDocIds.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            long exp = expectedDocIds.get(i);
            long act = actual.get(i).docId;
            Assert.assertEquals("index " + i + ": 合查顺序 == FT 过滤原顺序（无二次排序扰动），docId 必须全等", exp, act);
        }
        hc.close();
    }
}
