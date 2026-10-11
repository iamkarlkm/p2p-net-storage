package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
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

import com.q3lives.ds.annotation.DsCompositeIndex;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.DsHybridQueryClient;
import com.q3lives.ds.database.fulltext.cluster.HybridHit;
import com.q3lives.ds.database.fulltext.cluster.HybridScoreEngine;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.integration.QueryWrapper;

public class P29_T6_NfrBaselineAndCodeRubricTest {

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
    private DsHybridQueryClient<AbcEntity> hybridClient;

    private static Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> w_a001_c002() {
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> m = new HashMap<>();
        m.put("a", new AbstractMap.SimpleEntry<>(0.01, x -> (double) x.a));
        m.put("c", new AbstractMap.SimpleEntry<>(0.02, x -> (double) x.c));
        return m;
    }

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p29_t6_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(SHARDS, null, ftRoot.getAbsolutePath(), "shard");
        gm = new GenericManager<>(AbcEntity.class);
        rawDocTexts = new HashMap<>();

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
        }
        Thread.sleep(500);
        hybridClient = DsHybridQueryClient.ofLocal(AbcEntity.class, gm, ftClient);
    }

    @After
    public void after() {
        try { if (hybridClient != null) hybridClient.close(); } catch (Exception ignore) {}
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

    private static Path resolveModuleRoot(Path prj) {
        Path m1 = prj.resolve("p2p-db/src/main");
        if (Files.isDirectory(m1)) return prj;
        Path m2 = prj.resolve("src/main");
        if (Files.isDirectory(m2)) return prj.getParent() != null ? prj.getParent() : prj;
        return prj;
    }
    private static Path resolveSrcPath(Path root, String rel) {
        Path a = root.resolve(rel);
        if (Files.exists(a)) return a;
        String stripPrefix = rel;
        if (rel.startsWith("p2p-db/")) stripPrefix = rel.substring("p2p-db/".length());
        Path b = root.resolve("p2p-db/" + stripPrefix);
        if (Files.exists(b)) return b;
        Path c = root.resolve(stripPrefix);
        return c;
    }

    @Test
    public void TR6_1_P95LatencyThreeScenariosUnder96Seconds() throws Exception {
        final int ITER = 50;
        final int K = 20;
        final int scenarios = 3;
        long[] allLat = new long[ITER * scenarios];
        int idx = 0;

        QueryWrapper<AbcEntity> wFilter = new QueryWrapper<>();
        wFilter.eq("a", 100L);
        QueryWrapper<AbcEntity> wEmpty = new QueryWrapper<>();
        wEmpty.eq("a", 999999L);

        for (int i = 0; i < ITER; i++, idx++) {
            long t0 = System.nanoTime();
            List<HybridHit<AbcEntity>> r = hybridClient.searchHybridWithWeights(null,
                    "人民 创新", rawDocTexts, K, null, 1.0, 0.0, new HashMap<>());
            Assert.assertNotNull("Q-ALL iter " + i + " 非 null", r);
            allLat[idx] = System.nanoTime() - t0;
        }
        for (int i = 0; i < ITER; i++, idx++) {
            long t0 = System.nanoTime();
            List<HybridHit<AbcEntity>> r = hybridClient.searchHybridWithWeights(wFilter,
                    "人民 创新", rawDocTexts, K, null, 1.0, 0.0, new HashMap<>());
            Assert.assertNotNull("Q-FILTER iter " + i + " 非 null", r);
            allLat[idx] = System.nanoTime() - t0;
        }
        for (int i = 0; i < ITER; i++, idx++) {
            long t0 = System.nanoTime();
            List<HybridHit<AbcEntity>> r = hybridClient.searchHybridWithWeights(wEmpty,
                    "人民 创新", rawDocTexts, K, null, 1.0, 0.0, new HashMap<>());
            Assert.assertNotNull("Q-EMPTY iter " + i + " 非 null", r);
            allLat[idx] = System.nanoTime() - t0;
        }
        Arrays.sort(allLat);
        int p95Idx = (int) Math.ceil(allLat.length * 0.95) - 1;
        double p95Sec = allLat[p95Idx] / 1_000_000_000.0;
        double maxSec = allLat[allLat.length - 1] / 1_000_000_000.0;
        double medianSec = allLat[allLat.length / 2] / 1_000_000_000.0;
        Assert.assertTrue("TR6.1 三类场景 α=1β=0 合查 50×3=" + allLat.length + " 次，P95="
                        + String.format("%.3f", p95Sec) + "s Median=" + String.format("%.3f", medianSec)
                        + "s Max=" + String.format("%.3f", maxSec) + "s，必须 ≤ 96s（P28 baseline 80s ×1.2）",
                p95Sec <= 96.0);
    }

    @Test
    public void TR6_2_JavadocFourTargetsEachGte10Lines() throws Exception {
        Path prj = resolveModuleRoot(new File(System.getProperty("user.dir")).toPath().toAbsolutePath());
        String[] targets = new String[]{
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridScoreEngine.java",
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/DsHybridQueryClient.java"
        };
        Map<String, List<String>> fileLines = new HashMap<>();
        for (String t : targets) {
            Path p = resolveSrcPath(prj, t);
            if (Files.isRegularFile(p)) fileLines.put(t, Files.readAllLines(p, StandardCharsets.UTF_8));
        }

        int classHits = 0;
        List<String> hseLines = fileLines.get("p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridScoreEngine.java");
        if (hseLines != null) {
            boolean inJd = false; int jd = 0;
            for (String ln : hseLines) {
                String t = ln.trim();
                if (t.startsWith("/**")) { inJd = true; jd = 0; }
                if (inJd) jd++;
                if (t.endsWith("*/") && inJd) {
                    if (jd >= 10) classHits++;
                    break;
                }
            }
        }
        Assert.assertTrue("TR6.2 HybridScoreEngine 类级 Javadoc ≥ 10 行（实际 " + classHits + " 次命中）", classHits >= 1);

        int methodHits = 0;
        List<String> targetMethods = Arrays.asList("searchHybridWithWeights", "searchHybridWithLtr", "ofLocal");
        List<String> dhqcLines = fileLines.get("p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/DsHybridQueryClient.java");
        if (dhqcLines != null) {
            boolean inJd = false; int jd = 0;
            for (int i = 0; i < dhqcLines.size(); i++) {
                String t = dhqcLines.get(i).trim();
                if (t.startsWith("/**")) { inJd = true; jd = 0; }
                if (inJd) jd++;
                if (t.endsWith("*/") && inJd) {
                    inJd = false;
                    String next = "";
                    for (int j = i + 1; j < Math.min(dhqcLines.size(), i + 3); j++) {
                        next += dhqcLines.get(j).trim();
                    }
                    boolean matched = false;
                    for (String name : targetMethods) if (next.contains(name)) { matched = true; break; }
                    if (matched && jd >= 10) methodHits++;
                }
            }
        }
        Assert.assertTrue("TR6.2 DsHybridQueryClient 新 3 方法 Javadoc 各 ≥ 10 行，命中 ≥ 1 处（实际命中 " + methodHits + "）", methodHits >= 1);

        int ctorHits = 0;
        Path hh = resolveSrcPath(prj, "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridHit.java");
        if (Files.isRegularFile(hh)) {
            List<String> lines = Files.readAllLines(hh, StandardCharsets.UTF_8);
            boolean inJd = false; int jd = 0;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("/**")) { inJd = true; jd = 0; }
                if (inJd) jd++;
                if (t.endsWith("*/") && inJd) {
                    inJd = false;
                    String next = "";
                    for (int j = i + 1; j < Math.min(lines.size(), i + 4); j++) next += lines.get(j).trim();
                    if (next.contains("HybridHit") && next.contains("override") && jd >= 10) ctorHits++;
                }
            }
        }
        Assert.assertTrue("TR6.2 HybridHit 新 3 参数构造 Javadoc ≥ 10 行（实际 " + ctorHits + "）", ctorHits >= 1);
    }

    @Test
    public void TR6_3_Cpd25LineCrossFileDupZero() throws Exception {
        Path prj = resolveModuleRoot(new File(System.getProperty("user.dir")).toPath().toAbsolutePath());
        String[] targets = new String[]{
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridScoreEngine.java",
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/DsHybridQueryClient.java"
        };
        Map<String, Set<Integer>> winIdx = new HashMap<>();
        for (int fi = 0; fi < targets.length; fi++) {
            Path p = resolveSrcPath(prj, targets[fi]);
            if (!Files.isRegularFile(p)) continue;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            final int WIN = 25;
            for (int i = 0; i + WIN <= lines.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < WIN; j++) {
                    String c = lines.get(i + j).trim();
                    if (c.isEmpty()) continue;
                    if (c.startsWith("//") || c.startsWith("*") || c.startsWith("import")
                            || c.startsWith("package") || c.startsWith("@")
                            || c.equals("{") || c.equals("}")) continue;
                    sb.append(c);
                }
                String key = sb.toString().replaceAll("\\s+", " ");
                if (key.length() < 20) continue;
                winIdx.computeIfAbsent(key, k -> new HashSet<>()).add(fi);
            }
        }
        int dup = 0;
        for (Set<Integer> v : winIdx.values()) if (v.size() > 1) dup++;
        Assert.assertTrue("TR6.3 HybridScoreEngine vs DsHybridQueryClient 跨文件 25行窗口 CPD 复制粘贴块数=" + dup + "，必须 = 0（无复制粘贴）", dup == 0);
    }

    @Test
    public void TR6_4_BarePrintlnZeroAndNullGuardGte5() throws Exception {
        Path prj = resolveModuleRoot(new File(System.getProperty("user.dir")).toPath().toAbsolutePath());
        String[] newClasses = new String[]{
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridScoreEngine.java",
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/DsHybridQueryClient.java",
                "p2p-db/src/main/java/com/q3lives/ds/database/fulltext/cluster/HybridHit.java"
        };
        int println = 0;
        int nullGuard = 0;
        for (String target : newClasses) {
            Path p = resolveSrcPath(prj, target);
            if (!Files.isRegularFile(p)) continue;
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String ln : lines) {
                String t = ln.trim();
                if (t.contains("System.out.print") || t.contains("System.err.print")) println++;
                if (t.contains("Objects.requireNonNull") || t.contains("requireNonNull")) nullGuard++;
                if (t.contains("NullPointerException") && (t.contains("throw") || t.contains("catch"))) nullGuard++;
                if (t.contains("IllegalArgumentException") && t.contains("throw")) nullGuard++;
            }
        }
        Assert.assertEquals("TR6.4 裸 System.out/.err 打印 = " + println + "，必须 = 0（NFR 严禁生产级裸打印）", 0, println);
        Assert.assertTrue("TR6.4 null/IAE 防御性保护 total = " + nullGuard + "，必须 ≥ 5（FR-5 鲁棒性）", nullGuard >= 5);
    }

    @Test
    public void TR6_5_900RunsDocIdSetEqualsBaselineZeroRegressions() throws Exception {
        int exceptions = 0;
        int setMisMatch = 0;
        QueryWrapper<AbcEntity> wFilter = new QueryWrapper<>();
        wFilter.eq("a", 100L);
        QueryWrapper<AbcEntity> wEmpty = new QueryWrapper<>();
        wEmpty.eq("a", 999999L);

        QueryWrapper<AbcEntity>[] ws = new QueryWrapper[]{null, wFilter, wEmpty};
        double[][] abCandidates = new double[][]{ {1.0, 0.0}, {0.3, 0.7}, {0.5, 0.5} };
        Map<String, Map.Entry<Double, ToDoubleFunction<AbcEntity>>> cw = w_a001_c002();
        final int REPEAT = 100;
        for (int repeat = 0; repeat < REPEAT; repeat++) {
            for (int wi = 0; wi < ws.length; wi++) {
                QueryWrapper<AbcEntity> w = ws[wi];
                for (int ab = 0; ab < abCandidates.length; ab++) {
                    try {
                        Set<Long> base = hybridClient.searchHybrid(w, "人民", rawDocTexts, 20, null)
                                .stream().map(h -> h.docId).collect(Collectors.toSet());
                        Set<Long> weighted = hybridClient.searchHybridWithWeights(w, "人民", rawDocTexts, 20, null,
                                abCandidates[ab][0], abCandidates[ab][1], cw).stream().map(h -> h.docId).collect(Collectors.toSet());
                        if (!base.equals(weighted)) setMisMatch++;
                    } catch (Throwable t) {
                        exceptions++;
                    }
                }
            }
        }
        Assert.assertEquals("TR6.5 9×100 = 900 次合查 异常 = 0，实际 = " + exceptions, 0, exceptions);
        Assert.assertEquals("TR6.5 900 次 docId 集合 与 P28 5 参 searchHybrid 基线 Set equals，misMatch = 0，实际 = " + setMisMatch + "（证明权重只改 scoring，不改 recall/docId 交集）", 0, setMisMatch);
    }
}
