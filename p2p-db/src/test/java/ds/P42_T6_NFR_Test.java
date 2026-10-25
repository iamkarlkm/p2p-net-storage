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

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P42 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 1k 文档导出 P95 ≤ 阈值
 * <br>TR6.2 冷启动 open P95 ≤ 200ms（R10 零重建）
 * <br>TR6.3 3 类 Javadoc ≥ 10 行
 * <br>TR6.4 CPD 0 重复块（160 字符窗口）
 * <br>TR6.5 0 System.out/System.err/printStackTrace + IAE 显式守卫 ≥ 8
 */
public class P42_T6_NFR_Test {

    private static final String LAYERED_SRC = "src/main/java/com/q3lives/ds/database/fulltext/layered";

    // ============== TR6.1 1k 文档导出 P95 ==============

    @Test(timeout = 180000)
    public void tr61_Export1kDocsP95WithinThreshold() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p42t6-export-" + System.nanoTime());
        root.mkdirs();
        try {
            FulltextStore ft = new FulltextStore(root.getAbsolutePath());
            try {
                for (int i = 0; i < 1000; i++) {
                    ft.ingestDoc(1000 + i, "alpha beta gamma w" + (i % 50));
                }
                DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
                LayeredIndexExporter ex = new LayeredIndexExporter();
                for (int i = 0; i < 2; i++) {
                    ex.export(ft, store, 10L + i);
                }
                int n = 8;
                long[] dur = new long[n];
                for (int i = 0; i < n; i++) {
                    long segId = 100L + i;
                    long t0 = System.nanoTime();
                    ex.export(ft, store, segId);
                    dur[i] = System.nanoTime() - t0;
                    Assert.assertTrue("导出段 docCount 应 > 0", store.load(segId).docCount > 0);
                }
                Arrays.sort(dur);
                long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
                Assert.assertTrue("1k 文档导出 P95 应 ≤ 5000ms, got " + p95Ms + "ms", p95Ms <= 5000L);
            } finally {
                ft.close();
            }
        } finally {
            deleteRecursive(root);
        }
    }

    // ============== TR6.2 冷启动 open P95 ==============

    @Test(timeout = 120000)
    public void tr62_ColdStartOpenP95WithinThreshold() throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"), "p42t6-open-" + System.nanoTime());
        root.mkdirs();
        try {
            DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
            DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(10L));
            for (int i = 0; i < 3000; i++) {
                w.addDocStat(1000 + i, six(i + 1), six(3));
                w.addTermStat(2000 + i, 1.0 + i * 1e-3, new byte[]{(byte) (i & 0xFF)});
                w.addTf(3000 + i, 1 + (i % 7));
            }
            DsIndexSegment s = w.finish();
            store.append(10L, s);

            for (int i = 0; i < 5; i++) {
                LayeredInvertedView.open(store, new long[]{10L}).close();
            }
            int n = 25;
            long[] dur = new long[n];
            for (int i = 0; i < n; i++) {
                long t0 = System.nanoTime();
                LayeredInvertedView v = LayeredInvertedView.open(store, new long[]{10L});
                dur[i] = System.nanoTime() - t0;
                v.close();
            }
            Arrays.sort(dur);
            long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
            Assert.assertTrue("冷启动 open P95 应 ≤ 200ms, got " + p95Ms + "ms", p95Ms <= 200L);
        } finally {
            deleteRecursive(root);
        }
    }

    // ============== TR6.3 3 类 Javadoc ≥ 10 行 ==============

    @Test(timeout = 30000)
    public void tr63_ThreeClassesJavadocAtLeast10Lines() throws Exception {
        List<File> files = layeredFiles();
        Assert.assertEquals("layered 应有 3 个源文件", 3, files.size());
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
        for (File f : layeredFiles()) {
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
        for (File f : layeredFiles()) {
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

    private static List<File> layeredFiles() {
        File dir = new File(System.getProperty("user.dir"), LAYERED_SRC);
        Assert.assertTrue("layered 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
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
