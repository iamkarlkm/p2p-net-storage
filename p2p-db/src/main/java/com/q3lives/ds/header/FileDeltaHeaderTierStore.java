package com.q3lives.ds.header;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.BitSet;

public class FileDeltaHeaderTierStore implements HeaderTieredStore {

    public static final int MAGIC = 0x48445254; // "HDRT"
    public static final int VERSION = 2;
    public static final int HEADER_SIZE = 64;
    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_BLOCK_SIZE = 8;
    public static final int OFF_FLAGS = 16;
    public static final int OFF_DIRTY_BITSET_BYTES = 24;
    public static final int OFF_USER_RESERVED = 32;
    public static final int OFF_HEADER_CRC32 = 48;
    public static final int OFF_BLOCK_CRC32 = 52;

    private final String name;
    private final int blockSize;
    private final String tierDirPath;
    private volatile TierState state = TierState.IDLE;
    private File deltaFile;
    private RandomAccessFile deltaRaf;
    private FileChannel deltaChannel;
    private ByteBuffer base;
    private final BitSet dirtyBytes;
    private boolean anyDirty;
    private final byte[] cachedBlock;
    private final BitSet mergingDirtyBytes;
    private final byte[] mergingBlock;
    private boolean mergingActive = false;

    private static final int[] CRC32_TABLE = buildCrc32Table();

    private static int[] buildCrc32Table() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                c = (c & 1) != 0 ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            table[i] = c;
        }
        return table;
    }

    private static int crc32(byte[] b, int off, int len) {
        int c = 0xFFFFFFFF;
        for (int i = off, end = off + len; i < end; i++) {
            c = CRC32_TABLE[(c ^ (b[i] & 0xFF)) & 0xFF] ^ (c >>> 8);
        }
        return c ^ 0xFFFFFFFF;
    }

    public FileDeltaHeaderTierStore(String name, String tierDirPath) {
        this(name, 64 * 1024, tierDirPath);
    }

    public FileDeltaHeaderTierStore(String name, int blockSize, String tierDirPath) {
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize");
        this.name = name;
        this.blockSize = blockSize;
        this.tierDirPath = tierDirPath;
        this.dirtyBytes = new BitSet(blockSize);
        this.cachedBlock = new byte[blockSize];
        this.mergingDirtyBytes = new BitSet(blockSize);
        this.mergingBlock = new byte[blockSize];
    }

    private String safeName() {
        String s = name == null ? "hdr" : name;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "hdr" : sb.toString();
    }

    @Override
    public synchronized void attachBase(ByteBuffer baseHeaderBlock) throws IOException {
        if (baseHeaderBlock == null) throw new NullPointerException("baseHeaderBlock");
        if (baseHeaderBlock.capacity() != blockSize) {
            throw new IOException("header blockSize mismatch: expected=" + blockSize + " actual=" + baseHeaderBlock.capacity());
        }
        dirtyBytes.clear();
        mergingDirtyBytes.clear();
        Arrays.fill(cachedBlock, (byte) 0);
        Arrays.fill(mergingBlock, (byte) 0);
        anyDirty = false;
        mergingActive = false;
        this.base = baseHeaderBlock;
        ensureDeltaFileOpen();
        boolean recovered = tryRecoverFromFile();
        if (!recovered) recovered = tryRecoverFromYesterdayFile();
        state = TierState.IDLE;
        try { DailyMergeService.getInstance().register(this); } catch (Throwable ignore) {}
    }

    private void ensureDeltaFileOpen() throws IOException {
        if (deltaRaf != null) return;
        File dir;
        if (tierDirPath == null || tierDirPath.isEmpty()) {
            if (base == null) {
                throw new IOException("tierDirPath or dataFile is required");
            }
            dir = null;
        } else {
            dir = new File(tierDirPath);
        }
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File f;
        if (dir == null) {
            f = new File(safeName() + ".hdr_tier");
        } else {
            f = new File(dir, safeName() + ".hdr_tier");
        }
        this.deltaFile = f;
        if (!deltaFile.getParentFile().exists()) {
            deltaFile.getParentFile().mkdirs();
        }
        this.deltaRaf = new RandomAccessFile(deltaFile, "rw");
        this.deltaChannel = deltaRaf.getChannel();
    }

    private boolean tryRecoverFromFile() throws IOException {
        long flen = deltaRaf.length();
        if (flen < HEADER_SIZE + blockSize) {
            initEmptyDeltaFile();
            return false;
        }
        byte[] head = new byte[HEADER_SIZE];
        deltaRaf.seek(0);
        deltaRaf.readFully(head);
        ByteBuffer hb = ByteBuffer.wrap(head);
        int magic = hb.getInt(OFF_MAGIC);
        if (magic != MAGIC) {
            initEmptyDeltaFile();
            return false;
        }
        int ver = hb.getInt(OFF_VERSION);
        if (ver < VERSION) {
            migrateLegacyV1();
            return true;
        }
        int storedHeadCrc = hb.getInt(OFF_HEADER_CRC32);
        int calcHeadCrc = crc32(head, 0, OFF_HEADER_CRC32);
        int storedBlockCrc = hb.getInt(OFF_BLOCK_CRC32);
        int bs = hb.getInt(OFF_BLOCK_SIZE);
        if (storedHeadCrc != calcHeadCrc || bs != blockSize) {
            initEmptyDeltaFile();
            return false;
        }
        int dirtyBytesLen = hb.getInt(OFF_DIRTY_BITSET_BYTES);
        if (dirtyBytesLen <= 0 || dirtyBytesLen > blockSize) {
            initEmptyDeltaFile();
            return false;
        }
        if (flen < HEADER_SIZE + dirtyBytesLen + blockSize) {
            initEmptyDeltaFile();
            return false;
        }
        byte[] dirtyArr = new byte[dirtyBytesLen];
        deltaRaf.readFully(dirtyArr);
        BitSet loaded = BitSet.valueOf(dirtyArr);
        byte[] block = new byte[blockSize];
        deltaRaf.readFully(block);
        int calcBlockCrc = crc32(block, 0, blockSize);
        if (calcBlockCrc != storedBlockCrc) {
            initEmptyDeltaFile();
            return false;
        }
        System.arraycopy(block, 0, cachedBlock, 0, blockSize);
        dirtyBytes.clear();
        dirtyBytes.or(loaded);
        anyDirty = !dirtyBytes.isEmpty();
        if (anyDirty) {
            int next = -1;
            while ((next = dirtyBytes.nextSetBit(next + 1)) >= 0) {
                int end = dirtyBytes.nextClearBit(next);
                base.put(next, cachedBlock, next, end - next);
                next = end - 1;
                if (next >= blockSize - 1) break;
            }
        }
        return true;
    }

    private void migrateLegacyV1() throws IOException {
        long flen = deltaRaf.length();
        int v1Head = 40;
        if (flen < v1Head + blockSize) {
            initEmptyDeltaFile();
            return;
        }
        deltaRaf.seek(0);
        int magic = deltaRaf.readInt();
        if (magic != MAGIC) { initEmptyDeltaFile(); return; }
        int bs = deltaRaf.readInt();
        if (bs != blockSize) { initEmptyDeltaFile(); return; }
        deltaRaf.skipBytes(8);
        int dirtyBytesLen = deltaRaf.readInt();
        if (dirtyBytesLen <= 0 || dirtyBytesLen > blockSize) { initEmptyDeltaFile(); return; }
        byte[] dirtyArr = new byte[dirtyBytesLen];
        deltaRaf.readFully(dirtyArr);
        byte[] block = new byte[blockSize];
        deltaRaf.readFully(block);
        BitSet loaded = BitSet.valueOf(dirtyArr);
        writeDeltaFileInternal(loaded, block);
        dirtyBytes.clear();
        dirtyBytes.or(loaded);
        anyDirty = !dirtyBytes.isEmpty();
        System.arraycopy(block, 0, cachedBlock, 0, blockSize);
        if (anyDirty) {
            int next = -1;
            while ((next = dirtyBytes.nextSetBit(next + 1)) >= 0) {
                int end = dirtyBytes.nextClearBit(next);
                base.put(next, cachedBlock, next, end - next);
                next = end - 1;
                if (next >= blockSize - 1) break;
            }
        }
    }

    private boolean tryRecoverFromYesterdayFile() throws IOException {
        if (deltaFile == null) return false;
        File parent = deltaFile.getParentFile();
        String baseName = safeName() + ".hdr_tier";
        File[] candidates = parent == null ? new File(".").listFiles() : parent.listFiles();
        if (candidates == null || candidates.length == 0) return false;
        File best = null;
        long bestMod = -1L;
        for (File c : candidates) {
            String n = c.getName();
            if (!n.startsWith(baseName + ".")) continue;
            if (n.endsWith(".tmp")) continue;
            long lm = c.lastModified();
            if (lm > bestMod) { bestMod = lm; best = c; }
        }
        if (best == null || !best.exists()) return false;
        try (RandomAccessFile yraf = new RandomAccessFile(best, "r")) {
            long ylen = yraf.length();
            if (ylen < HEADER_SIZE + blockSize) return false;
            byte[] yhead = new byte[HEADER_SIZE];
            yraf.seek(0);
            yraf.readFully(yhead);
            ByteBuffer hb = ByteBuffer.wrap(yhead);
            if (hb.getInt(OFF_MAGIC) != MAGIC) return false;
            int ver = hb.getInt(OFF_VERSION);
            if (ver < VERSION) return false;
            int headCrc = hb.getInt(OFF_HEADER_CRC32);
            if (headCrc != crc32(yhead, 0, OFF_HEADER_CRC32)) return false;
            if (hb.getInt(OFF_BLOCK_SIZE) != blockSize) return false;
            int dblen = hb.getInt(OFF_DIRTY_BITSET_BYTES);
            if (dblen <= 0 || dblen > blockSize) return false;
            if (ylen < HEADER_SIZE + dblen + blockSize) return false;
            byte[] dirtyArr = new byte[dblen];
            yraf.readFully(dirtyArr);
            BitSet loaded = BitSet.valueOf(dirtyArr);
            byte[] block = new byte[blockSize];
            yraf.readFully(block);
            int calcBlockCrc = crc32(block, 0, blockSize);
            if (calcBlockCrc != hb.getInt(OFF_BLOCK_CRC32)) return false;
            System.arraycopy(block, 0, cachedBlock, 0, blockSize);
            dirtyBytes.clear();
            dirtyBytes.or(loaded);
            anyDirty = !dirtyBytes.isEmpty();
            if (anyDirty) {
                int next = -1;
                while ((next = dirtyBytes.nextSetBit(next + 1)) >= 0) {
                    int end = dirtyBytes.nextClearBit(next);
                    base.put(next, cachedBlock, next, end - next);
                    next = end - 1;
                    if (next >= blockSize - 1) break;
                }
            }
            return true;
        }
    }

    private void initEmptyDeltaFile() throws IOException {
        byte[] zeros = new byte[blockSize];
        Arrays.fill(cachedBlock, (byte) 0);
        writeDeltaFileInternal(new BitSet(blockSize), zeros);
        dirtyBytes.clear();
        anyDirty = false;
    }

    private void writeDeltaFileInternal(BitSet bs, byte[] block) throws IOException {
        deltaRaf.setLength(0);
        byte[] head = new byte[HEADER_SIZE];
        ByteBuffer hb = ByteBuffer.wrap(head);
        hb.putInt(OFF_MAGIC, MAGIC);
        hb.putInt(OFF_VERSION, VERSION);
        hb.putInt(OFF_BLOCK_SIZE, blockSize);
        hb.putLong(OFF_FLAGS, 0L);
        byte[] dirtyArr = bs.toByteArray();
        hb.putInt(OFF_DIRTY_BITSET_BYTES, dirtyArr.length);
        hb.putLong(OFF_USER_RESERVED, 0L);
        int blockCrc = crc32(block, 0, blockSize);
        hb.putInt(OFF_BLOCK_CRC32, blockCrc);
        int headCrc = crc32(head, 0, OFF_HEADER_CRC32);
        hb.putInt(OFF_HEADER_CRC32, headCrc);
        deltaRaf.write(head);
        deltaRaf.write(dirtyArr);
        if (dirtyArr.length < (HEADER_SIZE - HEADER_SIZE)) {}
        byte[] tailPad = new byte[Math.max(0, HEADER_SIZE - head.length - dirtyArr.length)];
        deltaRaf.write(tailPad);
        deltaRaf.write(block);
        deltaRaf.getChannel().force(true);
    }

    @Override
    public synchronized ByteBuffer getReadBuffer() {
        if (base == null) throw new IllegalStateException("attachBase not called");
        if (mergingActive) {
            applyOverlaySkipToday(mergingBlock, mergingDirtyBytes, dirtyBytes);
        }
        return base;
    }

    private void applyOverlay(byte[] src, BitSet mask) {
        if (src == null || mask == null || mask.isEmpty() || base == null) return;
        int next = -1;
        while ((next = mask.nextSetBit(next + 1)) >= 0) {
            int end = mask.nextClearBit(next);
            base.put(next, src, next, end - next);
            next = end - 1;
            if (next >= blockSize - 1) break;
        }
    }

    private void applyOverlaySkipToday(byte[] src, BitSet mask, BitSet todayCleanSkip) {
        if (src == null || mask == null || mask.isEmpty() || base == null) return;
        int next = -1;
        while ((next = mask.nextSetBit(next + 1)) >= 0) {
            int end = mask.nextClearBit(next);
            for (int i = next; i < end; i++) {
                if (todayCleanSkip != null && todayCleanSkip.get(i)) continue;
                base.put(i, src[i]);
            }
            next = end - 1;
            if (next >= blockSize - 1) break;
        }
    }

    @Override
    public synchronized ByteBuffer getWriteBufferForField(int offset, int len) {
        if (base == null) throw new IllegalStateException("attachBase not called");
        if (offset < 0 || len <= 0 || (long) offset + len > blockSize) {
            throw new IndexOutOfBoundsException("offset=" + offset + " len=" + len);
        }
        return base;
    }

    @Override
    public synchronized void markFieldDirty(int offset, int len) {
        if (base == null || offset < 0 || len <= 0) return;
        int end = Math.min(blockSize, offset + len);
        if (end <= offset) return;
        dirtyBytes.set(offset, end);
        try {
            ByteBuffer src = base.duplicate();
            src.position(offset);
            src.limit(end);
            src.get(cachedBlock, offset, end - offset);
        } catch (Throwable ignore) {
            for (int i = offset; i < end; i++) {
                cachedBlock[i] = base.get(i);
            }
        }
        anyDirty = true;
    }

    @Override
    public synchronized void markFullDirty() {
        if (base == null) return;
        dirtyBytes.set(0, blockSize);
        try {
            ByteBuffer src = base.duplicate();
            src.position(0);
            src.limit(blockSize);
            src.get(cachedBlock, 0, blockSize);
        } catch (Throwable ignore) {
            for (int i = 0; i < blockSize; i++) {
                cachedBlock[i] = base.get(i);
            }
        }
        anyDirty = true;
    }

    @Override
    public synchronized boolean isDirty() {
        return anyDirty;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (base == null || deltaRaf == null) return;
        writeDeltaFile();
    }

    @Override
    public TierState getState() {
        return state;
    }

    @Override
    public synchronized void rollover(String dayKey) throws IOException {
        if (base == null) return;
        writeDeltaFile();
        boolean doCopy = deltaFile != null && deltaFile.exists() && anyDirty;
        state = TierState.ROLLOVER_PREP;
        if (doCopy) {
            System.arraycopy(cachedBlock, 0, mergingBlock, 0, blockSize);
            mergingDirtyBytes.clear();
            mergingDirtyBytes.or(dirtyBytes);
            mergingActive = true;
            String newName = safeName() + ".hdr_tier." + (dayKey == null ? String.valueOf(System.currentTimeMillis()) : dayKey);
            File target;
            if (deltaFile.getParentFile() == null) {
                target = new File(newName);
            } else {
                target = new File(deltaFile.getParentFile(), newName);
            }
            if (!target.exists()) {
                if (deltaChannel != null) deltaChannel.force(true);
                deltaRaf.close();
                deltaRaf = null;
                deltaChannel = null;
                boolean renamed = deltaFile.renameTo(target);
                if (!renamed) {
                    ensureDeltaFileOpen();
                }
            }
        }
        if (deltaRaf == null) ensureDeltaFileOpen();
        initEmptyDeltaFile();
        state = TierState.MERGING;
    }

    @Override
    public synchronized boolean prepareMerge() throws IOException {
        if (state == TierState.IDLE) {
            if (!anyDirty) {
                state = TierState.MERGE_SWAP_PENDING;
                return false;
            }
            writeDeltaFile();
            System.arraycopy(cachedBlock, 0, mergingBlock, 0, blockSize);
            mergingDirtyBytes.clear();
            mergingDirtyBytes.or(dirtyBytes);
            mergingActive = true;
            initEmptyDeltaFile();
            state = TierState.MERGING;
        }
        return true;
    }

    @Override
    public synchronized boolean applyMergedToBase() throws IOException {
        if (state != TierState.MERGING && state != TierState.MERGE_SWAP_PENDING) return false;
        try {
            applyOverlaySkipToday(mergingBlock, mergingDirtyBytes, dirtyBytes);
            if (base != null && (base instanceof java.nio.MappedByteBuffer)) {
                try { ((java.nio.MappedByteBuffer) base).force(); } catch (Throwable ignore) {}
            }
            return true;
        } finally {
            mergingActive = false;
            Arrays.fill(mergingBlock, (byte) 0);
            mergingDirtyBytes.clear();
            state = TierState.IDLE;
        }
    }

    @Override
    public synchronized void cancelMerge() throws IOException {
        mergingActive = false;
        Arrays.fill(mergingBlock, (byte) 0);
        mergingDirtyBytes.clear();
        state = TierState.IDLE;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            try { DailyMergeService.getInstance().unregister(this); } catch (Throwable ignore) {}
            if (deltaRaf != null) {
                writeDeltaFile();
                try {
                    if (deltaChannel != null) deltaChannel.force(true);
                } finally {
                    deltaRaf.close();
                    deltaRaf = null;
                    deltaChannel = null;
                }
            }
        } finally {
            base = null;
            dirtyBytes.clear();
            mergingDirtyBytes.clear();
            Arrays.fill(cachedBlock, (byte) 0);
            Arrays.fill(mergingBlock, (byte) 0);
            anyDirty = false;
            mergingActive = false;
            state = TierState.IDLE;
        }
    }

    @Override
    public String debugName() {
        return "FileDeltaHeaderTierStore[" + name + ", dir=" + tierDirPath + "]";
    }

    public synchronized ByteBuffer snapshotDeltaBlockForMerge() {
        if (!anyDirty) return null;
        return ByteBuffer.wrap(Arrays.copyOf(cachedBlock, cachedBlock.length));
    }

    public synchronized BitSet dirtyBitSetSnapshot() {
        return (BitSet) dirtyBytes.clone();
    }

    private void writeDeltaFile() throws IOException {
        if (deltaRaf == null) ensureDeltaFileOpen();
        int pos = 0;
        for (int i = 0; i < blockSize; i++) {
            byte v = base.get(i);
            if (cachedBlock[i] != v) anyDirty = true;
            cachedBlock[i] = v;
        }
        writeDeltaFileInternal(dirtyBytes, cachedBlock);
    }
}
