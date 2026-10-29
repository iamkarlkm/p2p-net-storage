package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
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
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapIdAllocator;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsDatabaseClusterClient;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.rowsource.ColumnarAggRowSource;
import com.q3lives.ds.database.integration.GenericManager;

/**
 * P46 T4：真实 DB + 真实列存 ids 集合 E2E 3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link DsDatabaseLocal}（经 {@link GenericManager}）+ 真实
 * {@link DsDatabaseClusterClient}（本地列存分区 ids 集合）+ {@link ColumnarAggRowSource}
 * 全链路（{@code AggParser.parse → AggExecutor.execute}）vs 手算真值；TR4.2 隔离目录重复执行
 * 零重建（fingerprint 与文件数稳定）；TR4.3 跨进程 {@link AggResult#fingerprint()} 64-hex 一致
 * （子 JVM spawn）。
 *
 * <p>
 * <b>MVP 边界</b>：服务端 {@code queryIds} 当前按分区本地 ids 集合透传（不执行 DbQuery
 * 谓词过滤），故本测试将「谓词命中」与「列存注册」保持一致（全部行 tag='x'），DB 强一致由 {@code gm.getById} 物化保证。
 */
public class P46_T4_E2eColumnarAggTest {

    /**
     * 测试实体：tag 过滤列 + price 以文本列承载（null 可被真实存储往返，用于 NULL 语义）。
     */
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

    /**
     * 列存条件 + 2 聚合（COUNT/SUM）。
     */
    private static final String DSL = "tag = 'x' AGG(COUNT(price), SUM(price))";

    /**
     * 手算真值：4 行命中（price 150/50/200/null）→ union=4, COUNT=3, SUM=400。
     */
    private static final long EXPECT_UNION = 4L;
    private static final long EXPECT_COUNT = 3L;
    private static final double EXPECT_SUM = 400.0;

    private static final Map<String, ToDoubleFunction<T4Row>> COLS
            = java.util.Collections.singletonMap("price",
                    r -> r.priceTxt == null ? Double.NaN : Double.parseDouble(r.priceTxt));

    private File tmpRoot;
    private String oldHome;
    private File colRoot;
    private GenericManager<T4Row> gm;
    private DsPartitionManager manager;
    private int sid;
    private DsDatabaseClusterClient col;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p46_t4_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(T4Row.class);
        long id1 = save("x", "150");
        long id2 = save("x", "50");
        long id3 = save("x", "200");
        long id4 = save("x", null);

        // 本地列存节点：serverId 取真实实体 id 的高位（单节点全本地，无 P2P 服务端）。
        sid = DsIdLayout.serverId(id1);
        colRoot = new File(tmpRoot, "col");
        manager = new DsPartitionManager(colRoot, DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsHashMapIdAllocator allocator = new DsHashMapIdAllocator(new DsHashMap(new File(tmpRoot, "agg.map")));
        DsServerDirectory directory = new DsServerDirectory() {
            @Override
            public int[] listServerIds() {
                return new int[]{sid};
            }

            @Override
            public Endpoint endpoint(int serverId) {
                return null;
            }
        };
        col = new DsDatabaseClusterClient(sid, manager, allocator, directory);

        for (long id : new long[]{id1, id2, id3, id4}) {
            col.putCol(T4Row.class.getName(), id, "tag", "x".getBytes(StandardCharsets.UTF_8), true);
        }
    }

    private long save(String tag, String priceTxt) {
        T4Row e = new T4Row();
        e.tag = tag;
        e.priceTxt = priceTxt;
        gm.saveOrUpdate(e);
        return e.getId();
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
        Assert.assertEquals("2 聚合项", 2, r.size());
        Assert.assertEquals("union 去重行数", EXPECT_UNION, r.unionRowCount);
        Assert.assertEquals("COUNT(price)", EXPECT_COUNT, r.value(0).count);
        Assert.assertEquals("SUM(price)", EXPECT_SUM, r.value(1).value, 1e-9);
    }

    // ============== TR4.1 真实列存 E2E（AggParser → AggExecutor） ==============
    @Test(timeout = 120000)
    public void tr41_realColumnarEndToEnd() {
        AggQueryPlan plan = AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
        Assert.assertEquals("1 minterm", 1, plan.clause.size());
        Assert.assertEquals("minterm0 含 1 个 ORM 节点", 1, plan.clause.getMiniTerm(0).size());
        Assert.assertEquals("ORM 节点 kind", ConditionNode.Kind.ORM, plan.clause.getMiniTerm(0).get(0).kind);

        AggRowSource<T4Row> src = new ColumnarAggRowSource<>(gm, col, T4Row.class.getName());
        AggResult r = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(r);
    }

    // ============== TR4.2 隔离目录重复执行零重建 ==============
    @Test(timeout = 120000)
    public void tr42_isolatedDirRepeatZeroRebuild() {
        AggQueryPlan plan = AggParser.parse(T4Row.class, DSL, Map.of(), Map.of(), 256, 8);
        AggRowSource<T4Row> src = new ColumnarAggRowSource<>(gm, col, T4Row.class.getName());

        AggResult first = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(first);
        int filesAfterFirst = countFiles(colRoot);

        AggResult second = new AggExecutor<T4Row>().execute(plan, src, COLS);
        assertTruth(second);
        int filesAfterSecond = countFiles(colRoot);

        Assert.assertEquals("重复执行 fingerprint 稳定（零重建）",
                first.fingerprint(), second.fingerprint());
        Assert.assertEquals("重复执行列存目录文件数不增长（零重建）",
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
                    P46_T4_E2eColumnarAggTest.class.getName(),
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
        rs.add(new T4Row(1L, "x", "150"));
        rs.add(new T4Row(2L, "x", "50"));
        rs.add(new T4Row(3L, "x", "200"));
        rs.add(new T4Row(4L, "x", null));
        return rs;
    }

    /**
     * 内存行源：ORM 节点按 {@code tag='x'} 过滤（AND 语义，与真实列存注册一致）。
     */
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
            AggQueryPlan p = AggParser.parse(T4Row.class, args[0], Map.of(), Map.of(), 256, 8);
            AggResult r = new AggExecutor<T4Row>().execute(p, memSource(), COLS);
            System.out.println(r.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }
}
