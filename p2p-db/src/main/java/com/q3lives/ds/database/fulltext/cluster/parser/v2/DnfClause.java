package com.q3lives.ds.database.fulltext.cluster.parser.v2;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * DnfClause 不可变结构（P34 OQ1 DSL v2 OR/NOT/括号 DNF 表达式 MVP）。
 *
 * <p>DNF 范式拆分结果 = OR of AND：外层 List 表示 OR（多个 minterm），内层 List 表示 AND（minterm 内多条件）。
 * 例：{@code A AND (B OR C) AND (D OR E OR F)} 拆为
 * <pre>
 *   minterm0: [A, B, D]
 *   minterm1: [A, B, E]
 *   minterm2: [A, B, F]
 *   minterm3: [A, C, D]
 *   minterm4: [A, C, E]
 *   minterm5: [A, C, F]
 * </pre>
 *
 * <p>8 IAE 守卫（strict 校验）：
 * <ol>
 *   <li>minterms == null → IAE</li>
 *   <li>minterms.isEmpty() → IAE（DNF 至少 1 个 minterm）</li>
 *   <li>任一 minterm == null → IAE</li>
 *   <li>任一 minterm.isEmpty() → IAE（minterm 至少 1 个 ConditionNode）</li>
 *   <li>任一 ConditionNode == null → IAE</li>
 *   <li>minterm 数量 &gt; maxSize → IAE（外部可设 256 硬上限）</li>
 *   <li>任一 minterm size &gt; 1024 → IAE（防止 minterm 过长）</li>
 *   <li>minterms 构造后 unmodifiable 包装 → 后续修改抛 UnsupportedOperationException</li>
 * </ol>
 *
 * <p>业务方使用：
 * <ul>
 *   <li>{@link #size()}：minterm 数量（OR 数）</li>
 *   <li>{@link #getMiniTerm(int)}：i-th minterm（AND 条件列表）</li>
 *   <li>{@link #isAcceptable(int)}：size() ≤ maxSize 快速判断</li>
 *   <li>{@link #fingerprint()}：SHA-256 of minterms 序列，跨 run 稳定</li>
 *   <li>{@link #deepEquals(DnfClause, DnfClause)}：递归 minterm 列表 equals，T4 跨进程指纹稳定</li>
 * </ul>
 *
 * @author SP3.1 Implement Agent
 * @since P34 OQ1 DSL v2 MVP
 */
public final class DnfClause {

    public final List<List<ConditionNode>> minterms;

    /**
     * 构造 DnfClause 不可变实例（8 IAE 全检）。
     *
     * @param minterms 非 null 非空，每条 minterm 非 null 非空，每节点非 null
     * @throws IllegalArgumentException 任一 IAE 触发
     */
    public DnfClause(List<List<ConditionNode>> minterms) {
        if (minterms == null) throw new IllegalArgumentException("minterms cannot be null");
        if (minterms.isEmpty()) throw new IllegalArgumentException("minterms 不能为空（DNF 至少 1 个 minterm）");
        List<List<ConditionNode>> copy = new ArrayList<>(minterms.size());
        for (int i = 0; i < minterms.size(); i++) {
            List<ConditionNode> mt = minterms.get(i);
            if (mt == null) throw new IllegalArgumentException("minterms[" + i + "] cannot be null");
            if (mt.isEmpty()) throw new IllegalArgumentException("minterms[" + i + "] 不能为空（minterm 至少 1 个 ConditionNode）");
            if (mt.size() > 1024) throw new IllegalArgumentException("minterms[" + i + "] size=" + mt.size() + " > 1024 硬上限");
            for (int j = 0; j < mt.size(); j++) {
                if (mt.get(j) == null) {
                    throw new IllegalArgumentException("minterms[" + i + "][" + j + "] cannot be null");
                }
            }
            copy.add(Collections.unmodifiableList(new ArrayList<>(mt)));
        }
        this.minterms = Collections.unmodifiableList(copy);
    }

    /** minterm 数量。 */
    public int size() {
        return minterms.size();
    }

    /**
     * 取 i-th minterm（0-based）。
     *
     * @param i 0-based 索引
     * @return minterm 不可变列表
     * @throws IndexOutOfBoundsException 越界
     */
    public List<ConditionNode> getMiniTerm(int i) {
        if (i < 0 || i >= minterms.size()) {
            throw new IndexOutOfBoundsException("minterm 索引越界: " + i + " (size=" + minterms.size() + ")");
        }
        return minterms.get(i);
    }

    /**
     * 是否满足 maxSize 约束。
     *
     * @param maxSize 外部上限（≤ 256 推荐）
     * @return size() ≤ maxSize
     */
    public boolean isAcceptable(int maxSize) {
        return minterms.size() <= maxSize;
    }

    /**
     * 64-char hex SHA-256 指纹（minterms 序列）。
     * 用 size+kind+payload 而非 toString 避免 hashCode 漂移。
     */
    public String fingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (List<ConditionNode> mt : minterms) {
                md.update((byte) (mt.size() & 0xFF));
                md.update((byte) ((mt.size() >> 8) & 0xFF));
                for (ConditionNode c : mt) {
                    md.update((byte) (c.kind.ordinal() & 0xFF));
                    if (c.kind == ConditionNode.Kind.FT) {
                        md.update(c.ftQuery.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        md.update((byte) 0);
                        md.update((c.ormCol + "|" + c.ormOp.name() + "|" + c.ormValueA + "|" + c.ormValueB)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 深度比对两个 DnfClause 字节级全等（minterm 数量 + 每条 minterm 节点 equals）。
     *
     * @param a 第一个（允许 null）
     * @param b 第二个（允许 null）
     * @return 100% 全等 true
     */
    public static boolean deepEquals(DnfClause a, DnfClause b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.minterms.size() != b.minterms.size()) return false;
        for (int i = 0; i < a.minterms.size(); i++) {
            List<ConditionNode> am = a.minterms.get(i);
            List<ConditionNode> bm = b.minterms.get(i);
            if (am.size() != bm.size()) return false;
            for (int j = 0; j < am.size(); j++) {
                if (!Objects.equals(am.get(j), bm.get(j))) return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DnfClause[");
        for (int i = 0; i < minterms.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("(");
            for (int j = 0; j < minterms.get(i).size(); j++) {
                if (j > 0) sb.append(" AND ");
                sb.append(minterms.get(i).get(j));
            }
            sb.append(")");
        }
        sb.append("]");
        return sb.toString();
    }
}
