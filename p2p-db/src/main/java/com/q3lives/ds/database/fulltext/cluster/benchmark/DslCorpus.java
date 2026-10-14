package com.q3lives.ds.database.fulltext.cluster.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 100 条 P30 DSL 随机 query 模板。
 *
 * <p>
 * P33 OQ1 Hybrid v3 Benchmark 性能基线 MVP（2026-10-11）。 由 5 类模板 × 4 字段组合 × 5 排序 =
 * 100 条 DSL 字符串，覆盖 P30 DSL 解析器全部分支。
 *
 * <p>
 * 模板字段说明：
 * <ul>
 * <li>5 个 FT 关键词模板（人民民主/科技创新/绿色生态/国际合作/现代化复兴）</li>
 * <li>4 个 limit 数值（5/10/20/50）</li>
 * <li>5 个排序选项（score desc/asc + docId asc/desc + finalHybridScore desc）</li>
 * </ul>
 *
 * <p>
 * 所有 DSL query 在构造期通过 static initializer 一次性生成，运行期只读不可变。
 *
 * <p>
 * 红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 benchmark 子包）。
 */
public final class DslCorpus {

    public static final int COUNT = 100;

    private static final List<String> ALL;

    static {
        // 5 个 FT 关键词模板
        String[] ftKeywords = {
            "人民 民主",
            "科技 创新",
            "绿色 生态",
            "国际 合作",
            "现代化 复兴"
        };
        // 4 个 limit
        int[] limits = {5, 10, 20, 50};
        // 5 个排序
        String[] orders = {
            "order by score desc",
            "order by score asc",
            "order by docId asc",
            "order by docId desc",
            "order by finalHybridScore desc"
        };
        List<String> out = new ArrayList<>(100);
        int idx = 0;
        for (int k = 0; k < ftKeywords.length && idx < 100; k++) {
            for (int l = 0; l < limits.length && idx < 100; l++) {
                for (int o = 0; o < orders.length && idx < 100; o++) {
                    out.add(ftKeywords[k] + " " + orders[o] + " limit " + limits[l]);
                    idx++;
                }
            }
        }
        ALL = Collections.unmodifiableList(out);
    }

    private DslCorpus() {
    }

    /**
     * 0-based 索引取值（0..99）
     */
    public static String get(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IndexOutOfBoundsException("index must be in [0, " + COUNT + "), got " + index);
        }
        return ALL.get(index);
    }

    /**
     * 返回所有 100 条 DSL 的不可变副本
     */
    public static List<String> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL));
    }

    /**
     * 返回只读视图（不可修改）
     */
    public static List<String> readOnlyView() {
        return ALL;
    }
}
