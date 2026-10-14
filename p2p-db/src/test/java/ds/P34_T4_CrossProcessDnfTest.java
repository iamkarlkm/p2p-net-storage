package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfClause;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParser;
import com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfReturnMode;

/**
 * P34 T4：跨 Process TCP 解析 3 TR。
 *
 * <p>
 * TR4.1 2 子进程解析同 DSL：父子进程各跑 DnfParserHelperMain，输出 fingerprint 一致
 * <br>TR4.2 跨进程 DnfClause deepEquals：父子进程 DnfClause 通过 fingerprint 字符串比对实现等价
 * <br>TR4.3 跨进程 NOT IAE 异常一致性：父子进程解析非法 DSL 都输出 "ERROR: ..."
 */
public class P34_T4_CrossProcessDnfTest {

    private static final String HELPER
            = "com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParserHelperMain";

    /**
     * spawn 子 JVM 跑 DnfParserHelperMain，捕获 stdout 第一行（fingerprint 或 ERROR）。
     */
    private static String[] runHelperTwice(String dsl) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(
                    javaExe, "-Xmx1024m",
                    "-Dfile.encoding=UTF-8",
                    "-cp", classpath,
                    HELPER,
                    dsl);
            // 屏蔽父进程 _JAVA_OPTIONS 子进程会 echo 到 stdout
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String fpLine = null;
            String line;
            long deadline = System.currentTimeMillis() + 30_000L;
            while ((line = br.readLine()) != null) {
                // 过滤 JVM 启动 banner
                if (line.startsWith("Picked up ")) {
                    continue;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                fpLine = line.trim();
                break;
            }
            p.waitFor(10, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            outputs[i] = fpLine == null ? "" : fpLine;
        }
        return outputs;
    }

    /* -------- TR4.1 2 子进程解析同 DSL -------- */
    @Test
    public void tr41_twoSubprocesses() throws Exception {
        String dsl = "(a = 1 OR b = 2) AND (c = 3 OR d = 4)";
        // 父进程本地解析
        DnfClause d = DnfParser.parseOr(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
        String localFp = d.fingerprint();
        // 2 子进程解析
        String[] out = runHelperTwice(dsl);
        Assert.assertEquals("子进程0 fingerprint", localFp, out[0]);
        Assert.assertEquals("子进程1 fingerprint", localFp, out[1]);
    }

    /* -------- TR4.2 跨进程 DnfClause deepEquals 等价 -------- */
    @Test
    public void tr42_crossProcessDeepEquals() throws Exception {
        String[] dsls = {
            "a = 1 OR b = 2",
            "a = 1 AND (b = 2 OR c = 3)",
            "(a = 1 OR b = 2) AND (c = 3 OR d = 4)",
            "FT('hello') AND a = 1 OR b = 2"
        };
        for (String dsl : dsls) {
            DnfClause d = DnfParser.parseOr(DsTableAdapter.class, dsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
            String localFp = d.fingerprint();
            String[] out = runHelperTwice(dsl);
            Assert.assertEquals("DSL='" + dsl + "' 子进程0 fingerprint 一致", localFp, out[0]);
            Assert.assertEquals("DSL='" + dsl + "' 子进程1 fingerprint 一致", localFp, out[1]);
        }
    }

    /* -------- TR4.3 跨进程 NOT IAE 异常一致性 -------- */
    @Test
    public void tr43_crossProcessNotIae() throws Exception {
        String badDsl = "NOT (a = 1 OR b = 2)";
        // 父进程本地抛 IAE
        try {
            DnfParser.parseOr(DsTableAdapter.class, badDsl, Map.of(), Map.of(), 256, DnfReturnMode.DNF_ONLY);
            Assert.fail("父进程应抛 IAE");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue("msg 含 NOT 跨 OR", e.getMessage().contains("NOT 不得跨越 OR"));
        }
        // 子进程输出 ERROR
        String[] out = runHelperTwice(badDsl);
        Assert.assertTrue("子进程0 异常输出", out[0].startsWith("ERROR:"));
        Assert.assertTrue("子进程1 异常输出", out[1].startsWith("ERROR:"));
        Assert.assertTrue("子进程0 异常 msg 含 NOT 跨 OR", out[0].contains("NOT 不得跨越 OR"));
    }
}
