package com.q3lives.ds.binlog;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 回放器：按 dayKey 读取 fixed/dyn 两个文件，逐帧 CRC 校验、解析、调用 {@link DsBinlogHandler}。
 *
 * <p>回放期间，调用 handler.apply 前会先调用 {@link DsBinlogContext#setInReplay(boolean)} set true，
 * 返回后 clear，保证 apply 内部任何业务逻辑触发的 DsBinlogStore.append 都会短路跳过（不循环写 binlog）。
 *
 * <p>CRC 失败：直接 break（视为半写/坏帧），不抛异常，返回已成功解析帧数。与 StoreIdRegistry V2、FileDelta legacy
 * 三阶段 fallback 的风格保持一致：宁愿截断丢最后一条，不继续越过坏帧往后读（防撕裂蔓延）。
 */
final class DsBinlogReplayer implements Closeable {

    private static final int CHUNK_SIZE = 64 * 1024; // 256 frames * 256B

    private final RandomAccessFile fixedRaf;
    private final RandomAccessFile dynRaf;
    private final File fixedFile;
    private final File dynFile;
    private final byte[] frameBuf = new byte[DsBinlogLayout.FIXED_FRAME_SIZE];
    private final byte[] crcBuf = new byte[DsBinlogLayout.FIXED_FRAME_SIZE];
    private final byte[] chunkBuf = new byte[CHUNK_SIZE];

    DsBinlogReplayer(File dir, String dayKey) throws IOException {
        this.fixedFile = new File(dir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.FIXED_SUFFIX);
        this.dynFile = new File(dir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.DYN_SUFFIX);
        if (!fixedFile.exists()) throw new java.io.FileNotFoundException(fixedFile.getAbsolutePath());
        this.fixedRaf = new RandomAccessFile(fixedFile, "r");
        this.dynRaf = dynFile.exists() ? new RandomAccessFile(dynFile, "r") : null;
    }

    /**
     * @return 成功回放帧数（CRC fail/半写 frame 处 stop，不继续往后）。
     */
    int replayAll(DsBinlogHandler handler) throws IOException {
        fixedRaf.seek(0);
        return replayFrom(0L, handler);
    }

    int replayFrom(long startOffset, DsBinlogHandler handler) throws IOException {
        if (startOffset < 0) startOffset = 0;
        long frameSize = DsBinlogLayout.FIXED_FRAME_SIZE;
        if (startOffset % frameSize != 0) {
            // 对齐到上一个 frame 起点
            startOffset = startOffset - (startOffset % frameSize);
        }
        long len = fixedRaf.length();
        if (startOffset >= len) return 0;

        int processed = 0;
        long pos = startOffset;
        while (pos + frameSize <= len) {
            int toRead = (int) Math.min((long) CHUNK_SIZE, len - pos);
            toRead = toRead - (int) (toRead % frameSize);
            if (toRead == 0) break;

            fixedRaf.seek(pos);
            int readN = 0;
            while (readN < toRead) {
                int r = fixedRaf.read(chunkBuf, readN, toRead - readN);
                if (r < 0) break;
                readN += r;
            }
            if (readN < frameSize) break; // 不足一帧，视为尾部半写

            int frames = readN / (int) frameSize;
            for (int i = 0; i < frames; i++) {
                long framePos = pos + (long) i * frameSize;
                System.arraycopy(chunkBuf, i * (int) frameSize, frameBuf, 0, (int) frameSize);
                if (!processFrame(framePos, handler)) {
                    return processed;
                }
                processed++;
            }
            pos += (long) frames * frameSize;
        }
        return processed;
    }

    private boolean processFrame(long pos, DsBinlogHandler handler) throws IOException {
        int magic = ByteBuffer.wrap(frameBuf, DsBinlogLayout.OFF_MAGIC, 4).getInt();
        if (magic != DsBinlogLayout.MAGIC) return false; // MAGIC 不匹配视为坏帧或尾垃圾，不再向后

        int storedCrc = ByteBuffer.wrap(frameBuf, DsBinlogLayout.OFF_CRC32, 4).getInt();
        System.arraycopy(frameBuf, 0, crcBuf, 0, DsBinlogLayout.FIXED_FRAME_SIZE);
        crcBuf[DsBinlogLayout.OFF_CRC32 + 0] = 0;
        crcBuf[DsBinlogLayout.OFF_CRC32 + 1] = 0;
        crcBuf[DsBinlogLayout.OFF_CRC32 + 2] = 0;
        crcBuf[DsBinlogLayout.OFF_CRC32 + 3] = 0;
        int calcCrc = DsBinlogWriter.crc32(crcBuf, 0, DsBinlogLayout.FIXED_FRAME_SIZE);
        if (storedCrc != calcCrc) return false; // 与写侧对齐：CRC 非零→半写，整条 truncate

        ByteBuffer b = ByteBuffer.wrap(frameBuf);
        int flags = b.getInt(DsBinlogLayout.OFF_FLAGS);
        int opTypeOrdinal = b.getInt(DsBinlogLayout.OFF_OP_TYPE);
        int serverId = b.getInt(DsBinlogLayout.OFF_SERVER_ID);
        long sequence = b.getLong(DsBinlogLayout.OFF_SEQUENCE);
        long timestamp = b.getLong(DsBinlogLayout.OFF_TIMESTAMP);
        int tableId = b.getInt(DsBinlogLayout.OFF_TABLE_ID);
        int rowKey = b.getInt(DsBinlogLayout.OFF_ROW_KEY);
        long dynStart = b.getLong(DsBinlogLayout.OFF_DYN_START);
        int dynLen = b.getInt(DsBinlogLayout.OFF_DYN_LEN);
        int dynCount = b.getInt(DsBinlogLayout.OFF_DYN_COUNT);

        byte[] maskArr = new byte[DsBinlogLayout.COL_IDS_MASK_BYTES];
        System.arraycopy(frameBuf, DsBinlogLayout.OFF_COL_IDS_MASK, maskArr, 0, DsBinlogLayout.COL_IDS_MASK_BYTES);
        BitSet mask = BitSet.valueOf(maskArr);
        int[] colIds = mask.stream().toArray();

        short[] slots = new short[colIds.length];
        for (int i = 0; i < colIds.length; i++) {
            slots[i] = b.getShort(DsBinlogLayout.OFF_COL_FIXED + i * DsBinlogLayout.COL_FIXED_SLOT_BYTES);
        }

        List<byte[]> dynFrames = new ArrayList<>(Math.max(dynCount, 0));
        if (dynCount > 0 && dynLen > 0 && dynStart >= 0 && dynRaf != null) {
            dynRaf.seek(dynStart);
            int remaining = dynLen;
            for (int i = 0; i < dynCount && remaining > 0; i++) {
                if (remaining < 4) break;
                int segLen = dynRaf.readInt();
                remaining -= 4;
                if (segLen < 0 || segLen > remaining) break;
                byte[] seg = new byte[segLen];
                if (segLen > 0) {
                    int got = 0;
                    while (got < segLen) {
                        int r = dynRaf.read(seg, got, segLen - got);
                        if (r < 0) break;
                        got += r;
                    }
                    if (got < segLen) break;
                    remaining -= segLen;
                }
                dynFrames.add(seg);
            }
        }

        DsBinlogEntry entry = new DsBinlogEntry(flags, opTypeOrdinal, serverId, sequence, timestamp, tableId, rowKey, mask, colIds, slots, dynFrames);

        boolean wasInReplay = DsBinlogContext.isInReplay();
        try {
            if (!wasInReplay) DsBinlogContext.setInReplay(true);
            return handler.apply(pos, entry);
        } finally {
            if (!wasInReplay) DsBinlogContext.clear();
        }
    }

    @Override
    public void close() throws IOException {
        try { fixedRaf.close(); } catch (Throwable ignore) {}
        try { if (dynRaf != null) dynRaf.close(); } catch (Throwable ignore) {}
    }
}
