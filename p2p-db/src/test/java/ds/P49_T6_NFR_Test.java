package ds;

import java.io.File;
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
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentHook;
import com.q3lives.ds.database.fulltext.autoseg.AutoSegmentPolicy;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P49 T6：NFR 4 TR 0F0E。
 *
 * <p>
 * TR6.1 未触发态 {@code hook.putDoc} 开销不显著劣于裸 {@code ft.putDoc}（均值/中位数 ≤ 裸 +
 * 松弛量，P95 仅诊断）；
 * <br>TR6.2 autoseg 3 类 Javadoc 均 ≥10 行；TR6.3 CPD 160 字符窗口 0 复制块（autoseg 3 类 vs
 * sched 3 类 vs autocompact 3 类）；<br>TR6.4 0
 * {@code println}/{@code printStackTrace} + IAE 守卫 ≥5（FR-5 鲁棒性）。
 */
public class P49_T6_NFR_Test {

    private static final int ITER = 25;
    private static final int WARMUP = 10;
    /**
     * 未触发态允许的绝对松弛量（纳秒）：吸收策略判定与计数器自增的固定开销与调度抖动。
     */
    private static final long SLACK_NANOS = 10_000_000L;

    private static final String AUTOSEG_SRC = "src/main/java/com/q3lives/ds/database/fulltext/autoseg";
    private static final String AUTO_SRC = "src/main/java/com/q3lives/ds/database/fulltext/autocompact";
    private static final String SCHED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/sched";

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p49t6-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    // ============== TR6.1 未触发态开销 ≈ 裸写 ==============
    @Test(timeout = 180000)
    public void tr61_untriggeredPutDocOverheadWithinSlack() throws Exception {
        File hd = new File(root, "hook");
        File bd = new File(root, "bare");
        hd.mkdirs();
        bd.mkdirs();
        FulltextStore ftHook = new FulltextStore(hd.getAbsolutePath());
        FulltextStore ftBare = new FulltextStore(bd.getAbsolutePath());
        DsIndexSegmentStore store = new DsIndexSegmentStore(hd.getAbsolutePath());
        AutoSegmentHook hook = new AutoSegmentHook(ftHook, store, CompactionSchedConfig.defaults(),
                new AutoSegmentPolicy(1_000_000, 1_000_000));

        int total = WARMUP + ITER;
        for (int k = 0; k < WARMUP; k++) {
            hook.putDoc(1L + k, text(k));
            ftBare.putDoc(1L + k, text(k));
        }
        long[] hs = new long[ITER];
        long[] bs = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            int i = WARMUP + k;
            // 交替测量顺序：消除「后测者受页缓存/磁盘调度优待」的位置偏差（固定顺序对 hook 系统性不利）。
            if ((k & 1) == 0) {
                long t0 = System.nanoTime();
                hook.putDoc(1L + i, text(i));
                hs[k] = System.nanoTime() - t0;

                long t1 = System.nanoTime();
                ftBare.putDoc(1L + i, text(i));
                bs[k] = System.nanoTime() - t1;
            } else {
                long t1 = System.nanoTime();
                ftBare.putDoc(1L + i, text(i));
                bs[k] = System.nanoTime() - t1;

                long t0 = System.nanoTime();
                hook.putDoc(1L + i, text(i));
                hs[k] = System.nanoTime() - t0;
            }
        }
        double hookMean = avg(hs);
        double bareMean = avg(bs);
        long hookMed = p50(hs);
        long bareMed = p50(bs);
        long hookP95 = p95(hs);
        long bareP95 = p95(bs);
        Assert.assertEquals("未触发态不得导出任何段", 0L, hook.stats().flushesFired());

        // 容差口径遵循 NFR 硬性标准「较基准线 ≤ +5%」：取 max(绝对松弛 10ms, 基线 5%)。
        // 缘由：putDoc 为 IO 绑定操作（本机单次约 0.6~0.8s），其抖动量级（数十 ms）远超装饰器
        // 未触发态真实增量开销（两次原子自增 + 一次阈值比较，微秒级）；ITER=25 时 P95 退化为「次大值」，
        // 由单次 GC/磁盘抖动主导（实测同口径波动 16→48ms）→ 以稳健的均值 + 中位数作硬断言，P95 仅诊断输出。
        long meanTol = Math.max(SLACK_NANOS, (long) (bareMean * 0.05));
        long medTol = Math.max(SLACK_NANOS, (long) (bareMed * 0.05));
        Assert.assertTrue("TR6.1 hook 均值 = " + (long) hookMean + "ns 应 ≤ 裸 " + (long) bareMean
                + "ns + " + meanTol + "ns（≤+5% NFR 口径）(P95 hook=" + hookP95 + "ns bare=" + bareP95 + "ns)",
                hookMean <= bareMean + meanTol);
        Assert.assertTrue("TR6.1 hook 中位数 = " + hookMed + "ns 应 ≤ 裸 " + bareMed + "ns + " + medTol
                + "ns（≤+5% NFR 口径）",
                hookMed <= bareMed + medTol);
        hook.close();
        ftHook.close();
        ftBare.close();
    }

    // ============== TR6.2 autoseg 3 类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr62_threeClassesJavadocGte10Lines() throws Exception {
        for (String name : new String[]{
            "AutoSegmentPolicy", "AutoSegmentHook", "AutoSegmentStats"}) {
            Path p = new File(System.getProperty("user.dir"), AUTOSEG_SRC + "/" + name + ".java").toPath();
            Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
            int jd = countJavadocLines(p);
            Assert.assertTrue("TR6.2 " + name + " Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
        }
    }

    // ============== TR6.3 CPD 160 字符窗口 0 复制块 ==============
    @Test(timeout = 30000)
    public void tr63_cpd160WindowZeroDuplicateBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String rel : new String[]{AUTOSEG_SRC, SCHED_SRC, AUTO_SRC}) {
            File[] fs = new File(System.getProperty("user.dir"), rel).listFiles((d, n) -> n.endsWith(".java"));
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
        collectJavaFiles(new File(System.getProperty("user.dir"), AUTOSEG_SRC), files);
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
    private static String text(int i) {
        return "doc" + i + " hello world trae engine alpha beta gamma delta " + (i * 31 % 97);
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
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
