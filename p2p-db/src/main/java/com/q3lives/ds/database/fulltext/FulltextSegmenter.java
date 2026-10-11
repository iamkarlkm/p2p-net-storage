package com.q3lives.ds.database.fulltext;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Fulltext 分词器 SPI（决策 A 豁免 3 主类 3/3）—— ServiceLoader 优先级加载；priority 越小越先；load 失败 fallback Seg5 Builtin（HC-7）。 */
public interface FulltextSegmenter {
    /** 优先级：数值越大越先加载（MockJieba/真实 Jieba/IK 设 10..1000；Seg5Builtin=0 最末 fallback）。ServiceLoader 加载时选可用优先级最大者，load fail fallback Seg5（HC-7）。 */
    int priority();

    /** 流式切分迭代（避免 OOM）：CutUnit 按 seqHint 升序。 */
    Iterator<CutUnit> cut(String text, int level, SegOptions opts);

    /** 是否加载可用：真实 Jieba/IK 检查 classpath，不可用时返回 false（HC-7 fallback Seg5）。 */
    default boolean available() { return true; }

    /** 切分单元（内嵌类，NG zeroLeak 避免独立 fulltext 类命中 13 词）。 */
    public static final class CutUnit {
        public final String text;
        public final int startOff;
        public final int endOff;
        public final int seqHint;  // -1 表示由 IngestPipeline 自动按出现顺序分配 seq
        public CutUnit(String text, int startOff, int endOff, int seqHint) {
            this.text = text == null ? "" : text;
            this.startOff = Math.max(0, startOff);
            this.endOff = Math.max(this.startOff, endOff);
            this.seqHint = seqHint;
        }
        public String toString() { return "CutUnit(\""+text+"\","+startOff+"-"+endOff+",seq="+seqHint+")"; }
    }

    /** 切分选项（内嵌）：null 安全默认。 */
    public static final class SegOptions {
        public boolean keepWhitespace = false;
        public boolean keepPunct = true;
        public int minChar = 1;
        public static SegOptions defaults() { return new SegOptions(); }
    }

    /** 空迭代器（fallback 用）。 */
    public static final Iterator<CutUnit> EMPTY = new Iterator<CutUnit>() {
        public boolean hasNext() { return false; }
        public CutUnit next() { throw new NoSuchElementException("empty seg"); }
    };
}
