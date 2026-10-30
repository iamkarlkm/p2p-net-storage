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

import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionHook;
import com.q3lives.ds.database.fulltext.autocompact.AutoCompactionPolicy;
import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P48 T6：NFR 4 TR 0F0E。
 *
 * <p>
 * TR6.1 未触发态 {@code hook.append} 开销不显著劣于裸 {@code store.append}（均值/P95 ≤ 裸 + 松弛量）；
 * <br>TR6.2 autocompact 3 类 Javadoc 均 ≥10 行；TR6.3 CPD 160 字符窗口 0 复制块（autocompact 3 类
 * vs sched 3 类）；<br>TR6.4 0 {@code println}/{@code printStackTrace} + IAE 守卫 ≥5（FR-5 鲁棒性）。
 */
public class P48_T6_NFR_Test {

    private static final int ITER = 150;
    private static final int WARMUP = 20;
    /** 未触发态允许的绝对松弛量（纳秒）：吸收 list() 读 manifest 的固定开销与调度抖动。 */
    private static final long SLACK_NANOS = 10_000_000L;

    private static final String AUTO_SRC = "src/main/java/com/q3lives/ds/database/fulltext/autocompact";
    private static final String SCHED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/sched";

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p48t6-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    // ============== TR6.1 未触发态开销 ≈ 裸 append ==============
    @Test(timeout = 120000)
    public void tr61_untriggeredAppendOverheadWithinSlack() throws Exception {
        File bh = new File(root, "hook");
        File bb = new File(root, "bare");
        bh.mkdirs();
        bb.mkdirs();
        DsIndexSegmentStore hookStore = new DsIndexSegmentStore(bh.getAbsolutePath());
        DsIndexSegmentStore bareStore = new DsIndexSegmentStore(bb.getAbsolutePath());

        int total = WARMUP + ITER;
        DsIndexSegment[] hookSegs = new DsIndexSegment[total];
        DsIndexSegment[] bareSegs = new DsIndexSegment[total];
        long[] ids = new long[total];
        for (int k = 0; k < total; k++) {
            ids[k] = 1000L + k;
            hookSegs[k] = writeSmall(hookStore, ids[k], k & 0x3F);
            bareSegs[k] = writeSmall(bareStore, ids[k], k & 0x3F);
        }

        AutoCompactionHook hook = new AutoCompactionHook(hookStore, CompactionSchedConfig.defaults(),
                new AutoCompactionPolicy(1_000_000, 1_000_000));

        for (int k = 0; k < WARMUP; k++) {
            hook.append(ids[k], hookSegs[k]);
            bareStore.append(ids[k], bareSegs[k]);
        }
        long[] hs = new long[ITER];
        long[] bs = new long[ITER];
        for (int k = 0; k < ITER; k++) {
            int i = WARMUP + k;
            long t0 = System.nanoTime();
            hook.append(ids[i], hookSegs[i]);
            hs[k] = System.nanoTime() - t0;

            long t1 = System.nanoTime();
            bareStore.append(ids[i], bareSegs[i]);
            bs[k] = System.nanoTime() - t1;
        }
        double hookMean = avg(hs);
        double bareMean = avg(bs);
        long hookP95 = p95(hs);
        long bareP95 = p95(bs);
        Assert.assertEquals("未触发态不得触发任何 tick", 0L, hook.stats().ticks());

        Assert.assertTrue("TR6.1 hook 均值 = " + (long) hookMean + "ns 应 ≤ 裸 " + (long) bareMean
                + "ns + " + SLACK_NANOS + "ns (P95 hook=" + hookP95 + "ns bare=" + bareP95 + "ns)",
                hookMean <= bareMean + SLACK_NANOS);
        Assert.assertTrue("TR6.1 hook P95 = " + hookP95 + "ns 应 ≤ 裸 " + bareP95 + "ns + " + SLACK_NANOS + "ns",
                hookP95 <= bareP95 + SLACK_NANOS);
        hook.close();
    }

    // ============== TR6.2 autocompact 3 类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr62_threeClassesJavadocGte10Lines() throws Exception {
        for (String name : new String[]{
            "AutoCompactionPolicy", "AutoCompactionHook", "AutoCompactionStats"}) {
            Path p = new File(System.getProperty("user.dir"), AUTO_SRC + "/" + name + ".java").toPath();
            Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
            int jd = countJavadocLines(p);
            Assert.assertTrue("TR6.2 " + name + " Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
        }
    }

    // ============== TR6.3 CPD 160 字符窗口 0 复制块 ==============
    @Test(timeout = 30000)
    public void tr63_cpd160WindowZeroDuplicateBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String rel : new String[]{AUTO_SRC, SCHED_SRC}) {
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
        collectJavaFiles(new File(System.getProperty("user.dir"), AUTO_SRC), files);
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

    /** 写一个小段文件（不登记），返回其描述对象。 */
    private static DsIndexSegment writeSmall(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        w.addDocStat(1L + seed, six(1 + seed), six(1));
        w.addTermStat(100L + seed, 1.5 + seed, new byte[]{(byte) (seed + 1)});
        w.addTf(500L + seed, 1 + seed);
        return w.finish();
    }

    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
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
