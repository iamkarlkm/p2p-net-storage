package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByExecutor;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupByResult;
import com.q3lives.ds.database.fulltext.cluster.groupby.GroupBySpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec;
import com.q3lives.ds.database.fulltext.cluster.groupby.HavingSpec.HavingOp;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P41 T6：NFR + 场景正确性 5 TR 0F0E。
 *
 * <p>
 * TR6.1 execute P95 ≤ 2s（8 minterm × 3 agg × 1k 行 × 50 组）
 * <br>TR6.2 5 类 Javadoc ≥ 10 行
 * <br>TR6.3 CPD 0 重复块（160 字符滑窗）
 * <br>TR6.4 0 System.out / System.err / printStackTrace（groupby 源码）
 * <br>TR6.5 IAE 守卫 ≥ 8（源码扫描）+ 6 场景 100%（重叠去重 / 空 aggs DISTINCT / NULL 分组 /
 * HAVING 全剔除空结果 / 桶序确定性 / fingerprint 跨进程一致）
 */
public class P41_T6_NFR_Test {

    private static final String GROUPBY_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/groupby";

    /**
     * 测试实体：dept/city 分组列，price 聚合列（可 NULL）。
     */
    public static class NRow extends DsTableAdapter {

        final String dept;
        final String city;
        final Double price;

        public NRow(long id, String dept, String city, Double price) {
            setId(id);
            this.dept = dept;
            this.city = city;
            this.price = price;
        }

        /**
         * 无主键行（id 保持 null，用于 ISE 场景）。
         */
        public NRow(String dept, String city, Double price) {
            this.dept = dept;
            this.city = city;
            this.price = price;
        }
    }

    private static final Map<String, Function<NRow, String>> GCOLS
            = Map.of("dept", r -> r.dept, "city", r -> r.city);

    private static final Map<String, ToDoubleFunction<NRow>> ACOLS
            = Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    // ============== 计划构造 ==============

    private static DnfClause singleMinterm() {
        return new DnfClause(Collections.singletonList(
                Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "g0"))));
    }

    /** 8 minterm（各不相同的 g0..g7）× 3 agg。 */
    private static AggQueryPlan plan8x3() {
        List<List<ConditionNode>> mts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mts.add(Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "g" + i)));
        }
        return new AggQueryPlan(new DnfClause(mts),
                Arrays.asList(new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"),
                        new AggSpec(AggFn.MAX, "price")));
    }

    private static AggQueryPlan planCountSum() {
        return new AggQueryPlan(singleMinterm(),
                Arrays.asList(new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price")));
    }

    /** 1k 行，dept = g(i%50)，共 50 组；price = i。 */
    private static List<NRow> rows1k() {
        List<NRow> rs = new ArrayList<>(1000);
        for (int i = 1; i <= 1000; i++) {
            rs.add(new NRow(i, "g" + (i % 50), "c0", (double) i));
        }
        return rs;
    }

    private static GroupBySpec spec1(AggQueryPlan plan, List<String> groupColumns) {
        return new GroupBySpec(plan, groupColumns, Collections.emptyList(), Collections.emptyList());
    }

    // ============== TR6.1 execute P95 ≤ 2s ==============

    @Test(timeout = 120000)
    public void tr61_ExecuteP95Within2s() {
        GroupBySpec spec = spec1(plan8x3(), Collections.singletonList("dept"));
        List<NRow> all = rows1k();
        AggRowSource<NRow> src = minterm -> all;
        GroupByExecutor<NRow> ex = new GroupByExecutor<>();
        for (int i = 0; i < 10; i++) {
            ex.execute(spec, src, GCOLS, ACOLS);
        }
        int n = 40;
        long[] dur = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            GroupByResult r = ex.execute(spec, src, GCOLS, ACOLS);
            dur[i] = System.nanoTime() - t0;
            Assert.assertEquals("50 组", 50, r.size());
            Assert.assertEquals("union 去重后总行数", 1000L, r.totalRowCount);
        }
        Arrays.sort(dur);
        long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
        Assert.assertTrue("P95 应 ≤ 2000ms, got " + p95Ms + "ms", p95Ms <= 2000L);
    }

    // ============== TR6.2 5 类 Javadoc ≥ 10 行 ==============

    @Test(timeout = 30000)
    public void tr62_FiveClassesJavadocAtLeast10Lines() throws Exception {
        List<File> files = groupbyFiles();
        Assert.assertEquals("groupby 应有 5 个源文件", 5, files.size());
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

    // ============== TR6.3 CPD 0 重复块（160 字符滑窗） ==============

    @Test(timeout = 30000)
    public void tr63_NoDuplicateCodeBlocks() throws Exception {
        int window = 160;
        Map<String, Integer> seen = new HashMap<>();
        for (File f : groupbyFiles()) {
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

    // ============== TR6.4 0 println/printStackTrace ==============

    @Test(timeout = 30000)
    public void tr64_NoPrintStatements() throws Exception {
        for (File f : groupbyFiles()) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Assert.assertFalse(f.getName() + " 不应有 System.out", content.contains("System.out"));
            Assert.assertFalse(f.getName() + " 不应有 System.err", content.contains("System.err"));
            Assert.assertFalse(f.getName() + " 不应有 printStackTrace", content.contains("printStackTrace"));
        }
    }

    // ============== TR6.5 IAE ≥ 8 + 6 场景 100% ==============

    @Test(timeout = 120000)
    public void tr65_IaeFloorAndSixScenarios() throws Exception {
        int iae = 0;
        for (File f : groupbyFiles()) {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
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

        // 场景 1：重叠 minterm 去重（3 minterm × 4 行，若不去重 g0 会重复计数）
        GroupBySpec s1 = spec1(planCountSum(), Collections.singletonList("dept"));
        DnfClause overlap = new DnfClause(Arrays.asList(
                Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "g0")),
                Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "g1")),
                Collections.singletonList(ConditionNode.orm("dept", DslToken.OP_EQ, "g0"))));
        GroupBySpec sOverlap = new GroupBySpec(
                new AggQueryPlan(overlap, s1.plan.aggs), Collections.singletonList("dept"),
                Collections.emptyList(), Collections.emptyList());
        List<NRow> dup = new ArrayList<>();
        dup.add(new NRow(1L, "g0", "c0", 10.0));
        dup.add(new NRow(2L, "g0", "c0", 20.0));
        dup.add(new NRow(3L, "g1", "c0", 30.0));
        dup.add(new NRow(4L, "g1", "c0", 40.0));
        GroupByResult r1 = new GroupByExecutor<NRow>()
                .execute(sOverlap, minterm -> dup, GCOLS, ACOLS);
        Assert.assertEquals("场景1 union 去重总行数", 4L, r1.totalRowCount);
        long rc = 0;
        for (int i = 0; i < r1.size(); i++) {
            rc += r1.bucket(i).rowCount;
        }
        Assert.assertEquals("场景1 桶内 rowCount Σ 必须 = 去重总行数", 4L, rc);

        // 场景 2：空 aggs → SELECT DISTINCT 分组列（(dept,city) 组合去重）
        GroupBySpec s2 = new GroupBySpec(new AggQueryPlan(singleMinterm(), Collections.emptyList()),
                Arrays.asList("dept", "city"), Collections.emptyList(), Collections.emptyList());
        List<NRow> dist = new ArrayList<>();
        dist.add(new NRow(1L, "g0", "c0", 10.0));
        dist.add(new NRow(2L, "g0", "c0", 20.0));
        dist.add(new NRow(3L, "g0", "c1", 30.0));
        dist.add(new NRow(4L, "g1", "c0", 40.0));
        GroupByResult r2 = new GroupByExecutor<NRow>().execute(s2, minterm -> dist, GCOLS, ACOLS);
        Assert.assertEquals("场景2 应有 3 个 DISTINCT 组合桶", 3, r2.size());
        for (int i = 0; i < r2.size(); i++) {
            Assert.assertTrue("场景2 空 aggs 桶 values 必空", r2.bucket(i).values.isEmpty());
        }

        // 场景 3：NULL 分组列合法成键（canonical "\u0000"）
        List<NRow> withNull = new ArrayList<>();
        withNull.add(new NRow(1L, null, "c0", 10.0));
        withNull.add(new NRow(2L, "g0", "c0", 20.0));
        GroupByResult r3 = new GroupByExecutor<NRow>()
                .execute(s1, minterm -> withNull, GCOLS, ACOLS);
        Assert.assertEquals("场景3 应有 2 桶", 2, r3.size());
        Assert.assertEquals("场景3 NULL 键哨兵", "\u0000", r3.bucket(0).key.get(0));
        Assert.assertEquals("场景3 NULL 桶 rowCount", 1L, r3.bucket(0).rowCount);

        // 场景 4：HAVING 全剔除 → 空结果，但 totalRowCount 保留
        List<NRow> four = new ArrayList<>();
        four.add(new NRow(1L, "g0", "c0", 10.0));
        four.add(new NRow(2L, "g0", "c0", 20.0));
        four.add(new NRow(3L, "g1", "c0", 30.0));
        four.add(new NRow(4L, "g1", "c0", null));
        GroupBySpec s4 = new GroupBySpec(planCountSum(), Collections.singletonList("dept"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 999.0)));
        GroupByResult r4 = new GroupByExecutor<NRow>().execute(s4, minterm -> four, GCOLS, ACOLS);
        Assert.assertEquals("场景4 全剔除后 0 桶", 0, r4.size());
        Assert.assertEquals("场景4 totalRowCount 仍保留", 4L, r4.totalRowCount);

        // 场景 5：桶序 = 分组键字典序（插入序 c,a,b → 输出 a,b,c）
        List<NRow> unsorted = new ArrayList<>();
        unsorted.add(new NRow(1L, "c", "c0", 1.0));
        unsorted.add(new NRow(2L, "a", "c0", 2.0));
        unsorted.add(new NRow(3L, "b", "c0", 3.0));
        GroupByResult r5 = new GroupByExecutor<NRow>()
                .execute(s1, minterm -> unsorted, GCOLS, ACOLS);
        Assert.assertEquals("场景5 桶序字典序", Arrays.asList("a"), r5.bucket(0).key);
        Assert.assertEquals("场景5 桶序字典序", Arrays.asList("b"), r5.bucket(1).key);
        Assert.assertEquals("场景5 桶序字典序", Arrays.asList("c"), r5.bucket(2).key);

        // 场景 6：fingerprint 跨进程一致（子 JVM spawn）
        GroupBySpec s6 = new GroupBySpec(planCountSum(), Collections.singletonList("dept"),
                Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
        GroupByResult local = new GroupByExecutor<NRow>()
                .execute(s6, minterm -> scenarioRows(), GCOLS, ACOLS);
        String localFp = local.fingerprint();
        Assert.assertEquals("fingerprint 应 64-hex", 64, localFp.length());
        String[] child = runChildTwice();
        Assert.assertEquals("场景6 子进程0 fingerprint 一致", localFp, child[0]);
        Assert.assertEquals("场景6 子进程1 fingerprint 一致", localFp, child[1]);
    }

    // ============== 跨进程固定数据集 + 子 JVM 入口 ==============

    /** 跨进程对齐用固定数据集：g0 2 行（30），g1 2 行（1 行 NULL price）。 */
    private static List<NRow> scenarioRows() {
        List<NRow> rs = new ArrayList<>();
        rs.add(new NRow(1L, "g0", "c0", 10.0));
        rs.add(new NRow(2L, "g0", "c0", 20.0));
        rs.add(new NRow(3L, "g1", "c0", 30.0));
        rs.add(new NRow(4L, "g1", "c0", null));
        return rs;
    }

    /**
     * spawn 子 JVM 跑本测试类 main()，捕获 stdout 首行（fingerprint 或 ERROR: ...）。
     */
    private static String[] runChildTwice() throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx512m",
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    P41_T6_NFR_Test.class.getName());
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String fpLine = null;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Picked up ") || line.trim().isEmpty()) {
                    continue;
                }
                fpLine = line.trim();
                break;
            }
            p.waitFor(20, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            outputs[i] = fpLine == null ? "" : fpLine;
        }
        return outputs;
    }

    /**
     * 子 JVM 入口：固定数据集 GROUP BY dept + HAVING COUNT(price) ≥ 1 → 打印 GroupByResult fingerprint。
     */
    public static void main(String[] args) {
        try {
            GroupBySpec spec = new GroupBySpec(planCountSum(), Collections.singletonList("dept"),
                    Collections.emptyList(), Collections.singletonList(new HavingSpec(0, HavingOp.GE, 1.0)));
            GroupByResult r = new GroupByExecutor<NRow>()
                    .execute(spec, minterm -> scenarioRows(), GCOLS, ACOLS);
            System.out.println(r.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }

    /* ================ helpers ================ */

    private static List<File> groupbyFiles() {
        File dir = new File(System.getProperty("user.dir"), GROUPBY_SRC);
        Assert.assertTrue("groupby 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
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
}
