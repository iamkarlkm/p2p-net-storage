package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.cluster.DsFulltextClusterClient;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.rowsource.FulltextAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P45 T4：真实 DB + 真实 FT 索引 E2E 3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实
 * {@link DsDatabaseLocal} + {@link DsFulltextClusterClient} + {@link FulltextAggRowSource}
 * 全链路（{@code AggParser.parse → AggExecutor.execute}，含 FT 条件）vs 手算真值；TR4.2
 * 隔离目录重复执行 零重建（fingerprint 与计数稳定）；TR4.3 跨进程 {@link AggResult#fingerprint()}
 * 64-hex 一致（子 JVM spawn）。
 */
public class P45_T4_E2eFtAggTest {

    /**
     * 测试实体：tag 过滤列 + price 数值列 + txt 承载 FT 语料。
     */
    public static class T4Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;
        @DsField(name = "price", length = 8)
        public Double price;
        @DsField(name = "txt", length = 128)
        public String txt;

        public T4Row() {
        }

        public T4Row(long id, String tag, Double price, String txt) {
            setId(id);
            this.tag = tag;
            this.price = price;
            this.txt = txt;
        }
    }

    /**
     * FT 条件 + 2 聚合（COUNT/SUM）。
     */
    private static final String DSL = "FT('人民') AGG(COUNT(price), SUM(price))";
    private static final String FT_TERM = "人民";

    /**
     * 手算真值： 人民 命中 2 条（price 150 + 50）→ COUNT=2, SUM=200。
     */
    private static final double EXPECT_SUM = 200.0;
    private static final long EXPECT_COUNT = 2L;

    private static final Map<String, ToDoubleFunction<T4Row>> COLS
            = java.util.Collections.singletonMap("price", r -> r.price == null ? Double.NaN : r.price);

    private File tmpRoot;
    private String oldHome;
    private File ftRoot;
    private DsFulltextClusterClient ftClient;
    private GenericManager<T4Row> gm;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p45_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        ftRoot = new File(tmpRoot, "ft_inproc");
        ftRoot.mkdirs();
        ftClient = new DsFulltextClusterClient(2, null, ftRoot.getAbsolutePath(), "shard");
        gm = new GenericManager<>(T4Row.class);
        save("人民 民主 科技", 150.0);
        save("人民 创新 发展", 50.0);
        save("绿色 环保 低碳", 200.0);
        Thread.sleep(500);
    }

    private void save(String text, Double price) {
        T4Row e = new T4Row();
        e.tag = "x";
        e.price = price;
        e.txt = text;
        gm.saveOrUpdate(e);
        try {
            IngestStat st = ftClient.putDocCluster(e.getId(), text);
            Assert.assertNotNull("putDocCluster stat 非 null", st);
        } catch (IOException io) {
            throw new IllegalStateException(io);
        }
    }

    @After
    public void after() {
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
        try {
            Class<?> c = Class.forName("javax.net.p2p.server.handler.fulltext.DsServerFulltextRegistry");
            Method m = c.getDeclaredMethod("clearAll");
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

    private static void assertTruth(AggResult r) {
        Assert.assertEquals("2 聚合项", 2, r.size());
        Assert.assertEquals("union 去重行数", EXPECT_COUNT, r.unionRowCount);
        Assert.assertEquals("COUNT(price)", EXPECT_COUNT, r.value(0).count);
        Assert.assertEquals("SUM(price)", EXPECT_SUM, r.value(1).value, 1e-9);
    }

    // ============== TR4.1 真实 FT 索引 E2E ==============
    @Test(timeout = 120000)
    public void tr41_realFtIndexEndToEnd() throws Exception {
        AggQueryPlan plan = AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
        Assert.assertEquals("1 minterm", 1, plan.clause.size());
        Assert.assertEquals("minterm0 含 1 个 FT 节点", 1, plan.clause.getMiniTerm(0).size());
        Assert.assertEquals("FT 节点 kind", ConditionNode.Kind.FT, plan.clause.getMiniTerm(0).get(0).kind);

        AggRowSource<T4Row> src = new FulltextAggRowSource<>(gm, ftClient, 100, SearchOptions.defaults());
        AggResult r = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(r);
    }

    // ============== TR4.2 隔离目录重复执行零重建 ==============
    @Test(timeout = 120000)
    public void tr42_isolatedDirRepeatZeroRebuild() throws Exception {
        AggQueryPlan plan = AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
        AggRowSource<T4Row> src = new FulltextAggRowSource<>(gm, ftClient, 100, SearchOptions.defaults());

        AggResult first = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(first);
        int filesAfterFirst = countFiles(ftRoot);

        AggResult second = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(second);
        int filesAfterSecond = countFiles(ftRoot);

        Assert.assertEquals("重复执行 fingerprint 稳定（零重建）",
                first.fingerprint(), second.fingerprint());
        Assert.assertEquals("重复执行 FT 目录文件数不增长（零重建）",
                filesAfterFirst, filesAfterSecond);
    }

    // ============== TR4.3 跨进程 fingerprint 一致 ==============
    @Test(timeout = 120000)
    public void tr43_crossProcessFingerprint() throws Exception {
        AggQueryPlan plan = AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
        AggResult local = new AggExecutor<T4Row>().execute(plan, memSource(), COLS);
        assertTruth(local);
        String localFp = local.fingerprint();
        Assert.assertEquals(64, localFp.length());

        String[] child = runHelperTwice(DSL);
        Assert.assertEquals("子进程0 fingerprint 一致", localFp, child[0]);
        Assert.assertEquals("子进程1 fingerprint 一致", localFp, child[1]);
    }

    private static int countFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0;
        }
        int n = 0;
        File[] ch = dir.listFiles();
        if (ch != null) {
            for (File c : ch) {
                n += c.isDirectory() ? countFiles(c) : 1;
            }
        }
        return n;
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
                    P45_T4_E2eFtAggTest.class.getName(),
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
        rs.add(new T4Row(1L, "x", 150.0, "人民 民主 科技"));
        rs.add(new T4Row(2L, "x", 50.0, "人民 创新 发展"));
        rs.add(new T4Row(3L, "x", 200.0, "绿色 环保 低碳"));
        return rs;
    }

    /**
     * 内存行源：FT 节点按 {@code txt.contains(ftQuery)} 过滤（AND 语义，与真实 FT 命中一致）。
     */
    private static AggRowSource<T4Row> memSource() {
        List<T4Row> all = memData();
        return minterm -> {
            List<T4Row> out = new ArrayList<>();
            for (T4Row r : all) {
                boolean ok = true;
                for (ConditionNode n : minterm) {
                    if (n.kind == ConditionNode.Kind.FT) {
                        if (r.txt == null || !r.txt.contains(n.ftQuery)) {
                            ok = false;
                            break;
                        }
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
            AggQueryPlan p = AggParser.parse(T4Row.class, args[0], Map.of(), Map.of(), 256, 8);
            AggResult r = new AggExecutor<T4Row>().execute(p, memSource(), COLS);
            System.out.println(r.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }
}
