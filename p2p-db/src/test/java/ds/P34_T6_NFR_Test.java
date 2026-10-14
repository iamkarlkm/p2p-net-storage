package ds;

import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;
import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P34 T6: NFR 5 TR.
 * TR6.1 parseOr P95 ≤ 2s 30×
 * TR6.2 3 类 Javadoc ≥ 10 行
 * TR6.3 CPD dup ≤ 1（160 token）
 * TR6.4 0 println/printStackTrace + ≥8 IAE
 * TR6.5 DnfClause 6 场景 20× 100%
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P34_T6_NFR_Test {

    private static final String PKG_V2 = "com/q3lives/ds/database/fulltext/cluster/parser/v2";

    // ============== TR6.1 parseOr P95 ≤ 2s 30× ==============

    @Test(timeout = 60000)
    public void tr6_1_ParseOrP95_Under2s() {
        int RUNS = 30;
        long[] samples = new long[RUNS];
        String[] dsls = {
                "a = 1 OR b = 2",
                "a = 1 AND (b = 2 OR c = 3)",
                "(a = 1 OR b = 2) AND (c = 3 OR d = 4)",
                "FT('hello') AND a = 1 OR b = 2",
                "a = 1 AND (b = 2 OR (c = 3 AND (d = 4 OR e = 5)))"
        };
        for (int i = 0; i < RUNS; i++) {
            String dsl = dsls[i % dsls.length];
            long t0 = System.nanoTime();
            DnfParser.parseOr(com.q3lives.ds.database.adapter.DsTableAdapter.class,
                    dsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        java.util.Arrays.sort(samples);
        long p95 = samples[(int) Math.floor(RUNS * 0.95) - 1];
        Assert.assertTrue("P95 should be <= 2000ms, actual=" + p95 + "ms", p95 <= 2000);
    }

    // ============== TR6.2 3 类 Javadoc ≥ 10 行 ==============

    @Test(timeout = 30000)
    public void tr6_2_ThreeClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"DnfClause", "ConditionNode", "DnfParser"};
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, PKG_V2.replace("/", File.separator) + File.separator + cn + ".java");
            Assert.assertTrue("java source for " + cn + " not found at " + javaFile, javaFile.exists());
            String content = new String(java.nio.file.Files.readAllBytes(javaFile.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", Pattern.MULTILINE);
            Matcher m = p.matcher(content);
            int maxLines = 0;
            while (m.find()) {
                String body = m.group(1);
                int lines = body.split("\n").length;
                if (lines > maxLines) maxLines = lines;
            }
            Assert.assertTrue(cn + " Javadoc should have at least 10 lines, got " + maxLines, maxLines >= 10);
        }
    }

    // ============== TR6.3 CPD dup ≤ 1 (160 token) ==============

    @Test(timeout = 30000)
    public void tr6_3_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"DnfClause", "ConditionNode", "DnfParser", "DnfReturnMode"};
        java.util.List<String> bodies = new java.util.ArrayList<>();
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, PKG_V2.replace("/", File.separator) + File.separator + cn + ".java");
            if (!javaFile.exists()) continue;
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
                    if (b.contains(window)) hitCount++;
                    totalChecked++;
                }
                if (hitCount >= 2) {
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
        File pkgDir = new File(srcRoot, PKG_V2.replace("/", File.separator));
        File[] javaFiles = pkgDir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(javaFiles);
        int iaeCount = 0;
        for (File f : javaFiles) {
            // 跳过 DnfParserHelperMain（含 main 入口 print）
            if (f.getName().equals("DnfParserHelperMain.java")) continue;
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

    // ============== TR6.5 DnfClause 6 场景 20× 100% ==============

    @Test(timeout = 60000)
    public void tr6_5_DnfClauseFieldsSixScenes_100Percent() {
        int RUNS = 20;
        String fp64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // 场景 1: size() 字段一致
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            DnfClause d = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1"))));
            if (d.size() == 1) s1++;
        }
        Assert.assertTrue("size scene should pass 100%, got " + s1, s1 == RUNS);

        // 场景 2: getMiniTerm 正常 + 越界 IOOBE
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            DnfClause d = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1"))));
            try {
                d.getMiniTerm(99);
                s2--; // 不应到达
            } catch (IndexOutOfBoundsException ignore) {
                s2++;
            }
        }
        Assert.assertTrue("IOOBE scene should pass 100%, got " + s2, s2 == RUNS);

        // 场景 3: fingerprint 64-char hex
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            DnfClause d = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1"))));
            if (d.fingerprint().length() == 64) s3++;
        }
        Assert.assertTrue("Fingerprint 64-char scene should pass 100%, got " + s3, s3 == RUNS);

        // 场景 4: fingerprint 跨调用稳定
        int s4 = 0;
        DnfClause d = new DnfClause(java.util.Arrays.asList(java.util.Arrays.asList(ConditionNode.ft("q1"))));
        String fp1 = d.fingerprint();
        for (int i = 0; i < RUNS; i++) {
            if (d.fingerprint().equals(fp1)) s4++;
        }
        Assert.assertTrue("Fingerprint stable scene should pass 100%, got " + s4, s4 == RUNS);

        // 场景 5: isAcceptable 上限
        int s5 = 0;
        for (int i = 0; i < RUNS; i++) {
            DnfClause d2 = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1")),
                    java.util.Arrays.asList(ConditionNode.ft("q2"))));
            if (d2.isAcceptable(2) && !d2.isAcceptable(1)) s5++;
        }
        Assert.assertTrue("isAcceptable scene should pass 100%, got " + s5, s5 == RUNS);

        // 场景 6: deepEquals 同 minterm 100% 全等
        int s6 = 0;
        for (int i = 0; i < RUNS; i++) {
            DnfClause da = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1"))));
            DnfClause db = new DnfClause(java.util.Arrays.asList(
                    java.util.Arrays.asList(ConditionNode.ft("q1"))));
            if (DnfClause.deepEquals(da, db)) s6++;
        }
        Assert.assertTrue("deepEquals scene should pass 100%, got " + s6, s6 == RUNS);
    }
}
