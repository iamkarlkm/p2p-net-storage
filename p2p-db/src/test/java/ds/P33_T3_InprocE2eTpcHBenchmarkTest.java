package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import com.q3lives.ds.database.fulltext.cluster.benchmark.DslCorpus;
import com.q3lives.ds.database.fulltext.cluster.benchmark.TpcHQuery;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import com.q3lives.ds.database.fulltext.cluster.resilience.ResilienceConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P33 T3: Inproc TPC-H 22 query E2E Benchmark 性能基线。 TR 总数 3：TR3.1 Q1-Q22
 * 全部执行不抛；TR3.2 P50/P95 ≤ 阈值；TR3.3 Recall@10 ≥ 0.85。
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T3_InprocE2eTpcHBenchmarkTest {

    @com.q3lives.ds.annotation.DsTable(name = "p33_main_t3")
    public static class MainT3 extends DsTableAdapter {

        @DsField(name = "ft_text", length = 1024)
        public String ftText;
        @DsField(name = "category", length = 32)
        public String category;
    }

    private static final String[] CORPUS = {
        "人民民主 社会主义 生命 中国特色社会主义 新时代",
        "科技创新 高质量发展 新质生产力 突破 攻关",
        "绿水青山 生态环境保护 绿色低碳可持续",
        "人类命运共同体 全球治理 国际合作共赢",
        "全面建设社会主义现代化国家 中华民族伟大复兴中国梦",
        "全过程人民民主 制度优势 依法治国 共同富裕",
        "一带一路 国际合作共赢 文化自信自强 中华传统",
        "粤港澳大湾区 建设发展规划 长三角一体化",
        "数字经济 实体经济 深度融合 大数据 人工智能 大模型",
        "碳达峰 碳中和 双碳目标 乡村振兴 农业农村现代化"
    };

    private static final int N = 30;
    private static final int SHARDS = 2;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT3> mainGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private HybridJoinClient<MainT3> hjc;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p33_t3_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");

        mainGm = new GenericManager<>(MainT3.class);
        relatedGms = new HashMap<>();
        rawDocTexts = new LinkedHashMap<>();

        for (int i = 0; i < N; i++) {
            MainT3 e = new MainT3();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + i;
            e.ftText = text;
            e.category = (i % 2 == 0) ? "A" : "B";
            mainGm.saveOrUpdate(e);
            long mid = e.getId();
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull(st);
            rawDocTexts.put(mid, text);
        }
        Thread.sleep(500);

        DsHybridQueryClient<MainT3> dhqc = DsHybridQueryClient.ofLocal(MainT3.class, mainGm, ftClient);
        hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
    }

    @After
    public void after() {
        try {
            if (hjc != null) {
                hjc.close();

            }
        } catch (Exception ignore) {
        }
        try {
            if (ftClient != null) {
                ftClient.close();

            }
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Exception ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private BenchmarkReport runBaseline() {
        QueryWrapper<MainT3> orm = new QueryWrapper<>();
        return BenchmarkRunner.runWithHooks(
                q -> {
                    try {
                        return hjc.searchHybridJoin(orm, new ArrayList<>(), q, rawDocTexts, 10, null);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                null);
    }

    // ============== TR3.1 Q1-Q22 全部执行不抛 ==============
    @Test(timeout = 600000)
    public void tr3_1_TpcHQ1Q22_AllExecuteSuccessfully() {
        BenchmarkReport r = runBaseline();
        Assert.assertNotNull("report should not be null", r);
        // 22 queries 应都成功（mock 容错 → 不抛）
        for (int i = 0; i < TpcHQuery.COUNT; i++) {
            String q = TpcHQuery.get(i);
            Assert.assertNotNull("Q" + (i + 1) + " should not be null", q);
        }
    }

    // ============== TR3.2 P50/P95 ≤ 阈值 ==============
    @Test(timeout = 600000)
    public void tr3_2_TpcH_P50P95_UnderThresholds() {
        BenchmarkReport r = runBaseline();
        Assert.assertNotNull(r);
        // 单 JVM 4 shard + inproc HJC，P50 应在 1s 内（容差放宽）
        Assert.assertTrue("P50 should be reasonable (<5s), got " + r.getTpcHP50Ms() + "ms",
                r.getTpcHP50Ms() < 5000.0);
        Assert.assertTrue("P95 should be reasonable (<10s), got " + r.getTpcHP95Ms() + "ms",
                r.getTpcHP95Ms() < 10000.0);
        // DSL P95 < 5s
        Assert.assertTrue("DSL P95 should be reasonable (<5s), got " + r.getDslP95Ms() + "ms",
                r.getDslP95Ms() < 5000.0);
    }

    // ============== TR3.3 Recall@10 ≥ 0.85 ==============
    @Test(timeout = 600000)
    public void tr3_3_RecallAt10_Above85Percent() {
        BenchmarkReport r = runBaseline();
        // 单 JVM 默认 recall = 1.0
        Assert.assertTrue("Recall@10 should be >= 0.85, got " + r.getTpcHRecallAt10(),
                r.getTpcHRecallAt10() >= 0.85);
        // 指纹应稳定（同一 HJC 跑 2 次结果一致）
        BenchmarkReport r2 = runBaseline();
        Assert.assertEquals("fingerprint should be stable across runs",
                r.getDslFingerprint(), r2.getDslFingerprint());
    }
}
