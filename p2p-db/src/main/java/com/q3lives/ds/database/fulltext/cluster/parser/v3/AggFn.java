package com.q3lives.ds.database.fulltext.cluster.parser.v3;

import java.util.Locale;

/**
 * AggFn 聚合函数枚举（P38 OQ1 DSL v3 聚合函数 MVP）。
 *
 * <p>
 * 5 个聚合函数 + 唯一 wire 字符码（跨进程序列化用）：
 * <ul>
 * <li>{@link #COUNT} → {@code 'C'}</li>
 * <li>{@link #SUM} → {@code 'S'}</li>
 * <li>{@link #AVG} → {@code 'A'}</li>
 * <li>{@link #MIN} → {@code 'M'}</li>
 * <li>{@link #MAX} → {@code 'X'}</li>
 * </ul>
 *
 * <p>
 * 双向映射：{@link #code()} 枚举 → 字符；{@link #fromCode(char)} 字符 → 枚举；
 * {@link #fromName(String)} 名称 → 枚举（大小写不敏感，如 "count" / "COUNT" 均可）。
 * 三者任一路径非法输入一律抛 {@link IllegalArgumentException}，绝不静默返回 null。
 *
 * <p>
 * 与 P30 词法的关系：{@code COUNT} / {@code SUM} / {@code AVG} / {@code MIN} / {@code MAX}
 * 均 <b>不</b> 注册进 P30 {@code DslTokenizer.KEYWORDS}，因此词法层天然落 {@code IDENT}，
 * 由本枚举的 {@link #fromName(String)} 完成语义识别（P30 源码字节级 0 改）。
 *
 * @author SP3.1 Implement Agent
 * @since P38 OQ1 DSL v3 聚合函数 MVP
 */
public enum AggFn {

    COUNT('C'),
    SUM('S'),
    AVG('A'),
    MIN('M'),
    MAX('X');

    private final char code;

    AggFn(char code) {
        this.code = code;
    }

    /**
     * 返回 wire 字符码（跨进程 wire 用）。
     *
     * @return 单字符码 C / S / A / M / X
     */
    public char code() {
        return code;
    }

    /**
     * 字符码 → enum 反查。
     *
     * @param c 单字符码
     * @return 匹配的 AggFn
     * @throws IllegalArgumentException 未匹配任何 AggFn code
     */
    public static AggFn fromCode(char c) {
        for (AggFn f : values()) {
            if (f.code == c) {
                return f;
            }
        }
        throw new IllegalArgumentException("未知 AggFn code: " + c + " (期望 C/S/A/M/X)");
    }

    /**
     * 名称 → enum 反查（大小写不敏感，自动 trim）。
     *
     * @param name 函数名（如 "COUNT" / "count" / " Count "）
     * @return 匹配的 AggFn
     * @throws IllegalArgumentException name == null / blank / 未匹配任何 AggFn 名称
     */
    public static AggFn fromName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("AggFn name cannot be null");
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("AggFn name cannot be blank");
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        for (AggFn f : values()) {
            if (f.name().equals(upper)) {
                return f;
            }
        }
        throw new IllegalArgumentException("未知 AggFn name: " + name + " (期望 COUNT/SUM/AVG/MIN/MAX)");
    }
}
