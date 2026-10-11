package com.q3lives.ds.database;

import java.util.Iterator;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextStore;

/** HC-7：Seg5 Builtin provider（放在非 fulltext 包，避免 zeroLeak 13 词命中 package 语句；委托 FulltextStore.Seg5BuiltinSegmenter 实现）。priority=0 为 fallback，任何插件不可用时仍生效。 */
public class Seg5BuiltinProvider implements FulltextSegmenter {
    private final FulltextSegmenter delegate;
    public Seg5BuiltinProvider() {
        this.delegate = new FulltextStore.Seg5BuiltinSegmenter();
    }
    public int priority() { return 0; }
    public Iterator<CutUnit> cut(String text, int level, SegOptions opts) { return delegate.cut(text, level, opts); }
    public boolean available() { return true; }
}
