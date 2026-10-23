package ds;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.agg.AggExecutor;
import com.q3lives.ds.database.fulltext.cluster.agg.AggMerger;
import com.q3lives.ds.database.fulltext.cluster.agg.AggResult;
import com.q3lives.ds.database.fulltext.cluster.agg.AggRowSource;
import com.q3lives.ds.database.fulltext.cluster.agg.AggValue;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggSpec;

/**
 * P39 T6：NFR + 合并正确性 5 TR 0F0E。
 *
 * <p>
 * TR6.1 execute P95 ≤ 2s（8 minterm × 2 agg × 1k 行）
 * <br>TR6.2 6 类 Javadoc ≥ 10 行
 * <br>TR6.3 CPD 0 重复块（12 行滑动窗去重）
 * <br>TR6.4 0 println/printStackTrace + IAE 守卫 ≥ 8
 * <br>TR6.5 合并 6 场景 100%（COUNT/SUM/AVG/MIN/MAX + agg 序列错配 IAE）+ 分片合并 = 单次执行（逐聚合全等）
 */
public class P39_T6_NFR_Test {

    private static final String AGG_SRC = "src/main/java/com/q3lives/ds/database/fulltext/cluster/agg";

    public static class NRow extends DsTableAdapter {
        final Double price;

        public NRow(long id, Double price) {
            setId(id);
            this.price = price;
        }
    }

    private static final Map<String, ToDoubleFunction<NRow>> COLS =
            Map.of("price", r -> r.price == null ? Double.NaN : r.price);

    private static List<NRow> rows(int n) {
        List<NRow> rs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            rs.add(new NRow(i, (double) i));
        }
        return rs;
    }

    /** 8 minterm（同条件）+ 2 agg；等价 8×2 任务形态。 */
    private static AggQueryPlan plan8x2() {
        List<List<ConditionNode>> mts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            mts.add(Collections.singletonList(ConditionNode.orm("tag", DslToken.OP_EQ, "x")));
        }
        return new AggQueryPlan(new DnfClause(mts),
                Arrays.asList(new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price")));
    }

    private static AggQueryPlan plan5() {
        return new AggQueryPlan(new DnfClause(Collections.singletonList(
                Collections.singletonList(ConditionNode.orm("tag", DslToken.OP_EQ, "x")))),
                Arrays.asList(new AggSpec(AggFn.COUNT, "price"), new AggSpec(AggFn.SUM, "price"),
                        new AggSpec(AggFn.AVG, "price"), new AggSpec(AggFn.MIN, "price"),
                        new AggSpec(AggFn.MAX, "price")));
    }

    // ============== TR6.1 execute P95 ≤ 2s ==============

    @Test(timeout = 120000)
    public void tr61_ExecuteP95Within2s() {
        AggQueryPlan plan = plan8x2();
        List<NRow> all = rows(1000);
        AggRowSource<NRow> src = minterm -> all;
        AggExecutor<NRow> ex = new AggExecutor<>();
        // warmup
        for (int i = 0; i < 10; i++) {
            ex.execute(plan, src, COLS);
        }
        int n = 40;
        long[] dur = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            AggResult r = ex.execute(plan, src, COLS);
            dur[i] = System.nanoTime() - t0;
            Assert.assertEquals(1000L, r.unionRowCount);
        }
        Arrays.sort(dur);
        int idx = (int) Math.ceil(0.95 * n) - 1;
        long p95Ms = dur[idx] / 1_000_000L;
        Assert.assertTrue("P95 应 ≤ 2000ms, got " + p95Ms + "ms", p95Ms <= 2000L);
    }

    // ============== TR6.2 6 类 Javadoc ≥ 10 行 ==============

    @Test(timeout = 30000)
    public void tr62_SixClassesJavadocAtLeast10Lines() throws Exception {
        List<File> files = aggFiles();
        Assert.assertEquals("cluster/agg 应有 6 个源文件", 6, files.size());
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

    // ============== TR6.3 CPD 0 重复块 ==============

    @Test(timeout = 30000)
    public void tr63_NoDuplicateCodeBlocks() throws Exception {
        int window = 12;
        Map<String, Integer> seen = new HashMap<>();
        for (File f : aggFiles()) {
            List<String> norm = normalizedCodeLines(f);
            for (int i = 0; i + window <= norm.size(); i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < window; j++) {
                    sb.append(norm.get(i + j)).append('\n');
                }
                String key = sb.toString();
                Integer prev = seen.get(key);
                if (prev != null) {
                    Assert.fail("检测到 " + window + " 行重复代码块：出现 " + (prev + 1) + " 次\n" + key);
                }
                seen.put(key, 1);
            }
        }
    }

    // ============== TR6.4 0 println/printStackTrace + IAE ≥ 8 ==============

    @Test(timeout = 30000)
    public void tr64_NoPrintAndIaeFloor() throws Exception {
        int iae = 0;
        for (File f : aggFiles()) {
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

    // ============== TR6.5 合并 6 场景 100% + 分片合并等价 ==============

    @Test(timeout = 120000)
    public void tr65_MergeSixScenariosAndShardEquity() {
        // 场景 1 COUNT / 2 SUM / 3 AVG / 4 MIN / 5 MAX
        AggValue c = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.COUNT, "price"), 2, 0.0, 2.0),
                new AggValue(new AggSpec(AggFn.COUNT, "price"), 3, 0.0, 3.0));
        Assert.assertEquals("场景1 COUNT", 5.0, c.value, 0.0);

        AggValue s = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.SUM, "price"), 2, 30.0, 30.0),
                new AggValue(new AggSpec(AggFn.SUM, "price"), 1, 60.0, 60.0));
        Assert.assertEquals("场景2 SUM", 90.0, s.value, 0.0);

        AggValue a = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.AVG, "price"), 2, 30.0, 15.0),
                new AggValue(new AggSpec(AggFn.AVG, "price"), 1, 60.0, 60.0));
        Assert.assertEquals("场景3 AVG 加权", 30.0, a.value, 1e-12);
        Assert.assertNotEquals("AVG 绝不能是算术平均 (15+60)/2", 37.5, a.value, 1e-12);

        AggValue mn = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.MIN, "price"), 2, 30.0, 10.0),
                new AggValue(new AggSpec(AggFn.MIN, "price"), 1, 60.0, 5.0));
        Assert.assertEquals("场景4 MIN", 5.0, mn.value, 0.0);

        AggValue mx = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.MAX, "price"), 2, 30.0, 20.0),
                new AggValue(new AggSpec(AggFn.MAX, "price"), 1, 60.0, 40.0));
        Assert.assertEquals("场景5 MAX", 40.0, mx.value, 0.0);

        // MIN/MAX 空部分不污染（0.0 哨兵不得胜出）
        AggValue mnEmpty = AggMerger.mergeValues(
                new AggValue(new AggSpec(AggFn.MIN, "price"), 0, 0.0, 0.0),
                new AggValue(new AggSpec(AggFn.MIN, "price"), 2, 30.0, 10.0));
        Assert.assertEquals("空部分 MIN 不污染", 10.0, mnEmpty.value, 0.0);

        // 场景 6 IAE：null / empty / 含 null / agg 序列错配
        expectIae(() -> AggMerger.merge(null));
        expectIae(() -> AggMerger.merge(new ArrayList<>()));
        AggResult one = new AggResult(Collections.singletonList(
                new AggValue(new AggSpec(AggFn.COUNT, "price"), 1, 0.0, 1.0)), 1);
        expectIae(() -> AggMerger.merge(Arrays.asList(one, null)));
        AggResult otherSpec = new AggResult(Collections.singletonList(
                new AggValue(new AggSpec(AggFn.SUM, "price"), 1, 5.0, 5.0)), 1);
        expectIae(() -> AggMerger.merge(Arrays.asList(one, otherSpec)));
        expectIae(() -> AggMerger.mergeValues(null, one.value(0)));
        expectIae(() -> AggMerger.mergeValues(one.value(0), otherSpec.value(0)));

        // 分片合并 = 单次一次性执行（逐聚合全等）
        AggQueryPlan plan = plan5();
        List<NRow> all = rows(1000);
        AggExecutor<NRow> ex = new AggExecutor<>();
        AggResult single = ex.execute(plan, minterm -> all, COLS);
        AggResult shardA = ex.execute(plan, minterm -> all.subList(0, 500), COLS);
        AggResult shardB = ex.execute(plan, minterm -> all.subList(500, 1000), COLS);
        AggResult merged = AggMerger.merge(Arrays.asList(shardA, shardB));
        Assert.assertTrue("分片合并必须与单次执行逐聚合全等", AggResult.deepEquals(single, merged));
        Assert.assertEquals("unionRowCount Σ", single.unionRowCount, merged.unionRowCount);
        Assert.assertEquals("fingerprint 一致", single.fingerprint(), merged.fingerprint());
    }

    /* ================ helpers ================ */

    private static List<File> aggFiles() {
        File dir = new File(System.getProperty("user.dir"), AGG_SRC);
        Assert.assertTrue("cluster/agg 源目录不存在: " + dir.getAbsolutePath(), dir.isDirectory());
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

    private static void expectIae(Runnable r) {
        try {
            r.run();
            Assert.fail("期望 IAE");
        } catch (IllegalArgumentException expected) {
            // OK
        }
    }
}
