package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.compact.SegmentCompactor;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P40 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 merge view 查询 P95 ≤ 510ms（3 段 × 1k 键 × 3 类点查）
 * <br>TR6.2 compact 3 段 P95 ≤ 2000ms
 * <br>TR6.3 5 类 Javadoc ≥ 10 行
 * <br>TR6.4 CPD 0 重复块（160 字符窗口）
 * <br>TR6.5 0 System.out/System.err/printStackTrace + IAE 显式守卫 ≥ 8
 */
public class P40_T6_NFR_Test {

    private static final String COMPACT_SRC = "src/main/java/com/q3lives/ds/database/fulltext/compact";

    // ============== TR6.1 merge view 查询 P95 ==============
    @Test(timeout = 120000)
    public void tr61_MergeViewQueryP95WithinThreshold() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p40t6-query-" + System.nanoTime());
        root.mkdirs();
        try {
            DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
            buildLarge(store, 10L, 0);
            buildLarge(store, 11L, 500);
            buildLarge(store, 12L, 1000);

            List<DsIndexSegmentReader> rs = new ArrayList<>();
            rs.add(store.openReader(10L));
            rs.add(store.openReader(11L));
            rs.add(store.openReader(12L));
            SegmentMergeView view = new SegmentMergeView(rs);
            try {
                for (int i = 0; i < 5; i++) {
                    probe(view, 3000);
                }
                int n = 40;
                long[] dur = new long[n];
                for (int i = 0; i < n; i++) {
                    long t0 = System.nanoTime();
                    probe(view, 3000);
                    dur[i] = System.nanoTime() - t0;
                }
                Arrays.sort(dur);
                long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
                Assert.assertTrue("merge view 查询 P95 应 ≤ 510ms, got " + p95Ms + "ms", p95Ms <= 510L);
            } finally {
                view.close();
            }
        } finally {
            deleteRecursive(root);
        }
    }

    private static void probe(SegmentMergeView view, int keys) {
        for (long k = 0; k < keys; k++) {
            view.getTf(k);
            view.getTermStat(k);
            view.getDocStat(k);
        }
    }

    // ============== TR6.2 compact 3 段 P95 ==============
    @Test(timeout = 120000)
    public void tr62_CompactP95WithinThreshold() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p40t6-compact-" + System.nanoTime());
        root.mkdirs();
        try {
            DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
            buildLarge(store, 10L, 0);
            buildLarge(store, 11L, 500);
            buildLarge(store, 12L, 1000);
            SegmentCompactor compactor = new SegmentCompactor(store);

            for (int i = 0; i < 3; i++) {
                compactOnce(store, compactor, 13L);
            }
            int n = 20;
            long[] dur = new long[n];
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                DsIndexSegment merged = compactOnce(store, compactor, 13L);
                dur[i] = System.nanoTime() - t0;
                Assert.assertEquals("合并后 docCount", 2000L, merged.docCount);
            }
            Arrays.sort(dur);
            long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
            Assert.assertTrue("compact 3 段 P95 应 ≤ 2000ms, got " + p95Ms + "ms", p95Ms <= 2000L);
        } finally {
            deleteRecursive(root);
        }
    }

    private static DsIndexSegment compactOnce(DsIndexSegmentStore store, SegmentCompactor compactor, long target)
            throws Exception {
        List<DsIndexSegmentReader> rs = new ArrayList<>();
        rs.add(store.openReader(10L));
        rs.add(store.openReader(11L));
        rs.add(store.openReader(12L));
        try {
            return compactor.compact(store.segmentFile(target), rs);
        } finally {
            for (DsIndexSegmentReader r : rs) {
                r.close();
            }
        }
    }

    /**
     * 构造含 1k 记录的段：docId / uid / tf key 均在 [base, base+1000)。
     */
    private static void buildLarge(DsIndexSegmentStore store, long segId, int base) throws Exception {
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(segId));
        for (int i = 0; i < 1000; i++) {
            long key = base + i;
            w.addDocStat(key, six(i + 1), six(3));
            w.addTermStat(key, 1.0 + i * 1e-3, new byte[]{(byte) (i & 0xFF)});
            w.addTf(key, 1 + (i % 7));
        }
        DsIndexSegment s = w.finish();
        store.append(segId, s);
    }

    private static int[] six(int v) {
        return new int[]{v, 1, 1, 1, 1, 1};
    }

    // ============== TR6.3 5 类 Javadoc ≥ 10 行 ==============
    @Test(timeout = 30000)
    public void tr63_FiveClassesJavadocAtLeast10Lines() throws Exception {
        List<File> files = compactFiles();
        Assert.assertEquals("compact 应有 5 个源文件", 5, files.size());
        for (File f : files) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            int docLines = 0;
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("*") || t.startsWith("/**")) {
                    docLines++;
                }
            }
            Assert.assertTrue(f.getName() + " Javadoc 行数应 ≥ 10, got " + docLines, docLines >= 10);
        }
    }

    // ============== TR6.4 CPD 0 重复块（160 字符窗口） ==============
    @Test(timeout = 30000)
    public void tr64_NoDuplicateCodeBlocks() throws Exception {
        int window = 160;
        Map<String, Integer> seen = new HashMap<>();
        for (File f : compactFiles()) {
            List<String> norm = normalizedCodeLines(f);
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
    }

    // ============== TR6.5 0 println/printStackTrace + IAE ≥ 8 ==============
    @Test(timeout = 30000)
    public void tr65_NoPrintAndIaeFloor() throws Exception {
        int iae = 0;
        for (File f : compactFiles()) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse(f.getName() + " 不应有 System.out", content.contains("System.out"));
            Assert.assertFalse(f.getName() + " 不应有 System.err", content.contains("System.err"));
            Assert.assertFalse(f.getName() + " 不应有 printStackTrace", content.contains("printStackTrace"));
            int from = 0;
            while (true) {
                int k = content.indexOf("throw new IllegalArgumentException", from);
                if (k < 0) {
                    break;
                }
                iae++;
                from = k + 1;
            }
        }
        Assert.assertTrue("IAE 守卫总数应 ≥ 8, got " + iae, iae >= 8);
    }

    /* ================ helpers ================ */
    private static List<File> compactFiles() {
        File dir = new File(System.getProperty("user.dir"), COMPACT_SRC);
        Assert.assertTrue("compact 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(fs);
        List<File> out = new ArrayList<>(Arrays.asList(fs));
        Collections.sort(out);
        return out;
    }

    private static List<String> normalizedCodeLines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("package ") || t.startsWith("import ")
                    || t.startsWith("*") || t.startsWith("@")) {
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
