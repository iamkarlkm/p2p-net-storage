package com.q3lives.ds.database.fulltext.layered;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.compact.SegmentMergeView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P42 分层倒排只读视图（Layered Inverted View，mmap 零重建）。
 *
 * <p>
 * 包装 P40 {@link SegmentMergeView}（N 段「新→旧」短路覆盖），向上暴露 level-aware 的分层倒排查询面：
 * {@link #docLen} / {@link #nTerms}（按 {@code level} 取 docStat 6 level 列对应槽）、{@link #idf} /
 * {@link #termPayload}（termStat）、{@link #tf}（内部以
 * {@link TermDictStore.FarmHash64#mix4} 合成 {@code mix4(uid,docId,parentId,level)} 键）。
 *
 * <p>
 * <b>零重建（R10）</b>：{@link #open} 仅调用 P37 {@link DsIndexSegmentStore#openReader}（mmap + 段头 +
 * 4×CRC32），不把段内容读进堆、不建哈希表、不重算 idf → 打开代价与段体积无关（逆指标：冷启动
 * P95 ≤ 200ms）。
 *
 * <p>
 * <b>覆盖语义（R8）</b>：多段按调用方显式给定的「旧→新」顺序叠加，查询命中最新的段；miss 口径 ——
 * docStat/termStat 全 miss 返回 {@code null}（视图层映射为 0 / 0.0 / null），tf 全 miss 返回 0。
 *
 * <p>
 * 4 条 IAE 守卫：① store 为 null ② segIds 为 null ③ segIds 为空 ④ {@code level} ∉ [0,5]；
 * 另由 {@code openReader} 兜底「段未注册」IAE。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.layered}（ZeroLeak3=3 永保）；P37 {@code segment/} 与 P40
 * {@code compact/} 源码 0 改；仅 JDK + first-party import（R2 NG-3）。
 */
public final class LayeredInvertedView implements AutoCloseable {

    /** 分层级数（char/word/phrase/sentence/sentence_block/doc_struct）。 */
    private static final int LEVELS = 6;

    private final SegmentMergeView mergeView;
    private boolean closed;

    private LayeredInvertedView(SegmentMergeView mergeView) {
        this.mergeView = mergeView;
    }

    /**
     * 打开 1..N 个已注册段（旧→新）的零重建只读视图。
     *
     * @param store          段仓库，非 null
     * @param segIdsOldToNew 段 id 序列（旧在前、新在后），非 null 非空
     * @return 分层倒排只读视图
     */
    public static LayeredInvertedView open(DsIndexSegmentStore store, long[] segIdsOldToNew) throws IOException {
        if (store == null) {
            throw new IllegalArgumentException("view store null");
        }
        if (segIdsOldToNew == null) {
            throw new IllegalArgumentException("view segIds null");
        }
        if (segIdsOldToNew.length == 0) {
            throw new IllegalArgumentException("view segIds empty");
        }
        List<DsIndexSegmentReader> readers = new ArrayList<>(segIdsOldToNew.length);
        try {
            for (long segId : segIdsOldToNew) {
                readers.add(store.openReader(segId));
            }
        } catch (RuntimeException e) {
            for (DsIndexSegmentReader r : readers) {
                r.close();
            }
            throw e;
        }
        return new LayeredInvertedView(new SegmentMergeView(readers));
    }

    /**
     * 读某文档某 level 的文档长度（docStat.docLen[level]）；未命中返回 0。
     */
    public int docLen(long docId, int level) {
        FulltextStore.WeightDocStatRow row = docStat(docId, level);
        return row == null ? 0 : row.docLen[level];
    }

    /**
     * 读某文档某 level 的 distinct term 数（docStat.nTerms[level]）；未命中返回 0。
     */
    public int nTerms(long docId, int level) {
        FulltextStore.WeightDocStatRow row = docStat(docId, level);
        return row == null ? 0 : row.nTerms[level];
    }

    /**
     * 读 term 的逆文档频率（termStat.idf）；未命中返回 0.0。
     */
    public double idf(long uid) {
        FulltextStore.WeightTermStatRow row = mergeView.getTermStat(uid);
        return row == null ? 0.0 : row.idf;
    }

    /**
     * 读 term 的变长负载（termStat.payload）；未命中返回 null。
     */
    public byte[] termPayload(long uid) {
        FulltextStore.WeightTermStatRow row = mergeView.getTermStat(uid);
        return row == null ? null : row.payload;
    }

    /**
     * 读 {@code (uid,docId,parentId,level)} 下的词频；未命中返回 0。
     *
     * <p>键由 {@code FarmHash64.mix4(uid,docId,parentId,level)} 合成（与 P22 写入端一致）。
     */
    public int tf(long uid, long docId, long parentId, int level) {
        checkLevel(level);
        long key = TermDictStore.FarmHash64.mix4(uid, docId, parentId, level);
        return mergeView.getTf(key);
    }

    /**
     * 某 docId 是否在视图内任一命中（docStat 存在）。
     */
    public boolean hasDoc(long docId) {
        return mergeView.getDocStat(docId) != null;
    }

    /**
     * 某 uid 是否在视图内任一命中（termStat 存在）。
     */
    public boolean hasTerm(long uid) {
        return mergeView.getTermStat(uid) != null;
    }

    /**
     * 参与本视图的段数。
     */
    public int segmentCount() {
        return mergeView.segmentCount();
    }

    /**
     * 视图指纹（= {@link SegmentMergeView#fingerprint()}，随段序变化）。
     */
    public String fingerprint() {
        return mergeView.fingerprint();
    }

    /**
     * 关闭全部底层段读取端（幂等）。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        mergeView.close();
    }

    private FulltextStore.WeightDocStatRow docStat(long docId, int level) {
        checkLevel(level);
        return mergeView.getDocStat(docId);
    }

    private static void checkLevel(int level) {
        if (level < 0 || level >= LEVELS) {
            throw new IllegalArgumentException("level out of range [0," + (LEVELS - 1) + "]: " + level);
        }
    }
}
