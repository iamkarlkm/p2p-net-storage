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

import com.q3lives.ds.database.fulltext.sched.CompactionSchedConfig;
import com.q3lives.ds.database.fulltext.sched.CompactionScheduler;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P47 T6：NFR 4 TR 0F0E。
 *
 * <p>
 * TR6.1 稳态 {@code tick()} 开销可控（无候选轮次均值 ≤ 阈值，采样均值规避 GC/页缓存抖动，P95 一并打印）；
 * <br>TR6.2 sched 3 类 Javadoc 均 ≥10 行；TR6.3 CPD 160 字符窗口 0 复制块（sched 3 类 vs compact 5 类）；
 * <br>TR6.4 0 {@code println}/{@code printStackTrace} + IAE 守卫 ≥5（FR-5 鲁棒性）。
 */
public class P47_T6_NFR_Test {

    private static final int ITER = 200;
    private static final int WARMUP = 50;

    /** 稳态单轮 tick 均值上限（纳秒）；无候选轮只做 list + 排序 + 选层，远低于此值。 */
    private static final long MEAN_LIMIT_NANOS = 50_000_000L;
    /** 稳态单轮 tick P95 上限（纳秒）。 */
    private static final long P95_LIMIT_NANOS = 150_000_000L;

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p47t6-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    // ============== TR6.1 稳态 tick 均值/P95 ≤ 阈值 ==============
    @Test(timeout = 60000)
    public void tr61_steadyTickMeanAndP95WithinLimit() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        // 3 段 < fanIn=4 → 稳态无候选，度量 tick 的固定开销（list + 排序 + 选层）
        for (int i = 0; i < 3; i++) {
            buildWide(store, 10L + i, i);
        }
        CompactionScheduler sched = new CompactionScheduler(store, CompactionSchedConfig.defaults());

        for (int i = 0; i < WARMUP; i++) {
            Assert.assertEquals("稳态无候选应返回 0", 0, sched.tick());
        }
        long[] samples = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            sched.tick();
            samples[i] = System.nanoTime() - t0;
        }
        double mean = avg(samples);
        long p95 = p95(samples);
        Assert.assertTrue("TR6.1 tick 稳态均值 = " + (long) mean + "ns (limit=" + MEAN_LIMIT_NANOS
                + "ns, P95=" + p95 + "ns)", mean <= MEAN_LIMIT_NANOS);
        Assert.assertTrue("TR6.1 tick 稳态 P95 = " + p95 + "ns (limit=" + P95_LIMIT_NANOS + "ns)",
                p95 <= P95_LIMIT_NANOS);
        sched.close();
    }

    // ============== TR6.2 sched 3 类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr62_threeClassesJavadocGte10Lines() throws Exception {
        for (String name : new String[]{
            "CompactionSchedConfig", "CompactionStats", "CompactionScheduler"}) {
            Path p = new File(System.getProperty("user.dir"), SCHED_SRC + "/" + name + ".java").toPath();
            Assert.assertTrue("源文件不存在: " + p, Files.isRegularFile(p));
            int jd = countJavadocLines(p);
            Assert.assertTrue("TR6.2 " + name + " Javadoc 行数 = " + jd + "，必须 ≥10", jd >= 10);
        }
    }

    // ============== TR6.3 CPD 160 字符窗口 0 复制块 ==============
    @Test(timeout = 30000)
    public void tr63_cpd160WindowZeroDuplicateBlock() throws Exception {
        List<String> bodies = new ArrayList<>();
        for (String rel : new String[]{SCHED_SRC, COMPACT_SRC}) {
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
        // 约定（同 P33~P37/P45/P46）：160 字符窗口，命中 ≥3 视为复制块
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
        collectJavaFiles(new File(System.getProperty("user.dir"), SCHED_SRC), files);
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

    private static final String SCHED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/sched";
    private static final String COMPACT_SRC = "src/main/java/com/q3lives/ds/database/fulltext/compact";

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

    /** 构建含 1k termStat 的段（体积远超 4KB 对齐下限，tier ≥ 7）。 */
    private static void buildWide(DsIndexSegmentStore store, long segId, int seed) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        long x = segId * 0x9E3779B97F4A7C15L + 1L;
        for (int i = 0; i < 1024; i++) {
            byte[] payload = new byte[16];
            for (int j = 0; j < payload.length; j++) {
                x ^= x << 13;
                x ^= x >>> 7;
                x ^= x << 17;
                payload[j] = (byte) x;
            }
            w.addTermStat(1000L + i, 1.0 + (i & 0xFF) + seed, payload);
        }
        DsIndexSegment s = w.finish();
        store.append(segId, s);
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
