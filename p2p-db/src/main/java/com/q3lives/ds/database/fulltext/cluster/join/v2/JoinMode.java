package com.q3lives.ds.database.fulltext.cluster.join.v2;

import java.util.Objects;

/**
 * JoinMode 枚举（P35 OQ1 ORM 多表 Join v2 DsManyToOne MVP）。
 *
 * <p>Join 类型二元：ONE_TO_ONE（P31 MVP 1:1 等值 Join）/ MANY_TO_ONE（P35 新增 N:1 聚合）。
 * <ul>
 *   <li>ONE_TO_ONE：每个主表行 ↔ 最多 1 个从表行；{@code joinProp} 是从表外键列</li>
 *   <li>MANY_TO_ONE：每个主表行 ↔ 0..N 个从表行；{@code joinProp} 是从表外键列；
 *       走 IN 子查询 + FetchSize 批量 + aggregateByOuterId 聚合</li>
 * </ul>
 *
 * <p>字符码 wire 序列化（供 JoinSpec 跨进程序列化用）：O=ONE_TO_ONE, M=MANY_TO_ONE。
 *
 * @author SP3.1 Implement Agent
 * @since P35 OQ1 ORM Join v2 MVP
 */
public enum JoinMode {
    ONE_TO_ONE('O'),
    MANY_TO_ONE('M');

    private final char code;

    JoinMode(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    /**
     * 字符 → enum 反查（跨进程 wire 用），未匹配抛 IAE。
     *
     * @param c 单字符 O / M
     * @return 对应 enum
     * @throws IllegalArgumentException c == null 或未匹配
     */
    public static JoinMode fromCode(char c) {
        for (JoinMode m : values()) {
            if (m.code == c) return m;
        }
        throw new IllegalArgumentException("未知 JoinMode code: " + c + " (期望 O / M)");
    }

    /**
     * 字符串 → enum（null → null，匹配 fromCode 同语义）。
     *
     * @param s 单字符字符串
     * @return enum 或 null
     */
    public static JoinMode fromString(String s) {
        if (s == null) return null;
        if (s.length() != 1) {
            throw new IllegalArgumentException("JoinMode 字符串必须 1 字符: " + s);
        }
        return fromCode(s.charAt(0));
    }
}
