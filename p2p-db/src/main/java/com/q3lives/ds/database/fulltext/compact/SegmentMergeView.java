package com.q3lives.ds.database.fulltext.compact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;

/**
 * P40 多段只读合并视图（Segment Merge View）。
 *
 * <p>
 * 把若干已落盘的 P37 不可变段（{@link DsIndexSegmentReader}，mmap 零重建）按调用方给定的 「旧 →
 * 新」顺序叠成一个逻辑读取端；查询语义 = <b>新段优先覆盖</b>：从最新段向最旧段逐个 二分查找，首个命中即返回，与 P22
 * {@code WeightStore} 的「同键后者覆盖」口径完全一致。
 *
 * <p>
 * 本类<b>不重建任何索引</b>：仅持有 N 个 reader 的引用，查询时按「新 → 旧」短路，因此打开代价 与段数线性相关、与段体积无关，符合
 * P37「零 startup 重建」的设计初衷（逆指标：禁止把 N 段 全量读进堆建哈希表）。
 *
 * <p>
 * 覆盖语义与未命中口径（R8 继承 P37/P22）：
 * <ul>
 * <li>{@link #getDocStat(long)}：新→旧首个命中；全 miss 返回 {@code null}</li>
 * <li>{@link #getTermStat(long)}：新→旧首个命中；全 miss 返回 {@code null}</li>
 * <li>{@link #getTf(long)}：新→旧首个非零命中；全 miss 返回 {@code 0}（tf 值 0 视作未命中）</li>
 * </ul>
 *
 * <p>
 * 顺序由调用方<b>显式给定</b>（旧在前、新在后），不依赖 P37 manifest 的物理行序（manifest 为插入序，
 * 非排序）。{@link #fingerprint()} 覆盖各段指纹并按旧→新序拼接后做 SHA-256，故对段序强相关。
 *
 * <p>
 * 3 条 IAE 守卫：① readers 列表为 null ② 列表为空 ③ 列表含 null 元素。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.compact}，不新增 fulltext 根包 top-level 类（ZeroLeak3=3）；
 * P37 {@code segment/} 5 类源码 0 改。
 */
public final class SegmentMergeView implements AutoCloseable {

    private final List<DsIndexSegmentReader> readers;

    /**
     * 以「旧 → 新」有序段读取端构建合并视图。
     *
     * @param readersInOldToNewOrder 段读取端列表，非 null、非空、无 null 元素；索引 0 为最旧段
     */
    public SegmentMergeView(List<DsIndexSegmentReader> readersInOldToNewOrder) {
        if (readersInOldToNewOrder == null) {
            throw new IllegalArgumentException("mergeView readers null");
        }
        if (readersInOldToNewOrder.isEmpty()) {
            throw new IllegalArgumentException("mergeView readers empty");
        }
        for (DsIndexSegmentReader r : readersInOldToNewOrder) {
            if (r == null) {
                throw new IllegalArgumentException("mergeView readers contains null");
            }
        }
        this.readers = Collections.unmodifiableList(new ArrayList<>(readersInOldToNewOrder));
    }

    /**
     * 按「新 → 旧」顺序查文档统计行；全 miss 返回 null。
     *
     * @param docId 文档 id
     */
    public FulltextStore.WeightDocStatRow getDocStat(long docId) {
        for (int i = readers.size() - 1; i >= 0; i--) {
            FulltextStore.WeightDocStatRow row = readers.get(i).getDocStat(docId);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    /**
     * 按「新 → 旧」顺序查 term 全局统计行（idf + payload）；全 miss 返回 null。
     *
     * @param uid term 唯一 id
     */
    public FulltextStore.WeightTermStatRow getTermStat(long uid) {
        for (int i = readers.size() - 1; i >= 0; i--) {
            FulltextStore.WeightTermStatRow row = readers.get(i).getTermStat(uid);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    /**
     * 按「新 → 旧」顺序查词频；首个非零命中即返回，全 miss 返回 0。
     *
     * @param key mix4(uid,docId,parentId,level) 键
     */
    public int getTf(long key) {
        for (int i = readers.size() - 1; i >= 0; i--) {
            int v = readers.get(i).getTf(key);
            if (v != 0) {
                return v;
            }
        }
        return 0;
    }

    /**
     * 参与本视图的段数。
     */
    public int segmentCount() {
        return readers.size();
    }

    /**
     * 合并视图指纹：对各段指纹（按旧→新序拼接）再做 SHA-256，返回 64 位小写 hex。
     *
     * <p>
     * 同一段集合在任意机器 / 任意 run 上指纹一致；段序变化即指纹变化。
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder(readers.size() * 65);
        for (DsIndexSegmentReader r : readers) {
            sb.append(r.segment().fingerprint()).append('|');
        }
        return sha256Hex(sb.toString());
    }

    /**
     * 关闭全部底层段读取端（幂等；{@link DsIndexSegmentReader#close()} 本身幂等）。
     */
    @Override
    public void close() {
        for (DsIndexSegmentReader r : readers) {
            r.close();
        }
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 16) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
