package com.q3lives.ds.database.fulltext.cluster.parser.v2;

import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * DnfParserHelperMain 子进程解析工具（P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 MVP）。
 *
 * <p>用于 T4 跨进程测试：spawn 子 JVM 跑此 main，传入 dsl 命令行参数，输出 fingerprint to stdout。
 * 父进程读取子进程 stdout 拿到 fingerprint 与本地解析的 fingerprint 比对，验证跨进程 DNF 解析稳定。
 *
 * <p>调用方式：
 * <pre>java -cp ... com.q3lives.ds.database.fulltext.cluster.parser.v2.DnfParserHelperMain "a = 1 OR b = 2"</pre>
 *
 * <p>输出格式：单行 64-char hex SHA-256 fingerprint；如解析失败输出 "ERROR: <msg>"。
 *
 * <p>子进程 main 不在生产代码路径使用（@since P34 标记为 TEST-ONLY），但为子包内工具类
 * 允许放在 src/main 避免 test classpath 加载不到（p2p-db test classpath 包含 main）。
 *
 * @author SP3.1 Implement Agent
 * @since P34 OQ1 DSL v2 MVP
 */
public final class DnfParserHelperMain {

    private DnfParserHelperMain() {}

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("ERROR: missing dsl arg");
            System.exit(1);
        }
        String dsl = args[0];
        try {
            DnfClause d = DnfParser.parseOr(
                    com.q3lives.ds.database.adapter.DsTableAdapter.class,
                    dsl,
                    new LinkedHashMap<String, java.util.function.ToDoubleFunction<com.q3lives.ds.database.adapter.DsTableAdapter>>(),
                    new LinkedHashMap<String, java.util.function.ToDoubleFunction<com.q3lives.ds.database.adapter.DsTableAdapter>>(),
                    256,
                    DnfReturnMode.DNF_ONLY);
            System.out.println(d.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            System.exit(2);
        }
        // 防 daemon 进程未 flush stdout
        System.out.flush();
    }
}
