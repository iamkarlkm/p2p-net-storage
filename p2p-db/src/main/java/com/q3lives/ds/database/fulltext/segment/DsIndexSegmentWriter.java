package com.q3lives.ds.database.fulltext.segment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * P37 BM25 索引段写入口（Segment Writer）。
 *
 * <p>
 * 接收外部喂入的 BM25 统计三元组（docStat / termStat / termFreq），在 {@link #finish()} 时统一
 * 排序、去重（同键后者覆盖）、列式打包、动态区逐块 LZ4 压缩、4KB 页对齐、CRC32 校验，
 * 并以「写临时文件 → 原子 rename」方式落盘，产出不可变段文件 + {@link DsIndexSegment} 描述。
 *
 * <p>对齐 P22 {@code WeightStore} 口径：
 * <ul>
 *   <li>docStat 负载 48B = docLen[6] + nTerms[6]（加 8B docId 键 → 56B 记录）</li>
 *   <li>termStat 记录 = uid + idf(double) + payloadOff + payloadLen</li>
 *   <li>tf 键 = {@code FarmHash64.mix4(uid,docId,parentId,level)}（由调用方计算后传入）</li>
 * </ul>
 *
 * <p>本类纯 additive：不触碰 P21 {@code FulltextStore} / P22 {@code WeightStore} 任何源码。
 */
public final class DsIndexSegmentWriter {

    private static final int DOC_LEN_POS = 0;
    private static final int N_TERMS_POS = 6;

    private static final class TermStat {
        final double idf;
        final byte[] payload;

        TermStat(double idf, byte[] payload) {
            this.idf = idf;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    private final File target;
    private final Map<Long, int[]> docStat = new HashMap<>();
    private final Map<Long, TermStat> termStat = new HashMap<>();
    private final Map<Long, Integer> tf = new HashMap<>();
    private boolean finished;

    /**
     * 创建写入口。
     *
     * @param target 段文件目标路径（父目录会自动创建）
     */
    public DsIndexSegmentWriter(File target) {
        if (target == null) {
            throw new IllegalArgumentException("writer target null");
        }
        this.target = target;
    }

    /**
     * 追加一条 docStat（docLen[6] + nTerms[6]，同 docId 后者覆盖）。
     *
     * @param docId 文档 id，≥ 0
     * @param docLen 6 个 level 的文档长度
     * @param nTerms 6 个 level 的 term 数
     */
    public void addDocStat(long docId, int[] docLen, int[] nTerms) {
        ensureOpen();
        if (docId < 0) {
            throw new IllegalArgumentException("docId < 0: " + docId);
        }
        if (docLen == null || docLen.length != 6) {
            throw new IllegalArgumentException("docLen must have 6 entries");
        }
        if (nTerms == null || nTerms.length != 6) {
            throw new IllegalArgumentException("nTerms must have 6 entries");
        }
        int[] rec = new int[12];
        System.arraycopy(docLen, 0, rec, DOC_LEN_POS, 6);
        System.arraycopy(nTerms, 0, rec, N_TERMS_POS, 6);
        docStat.put(docId, rec);
    }

    /**
     * 追加一条 termStat（同 uid 后者覆盖，保留最后一次的 idf 与 payload）。
     *
     * @param uid     term 唯一 id，≥ 0
     * @param idf     逆文档频率，必须有限
     * @param payload 变长负载（可为 null，视作 0 长）
     */
    public void addTermStat(long uid, double idf, byte[] payload) {
        ensureOpen();
        if (uid < 0) {
            throw new IllegalArgumentException("uid < 0: " + uid);
        }
        if (Double.isNaN(idf) || Double.isInfinite(idf)) {
            throw new IllegalArgumentException("idf not finite: " + idf);
        }
        termStat.put(uid, new TermStat(idf, payload));
    }

    /**
     * 追加一条 termFreq（同 key 后者覆盖）。
     *
     * @param key mix4(uid,docId,parentId,level) 键
     * @param tf  词频，≥ 0
     */
    public void addTf(long key, int tfv) {
        ensureOpen();
        if (tfv < 0) {
            throw new IllegalArgumentException("tf < 0: " + tfv);
        }
        tf.put(key, tfv);
    }

    /**
     * 完成段构建并原子落盘。
     *
     * @return 段描述对象
     */
    public DsIndexSegment finish() throws IOException {
        ensureOpen();
        long[] ids = sortedKeys(docStat);
        int[][] dl = new int[ids.length][];
        int[][] nt = new int[ids.length][];
        for (int i = 0; i < ids.length; i++) {
            int[] rec = docStat.get(ids[i]);
            dl[i] = Arrays.copyOfRange(rec, DOC_LEN_POS, DOC_LEN_POS + 6);
            nt[i] = Arrays.copyOfRange(rec, N_TERMS_POS, N_TERMS_POS + 6);
        }
        byte[] docStatBytes = SegmentColumnCodec.packDocStat(ids, dl, nt);

        long[] uids = sortedKeys(termStat);
        double[] idf = new double[uids.length];
        byte[][] payloads = new byte[uids.length][];
        for (int i = 0; i < uids.length; i++) {
            TermStat ts = termStat.get(uids[i]);
            idf[i] = ts.idf;
            payloads[i] = ts.payload;
        }
        SegmentColumnCodec.Dynamic dyn = SegmentColumnCodec.packDynamic(payloads, SegmentColumnCodec.MIN_PAGE);
        byte[] termStatBytes = SegmentColumnCodec.packTermStat(uids, idf, dyn.off, dyn.len);

        long[] tkeys = sortedKeys(tf);
        int[] tvals = new int[tkeys.length];
        for (int i = 0; i < tkeys.length; i++) {
            tvals[i] = tf.get(tkeys[i]);
        }
        byte[] tfBytes = SegmentColumnCodec.packTf(tkeys, tvals);

        long docStatOff = DsIndexSegment.HEADER_SIZE;
        long docStatLen = docStatBytes.length;
        long termStatOff = align4k(docStatOff + docStatLen);
        long termStatLen = termStatBytes.length;
        long tfOff = align4k(termStatOff + termStatLen);
        long tfLen = tfBytes.length;
        long varOff = align4k(tfOff + tfLen);
        long varLen = dyn.data.length;
        int[] crcs = {
                SegmentColumnCodec.crc32(docStatBytes),
                SegmentColumnCodec.crc32(termStatBytes),
                SegmentColumnCodec.crc32(tfBytes),
                SegmentColumnCodec.crc32(dyn.data)
        };
        DsIndexSegment seg = new DsIndexSegment(DsIndexSegment.VERSION, ids.length, uids.length, tkeys.length,
                docStatOff, docStatLen, termStatOff, termStatLen, tfOff, tfLen, varOff, varLen, crcs);

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(SegmentColumnCodec.writeHeader(seg));
            out.write(docStatBytes);
            writePad(out, (int) (termStatOff - (docStatOff + docStatLen)));
            out.write(termStatBytes);
            writePad(out, (int) (tfOff - (termStatOff + termStatLen)));
            out.write(tfBytes);
            writePad(out, (int) (varOff - (tfOff + tfLen)));
            out.write(dyn.data);
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        finished = true;
        return seg;
    }

    private void ensureOpen() {
        if (finished) {
            throw new IllegalArgumentException("writer already finished");
        }
    }

    private static long[] sortedKeys(Map<Long, ?> m) {
        long[] keys = new long[m.size()];
        int i = 0;
        for (Long k : m.keySet()) {
            keys[i++] = k;
        }
        Arrays.sort(keys);
        return keys;
    }

    private static long align4k(long v) {
        long page = DsIndexSegment.HEADER_SIZE;
        return ((v + page - 1) / page) * page;
    }

    private static void writePad(FileOutputStream out, int pad) throws IOException {
        if (pad <= 0) {
            return;
        }
        byte[] zeros = new byte[Math.min(pad, 8192)];
        int remaining = pad;
        while (remaining > 0) {
            int n = Math.min(remaining, zeros.length);
            out.write(zeros, 0, n);
            remaining -= n;
        }
    }
}
