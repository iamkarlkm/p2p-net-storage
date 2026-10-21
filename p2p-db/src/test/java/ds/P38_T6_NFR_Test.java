package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask;

/**
 * P38 T6：NFR 5 TR。
 * TR6.1 parse P95 ≤ 2s 30×
 * TR6.2 5 类 Javadoc ≥ 10 行
 * TR6.3 CPD dup ≤ 1（160 token）
 * TR6.4 0 println/printStackTrace + IAE ≥ 8
 * TR6.5 AggQueryPlan 6 场景 20× 100%
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P38_T6_NFR_Test {

    private static final String V3_REL = "com/q3lives/ds/database/fulltext/cluster/parser/v3";
    private static final String V3_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/parser/v3";

    // ============== TR6.1 parse P95 ≤ 2s 30× ==============

    @Test(timeout = 60000)
    public void tr6_1_ParseP95_Under2s() {
        int RUNS = 30;
        long[] samples = new long[RUNS];
        String[] dsls = {
                "a = 1 AGG(COUNT(id))",
                "a = 1 OR b = 2 AGG(COUNT(id), SUM(price))",
                "(a = 1 OR b = 2) AND c = 3 AGG(AVG(x), MIN(y), MAX(z))",
                "FT('hello') AND a = 1 OR b = 2 AGG(COUNT(id))",
                "a = 1 AND (b = 2 OR (c = 3 AND (d = 4 OR e = 5))) AGG(COUNT(a), SUM(b), AVG(c))"
        };
        for (int i = 0; i < RUNS; i++) {
            String dsl = dsls[i % dsls.length];
            long t0 = System.nanoTime();
            AggParser.parse(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, 8);
            samples[i] = (System.nanoTime() - t0) / 1_000_000L;
        }
        Arrays.sort(samples);
        long p95 = samples[(int) Math.floor(RUNS * 0.95) - 1];
        Assert.assertTrue("P95 should be <= 2000ms, actual=" + p95 + "ms", p95 <= 2000);
    }

    // ============== TR6.2 5 类 Javadoc ≥ 10 行 ==============

    @Test(timeout = 30000)
    public void tr6_2_FiveClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"AggFn", "AggSpec", "MintermAggTask", "AggQueryPlan", "AggParser"};
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, V3_REL.replace("/", File.separator) + File.separator + cn + ".java");
            Assert.assertTrue("java source for " + cn + " not found at " + javaFile, javaFile.exists());
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", Pattern.MULTILINE).matcher(content);
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

    // ============== TR6.3 CPD dup ≤ 1（160 token） ==============

    @Test(timeout = 30000)
    public void tr6_3_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[] coreClasses = {"AggFn", "AggSpec", "MintermAggTask", "AggQueryPlan", "AggParser"};
        List<String> bodies = new ArrayList<>();
        for (String cn : coreClasses) {
            File javaFile = new File(srcRoot, V3_REL.replace("/", File.separator) + File.separator + cn + ".java");
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
                    String window = a.substring(s, s + 160);
                    if (b.contains(window)) {
                        hitCount++;
                    }
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
        File pkgDir = new File(new File(System.getProperty("user.dir"), "src/main/java"),
                V3_REL.replace("/", File.separator));
        File[] javaFiles = pkgDir.listFiles((d, n) -> n.endsWith(".java"));
        Assert.assertNotNull(javaFiles);
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

    // ============== TR6.5 AggQueryPlan 6 场景 20× 100% ==============

    @Test(timeout = 60000)
    public void tr65_AggQueryPlanSixScenes_100Percent() {
        int RUNS = 20;
        String dsl = "(a = 1 OR b = 2) AND c = 3 AGG(COUNT(id), SUM(price), AVG(score))";

        // 场景 1: taskCount = clause.size() × aggs.size()
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            AggQueryPlan p = parse(dsl);
            if (p.taskCount() == p.clause.size() * p.aggs.size() && p.taskCount() == 6) {
                s1++;
            }
        }
        Assert.assertTrue("taskCount scene should pass 100%, got " + s1, s1 == RUNS);

        // 场景 2: tasks() 顺序 = minterm 序 × agg 声明序
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            AggQueryPlan p = parse(dsl);
            List<MintermAggTask> expected = new ArrayList<>();
            for (int mi = 0; mi < p.clause.size(); mi++) {
                for (AggSpec a : p.aggs) {
                    expected.add(new MintermAggTask(mi, p.clause.getMiniTerm(mi), a));
                }
            }
            if (expected.equals(p.tasks())) {
                s2++;
            }
        }
        Assert.assertTrue("task order scene should pass 100%, got " + s2, s2 == RUNS);

        // 场景 3: fingerprint 64-char hex
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            if (parse(dsl).fingerprint().length() == 64) {
                s3++;
            }
        }
        Assert.assertTrue("fingerprint 64-char scene should pass 100%, got " + s3, s3 == RUNS);

        // 场景 4: fingerprint 跨调用稳定
        int s4 = 0;
        AggQueryPlan stable = parse(dsl);
        String fp1 = stable.fingerprint();
        for (int i = 0; i < RUNS; i++) {
            if (stable.fingerprint().equals(fp1)) {
                s4++;
            }
        }
        Assert.assertTrue("fingerprint stable scene should pass 100%, got " + s4, s4 == RUNS);

        // 场景 5: deepEquals 自洽 + aggs 不可变（add → UnsupportedOperationException）
        int s5 = 0;
        for (int i = 0; i < RUNS; i++) {
            AggQueryPlan a = parse(dsl);
            AggQueryPlan b = parse(dsl);
            boolean immutable = false;
            try {
                a.aggs.add(new AggSpec(AggFn.COUNT, "x"));
            } catch (UnsupportedOperationException ignore) {
                immutable = true;
            }
            if (AggQueryPlan.deepEquals(a, b) && immutable) {
                s5++;
            }
        }
        Assert.assertTrue("deepEquals + immutability scene should pass 100%, got " + s5, s5 == RUNS);

        // 场景 6: aggs.size() > MAX_AGG → IAE
        int s6 = 0;
        for (int i = 0; i < RUNS; i++) {
            List<AggSpec> tooMany = new ArrayList<>();
            for (int k = 0; k <= AggQueryPlan.MAX_AGG; k++) {
                tooMany.add(new AggSpec(AggFn.COUNT, "c" + k));
            }
            try {
                new AggQueryPlan(new DnfClause(Arrays.asList(Arrays.asList(ConditionNode.ft("q1")))), tooMany);
                s6--;
            } catch (IllegalArgumentException ignore) {
                s6++;
            }
        }
        Assert.assertTrue("MAX_AGG guard scene should pass 100%, got " + s6, s6 == RUNS);
    }

    private static AggQueryPlan parse(String dsl) {
        return AggParser.parse(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, 8);
    }
}
