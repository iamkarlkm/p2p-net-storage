package ds;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.binlog.DsBinlogReplicator;
import com.q3lives.ds.cluster.DsClusterMonitor;
import com.q3lives.ds.cluster.DsNodeContext;
import com.q3lives.ds.cluster.DsServerDirectory;
import com.q3lives.ds.cluster.stats.DbStats;
import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.core.DsMemory;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;

/**
 * P55 T5：Gatekeeper 9Y（9 TR / 0F0E）。
 *
 * <p>
 * Y1 ZeroLeak3 ≤3（新包 {@code cluster/stats/} = 2）+ 既有锁定包类数守恒；Y2 NG-3 零依赖； Y3 R10
 * 零新线程（去注释判定）；Y4 Facade 单调不减（P21~P54 fulltext 0 改 + {@code DsMemory} public 面 0
 * 改）； Y5 既有 API 0 改；Y6 P21~P55 编译兼容 + 新方法可达；Y7 {@code /sync/api/db-stats}
 * 端点已注册； Y8 注册表并发安全；Y9 0 TODO/FIXME/HACK（含移除 {@code DsClusterMonitor} 既有 TODO）。
 */
public class P55_T5_Gatekeeper9YTest {

    private static final String STATS_REL = "com/q3lives/ds/cluster/stats";
    private static final String STATS_SRC = "src/main/java/com/q3lives/ds/cluster/stats";
    private static final String FT = "src/main/java/com/q3lives/ds/database/fulltext";
    private static final String COL = "src/main/java/com/q3lives/ds/database/columnar";
    private static final String SYNC_MONITOR_REL
            = "p2p-sync/src/main/java/javax/net/p2p/filesync/monitor/P2PSyncMonitorServer.java";

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
        DsClusterMonitor.clear();
        DsNodeContext.clear();
    }

    // ============== Y1 ZeroLeak3 + 既有类数守恒 ==============
    @Test(timeout = 30000)
    public void y1_newPackageWithinThreeAndLegacyCountsIntact() {
        Assert.assertEquals("新包 cluster/stats/ top-level 必须 = 2", 2, countTopLevelJava(STATS_SRC));
        Assert.assertEquals("fulltext 根包 top-level 仍 3", 3, countTopLevelJava(FT));
        Assert.assertEquals("segment/ 仍 5", 5, countTopLevelJava(FT + "/segment"));
        Assert.assertEquals("compact/ 仍 5", 5, countTopLevelJava(FT + "/compact"));
        Assert.assertEquals("layered/ 仍 3", 3, countTopLevelJava(FT + "/layered"));
        Assert.assertEquals("sched/ 仍 3", 3, countTopLevelJava(FT + "/sched"));
        Assert.assertEquals("autocompact/ 仍 3", 3, countTopLevelJava(FT + "/autocompact"));
        Assert.assertEquals("autoseg/ 仍 3", 3, countTopLevelJava(FT + "/autoseg"));
        Assert.assertEquals("wordnet/ 仍 3", 3, countTopLevelJava(FT + "/wordnet"));
        Assert.assertEquals("cluster/agg/ 仍 6", 6, countTopLevelJava(FT + "/cluster/agg"));
        Assert.assertEquals("cluster/groupby/ 仍 5", 5, countTopLevelJava(FT + "/cluster/groupby"));
        Assert.assertEquals("cluster/rowsource/ 仍 3", 3, countTopLevelJava(FT + "/cluster/rowsource"));
        Assert.assertEquals("cluster/parser/v3/ 仍 5", 5, countTopLevelJava(FT + "/cluster/parser/v3"));
        Assert.assertEquals("columnar/pushdown/ 仍 2", 2, countTopLevelJava(COL + "/pushdown"));
        Assert.assertEquals("columnar/aggpushdown/ 仍 1", 1, countTopLevelJava(COL + "/aggpushdown"));
        Assert.assertNotNull("stats 包资源必须存在",
                Thread.currentThread().getContextClassLoader().getResource(STATS_REL));
    }

    // ============== Y2 NG-3 零依赖 ==============
    @Test(timeout = 30000)
    public void y2_zeroForbiddenImportsAndPomBannedZero() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), javaFiles);
        Assert.assertEquals("stats 源文件数应为 2", 2, javaFiles.size());
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String pkg : new String[]{"io.netty", "io.grpc", "javax.net.p2p.client", "p2p.client"}) {
                Assert.assertFalse("forbidden ref " + pkg + " in " + f.getName(), content.contains(pkg));
            }
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (!t.startsWith("import ")) {
                    continue;
                }
                boolean ok = t.startsWith("import java.")
                        || t.contains("com.q3lives.ds.")
                        || t.startsWith("import javax.net.p2p.model.");
                Assert.assertTrue("stats 只允许 JDK + first-party import, got: " + t, ok);
            }
        }
        String pom = new String(Files.readAllBytes(
                new File(System.getProperty("user.dir"), "pom.xml").toPath()),
                StandardCharsets.UTF_8).toLowerCase();
        for (String banned : new String[]{"micrometer", "prometheus", "dropwizard", "metrics-core"}) {
            Assert.assertFalse("pom 不应新增指标库依赖: " + banned, pom.contains(banned));
        }
    }

    // ============== Y3 R10 零新线程 ==============
    @Test(timeout = 30000)
    public void y3_noNewThreadInStatsPackageAfterStripComments() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), javaFiles);
        for (File f : javaFiles) {
            String code = stripComments(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (String banned : new String[]{
                "ExecutorService", "Executors", "new Thread", "Timer", "scheduleAtFixedRate", "ScheduledFuture"}) {
                Assert.assertFalse("R10 violation (" + banned + ") in " + f.getName(), code.contains(banned));
            }
        }
    }

    // ============== Y4 Facade 单调不减 ==============
    @Test(timeout = 30000)
    public void y4_facadeMonotonicFulltextZeroChangeAndDsMemoryPublicUnchanged() throws Exception {
        // DsMemory public 面 0 改：快照入口 + 14 个 CacheStats getter 恒在。
        Assert.assertNotNull("getCacheStats 必须存在", DsMemory.class.getMethod("getCacheStats"));
        Assert.assertNotNull("getAndResetCacheStats 必须存在",
                DsMemory.class.getMethod("getAndResetCacheStats"));
        Assert.assertEquals("CacheStats getter 数应仍为 14", 14,
                countGetters(DsMemory.CacheStats.class));

        // P21~P54 fulltext 锁定包 0 新增/删除类。
        Assert.assertEquals("segment/ 仍 5", 5, countTopLevelJava(FT + "/segment"));
        Assert.assertEquals("compact/ 仍 5", 5, countTopLevelJava(FT + "/compact"));
        Assert.assertEquals("sched/ 仍 3", 3, countTopLevelJava(FT + "/sched"));
        Assert.assertEquals("wordnet/ 仍 3", 3, countTopLevelJava(FT + "/wordnet"));

        // P21 既有签名不得消失。
        Assert.assertNotNull("neighborsWithin 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("neighborsWithin",
                        String.class, long.class, long.class, int.class, int.class));
        Assert.assertNotNull("distanceBetween 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("distanceBetween",
                        String.class, String.class, long.class, long.class, int.class));
        Assert.assertNotNull("topKNeighbors 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("topKNeighbors",
                        String.class, long.class, long.class, int.class, int.class));
        Assert.assertNotNull("ingestDoc 原签名必须保留",
                FulltextStore.class.getDeclaredMethod("ingestDoc", long.class, String.class));

        // 依赖方向单向：P21 FulltextStore 不得引用新包 cluster.stats。
        String fts = new String(Files.readAllBytes(
                new File(System.getProperty("user.dir"),
                        "src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertFalse("FulltextStore 不应依赖 cluster.stats",
                fts.contains("cluster.stats") || fts.contains("DbStatsRegistry") || fts.contains("DbStats"));
    }

    // ============== Y5 既有 API 0 改 ==============
    @Test(timeout = 30000)
    public void y5_existingApiSignatureUnchanged() throws Exception {
        Assert.assertTrue("DsServerDirectory 必须为接口", DsServerDirectory.class.isInterface());
        Assert.assertNotNull("listServerIds 必须保留",
                DsServerDirectory.class.getMethod("listServerIds"));
        Assert.assertNotNull("endpoint 必须保留",
                DsServerDirectory.class.getMethod("endpoint", int.class));
        Assert.assertNotNull("replicas 默认方法必须保留",
                DsServerDirectory.class.getMethod("replicas", int.class));
        Assert.assertEquals("Endpoint 仍 3 分量 record", 3,
                DsServerDirectory.Endpoint.class.getRecordComponents().length);

        Assert.assertEquals("DsClusterMonitor.snapshotMap 返回 Map", Map.class,
                method(DsClusterMonitor.class, "snapshotMap").getReturnType());
        Assert.assertNotNull("registerDirectory 必须保留",
                DsClusterMonitor.class.getMethod("registerDirectory", DsServerDirectory.class));
        Assert.assertNotNull("getDirectory 必须保留", DsClusterMonitor.class.getMethod("getDirectory"));
        Assert.assertNotNull("clear 必须保留", DsClusterMonitor.class.getMethod("clear"));
        Assert.assertEquals("snapshotMap 仍 0 参", 0,
                method(DsClusterMonitor.class, "snapshotMap").getParameterCount());
    }

    // ============== Y6 P21~P55 编译兼容 + 新方法可达 ==============
    @Test(timeout = 30000)
    public void y6_allClassesPresentAndCompileCompatible() throws Exception {
        String[] classes = {
            "com.q3lives.ds.cluster.stats.DbStats",
            "com.q3lives.ds.cluster.stats.DbStatsRegistry",
            "com.q3lives.ds.binlog.DsBinlogReplicator",
            "com.q3lives.ds.cluster.DsClusterMonitor",
            "com.q3lives.ds.cluster.DsServerDirectory",
            "com.q3lives.ds.core.DsMemory",
            "com.q3lives.ds.database.DsDatabaseLocal",
            "com.q3lives.ds.database.tx.DsTransactionManager",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.sched.CompactionScheduler",
            "com.q3lives.ds.database.fulltext.wordnet.WordNetExporter"
        };
        for (String c : classes) {
            try {
                Class.forName(c);
            } catch (ClassNotFoundException cnfe) {
                Assert.fail("class should exist: " + c);
            }
        }

        Assert.assertNotNull("replicationLag 必须可达",
                DsBinlogReplicator.class.getMethod("replicationLag"));
        Assert.assertEquals("replicationLag 返回 long", long.class,
                DsBinlogReplicator.class.getMethod("replicationLag").getReturnType());
        Assert.assertNotNull("snapshot 必须可达", DbStatsRegistry.class.getMethod("snapshot"));
        Assert.assertNotNull("registerReplicator 必须可达",
                DbStatsRegistry.class.getMethod("registerReplicator", String.class, Supplier.class));
        Assert.assertNotNull("registerCache 必须可达",
                DbStatsRegistry.class.getMethod("registerCache", String.class, Supplier.class));
        Assert.assertNotNull("registerCompaction(2参) 必须可达",
                DbStatsRegistry.class.getMethod("registerCompaction", String.class, Supplier.class));
        Assert.assertNotNull("registerCompaction(3参) 必须可达",
                DbStatsRegistry.class.getMethod("registerCompaction", String.class, long.class, Supplier.class));
        Assert.assertNotNull("recordTxnLatency 必须可达",
                DbStatsRegistry.class.getMethod("recordTxnLatency", long.class));
        Assert.assertNotNull("toMap 必须可达", DbStats.class.getMethod("toMap"));
        Assert.assertNotNull("DbStats 仍 9 参构造", DbStats.class.getConstructor(
                double.class, int.class, long.class, long.class, long.class, long.class,
                double.class, long.class, long.class));
    }

    // ============== Y7 端点注册 ==============
    @Test(timeout = 30000)
    public void y7_dbStatsEndpointRegistered() throws Exception {
        String src = new String(Files.readAllBytes(repoFile(SYNC_MONITOR_REL).toPath()),
                StandardCharsets.UTF_8);
        Assert.assertTrue("必须注册 /sync/api/db-stats context",
                src.contains("createContext(\"/sync/api/db-stats\""));
        Assert.assertTrue("必须有同构内嵌 DbStatsHandler",
                src.contains("class DbStatsHandler implements HttpHandler"));
        Assert.assertTrue("handler 必须调用 DbStatsRegistry.snapshot()",
                src.contains("DbStatsRegistry.snapshot().toMap()"));
    }

    // ============== Y8 注册表并发安全 ==============
    @Test(timeout = 60000)
    public void y8_registryConcurrencySafe() throws Exception {
        List<File> javaFiles = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), javaFiles);
        String all = "";
        for (File f : javaFiles) {
            all += new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        Assert.assertTrue("注册源容器须用 ConcurrentHashMap", all.contains("ConcurrentHashMap"));
        Assert.assertTrue("采样器须用 AtomicLongArray", all.contains("AtomicLongArray"));

        final int threads = 4;
        final int perThread = 1000;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    DbStatsRegistry.recordTxnLatency(j);
                    if ((j & 0xFF) == 0) {
                        DbStatsRegistry.snapshot();
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        Assert.assertEquals("并发写入计数须精确",
                (long) threads * perThread, DbStatsRegistry.txnCount());
        Assert.assertTrue("p99 必须为非负有限值", DbStatsRegistry.txnLatencyP99Nanos() >= 0L);
    }

    // ============== Y9 0 TODO/FIXME/HACK ==============
    @Test(timeout = 30000)
    public void y9_noTodoFixmeHack() throws Exception {
        List<File> files = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), files);
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/binlog/DsBinlogReplicator.java"));
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/tx/DsTransactionManager.java"));
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/cluster/DsClusterMonitor.java"));
        files.add(repoFile(SYNC_MONITOR_REL));
        for (File f : files) {
            Assert.assertTrue("文件必须存在: " + f.getAbsolutePath(), f.isFile());
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), content.contains(banned));
            }
        }
    }

    // ============== helpers ==============
    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static Method method(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        Assert.fail("method not found: " + c.getSimpleName() + "." + name);
        return null;
    }

    private static int countGetters(Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().startsWith("get") && m.getParameterCount() == 0
                    && !Modifier.isStatic(m.getModifiers())) {
                n++;
            }
        }
        return n;
    }

    private static File srcDir(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
    }

    private static File repoFile(String rel) {
        File cur = new File(System.getProperty("user.dir"));
        for (int i = 0; i < 4 && cur != null; i++) {
            File f = new File(cur, rel);
            if (f.isFile()) {
                return f;
            }
            cur = cur.getParentFile();
        }
        Assert.fail("repo file not found: " + rel);
        return null;
    }

    private static int countTopLevelJava(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] children = dir.listFiles();
        int n = 0;
        if (children != null) {
            for (File f : children) {
                if (f.isFile() && f.getName().endsWith(".java")) {
                    n++;
                }
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

    @SuppressWarnings("unused")
    private static Set<String> names(Method[] ms) {
        Set<String> s = new HashSet<>();
        for (Method m : ms) {
            s.add(m.getName());
        }
        return s;
    }

    @SuppressWarnings("unused")
    private static List<String> asList(String... items) {
        return Arrays.asList(items);
    }
}
