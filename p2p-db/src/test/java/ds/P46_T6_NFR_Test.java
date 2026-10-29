package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

import javax.net.p2p.model.DbQuery;

/**
 * P46 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 本地双模路径 {@code fetch} 均值 ≤ {@link GenericManagerRowSource} +5%（性能无退化，
 * 采样统计量取均值以规避 GC/page-cache 抖动，逐样本 P95 一并打印）； TR6.2
 * {@code ColumnarAggRowSource} Javadoc ≥10 行；TR6.3 CPD 160 字符窗口 0 复制块； TR6.4 0
 * {@code println}/{@code printStackTrace} + IAE ≥5；TR6.5 6 场景 ×100 迭代 100%（0
 * 意外异常 + ghost 每次 ISE）。
 */
public class P46_T6_NFR_Test {

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

    private static final DsServerDirectory EMPTY_DIR = new DsServerDirectory() {
        @Override
        public int[] listServerIds() {
            return new int[0];
        }

        @Override
        public Endpoint endpoint(int serverId) {
            return null;
        }
    };

    /**
     * 可编程列存客户端：{@link #queryIds} 恒返回当前预设 id 列表。
     */
    static class MapColClient extends DsDatabaseClusterClient {

        List<Long> next = new ArrayList<>();

        MapColClient(DsPartitionManager m, DsHashMapIdAllocator a) {
            super(1, m, a, EMPTY_DIR);
        }

        void set(List<Long> ids) {
            this.next = ids;
        }

        @Override
        public List<Long> queryIds(String entityClassName, DbQuery query, int offset, int limit) {
            return new ArrayList<>(next);
        }
    }

    private static final int N = 300;
    private static final int ITER = 120;
    private static final int WARMUP = 80;

    private File tmpRoot;
    private String oldHome;
    private GenericManager<T6Row> gm;
    private MapColClient col;
    private DsPartitionManager manager;
    private long id1;
    private long id2;
    private long id3;
    private long ghostId;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t6_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

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

        // ghost：分配有效 id 后删除 → getById 返回 null（构造「列存命中但无实体」）
        T6Row ghostRow = new T6Row("ghost", 1.0);
        gm.saveOrUpdate(ghostRow);
        ghostId = ghostRow.getId();
        gm.removeById(ghostId);

        manager = new DsPartitionManager(new File(tmpRoot, "pm"),
                DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        col = new MapColClient(manager, allocator);
    }

    @After
    public void after() {
        if (col != null) {
            col.close();
        }
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception ignore) {
            }
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

    // ============== TR6.1 本地双模 fetch 均值 ≤ GenericManagerRowSource +5% ==============
    @Test
    public void tr61_localModeFetchAvgWithin5PctOfBaseline() {
        List<ConditionNode> minterm = list(ConditionNode.orm("price", DslToken.OP_EQ, 42.0));
        GenericManagerRowSource<T6Row> base = new GenericManagerRowSource<>(gm);
        ColumnarAggRowSource<T6Row> sut = new ColumnarAggRowSource<>(gm, null, null);

        // 预热（触发 JIT + 页缓存），随后交替采样以抵消顺序偏差与漂移
        for (int i = 0; i < WARMUP; i++) {
            base.fetch(minterm);
            sut.fetch(minterm);
        }
        long[] b = new long[ITER];
        long[] s = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            if ((i & 1) == 0) {
                long t0 = System.nanoTime();
                base.fetch(minterm);
                b[i] = System.nanoTime() - t0;
                long t1 = System.nanoTime();
                sut.fetch(minterm);
                s[i] = System.nanoTime() - t1;
            } else {
                long t1 = System.nanoTime();
                sut.fetch(minterm);
                s[i] = System.nanoTime() - t1;
                long t0 = System.nanoTime();
                base.fetch(minterm);
                b[i] = System.nanoTime() - t0;
            }
        }

        double baseAvg = avg(b);
        double sutAvg = avg(s);
        double limit = baseAvg * 1.05;
        Assert.assertTrue("TR6.1 本地双模 fetch 均值不得退化 >5%：base=" + (long) baseAvg
                + "ns (P95=" + p95(b) + "ns), sut=" + (long) sutAvg + "ns (P95=" + p95(s)
                + "ns), limit=" + (long) limit + "ns", sutAvg <= limit);
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
        Assert.assertTrue("TR6.2 ColumnarAggRowSource Javadoc 行数 = " + jdLines + "，必须 ≥10", jdLines >= 10);
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
        // 约定（同 P33/P34/P35/P36/P37/P45）：160 字符窗口，命中 ≥3 视为复制块
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
        ColumnarAggRowSource<T6Row> sut = new ColumnarAggRowSource<>(gm, col, T6Row.class.getName());
        List<ConditionNode> eq = list(ConditionNode.orm("price", DslToken.OP_EQ, 42.0));
        List<ConditionNode> gt = list(ConditionNode.orm("price", DslToken.OP_GT, 0.0));
        List<ConditionNode> between = list(ConditionNode.between("price", 1.0, 100.0));
        List<ConditionNode> in = list(ConditionNode.orm("price", DslToken.KW_IN, Arrays.asList(1.0, 2.0)));
        List<ConditionNode> isNull = list(ConditionNode.isNull("price"));
        List<ConditionNode> ghost = list(ConditionNode.orm("price", DslToken.OP_EQ, 42.0));

        int unexpected = 0;
        int ghostIse = 0;
        for (int i = 0; i < 100; i++) {
            try {
                col.set(Arrays.asList(id1, id2));
                if (sut.fetch(eq) == null) {
                    unexpected++;
                }
                col.set(Arrays.asList(id1, id2, id3));
                if (sut.fetch(gt) == null) {
                    unexpected++;
                }
                col.set(Arrays.asList(id1, id2));
                if (sut.fetch(between) == null) {
                    unexpected++;
                }
                col.set(Collections.singletonList(id1));
                if (sut.fetch(in) == null) {
                    unexpected++;
                }
                col.set(Collections.emptyList());
                if (!sut.fetch(isNull).isEmpty()) {
                    unexpected++;
                }
            } catch (Throwable t) {
                unexpected++;
            }
            try {
                col.set(Collections.singletonList(ghostId));
                sut.fetch(ghost);
                unexpected++;
            } catch (IllegalStateException expected) {
                ghostIse++;
            } catch (Throwable t) {
                unexpected++;
            }
        }
        Assert.assertEquals("TR6.5 6 场景 ×100 迭代 意外异常 = " + unexpected + "，必须 = 0", 0, unexpected);
        Assert.assertEquals("TR6.5 列存命中无实体场景必须每次抛 ISE", 100, ghostIse);
    }

    // ============== helpers（源定位） ==============
    private static Path rowsourceDir() {
        Path p = new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/fulltext/cluster/rowsource").toPath();
        Assert.assertTrue("rowsource 源目录不存在: " + p, Files.isDirectory(p));
        return p;
    }

    private static Path rowsourceSrc() {
        Path p = rowsourceDir().resolve("ColumnarAggRowSource.java");
        Assert.assertTrue("ColumnarAggRowSource 源文件不存在: " + p, Files.isRegularFile(p));
        return p;
    }
}
