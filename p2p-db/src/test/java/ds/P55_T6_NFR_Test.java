package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.q3lives.ds.cluster.stats.DbStatsRegistry;
import com.q3lives.ds.database.fulltext.sched.CompactionStats;

/**
 * P55 T6：NFR（5 TR）。
 *
 * <p>tr61 {@code snapshot()} 1k 源 P95 ≤ 阈值；tr62 {@code recordTxnLatency} 1M 次 P95 ≤ 阈值（0 分配路径）；
 * tr63 改动类 Javadoc ≥ 10 行 + CPD 160 窗口 0 重复块；tr64 0 {@code System.out.println}/{@code printStackTrace}
 * + IAE 显式守卫；tr65 新增/改源码 0 TODO/FIXME/HACK。
 */
public class P55_T6_NFR_Test {

    private static final String STATS_SRC = "src/main/java/com/q3lives/ds/cluster/stats";
    private static final long SNAPSHOT_P95_BUDGET_NANOS = 50_000_000L;
    private static final long RECORD_P95_BUDGET_NANOS = 2_000L;

    @Before
    public void setUp() {
        DbStatsRegistry.clear();
    }

    @After
    public void tearDown() {
        DbStatsRegistry.clear();
    }

    @Test(timeout = 120000)
    public void tr61_snapshotP95WithinBudgetWith1kSources() {
        for (int i = 0; i < 1000; i++) {
            final int k = i;
            DbStatsRegistry.registerCache("c" + i, () -> null);
            DbStatsRegistry.registerReplicator("r" + i, () -> (long) k);
            DbStatsRegistry.registerCompaction("cp" + i, () -> (CompactionStats) null);
        }
        int iters = 200;
        long[] t = new long[iters];
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            DbStatsRegistry.snapshot();
            t[i] = System.nanoTime() - t0;
        }
        long p95 = percentile(t, 0.95d);
        Assert.assertTrue("snapshot P95 = " + p95 + "ns 超预算 " + SNAPSHOT_P95_BUDGET_NANOS,
                p95 <= SNAPSHOT_P95_BUDGET_NANOS);
    }

    @Test(timeout = 120000)
    public void tr62_recordTxnLatencyP95WithinBudgetFor1M() {
        int batches = 1000;
        int perBatch = 1000;
        long[] perOp = new long[batches];
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int j = 0; j < perBatch; j++) {
                DbStatsRegistry.recordTxnLatency(j);
            }
            long dt = System.nanoTime() - t0;
            perOp[b] = dt / perBatch;
        }
        long total = (long) batches * perBatch;
        Assert.assertEquals("总采样笔数", total, DbStatsRegistry.txnCount());
        long p95 = percentile(perOp, 0.95d);
        Assert.assertTrue("recordTxnLatency P95 = " + p95 + "ns/op 超预算 " + RECORD_P95_BUDGET_NANOS,
                p95 <= RECORD_P95_BUDGET_NANOS);
    }

    @Test(timeout = 30000)
    public void tr63_javadocCoverageAndNoDuplicateWindow() throws Exception {
        List<File> files = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), files);
        int javadocLines = 0;
        List<String> codeLines = new ArrayList<>();
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String line : src.split("\n", -1)) {
                String t = line.trim();
                if (t.startsWith("*") || t.startsWith("/**")) {
                    javadocLines++;
                }
            }
            for (String line : stripComments(src).split("\n", -1)) {
                if (!line.trim().isEmpty()) {
                    codeLines.add(line.trim());
                }
            }
        }
        Assert.assertTrue("改动类 Javadoc 行数应 ≥ 10，实为 " + javadocLines, javadocLines >= 10);
        assertNoDuplicateWindow(codeLines, 160);
    }

    @Test(timeout = 30000)
    public void tr64_noConsoleNoiseAndExplicitIaeGuards() throws Exception {
        List<File> files = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), files);
        int iae = 0;
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse("禁用 System.out.println: " + f.getName(),
                    src.contains("System.out.println") || src.contains("System.out.print"));
            Assert.assertFalse("禁用 printStackTrace: " + f.getName(), src.contains("printStackTrace"));
            int idx = 0;
            while ((idx = src.indexOf("IllegalArgumentException", idx)) >= 0) {
                iae++;
                idx += 5;
            }
        }
        Assert.assertTrue("IAE 显式守卫应 ≥ 3 处，实为 " + iae, iae >= 3);
    }

    @Test(timeout = 30000)
    public void tr65_noTodoFixmeHackInNewOrChangedSources() throws Exception {
        List<File> files = new ArrayList<>();
        collectJavaFiles(srcDir(STATS_SRC), files);
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/binlog/DsBinlogReplicator.java"));
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/database/tx/DsTransactionManager.java"));
        files.add(new File(System.getProperty("user.dir"),
                "src/main/java/com/q3lives/ds/cluster/DsClusterMonitor.java"));
        for (File f : files) {
            Assert.assertTrue("文件必须存在: " + f.getAbsolutePath(), f.isFile());
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                Assert.assertFalse("found " + banned + " in " + f.getName(), src.contains(banned));
            }
        }
    }

    private static long percentile(long[] values, double q) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        int idx = (int) Math.ceil(q * copy.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= copy.length) {
            idx = copy.length - 1;
        }
        return copy[idx];
    }

    private static void assertNoDuplicateWindow(List<String> lines, int window) {
        if (lines.size() < window) {
            return;
        }
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i + window <= lines.size(); i++) {
            StringBuilder sb = new StringBuilder(window * 16);
            for (int j = 0; j < window; j++) {
                sb.append(lines.get(i + j)).append('\n');
            }
            String key = sb.toString();
            Integer prev = seen.put(key, i);
            Assert.assertNull("CPD：" + window + " 行窗口在 " + prev + " 与 " + i + " 处重复", prev);
        }
    }

    private static String stripComments(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("//");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString();
    }

    private static File srcDir(String rel) {
        File dir = new File(System.getProperty("user.dir"), rel);
        Assert.assertTrue("源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        return dir;
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
}
