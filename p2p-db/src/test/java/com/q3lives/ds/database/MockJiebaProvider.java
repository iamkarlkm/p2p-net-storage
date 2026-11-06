package com.q3lives.ds.database;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;

/** MockJieba：T2-TR-2 测试专用 provider，priority=10 > Seg5=0 → resolveSegmenter 选 MockJieba（HC-7：ServiceLoader 按 priority 最小优先）。
 *  <p>切分规则故意和 Seg5 区分：ds1 WORD 会把"全文索引"整成 1 词（Seg5 切成 2 词/按字符），便于测试覆盖断言。scope=test，zeroLeak 非主代码无 NG 限制。 */
public class MockJiebaProvider implements FulltextSegmenter {
    public int priority() { return 10; }
    public boolean available() { return true; }
    public Iterator<CutUnit> cut(String text, int level, SegOptions opts) {
        if (text == null) text = "";
        if (opts == null) opts = SegOptions.defaults();
        switch (level) {
            case 0: return segChar(text);
            case 1: return segWordSmart(text);  // 故意和 Seg5 切法不同
            case 2: return seg2Gram(text);
            case 3: return segSentence(text);
            case 4: return segBlock(text);
            default: return segWhole(text);
        }
    }
    private Iterator<CutUnit> segWhole(String text) {
        List<CutUnit> out = new ArrayList<>();
        out.add(new CutUnit(text, 0, text.length(), 0));
        return out.iterator();
    }
    private Iterator<CutUnit> segChar(String text) {
        List<CutUnit> out = new ArrayList<>();
        int seq = 0, n = text.length(), i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            char[] ch = Character.toChars(cp);
            int len = ch.length;
            out.add(new CutUnit(new String(ch), i, i+len, seq++));
            i += len;
        }
        return out.iterator();
    }
    private Iterator<CutUnit> segWordSmart(String text) {
        List<CutUnit> out = new ArrayList<>();
        int seq = 0, n = text.length(), i = 0;
        while (i < n) {
            // 优先匹配"全文索引"/"倒排索引"整词（和 Seg5 切法不同，用于断言 MockJieba 覆盖）
            if (i + 4 <= n && text.substring(i, i+4).equals("全文索引")) {
                out.add(new CutUnit("全文索引", i, i+4, seq++));
                i += 4;
                continue;
            }
            if (i + 4 <= n && text.substring(i, i+4).equals("倒排索引")) {
                out.add(new CutUnit("倒排索引", i, i+4, seq++));
                i += 4;
                continue;
            }
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c < 128 && !Character.isDigit(c)) {
                int j = i;
                while (j < n && text.charAt(j) < 128 && !Character.isWhitespace(text.charAt(j))) j++;
                out.add(new CutUnit(text.substring(i, j), i, j, seq++));
                i = j;
                continue;
            }
            // 中文逐字（Seg5 也是逐字，但整词优先）
            int cp = text.codePointAt(i);
            char[] ch = Character.toChars(cp);
            int len = ch.length;
            out.add(new CutUnit(new String(ch), i, i+len, seq++));
            i += len;
        }
        return out.iterator();
    }
    private Iterator<CutUnit> seg2Gram(String text) {
        List<CutUnit> w = new ArrayList<>();
        segWordSmart(text).forEachRemaining(w::add);
        List<CutUnit> out = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < w.size() - 1; i++) {
            CutUnit a = w.get(i), b = w.get(i+1);
            out.add(new CutUnit(a.text + b.text, a.startOff, b.endOff, seq++));
        }
        return out.iterator();
    }
    private Iterator<CutUnit> segSentence(String text) {
        List<CutUnit> out = new ArrayList<>();
        int seq = 0, n = text.length(), i = 0, start = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                i++;
                out.add(new CutUnit(text.substring(start, i), start, i, seq++));
                start = i;
            } else i++;
        }
        if (start < n) out.add(new CutUnit(text.substring(start), start, n, seq));
        return out.iterator();
    }
    private Iterator<CutUnit> segBlock(String text) {
        return segWhole(text);
    }
}
