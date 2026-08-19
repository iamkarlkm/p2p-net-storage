package com.q3lives.ds.binlog;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DsBinlog 门面。
 *
 * <p>设计原则（与用户要求一一对应）：
 * <ol>
 *   <li><b>MySQL binlog 风格 row 交换格式</b>：op_type + col_ids 位图 + col_values 按 slot 顺序 packed。</li>
 *   <li><b>固定长度提高性能</b>：每帧 {@link DsBinlogLayout#FIXED_FRAME_SIZE}=256B 定长 → O(1) frameOffset = frameId * 256，无需 skip list 即可按序 seek。</li>
 *   <li><b>动态长度 DsBytes 索引</b>：超大 byte[]/string 列走 {@link DsBytes}（封装 DsData 内容寻址 + refCount），
 *       binlog dyn 文件中只写 8B indexId + 不重复内容存储，避免跨帧/跨日同列值重复膨胀。</li>
 *   <li><b>固定/动态独立存储</b>：{@code dsbinlog_<dayKey>_fixed.dat} 只写 256B 定长帧；
 *       {@code dsbinlog_<dayKey>_dyn.dat} 只写变长 payload；{@code _binlog_bytes/} 为 DsData 索引 store。三者独立 append、互不干扰。</li>
 *   <li><b>回放不写二进制日志</b>：{@link DsBinlogContext#isInReplay()} 为 true 时，
 *       {@link #append} 直接返回 -1 不做任何 write（handler 内部任何业务调用 append 都会被短路）。</li>
 * </ol>
 *
 * <p>日切 rotate：{@link #rotate(String)} 关闭当前 writer，切换到新的 dayKey；
 * 配合 {@link DailyMergeService}（或外部定时器）每天凌晨 3 点自动 rollover。
 *
 * <p>人工重试按钮（永不禁用）：{@link #forceFlushAll()}、{@link #rotate()}、{@link #replayAll(DsBinlogHandler)}
 * 全部公开 API，无状态门禁。
 */
public final class DsBinlogStore implements Closeable {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Object monitor = new Object();
    private final File rootDir;
    private final File storeDir;
    private final DsBytes dsBytes;
    private final AtomicLong nextSeq = new AtomicLong(0L);
    private final boolean enableDsBytes;

    private String currentDayKey;
    private DsBinlogWriter writer;

    public DsBinlogStore(String rootDir) throws IOException {
        this(new File(rootDir), true);
    }

    public DsBinlogStore(File rootDir) throws IOException {
        this(rootDir, true);
    }

    public DsBinlogStore(File rootDir, boolean enableDsBytes) throws IOException {
        if (rootDir == null) throw new NullPointerException("rootDir");
        this.rootDir = rootDir;
        this.storeDir = new File(rootDir, "_binlog");
        if (!this.storeDir.exists()) this.storeDir.mkdirs();
        this.enableDsBytes = enableDsBytes;
        this.dsBytes = enableDsBytes ? new DsBytes(storeDir) : null;
        this.currentDayKey = LocalDate.now().format(DAY_FMT);
        this.writer = new DsBinlogWriter(storeDir, currentDayKey);
    }

    public DsBytes dsBytes() { return dsBytes; }
    public String currentDayKey() { synchronized (monitor) { return currentDayKey; } }

    public long nextSequence() { return nextSeq.incrementAndGet(); }

    /**
     * 追加一行 binlog 帧（外部调用主入口）。
     *
     * <p>若当前线程处于回放（{@link DsBinlogContext#isInReplay()}），则直接返回 -1，不写入任何文件。
     * 这是硬约束：回放写入绝不递归产生新 binlog。
     *
     * @param opType   操作类型
     * @param serverId 节点 id
     * @param sequence 单调递增序列号（可传 0 让 store 自己 nextSeq）
     * @param tableId  逻辑表 id（无则传 0）
     * @param rowKey   行 hash 或主键低 32 位（无则传 0）
     * @param colIds   出现的列 id（必须 <= COL_IDS_MAX=512，且 slot 数 < 64）
     * @param values   与 colIds 同序的值对象，支持 null/Short/Integer/Long/Float/Double/byte[]/Long(DsBytes indexId)
     * @return fixed 文件中的 byte 偏移；回放跳过或空帧返回 -1
     */
    public long append(DsBinlogOpType opType, int serverId, long sequence,
                       int tableId, int rowKey, int[] colIds, Object[] values) throws IOException {
        if (DsBinlogContext.isInReplay()) return -1L;
        if (colIds == null || colIds.length == 0) return -1L;
        if (values == null || values.length != colIds.length) {
            throw new IllegalArgumentException("colIds/values length mismatch");
        }
        if (colIds.length > DsBinlogLayout.COL_FIXED_SLOT_COUNT) {
            throw new IllegalArgumentException("too many cols, max=" + DsBinlogLayout.COL_FIXED_SLOT_COUNT);
        }

        BitSet mask = new BitSet(DsBinlogLayout.COL_IDS_MAX);
        for (int c : colIds) {
            if (c < 0 || c >= DsBinlogLayout.COL_IDS_MAX) {
                throw new IllegalArgumentException("colId out of range [0," + DsBinlogLayout.COL_IDS_MAX + "): " + c);
            }
            mask.set(c);
        }

        List<byte[]> dynList = new ArrayList<>(colIds.length);
        short[] slots = new short[colIds.length];

        for (int i = 0; i < colIds.length; i++) {
            Object v = values[i];
            if (v == null) {
                slots[i] = (short) DsBinlogLayout.colSlotPack(0, DsBinlogLayout.DVT_NULL);
                continue;
            }
            if (v instanceof Short) {
                short s = ((Short) v).shortValue();
                if (DsBinlogLayout.canInlineShort(s)) {
                    slots[i] = (short) DsBinlogLayout.colSlotPackInline(s);
                } else {
                    byte[] payload = new byte[2];
                    ByteBuffer.wrap(payload).putShort(0, s);
                    slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_SHORT);
                    dynList.add(segmentWrap(payload));
                }
                continue;
            }
            if (v instanceof Integer) {
                int iv = (Integer) v;
                if (DsBinlogLayout.canInlineInt(iv)) {
                    slots[i] = (short) DsBinlogLayout.colSlotPackInline((short) iv);
                    continue;
                }
                byte[] payload = new byte[4];
                ByteBuffer.wrap(payload).putInt(0, iv);
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_INT);
                dynList.add(segmentWrap(payload));
                continue;
            }
            if (v instanceof Long) {
                long lv = (Long) v;
                if (DsBinlogLayout.canInlineLong(lv)) {
                    slots[i] = (short) DsBinlogLayout.colSlotPackInline((short) lv);
                    continue;
                }
                byte[] payload = new byte[8];
                ByteBuffer.wrap(payload).putLong(0, lv);
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_LONG);
                dynList.add(segmentWrap(payload));
                continue;
            }
            if (v instanceof Float) {
                float fv = (Float) v;
                byte[] payload = new byte[4];
                ByteBuffer.wrap(payload).putFloat(0, fv);
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_FLOAT);
                dynList.add(segmentWrap(payload));
                continue;
            }
            if (v instanceof Double) {
                double dv = (Double) v;
                byte[] payload = new byte[8];
                ByteBuffer.wrap(payload).putDouble(0, dv);
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_DOUBLE);
                dynList.add(segmentWrap(payload));
                continue;
            }
            if (v instanceof byte[]) {
                byte[] raw = (byte[]) v;
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_BYTES_RAW);
                dynList.add(segmentWrap(raw));
                continue;
            }
            if (v instanceof DsBytesIndex) {
                if (!enableDsBytes || dsBytes == null) throw new IllegalStateException("DsBytes disabled");
                long idxId = ((DsBytesIndex) v).indexId;
                byte[] payload = new byte[8];
                ByteBuffer.wrap(payload).putLong(0, idxId);
                slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_DS_BYTES_REF);
                dynList.add(segmentWrap(payload));
                continue;
            }
            // fallback: toString -> bytes raw
            byte[] raw = String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            slots[i] = (short) DsBinlogLayout.colSlotPack(dynList.size(), DsBinlogLayout.DVT_BYTES_RAW);
            dynList.add(segmentWrap(raw));
        }

        long seq = sequence > 0 ? sequence : nextSeq.incrementAndGet();
        long ts = System.currentTimeMillis();

        byte[][] dynPayloads = dynList.isEmpty() ? null : dynList.toArray(new byte[0][]);

        synchronized (monitor) {
            ensureWriterOpenLocked();
            return writer.append(opType.ordinal(), serverId, seq, ts, tableId, rowKey, mask, slots, dynPayloads);
        }
    }

    private static byte[] segmentWrap(byte[] inner) {
        int len = inner == null ? 0 : inner.length;
        ByteBuffer bb = ByteBuffer.allocate(4 + len);
        bb.putInt(len);
        if (len > 0) bb.put(inner);
        return bb.array();
    }

    /** 将 DsBytes 放入字典得到的 indexId 包装成 value，append 时可直接传入。 */
    public static Object ofDsBytesIndex(long indexId) {
        return new DsBytesIndex(indexId);
    }

    private static final class DsBytesIndex {
        final long indexId;
        DsBytesIndex(long indexId) { this.indexId = indexId; }
    }

    /** 关闭当前 writer，切到新 dayKey（不传则用今天）。 */
    public String rotate() throws IOException {
        return rotate(LocalDate.now().format(DAY_FMT));
    }

    public String rotate(String newDayKey) throws IOException {
        if (newDayKey == null || newDayKey.isEmpty()) {
            newDayKey = LocalDate.now().format(DAY_FMT);
        }
        synchronized (monitor) {
            if (writer != null) {
                try { writer.flush(); } catch (Throwable ignore) {}
                try { writer.close(); } catch (Throwable ignore) {}
            }
            currentDayKey = newDayKey;
            writer = new DsBinlogWriter(storeDir, currentDayKey);
            return currentDayKey;
        }
    }

    /** 从当前 dayKey 的开头回放所有帧。 */
    public int replayAll(DsBinlogHandler handler) throws IOException {
        return replayFrom(currentDayKey, 0L, handler);
    }

    /** 指定 dayKey + startOffset 回放。 */
    public int replayFrom(String dayKey, long startOffset, DsBinlogHandler handler) throws IOException {
        try (DsBinlogReplayer r = new DsBinlogReplayer(storeDir, dayKey)) {
            return r.replayFrom(startOffset, handler);
        }
    }

    public List<String> listDayKeys() {
        String prefix = DsBinlogLayout.FILE_PREFIX;
        String suffix = DsBinlogLayout.FIXED_SUFFIX;
        List<String> result = new ArrayList<>();
        File[] files = storeDir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(prefix) && n.endsWith(suffix)) {
                String mid = n.substring(prefix.length(), n.length() - suffix.length());
                if (!mid.isEmpty()) result.add(mid);
            }
        }
        result.sort(null);
        return result;
    }

    public void forceFlushAll() throws IOException {
        synchronized (monitor) {
            ensureWriterOpenLocked();
            writer.flush();
        }
    }

    public static void forceResetForTest(File rootDir) {
        File storeDir = new File(rootDir, "_binlog");
        if (!storeDir.exists()) return;
        File[] files = storeDir.listFiles();
        if (files == null) return;
        for (File f : files) deleteRecursive(f);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] sub = f.listFiles();
            if (sub != null) for (File s : sub) deleteRecursive(s);
        }
        try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignore) {}
    }

    private void ensureWriterOpenLocked() throws IOException {
        if (writer == null) writer = new DsBinlogWriter(storeDir, currentDayKey);
    }

    @Override
    public void close() throws IOException {
        synchronized (monitor) {
            if (writer != null) {
                try { writer.flush(); } catch (Throwable ignore) {}
                try { writer.close(); } catch (Throwable ignore) {}
                writer = null;
            }
            if (dsBytes != null) {
                try { dsBytes.close(); } catch (Throwable ignore) {}
            }
        }
    }
}
