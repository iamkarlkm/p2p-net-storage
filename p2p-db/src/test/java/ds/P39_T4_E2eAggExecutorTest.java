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
import java.util.function.ToDoubleFunction;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.GenericManagerRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P39 T4：真实 DB E2E + AggParser 全链路 + 跨进程 fingerprint 3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link DsDatabaseLocal} + {@link GenericManagerRowSource} E2E：5 聚合 vs 手算真值（含 NULL 行）
 * <br>TR4.2 {@code AggParser.parse} → {@code AggExecutor.execute} 全链路等价 TR4.1
 * <br>TR4.3 跨进程 {@code AggResult.fingerprint()} 一致（子 JVM spawn，复用 P38_T4 模板）
 */
public class P39_T4_E2eAggExecutorTest {

    /** 测试实体：tag 为过滤列；price 以文本列承载（null 可被真实存储往返，用于 NULL 语义）。 */
    public static class T4Row extends DsTableAdapter {
        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price_txt", length = 16)
        public String priceTxt;

        public T4Row() {
        }

        public T4Row(long id, String tag, String priceTxt) {
            setId(id);
            this.tag = tag;
            this.priceTxt = priceTxt;
        }
    }

    private static final String DSL =
            "tag = 'x' AGG(COUNT(price), SUM(price), AVG(price), MIN(price), MAX(price))";

    /** 有 6 行（含 1 行 NULL price）：非 NULL = {10,20,40,5,15} → COUNT=5, SUM=90, AVG=18, MIN=5, MAX=40。 */
    private static final Map<String, ToDoubleFunction<T4Row>> COLS =
            Map.of("price", r -> r.priceTxt == null ? Double.NaN : Double.parseDouble(r.priceTxt));

    private File tmpRoot;
    private String oldHome;
    private GenericManager<T4Row> gm;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p39_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(T4Row.class);
        save("10");
        save("20");
        save(null);
        save("40");
        save("5");
        save("15");
    }

    private void save(String priceTxt) {
        T4Row e = new T4Row();
        e.tag = "x";
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

    private static void assertTruth(AggResult r) {
        Assert.assertEquals("5 聚合项", 5, r.size());
        Assert.assertEquals("union 去重行数", 6L, r.unionRowCount);
        Assert.assertEquals("COUNT", 5L, r.value(0).count);
        Assert.assertEquals("COUNT value", 5.0, r.value(0).value, 0.0);
        Assert.assertEquals("SUM", 90.0, r.value(1).value, 1e-9);
        Assert.assertEquals("AVG", 18.0, r.value(2).value, 1e-9);
        Assert.assertEquals("MIN", 5.0, r.value(3).value, 1e-9);
        Assert.assertEquals("MAX", 40.0, r.value(4).value, 1e-9);
    }

    // ============== TR4.1 真实 DB E2E（手写计划 + GenericManagerRowSource） ==============

    @Test(timeout = 120000)
    public void tr41_e2eGenericManagerRowSource() throws Exception {
        DnfClause clause = new DnfClause(Collections.singletonList(
                Collections.singletonList(ConditionNode.orm("tag", DslToken.OP_EQ, "x"))));
        AggQueryPlan plan = new AggQueryPlan(clause, Arrays.asList(
                new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"),
                new AggSpec(AggFn.AVG, "price"), new AggSpec(AggFn.MIN, "price"),
                new AggSpec(AggFn.MAX, "price")));
        AggRowSource<T4Row> src = new GenericManagerRowSource<>(gm);
        AggResult r = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(r);
    }

    // ============== TR4.2 AggParser.parse → AggExecutor.execute 全链路 ==============

    @Test(timeout = 120000)
    public void tr42_parserToExecutorFullChain() throws Exception {
        AggQueryPlan plan = AggParser.parse(DsTableAdapter.class, DSL, Map.of(), Map.of(), 256, 8);
        Assert.assertEquals("1 minterm", 1, plan.clause.size());
        Assert.assertEquals("5 aggs", 5, plan.aggs.size());
        Assert.assertEquals("N = 5", 5, plan.taskCount());

        AggRowSource<T4Row> src = new GenericManagerRowSource<>(gm);
        AggResult r = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(r);
    }

    // ============== TR4.3 跨进程 fingerprint 一致 ==============

    @Test(timeout = 120000)
    public void tr43_crossProcessFingerprint() throws Exception {
        AggQueryPlan plan = AggParser.parse(DsTableAdapter.class, DSL, Map.of(), Map.of(), 256, 8);
        AggResult local = new AggExecutor<T4Row>().execute(plan, memSource(), COLS);
        assertTruth(local);
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
                    P39_T4_E2eAggExecutorTest.class.getName(),
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
        rs.add(new T4Row(1L, "x", "10"));
        rs.add(new T4Row(2L, "x", "20"));
        rs.add(new T4Row(3L, "x", null));
        rs.add(new T4Row(4L, "x", "40"));
        rs.add(new T4Row(5L, "x", "5"));
        rs.add(new T4Row(6L, "x", "15"));
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
     * 子 JVM 入口：解析 DSL → 内存确定性行集执行 → 打印 AggResult fingerprint（或 ERROR: ...）。
     */
    public static void main(String[] args) {
        try {
            AggQueryPlan p = AggParser.parse(DsTableAdapter.class, args[0], Map.of(), Map.of(), 256, 8);
            AggResult r = new AggExecutor<T4Row>().execute(p, memSource(), COLS);
            System.out.println(r.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }
}
