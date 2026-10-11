package com.q3lives.ds.database.fulltext.cluster.parser;

/**
 * ParsePos 位置信息 record（MVP v1.0 @since P30）：Token/异常定位行号列号从 1 起。
 * <ul>
 *   <li>startLine：Token 起始行（≥ 1）
 *   <li>startCol：Token 起始列（≥ 1，首字符所在列）
 *   <li>endLine：Token 结束行（多行 STRING/注释时可能 >startLine）
 *   <li>endCol：Token 结束列（最后字符所在列，inclusive）
 * </ul>
 * HybridQueryParseException 统一接收 ParsePos，错误消息格式化为"line X, col Y"，
 * 便于 T3/T5 断言行列号精确性。所有字段 public final（record 语义，不可变）。
 *
 * @param startLine 起始行号从 1
 * @param startCol 起始列号从 1
 * @param endLine 结束行号从 1
 * @param endCol 结束列号从 1（inclusive）
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public record ParsePos(int startLine, int startCol, int endLine, int endCol) {

    /**
     * 构造 ParsePos：行号列号均 ≥1，否则 IAE。
     *
     * @throws IllegalArgumentException 若 startLine<1 / startCol<1 / endLine<startLine /
     *         endLine==startLine 时 endCol<startCol
     */
    public ParsePos {
        if (startLine < 1) {
            throw new IllegalArgumentException("startLine must be >=1, actual=" + startLine);
        }
        if (startCol < 1) {
            throw new IllegalArgumentException("startCol must be >=1, actual=" + startCol);
        }
        if (endLine < startLine) {
            throw new IllegalArgumentException(
                    "endLine must >= startLine, actual endLine=" + endLine + " startLine=" + startLine);
        }
        if (endLine == startLine && endCol < startCol) {
            throw new IllegalArgumentException(
                    "endCol must >= startCol for same line, actual endCol=" + endCol + " startCol=" + startCol);
        }
    }

    /**
     * 单字符位置（start==end）：ParsePos(line,col,line,col)。
     *
     * @param line 行号 ≥1
     * @param col 列号 ≥1
     * @return ParsePos start==end
     * @throws IllegalArgumentException line/col <1
     */
    public static ParsePos single(int line, int col) {
        return new ParsePos(line, col, line, col);
    }
}
