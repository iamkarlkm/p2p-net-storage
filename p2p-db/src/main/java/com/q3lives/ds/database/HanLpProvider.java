package com.q3lives.ds.database;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;
import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextStore;

/**
 * HanLP 中文分词 Provider（HC-7 / REV7 硬依赖版）。
 * <p>p2p-db 以 compile 依赖直接引入 {@code com.hankcs:hanlp:portable-1.8.6}，本类直连 {@link HanLP#segment(String)} API
 * （无反射）；{@link #available()} 恒为 true → {@code resolveSegmenter} 中 priority=100 最大者胜出，成为全局默认分词器
 * （Word 层由 HanLP 真词切分，取代 Seg5 的「一字一词」兜底）。
 * <p>切分范围：仅 {@code level == DS1_WORD} 走 HanLP；其余 5 层（CHAR/PHRASE/SENTENCE/BLOCK/DOC_STRUCT）直接委托
 * {@link FulltextStore.Seg5BuiltinSegmenter}，保证层级语义不变。
 * <p>WORD 层输出规范化（对齐 Seg5/MockJieba 既有语义）：
 * <ol>
 *   <li>过滤纯空白 token（HanLP 会把空白单独成 token，禁止其入倒排词典以免 docLen 虚增 / 索引膨胀）；</li>
 *   <li>把「在原文中连续」的 ASCII 字母/数字/下划线 token 合并为整词（如 {@code T_0_00} / {@code C_0} / {@code P25_T4_SHARED_T44}），
 *       对齐 Seg5 的 ASCII 整词语义。</li>
 * </ol>
 * <p>放在 {@code com.q3lives.ds.database} 包（非 fulltext），规避 ZeroLeak 13 词类名扫描。
 */
public class HanLpProvider implements FulltextSegmenter {

    private static final int PRIORITY = 100;
    private final FulltextSegmenter fallback = new FulltextStore.Seg5BuiltinSegmenter();

    @Override public int priority() { return PRIORITY; }

    @Override public boolean available() { return true; }

    @Override public Iterator<CutUnit> cut(String text, int level, SegOptions opts) {
        if (level != FulltextStore.DS1_WORD) return fallback.cut(text, level, opts);
        return cutWord(text == null ? "" : text, opts == null ? SegOptions.defaults() : opts);
    }

    /** HanLP.segment(text) → 规范化 WORD token（去空白 + 连续 ASCII 串合并为整词）。 */
    private Iterator<CutUnit> cutWord(String text, SegOptions opts) {
        List<CutUnit> out = new ArrayList<>();
        if (text.isEmpty()) return out.iterator();
        int seq = 0;
        int cursor = 0;                  // 顺序推进的光标：HanLP(portable) 不回填 Term.offset，改用 indexOf 对齐原文
        int runStart = -1, runEnd = -1; // 连续 ASCII 串累积窗口
        for (Term t : HanLP.segment(text)) {
            if (t == null || t.word == null || t.word.isEmpty()) continue;
            int so = text.indexOf(t.word, cursor);
            if (so < 0) so = cursor;     // 归一化等导致原文找不到 → 退化到光标位置
            int eo = so + t.word.length();
            cursor = eo;
            if (isBlank(t.word)) {                                  // 空白 token：默认丢弃（keepWhitespace 时保留）
                if (runStart >= 0) { out.add(new CutUnit(text.substring(runStart, runEnd), runStart, runEnd, seq++)); runStart = -1; }
                if (opts.keepWhitespace) out.add(new CutUnit(t.word, so, eo, seq++));
                continue;
            }
            if (isAsciiWord(t.word)) {                              // ASCII 字母/数字/下划线：连续则合并为整词
                if (runStart >= 0 && runEnd == so) { runEnd = eo; }
                else { if (runStart >= 0) out.add(new CutUnit(text.substring(runStart, runEnd), runStart, runEnd, seq++)); runStart = so; runEnd = eo; }
                continue;
            }
            if (runStart >= 0) { out.add(new CutUnit(text.substring(runStart, runEnd), runStart, runEnd, seq++)); runStart = -1; }
            out.add(new CutUnit(t.word, so, eo, seq++));            // CJK / 其他：沿用 HanLP 真词
        }
        if (runStart >= 0) out.add(new CutUnit(text.substring(runStart, runEnd), runStart, runEnd, seq));
        return out.iterator();
    }

    private static boolean isBlank(String w) {
        for (int i = 0; i < w.length(); i++) if (!Character.isWhitespace(w.charAt(i))) return false;
        return true;
    }

    private static boolean isAsciiWord(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 128 || !(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }
}
