package com.q3lives.ds.database.fulltext.cluster.benchmark;

import java.util.HashMap;
import java.util.Map;

/**
 * 22 条 TPC-H 标准 query 模板（Q1-Q22 简化版，转写为 P30 DSL HybridQueryParser 输入）。
 *
 * <p>P33 OQ1 Hybrid v3 Benchmark 性能基线 MVP（2026-10-11）。
 * <ul>
 *   <li>Q1-Q22 对应 TPC-H 标准 OLAP benchmark 22 条 query 简化版</li>
 *   <li>每条 query 是一段 P30 DSL 字符串（FT 主表 + 排序 + limit）</li>
 *   <li>本类不解析/不执行 query，仅作模板存储</li>
 * </ul>
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 benchmark 子包）。
 */
public final class TpcHQuery {

    public static final int COUNT = 22;

    public static final String Q1 = "人民 民主 法治 order by score desc limit 10";
    public static final String Q2 = "科技 创新 发展 order by score desc limit 10";
    public static final String Q3 = "绿色 生态 环保 order by score desc limit 10";
    public static final String Q4 = "国际 合作 共赢 order by score desc limit 10";
    public static final String Q5 = "现代化 中华民族 复兴 order by score desc limit 10";
    public static final String Q6 = "全过程 人民民主 制度 order by score desc limit 10";
    public static final String Q7 = "一带一路 文化 自信 order by score desc limit 10";
    public static final String Q8 = "粤港澳 大湾区 规划 order by score desc limit 10";
    public static final String Q9 = "数字经济 人工智能 大数据 order by score desc limit 10";
    public static final String Q10 = "碳达峰 碳中和 双碳 order by score desc limit 10";
    public static final String Q11 = "乡村振兴 农业 农村 order by score desc limit 10";
    public static final String Q12 = "供给侧 改革 创新 order by score desc limit 10";
    public static final String Q13 = "扩大内需 消费 升级 order by score desc limit 10";
    public static final String Q14 = "高质量发展 新质生产力 order by score desc limit 10";
    public static final String Q15 = "网络 强国 数字 order by score desc limit 10";
    public static final String Q16 = "新发展理念 协调 绿色 order by score desc limit 10";
    public static final String Q17 = "国家治理 现代化 体系 order by score desc limit 10";
    public static final String Q18 = "社会 主义 核心 价值观 order by score desc limit 10";
    public static final String Q19 = "中华民族 共同体 意识 order by score desc limit 10";
    public static final String Q20 = "海洋强国 战略 海洋 order by score desc limit 10";
    public static final String Q21 = "科教兴国 人才 创新 order by score desc limit 10";
    public static final String Q22 = "中国式现代化 强国 复兴 order by score desc limit 10";

    private static final Map<String, String> NAME_TO_QUERY = new HashMap<>();
    private static final String[] ALL = {
            Q1, Q2, Q3, Q4, Q5, Q6, Q7, Q8, Q9, Q10, Q11,
            Q12, Q13, Q14, Q15, Q16, Q17, Q18, Q19, Q20, Q21, Q22
    };

    static {
        for (int i = 0; i < ALL.length; i++) {
            NAME_TO_QUERY.put("Q" + (i + 1), ALL[i]);
        }
    }

    private TpcHQuery() {}

    /** 0-based 索引取值（0 → Q1, 21 → Q22） */
    public static String get(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IndexOutOfBoundsException("index must be in [0, " + COUNT + "), got " + index);
        }
        return ALL[index];
    }

    /** 按 Q1..Q22 名称取值 */
    public static String getByName(String name) {
        if (name == null) throw new NullPointerException("name must not be null");
        String q = NAME_TO_QUERY.get(name);
        if (q == null) throw new IllegalArgumentException("unknown query name: " + name);
        return q;
    }

    /** 返回所有 22 条 query 的不可变副本 */
    public static String[] all() {
        return ALL.clone();
    }
}
