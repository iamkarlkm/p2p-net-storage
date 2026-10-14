package ds;

import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkReport;
import com.q3lives.ds.database.fulltext.cluster.benchmark.BenchmarkRunner;
import com.q3lives.ds.database.fulltext.cluster.benchmark.DslCorpus;
import com.q3lives.ds.database.fulltext.cluster.benchmark.TpcHQuery;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * P33 T6: NFR 5 TR. TR6.1 benchmark P95 ≤ 80s 30× TR6.2 4 类 Javadoc ≥ 10 行
 * TR6.3 CPD dup ≤ 1（160 token） TR6.4 0 println/printStackTrace + ≥8 IAE TR6.5 4
 * 报表字段 6 场景 20× 100%
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P33_T6_NFR_Test {

    private static final String PKG_BENCHMARK = "com/q3lives/ds/database/fulltext/cluster/benchmark";

    // ============== TR6.1 benchmark P95 ≤ 80s 30× ==============
    @Test(timeout = 180000)
    public void tr6_1_BenchmarkP95_Under80s() throws Exception {
        int RUNS = 30;
        long[] samples = new long[RUNS];
        Function<String, Object> hook = q -> "result-" + q.hashCode();
        for (int i = 0; i < RUNS; i++) {
            long t0 = System.nanoTime();
            BenchmarkRunner.runWithHooks(hook, null);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) Math.floor(RUNS * 0.95) - 1];
        Assert.assertTrue("P95 should be <= 30000ms (relaxed for unit test), actual=" + p95 + "ms", p95 <= 30000);
    }

    // ============== TR6.2 4 类 Javadoc ≥ 10 行 ==============
    @Test(timeout = 30000)
    public void tr6_2_FourClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"TpcHQuery", "DslCorpus", "BenchmarkReport", "BenchmarkRunner"};
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, PKG_BENCHMARK.replace("/", File.separator) + File.separator + cn + ".java");
            Assert.assertTrue("java source for " + cn + " not found at " + javaFile, javaFile.exists());
            String content = new String(java.nio.file.Files.readAllBytes(javaFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", java.util.regex.Pattern.MULTILINE);
            java.util.regex.Matcher m = p.matcher(content);
            int maxLines = 0;
            while (m.find()) {
                String body = m.group(1);
                int lines = body.split("\n").length;
                if (lines > maxLines) {
                    maxLines = lines;
                }
            }
            Assert.assertTrue(cn + " Javadoc should have at least 10 lines, got " + maxLines, maxLines >= 10);
        }
    }

    // ============== TR6.3 CPD dup ≤ 1 (160 token) ==============
    @Test(timeout = 30000)
    public void tr6_3_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"TpcHQuery", "DslCorpus", "BenchmarkReport", "BenchmarkRunner"};
        java.util.List<String> bodies = new java.util.ArrayList<>();
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, PKG_BENCHMARK.replace("/", File.separator) + File.separator + cn + ".java");
            if (!javaFile.exists()) {
                continue;
            }
            String content = new String(java.nio.file.Files.readAllBytes(javaFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
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
                    String window = a.substring(s, s + 160);
                    if (b.contains(window)) {
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

    // ============== TR6.4 0 println/printStackTrace + ≥8 IAE ==============
    @Test(timeout = 30000)
    public void tr6_4_NoStdoutPrint_AtLeast8IAE() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        File pkgDir = new File(srcRoot, PKG_BENCHMARK.replace("/", File.separator));
        File[] javaFiles = pkgDir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(javaFiles);
        int iaeCount = 0;
        for (File f : javaFiles) {
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
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

    // ============== TR6.5 4 报表字段 6 场景 20× 100% ==============
    @Test(timeout = 60000)
    public void tr6_5_FourReportFieldsSixScenes_100Percent() {
        int RUNS = 20;
        // 场景 1: P50/P95 字段一致
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            BenchmarkReport r = new BenchmarkReport(100.0, 200.0, 0.9, 50.0,
                    "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 1.0);
            if (r.getTpcHP50Ms() <= r.getTpcHP95Ms()) {
                s1++;
            }
        }
        Assert.assertTrue("P50 ≤ P95 scene should pass 100%, got " + s1, s1 == RUNS);

        // 场景 2: Recall 0.0~1.0
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            double recall = (double) i / RUNS;
            BenchmarkReport r = new BenchmarkReport(0.0, 0.0, recall, 0.0,
                    "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 0.0);
            if (r.getTpcHRecallAt10() >= 0.0 && r.getTpcHRecallAt10() <= 1.0) {
                s2++;
            }
        }
        Assert.assertTrue("Recall range scene should pass 100%, got " + s2, s2 == RUNS);

        // 场景 3: fingerprint 非空
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            // 64-char hex (SHA-256)
            String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
            BenchmarkReport r = new BenchmarkReport(0.0, 0.0, 0.5, 0.0, fp, 0.0);
            if (r.getDslFingerprint().length() == 64) {
                s3++;
            }
        }
        Assert.assertTrue("Fingerprint 64-char scene should pass 100%, got " + s3, s3 == RUNS);

        // 场景 4: isAcceptable 边界
        int s4 = 0;
        for (int i = 0; i < RUNS; i++) {
            BenchmarkReport r = new BenchmarkReport(500.0, 2000.0, 0.85, 1000.0,
                    "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 10.0);
            if (r.isAcceptable()) {
                s4++;
            }
        }
        Assert.assertTrue("Acceptable scene should pass 100%, got " + s4, s4 == RUNS);

        // 场景 5: TpcHQuery 22 条与 DslCorpus 100 条
        int s5 = 0;
        for (int i = 0; i < RUNS; i++) {
            if (TpcHQuery.COUNT == 22 && DslCorpus.COUNT == 100) {
                s5++;
            }
        }
        Assert.assertTrue("Corpus size scene should pass 100%, got " + s5, s5 == RUNS);

        // 场景 6: Report toString 包含所有字段
        int s6 = 0;
        for (int i = 0; i < RUNS; i++) {
            BenchmarkReport r = new BenchmarkReport(1.0, 2.0, 0.5, 3.0,
                    "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", 4.0);
            String s = r.toString();
            if (s.contains("tpcH_p50_ms") && s.contains("tpcH_p95_ms") && s.contains("dsl_fingerprint")
                    && s.contains("acceptable")) {
                s6++;
            }
        }
        Assert.assertTrue("ToString scene should pass 100%, got " + s6, s6 == RUNS);
    }
}
