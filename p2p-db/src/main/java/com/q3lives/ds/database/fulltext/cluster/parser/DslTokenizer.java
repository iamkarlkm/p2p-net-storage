package com.q3lives.ds.database.fulltext.cluster.parser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * DslTokenizer LL(1) 词法扫描器（MVP v1.0 @since P30）：零回溯贪心扫描 DSL 字符串。
 * <ul>
 *   <li>9 Token 类型：IDENT / NUMBER / STRING / LPAREN / RPAREN / COMMA
 *   <li>7 运算符：OP_EQ(=) / OP_NE(!=) / OP_GT(>) / OP_GE(>=) / OP_LT(<) / OP_LE(<=)
 *   <li>12 关键字 KW：AND / BETWEEN / IN / IS / NOT / NULL / LIKE / WEIGHT / LTR / FT / TRUE / FALSE
 *   <li>空白自动跳过：' '\t\r\n；单行注释 "--" 到行尾
 *   <li>STRING 转义：\' → '；\\ → \；未闭合字符串抛 HybridQueryParseException（位置 = 引号列）
 *   <li>NUMBER 支持整数/浮点："123" / "123.456" / ".45" / "123."（负号作为独立 '-'，Parser 一元处理）
 * </ul>
 * 使用方式：new DslTokenizer(src).nextToken() 连续扫描返回带 value/pos 的 TokenEntry；peekToken(i)
 * 无限前窥（LL(*)，但 Parser 仅 LL(1) peekToken(0) 足以，MVP 不回溯）。位置信息 ParsePos 行号列号从 1。
 *
 * @author SP3.1 Implement Agent
 * @since P30 OQ1 QueryParser DSL MVP v1.0
 */
public final class DslTokenizer {

    private static final Map<String, DslToken> KEYWORDS = new LinkedHashMap<>();
    static {
        KEYWORDS.put("AND", DslToken.KW_AND);
        KEYWORDS.put("BETWEEN", DslToken.KW_BETWEEN);
        KEYWORDS.put("IN", DslToken.KW_IN);
        KEYWORDS.put("IS", DslToken.KW_IS);
        KEYWORDS.put("NOT", DslToken.KW_NOT);
        KEYWORDS.put("NULL", DslToken.KW_NULL);
        KEYWORDS.put("LIKE", DslToken.KW_LIKE);
        KEYWORDS.put("WEIGHT", DslToken.KW_WEIGHT);
        KEYWORDS.put("LTR", DslToken.KW_LTR);
        KEYWORDS.put("FT", DslToken.KW_FT);
        KEYWORDS.put("TRUE", DslToken.KW_TRUE);
        KEYWORDS.put("FALSE", DslToken.KW_FALSE);
        KEYWORDS.put("OR", DslToken.KW_OR);
    }

    /** Token + 值 + 位置（DslTokenizer 内部输出，Parser 用）。MVP 零分配记录类。 */
    public static final class TokenEntry {
        public final DslToken type;
        public final Object value;
        public final ParsePos pos;

        public TokenEntry(DslToken type, Object value, ParsePos pos) {
            this.type = Objects.requireNonNull(type, "type cannot be null");
            this.value = value;
            this.pos = Objects.requireNonNull(pos, "pos cannot be null");
        }
    }

    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;
    private final java.util.List<TokenEntry> buffer = new java.util.ArrayList<>();

    /**
     * 构造 DslTokenizer：ds1 非空（已 trim，允许空串→nextToken().type==EOF-like 但 T3 TR3.7 Parser 会报空 DSL）。
     *
     * @param src DSL 原始字符串（非 null）
     * @throws NullPointerException src null
     */
    public DslTokenizer(String src) {
        this.src = Objects.requireNonNull(src, "dsl src cannot be null");
        this.pos = 0;
    }

    /**
     * 取第 i 个前窥 Token（i=0 下一个；不消耗输入）。
     *
     * @param i 前窥深度（≥0）
     * @return TokenEntry（若无 Token → 返回 synthetic EOF=null，由 Parser 0 条件 IAE 处理）
     */
    public TokenEntry peekToken(int i) {
        while (buffer.size() <= i) {
            TokenEntry n = scanOne();
            if (n == null) return null;
            buffer.add(n);
        }
        return buffer.get(i);
    }

    /**
     * 消耗下一个 Token 并返回。
     *
     * @return 下一个 TokenEntry；无 Token 返回 null（EOF）
     */
    public TokenEntry nextToken() {
        if (!buffer.isEmpty()) return buffer.remove(0);
        return scanOne();
    }

    /**
     * 当前未读部分起始位置（用于定位）。
     *
     * @return ParsePos 单字符当前位置
     */
    public ParsePos position() {
        return ParsePos.single(line, col);
    }

    /* ----------- 内部扫描 ----------- */

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                advance(c);
            } else if (c == '-' && pos + 1 < src.length() && src.charAt(pos + 1) == '-') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    advance(src.charAt(pos));
                }
            } else {
                break;
            }
        }
    }

    private void advance(char c) {
        pos++;
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
    }

    private TokenEntry scanOne() {
        skipWhitespaceAndComments();
        if (pos >= src.length()) return null;
        int startL = line;
        int startC = col;
        char c = src.charAt(pos);
        if (c == '(') { advance(c); return mk(DslToken.LPAREN, "(", startL, startC); }
        if (c == ')') { advance(c); return mk(DslToken.RPAREN, ")", startL, startC); }
        if (c == ',') { advance(c); return mk(DslToken.COMMA, ",", startL, startC); }
        if (c == '=') { advance(c); return mk(DslToken.OP_EQ, "=", startL, startC); }
        if (c == '!') {
            advance(c);
            if (pos < src.length() && src.charAt(pos) == '=') {
                advance('=');
                return mk(DslToken.OP_NE, "!=", startL, startC);
            }
            throw new HybridQueryParseException("unexpected '!' (only '!=' allowed)",
                    ParsePos.single(startL, startC), fragment(startL, startC));
        }
        if (c == '>') {
            advance(c);
            if (pos < src.length() && src.charAt(pos) == '=') {
                advance('=');
                return mk(DslToken.OP_GE, ">=", startL, startC);
            }
            return mk(DslToken.OP_GT, ">", startL, startC);
        }
        if (c == '<') {
            advance(c);
            if (pos < src.length() && src.charAt(pos) == '=') {
                advance('=');
                return mk(DslToken.OP_LE, "<=", startL, startC);
            }
            return mk(DslToken.OP_LT, "<", startL, startC);
        }
        if (c == '\'') {
            StringBuilder sb = new StringBuilder();
            advance(c);
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == '\\' && pos + 1 < src.length()) {
                    advance(ch);
                    char nx = src.charAt(pos);
                    advance(nx);
                    if (nx == '\'') sb.append('\'');
                    else if (nx == '\\') sb.append('\\');
                    else sb.append(ch).append(nx);
                } else if (ch == '\'') {
                    advance(ch);
                    return mk(DslToken.STRING, sb.toString(), startL, startC);
                } else {
                    advance(ch);
                    sb.append(ch);
                }
            }
            throw new HybridQueryParseException("unclosed string literal",
                    ParsePos.single(startL, startC), fragment(startL, startC));
        }
        if ((c == '-' && pos + 1 < src.length()
                && (Character.isDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '.'))
                || Character.isDigit(c) || c == '.') {
            StringBuilder sb = new StringBuilder();
            if (c == '-') { sb.append(c); advance(c); }
            boolean dot = false;
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (Character.isDigit(ch)) {
                    sb.append(ch);
                    advance(ch);
                } else if (ch == '.' && !dot) {
                    dot = true;
                    sb.append(ch);
                    advance(ch);
                } else {
                    break;
                }
            }
            if (sb.length() == 1 && sb.charAt(0) == '.') {
                throw new HybridQueryParseException("lone '.' not allowed",
                        ParsePos.single(startL, startC), fragment(startL, startC));
            }
            if (sb.length() == 1 && sb.charAt(0) == '-') {
                throw new HybridQueryParseException("lone '-' not allowed",
                        ParsePos.single(startL, startC), fragment(startL, startC));
            }
            double dv = Double.parseDouble(sb.toString());
            return mk(DslToken.NUMBER, dv, startL, startC);
        }
        if (Character.isLetter(c) || c == '_') {
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (Character.isLetterOrDigit(ch) || ch == '_') {
                    sb.append(ch);
                    advance(ch);
                } else {
                    break;
                }
            }
            String up = sb.toString().toUpperCase(Locale.ROOT);
            DslToken kw = KEYWORDS.get(up);
            Object val = (kw == null) ? sb.toString() : up;
            DslToken type = (kw != null) ? kw : DslToken.IDENT;
            return mk(type, val, startL, startC);
        }
        throw new HybridQueryParseException("unexpected character '" + c + "'",
                ParsePos.single(startL, startC), fragment(startL, startC));
    }

    private TokenEntry mk(DslToken type, Object value, int sLine, int sCol) {
        return new TokenEntry(type, value, new ParsePos(sLine, sCol, line, Math.max(col - 1, sCol)));
    }

    private String fragment(int sLine, int sCol) {
        int from = Math.max(0, pos - 5);
        int to = Math.min(src.length(), pos + 5);
        return src.substring(from, to).replace("\n", "\\n").replace("\r", "\\r");
    }
}
