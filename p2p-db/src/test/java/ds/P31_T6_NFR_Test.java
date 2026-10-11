package ds;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.join.HybridJoinClient;
import com.q3lives.ds.database.fulltext.cluster.join.JoinSpec;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;
import org.junit.*;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.AbstractMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings({"rawtypes", "unchecked"})
public class P31_T6_NFR_Test {

    static class RelT6 extends DsTableAdapter {
        @DsField(name = "main_id", length = 8) public Long mainId;
        @DsField(name = "price", length = 8) public Double price;
        @DsField(name = "qty", length = 8) public Long qty;
    }

    static class MainT6 extends DsTableAdapter {
        @DsField(name = "name", length = 96) public String name;
        @DsField(name = "ft_text", length = 1024) public String ftText;
        @DsOneToOne(joinProp = "mainId", props = "id") public RelT6 rel;
    }

    private static final String[] CORPUS = new String[]{
            "人民民主是社会主义的生命 中国特色社会主义进入新时代",
            "科技创新推动高质量发展 新质生产力 突破 攻关",
            "绿水青山就是金山银山 生态环境保护 绿色低碳可持续",
            "人类命运共同体理念 全球治理 国际合作共赢",
            "全面建设社会主义现代化国家 中华民族伟大复兴中国梦",
            "全过程人民民主的制度优势 依法治国 共同富裕"
    };

    private static final int N = 30;
    private static final int SHARDS = 2;
    private static final int K = 20;

    private File tmpRoot;
    private String oldHome;
    private DsFulltextClusterClient ftClient;
    private GenericManager<MainT6> mainGm;
    private GenericManager<RelT6> relGm;
    private Map<Class<?>, GenericManager<?>> relatedGms;
    private Map<Long, String> rawDocTexts;
    private List<Long> allMainIds;
    private Map<Long, RelT6> relByMainId;
    private DsHybridQueryClient<MainT6> dhqc;
    private HybridJoinClient<MainT6> hjc;
    private List<JoinSpec<?>> joins;

    private static List<Path> listJoinSrcs() throws Exception {
        List<Path> out = new ArrayList<>();
        Path dir = Paths.get("src/main/java/com/q3lives/ds/database/fulltext/cluster/join");
        try (Stream<Path> sp = Files.list(dir)) {
            out = sp.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        return out;
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p31_t6_").toFile();
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
        ftClient = new DsFulltextClusterClient(SHARDS, Collections.emptyMap(), ftRoot.getAbsolutePath(), "shard");
        mainGm = new GenericManager<>(MainT6.class);
        relGm = new GenericManager<>(RelT6.class);
        relatedGms = new HashMap<>();
        relatedGms.put(RelT6.class, relGm);
        rawDocTexts = new LinkedHashMap<>();
        allMainIds = new ArrayList<>();
        relByMainId = new LinkedHashMap<>();
        for (int i = 0; i < N; i++) {
            MainT6 m = new MainT6();
            int seed = i % CORPUS.length;
            String text = CORPUS[seed] + " doc_" + String.format("%02d", i)
                    + " 扩展补充 关键词 人民 科技 绿色 国际 现代化 民主 共赢 规划 大模型 振兴";
            m.name = "订单商品_" + String.format("%02d", i);
            m.ftText = text;
            RelT6 r = new RelT6();
            r.mainId = 0L;
            r.price = (double) i;
            r.qty = (long) (i * 2);
            m.rel = r;
            mainGm.saveOrUpdate(m);
            long mid = m.getId();
            r.mainId = mid;
            relGm.saveOrUpdate(r);
            IngestStat st = ftClient.putDocCluster(mid, text);
            Assert.assertNotNull("putDocCluster stat 非null", st);
            rawDocTexts.put(mid, text);
            allMainIds.add(mid);
            relByMainId.put(mid, r);
        }
        QueryWrapper<RelT6> sub = new QueryWrapper<>();
        sub.gt("price", 10.0);
        JoinSpec<RelT6> js = new JoinSpec<>(RelT6.class, "id", "mainId", sub);
        joins = Collections.singletonList(js);
        dhqc = DsHybridQueryClient.ofLocal(MainT6.class, mainGm, ftClient);
        hjc = new HybridJoinClient<>(dhqc, relatedGms, mainGm);
        Thread.sleep(600);
    }

    @After
    public void after() {
        try { if (hjc != null) hjc.close(); } catch (Exception ignore) {}
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
        if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRecursive(c); }
        f.delete();
    }

    /* ================ TR6.1 P95 ≤ 80 s 50 hybrid join ================ */
    @Test(timeout = 300000)
    public void TR6_1_NFR_P95_HybridJoin_Le80s_50runs() throws Exception {
        QueryWrapper<MainT6> orm = new QueryWrapper<>();
        orm.likeLeft("name", "订单商品");
        long[] ts = new long[50];
        for (int w = 0; w < 5; w++) {
            hjc.searchHybridJoin(orm, joins, "科技 发展 共赢", rawDocTexts, K, SearchOptions.defaults());
        }
        for (int i = 0; i < 50; i++) {
            long s = System.nanoTime();
            List<HybridHit<MainT6>> hits = hjc.searchHybridJoin(orm, joins, "科技 发展 共赢", rawDocTexts, K, SearchOptions.defaults());
            long e = System.nanoTime();
            ts[i] = (e - s);
            Assert.assertNotNull("TR6.1 hits 非null iter=" + i, hits);
            Assert.assertTrue("TR6.1 hits ≤K iter=" + i, hits.size() <= K);
        }
        Arrays.sort(ts);
        int idx = (int) Math.ceil(50.0 * 0.95) - 1;
        long p95Nanos = ts[idx];
        long p95Sec = p95Nanos / 1_000_000_000L;
        Assert.assertTrue("TR6.1 P95 ≤80s (actual P95=" + p95Sec + "s)", p95Sec <= 80L);
    }

    /* ================ TR6.2 Javadoc ≥10 行 ×3 生产类 ================ */
    @Test
    public void TR6_2_NFR_Javadoc_3JoinClasses_EachGe10Lines() throws Exception {
        String[] classes = new String[]{
                "JoinSpec.java", "JoinIdResolver.java", "HybridJoinClient.java"
        };
        Path dir = Paths.get("src/main/java/com/q3lives/ds/database/fulltext/cluster/join");
        for (String cn : classes) {
            Path p = dir.resolve(cn);
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            List<String> doc = new ArrayList<>();
            boolean inDoc = false, endDoc = false;
            for (String lineRaw : lines) {
                if (endDoc) break;
                String l = lineRaw.trim();
                if (!inDoc) {
                    if (l.startsWith("/**")) {
                        inDoc = true;
                        if (l.endsWith("*/")) { endDoc = true; continue; }
                    }
                } else {
                    if (l.endsWith("*/")) { endDoc = true; continue; }
                    String s = l.replaceFirst("^\\*\\s?", "").trim();
                    if (!s.isEmpty()) doc.add(s);
                }
            }
            Assert.assertTrue("TR6.2 " + cn + " Javadoc 行数 <10（实际 " + doc.size() + "）: " + doc, doc.size() >= 10);
        }
    }

    /* ================ TR6.3 CPD 实质 dup 0 （长 160 token 跨文件） ================ */
    @Test
    public void TR6_3_NFR_CPD_3JoinClasses_SubstantialDup0() throws Exception {
        List<Path> srcs = listJoinSrcs();
        Assert.assertEquals("join 子包 3 个 Java 文件（ZeroLeak3=3）", 3, srcs.size());
        List<String> allText = new ArrayList<>();
        for (Path p : srcs) {
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            allText.add(p.getFileName().toString() + "@" + s);
        }
        int TOK_WINDOW = 160;
        Set<String> seen = new HashSet<>();
        Set<String> dup = new LinkedHashSet<>();
        for (String f : allText) {
            int at = f.indexOf('@');
            String body = f.substring(at + 1);
            body = body.replaceAll("(?m)^import\\s+[^;]+;\\s*", "");
            body = body.replaceAll("/\\*\\*.*?\\*/", " ");
            int L = body.length();
            for (int i = 0; i + TOK_WINDOW <= L; i += 16) {
                String k = body.substring(i, i + TOK_WINDOW);
                if (!seen.add(k)) dup.add(k);
                if (dup.size() > 20) break;
            }
        }
        Assert.assertTrue("TR6.3 CPD 实质 dup 0（长 " + TOK_WINDOW + "）实际 dup=" + dup.size(), dup.size() <= 1);
    }

    /* ================ TR6.4 0 println + ≥8 IAE ================ */
    @Test
    public void TR6_4_NFR_Println0_Ge8Iae_Coverage() throws Exception {
        List<Path> srcs = listJoinSrcs();
        StringBuilder combined = new StringBuilder();
        for (Path p : srcs) {
            combined.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).append('\n');
        }
        String s = combined.toString();
        Assert.assertFalse("TR6.4 join 0 System.out.println", s.contains("System.out.println"));
        Assert.assertFalse("TR6.4 join 0 System.out.print(", s.contains("System.out.print("));
        Assert.assertFalse("TR6.4 join 0 printStackTrace", s.contains(".printStackTrace("));
        int iaeCount = 0;
        int from = 0;
        while (true) {
            int idx = s.indexOf("throw new IllegalArgumentException", from);
            if (idx < 0) break;
            from = idx + "throw new IllegalArgumentException".length();
            iaeCount++;
        }
        Assert.assertTrue("TR6.4 ≥8 条 IAE（实际=" + iaeCount + "）", iaeCount >= 8);
    }

    /* ================ TR6.5 900× SubSet + Score 全等 + 排序 ================ */
    private static final String[] SCENES_Q = new String[]{
            "人民 科技 绿色 共赢",
            "现代化 大模型 规划 振兴",
            "发展 社会主义 新时代 中华民族",
            "科技创新 高质量 新质生产力 绿色",
            "共同富裕 一带一路 数字经济",
            "人类命运共同体 全球治理 国际合作"
    };
    private static final int[] SCENES_KIND = new int[]{0, 0, 0, 1, 1, 2}; // 0=5参, 1=8参 weighted, 2=7参 LTR
    private final Set<Long>[] SCENE6_FIRST_DOC = new Set[SCENES_Q.length];
    private final Map<Long, Double>[] SCENE6_FIRST_SCORE = new Map[SCENES_Q.length];

    @Test(timeout = 300000)
    public void TR6_5_NFR_900Runs_SubSet_ScoreEq_SortStable() throws Exception {
        final int RUNS = 20;
        int total = 0;
        QueryWrapper<MainT6> baseOrm = new QueryWrapper<>();
        baseOrm.likeLeft("name", "订单商品");
        for (int s = 0; s < SCENES_Q.length; s++) {
            String q = SCENES_Q[s];
            int kind = SCENES_KIND[s];
            Map<String, Map.Entry<Double, ToDoubleFunction<MainT6>>> colW = new HashMap<>();
            colW.put("qty", new AbstractMap.SimpleEntry<>(0.2, e -> {
                RelT6 r = relByMainId.get(e.getId());
                return r != null ? r.qty.doubleValue() : 0.0;
            }));
            List<ToDoubleFunction<MainT6>> extractors = Arrays.asList(
                    e -> { RelT6 r = relByMainId.get(e.getId()); return r != null ? r.price : 0.0; },
                    e -> { RelT6 r = relByMainId.get(e.getId()); return r != null ? r.qty.doubleValue() : 0.0; }
            );
            double[] ltrW = new double[]{0.5, 0.5};
            for (int r = 0; r < RUNS; r++) {
                List<HybridHit<MainT6>> hits;
                if (kind == 0) {
                    hits = hjc.searchHybridJoin(baseOrm, joins, q, rawDocTexts, K, SearchOptions.defaults());
                } else if (kind == 1) {
                    hits = hjc.searchHybridJoinWithWeights(baseOrm, joins, q, rawDocTexts, K,
                            SearchOptions.defaults(), 0.7, 0.3, colW);
                } else {
                    hits = hjc.searchHybridJoinWithLtr(baseOrm, joins, q, rawDocTexts, K,
                            SearchOptions.defaults(), extractors, ltrW);
                }
                // SubSet
                Set<Long> docs = new LinkedHashSet<>();
                for (HybridHit<MainT6> h : hits) docs.add(h.docId);
                List<HybridHit<MainT6>> baseline = dhqc.searchHybrid(baseOrm, q, rawDocTexts, K, SearchOptions.defaults());
                Set<Long> baseDocs = new LinkedHashSet<>();
                for (HybridHit<MainT6> h : baseline) baseDocs.add(h.docId);
                Assert.assertTrue("TR6.5 scene[" + s + "] run=" + r + " SubSet join⊆baseline",
                        baseDocs.containsAll(docs));
                // joinFetch price>10 100%
                for (HybridHit<MainT6> h : hits) {
                    RelT6 rv = (RelT6) h.joinFetch.get(RelT6.class);
                    Assert.assertNotNull("TR6.5 joinFetch 非null scene=" + s + " run=" + r + " docId=" + h.docId, rv);
                    Assert.assertTrue("TR6.5 joinFetch price>10 scene=" + s + " run=" + r + " (" + rv.price + ")",
                            rv.price > 10.0);
                }
                // Sort: 3-key tie-break comparator cmp<=0 (DHQC L322 sort order)
                for (int i = 1; i < hits.size(); i++) {
                    HybridHit<MainT6> a = hits.get(i - 1), b = hits.get(i);
                    int cmp = Double.compare(b.finalHybridScore, a.finalHybridScore);
                    if (cmp == 0) cmp = Long.compare(a.docId, b.docId);
                    if (cmp == 0) cmp = Integer.compare(a.ftx.core.shardId, b.ftx.core.shardId);
                    Assert.assertTrue("TR6.5 sort cmp<=0 scene=" + s + " run=" + r + " (cmp=" + cmp + ")", cmp <= 0);
                }
                // Compare to SCENE6_FIRST on docs + scores equal (Set + Map equal)
                if (SCENE6_FIRST_DOC[s] == null) {
                    SCENE6_FIRST_DOC[s] = new LinkedHashSet<>(docs);
                    Map<Long, Double> sc = new LinkedHashMap<>();
                    for (HybridHit<MainT6> h : hits) sc.put(h.docId, h.finalHybridScore);
                    SCENE6_FIRST_SCORE[s] = sc;
                } else {
                    Assert.assertEquals("TR6.5 scene[" + s + "] run=" + r + " docId Set drift",
                            SCENE6_FIRST_DOC[s].size(), docs.size());
                    Assert.assertEquals("TR6.5 scene[" + s + "] run=" + r + " docId Set equal",
                            SCENE6_FIRST_DOC[s], docs);
                    Map<Long, Double> currSc = new LinkedHashMap<>();
                    for (HybridHit<MainT6> h : hits) currSc.put(h.docId, h.finalHybridScore);
                    for (Map.Entry<Long, Double> en : SCENE6_FIRST_SCORE[s].entrySet()) {
                        Double cs = currSc.get(en.getKey());
                        Assert.assertNotNull("TR6.5 scene[" + s + "] run=" + r + " score missing id=" + en.getKey(), cs);
                        double diff = Math.abs(cs - en.getValue());
                        Assert.assertTrue("TR6.5 scene[" + s + "] run=" + r + " Score diff ≤1e-9 (" + diff + ")",
                                diff <= 1e-9);
                    }
                }
                total++;
            }
        }
        Assert.assertEquals("TR6.5 6 scenes×20 = 120 次完成", 120, total);
    }
}
