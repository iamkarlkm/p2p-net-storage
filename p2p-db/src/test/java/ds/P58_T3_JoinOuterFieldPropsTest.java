package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
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

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinIdResolver;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.fulltext.cluster.join.v2.HybridJoinClientV2Adapter;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinIdResolverV2;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

/**
 * P58 T3：P2 Feature#3 验收 —— 补齐 {@code JoinSpec.props()} 非主键 outerField，实现真正的
 * ORM×FT 多表 Join（主表列等值语义 {@code main.outerField == related.relatedField}，不再强制 outerField="id"）。
 *
 * <p>覆盖 6 TR：
 * <ol>
 * <li>TR58.1 反射工厂 {@code fromDsOneToOneAnnotations(props=非id)} → outerField 正确 + 非法 outerField Fail-First</li>
 * <li>TR58.2 {@code JoinSpec.readOuterValue} 单元：id/空/null→主键；非 id→列值；缺失/非 Long→IAE</li>
 * <li>TR58.3 {@code JoinIdResolver.resolve} 非主键分支 → 命中的主表 PK 子集</li>
 * <li>TR58.4 端到端 ORM×FT：{@code HybridJoinClient.searchHybridJoin} 非主键 outerField → joinFetch 正确关联</li>
 * <li>TR58.5 V2 N:1：{@code HybridJoinClientV2Adapter} 非主键 outerField → ManyToOneJoinResult 按列值聚合</li>
 * <li>TR58.6 树级联 J=2 非主键 outerField → IAE（R1.PK == R2.relatedField 主键链语义收紧）</li>
 * </ol>
 *
 * @since P58
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class P58_T3_JoinOuterFieldPropsTest {

    @DsTable(name = "p58_t3_rel")
    public static class RelT58 extends DsTableAdapter {
        @DsField(name = "dept_id", length = 8) public Long deptId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    @DsTable(name = "p58_t3_main")
    public static class MainT58 extends DsTableAdapter {
        @DsField(name = "dept_id", length = 8) public Long deptId;
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "ft_text", length = 1024) public String ftText;
        @DsOneToOne(joinProp = "deptId", props = "deptId") public RelT58 rel;
    }

    /** 反射校验：props 指向不存在的列。 */
    @DsTable(name = "p58_t3_main_bad1")
    static class MainBad1 extends DsTableAdapter {
        @DsField(name = "name", length = 32) public String name;
        @DsOneToOne(joinProp = "deptId", props = "noSuchCol") public RelT58 rel;
    }

    /** 反射校验：props 指向非 Long 列。 */
    @DsTable(name = "p58_t3_main_bad2")
    static class MainBad2 extends DsTableAdapter {
        @DsField(name = "tag", length = 32) public String tag;
        @DsOneToOne(joinProp = "deptId", props = "tag") public RelT58 rel;
    }

    private static final String[] CORPUS = new String[]{
            "人民民主是社会主义的生命 中国特色社会主义进入新时代",
            "科技创新推动高质量发展 新质生产力 突破 攻关",
            "绿水青山就是金山银山 生态环境保护 绿色低碳可持续",
            "人类命运共同体理念 全球治理 国际合作共赢",
            "全面建设社会主义现代化国家 中华民族伟大复兴中国梦",
            "全过程人民民主的制度优势 依法治国 共同富裕"
    };

    private static final int N = 40;
    private static final int SHARDS = 2;
    private static final int K = 20;

    private File tmpRoot;
    private String oldHome;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT58> mainGm;
    private GenericManager<RelT58> relGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private Map<Long, Long> deptByMainId;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p58_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        File ftRoot = new File(tmpRoot, "ft_p58_t3");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");

        mainGm = new GenericManager<>(MainT58.class);
        relGm = new GenericManager<>(RelT58.class);
        relatedGms = new HashMap<>();
        relatedGms.put(RelT58.class, relGm);
        rawDocTexts = new LinkedHashMap<>();
        deptByMainId = new HashMap<>();

        // 主表 deptId = i % 10（非主键关联列，多行共享同一 deptId）
        for (int i = 0; i < N; i++) {
            MainT58 e = new MainT58();
            e.deptId = (long) (i % 10);
            e.name = "订单商品_" + String.format("%03d", i);
            e.ftText = CORPUS[i % CORPUS.length] + " doc_" + String.format("%03d", i)
                    + " 补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴 发展";
            mainGm.saveOrUpdate(e);
            long mid = e.getId();
            deptByMainId.put(mid, e.deptId);
            IngestStat st = ftClient.putDocCluster(mid, e.ftText);
            Assert.assertNotNull("putDocCluster stat 非 null", st);
            rawDocTexts.put(mid, e.ftText);
        }
        // 从表仅覆盖偶数 deptId {0,2,4,6,8}，奇数 deptId 无关联 → Join 应过滤掉
        for (long dept = 0; dept < 10; dept += 2) {
            RelT58 r = new RelT58();
            r.deptId = dept;
            r.price = dept * 10.0 + 1.0;
            r.qty = dept;
            relGm.saveOrUpdate(r);
        }
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

    // ============== TR58.1 反射工厂非主键 outerField ==============
    @Test
    public void tr58_1_fromDsOneToOneAnnotations_nonPkOuterField() {
        List<JoinSpec<?>> specs = JoinSpec.fromDsOneToOneAnnotations(MainT58.class);
        Assert.assertEquals("1 个 @DsOneToOne 字段", 1, specs.size());
        JoinSpec<?> j = specs.get(0);
        Assert.assertSame(RelT58.class, j.relatedClass);
        Assert.assertEquals("outerField 取 props 非 id 列", "deptId", j.outerField);
        Assert.assertEquals("relatedField 取 joinProp", "deptId", j.relatedField);

        try {
            JoinSpec.fromDsOneToOneAnnotations(MainBad1.class);
            Assert.fail("props 指向不存在列必须 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message 含 noSuchCol: " + e.getMessage(), e.getMessage().contains("noSuchCol"));
        }
        try {
            JoinSpec.fromDsOneToOneAnnotations(MainBad2.class);
            Assert.fail("props 指向非 Long 列必须 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message 含 must be Long: " + e.getMessage(), e.getMessage().contains("must be Long"));
        }
    }

    // ============== TR58.2 readOuterValue 单元 ==============
    @Test
    public void tr58_2_readOuterValue_unit() {
        MainT58 m = new MainT58();
        m.setId(42L);
        m.deptId = 7L;
        Assert.assertEquals(Long.valueOf(42L), JoinSpec.readOuterValue(m, "id"));
        Assert.assertEquals("空串 → 主键", Long.valueOf(42L), JoinSpec.readOuterValue(m, ""));
        Assert.assertEquals("null → 主键", Long.valueOf(42L), JoinSpec.readOuterValue(m, null));
        Assert.assertEquals("非主键列值", Long.valueOf(7L), JoinSpec.readOuterValue(m, "deptId"));
        Assert.assertNull("mainRow null → null", JoinSpec.readOuterValue(null, "deptId"));

        try {
            JoinSpec.readOuterValue(m, "noSuchField");
            Assert.fail("outerField 缺失必须 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("noSuchField"));
        }
        try {
            JoinSpec.readOuterValue(m, "name");
            Assert.fail("outerField 非 Long 必须 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("must be Long"));
        }
    }

    // ============== TR58.3 JoinIdResolver 非主键分支 ==============
    @Test
    public void tr58_3_joinIdResolver_nonPkOuterField() {
        GenericManager<MainT58> mg = new GenericManager<>(MainT58.class);
        GenericManager<RelT58> rg = new GenericManager<>(RelT58.class);
        Map<Class<?>, GenericManager<?>> rels = new HashMap<>();
        rels.put(RelT58.class, rg);

        // name 前缀 "m" 与 @Before 的 "订单商品_xxx" 隔离，保证本 TR 仅作用于自建 100 行
        for (int i = 0; i < 100; i++) {
            MainT58 e = new MainT58();
            e.deptId = (long) (i % 10);
            e.name = "m" + i;
            mg.saveOrUpdate(e);
        }
        for (long dept = 0; dept < 10; dept += 2) {
            RelT58 r = new RelT58();
            r.deptId = dept;
            r.price = dept * 10.0;
            r.qty = dept;
            rg.saveOrUpdate(r);
        }

        // extractMatched 的 v != 0 守卫 → matched = {2,4,6,8}（deptId=0 被排除）
        // 主表 100 行 deptId = i%10 → 命中 4 个 deptId × 10 行 = 40
        QueryWrapper<MainT58> cond = new QueryWrapper<>();
        cond.likeLeft("name", "m");

        JoinSpec<RelT58> j = new JoinSpec<>(RelT58.class, "deptId", "deptId", null);
        Set<Long> res = JoinIdResolver.resolve(mg, cond, Collections.singletonList(j), rels, new HashMap<>());
        Assert.assertEquals("deptId∈{2,4,6,8} 命中 40 行", 40, res.size());

        Set<Long> zeroDeptIds = new HashSet<>();
        for (MainT58 e : (List<MainT58>) (List) mg.list(new QueryWrapper<>())) {
            if (e.name == null || !e.name.startsWith("m")) continue;
            if (e.deptId == null) continue;
            if (e.deptId == 0L) {
                zeroDeptIds.add(e.getId());
            } else if (e.deptId % 2 == 0L) {
                Assert.assertTrue("偶数非零 deptId 必须命中: deptId=" + e.deptId, res.contains(e.getId()));
            } else {
                Assert.assertFalse("奇数 deptId 不得命中: deptId=" + e.deptId, res.contains(e.getId()));
            }
        }
        Assert.assertTrue("存在 deptId==0 行以验证排除语义", !zeroDeptIds.isEmpty());
        for (Long zid : zeroDeptIds) {
            Assert.assertFalse("deptId==0 不在命中集（extractMatched v!=0 守卫）", res.contains(zid));
        }
    }

    // ============== TR58.4 端到端 ORM×FT 非主键 Join ==============
    @Test(timeout = 180000)
    public void tr58_4_e2e_searchHybridJoin_nonPkOuterField() throws Exception {
        DsHybridQueryClient<MainT58> dhqc = DsHybridQueryClient.ofLocal(MainT58.class, mainGm, ftClient);
        HybridJoinClient<MainT58> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT58> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            JoinSpec<RelT58> js = new JoinSpec<>(RelT58.class, "deptId", "deptId", null);
            List<JoinSpec<?>> joins = Collections.singletonList(js);

            List<HybridHit<MainT58>> jHits = hjc.searchHybridJoin(
                    orm, joins, "科技 发展", rawDocTexts, K, SearchOptions.defaults());
            Assert.assertNotNull("join hits 非 null", jHits);

            for (HybridHit<MainT58> h : jHits) {
                Long dept = deptByMainId.get(h.docId);
                Assert.assertNotNull("命中主表已知 deptId", dept);
                Assert.assertEquals("非主键 Join 只保留偶数 deptId", 0L, dept % 2);
                RelT58 r = (RelT58) h.joinFetch.get(RelT58.class);
                Assert.assertNotNull("joinFetch RelT58 非 null（非主键 outerField 关联）", r);
                Assert.assertEquals("joinFetch.deptId == main.deptId", dept, r.deptId);
                Assert.assertEquals("deptId == outerField 列值", dept, JoinSpec.readOuterValue(h.entity, "deptId"));
            }

            List<HybridHit<MainT58>> baseline = dhqc.searchHybrid(
                    orm, "科技 发展", rawDocTexts, K, SearchOptions.defaults());
            Set<Long> bIds = new HashSet<>();
            for (HybridHit<MainT58> h : baseline) bIds.add(h.docId);
            Set<Long> jIds = new HashSet<>();
            for (HybridHit<MainT58> h : jHits) jIds.add(h.docId);
            Assert.assertTrue("Join 结果 ⊆ Baseline (子集)", bIds.containsAll(jIds));
            Assert.assertTrue("非主键 Join 后候选非空", !jIds.isEmpty());
        } finally {
            hjc.close();
        }
    }

    // ============== TR58.5 V2 N:1 非主键 outerField ==============
    @Test(timeout = 180000)
    public void tr58_5_v2_manyToOne_nonPkOuterField() throws Exception {
        DsHybridQueryClient<MainT58> dhqc = DsHybridQueryClient.ofLocal(MainT58.class, mainGm, ftClient);
        HybridJoinClient<MainT58> hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        try {
            QueryWrapper<MainT58> orm = new QueryWrapper<>();
            orm.likeLeft("name", "订单商品");
            JoinSpec<RelT58> js = new JoinSpec<>(RelT58.class, "deptId", "deptId", null,
                    JoinMode.MANY_TO_ONE, 256);

            List<HybridHit<MainT58>> hits = HybridJoinClientV2Adapter.searchHybridJoinWithManyToOne(
                    hjc, orm, js, "科技 发展", rawDocTexts, K, SearchOptions.defaults(), mainGm, relatedGms);
            Assert.assertNotNull(hits);
            Assert.assertTrue("N:1 非主键 Join 候选非空", !hits.isEmpty());
            for (HybridHit<MainT58> h : hits) {
                Object o = h.joinFetch.get(RelT58.class);
                Assert.assertNotNull("joinFetch 携 ManyToOneJoinResult", o);
                ManyToOneJoinResult m = (ManyToOneJoinResult) o;
                Long key = JoinSpec.readOuterValue(h.entity, "deptId");
                Assert.assertTrue("matchedOuterIds 含 main.deptId", m.matchedOuterIds.contains(key));
                Assert.assertNotNull("byOuterId 命中 key=" + key, m.byOuterId.get(key));
            }
        } finally {
            hjc.close();
        }
    }

    // ============== TR58.6 树级联非主键 outerField 拒绝 ==============
    @Test
    public void tr58_6_tree_rejects_nonPkOuterField() {
        JoinSpec<RelT58> j1 = new JoinSpec<>(RelT58.class, "deptId", "deptId", null,
                JoinMode.MANY_TO_ONE, 256);
        JoinSpec<RelT58> j2 = new JoinSpec<>(RelT58.class, "id", "deptId", null,
                JoinMode.MANY_TO_ONE, 256);
        try {
            JoinIdResolverV2.resolveManyToOneTree(mainGm, null, j1, j2, relatedGms);
            Assert.fail("J=2 树级联 outerField 非 \"id\" 必须 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("message 含 outerField: " + e.getMessage(), e.getMessage().contains("outerField"));
        }
        // 主键链（join1="id"）→ 校验通过后返回集合（不抛 IAE）
        List<Long> ids = new ArrayList<>();
        Set<Long> res = JoinIdResolverV2.resolveManyToOneTree(
                mainGm, null,
                new JoinSpec<>(RelT58.class, "id", "deptId", null, JoinMode.MANY_TO_ONE, 256),
                new JoinSpec<>(RelT58.class, "id", "deptId", null, JoinMode.MANY_TO_ONE, 256),
                relatedGms);
        Assert.assertNotNull(res);
        ids.addAll(res);
    }
}
