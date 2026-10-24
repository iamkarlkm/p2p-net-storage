package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactionPlan;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactionPolicy;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactionRunner;
import com.q3lives.ds.database.fulltext.compact.SegmentCompactor;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P40 T4：E2E 段合并 + 崩溃安全 + 跨进程计划指纹，3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link DsIndexSegmentStore} E2E：写 3 段 → {@code selectTiers} → {@code plan} →
 * {@code runner.run} → {@code list()} = 1 段 + 合并前后 {@link SegmentMergeView} 逐条等价
 * <br>TR4.2 崩溃安全注入：{@code append(merged)} 后 {@code remove(sources)} 前中断 → 两段共存下
 * merge view 仍与合并前等价（R9）
 * <br>TR4.3 跨进程 {@code SegmentCompactionPlan.fingerprint()} 一致（子 JVM spawn 复现 64-hex）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P40_T4_E2eCompactionTest {

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p40t4-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR4.1 真实 store E2E 全链路 -------- */

    @Test(timeout = 60000)
    public void tr41_e2ePolicyPlanRunner() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        writeThreeSegments(store);

        long[] ids = store.list();
        Assert.assertArrayEquals(new long[]{10L, 11L, 12L}, ids);

        long[] sizes = {store.segmentFile(10L).length(), store.segmentFile(11L).length(),
                store.segmentFile(12L).length()};
        List<long[]> groups = SegmentCompactionPolicy.selectTiers(ids, sizes, 3, 3);
        Assert.assertEquals("3 段同层触发 1 组", 1, groups.size());
        Assert.assertArrayEquals(new long[]{10L, 11L, 12L}, groups.get(0));

        SegmentMergeView pre = new SegmentMergeView(open(store, 10L, 11L, 12L));
        long mergedId = 13L;
        SegmentCompactionPlan plan = new SegmentCompactionPlan(0, groups.get(0), mergedId);
        DsIndexSegment merged = new SegmentCompactionRunner(store).run(plan);
        Assert.assertNotNull(merged);

        long[] after = store.list();
        Assert.assertArrayEquals("合并后仅剩产物段", new long[]{mergedId}, after);

        try (SegmentMergeView post = new SegmentMergeView(open(store, mergedId))) {
            assertEquivalent(pre, post);
        }
        pre.close();
    }

    /* -------- TR4.2 崩溃安全：append 后 remove 前中断 -------- */

    @Test(timeout = 60000)
    public void tr42_crashSafeAppendBeforeRemove() throws Exception {
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        writeThreeSegments(store);

        SegmentMergeView pre = new SegmentMergeView(open(store, 10L, 11L, 12L));

        List<DsIndexSegmentReader> srcs = open(store, 10L, 11L, 12L);
        long mergedId = 13L;
        DsIndexSegment merged = new SegmentCompactor(store).compact(store.segmentFile(mergedId), srcs);
        store.append(mergedId, merged); // 崩溃点：已 append，尚未 remove 源段
        for (DsIndexSegmentReader r : srcs) {
            r.close();
        }

        long[] mid = store.list();
        Assert.assertArrayEquals("中断点两段（源 + 产物）共存", new long[]{10L, 11L, 12L, mergedId}, mid);

        try (SegmentMergeView midView = new SegmentMergeView(open(store, 10L, 11L, 12L, mergedId))) {
            assertEquivalent(pre, midView);
        }
        pre.close();
    }

    /* -------- TR4.3 跨进程计划指纹 -------- */

    @Test(timeout = 60000)
    public void tr43_crossProcessPlanFingerprint() throws Exception {
        SegmentCompactionPlan plan = new SegmentCompactionPlan(0, new long[]{10L, 11L, 12L}, 13L);
        String localFp = plan.fingerprint();
        Assert.assertEquals(64, localFp.length());

        String[] child = runHelperTwice("0", "10,11,12", "13");
        Assert.assertEquals("子进程0 fingerprint 一致", localFp, child[0]);
        Assert.assertEquals("子进程1 fingerprint 一致", localFp, child[1]);
    }

    /* -------- helpers -------- */

    /** 写 3 个结构等大、键部分重叠的段（保证同 tier 触发）。 */
    private void writeThreeSegments(DsIndexSegmentStore store) throws Exception {
        build(store, 10L, w -> {
            w.addDocStat(1L, six(1), six(1));
            w.addTermStat(100L, 1.5, new byte[]{1});
            w.addTf(500L, 5);
        });
        build(store, 11L, w -> {
            w.addDocStat(1L, six(9), six(9)); // 与 seg10 同键，新段优先
            w.addDocStat(2L, six(2), six(2));
            w.addTermStat(100L, 9.25, new byte[]{9}); // 新段优先
            w.addTermStat(200L, 2.5, new byte[]{2});
            w.addTf(500L, 9);
            w.addTf(600L, 6);
        });
        build(store, 12L, w -> {
            w.addDocStat(3L, six(3), six(3));
            w.addTermStat(300L, 3.5, new byte[]{3});
            w.addTf(700L, 7);
        });
    }

    private static final long[] PROBE_DOCS = {1L, 2L, 3L, 4L};
    private static final long[] PROBE_UIDS = {100L, 200L, 300L, 400L};
    private static final long[] PROBE_TF = {500L, 600L, 700L, 800L};

    private static void assertEquivalent(SegmentMergeView a, SegmentMergeView b) {
        for (long d : PROBE_DOCS) {
            FulltextStore.WeightDocStatRow ra = a.getDocStat(d);
            FulltextStore.WeightDocStatRow rb = b.getDocStat(d);
            if (ra == null) {
                Assert.assertNull("doc " + d, rb);
            } else {
                Assert.assertNotNull("doc " + d, rb);
                Assert.assertArrayEquals("doc " + d + " docLen", ra.docLen, rb.docLen);
                Assert.assertArrayEquals("doc " + d + " nTerms", ra.nTerms, rb.nTerms);
            }
        }
        for (long u : PROBE_UIDS) {
            FulltextStore.WeightTermStatRow ra = a.getTermStat(u);
            FulltextStore.WeightTermStatRow rb = b.getTermStat(u);
            if (ra == null) {
                Assert.assertNull("uid " + u, rb);
            } else {
                Assert.assertNotNull("uid " + u, rb);
                Assert.assertEquals("uid " + u + " idf", 0, Double.compare(ra.idf, rb.idf));
                Assert.assertArrayEquals("uid " + u + " payload", ra.payload, rb.payload);
            }
        }
        for (long k : PROBE_TF) {
            Assert.assertEquals("tf " + k, a.getTf(k), b.getTf(k));
        }
    }

    private static List<DsIndexSegmentReader> open(DsIndexSegmentStore store, long... segIds) throws Exception {
        List<DsIndexSegmentReader> rs = new ArrayList<>(segIds.length);
        for (long id : segIds) {
            rs.add(store.openReader(id));
        }
        return rs;
    }

    private static DsIndexSegment build(DsIndexSegmentStore store, long segId, Consumer<DsIndexSegmentWriter> fill)
            throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        fill.accept(w);
        DsIndexSegment s = w.finish();
        store.append(segId, s);
        return s;
    }

    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
    }

    /**
     * spawn 子 JVM 跑本测试类 main()，捕获 stdout 首行（fingerprint 或 ERROR: ...）。
     */
    private static String[] runHelperTwice(String tier, String idsCsv, String merged) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-Xmx512m", "-Dfile.encoding=UTF-8", "-cp", classpath,
                    P40_T4_E2eCompactionTest.class.getName(), tier, idsCsv, merged);
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String out = "";
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (line.startsWith("Picked up ") || t.isEmpty()) {
                    continue;
                }
                out = t;
                break;
            }
            p.waitFor(20, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            outputs[i] = out;
        }
        return outputs;
    }

    /**
     * 子 JVM 入口：按参数构造计划 → 打印 fingerprint（或 ERROR: ...）。
     */
    public static void main(String[] args) {
        try {
            int tier = Integer.parseInt(args[0]);
            String[] parts = args[1].split(",");
            long[] ids = new long[parts.length];
            for (int i = 0; i < parts.length; i++) {
                ids[i] = Long.parseLong(parts[i]);
            }
            long merged = Long.parseLong(args[2]);
            System.out.println(new SegmentCompactionPlan(tier, ids, merged).fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
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
