package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.Objects;

/**
 * HybridQueryParseException 解析异常（MVP v1.0 @since P30）：继承 RuntimeException 0 破坏 Facade。
 * <ul>
 *   <li>message：人类可读错误原因（必填，null→NPE）
 *   <li>pos：ParsePos 行号列号定位（非空，便于 T3 断言 "line 1, col X"）
 *   <li>dslFragment：DSL 10 字符上下文片段（错误位置 +/- 5 字符，含开头位置）
 * </ul>
 * Spec NFR-7 要求 100% 异常消息含 "line" / "col"，getMessage() 自动拼接 "line X, col Y: msg [fragment]"。
 * Parser/T3 绝对禁止抛无定位 RuntimeException/IAE，必须统一走本类（或 T1 结构 IAE）。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public class HybridQueryParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ParsePos pos;
    private final String dslFragment;

    /**
     * 构造解析异常：三字段全必填，null → NPE。
     *
     * @param message 错误原因文本
     * @param pos 位置信息（非空）
     * @param dslFragment DSL 错误位置上下文 10 字符片段（非空）
     * @throws NullPointerException 任一参数为 null
     */
    public HybridQueryParseException(String message, ParsePos pos, String dslFragment) {
        super(Objects.requireNonNull(message, "message cannot be null"));
        this.pos = Objects.requireNonNull(pos, "pos cannot be null");
        this.dslFragment = Objects.requireNonNull(dslFragment, "dslFragment cannot be null");
    }

    /**
     * 完整错误消息："line <L>, col <C>: <message> [fragment:'<dslFragment>']"；NFR-7 格式对齐。
     *
     * @return 含位置 + 错误 + 片段的完整描述
     */
    @Override
    public String getMessage() {
        return "line " + pos.startLine() + ", col " + pos.startCol() + ": " + super.getMessage()
                + " [fragment:'" + dslFragment + "']";
    }

    /**
     * 位置信息 ParsePos（非空）。
     *
     * @return ParsePos startLine/startCol/endLine/endCol 四元
     */
    public ParsePos getPos() {
        return pos;
    }

    /**
     * DSL 上下文片段（10 字符内）。
     *
     * @return 片段字符串
     */
    public String getDslFragment() {
        return dslFragment;
    }
}
