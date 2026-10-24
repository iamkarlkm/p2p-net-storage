package com.q3lives.ds.database.fulltext.compact;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;
import com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec;

/**
 * P40 段归并器（Segment Compactor）：把 N 个 P37 不可变段归并为 1 个新段文件。
 *
 * <p>
 * 归并语义与 {@link SegmentMergeView} 完全一致 —— <b>新段优先覆盖</b>：按调用方给定的「旧 → 新」
 * 顺序依次把各段的 docStat / termStat / tf 记录喂入 {@link DsIndexSegmentWriter}，writer 的
 * {@code put()} 天然「同键后者覆盖」，故后喂的新段记录自动覆盖旧段同键记录。产物是<b>合法 P37 段</b>
 * （排序 + 列式 + LZ4 + 4KB 对齐 + 4×CRC32 + 原子 rename），可直接被 {@link DsIndexSegmentReader#open}
 * mmap 读取（R7：段格式 0 变更）。
 *
 * <p>
 * <b>为何以「(store, 源 reader)」而非纯 reader 定位源数据</b>：P37 {@link DsIndexSegmentReader} 只暴露
 * 点查（{@code getDocStat} / {@code getTermStat} / {@code getTf} / {@code scanTfPrefix}），<b>无
 * docStat / termStat 键枚举 API</b>；而归并要求遍历全部键。为遵守 R4（P37 {@code segment/} 5 类源码 0 改）
 * 与 R1（{@code compact/} 仅 5 文件，枚举逻辑不外置），本类改由 {@link DsIndexSegmentStore} 以段指纹把
 * 每个 {@code reader} 回填到其已注册源段文件，再自行按列式布局解析记录。指纹相同即内容相同，故同一 store
 * 内出现内容相同的段时归并结果不受影响（消耗序由 manifest 行序决定，确定性）。
 *
 * <p>4 条 IAE 守卫：① target 为 null ② sources 为 null / 空 ③ sources 含 null 元素 ④ 源段计数与
 * section 长度不一致（越界脏数据）。
 *
 * <p>红线：本类位于新子包 {@code fulltext.compact}（ZeroLeak3=3 永保）；P37 源码 0 改；0 新增 Maven dep。
 */
public final class SegmentCompactor {

    private final DsIndexSegmentStore store;

    /**
     * 创建归并器。
     *
     * @param store 源段所在的段仓库，非 null
     */
    public SegmentCompactor(DsIndexSegmentStore store) {
        if (store == null) {
            throw new IllegalArgumentException("compactor store null");
        }
        this.store = store;
    }

    /**
     * 把「旧 → 新」有序的 N 个源段归并为 1 个新段文件。
     *
     * @param target           产物段文件路径（父目录自动创建）
     * @param sourcesOldToNew  源段读取端（旧在前、新在后；索引 0 为最旧段）
     * @return 产物段描述对象
     */
    public DsIndexSegment compact(File target, List<DsIndexSegmentReader> sourcesOldToNew) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("compactor target null");
        }
        if (sourcesOldToNew == null) {
            throw new IllegalArgumentException("compactor sources null");
        }
        if (sourcesOldToNew.isEmpty()) {
            throw new IllegalArgumentException("compactor sources empty");
        }
        for (DsIndexSegmentReader r : sourcesOldToNew) {
            if (r == null) {
                throw new IllegalArgumentException("compactor sources contains null");
            }
        }
        File[] files = resolveSources(sourcesOldToNew);
        DsIndexSegmentWriter writer = new DsIndexSegmentWriter(target);
        for (File f : files) {
            feed(f, writer);
        }
        return writer.finish();
    }

    /**
     * 以段指纹把源 reader 回填到已注册源段文件（manifest 序消耗，确定性）。
     */
    private File[] resolveSources(List<DsIndexSegmentReader> sources) throws IOException {
        Map<String, Deque<Long>> byFingerprint = new HashMap<>();
        for (long segId : store.list()) {
            String fp = store.load(segId).fingerprint();
            byFingerprint.computeIfAbsent(fp, k -> new ArrayDeque<>()).add(segId);
        }
        File[] files = new File[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            String fp = sources.get(i).segment().fingerprint();
            Deque<Long> queue = byFingerprint.get(fp);
            if (queue == null || queue.isEmpty()) {
                throw new IllegalArgumentException("compactor source not registered: " + fp);
            }
            files[i] = store.segmentFile(queue.poll());
        }
        return files;
    }

    /**
     * 解析一个源段文件的三列记录并喂入 writer（后喂覆盖先喂 → 新段优先）。
     */
    private static void feed(File src, DsIndexSegmentWriter writer) throws IOException {
        byte[] all = Files.readAllBytes(src.toPath());
        if (all.length < DsIndexSegment.HEADER_SIZE) {
            throw new IllegalArgumentException("compactor source too short: " + src.getName());
        }
        DsIndexSegment seg = SegmentColumnCodec.readHeader(
                ByteBuffer.wrap(all, 0, DsIndexSegment.HEADER_SIZE));
        ByteBuffer docStat = section(all, seg.docStatOff, seg.docStatLen,
                seg.docCount, SegmentColumnCodec.DOC_STAT_REC, "docStat");
        ByteBuffer termStat = section(all, seg.termStatOff, seg.termStatLen,
                seg.termCount, SegmentColumnCodec.TERM_STAT_REC, "termStat");
        ByteBuffer tf = section(all, seg.tfOff, seg.tfLen, seg.tfCount, SegmentColumnCodec.TF_REC, "tf");
        ByteBuffer var = section(all, seg.varOff, seg.varLen, 0L, 0, "var");

        int docBase = SegmentColumnCodec.DOC_STAT_KEY;
        for (int i = 0; i < seg.docCount; i++) {
            int base = i * SegmentColumnCodec.DOC_STAT_REC;
            int[] docLen = new int[6];
            int[] nTerms = new int[6];
            for (int k = 0; k < 6; k++) {
                docLen[k] = docStat.getInt(base + docBase + k * 4);
                nTerms[k] = docStat.getInt(base + docBase + 24 + k * 4);
            }
            writer.addDocStat(docStat.getLong(base), docLen, nTerms);
        }
        for (int i = 0; i < seg.termCount; i++) {
            int base = i * SegmentColumnCodec.TERM_STAT_REC;
            double idf = termStat.getDouble(base + 8);
            byte[] payload = SegmentColumnCodec.readVar(var, termStat.getInt(base + 16), termStat.getInt(base + 20));
            writer.addTermStat(termStat.getLong(base), idf, payload);
        }
        for (int i = 0; i < seg.tfCount; i++) {
            int base = i * SegmentColumnCodec.TF_REC;
            writer.addTf(tf.getLong(base), tf.getInt(base + 8));
        }
    }

    /**
     * 切出某个 section 视图，并校验计数与长度一致（越界脏数据即 IAE）。
     */
    private static ByteBuffer section(byte[] all, long off, long len, long count, int recSize, String name) {
        if (off < 0 || len < 0 || off + len > all.length) {
            throw new IllegalArgumentException(
                    "compactor section out of range [" + name + "]: off=" + off + " len=" + len);
        }
        if (count > 0 && count * recSize != len) {
            throw new IllegalArgumentException(
                    "compactor section count mismatch [" + name + "]: count=" + count + " len=" + len);
        }
        ByteBuffer b = ByteBuffer.wrap(all);
        b.position((int) off);
        b.limit((int) (off + len));
        return b.slice();
    }
}
