package com.q3lives.ds.binlog;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.BitSet;

/**
 * 写入器：single dayKey → (fixed, dyn) 两个 RAF，帧落盘 + CRC32 IEEE 校验。
 *
 * <p>线程安全：外部通过 DsBinlogStore.monitor 串行化（本类不做内部锁，避免双层竞争）。
 */
final class DsBinlogWriter implements Closeable {

    private static final int[] CRC32_TABLE = buildCrc32Table();

    private static int[] buildCrc32Table() {
        int[] t = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            t[i] = c;
        }
        return t;
    }

    static int crc32(byte[] b, int off, int len) {
        int c = 0xFFFFFFFF;
        for (int i = off, end = off + len; i < end; i++) {
            c = CRC32_TABLE[(c ^ (b[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

    private final RandomAccessFile fixedRaf;
    private final RandomAccessFile dynRaf;
    private final File fixedFile;
    private final File dynFile;
    private final byte[] frameScratch = new byte[DsBinlogLayout.FIXED_FRAME_SIZE];
    private final byte[] crcScratch = new byte[DsBinlogLayout.FIXED_FRAME_SIZE];

    DsBinlogWriter(File dir, String dayKey) throws IOException {
        if (!dir.exists()) dir.mkdirs();
        this.fixedFile = new File(dir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.FIXED_SUFFIX);
        this.dynFile = new File(dir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.DYN_SUFFIX);
        this.fixedRaf = new RandomAccessFile(fixedFile, "rw");
        this.dynRaf = new RandomAccessFile(dynFile, "rw");
        this.fixedRaf.seek(fixedRaf.length());
        this.dynRaf.seek(dynRaf.length());
    }

    long nextFrameOffset() throws IOException {
        return fixedRaf.getFilePointer();
    }

    long dynBytesWritten() throws IOException {
        return dynRaf.getFilePointer();
    }

    /**
     * 追加一帧。
     * @return 本帧在 fixed 文件中的 byte 偏移（可直接用于 replayFrom(offset)）。
     */
    long append(int opTypeOrdinal, int serverId, long sequence, long timestamp,
               int tableId, int rowKey,
               BitSet colIdsBitSet, short[] packedSlots, byte[][] dynPayloads) throws IOException {
        if (colIdsBitSet.length() > DsBinlogLayout.COL_IDS_MAX) {
            throw new IllegalArgumentException("col count exceeds " + DsBinlogLayout.COL_IDS_MAX);
        }
        if (packedSlots.length > DsBinlogLayout.COL_FIXED_SLOT_COUNT) {
            throw new IllegalArgumentException("slot count exceeds " + DsBinlogLayout.COL_FIXED_SLOT_COUNT);
        }

        long frameOffset = fixedRaf.getFilePointer();
        long dynStart = -1L;
        int dynLen = 0;
        int dynCount = 0;
        if (dynPayloads != null && dynPayloads.length > 0) {
            dynStart = dynRaf.getFilePointer();
            for (byte[] p : dynPayloads) {
                if (p == null) continue;
                dynRaf.write(p);
                dynLen += p.length;
                dynCount++;
            }
        }

        byte[] f = frameScratch;
        for (int i = 0; i < DsBinlogLayout.FIXED_FRAME_SIZE; i++) f[i] = 0;
        ByteBuffer buf = ByteBuffer.wrap(f);
        buf.putInt(DsBinlogLayout.OFF_MAGIC, DsBinlogLayout.MAGIC);
        buf.putInt(DsBinlogLayout.OFF_VERSION, DsBinlogLayout.VERSION);
        int flags = 0;
        if (dynLen > 0) flags |= DsBinlogLayout.FLAG_BIT_HAS_DYN;
        buf.putInt(DsBinlogLayout.OFF_FLAGS, flags);
        buf.putInt(DsBinlogLayout.OFF_CRC32, 0); // 占位，写完整体再算
        buf.putInt(DsBinlogLayout.OFF_OP_TYPE, opTypeOrdinal);
        buf.putInt(DsBinlogLayout.OFF_SERVER_ID, serverId);
        buf.putLong(DsBinlogLayout.OFF_SEQUENCE, sequence);
        buf.putLong(DsBinlogLayout.OFF_TIMESTAMP, timestamp);
        buf.putInt(DsBinlogLayout.OFF_TABLE_ID, tableId);
        buf.putInt(DsBinlogLayout.OFF_ROW_KEY, rowKey);
        buf.putLong(DsBinlogLayout.OFF_DYN_START, dynStart);
        buf.putInt(DsBinlogLayout.OFF_DYN_LEN, dynLen);
        buf.putInt(DsBinlogLayout.OFF_DYN_COUNT, dynCount);

        byte[] maskBytes = colIdsBitSet.toByteArray();
        int copyLen = Math.min(maskBytes.length, DsBinlogLayout.COL_IDS_MASK_BYTES);
        System.arraycopy(maskBytes, 0, f, DsBinlogLayout.OFF_COL_IDS_MASK, copyLen);

        for (int i = 0; i < packedSlots.length; i++) {
            buf.putShort(DsBinlogLayout.OFF_COL_FIXED + i * DsBinlogLayout.COL_FIXED_SLOT_BYTES, packedSlots[i]);
        }

        System.arraycopy(f, 0, crcScratch, 0, DsBinlogLayout.FIXED_FRAME_SIZE);
        int crc = crc32(crcScratch, 0, DsBinlogLayout.FIXED_FRAME_SIZE);
        buf.putInt(DsBinlogLayout.OFF_CRC32, crc);

        fixedRaf.write(f);
        return frameOffset;
    }

    void force(boolean meta) throws IOException {
        fixedRaf.getFD().sync();
        dynRaf.getFD().sync();
    }

    void flush() throws IOException {
        // RAF 无缓冲，这里只做 sync；保留方法签名与上层语义一致
        force(true);
    }

    File fixedFile() { return fixedFile; }
    File dynFile() { return dynFile; }

    @Override
    public void close() throws IOException {
        try { fixedRaf.close(); } catch (Throwable ignore) {}
        try { dynRaf.close(); } catch (Throwable ignore) {}
    }
}
