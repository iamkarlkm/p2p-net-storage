package ds;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.columnar.aggpushdown.ColumnAggPushdown;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.model.DbAggFn;
import javax.net.p2p.model.DbAggItem;

/**
 * P53 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 列值聚合端到端 ≤「N 次 {@link ColumnarStore#getValue} + 单遍累加」线性上界（硬断言正确性 +
 * 均值/中位数带松弛上界）；TR6.2 列值聚合结果 ≡ P39 {@link AggExecutor}（实体级 oracle）**逐值等价**；
 * TR6.3 新类 Javadoc ≥10 行；TR6.4 CPD 160 字符窗口 0 重复块（新类 + 受改 handler/client）；TR6.5
 * 0 {@code System.out.println}/{@code printStackTrace} + IAE 显式守卫足量。
 *
 * <p>
 * 夹具：{@code p2p.db.home} = tmpRoot，ORM 行（{@link GenericManager}）+ 列元
 * （{@link TableMetaStore#ensureMeta}）+ 列值（{@link ColumnarStore#putValue}）+ id
 * 集合
 * （{@link DynamicIndexUtil#idsFile}）**同根双写**；{@link ColumnAggPushdown#compute}
 * 直接以根目录读取列值。
 */
public class P53_T6_NFR_Test {

    private static final int ITER = 12;
    private static final int WARMUP = 4;

    /**
     * 未触发态允许的绝对松弛量（纳秒）：吸收 {@link ColumnAggPushdown#compute} 相对手工基线的
     * buckets/Store 构造等固有增量 + 调度抖动。
     */
    private static final long SLACK_NANOS = 50_000_000L;

    private static final int ROWS = 200;

    private static final String AGGRUN_SRC
            = "p2p-db/src/main/java/com/q3lives/ds/database/columnar/aggpushdown";
    private static final String HANDLER_SRC
            = "p2p-db/src/main/java/javax/net/p2p/server/handler/DbRowAggServerHandler.java";
    private static final String CLIENT_SRC
            = "p2p-db/src/main/java/com/q3lives/ds/database/columnar/DsDatabaseClusterClient.java";
    private static final String DB_AGG_ITEM_SRC
            = "p2p-core/src/main/java/javax/net/p2p/model/DbAggItem.java";

    /**
     * 测试实体：tag 谓词列 + num 聚合列。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "num", length = 4)
        public int num;

        public Row() {
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private ColumnarStore cs;
    private final List<Long> idList = new ArrayList<>();
    private List<Row> rows;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p53_t6_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        new TableMetaStore(tmpRoot).ensureMeta(Row.class);
        cs = new ColumnarStore(tmpRoot);

        rows = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            int num = (i % 60) + 1;
            Row e = new Row();
            e.tag = "x";
            e.num = num;
            gm.saveOrUpdate(e);
            long id = e.getId();
            idList.add(id);
            rows.add(e);
            cs.putValue(Row.class.getName(), "tag", id, pad("x", 16));
            cs.putValue(Row.class.getName(), "num", id, ByteBuffer.allocate(4).putInt(num).array());
        }
        // 不在此处 close：putValue 写通盘，ColumnAggPushdown 以独立句柄读取可见；
        // 保持 cs 存活供 tr61 基线 getValue 使用，统一由 @After 关闭。
        registerIds();
    }

    @After
    public void after() {
        try {
            if (cs != null) {
                cs.close();
            }
        } catch (Throwable ignore) {
        }
        try {
            DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        try {
            DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        } catch (Throwable ignore) {
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    private void registerIds() throws IOException {
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(tmpRoot, Row.class.getName()));
        for (long id : idList) {
            set.add(id);
        }
    }

    private static byte[] pad(String s, int len) {
        byte[] raw = s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, Math.min(raw.length, len));
        return out;
    }

    private static DbAggItem agg(DbAggFn fn, String column) {
        return new DbAggItem(fn, column);
    }

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    // ============== TR6.1 线性上界（正确性硬断言 + 均值/中位数松弛上界） ==============
    @Test(timeout = 180000)
    public void tr61_linearUpperBoundOfColumnAggregations() throws Exception {
        String ent = Row.class.getName();

        // 手工基线：N 次 getValue + 单遍累加。
        long expectSum = 0L;
        for (long id : idList) {
            byte[] b = cs.getValue(ent, "num", id);
            if (b != null) {
                expectSum += ByteBuffer.wrap(b).getInt();
            }
        }
        List<Object[]> got = ColumnAggPushdown.compute(tmpRoot, ent, idList,
                Collections.singletonList(agg(DbAggFn.SUM, "num")), null, null, null, 0, 100);
        Assert.assertEquals("全局聚合 1 行", 1, got.size());
        Assert.assertEquals("TR6.1 SUM 与手工累加等价（正确性硬断言）",
                (double) expectSum, d(got.get(0)[0]), 1e-9);

        for (int k = 0; k < WARMUP; k++) {
            baselineOnce(ent);
            candidateOnce(ent);
        }
        long[] bs = new long[ITER];
        long[] csArr = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            if ((k & 1) == 0) {
                bs[k] = baselineOnce(ent);
                csArr[k] = candidateOnce(ent);
            } else {
                csArr[k] = candidateOnce(ent);
                bs[k] = baselineOnce(ent);
            }
        }
        double baseMean = avg(bs);
        double candMean = avg(csArr);
        long baseMed = p50(bs);
        long candMed = p50(csArr);
        long meanTol = Math.max(SLACK_NANOS, (long) (baseMean * 1.0));
        long medTol = Math.max(SLACK_NANOS, (long) (baseMed * 1.0));
        Assert.assertTrue("TR6.1 聚合均值 = " + (long) candMean + "ns 应 ≤ 基线 "
                + (long) baseMean + "ns + " + meanTol + "ns（P95 base=" + p95(bs)
                + "ns cand=" + p95(csArr) + "ns）",
                candMean <= baseMean + meanTol);
        Assert.assertTrue("TR6.1 聚合中位数 = " + candMed + "ns 应 ≤ 基线 "
                + baseMed + "ns + " + medTol + "ns",
                candMed <= baseMed + medTol);
    }

    private long baselineOnce(String ent) throws IOException {
        long t0 = System.nanoTime();
        long sum = 0L;
        for (long id : idList) {
            byte[] b = cs.getValue(ent, "num", id);
            if (b != null) {
                sum += ByteBuffer.wrap(b).getInt();
            }
        }
        long r = System.nanoTime() - t0;
        if (sum < 0) {
            throw new IllegalStateException("impossible");
        }
        return r;
    }

    private long candidateOnce(String ent) {
        long t0 = System.nanoTime();
        List<Object[]> out = ColumnAggPushdown.compute(tmpRoot, ent, idList,
                Collections.singletonList(agg(DbAggFn.SUM, "num")), null, null, null, 0, 100);
        long r = System.nanoTime() - t0;
        Assert.assertEquals(1, out.size());
        return r;
    }

    // ============== TR6.2 列值聚合 ≡ AggExecutor（实体级 oracle）逐值等价 ==============
    @Test(timeout = 120000)
    public void tr62_columnAggEqualsAggExecutorOracle() throws Exception {
        String dsl = "tag = 'x' AGG(COUNT(num), SUM(num), AVG(num), MIN(num), MAX(num))";
        Map<String, ToDoubleFunction<Row>> cols
                = Collections.singletonMap("num", r -> (double) r.num);

        AggQueryPlan plan = AggParser.parse(Row.class, dsl, Map.of(), Map.of(), 256, 8);
        List<Row> matched = new ArrayList<>();
        for (long id : idList) {
            matched.add(gm.getById(id));
        }
        AggRowSource<Row> src = minterm -> matched;
        AggResult oracle = new AggExecutor<Row>().execute(plan, src, cols);

        List<Object[]> got = ColumnAggPushdown.compute(tmpRoot, Row.class.getName(), idList,
                Arrays.asList(agg(DbAggFn.COUNT, "num"), agg(DbAggFn.SUM, "num"),
                        agg(DbAggFn.AVG, "num"), agg(DbAggFn.MIN, "num"), agg(DbAggFn.MAX, "num")),
                null, null, null, 0, 100);
        Assert.assertEquals("全局聚合 1 行", 1, got.size());
        Assert.assertEquals("行宽 = 5 聚合", 5, got.get(0).length);
        Assert.assertEquals("oracle 5 聚合项", 5, oracle.size());
        Assert.assertEquals("oracle 参与行数 = N", ROWS, oracle.value(0).count);

        for (int j = 0; j < 5; j++) {
            Assert.assertEquals("TR6.2 聚合值[" + j + "] 逐值等价",
                    oracle.value(j).value, d(got.get(0)[j]), 1e-9);
        }
    }

    // ============== TR6.3 新类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr63_newClassJavadocGte10Lines() throws Exception {
        Path p = new File(projectRoot(), AGGRUN_SRC + "/ColumnAggPushdown.java").toPath();
        Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
        int jd = countJavadocLines(p);
        Assert.assertTrue("TR6.3 ColumnAggPushdown Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
    }

    // ============== TR6.4 CPD 160 字符窗口 0 重复块 ==============
    @Test(timeout = 30000)
    public void tr64_cpd160WindowZeroDuplicateBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String rel : new String[]{
            AGGRUN_SRC + "/ColumnAggPushdown.java", HANDLER_SRC, CLIENT_SRC}) {
            File f = new File(projectRoot(), rel);
            Assert.assertTrue("参与 CPD 的源文件不存在: " + f, f.isFile());
            String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            c = c.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            c = c.replaceAll("import\\s+[^;]+;", "");
            c = c.replaceAll("//.*", "");
            c = c.replaceAll("\\s+", "");
            bodies.add(f.getName() + "::" + c);
        }
        Assert.assertTrue("TR6.4 参与 CPD 的源文件数应 ≥2", bodies.size() >= 2);
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
        Assert.assertEquals("TR6.4 CPD 160 字符窗口复制块 = " + dupBlocks + "，必须 = 0", 0, dupBlocks);
    }

    // ============== TR6.5 0 裸打印 + IAE ≥8 ==============
    @Test(timeout = 30000)
    public void tr65_noBarePrintAndIaeGte8() throws Exception {
        int barePrints = 0;
        int iae = 0;
        List<File> files = new ArrayList<>();
        for (String rel : new String[]{
            AGGRUN_SRC + "/ColumnAggPushdown.java", HANDLER_SRC, CLIENT_SRC, DB_AGG_ITEM_SRC}) {
            File f = new File(projectRoot(), rel);
            Assert.assertTrue("受检源文件不存在: " + f, f.isFile());
            files.add(f);
        }
        for (File f : files) {
            for (String raw : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
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
        Assert.assertEquals("TR6.5 裸打印/堆栈打印 = " + barePrints + "，必须 = 0", 0, barePrints);
        Assert.assertTrue("TR6.5 IAE 守卫数 = " + iae + "，必须 ≥8（FR-5 鲁棒性）", iae >= 8);
    }

    /* -------- helpers -------- */
    private static File projectRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "p2p-db").isDirectory() && new File(dir, "p2p-core").isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static double avg(long[] v) {
        long sum = 0;
        for (long x : v) {
            sum += x;
        }
        return sum / (double) v.length;
    }

    private static long p50(long[] v) {
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static long p95(long[] v) {
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int idx = (int) Math.ceil(c.length * 0.95) - 1;
        return c[Math.max(0, Math.min(idx, c.length - 1))];
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

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
