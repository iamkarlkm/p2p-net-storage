package com.q3lives.ds.database.fulltext.cluster.parser.v2;

import java.util.Objects;

/**
 * DnfReturnMode 枚举（P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 MVP）。
 *
 * <p>HybridQueryParser.parseOr 的返回模式二元：
 * <ul>
 *   <li>{@link #DNF_ONLY}：返回 {@link DnfClause} 不可变实例，包含 N 个 minterm 供业务方自行串/并行执行</li>
 *   <li>{@link #LEGACY_COMPAT}：自动 fallback 走 P30 HybridQueryParser.parse（OR 抛 IAE，行为字节级保持）</li>
 * </ul>
 *
 * <p>Enum 字符映射 wire（供跨进程序列化用）：D=DNF_ONLY, L=LEGACY_COMPAT。
 * parseOr 入参校验：mode 非空（不允许 null），不允许自定义 value。
 *
 * @author SP3.1 Implement Agent
 * @since P34 OQ1 DSL v2 MVP
 */
public enum DnfReturnMode {
    DNF_ONLY('D'),
    LEGACY_COMPAT('L');

    private final char code;

    DnfReturnMode(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    /**
     * 字符 → enum 反查（跨进程 wire 用），未匹配抛 IAE。
     *
     * @param code 单字符 D / L
     * @return 对应 enum
     * @throws IllegalArgumentException code == null 或未匹配
     */
    public static DnfReturnMode fromCode(char code) {
        Objects.requireNonNull(code, "code cannot be null");
        for (DnfReturnMode m : values()) {
            if (m.code == code) return m;
        }
        throw new IllegalArgumentException("未知 DnfReturnMode code: " + code + " (期望 D / L)");
    }

    /**
     * 字符串 → enum（允许 null → null，匹配 fromCode 同语义）。
     *
     * @param s 单字符字符串
     * @return enum 或 null
     */
    public static DnfReturnMode fromString(String s) {
        if (s == null) return null;
        if (s.length() != 1) {
            throw new IllegalArgumentException("DnfReturnMode 字符串必须 1 字符: " + s);
        }
        return fromCode(s.charAt(0));
    }
}
