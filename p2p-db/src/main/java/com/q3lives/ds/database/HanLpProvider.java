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
 * <p>放在 {@code com.q3lives.ds.database} 包（非 fulltext），规避 ZeroLeak 13 词类名扫描。
 */
public class HanLpProvider implements FulltextSegmenter {

    private static final int PRIORITY = 100;
    private final FulltextSegmenter fallback = new FulltextStore.Seg5BuiltinSegmenter();

    @Override public int priority() { return PRIORITY; }

    @Override public boolean available() { return true; }

    @Override public Iterator<CutUnit> cut(String text, int level, SegOptions opts) {
        if (level != FulltextStore.DS1_WORD) return fallback.cut(text, level, opts);
        return cutWord(text == null ? "" : text);
    }

    /** HanLP.segment(text) → 每个 Term 取 word/offset，按出现顺序生成 CutUnit（seqHint 递增）。 */
    private Iterator<CutUnit> cutWord(String text) {
        List<CutUnit> out = new ArrayList<>();
        if (text.isEmpty()) return out.iterator();
        int seq = 0;
        for (Term t : HanLP.segment(text)) {
            if (t == null || t.word == null || t.word.isEmpty()) continue;
            int start = t.offset;
            out.add(new CutUnit(t.word, start, start + t.word.length(), seq++));
        }
        return out.iterator();
    }
}
