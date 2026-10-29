package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P45 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 纯 ORM 路径 {@code fetch} 均值 ≤ {@link GenericManagerRowSource} +5%（性能无退化，
 * 采样统计量取均值以规避 GC/page-cache 抖动，逐样本 P95 一并打印）； TR6.2
 * {@code FulltextAggRowSource} Javadoc ≥10 行；TR6.3 CPD 160 字符窗口 0 复制块； TR6.4 0
 * {@code println}/{@code printStackTrace} + IAE ≥5；TR6.5 6 场景 ×100 迭代 100%（0
 * 意外异常）。
 */
public class P45_T6_NFR_Test {

    /**
     * 单表实体（tag + price）。
     */
    public static class T6Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;

        public T6Row() {
        }

        T6Row(String tag, Double price) {
            this.tag = tag;
            this.price = price;
        }
    }

    /**
     * 可编程 FT 客户端：命中表由 {@link #hits} 决定，同时记录检索过的 query。
     */
    static class MapFtClient extends DsFulltextClusterClient {

        final Map<String, List<Long>> hits = new HashMap<>();
        final List<String> queries = new ArrayList<>();

        MapFtClient(String root) throws Exception {
            super(2, null, root, "shard");
        }

        @Override
        public List<ScoredDocHit> searchCluster(String q, int K, SearchOptions opt) {
            queries.add(q);
            List<Long> ids = hits.getOrDefault(q, Collections.emptyList());
            List<ScoredDocHit> out = new ArrayList<>(ids.size());
            for (Long id : ids) {
                out.add(new ScoredDocHit(id, 0L, 1, 0, 0L, 1.0, 1.0, 0.0, 0.0, 1));
            }
            return out;
        }
    }

    private static final int N = 300;
    private static final int ITER = 120;
    private static final int WARMUP = 80;

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private MapFtClient ft;
    private GenericManager<T6Row> gm;
    private long id1;
    private long id2;
    private long id3;
    private long ghostId;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p45_t6_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft");
        ftRoot.mkdirs();
        ft = new MapFtClient(ftRoot.getAbsolutePath());
        gm = new GenericManager<>(T6Row.class);

        long[] ids = new long[N];
        for (int i = 0; i < N; i++) {
            T6Row e = new T6Row((i % 2 == 0) ? "eng" : "ops", (i % 100 == 0) ? 42.0 : (double) (i + 1));
            gm.saveOrUpdate(e);
            ids[i] = e.getId();
        }
        id1 = ids[0];
        id2 = ids[2];
        id3 = ids[4];

        // ghost：分配有效 id 后删除 → getById 返回 null（构造「FT 命中但无实体」）
        T6Row ghostRow = new T6Row("ghost", 1.0);
        gm.saveOrUpdate(ghostRow);
        ghostId = ghostRow.getId();
        gm.removeById(ghostId);

        ft.hits.put("java", Arrays.asList(id1, id2));
        ft.hits.put("vm", Arrays.asList(id2, id3));
        ft.hits.put("java_vm", Arrays.asList(id1, id2, id3));
        ft.hits.put("none", Collections.emptyList());
        ft.hits.put("ghost", Collections.singletonList(ghostId));
    }

    @After
    public void after() {
        try {
            ft.close();
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
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            java.lang.reflect.Method m = c.getDeclaredMethod("clearAll");
            m.setAccessible(true);
            m.invoke(null);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null) {
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

    private static List<ConditionNode> list(ConditionNode... ns) {
        return new ArrayList<>(Arrays.asList(ns));
    }

    // ============== TR6.1 纯 ORM fetch 均值 ≤ GenericManagerRowSource +5% ==============
    @Test
    public void tr61_pureOrmFetchAvgWithin5PctOfBaseline() {
        List<ConditionNode> minterm = list(ConditionNode.orm("price", DslToken.OP_EQ, 42.0));
        GenericManagerRowSource<T6Row> base = new GenericManagerRowSource<>(gm);
        FulltextAggRowSource<T6Row> sut = new FulltextAggRowSource<>(gm, ft, 20, SearchOptions.defaults());

        // 预热（触发 JIT + 页缓存），随后分别独立采样，避免调用顺序偏差
        for (int i = 0; i < WARMUP; i++) {
            base.fetch(minterm);
            sut.fetch(minterm);
        }
        long[] b = sample(() -> base.fetch(minterm));
        long[] s = sample(() -> sut.fetch(minterm));

        double baseAvg = avg(b);
        double sutAvg = avg(s);
        double limit = baseAvg * 1.05;
        Assert.assertTrue("TR6.1 纯 ORM fetch 均值不得退化 >5%：base=" + (long) baseAvg
                + "ns (P95=" + p95(b) + "ns), sut=" + (long) sutAvg + "ns (P95=" + p95(s)
                + "ns), limit=" + (long) limit + "ns", sutAvg <= limit);
    }

    /**
     * 采样给定操作的耗时（ns）。
     */
    private static long[] sample(Runnable op) {
        long[] v = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            op.run();
            v[i] = System.nanoTime() - t0;
        }
        return v;
    }

    private static double avg(long[] v) {
        long sum = 0;
        for (long x : v) {
            sum += x;
        }
        return sum / (double) v.length;
    }

    private static long p95(long[] v) {
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int idx = (int) Math.ceil(c.length * 0.95) - 1;
        return c[Math.max(0, Math.min(idx, c.length - 1))];
    }

    // ============== TR6.2 Javadoc ≥10 行 ==============
    @Test
    public void tr62_javadocLinesGte10() throws Exception {
        Path src = rowsourceSrc();
        int jdLines = countJavadocLines(src);
        Assert.assertTrue("TR6.2 FulltextAggRowSource Javadoc 行数 = " + jdLines + "，必须 ≥10", jdLines >= 10);
    }

    private static int countJavadocLines(Path p) throws Exception {
        int n = 0;
        boolean in = false;
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String t = raw.trim();
            if (t.startsWith("/**")) {
                in = true;
            }
            if (in) {
                n++;
            }
            if (in && t.endsWith("*/")) {
                in = false;
            }
        }
        return n;
    }

    // ============== TR6.3 CPD 160 字符窗口 0 复制块 ==============
    @Test
    public void tr63_cpd160WindowZeroDuplicateBlock() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] rels = {
            "com/q3lives/ds/database/fulltext/cluster/rowsource",
            "com/q3lives/ds/database/fulltext/cluster/agg"
        };
        List<String> bodies = new ArrayList<>();
        for (String rel : rels) {
            File[] fs = new File(srcRoot, rel).listFiles((d, n) -> n.endsWith(".java"));
            if (fs == null) {
                continue;
            }
            for (File f : fs) {
                String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                c = c.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
                c = c.replaceAll("import\\s+[^;]+;", "");
                c = c.replaceAll("//.*", "");
                c = c.replaceAll("\\s+", "");
                bodies.add(f.getName() + "::" + c);
            }
        }
        Assert.assertTrue("TR6.3 参与 CPD 的源文件数应 ≥2", bodies.size() >= 2);
        // 约定（同 P33/P34/P35/P36/P37）：160 字符窗口，命中 ≥3 视为复制块
        int dupBlocks = 0;
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                String a = bodies.get(i);
                String b = bodies.get(j);
                int hit = 0;
                int checked = 0;
                int step = Math.max(1, a.length() / 10);
                for (int s = 0; s + 160 <= a.length() && checked < 5; s += step) {
                    if (b.contains(a.substring(s, s + 160))) {
                        hit++;
                    }
                    checked++;
                }
                if (hit >= 3) {
                    dupBlocks++;
                }
            }
        }
        Assert.assertEquals("TR6.3 CPD 160 字符窗口复制块 = " + dupBlocks + "，必须 = 0", 0, dupBlocks);
    }

    // ============== TR6.4 0 裸打印 + IAE ≥5 ==============
    @Test
    public void tr64_noBarePrintAndIaeGte5() throws Exception {
        int barePrints = 0;
        int iae = 0;
        List<Path> files = new ArrayList<>();
        Files.walk(rowsourceDir()).filter(Files::isRegularFile)
                .filter(f -> f.toString().endsWith(".java")).forEach(files::add);
        for (Path p : files) {
            for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String t = raw.trim();
                if (t.contains("System.out.print") || t.contains("System.err.print")
                        || t.contains("printStackTrace")) {
                    barePrints++;
                }
                if (t.contains("throw new IllegalArgumentException")) {
                    iae++;
                }
            }
        }
        Assert.assertEquals("TR6.4 裸打印/堆栈打印 = " + barePrints + "，必须 = 0", 0, barePrints);
        Assert.assertTrue("TR6.4 IAE 守卫数 = " + iae + "，必须 ≥5（FR-5 鲁棒性）", iae >= 5);
    }

    // ============== TR6.5 6 场景 ×100 迭代 100% ==============
    @Test
    public void tr65_sixScenarios100xNoUnexpectedException() {
        FulltextAggRowSource<T6Row> sut = new FulltextAggRowSource<>(gm, ft, 20, SearchOptions.defaults());
        List<ConditionNode> pureOrm = list(ConditionNode.orm("price", DslToken.OP_GT, 0.0));
        List<ConditionNode> pureFt = list(ConditionNode.ft("java"));
        List<ConditionNode> ftOrm = list(ConditionNode.ft("java_vm"), ConditionNode.orm("price", DslToken.OP_GT, 0.0));
        List<ConditionNode> multiFt = list(ConditionNode.ft("java"), ConditionNode.ft("vm"));
        List<ConditionNode> ftNone = list(ConditionNode.ft("none"));
        List<ConditionNode> ftGhost = list(ConditionNode.ft("ghost"));

        int unexpected = 0;
        int ghostIse = 0;
        for (int i = 0; i < 100; i++) {
            try {
                if (sut.fetch(pureOrm) == null) {
                    unexpected++;
                }
                if (sut.fetch(pureFt) == null) {
                    unexpected++;
                }
                if (sut.fetch(ftOrm) == null) {
                    unexpected++;
                }
                if (sut.fetch(multiFt) == null) {
                    unexpected++;
                }
                if (!sut.fetch(ftNone).isEmpty()) {
                    unexpected++;
                }
            } catch (Throwable t) {
                unexpected++;
            }
            try {
                sut.fetch(ftGhost);
                unexpected++;
            } catch (IllegalStateException expected) {
                ghostIse++;
            } catch (Throwable t) {
                unexpected++;
            }
        }
        Assert.assertEquals("TR6.5 6 场景 ×100 迭代 意外异常 = " + unexpected + "，必须 = 0", 0, unexpected);
        Assert.assertEquals("TR6.5 FT 命中无实体场景必须每次抛 ISE", 100, ghostIse);
    }

    // ============== helpers（源定位） ==============
    private static Path rowsourceDir() {
        Path p = new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource").toPath();
        Assert.assertTrue("rowsource 源目录不存在: " + p, Files.isDirectory(p));
        return p;
    }

    private static Path rowsourceSrc() {
        Path p = rowsourceDir().resolve("FulltextAggRowSource.java");
        Assert.assertTrue("FulltextAggRowSource 源文件不存在: " + p, Files.isRegularFile(p));
        return p;
    }
}
