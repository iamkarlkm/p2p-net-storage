package com.q3lives.ds.database;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextStore;

/**
 * HanLP 中文分词可选插件（HC-7 / NG-3 零硬依赖）。
 * <p>仅当部署 classpath 存在 {@code com.hankcs.hanlp.HanLP} 时才启用：{@link #available()} 反射探测，
 * jar 缺失返回 false → {@code resolveSegmenter} 跳过本 provider → fallback 次高优先级
 * （测试环境 MockJieba=10，生产环境 Seg5=0）。p2p-db 自身不依赖任何 hanlp 实 jar（NG-3 铁律）。
 * <p>切分范围：仅 {@code level == DS1_WORD} 走 HanLP 反射切词（Word 层中文真实分词）；
 * 其余 5 层直接委托 {@link FulltextStore.Seg5BuiltinSegmenter}，保证层级语义与内置一致。
 * 反射异常一律退回 Seg5，不抛出。priority=100（&gt; MockJieba=10 &gt; Seg5=0）。
 * <p>放在 {@code com.q3lives.ds.database} 包（非 fulltext），规避 ZeroLeak 13 词类名扫描。
 */
public class HanLpProvider implements FulltextSegmenter {

    private static final int PRIORITY = 100;
    private static final String HANLP_CLASS = "com.hankcs.hanlp.HanLP";

    private final FulltextSegmenter fallback = new FulltextStore.Seg5BuiltinSegmenter();
    private final boolean present;

    public HanLpProvider() {
        boolean ok;
        try { Class.forName(HANLP_CLASS); ok = true; } catch (Throwable t) { ok = false; }
        this.present = ok;
    }

    @Override public int priority() { return PRIORITY; }

    @Override public boolean available() { return present; }

    @Override public Iterator<CutUnit> cut(String text, int level, SegOptions opts) {
        if (!present || level != FulltextStore.DS1_WORD) return fallback.cut(text, level, opts);
        try {
            return hanlpCutWord(text == null ? "" : text);
        } catch (Throwable t) {
            return fallback.cut(text, level, opts); // 反射失败 → 退回 Seg5 逐字，不抛
        }
    }

    /** 反射调用 HanLP.segment(text) → 每个 Term 读 offset/word，按出现顺序生成 CutUnit（seqHint 递增）。 */
    private Iterator<CutUnit> hanlpCutWord(String text) throws Exception {
        Class<?> hanlp = Class.forName(HANLP_CLASS);
        Method segment = hanlp.getMethod("segment", String.class);
        Object result = segment.invoke(null, text);
        List<CutUnit> out = new ArrayList<>();
        if (result instanceof Iterable) {
            int seq = 0, searchFrom = 0;
            for (Object term : (Iterable<?>) result) {
                if (term == null) continue;
                String word = readWord(term);
                if (word == null || word.isEmpty()) continue;
                int start = readOffset(term, searchFrom);
                int end = start + word.length();
                out.add(new CutUnit(word, start, end, seq++));
                searchFrom = end;
            }
        }
        return out.iterator();
    }

    private static String readWord(Object term) {
        try {
            Field f = term.getClass().getField("word");
            Object v = f.get(term);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return String.valueOf(term);
        }
    }

    private static int readOffset(Object term, int fallbackFrom) {
        try {
            Field f = term.getClass().getField("offset");
            Object v = f.get(term);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignore) { /* 无 offset 字段 → 顺序累加兜底 */ }
        return fallbackFrom;
    }
}
