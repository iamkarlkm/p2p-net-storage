package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.ConditionNode;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggFn;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.AggQueryPlan;
import com.q3lives.ds.database.fulltext.cluster.parser.v3.MintermAggTask;

/**
 * P38 T4：Inproc E2E 聚合模拟 + 跨进程 fingerprint 3 TR。
 *
 * <p>
 * TR4.1 DSL → 计划 → N task 业务模拟聚合（对每 task 按 minterm 过滤内存行 + 计算聚合， 结果与手写真值全等）
 * <br>TR4.2 {@code (A OR B) AND C AGG(...)} 拆分 N 对（顺序 + minterm 节点全等 + 笛卡尔积）
 * <br>TR4.3 跨进程 AggQueryPlan fingerprint 一致（子 JVM 复现 64-hex）+ deepEquals=true
 */
public class P38_T4_E2eAggTest {

    /* ================ 内存行集（业务模拟：无需 DB） ================ */
    private static Map<String, Object> row(long a, long b, long price, double score, long c, long d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", a);
        m.put("b", b);
        m.put("price", price);
        m.put("score", score);
        m.put("c", c);
        m.put("d", d);
        return m;
    }

    private static List<Map<String, Object>> rows() {
        return Arrays.asList(
                row(1, 1, 10, 1.0, 7, 100),
                row(1, 2, 20, 2.0, 6, 200),
                row(2, 1, 30, 3.0, 5, 300),
                row(2, 2, 40, 4.0, 4, 400),
                row(3, 1, 50, 5.0, 3, 500));
    }

    /**
     * 业务模拟：按 minterm 逐条过滤（AND 语义）。
     */
    private static List<Map<String, Object>> filter(List<Map<String, Object>> src, List<ConditionNode> minterm) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : src) {
            boolean ok = true;
            for (ConditionNode n : minterm) {
                if (!match(n, r)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean match(ConditionNode n, Map<String, Object> r) {
        if (n.kind == ConditionNode.Kind.IS_NULL) {
            return r.get(n.ormCol) == null;
        }
        if (n.kind == ConditionNode.Kind.IS_NOT_NULL) {
            return r.get(n.ormCol) != null;
        }
        Object v = r.get(n.ormCol);
        if (n.ormOp == DslToken.OP_EQ) {
            return v != null && asDouble(v) == asDouble(n.ormValueA);
        }
        if (n.ormOp == DslToken.KW_BETWEEN) {
            return v != null && asDouble(v) >= asDouble(n.ormValueA) && asDouble(v) <= asDouble(n.ormValueB);
        }
        if (n.ormOp == DslToken.KW_IN) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) n.ormValueA;
            for (Object o : list) {
                if (asDouble(o) == asDouble(v)) {
                    return true;
                }
            }
            return false;
        }
        throw new IllegalStateException("未支持的 op: " + n.ormOp);
    }

    private static double asDouble(Object o) {
        return ((Number) o).doubleValue();
    }

    /**
     * 业务模拟：对过滤后的行集计算 aggFn 聚合。
     */
    private static double aggregate(AggFn fn, List<Map<String, Object>> filtered, String col) {
        if (fn == AggFn.COUNT) {
            int c = 0;
            for (Map<String, Object> r : filtered) {
                if (r.get(col) != null) {
                    c++;
                }
            }
            return c;
        }
        double s = 0;
        int n = 0;
        double mn = Double.POSITIVE_INFINITY;
        double mx = Double.NEGATIVE_INFINITY;
        for (Map<String, Object> r : filtered) {
            Object o = r.get(col);
            if (o == null) {
                continue;
            }
            double d = asDouble(o);
            s += d;
            n++;
            mn = Math.min(mn, d);
            mx = Math.max(mx, d);
        }
        if (fn == AggFn.SUM) {
            return s;
        }
        if (fn == AggFn.MIN) {
            return n == 0 ? Double.NaN : mn;
        }
        if (fn == AggFn.MAX) {
            return n == 0 ? Double.NaN : mx;
        }
        if (fn == AggFn.AVG) {
            return n == 0 ? Double.NaN : s / n;
        }
        throw new IllegalStateException("未支持的 aggFn: " + fn);
    }

    private static AggQueryPlan parse(String dsl) {
        return AggParser.parse(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, 8);
    }

    /* ================ TR4.1 DSL → 计划 → N task 业务模拟聚合 ================ */
    @Test
    public void tr41_e2ePlanDrivenAggregation() {
        AggQueryPlan p = parse("a = 1 AGG(COUNT(a), SUM(price), MIN(c), MAX(d), AVG(score))");
        Assert.assertEquals(1, p.clause.size());
        Assert.assertEquals(5, p.aggs.size());
        Assert.assertEquals(5, p.taskCount());
        List<Map<String, Object>> src = rows();
        double[] actual = new double[p.taskCount()];
        for (int i = 0; i < p.taskCount(); i++) {
            MintermAggTask t = p.tasks().get(i);
            actual[i] = aggregate(t.aggSpec.fn, filter(src, t.minterm), t.aggSpec.column);
        }
        // 手写真值：a=1 命中 row0(1,1,10,1.0,7,100) + row1(1,2,20,2.0,6,200)
        Assert.assertEquals("COUNT(a)", 2.0, actual[0], 1e-9);
        Assert.assertEquals("SUM(price)", 30.0, actual[1], 1e-9);
        Assert.assertEquals("MIN(c)", 6.0, actual[2], 1e-9);
        Assert.assertEquals("MAX(d)", 200.0, actual[3], 1e-9);
        Assert.assertEquals("AVG(score)", 1.5, actual[4], 1e-9);
    }

    /* ================ TR4.2 (A OR B) AND C 拆分 N 对 ================ */
    @Test
    public void tr42_cartesianSplitEquivalence() {
        AggQueryPlan p = parse("(a = 1 OR b = 2) AND c BETWEEN 3 AND 6 AGG(COUNT(a), SUM(price))");
        Assert.assertEquals("2 minterms", 2, p.clause.size());
        Assert.assertEquals("2 aggs", 2, p.aggs.size());
        Assert.assertEquals("N = 4", 4, p.taskCount());
        // 笛卡尔积逐条全等（顺序 = minterm 序 × agg 声明序）
        List<MintermAggTask> expected = new ArrayList<>();
        for (int i = 0; i < p.clause.size(); i++) {
            for (int j = 0; j < p.aggs.size(); j++) {
                expected.add(new MintermAggTask(i, p.clause.getMiniTerm(i), p.aggs.get(j)));
            }
        }
        Assert.assertEquals("任务列表逐条全等", expected, p.tasks());
        // minterm 节点与 clause 全等
        for (int i = 0; i < expected.size(); i++) {
            Assert.assertEquals("minterm 节点全等", p.clause.getMiniTerm(expected.get(i).mintermIndex),
                    p.tasks().get(i).minterm);
        }
        // 业务模拟聚合真值
        List<Map<String, Object>> src = rows();
        double[] actual = new double[4];
        for (int i = 0; i < 4; i++) {
            MintermAggTask t = p.tasks().get(i);
            actual[i] = aggregate(t.aggSpec.fn, filter(src, t.minterm), t.aggSpec.column);
        }
        // minterm0 = [a=1, c∈[3,6]] → row1 → COUNT=1, SUM=20
        // minterm1 = [b=2, c∈[3,6]] → row1(price=20),row3(price=40) → COUNT=2, SUM=60
        Assert.assertEquals(1.0, actual[0], 1e-9);
        Assert.assertEquals(20.0, actual[1], 1e-9);
        Assert.assertEquals(2.0, actual[2], 1e-9);
        Assert.assertEquals(60.0, actual[3], 1e-9);
    }

    /* ================ TR4.3 跨进程 fingerprint 一致 ================ */
    @Test
    public void tr43_crossProcessFingerprint() throws Exception {
        String[] dsls = {
            "a = 1 AGG(COUNT(a), SUM(price))",
            "(a = 1 OR b = 2) AND c BETWEEN 3 AND 6 AGG(COUNT(a), SUM(price))",
            "FT('hello') OR a = 1 AGG(MIN(c))",
            "a = 1"};
        for (String dsl : dsls) {
            AggQueryPlan local = parse(dsl);
            String localFp = local.fingerprint();
            String[] child = runHelperTwice(dsl);
            Assert.assertEquals("DSL='" + dsl + "' 子进程0 fingerprint 一致", localFp, child[0]);
            Assert.assertEquals("DSL='" + dsl + "' 子进程1 fingerprint 一致", localFp, child[1]);
            // deepEquals：本地重解析一次，语义全等
            Assert.assertTrue("deepEquals 自洽 '" + dsl + "'",
                    AggQueryPlan.deepEquals(local, parse(dsl)));
        }
    }

    /**
     * spawn 子 JVM 跑本测试类的 main()，捕获 stdout 首行（fingerprint 或 ERROR）。
     */
    private static String[] runHelperTwice(String dsl) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx512m",
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    P38_T4_E2eAggTest.class.getName(),
                    dsl);
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
     * 子 JVM 入口：解析 DSL 并打印 fingerprint（或 ERROR: ...）。
     */
    public static void main(String[] args) {
        try {
            AggQueryPlan p = AggParser.parse(DsTableAdapter.class, args[0], Map.of(), Map.of(), 256, 8);
            System.out.println(p.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }
}
