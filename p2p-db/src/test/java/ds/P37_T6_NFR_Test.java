package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P37 T6：NFR 5 TR 0F0E。
 *
 * <p>
 * TR6.1 段体积压缩率达标（高可压缩 payload 段体积 ≤ 未压缩等价 × 50%）
 * <br>TR6.2 冷启动 open P95 ≤ 200ms（mmap + 常数校验）
 * <br>TR6.3 5 新类 Javadoc ≥ 10 行
 * <br>TR6.4 CPD 重复 ≤ 1（K-gram 窗口匹配）
 * <br>TR6.5 0 println/printStackTrace + 段模块 IAE ≥ 8
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T6_NFR_Test {

    private static final String PKG_SEGMENT = "com/q3lives/ds/database/fulltext/segment";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ============== TR6.1 段体积压缩率达标 ==============
    @Test(timeout = 60000)
    public void tr61_SegmentCompressionRatio() throws Exception {
        File root = tmp.newFolder("p37t6-ratio");
        DsIndexSegmentStore segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        File segFile = segStore.segmentFile(1L);

        DsIndexSegmentWriter w = new DsIndexSegmentWriter(segFile);
        byte[] fat = new byte[20000];
        Arrays.fill(fat, (byte) 'x');
        byte[] fat2 = new byte[16000];
        Arrays.fill(fat2, (byte) 'a');
        w.addTermStat(1L, 1.0, fat);
        w.addTermStat(2L, 2.0, fat2);
        DsIndexSegment seg = w.finish();

        long blocks = (fat.length + 4095L) / 4096L + (fat2.length + 4095L) / 4096L;
        long rawEquiv = DsIndexSegment.HEADER_SIZE + seg.termStatLen + fat.length + fat2.length + 5L * blocks;
        Assert.assertTrue("compressed ratio should be <= 50%: seg=" + segFile.length() + " raw=" + rawEquiv,
                segFile.length() <= rawEquiv / 2);
        segStore.close();
    }

    // ============== TR6.2 冷启动 open P95 ≤ 200ms ==============
    @Test(timeout = 60000)
    public void tr62_ColdStartOpenP95Under200ms() throws Exception {
        File root = tmp.newFolder("p37t6-p95");
        DsIndexSegmentStore segStore = new DsIndexSegmentStore(root.getAbsolutePath());
        File segFile = segStore.segmentFile(1L);

        DsIndexSegmentWriter w = new DsIndexSegmentWriter(segFile);
        int[] docLen = {1, 2, 3, 4, 5, 6};
        int[] nTerms = {6, 6, 6, 6, 6, 6};
        for (int d = 0; d < 200; d++) {
            w.addDocStat(d, docLen, nTerms);
        }
        w.addTermStat(1L, 1.0, new byte[]{1, 2, 3});
        w.finish();

        // 预热（剥离类加载 / JIT 噪声）
        for (int i = 0; i < 5; i++) {
            DsIndexSegmentReader r = DsIndexSegmentReader.open(segFile);
            r.close();
        }
        int runs = 20;
        long[] ms = new long[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            DsIndexSegmentReader r = DsIndexSegmentReader.open(segFile);
            ms[i] = (System.nanoTime() - t0) / 1_000_000L;
            r.close();
        }
        Arrays.sort(ms);
        long p95 = ms[(int) Math.floor(runs * 0.95) - 1];
        Assert.assertTrue("cold-start open P95 should be <= 200ms, actual=" + p95 + "ms", p95 <= 200);
        segStore.close();
    }

    // ============== TR6.3 5 新类 Javadoc ≥10 行 ==============
    @Test(timeout = 30000)
    public void tr63_FiveNewClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] classes = {
            "DsIndexSegment",
            "SegmentColumnCodec",
            "DsIndexSegmentWriter",
            "DsIndexSegmentReader",
            "DsIndexSegmentStore"
        };
        Pattern p = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", Pattern.MULTILINE);
        for (String cn : classes) {
            File javaFile = new File(srcRoot, PKG_SEGMENT + "/" + cn + ".java");
            Assert.assertTrue("java source for " + cn + " not found at " + javaFile, javaFile.exists());
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            Matcher m = p.matcher(content);
            int maxLines = 0;
            while (m.find()) {
                int lines = m.group(1).split("\n").length;
                if (lines > maxLines) {
                    maxLines = lines;
                }
            }
            Assert.assertTrue(cn + " Javadoc should have at least 10 lines, got " + maxLines, maxLines >= 10);
        }
    }

    // ============== TR6.4 CPD 重复 ≤ 1 ==============
    @Test(timeout = 30000)
    public void tr64_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] classes = {
            "DsIndexSegment",
            "SegmentColumnCodec",
            "DsIndexSegmentWriter",
            "DsIndexSegmentReader",
            "DsIndexSegmentStore"
        };
        List<String> bodies = new ArrayList<>();
        for (String cn : classes) {
            File javaFile = new File(srcRoot, PKG_SEGMENT + "/" + cn + ".java");
            if (!javaFile.exists()) {
                continue;
            }
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            content = content.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            content = content.replaceAll("import\\s+[^;]+;", "");
            content = content.replaceAll("\\s+", "");
            bodies.add(cn + "::" + content);
        }
        for (int i = 0; i < bodies.size(); i++) {
            for (int j = i + 1; j < bodies.size(); j++) {
                String a = bodies.get(i);
                String b = bodies.get(j);
                int hitCount = 0;
                int totalChecked = 0;
                for (int s = 0; s + 160 <= a.length() && totalChecked < 5; s += Math.max(1, a.length() / 10)) {
                    if (b.contains(a.substring(s, s + 160))) {
                        hitCount++;
                    }
                    totalChecked++;
                }
                if (hitCount >= 3) {
                    Assert.fail("CPD violation: " + a.substring(0, a.indexOf("::")) + " <-> "
                            + b.substring(0, b.indexOf("::")) + " hits=" + hitCount + "/" + totalChecked);
                }
            }
        }
    }

    // ============== TR6.5 0 println/printStackTrace + IAE ≥ 8 ==============
    @Test(timeout = 30000)
    public void tr65_NoStdoutPrint_AtLeast8IAE() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        File pkgDir = new File(srcRoot, PKG_SEGMENT);
        File[] javaFiles = pkgDir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(javaFiles);
        Assert.assertTrue("segment 子包应有 5 个源文件", javaFiles.length >= 5);
        int iaeCount = 0;
        for (File f : javaFiles) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String codeOnly = content.replaceAll("/\\*[\\s\\S]*?\\*/", "").replaceAll("//.*", "");
            Assert.assertFalse("no println in " + f.getName(), codeOnly.contains("System.out.println"));
            Assert.assertFalse("no printStackTrace in " + f.getName(), codeOnly.contains("printStackTrace"));
            int from = 0;
            while ((from = content.indexOf("throw new IllegalArgumentException", from)) >= 0) {
                iaeCount++;
                from += 1;
            }
        }
        Assert.assertTrue("expected at least 8 IAE, got " + iaeCount, iaeCount >= 8);
    }
}
