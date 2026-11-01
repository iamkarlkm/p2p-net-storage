package com.q3lives.ds.database.fulltext.wordnet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.segment.SegmentColumnCodec;

/**
 * P51 跨段词网导出器（Word Net Exporter）。
 *
 * <p>
 * 把 P21 {@code FulltextStore} 已驻内存的<b>词网</b>（{@code distL[d]}/{@code distR[d]}
 * 距离桶的 上下文 L/R 邻接 + {@code WeightStore.termPositionsMap} 的 positions
 * 命中区间）导出为<b>独立 不可变「词网段」工件</b>{@code rootDir/wordnet/seg-<id>.dnet}，并登记
 * manifest。
 *
 * <p>
 * <b>键口径 0 漂移（R6 USER-HC-0）</b>：邻接键直接复用 P21 既有公式
 * {@code mix4(docId,parentId,level,srcUid)}（见 {@link #ctxKey}），positions 键复用
 * {@code mix4(uid,docId,parentId,mix2(level,0xDEADBEEF))}（见 {@link #posKey}）→
 * <b>不做 uid 重编码</b>、 不新增 level 前缀，导出即快照（R7：同源同批 → 段指纹跨进程 / 跨 run 恒定）。
 *
 * <p>
 * <b>DIDX 段格式 0 变更（R5）</b>：本工件自有 MAGIC {@code 'D','N','E','T'} 与自有
 * {@code VERSION=1}，沿用 P37「4KB 头 + 4KB 对齐 section + 4×CRC32 + LZ4 动态区」自描述模式，
 * 但<b>不写入</b> {@code segment/} 目录、<b>不触碰</b> {@code DsIndexSegment*} 任何源码。
 *
 * <p>
 * <b>崩溃安全（R9）</b>：严格「{@code .tmp} 写满 → 原子 {@code Files.move}（finish）<b>先于</b>
 * manifest 登记」。任一崩溃点下，未登记的半成品段不在 manifest，{@link WordNetSegmentView.Store#list}
 * 不可见。
 *
 * <p>
 * <b>唯一上游触碰（R4 / §0 方案 A）</b>：为枚举词网上下文与邻接，本类依赖 P21 {@code FulltextStore}
 * append-only 追加的 3 个只读 accessor（{@code wordNetAllContextKeys} /
 * {@code wordNetNeighborsForCtx} / {@code wordNetPositionsForCtx}）；P21 既有方法 /
 * 内嵌类型 0 改。
 *
 * <p>
 * 4 条 IAE 守卫：① {@code ft} 为 null ② {@code wordNetRootDir} 为 null / 空白 ③
 * {@code segId} &lt; 0 ④ {@code segId} 已登记（防重复导出覆盖）。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.wordnet}（ZeroLeak3=3 永保）；导出为纯数据搬运，0 改 K、0 触
 * {@code SearchOptions}、0 污染 {@code bucketAccessCounter}（R3 TOPK 铁律）；仅 JDK +
 * first-party import（R2 NG-3）。
 */
public final class WordNetExporter {

    /**
     * 邻接索引记录定长宽度：{@code [ctxKey 8B][off 4B][len 4B]}。
     */
    private static final int INDEX_REC = 16;

    /**
     * 创建无状态导出器（0 字段、0 资源持有；导出参数全部由 {@link #export} 传入）。
     */
    public WordNetExporter() {
    }

    /**
     * 词网邻接键（与 P21 {@code FulltextStore.appendNeighbor} 1:1 同款）。
     *
     * <p>
     * 包内可访问：供 {@link WordNetSegmentView} 在 mmap 上做 O(log N) 二分定位。
     */
    static long ctxKey(long docId, long parentId, int level, long srcUid) {
        return TermDictStore.FarmHash64.mix4(docId, parentId, (long) level, srcUid);
    }

    /**
     * positions 键（与 P21 {@code WeightStore.posKey} 1:1 同款）。
     *
     * <p>
     * 包内可访问：供 {@link WordNetSegmentView} 在 mmap 上做 O(log N) 二分定位。
     */
    static long posKey(long uid, long docId, long parentId, int level) {
        return TermDictStore.FarmHash64.mix4(uid, docId, parentId,
                TermDictStore.FarmHash64.mix2((long) level, 0xDEADBEEFL));
    }

    /**
     * 把 {@code ft} 的内存词网（positions + L/R 邻接）导出为词网段并登记到 manifest。
     *
     * <p>
     * 导出序列（R9 崩溃安全）：① 逐上下文枚举邻接 / positions → 列式打包 + LZ4 动态区； ② 4KB 页对齐 +
     * 4×CRC32 + 组装 4KB 段头；③ {@code .tmp} 写满 → 原子 {@code Files.move} （finish）；④
     * 登记 manifest；⑤ 返回不可变快照描述。
     *
     * @param ft 源全文存储（提供词网只读枚举），非 null
     * @param wordNetRootDir 词网段根目录（其下为 {@code wordnet/}），非 null 且非空白
     * @param segId 新段 id，≥ 0 且尚未登记
     * @return 本次导出的不可变快照描述
     */
    public WordNetSnapshot export(FulltextStore ft, String wordNetRootDir, long segId) throws IOException {
        if (ft == null) {
            throw new IllegalArgumentException("export ft null");
        }
        if (wordNetRootDir == null || wordNetRootDir.trim().isEmpty()) {
            throw new IllegalArgumentException("export rootDir null or empty");
        }
        if (segId < 0) {
            throw new IllegalArgumentException("export segId < 0: " + segId);
        }
        for (long registered : WordNetSegmentView.Store.list(wordNetRootDir)) {
            if (registered == segId) {
                throw new IllegalArgumentException("export segId already registered: " + segId);
            }
        }

        TreeMap<Long, byte[]> adjByKey = new TreeMap<>();
        TreeMap<Long, byte[]> posByKey = new TreeMap<>();
        long adjCount = 0L;
        long posCount = 0L;
        for (long[] ctx : ft.wordNetAllContextKeys()) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            List<long[]> neighbors = ft.wordNetNeighborsForCtx(docId, parentId, level);
            Map<Long, List<long[]>> bySrc = new LinkedHashMap<>();
            for (long[] n : neighbors) {
                bySrc.computeIfAbsent(n[0], k -> new ArrayList<>()).add(n);
            }
            for (Map.Entry<Long, List<long[]>> e : bySrc.entrySet()) {
                List<long[]> entries = e.getValue();
                adjByKey.put(ctxKey(docId, parentId, level, e.getKey()), packAdj(entries));
                adjCount += entries.size();
            }
            LinkedHashSet<Long> uids = new LinkedHashSet<>();
            for (TuRow r : ft.selectByParentLevel(docId, parentId, level)) {
                uids.add(r.unit_id());
            }
            for (Long u : uids) {
                long uid = u.longValue();
                List<PositionSpan> spans = ft.wordNetPositionsForCtx(uid, docId, parentId, level);
                if (spans.isEmpty()) {
                    continue;
                }
                posByKey.put(posKey(uid, docId, parentId, level), packPos(spans));
                posCount += spans.size();
            }
        }

        long[] adjKeys = keysOf(adjByKey);
        long[] posKeys = keysOf(posByKey);
        SegmentColumnCodec.Dynamic adjDyn = SegmentColumnCodec.packDynamic(valuesOf(adjByKey),
                SegmentColumnCodec.MIN_PAGE);
        SegmentColumnCodec.Dynamic posDyn = SegmentColumnCodec.packDynamic(valuesOf(posByKey),
                SegmentColumnCodec.MIN_PAGE);
        byte[] ctxCol = packIndex(adjKeys, adjDyn.off, adjDyn.len);
        byte[] posCol = packIndex(posKeys, posDyn.off, posDyn.len);

        long ctxOff = WordNetSegmentView.Store.HEADER_SIZE;
        long ctxLen = ctxCol.length;
        long posOff = align4k(ctxOff + ctxLen);
        long posLen = posCol.length;
        long adjVarOff = align4k(posOff + posLen);
        long adjVarLen = adjDyn.data.length;
        long posVarOff = align4k(adjVarOff + adjVarLen);
        long posVarLen = posDyn.data.length;
        int[] crcs = {
            SegmentColumnCodec.crc32(ctxCol),
            SegmentColumnCodec.crc32(posCol),
            SegmentColumnCodec.crc32(adjDyn.data),
            SegmentColumnCodec.crc32(posDyn.data)
        };
        byte[] header = WordNetSegmentView.Store.buildHeader(adjKeys.length, adjCount, posCount,
                ctxOff, ctxLen, posOff, posLen, adjVarOff, adjVarLen, posVarOff, posVarLen, crcs);

        File target = WordNetSegmentView.Store.segFile(wordNetRootDir, segId);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(header);
            writePad(out, (int) (ctxOff - WordNetSegmentView.Store.HEADER_SIZE));
            out.write(ctxCol);
            writePad(out, (int) (posOff - (ctxOff + ctxLen)));
            out.write(posCol);
            writePad(out, (int) (adjVarOff - (posOff + posLen)));
            out.write(adjDyn.data);
            writePad(out, (int) (posVarOff - (adjVarOff + adjVarLen)));
            out.write(posDyn.data);
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

        WordNetSegmentView.Store.append(wordNetRootDir, segId);
        String segFingerprint = WordNetSnapshot.sha256Hex(
                Arrays.copyOf(header, WordNetSegmentView.Store.HEADER_FIXED));
        return new WordNetSnapshot(segId, adjKeys.length, adjCount, posCount, segFingerprint);
    }

    /**
     * 打包单上下文下某 srcUid 的 L/R
     * 邻接负载：{@code [count 4B][(d 4B)(dir 4B)(uid 8B)]×count}。
     *
     * <p>
     * dir 编码：{@code 1} = R（target 右侧邻居）、{@code 0} = L；顺序由调用方给定（d↑，同 d 内 R→L）， 与
     * P21 {@code topKNeighbors} 的遍历顺序逐条对准（R12 等价性）。
     */
    private static byte[] packAdj(List<long[]> entries) {
        ByteBuffer bb = ByteBuffer.allocate(4 + entries.size() * 16);
        bb.putInt(entries.size());
        for (long[] e : entries) {
            bb.putInt((int) e[3]);
            bb.putInt((int) e[2]);
            bb.putLong(e[1]);
        }
        return bb.array();
    }

    /**
     * 打包 positions 负载：{@code [count 4B][(start 4B)(end 4B)]×count}。
     */
    private static byte[] packPos(List<PositionSpan> spans) {
        ByteBuffer bb = ByteBuffer.allocate(4 + spans.size() * 8);
        bb.putInt(spans.size());
        for (PositionSpan s : spans) {
            bb.putInt(s.start);
            bb.putInt(s.end);
        }
        return bb.array();
    }

    /**
     * 打包定长索引列（键严格升序）：{@code [key 8B][off 4B][len 4B]} × N。
     */
    private static byte[] packIndex(long[] keys, int[] off, int[] len) {
        ByteBuffer bb = ByteBuffer.allocate(keys.length * INDEX_REC);
        for (int i = 0; i < keys.length; i++) {
            bb.putLong(keys[i]);
            bb.putInt(off[i]);
            bb.putInt(len[i]);
        }
        return bb.array();
    }

    private static long[] keysOf(TreeMap<Long, byte[]> m) {
        long[] out = new long[m.size()];
        int i = 0;
        for (Long k : m.keySet()) {
            out[i++] = k.longValue();
        }
        return out;
    }

    private static byte[][] valuesOf(TreeMap<Long, byte[]> m) {
        byte[][] out = new byte[m.size()][];
        int i = 0;
        for (byte[] v : m.values()) {
            out[i++] = v;
        }
        return out;
    }

    private static long align4k(long v) {
        long page = WordNetSegmentView.Store.HEADER_SIZE;
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
