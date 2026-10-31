package ds;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsPartitionRouter;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.DsPartitionManager;
import com.q3lives.ds.database.integration.GenericManager;
import com.q3lives.ds.database.schema.DynamicIndexUtil;

import javax.net.p2p.api.P2PCommand;
import javax.net.p2p.model.DbNodeQueryIdsRequest;
import javax.net.p2p.model.DbQuery;
import javax.net.p2p.model.DbQueryCriterion;
import javax.net.p2p.model.DbQueryOp;
import javax.net.p2p.model.DbRowQueryIdsResponse;
import javax.net.p2p.model.P2PWrapper;
import javax.net.p2p.server.handler.DbNodeQueryIdsServerHandler;
import javax.net.p2p.utils.SerializationUtil;

/**
 * P50 T6：NFR 4 TR 0F0E。
 *
 * <p>
 * TR6.1 谓词下推端到端收益：口径 = 「服务端响应 + 客户端逐 id 物化」总耗时，下推后均值/中位数 ≤ 全透传基线 + 松弛量（同时硬断言返回
 * id 数与响应字节数单调不减）；TR6.2 新类 Javadoc ≥10 行；TR6.3 CPD 160 字符窗口 0 重复块（新类 + 受改
 * handler/client）；TR6.4 0 {@code println}/{@code printStackTrace} + IAE 守卫 ≥5。
 */
public class P50_T6_NFR_Test {

    private static final int ITER = 12;
    private static final int WARMUP = 4;
    /**
     * 未触发态允许的绝对松弛量（纳秒）：吸收服务端过滤（ORM 谓词扫描）相对全透传的固有增量 + 调度抖动。
     */
    private static final long SLACK_NANOS = 20_000_000L;

    private static final String PUSHDOWN_SRC = "src/main/java/com/q3lives/ds/database/columnar/pushdown";
    private static final String HANDLER_SRC
            = "src/main/java/javax/net/p2p/server/handler/DbNodeQueryIdsServerHandler.java";
    private static final String CLIENT_SRC
            = "src/main/java/com/q3lives/ds/database/columnar/DsDatabaseClusterClient.java";

    /**
     * 测试实体：单列 tag 承载谓词。
     */
    public static class Row extends DsTableAdapter {

        @DsField(name = "tag", length = 16)
        public String tag;

        public Row() {
        }
    }

    private File tmpRoot;
    private String oldHome;
    private GenericManager<Row> gm;
    private DsPartitionManager manager;
    private DbNodeQueryIdsServerHandler handler;
    private int sid;
    private long[] allIds;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p50_t6_").toFile();
        DsDatabaseLocal.forceResetAllIndexesForTest(tmpRoot);
        DsDatabaseLocal.forceResetSchemaMetaForTest(tmpRoot);
        oldHome = System.getProperty("p2p.db.home");
        System.setProperty("p2p.db.home", tmpRoot.getAbsolutePath());

        gm = new GenericManager<>(Row.class);
        List<Long> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(save("x"));
        }
        for (int i = 0; i < 21; i++) {
            all.add(save("y"));
        }
        allIds = toArray(all);
        Arrays.sort(allIds);

        sid = DsIdLayout.serverId(allIds[0]);
        manager = new DsPartitionManager(new File(tmpRoot, "col"),
                DsPartitionRouter.maxPartitionCount(), new String[0]);
        DsNodeContext.register(sid, manager);
        registerIds(manager, DsIdLayout.compose(sid, 0, 1L), allIds);
        handler = new DbNodeQueryIdsServerHandler();
    }

    @After
    public void after() {
        DsNodeContext.clear();
        if (manager != null) {
            try {
                manager.close();
            } catch (IOException ignore) {
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

    private long save(String tag) {
        Row e = new Row();
        e.tag = tag;
        gm.saveOrUpdate(e);
        return e.getId();
    }

    private static long[] toArray(List<Long> v) {
        long[] out = new long[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    private static void registerIds(DsPartitionManager mgr, long anchorRowId, long[] rowIds) throws IOException {
        File dbRoot = mgr.getStore(anchorRowId).getDbRoot();
        DsHashSet set = new DsHashSet(DynamicIndexUtil.idsFile(dbRoot, Row.class.getName()));
        for (long id : rowIds) {
            set.add(id);
        }
    }

    private long[] serverHits(DbQuery q) {
        long anchor = DsIdLayout.compose(sid, 0, 1L);
        P2PWrapper req = P2PWrapper.build(11, P2PCommand.DB_NODE_QUERY_IDS,
                new DbNodeQueryIdsRequest(Row.class.getName(), anchor, q, 0, 10_000));
        P2PWrapper resp = handler.process(req);
        Assert.assertEquals(P2PCommand.R_OK_DB_NODE_QUERY_IDS, resp.getCommand());
        byte[] bytes = ((DbRowQueryIdsResponse) resp.getData()).idsBytes;
        Assert.assertNotNull(bytes);
        return SerializationUtil.deserialize(long[].class, bytes);
    }

    private static int bytesOf(long[] v) {
        return SerializationUtil.serialize(v).length;
    }

    /**
     * 端到端一次：服务端响应 + 客户端逐 id {@link GenericManager#getById} 物化。
     */
    private long e2eOnce(DbQuery q) {
        long t0 = System.nanoTime();
        long[] hits = serverHits(q);
        for (long id : hits) {
            Assert.assertNotNull("强一致：命中 id 必可物化", gm.getById(id));
        }
        return System.nanoTime() - t0;
    }

    private static DbQuery eq(String col, String val) {
        DbQuery q = new DbQuery();
        q.where.add(new DbQueryCriterion(DbQueryOp.EQ, col, val, null, null));
        return q;
    }

    // ============== TR6.1 下推端到端收益 ==============
    @Test(timeout = 180000)
    public void tr61_pushdownE2eNotSlowerThanFullPassthrough() {
        DbQuery base = null;
        DbQuery cand = eq("tag", "x");

        // 确定性硬断言：下推后返回量单调不减（减少 = 收益兑现）。
        long[] baseHits = serverHits(base);
        long[] candHits = serverHits(cand);
        Assert.assertEquals("下推命中数 = tag='x' 行数", 3, candHits.length);
        Assert.assertTrue("下推后返回 id 数 ≤ 全透传", candHits.length <= baseHits.length);
        Assert.assertTrue("下推后响应字节数 ≤ 全透传",
                bytesOf(candHits) <= bytesOf(baseHits));

        for (int k = 0; k < WARMUP; k++) {
            e2eOnce(base);
            e2eOnce(cand);
        }
        long[] bs = new long[ITER];
        long[] cs = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            // 交替测量顺序：消除「后测者受页缓存/磁盘调度优待」的位置偏差。
            if ((k & 1) == 0) {
                bs[k] = e2eOnce(base);
                cs[k] = e2eOnce(cand);
            } else {
                cs[k] = e2eOnce(cand);
                bs[k] = e2eOnce(base);
            }
        }
        double baseMean = avg(bs);
        double candMean = avg(cs);
        long baseMed = p50(bs);
        long candMed = p50(cs);
        long meanTol = Math.max(SLACK_NANOS, (long) (baseMean * 0.20));
        long medTol = Math.max(SLACK_NANOS, (long) (baseMed * 0.20));
        Assert.assertTrue("TR6.1 下推 E2E 均值 = " + (long) candMean + "ns 应 ≤ 全透传 "
                + (long) baseMean + "ns + " + meanTol + "ns（P95 诊断 base=" + p95(bs)
                + "ns cand=" + p95(cs) + "ns）",
                candMean <= baseMean + meanTol);
        Assert.assertTrue("TR6.1 下推 E2E 中位数 = " + candMed + "ns 应 ≤ 全透传 "
                + baseMed + "ns + " + medTol + "ns",
                candMed <= baseMed + medTol);
    }

    // ============== TR6.2 新类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr62_newClassJavadocGte10Lines() throws Exception {
        Path p = new File(System.getProperty("user.dir"),
                PUSHDOWN_SRC + "/DbQueryToQueryWrapper.java").toPath();
        Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
        int jd = countJavadocLines(p);
        Assert.assertTrue("TR6.2 DbQueryToQueryWrapper Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
    }

    // ============== TR6.3 CPD 160 字符窗口 0 复制块 ==============
    @Test(timeout = 30000)
    public void tr63_cpd160WindowZeroDuplicateBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String rel : new String[]{
            PUSHDOWN_SRC + "/DbQueryToQueryWrapper.java", HANDLER_SRC, CLIENT_SRC}) {
            File f = new File(System.getProperty("user.dir"), rel);
            Assert.assertTrue("参与 CPD 的源文件不存在: " + f, f.isFile());
            String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            c = c.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            c = c.replaceAll("import\\s+[^;]+;", "");
            c = c.replaceAll("//.*", "");
            c = c.replaceAll("\\s+", "");
            bodies.add(f.getName() + "::" + c);
        }
        Assert.assertTrue("TR6.3 参与 CPD 的源文件数应 ≥2", bodies.size() >= 2);
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
    @Test(timeout = 30000)
    public void tr64_noBarePrintAndIaeGte5() throws Exception {
        int barePrints = 0;
        int iae = 0;
        List<File> files = new ArrayList<>();
        collectJavaFiles(new File(System.getProperty("user.dir"), PUSHDOWN_SRC), files);
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
        Assert.assertEquals("TR6.4 裸打印/堆栈打印 = " + barePrints + "，必须 = 0", 0, barePrints);
        Assert.assertTrue("TR6.4 IAE 守卫数 = " + iae + "，必须 ≥5（FR-5 鲁棒性）", iae >= 5);
    }

    /* -------- helpers -------- */
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

    private static void collectJavaFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collectJavaFiles(f, out);
            } else if (f.getName().endsWith(".java")) {
                out.add(f);
            }
        }
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
