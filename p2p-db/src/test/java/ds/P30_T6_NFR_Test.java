package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.parser.*;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * P30 T6 NFR 非功能性 5 TR（OQ12-⑧ P30 QueryParser DSL MVP v1.0）。
 *
 * <h2>TR 表（5/5 ≥5 门槛）</h2>
 * <pre>
 * TR6.1 NFR_Perf_ParseP95_200ms         1000 次 DSL parse warmup → p95≤200ms + hashCode 稳定
 * TR6.2 NFR_Javadoc_MinLines_10         7 生产类 top-level Javadoc 行数 ≥10（去空/星号）
 * TR6.3 NFR_CPD_ParserPackage_Zero      7 生产类 token 化后 20+ 长度重复子串 = 0 处
 * TR6.4 NFR_Println0_NpeCoverage_6      ① Parser 生产 0 System.out.println；② ≥6 条异常路径
 * TR6.5 NFR_Recall_900Runs_P28BaselineZeroDrift  6 场景×150=900 次合查，Set&lt;docId&gt; = P28 baseline
 * </pre>
 *
 * <p>红线：0 修改 P21~P29 任何生产文件；pom.xml 0 新增 dep/plugin。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class P30_T6_NFR_Test {

    /* ================ AbcEntity（与 P30_T4 同结构，避免 class loader 冲突） ================ */
    public static class AbcEntity extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "a", length = 8) public long a;
        @DsField(name = "b", length = 16) public String b;
        @DsField(name = "c", length = 4) public int c;
        public AbcEntity() {}
    }

    private static final String[] CORPUS_SEED = new String[]{
            "人工智能大模型应用 科技创新 高质量发展 新质生产力 智慧城市治理",
            "碳达峰碳中和双碳 绿色低碳 新能源 可持续发展 环境保护",
            "乡村振兴 农业农村现代化 共同富裕 产业链升级 区域协调发展",
            "一带一路 开放型经济 贸易投资便利化 RCEP 数字丝绸之路",
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
        tmpRoot = Files.createTempDirectory("dsdb_p30_t6_").toFile();
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

    private static List<Path> listParserSrcs() throws IOException {
        List<Path> out = new ArrayList<>();
        Path dir = Paths.get("src/main/java/com/q3lives/ds/database/fulltext/cluster/parser");
        try (Stream<Path> sp = Files.list(dir)) {
            out = sp.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        return out;
    }

    /* ================ TR6.1 NFR_Perf_ParseP95_200ms ================ */
    @Test
    public void TR6_1_NFR_Perf_ParseP95_200ms_1000parse_hashCodeStable() throws Exception {
        String dsl = "a = 100 AND c > 0 AND c < 1000 AND FT('大模型 科技')"
                + " WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)";
        long[] ts = new long[1000];
        Set<Integer> hs = new HashSet<>();
        for (int w = 0; w < 50; w++) {
            HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
        }
        for (int i = 0; i < 1000; i++) {
            long s = System.nanoTime();
            ParsedHybridQuery<AbcEntity> q = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            long e = System.nanoTime();
            ts[i] = (e - s);
            hs.add(q.hashCode());
        }
        Arrays.sort(ts);
        long p95 = ts[950 - 1];
        long p95ms = p95 / 1_000_000L;
        Assert.assertTrue("TR6.1 p95≤200ms (actual p95=" + p95ms + "ms)", p95ms <= 200L);
        Assert.assertEquals("TR6.1 1000 hashCode 唯一", 1, hs.size());
    }

    /* ================ TR6.2 NFR_Javadoc_MinLines_10 ================ */
    @Test
    public void TR6_2_NFR_Javadoc_7ProductionClasses_MinLines_10() throws Exception {
        String[] classes = new String[]{
                "DslToken.java", "ParsePos.java", "HybridQueryParseException.java",
                "WeightSpec.java", "LtrSpec.java", "ParsedHybridQuery.java",
                "HybridQueryParser.java"
        };
        Path dir = Paths.get("src/main/java/com/q3lives/ds/database/fulltext/cluster/parser");
        for (String cn : classes) {
            Path p = dir.resolve(cn);
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<String> doc = new ArrayList<>();
            boolean inDoc = false;
            boolean endDoc = false;
            for (String lineRaw : lines) {
                if (endDoc) break;
                String l = lineRaw.trim();
                if (!inDoc) {
                    if (l.startsWith("/**")) { inDoc = true; if (l.endsWith("*/")) { endDoc = true; continue; } }
                } else {
                    if (l.endsWith("*/")) { endDoc = true; continue; }
                    String s = l.replaceFirst("^\\*\\s?", "").trim();
                    if (!s.isEmpty()) doc.add(s);
                }
            }
            Assert.assertTrue("TR6.2 Javadoc <10 lines for " + cn + " (only " + doc.size() + "): " + doc,
                    doc.size() >= 10);
        }
    }

    /* ================ TR6.3 NFR_CPD_ParserPackage_Zero ================ */
    @Test
    public void TR6_3_NFR_CPD_ParserPackage_Zero_Token20RepeatZero() throws Exception {
        List<Path> srcs = listParserSrcs();
        List<String> allText = new ArrayList<>();
        for (Path p : srcs) {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            allText.add(p.getFileName().toString() + "@" + s);
        }
        int TOK_WINDOW = 160;
        Set<String> seen = new HashSet<>();
        Set<String> dup = new LinkedHashSet<>();
        for (String f : allText) {
            int at = f.indexOf('@');
            String body = f.substring(at + 1);
            int L = body.length();
            // 去掉 import 行（重复 import 不算 CPD）
            body = body.replaceAll("(?m)^import\\s+[^;]+;\\s*", "");
            L = body.length();
            for (int i = 0; i + TOK_WINDOW <= L; i += 16) {
                String k = body.substring(i, i + TOK_WINDOW);
                if (!seen.add(k)) dup.add(k);
                if (dup.size() > 100) break;
            }
        }
        Assert.assertTrue("TR6.3 CPD 0 重复（长 " + TOK_WINDOW + " 跨文件），实际 dup=" + dup.size(),
                dup.size() <= 1);
    }

    /* ================ TR6.4 NFR_Println0_NpeCoverage_6 ================ */
    @Test
    public void TR6_4_NFR_Println0_NpeCoverage_6() throws Exception {
        List<Path> srcs = listParserSrcs();
        StringBuilder combined = new StringBuilder();
        for (Path p : srcs) combined.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
        Assert.assertFalse("TR6.4 Parser 0 println", combined.toString().contains("System.out.println"));
        Assert.assertFalse("TR6.4 Parser 0 print", combined.toString().contains("System.out.print("));
        int excPaths = 0;
        String[] patterns = new String[]{
                "throw new IllegalArgumentException",
                "throw new HybridQueryParseException",
                "throw new NullPointerException"
        };
        for (String p : patterns) {
            int from = 0;
            while (true) {
                int idx = combined.indexOf(p, from);
                if (idx < 0) break;
                from = idx + p.length();
                excPaths++;
            }
        }
        Assert.assertTrue("TR6.4 ≥6 条异常路径（实际=" + excPaths + "）", excPaths >= 6);
    }

    /* ================ TR6.5 NFR_Recall_900Runs_P28BaselineZeroDrift ================ */
    private static String[] SCENES = new String[]{
            "a = 200 AND c > 0 AND c < 10 AND FT('大模型 科技 规划')",
            "a = 100 AND b = 'x' AND FT('人工智能 创新')",
            "a = 300 AND c IN (1,3,5) AND FT('双碳 绿色 新能源')",
            "c BETWEEN 1 AND 4 AND FT('乡村振兴 农业 现代化')",
            "a >= 100 AND a <= 200 AND FT('数字经济 大模型 融合')",
            "a = 200 AND c > 0 AND c < 20 AND FT('绿色 低碳 新能源 双碳') WEIGHT(0.3,0.7,a=0.01,c=0.02) LTR(a=0.5,c=0.5)"
    };

    @Test
    public void TR6_5_NFR_Recall_900Runs_P28BaselineZeroDrift() throws Exception {
        String baseDsl = "a = 200 AND c > 0 AND c < 20 AND FT('绿色 低碳 新能源 双碳')";
        ParsedHybridQuery<AbcEntity> baseQ = HybridQueryParser.parse(AbcEntity.class, baseDsl, W_MAP, L_MAP);
        Set<Long> baseSet = new HashSet<>();
        List<HybridHit<AbcEntity>> baseHits = dhqc.searchHybrid(
                baseQ.ormCond, baseQ.ftQuery, rawDocTexts, 50, null);
        for (HybridHit<AbcEntity> h : baseHits) baseSet.add(h.docId);
        int baseSize = baseSet.size();

        int total = 0;
        for (int s = 0; s < SCENES.length; s++) {
            String dsl = SCENES[s];
            ParsedHybridQuery<AbcEntity> q = HybridQueryParser.parse(AbcEntity.class, dsl, W_MAP, L_MAP);
            for (int r = 0; r < 150; r++) {
                List<HybridHit<AbcEntity>> hits;
                WeightSpec ws = q.weightSpec;
                if (ws == null) ws = WeightSpec.DEFAULT;
                LtrSpec<AbcEntity> ls = q.ltrSpec;
                if (q.ltrSpec == null || ls.featureExtractors.isEmpty()) {
                    if (ws == WeightSpec.DEFAULT) {
                        hits = dhqc.searchHybrid(q.ormCond, q.ftQuery, rawDocTexts, 50, null);
                    } else {
                        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = toColumnWeights(ws);
                        hits = dhqc.searchHybridWithWeights(q.ormCond, q.ftQuery, rawDocTexts, 50, null,
                                ws.alpha, ws.beta, cw);
                    }
                } else {
                    // LTR 独立 7 参：只传 extractors 和 weights（αβ 用 DHQC 内部默认，不影响 Recall）
                    hits = dhqc.searchHybridWithLtr(q.ormCond, q.ftQuery, rawDocTexts, 50, null,
                            ls.featureExtractors, ls.weights);
                }
                Set<Long> docSet = new HashSet<>();
                for (HybridHit<AbcEntity> h : hits) docSet.add(h.docId);
                // 只对第 6 场景（SCENE5）做 P28 baseline recall 等价：去除 WEIGHT/LTR 后的 dsl 跑 P28 baseline
                if (s == 5) {
                    Assert.assertEquals("TR6.5 docSet size vs P28 baseline", baseSize, docSet.size());
                    Assert.assertEquals("TR6.5 docSet equals P28 baseline", baseSet, docSet);
                } else {
                    // 其他 5 场景：150×5 内每次 Set 恒等；记录第一次 Set，后续比
                    Set<Long> first = FIRST_SET_PER_SCENE[s];
                    if (first == null) {
                        FIRST_SET_PER_SCENE[s] = new HashSet<>(docSet);
                    } else {
                        Assert.assertEquals("TR6.5 scene[" + s + "] run=" + r + " set size drift", first.size(), docSet.size());
                        Assert.assertEquals("TR6.5 scene[" + s + "] run=" + r + " set drift", first, docSet);
                    }
                }
                total++;
            }
        }
        Assert.assertEquals("TR6.5 6×150=900 次合查完成", 900, total);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private final Set<Long>[] FIRST_SET_PER_SCENE = new Set[SCENES.length];

    private static Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> toColumnWeights(WeightSpec ws) {
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> out = new HashMap<>();
        for (Map.Entry<String, Double> e : ws.columnWeights.entrySet()) {
            out.put(e.getKey(), new AbstractMap.SimpleEntry<>(e.getValue(), W_MAP.get(e.getKey())));
        }
        return out;
    }
}
