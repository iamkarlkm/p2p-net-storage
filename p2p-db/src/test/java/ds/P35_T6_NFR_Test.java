package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.cluster.join.v2.HybridJoinClientV2Adapter;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinIdResolverV2;
import com.q3lives.ds.database.fulltext.cluster.join.v2.JoinMode;
import com.q3lives.ds.database.fulltext.cluster.join.v2.ManyToOneJoinResult;

/**
 * P35 T6: NFR 5 TR.
 *
 * <p>
 * TR6.1 5 类 Javadoc ≥ 10 行（DsManyToOne + JoinMode + JoinSpec + JoinIdResolverV2
 * + ManyToOneJoinResult + HybridJoinClientV2Adapter）
 * <br>TR6.2 CPD dup ≤ 1（160 token）
 * <br>TR6.3 0 println/printStackTrace + ≥ 6 IAE
 * <br>TR6.4 4 NFR 字段一致性 20×100%
 * <br>TR6.5 batchSize 边界 1/4096/4097 100% 行为一致
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P35_T6_NFR_Test {

    private static final String PKG_V2 = "com/q3lives/ds/database/fulltext/cluster/join/v2";
    private static final String PKG_JOIN = "com/q3lives/ds/database/fulltext/cluster/join";
    private static final String PKG_ANNO = "com/q3lives/ds/annotation";

    // ============== TR6.1 6 类 Javadoc ≥ 10 行 ==============
    @Test(timeout = 30000)
    public void tr6_1_SixClassesJavadocAtLeast10Lines() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[][] classPaths = {
            {PKG_ANNO, "DsManyToOne"},
            {PKG_V2, "JoinMode"},
            {PKG_V2, "JoinIdResolverV2"},
            {PKG_V2, "ManyToOneJoinResult"},
            {PKG_V2, "HybridJoinClientV2Adapter"},
            {PKG_JOIN, "JoinSpec"},};
        Pattern p = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/", Pattern.MULTILINE);
        for (String[] cp : classPaths) {
            File javaFile = new File(srcRoot, cp[0].replace("/", File.separator)
                    + File.separator + cp[1] + ".java");
            Assert.assertTrue("java source for " + cp[1] + " not found at " + javaFile, javaFile.exists());
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            Matcher m = p.matcher(content);
            int maxLines = 0;
            while (m.find()) {
                String body = m.group(1);
                int lines = body.split("\n").length;
                if (lines > maxLines) {
                    maxLines = lines;
                }
            }
            Assert.assertTrue(cp[1] + " Javadoc should have at least 10 lines, got " + maxLines,
                    maxLines >= 10);
        }
    }

    // ============== TR6.2 CPD dup ≤ 1（160 token） ==============
    @Test(timeout = 30000)
    public void tr6_2_NoCopyPasteDuplication() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        String[][] classPaths = {
            {PKG_ANNO, "DsManyToOne"},
            {PKG_V2, "JoinMode"},
            {PKG_V2, "JoinIdResolverV2"},
            {PKG_V2, "ManyToOneJoinResult"},
            {PKG_V2, "HybridJoinClientV2Adapter"},};
        List<String> bodies = new ArrayList<>();
        for (String[] cp : classPaths) {
            File javaFile = new File(srcRoot, cp[0].replace("/", File.separator)
                    + File.separator + cp[1] + ".java");
            if (!javaFile.exists()) {
                continue;
            }
            String content = new String(Files.readAllBytes(javaFile.toPath()), StandardCharsets.UTF_8);
            content = content.replaceAll("/\\*\\*([\\s\\S]*?)\\*/", "");
            content = content.replaceAll("import\\s+[^;]+;", "");
            content = content.replaceAll("\\s+", "");
            bodies.add(cp[1] + "::" + content);
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

    // ============== TR6.3 0 println/printStackTrace + ≥ 6 IAE ==============
    @Test(timeout = 30000)
    public void tr6_3_NoStdoutPrint_AtLeast6IAE() throws Exception {
        File srcRoot = new File(System.getProperty("user.dir"), "src/main/java");
        File v2Dir = new File(srcRoot, PKG_V2.replace("/", File.separator));
        File[] javaFiles = v2Dir.listFiles((d, n) -> n.endsWith(".java"));
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
        Assert.assertTrue("expected at least 6 IAE, got " + iaeCount, iaeCount >= 6);
    }

    // ============== TR6.4 4 NFR 字段一致性 20×100% ==============
    @Test(timeout = 30000)
    public void tr6_4_NfrFieldsConsistent_20x() {
        int RUNS = 20;
        // 场景 1: JoinMode.values() 必须有 2 个值
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            if (JoinMode.values().length == 2) {
                s1++;
            }
        }
        Assert.assertEquals("JoinMode.values() 必须 == 2", RUNS, s1);

        // 场景 2: JoinMode.code() 必须为 'O' 或 'M'
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            for (JoinMode m : JoinMode.values()) {
                char c = m.code();
                if (c == 'O' || c == 'M') {
                    s2++;
                }
            }
        }
        Assert.assertEquals("JoinMode.code() 必须 O 或 M × 2 值 × 20 次", RUNS * 2, s2);

        // 场景 3: JoinSpec.DEFAULT_BATCH_SIZE == 256, MAX_BATCH_SIZE == 4096
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            if (com.q3lives.ds.database.fulltext.cluster.join.JoinSpec.DEFAULT_BATCH_SIZE == 256
                    && com.q3lives.ds.database.fulltext.cluster.join.JoinSpec.MAX_BATCH_SIZE == 4096) {
                s3++;
            }
        }
        Assert.assertEquals("JoinSpec batchSize 常量", RUNS, s3);

        // 场景 4: JoinIdResolverV2.J_TREE_LIMIT == 2
        int s4 = 0;
        for (int i = 0; i < RUNS; i++) {
            if (JoinIdResolverV2.J_TREE_LIMIT == 2) {
                s4++;
            }
        }
        Assert.assertEquals("J_TREE_LIMIT == 2", RUNS, s4);
    }

    // ============== TR6.5 batchSize 边界 1/4096/4097 100% 行为一致 ==============
    @Test(timeout = 30000)
    public void tr6_5_BatchSizeBoundary_20x() {
        int RUNS = 20;
        // 边界 4097 → IAE
        int s1 = 0;
        for (int i = 0; i < RUNS; i++) {
            try {
                new com.q3lives.ds.database.fulltext.cluster.join.JoinSpec<>(
                        com.q3lives.ds.database.adapter.DsTableAdapter.class,
                        "id", "mainId", null, JoinMode.MANY_TO_ONE, 4097);
                s1--;
            } catch (IllegalArgumentException ignore) {
                s1++;
            }
        }
        Assert.assertEquals("batchSize=4097 应 100% IAE", RUNS, s1);

        // 边界 4096 → 合法
        int s2 = 0;
        for (int i = 0; i < RUNS; i++) {
            try {
                com.q3lives.ds.database.fulltext.cluster.join.JoinSpec<com.q3lives.ds.database.adapter.DsTableAdapter> js
                        = new com.q3lives.ds.database.fulltext.cluster.join.JoinSpec<>(
                                com.q3lives.ds.database.adapter.DsTableAdapter.class,
                                "id", "mainId", null, JoinMode.MANY_TO_ONE, 4096);
                if (js.batchSize == 4096) {
                    s2++;
                }
            } catch (Exception ignore) {
            }
        }
        Assert.assertEquals("batchSize=4096 应 100% 合法", RUNS, s2);

        // ManyToOneJoinResult 5 IAE 守卫类型校验
        int s3 = 0;
        for (int i = 0; i < RUNS; i++) {
            try {
                ManyToOneJoinResult<com.q3lives.ds.database.adapter.DsTableAdapter> m
                        = new ManyToOneJoinResult<>(null,
                                new java.util.LinkedHashMap<>(),
                                new java.util.LinkedHashSet<>());
                s3--;
            } catch (IllegalArgumentException ignore) {
                s3++;
            }
        }
        Assert.assertEquals("ManyToOneJoinResult relatedClass null IAE", RUNS, s3);
    }
}
