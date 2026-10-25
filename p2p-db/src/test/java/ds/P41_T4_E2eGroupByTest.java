package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByResult;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec.HavingOp;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P41 T4：真实 DB E2E + AggParser 全链路 + 跨进程 fingerprint 3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link DsDatabaseLocal} + {@link GenericManagerRowSource} E2E：双列 GROUP BY（含 NULL 分组列
 * + NULL 聚合列）+ HAVING 剔除 NULL 桶 vs 手算真值
 * <br>TR4.2 {@code AggParser.parse} → {@code GroupBySpec} → {@code GroupByExecutor.execute} 全链路
 * <br>TR4.3 跨进程 {@code GroupByResult.fingerprint()} 一致（子 JVM spawn）
 */
public class P41_T4_E2eGroupByTest {

    /** 测试实体：tag 过滤列；grp/city 分组列（可 NULL）；price 以文本列承载（NULL 可真实往返）。 */
    public static class T4Row extends DsTableAdapter {
        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "grp", length = 16)
        public String grp;
        @DsField(name = "city", length = 16)
        public String city;
        @DsField(name = "price_txt", length = 16)
        public String priceTxt;

        public T4Row() {
        }

        public T4Row(long id, String tag, String grp, String city, String priceTxt) {
            setId(id);
            this.tag = tag;
            this.grp = grp;
            this.city = city;
            this.priceTxt = priceTxt;
        }
    }

    private static final String DSL = "tag = 'x' AGG(COUNT(price), SUM(price))";

    private static final Map<String, Function<T4Row, String>> GROUP_COLS
            = Map.of("grp", r -> r.grp, "city", r -> r.city);

    private static final Map<String, ToDoubleFunction<T4Row>> AGG_COLS
            = Map.of("price", r -> r.priceTxt == null ? Double.NaN : Double.parseDouble(r.priceTxt));

    private File tmpRoot;
    private String oldHome;
    private GenericManager<T4Row> gm;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p41_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(T4Row.class);
        save("eng", "sfo", "10");
        save("eng", "sfo", "20");
        save(null, null, null);
        save("ops", "nyc", "40");
        save("ops", "nyc", "5");
        save("eng", "nyc", "15");
    }

    private void save(String grp, String city, String priceTxt) {
        T4Row e = new T4Row();
        e.tag = "x";
        e.grp = grp;
        e.city = city;
        e.priceTxt = priceTxt;
        gm.saveOrUpdate(e);
    }

    @After
    public void after() {
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

    /** 计划：2 agg（COUNT/SUM）+ GROUP BY grp,city。 */
    private static AggQueryPlan planFromParse() {
        return AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
    }

    /**
     * 手算真值（GROUP BY (grp,city)，COUNT/SUM(price)）：
     * <pre>
     * [\u0000,\u0000] count0 sum0  rowCount1
     * [eng,nyc]       count1 sum15 rowCount1
     * [eng,sfo]       count2 sum30 rowCount2
     * [ops,nyc]       count2 sum45 rowCount2
     * </pre>
     */
    private static void assertTruth(GroupByResult r, boolean withNullBucket) {
        Assert.assertEquals(6L, r.totalRowCount);
        Assert.assertEquals(withNullBucket ? 4 : 3, r.size());
        int base = withNullBucket ? 1 : 0;
        if (withNullBucket) {
            Assert.assertEquals("\u0000", r.bucket(0).key.get(0));
            Assert.assertEquals("\u0000", r.bucket(0).key.get(1));
            Assert.assertEquals(1L, r.bucket(0).rowCount);
            Assert.assertEquals(0.0, r.bucket(0).values.get(0).value, 0.0);
            Assert.assertEquals(0.0, r.bucket(0).values.get(1).value, 0.0);
        }
        Assert.assertEquals(Arrays.asList("eng", "nyc"), r.bucket(base).key);
        Assert.assertEquals(1.0, r.bucket(base).values.get(0).value, 0.0);
        Assert.assertEquals(15.0, r.bucket(base).values.get(1).value, 0.0);

        Assert.assertEquals(Arrays.asList("eng", "sfo"), r.bucket(base + 1).key);
        Assert.assertEquals(2L, r.bucket(base + 1).rowCount);
        Assert.assertEquals(2.0, r.bucket(base + 1).values.get(0).value, 0.0);
        Assert.assertEquals(30.0, r.bucket(base + 1).values.get(1).value, 0.0);

        Assert.assertEquals(Arrays.asList("ops", "nyc"), r.bucket(base + 2).key);
        Assert.assertEquals(2L, r.bucket(base + 2).rowCount);
        Assert.assertEquals(2.0, r.bucket(base + 2).values.get(0).value, 0.0);
        Assert.assertEquals(45.0, r.bucket(base + 2).values.get(1).value, 0.0);
    }

    // ============== TR4.1 真实 DB E2E（手写计划 + GenericManagerRowSource） ==============

    @Test(timeout = 120000)
    public void tr41_e2eDoubleColumnGroupBy() throws Exception {
        AggQueryPlan plan = planFromParse();
        AggRowSource<T4Row> src = new GenericManagerRowSource<>(gm);

        // 无 HAVING：含 NULL 分组桶（key="\u0000"）
        GroupBySpec noHv = new GroupBySpec(plan, Arrays.asList("grp", "city"),
                Collections.emptyList(), Collections.emptyList());
        GroupByResult rAll = new GroupByExecutor<T4Row>().execute(noHv, src, GROUP_COLS, AGG_COLS);
        assertTruth(rAll, true);

        // HAVING COUNT(price) >= 1：NULL 桶（count0）剔除
        GroupBySpec withHv = new GroupBySpec(plan, Arrays.asList("grp", "city"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
        GroupByResult rHv = new GroupByExecutor<T4Row>().execute(withHv, src, GROUP_COLS, AGG_COLS);
        assertTruth(rHv, false);
    }

    // ============== TR4.2 AggParser.parse → GroupBySpec → execute 全链路 ==============

    @Test(timeout = 120000)
    public void tr42_parserToGroupByFullChain() throws Exception {
        AggQueryPlan plan = planFromParse();
        Assert.assertEquals("1 minterm", 1, plan.clause.size());
        Assert.assertEquals("2 aggs", 2, plan.aggs.size());

        GroupBySpec spec = new GroupBySpec(plan, Arrays.asList("grp", "city"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
        GroupByResult r = new GroupByExecutor<T4Row>()
                .execute(spec, new GenericManagerRowSource<>(gm), GROUP_COLS, AGG_COLS);
        assertTruth(r, false);
    }

    // ============== TR4.3 跨进程 fingerprint 一致 ==============

    @Test(timeout = 120000)
    public void tr43_crossProcessFingerprint() throws Exception {
        GroupBySpec spec = new GroupBySpec(planFromParse(), Arrays.asList("grp", "city"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
        GroupByResult local = new GroupByExecutor<T4Row>().execute(spec, memSource(), GROUP_COLS, AGG_COLS);
        assertTruth(local, false);
        String localFp = local.fingerprint();
        Assert.assertEquals(64, localFp.length());

        String[] child = runHelperTwice(DSL);
        Assert.assertEquals("子进程0 fingerprint 一致", localFp, child[0]);
        Assert.assertEquals("子进程1 fingerprint 一致", localFp, child[1]);
    }

    /**
     * spawn 子 JVM 跑本测试类 main()，捕获 stdout 首行（fingerprint 或 ERROR: ...）。
     */
    private static String[] runHelperTwice(String dsl) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx512m",
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    P41_T4_E2eGroupByTest.class.getName(),
                    dsl);
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String fpLine = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Picked up ") || line.trim().isEmpty()) {
                    continue;
                }
                fpLine = line.trim();
                break;
            }
            p.waitFor(20, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            outputs[i] = fpLine == null ? "" : fpLine;
        }
        return outputs;
    }

    // ============== 内存确定性行集（跨进程对齐用） ==============

    private static List<T4Row> memData() {
        List<T4Row> rs = new ArrayList<>();
        rs.add(new T4Row(1L, "x", "eng", "sfo", "10"));
        rs.add(new T4Row(2L, "x", "eng", "sfo", "20"));
        rs.add(new T4Row(3L, "x", null, null, null));
        rs.add(new T4Row(4L, "x", "ops", "nyc", "40"));
        rs.add(new T4Row(5L, "x", "ops", "nyc", "5"));
        rs.add(new T4Row(6L, "x", "eng", "nyc", "15"));
        return rs;
    }

    private static AggRowSource<T4Row> memSource() {
        List<T4Row> all = memData();
        return minterm -> {
            List<T4Row> out = new ArrayList<>();
            for (T4Row r : all) {
                boolean ok = true;
                for (ConditionNode n : minterm) {
                    if (!"tag".equals(n.ormCol) || n.ormOp != DslToken.OP_EQ
                            || !"x".equals(String.valueOf(n.ormValueA))) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    out.add(r);
                }
            }
            return out;
        };
    }

    /**
     * 子 JVM 入口：解析 DSL → 内存确定性行集 GROUP BY → 打印 GroupByResult fingerprint（或 ERROR: ...）。
     */
    public static void main(String[] args) {
        try {
            GroupBySpec spec = new GroupBySpec(planFromParse(), Arrays.asList("grp", "city"),
                    Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
            GroupByResult r = new GroupByExecutor<T4Row>().execute(spec, memSource(), GROUP_COLS, AGG_COLS);
            System.out.println(r.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }
}
