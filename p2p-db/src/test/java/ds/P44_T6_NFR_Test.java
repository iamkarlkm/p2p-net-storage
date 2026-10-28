package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P44 T6：NFR，5 TR 0F0E。
 *
 * <p>
 * TR6.1 内存收益：{@code weightStatsMappedBytesForTest()} 驱逐前 &gt; 驱逐后（且驱逐态 search 后不回升）
 * <br>TR6.2 驱逐态 {@code search} P95 ≤ 阈值（相对未驱逐基线容差 ≤ +5% / 绝对阈值）
 * <br>TR6.3 {@code evict}/{@code rehydrate} O(1)（1e5 次循环有界）
 * <br>TR6.4 P44 增量区 Javadoc ≥ 10 行 + CPD 160 字符窗口 0 重复块 + 0 {@code System.out}/{@code err}/
 * {@code printStackTrace}
 * <br>TR6.5 P44 增量区新增 IAE 显式守卫 ≥ 1（实装 1 条真实前置；拒绝为凑数追加无意义防御，见 spec §10 D1）
 */
public class P44_T6_NFR_Test {

    private static final String STORE_FILE = "src/main/java/com/q3lives/ds/database/fulltext/FulltextStore.java";
    private static final String P44_START = "P44 T1-T6 WeightStore stat 驱逐";
    private static final String P44_END = "P44 增量区结束";

    // ============== TR6.1 内存收益（实度量） ==============

    @Test(timeout = 240000)
    public void tr61_MemoryBenefitMeasured() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p44t6-mem-" + System.nanoTime());
        root.mkdirs();
        FulltextStore ft = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        try {
            for (int i = 0; i < 500; i++) {
                ft.ingestDoc(1000 + i, "alpha beta gamma w" + (i % 50));
            }
            List<Long> uids = new ArrayList<>();
            for (String t : new String[]{"alpha", "beta", "gamma"}) {
                long u = ft.termDictToUidForTest(t, false);
                if (u > 0L) {
                    uids.add(u);
                }
            }
            ft.recomputeIdfForTerms(uids);
            // 预热一次点查，确保三表已有映射窗口
            ft.getDocStatForTest(1000L);

            long before = ft.weightStatsMappedBytesForTest();
            Assert.assertTrue("驱逐前 3 张 stat 表应有映射字节, got " + before, before > 0L);

            new LayeredIndexExporter().export(ft, segStore, 10L);
            try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
                ft.attachLayeredView(view);
                ft.evictLayeredStats();
                long after = ft.weightStatsMappedBytesForTest();
                Assert.assertTrue("驱逐后映射字节应显著下降: before=" + before + " after=" + after,
                        after <= before / 2L);

                // 驱逐态 search 不应重新映射 3 张 stat 表（收益不回吐）
                ft.search("alpha beta", 10, SearchOptions.defaults());
                long post = ft.weightStatsMappedBytesForTest();
                Assert.assertTrue("驱逐态 search 后不应回升: after=" + after + " post=" + post, post <= after);
                ft.detachLayeredView();
            }
        } finally {
            try {
                ft.close();
            } catch (Exception ignored) {
                // 清理
            }
            try {
                segStore.close();
            } catch (Exception ignored) {
                // 清理
            }
            deleteRecursive(root);
        }
    }

    // ============== TR6.2 驱逐态 search P95 ==============

    @Test(timeout = 240000)
    public void tr62_EvictedSearchP95WithinThreshold() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p44t6-p95-" + System.nanoTime());
        root.mkdirs();
        FulltextStore ft = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        try {
            for (int i = 0; i < 1000; i++) {
                ft.ingestDoc(1000 + i, "alpha beta gamma w" + (i % 50));
            }
            List<Long> uids = new ArrayList<>();
            for (String t : new String[]{"alpha", "beta", "gamma"}) {
                long u = ft.termDictToUidForTest(t, false);
                if (u > 0L) {
                    uids.add(u);
                }
            }
            ft.recomputeIdfForTerms(uids);

            SearchOptions opts = SearchOptions.defaults();
            long baseP95 = p95(ft, opts, 5, 15);
            new LayeredIndexExporter().export(ft, segStore, 10L);
            long evP95;
            try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
                ft.attachLayeredView(view);
                ft.evictLayeredStats();
                evP95 = p95(ft, opts, 5, 15);
                ft.detachLayeredView();
            }
            Assert.assertTrue("驱逐态 search P95 应 ≤ 2000ms, got " + evP95 + "ms", evP95 <= 2000L);
            Assert.assertTrue("驱逐态 search P95 相对基线退化应 ≤ +5% / 25ms 容差, base=" + baseP95
                    + "ms ev=" + evP95 + "ms", evP95 <= baseP95 + Math.max(1L, baseP95 / 20L) + 25L);
        } finally {
            try {
                ft.close();
            } catch (Exception ignored) {
                // 清理
            }
            try {
                segStore.close();
            } catch (Exception ignored) {
                // 清理
            }
            deleteRecursive(root);
        }
    }

    // ============== TR6.3 evict/rehydrate O(1) ==============

    @Test(timeout = 120000)
    public void tr63_EvictRehydrateIsO1() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p44t6-o1-" + System.nanoTime());
        root.mkdirs();
        FulltextStore ft = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        try {
            new LayeredIndexExporter().export(ft, segStore, 10L);
            try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
                ft.attachLayeredView(view);
                int cycles = 100_000;
                long max1 = 0L;
                long t0 = System.nanoTime();
                for (int i = 0; i < cycles; i++) {
                    long s = System.nanoTime();
                    ft.evictLayeredStats();
                    ft.rehydrateLayeredStats();
                    long d = System.nanoTime() - s;
                    if (d > max1) {
                        max1 = d;
                    }
                }
                long totalMs = (System.nanoTime() - t0) / 1_000_000L;
                Assert.assertTrue("1e5 次 evict/rehydrate 总耗时应 ≤ 5000ms, got " + totalMs + "ms", totalMs <= 5000L);
                Assert.assertTrue("单次 evict/rehydrate 应远小于 50ms, got " + (max1 / 1_000_000L) + "ms",
                        max1 <= 50_000_000L);
                Assert.assertFalse("循环结束应为未驱逐态", ft.isLayeredStatsEvicted());
                ft.detachLayeredView();
            }
        } finally {
            try {
                ft.close();
            } catch (Exception ignored) {
                // 清理
            }
            try {
                segStore.close();
            } catch (Exception ignored) {
                // 清理
            }
            deleteRecursive(root);
        }
    }

    // ============== TR6.4 Javadoc ≥ 10 行 + CPD 0 重复块 + 0 println ==============

    @Test(timeout = 30000)
    public void tr64_JavadocCpdAndNoPrint() throws Exception {
        String region = incrementRegion();

        int docLines = 0;
        for (String line : region.split("\n")) {
            String t = line.trim();
            if (t.startsWith("*") || t.startsWith("/**")) {
                docLines++;
            }
        }
        Assert.assertTrue("P44 增量区 Javadoc 行数应 ≥ 10, got " + docLines, docLines >= 10);

        Assert.assertFalse("P44 增量区不应有 System.out", region.contains("System.out"));
        Assert.assertFalse("P44 增量区不应有 System.err", region.contains("System.err"));
        Assert.assertFalse("P44 增量区不应有 printStackTrace", region.contains("printStackTrace"));

        int window = 160;
        Map<String, Integer> seen = new HashMap<>();
        List<String> norm = normalizedCodeLines(region);
        for (int i = 0; i < norm.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < norm.size() && sb.length() < window; j++) {
                sb.append(norm.get(j)).append('\n');
            }
            if (sb.length() < window) {
                continue;
            }
            String key = sb.toString();
            if (seen.containsKey(key)) {
                Assert.fail("检测到 ≥" + window + " 字符重复代码块：\n" + key);
            }
            seen.put(key, 1);
        }
    }

    // ============== TR6.5 新增 IAE 显式守卫下限 ==============

    @Test(timeout = 30000)
    public void tr65_IaeFloor() throws Exception {
        String region = incrementRegion();
        int iae = 0;
        int from = 0;
        while (true) {
            int k = region.indexOf("throw new IllegalArgumentException", from);
            if (k < 0) {
                break;
            }
            iae++;
            from = k + 1;
        }
        // 实装 1 条（未挂载视图 evict 拒绝）；拒绝为凑数追加无意义防御（spec §10 D1）
        Assert.assertTrue("P44 增量区 IAE 显式守卫应 ≥ 1, got " + iae, iae >= 1);
    }

    /* ================ helpers ================ */

    private static long p95(FulltextStore ft, SearchOptions opts, int warmup, int n) throws Exception {
        for (int i = 0; i < warmup; i++) {
            ft.search("alpha beta", 10, opts);
        }
        long[] dur = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            ft.search("alpha beta", 10, opts);
            dur[i] = System.nanoTime() - t0;
        }
        Arrays.sort(dur);
        return dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
    }

    private static String incrementRegion() throws Exception {
        File f = new File(System.getProperty("user.dir"), STORE_FILE);
        Assert.assertTrue("file missing: " + f.getAbsolutePath(), f.isFile());
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int s = content.indexOf(P44_START);
        int e = content.indexOf(P44_END);
        Assert.assertTrue("P44 增量区起点缺失", s > 0);
        Assert.assertTrue("P44 增量区终点缺失", e > s);
        return content.substring(s, e);
    }

    private static List<String> normalizedCodeLines(String content) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("/*")) {
                inBlock = true;
            }
            if (inBlock) {
                if (t.contains("*/")) {
                    inBlock = false;
                }
                continue;
            }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("@")) {
                continue;
            }
            out.add(t);
        }
        return out;
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
