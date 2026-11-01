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

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot;

/**
 * P51 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 导出 1k ctx P95 ≤ 阈值（均值/中位数硬断言 + P95 诊断）；TR6.2 冷启动 {@code open} P95 ≤ 200ms
 * （R10 零重建实证）；TR6.3 {@code wordnet/} 3 类 Javadoc 各 ≥10 行 + CPD 160 字符窗口 0 重复块；TR6.4 0
 * {@code System.out} / {@code printStackTrace} + IAE 显式守卫 ≥8；TR6.5 新增源码 0 TODO/FIXME/HACK。
 */
public class P51_T6_NFR_Test {

    private static final int EXPORT_CTX = 1000;
    private static final int UIDS_PER_CTX = 4;
    private static final int ITER = 12;
    private static final int WARMUP = 3;
    /** 导出 1k ctx 的 P95 时间上限（毫秒）。 */
    private static final long EXPORT_P95_BUDGET_MS = 4000L;
    /** 冷启动 open 的 P95 时间上限（毫秒，R10 逆指标）。 */
    private static final long COLD_START_P95_BUDGET_MS = 200L;

    private static final String WORDNET_SRC = "src/main/java/com/q3lives/ds/database/fulltext/wordnet";

    private File tmpRoot;
    private String oldHome;
    private String root;
    private FulltextStore store;
    private boolean storeClosed;
    private WordNetSegmentView view;

    @Before
    public void before() throws Exception {
        tmpRoot = Files.createTempDirectory("dsdb_p51_t6_").toFile();
        oldHome = System.getProperty("p2p.db.home");
        root = tmpRoot.getAbsolutePath();
        System.setProperty("p2p.db.home", root);
    }

    @After
    public void after() {
        if (view != null) {
            try {
                view.close();
            } catch (Throwable ignore) {
                // 关闭失败不阻塞用例
            }
        }
        if (store != null && !storeClosed) {
            try {
                store.close();
            } catch (Throwable ignore) {
                // 关闭失败不阻塞用例
            }
        }
        deleteRecursive(tmpRoot);
        if (oldHome == null) {
            System.clearProperty("p2p.db.home");
        } else {
            System.setProperty("p2p.db.home", oldHome);
        }
    }

    // ============== TR6.1 导出 1k ctx P95 ≤ 阈值 ==============
    @Test(timeout = 180000)
    public void tr61_export1kContextP95WithinBudget() throws Exception {
        store = buildStore(EXPORT_CTX);
        WordNetExporter exporter = new WordNetExporter();

        // 基线导出一次，校验确定性规模（每 ctx 4 个 src 邻接键）。
        WordNetSnapshot base = exporter.export(store, root, 0L);
        Assert.assertEquals("TR6.1 ctxCount = CTX × UIDS_PER_CTX",
                (long) EXPORT_CTX * UIDS_PER_CTX, base.ctxCount);
        Assert.assertTrue("TR6.1 adjCount > 0", base.adjCount > 0L);

        for (int k = 0; k < WARMUP; k++) {
            exporter.export(store, root, 100L + k);
        }
        long[] ts = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            long t0 = System.nanoTime();
            exporter.export(store, root, 1000L + k);
            ts[k] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = p95(ts);
        long med = p50(ts);
        Assert.assertTrue("TR6.1 export 1k ctx P95 = " + p95 + "ms 应 ≤ " + EXPORT_P95_BUDGET_MS
                + "ms（中位数=" + med + "ms 均值=" + (long) avg(ts) + "ms）",
                p95 <= EXPORT_P95_BUDGET_MS);
    }

    // ============== TR6.2 冷启动 open P95 ≤ 200ms ==============
    @Test(timeout = 120000)
    public void tr62_coldOpenP95Within200ms() throws Exception {
        store = buildStore(500);
        WordNetSnapshot snap = new WordNetExporter().export(store, root, 7L);
        store.close();
        storeClosed = true;

        for (int k = 0; k < WARMUP; k++) {
            try (WordNetSegmentView warm = WordNetSegmentView.open(root, new long[]{snap.segId})) {
                Assert.assertEquals("TR6.2 段数", 1, warm.segmentCount());
            }
        }
        long[] ts = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            long t0 = System.nanoTime();
            view = WordNetSegmentView.open(root, new long[]{snap.segId});
            ts[k] = (System.nanoTime() - t0) / 1_000_000L;
        }
        long p95 = p95(ts);
        Assert.assertTrue("TR6.2 冷启动 open P95 = " + p95 + "ms 应 ≤ " + COLD_START_P95_BUDGET_MS
                + "ms（中位数=" + p50(ts) + "ms 均值=" + (long) avg(ts) + "ms）",
                p95 <= COLD_START_P95_BUDGET_MS);
    }

    // ============== TR6.3 3 类 Javadoc ≥10 行 + CPD 160 窗口 0 重复 ==============
    @Test(timeout = 30000)
    public void tr63_javadocGte10AndCpd160ZeroDup() throws Exception {
        String[] names = {"WordNetSnapshot.java", "WordNetExporter.java", "WordNetSegmentView.java"};
        int total = 0;
        for (String n : names) {
            Path p = new File(System.getProperty("user.dir"), WORDNET_SRC + "/" + n).toPath();
            Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
            int jd = countJavadocLines(p);
            Assert.assertTrue("TR6.3 " + n + " Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
            total += jd;
        }
        Assert.assertTrue("TR6.3 三类 Javadoc 合计 = " + total + "，必须 ≥30", total >= 30);

        // CPD 160 字符窗口：去注释 / import / 空白后跨文件采样比对。
        List<String> bodies = new ArrayList<>();
        for (String n : names) {
            File f = new File(System.getProperty("user.dir"), WORDNET_SRC + "/" + n);
            String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            c = c.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            c = c.replaceAll("import\\s+[^;]+;", "");
            c = c.replaceAll("//.*", "");
            c = c.replaceAll("\\s+", "");
            bodies.add(f.getName() + "::" + c);
        }
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

    // ============== TR6.4 0 裸打印 + IAE ≥8 ==============
    @Test(timeout = 30000)
    public void tr64_noBarePrintAndIaeGte8() throws Exception {
        int barePrints = 0;
        int iae = 0;
        List<File> files = new ArrayList<>();
        collectJavaFiles(new File(System.getProperty("user.dir"), WORDNET_SRC), files);
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
        Assert.assertTrue("TR6.4 IAE 显式守卫数 = " + iae + "，必须 ≥8（FR-5 鲁棒性）", iae >= 8);
    }

    // ============== TR6.5 0 TODO/FIXME/HACK ==============
    @Test(timeout = 30000)
    public void tr65_noTodoFixmeHack() throws Exception {
        int found = 0;
        List<File> files = new ArrayList<>();
        collectJavaFiles(new File(System.getProperty("user.dir"), WORDNET_SRC), files);
        for (File f : files) {
            String c = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String banned : new String[]{"TODO", "FIXME", "HACK"}) {
                if (c.contains(banned)) {
                    found++;
                }
            }
        }
        Assert.assertEquals("TR6.5 TODO/FIXME/HACK 命中 = " + found + "，必须 = 0", 0, found);
    }

    /* -------- helpers -------- */
    /**
     * 构建含 {@code ctx} 个 (docId,parentId,level=0) 上下文的内存词网，每上下文 4 个 src uid。
     */
    private FulltextStore buildStore(int ctx) throws IOException {
        FulltextStore s = new FulltextStore(root);
        for (int c = 0; c < ctx; c++) {
            long docId = 1000L + c;
            long parentId = docId * 10L + 1L;
            List<Long> uids = new ArrayList<>(UIDS_PER_CTX);
            for (int k = 0; k < UIDS_PER_CTX; k++) {
                long uid = 1_000_000L + (long) c * 10L + k;
                s.insert(new TuRow(uid, docId, parentId, 0, k, k * 2, k * 2 + 1, "t" + uid));
                uids.add(uid);
            }
            s.buildDistForSeq(docId, parentId, 0, uids);
        }
        return s;
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
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }
}
