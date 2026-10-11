package com.q3lives.ds.database.fulltext.cluster.parser;

/**
 * DslToken 枚举（MVP v1.0 @since P30）：LL(1) 递归下降词法单元类型。
 * 9 类基础 Token + 12 个保留关键字；IDENT 序列命中关键字 enum 时自动转关键字 token。
 * <ul>
 *   <li>基础 9 类：IDENT / NUMBER / STRING / LPAREN / RPAREN / COMMA
 *   <li>运算符 7 个：OP_EQ / OP_NE / OP_GT / OP_GE / OP_LT / OP_LE
 *   <li>关键字 12 个：AND / BETWEEN / IN / IS / NOT / NULL / LIKE / WEIGHT / LTR / FT / TRUE / FALSE
 * </ul>
 * 所有 Token 均由 DslTokenizer 扫描时分配 startLine/startCol/endLine/endCol 位置信息，
 * 便于 HybridQueryParseException 精确定位到列。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public enum DslToken {
    IDENT,
    NUMBER,
    STRING,
    LPAREN,
    RPAREN,
    COMMA,
    OP_EQ,
    OP_NE,
    OP_GT,
    OP_GE,
    OP_LT,
    OP_LE,
    KW_AND,
    KW_BETWEEN,
    KW_IN,
    KW_IS,
    KW_NOT,
    KW_NULL,
    KW_LIKE,
    KW_WEIGHT,
    KW_LTR,
    KW_FT,
    KW_TRUE,
    KW_FALSE,
    KW_OR;
}
