package com.q3lives.ds.database.fulltext.cluster.parser.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.fulltext.cluster.parser.DslToken;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer;
import com.q3lives.ds.database.fulltext.cluster.parser.DslTokenizer.TokenEntry;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParseException;
import com.q3lives.ds.database.fulltext.cluster.parser.HybridQueryParser;
import com.q3lives.ds.database.fulltext.cluster.parser.ParsePos;

/**
 * DnfParser 静态工具类（P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 MVP）。
 *
 * <p>
 * 主入口：{@link #parseOr(Class, String, Map, Map, int, DnfReturnMode)}，6 参。 内部走 5
 * 阶段管线：
 * <ol>
 * <li>Tokenize：复用 P30 DslTokenizer（不破坏 P30 字节级）</li>
 * <li>构造 AST：OrNode / AndNode / ConditionNode（LL(1) 递归下降，OR 左结合、AND 优先于 OR、( )
 * 改变优先级）</li>
 * <li>DNF 化：递归 distribute AND over OR（distribute 顺序：左子树优先；NOT 跨越 OR 抛 IAE）</li>
 * <li>拆分 minterm：每条 minterm = List&lt;ConditionNode&gt; AND-flat（去重 + 排序）</li>
 * <li>校验：maxDnfSize ≤ 256 硬上限（超出 IAE），任一 ConditionNode 8 IAE 全检</li>
 * </ol>
 *
 * <p>
 * 与 P30 MVP 的关系（关键约束）：
 * <ul>
 * <li>P30 HybridQueryParser.parse 4 参签名 1 字不改（P30 字节级 0 破坏）</li>
 * <li>本类为 NEW 独立类，不修改 HybridQueryParser 任何字段/方法体</li>
 * <li>当 mode=LEGACY_COMPAT 时直接调用 HybridQueryParser.parse 入参（不读 dsl）保证与 P30
 * 行为字节级一致</li>
 * <li>P30 DslToken 已有 KW_OR / KW_NOT（占位），本类实装语义</li>
 * </ul>
 *
 * <p>
 * DNF 范式算法核心 distribute(Node)：
 * <pre>
 *   distribute(OrNode(a,b)) = OrNode(distribute(a), distribute(b))
 *   distribute(AndNode(a,b)) = distribute(a) × distribute(b)（笛卡尔积）
 *   distribute(ConditionNode) = [ConditionNode]（单元素 minterm 列表）
 * </pre> 其中 × 是 distribute 后的"组合"：A × B = 拼接每条 minterm 形成新 minterm，最终压平。
 *
 * @author SP3.1 Implement Agent
 * @since P34 OQ1 DSL v2 MVP
 */
public final class DnfParser {

    /**
     * DNF 拆分硬上限（R8）
     */
    public static final int MAX_DNF_SIZE = 256;

    private DnfParser() {
    }

    /**
     * 解析 DSL 字符串 → DnfClause 不可变实例。
     *
     * @param entityClass 非空（校验用，LEGACY_COMPAT 模式才用）
     * @param dsl 非空 DSL 字符串（允许含 OR/NOT/括号/AND/FT/ORM）
     * @param weightMap 允许 null 或 empty（V2 不消费，保留接口对齐）
     * @param ltrMap 允许 null 或 empty（同上）
     * @param maxDnfSize 必须 ≥ 1；上限保护（≤ MAX_DNF_SIZE）
     * @param mode 非空（DNF_ONLY 返回 DnfClause；LEGACY_COMPAT 走 P30 parse）
     * @param <T> DsTableAdapter 实体
     * @return DnfClause 不可变实例
     * @throws NullPointerException entityClass/dsl/mode == null
     * @throws IllegalArgumentException maxDnfSize ≤ 0 或超 MAX_DNF_SIZE；或 NOT 跨
     * OR；或 DNF 拆分超 maxDnfSize
     * @throws HybridQueryParseException 解析结构/语法错误
     */
    public static <T extends DsTableAdapter> DnfClause parseOr(
            Class<T> entityClass,
            String dsl,
            Map<String, ToDoubleFunction<T>> weightMap,
            Map<String, ToDoubleFunction<T>> ltrMap,
            int maxDnfSize,
            DnfReturnMode mode) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        Objects.requireNonNull(dsl, "dsl cannot be null");
        Objects.requireNonNull(mode, "mode cannot be null");
        if (maxDnfSize <= 0) {
            throw new IllegalArgumentException("maxDnfSize 必须 ≥ 1: " + maxDnfSize);
        }
        if (maxDnfSize > MAX_DNF_SIZE) {
            throw new IllegalArgumentException("maxDnfSize 超过硬上限 " + MAX_DNF_SIZE + ": " + maxDnfSize);
        }
        // weightMap/ltrMap 允许 null 或 empty（V2 暂不消费 WEIGHT/LTR 段；如需可后续扩展）
        if (weightMap == null) {
            weightMap = new java.util.LinkedHashMap<>();
        }
        if (ltrMap == null) {
            ltrMap = new java.util.LinkedHashMap<>();
        }
        if (mode == DnfReturnMode.LEGACY_COMPAT) {
            // 行为字节级 = P30 parse：OR/NOT/括号 → IAE（与 P30 抛一致）
            HybridQueryParser.parse(entityClass, dsl, weightMap, ltrMap);
            // 不应到达（P30 必抛 IAE for 含 OR 的 dsl）
            throw new IllegalStateException("LEGACY_COMPAT 模式 P30 parse 应已抛 IAE 但未抛");
        }
        // DNF_ONLY 模式
        DslTokenizer tz = new DslTokenizer(dsl);
        Node ast = parseOrExpr(tz, dsl);
        TokenEntry tail = tz.nextToken();
        if (tail != null) {
            throw err("OR 表达式后多余 token: " + tail.type, tail.pos);
        }
        // 校验 NOT 不跨 OR（递归扫描）
        validateNoNotOverOr(ast, dsl);
        // DNF 化
        List<List<ConditionNode>> dnf = toDnf(ast, dsl);
        // 校验 maxDnfSize
        if (dnf.size() > maxDnfSize) {
            throw new IllegalArgumentException("DNF 拆分 " + dnf.size() + " 个 minterm 超过 maxDnfSize=" + maxDnfSize);
        }
        return new DnfClause(dnf);
    }

    /* ============================================================
     *  AST 节点定义（私有内部类）
     * ============================================================ */
    private interface Node {
    }

    private static final class OrNode implements Node {

        final Node left, right;

        OrNode(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class AndNode implements Node {

        final Node left, right;

        AndNode(Node left, Node right) {
            this.left = left;
            this.right = right;
        }
    }

    private static final class NotNode implements Node {

        final Node child;

        NotNode(Node child) {
            this.child = child;
        }
    }

    private static final class CondNode implements Node {

        final ConditionNode cond;
        final boolean negated;

        CondNode(ConditionNode cond, boolean negated) {
            this.cond = cond;
            this.negated = negated;
        }
    }

    /* ============================================================
     *  LL(1) 递归下降（OR 顶层）
     * ============================================================ */
    /**
     * 解析 orExpr := andExpr ('OR' andExpr)*
     */
    private static Node parseOrExpr(DslTokenizer tz, String dsl) {
        Node left = parseAndExpr(tz, dsl);
        while (true) {
            TokenEntry peek = tz.peekToken(0);
            if (peek == null) {
                return left;
            }
            if (peek.type != DslToken.KW_OR) {
                return left;
            }
            consume(tz);
            Node right = parseAndExpr(tz, dsl);
            left = new OrNode(left, right);
        }
    }

    /**
     * 解析 andExpr := notExpr ('AND' notExpr)*
     */
    private static Node parseAndExpr(DslTokenizer tz, String dsl) {
        Node left = parseNotExpr(tz, dsl);
        while (true) {
            TokenEntry peek = tz.peekToken(0);
            if (peek == null) {
                return left;
            }
            if (peek.type != DslToken.KW_AND) {
                return left;
            }
            consume(tz);
            Node right = parseNotExpr(tz, dsl);
            left = new AndNode(left, right);
        }
    }

    /**
     * 解析 notExpr := 'NOT' notExpr | primary
     */
    private static Node parseNotExpr(DslTokenizer tz, String dsl) {
        TokenEntry peek = tz.peekToken(0);
        if (peek == null) {
            throw err("意外 EOF（期望 NOT / 条件 / '('）", tz.position());
        }
        if (peek.type == DslToken.KW_NOT) {
            consume(tz);
            Node child = parseNotExpr(tz, dsl);
            return new NotNode(child);
        }
        return parsePrimary(tz, dsl);
    }

    /**
     * 解析 primary := '(' orExpr ')' | ft-cond | orm-cond
     */
    private static Node parsePrimary(DslTokenizer tz, String dsl) {
        TokenEntry first = tz.peekToken(0);
        if (first == null) {
            throw err("意外 EOF（期望 '(' / FT / 列名）", tz.position());
        }
        if (first.type == DslToken.LPAREN) {
            consume(tz);
            Node inner = parseOrExpr(tz, dsl);
            expect(tz, DslToken.RPAREN, dsl);
            return inner;
        }
        if (first.type == DslToken.KW_FT) {
            consume(tz);
            expect(tz, DslToken.LPAREN, dsl);
            TokenEntry s = expect(tz, DslToken.STRING, dsl);
            expect(tz, DslToken.RPAREN, dsl);
            return new CondNode(ConditionNode.ft(((String) s.value).trim()), false);
        }
        // ORM 条件
        if (first.type == DslToken.IDENT) {
            TokenEntry col = consume(tz);
            TokenEntry op = consume(tz);
            switch (op.type) {
                case OP_EQ:
                case OP_NE:
                case OP_GT:
                case OP_GE:
                case OP_LT:
                case OP_LE:
                case KW_LIKE: {
                    Object v = toJava(consume(tz));
                    String pat = v == null ? "" : v.toString();
                    if (op.type == DslToken.KW_LIKE) {
                        // LIKE 仍按 EQ 处理（用 OP_EQ 标记，业务方后续走 like）
                        return new CondNode(ConditionNode.orm((String) col.value, DslToken.OP_EQ, pat), false);
                    }
                    return new CondNode(ConditionNode.orm((String) col.value, op.type, v), false);
                }
                case KW_BETWEEN: {
                    Object lo = toJava(consume(tz));
                    expect(tz, DslToken.KW_AND, dsl);
                    Object hi = toJava(consume(tz));
                    return new CondNode(ConditionNode.between((String) col.value, lo, hi), false);
                }
                case KW_IN: {
                    List<Object> list = parseInList(tz, dsl);
                    return new CondNode(ConditionNode.orm((String) col.value, DslToken.KW_IN, list), false);
                }
                case KW_NOT: {
                    TokenEntry nx = consume(tz);
                    if (nx.type == DslToken.KW_IN) {
                        List<Object> list = parseInList(tz, dsl);
                        return new CondNode(ConditionNode.orm((String) col.value, DslToken.KW_IN, list), false);
                    } else if (nx.type == DslToken.KW_NULL) {
                        return new CondNode(ConditionNode.isNotNull((String) col.value), false);
                    }
                    throw err("NOT 后仅允许 IN / NULL", nx.pos);
                }
                case KW_IS: {
                    TokenEntry p = consume(tz);
                    if (p.type == DslToken.KW_NOT) {
                        expect(tz, DslToken.KW_NULL, dsl);
                        return new CondNode(ConditionNode.isNotNull((String) col.value), false);
                    } else if (p.type == DslToken.KW_NULL) {
                        return new CondNode(ConditionNode.isNull((String) col.value), false);
                    }
                    throw err("IS 后仅允许 [NOT] NULL", p.pos);
                }
                default:
                    throw err("未支持 OP " + op.type, op.pos);
            }
        }
        throw err("未知开头 Token: " + first.type, first.pos);
    }

    /* ============================================================
     *  IN 列表
     * ============================================================ */
    private static List<Object> parseInList(DslTokenizer tz, String dsl) {
        expect(tz, DslToken.LPAREN, dsl);
        List<Object> list = new ArrayList<>();
        list.add(toJava(consume(tz)));
        while (tz.peekToken(0) != null && tz.peekToken(0).type == DslToken.COMMA) {
            consume(tz);
            list.add(toJava(consume(tz)));
        }
        expect(tz, DslToken.RPAREN, dsl);
        return list;
    }

    /* ============================================================
     *  DNF 化：distribute AND over OR（核心算法）
     * ============================================================ */
    /**
     * AST → DNF minterm 列表（OR of AND）。 算法： distribute(OrNode(a,b)) =
     * distribute(a) ∪ distribute(b) distribute(AndNode(a,b)) = { ax ∪ bx |
     * ax∈distribute(a), bx∈distribute(b) }（笛卡尔积） distribute(NotNode(c)) = 抛
     * IAE（NOT 跨 OR 不允许；pre-check 已拦截；这里兜底） distribute(CondNode) = [{c}]
     */
    @SuppressWarnings("unchecked")
    private static List<List<ConditionNode>> toDnf(Node root, String dsl) {
        List<List<ConditionNode>> result = distribute(root, dsl);
        // 排序：minterm 内按 (kind, ftQuery/ormCol, ormOp) 字典序；minterm 列表按 size 升序再字符串序
        for (List<ConditionNode> mt : result) {
            mt.sort((x, y) -> {
                int c = Integer.compare(x.kind.ordinal(), y.kind.ordinal());
                if (c != 0) {
                    return c;
                }
                c = safeCompare(x.ftQuery, y.ftQuery);
                if (c != 0) {
                    return c;
                }
                c = safeCompare(x.ormCol, y.ormCol);
                if (c != 0) {
                    return c;
                }
                c = Integer.compare(x.ormOp == null ? 0 : x.ormOp.ordinal(),
                        y.ormOp == null ? 0 : y.ormOp.ordinal());
                return c;
            });
        }
        result.sort((a, b) -> {
            int c = Integer.compare(a.size(), b.size());
            if (c != 0) {
                return c;
            }
            for (int i = 0; i < a.size(); i++) {
                c = a.get(i).toString().compareTo(b.get(i).toString());
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        return result;
    }

    private static int safeCompare(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        return a.compareTo(b);
    }

    @SuppressWarnings("unchecked")
    private static List<List<ConditionNode>> distribute(Node n, String dsl) {
        if (n instanceof OrNode) {
            OrNode o = (OrNode) n;
            List<List<ConditionNode>> left = distribute(o.left, dsl);
            List<List<ConditionNode>> right = distribute(o.right, dsl);
            List<List<ConditionNode>> merged = new ArrayList<>(left.size() + right.size());
            merged.addAll(left);
            merged.addAll(right);
            return merged;
        }
        if (n instanceof AndNode) {
            AndNode a = (AndNode) n;
            List<List<ConditionNode>> left = distribute(a.left, dsl);
            List<List<ConditionNode>> right = distribute(a.right, dsl);
            List<List<ConditionNode>> product = new ArrayList<>(left.size() * right.size());
            for (List<ConditionNode> l : left) {
                for (List<ConditionNode> r : right) {
                    List<ConditionNode> combined = new ArrayList<>(l.size() + r.size());
                    combined.addAll(l);
                    combined.addAll(r);
                    product.add(combined);
                }
            }
            return product;
        }
        if (n instanceof NotNode) {
            // validateNoNotOverOr 预检已拦截 NOT 跨 OR；此处仅当 NotNode 包裹合法单条件
            // （当前 V2 实现：IS NOT NULL / col NOT IN 已转 CondNode.isNotNull / orm(KW_IN,...) 不走 NotNode）
            // 所以 NotNode 出现在此即为编程错误或校验漏洞
            throw new IllegalArgumentException("NOT 节点不应到达 distribute（应通过 IS_NOT_NULL 工厂）");
        }
        if (n instanceof CondNode) {
            CondNode c = (CondNode) n;
            List<List<ConditionNode>> single = new ArrayList<>(1);
            List<ConditionNode> one = new ArrayList<>(1);
            one.add(c.cond);
            single.add(one);
            return single;
        }
        throw new IllegalStateException("未知 Node 类型: " + n);
    }

    /**
     * 校验 NOT 不得跨越 OR（pre-check distribute 之前）。 规则：NotNode 子节点若是 OrNode →
     * IAE；NotNode 子节点若是 AndNode → 递归其两个子树。
     */
    private static void validateNoNotOverOr(Node n, String dsl) {
        if (n instanceof OrNode) {
            OrNode o = (OrNode) n;
            validateNoNotOverOr(o.left, dsl);
            validateNoNotOverOr(o.right, dsl);
            return;
        }
        if (n instanceof AndNode) {
            AndNode a = (AndNode) n;
            validateNoNotOverOr(a.left, dsl);
            validateNoNotOverOr(a.right, dsl);
            return;
        }
        if (n instanceof NotNode) {
            NotNode nn = (NotNode) n;
            if (nn.child instanceof OrNode) {
                throw new IllegalArgumentException("NOT 不得跨越 OR：NOT(A OR B) 整体否定不允许");
            }
            validateNoNotOverOr(nn.child, dsl);
            return;
        }
        // CondNode / null：no-op
    }

    /* ============================================================
     *  通用工具
     * ============================================================ */
    private static TokenEntry consume(DslTokenizer tz) {
        TokenEntry e = tz.nextToken();
        if (e == null) {
            throw err("EOF 但期望继续", tz.position());
        }
        return e;
    }

    private static TokenEntry expect(DslTokenizer tz, DslToken type, String dsl) {
        TokenEntry e = consume(tz);
        if (e.type != type) {
            throw err("期望 Token " + type + " 但得到 " + e.type, e.pos);
        }
        return e;
    }

    private static Object toJava(TokenEntry e) {
        return switch (e.type) {
            case NUMBER -> {
                double d = ((Number) e.value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    yield Long.valueOf((long) d);
                }
                yield Double.valueOf(d);
            }
            case KW_TRUE ->
                Boolean.TRUE;
            case KW_FALSE ->
                Boolean.FALSE;
            case KW_NULL ->
                null;
            default ->
                e.value;
        };
    }

    private static HybridQueryParseException err(String msg, ParsePos p) {
        return new HybridQueryParseException(msg, p, "");
    }
}

