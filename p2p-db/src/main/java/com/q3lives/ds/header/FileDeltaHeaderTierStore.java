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
    public static final int HEADER_SIZE = 40;
    public static final int OFF_MAGIC = 0;
    public static final int OFF_VERSION = 4;
    public static final int OFF_BLOCK_SIZE = 8;
    public static final int OFF_FLAGS = 16;
    public static final int OFF_DIRTY_BITSET_BYTES = 24;
    public static final int OFF_USER_RESERVED = 32;

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
        tryRecoverFromFile();
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

    private void tryRecoverFromFile() throws IOException {
        long flen = deltaRaf.length();
        if (flen < HEADER_SIZE + blockSize) {
            initEmptyDeltaFile();
            return;
        }
        deltaRaf.seek(0);
        int magic = deltaRaf.readInt();
        if (magic != MAGIC) {
            initEmptyDeltaFile();
            return;
        }
        deltaRaf.seek(OFF_BLOCK_SIZE);
        int bs = deltaRaf.readInt();
        if (bs != blockSize) {
            initEmptyDeltaFile();
            return;
        }
        deltaRaf.seek(OFF_DIRTY_BITSET_BYTES);
        int dirtyBytesLen = deltaRaf.readInt();
        if (dirtyBytesLen <= 0 || dirtyBytesLen > blockSize) {
            initEmptyDeltaFile();
            return;
        }
        byte[] dirtyArr = new byte[dirtyBytesLen];
        deltaRaf.readFully(dirtyArr);
        BitSet loaded = BitSet.valueOf(dirtyArr);
        deltaRaf.seek(HEADER_SIZE);
        deltaRaf.readFully(cachedBlock);
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
    }

    private void initEmptyDeltaFile() throws IOException {
        deltaRaf.setLength(0);
        deltaRaf.writeInt(MAGIC);
        deltaRaf.writeInt(1);
        deltaRaf.writeLong(blockSize);
        deltaRaf.writeLong(0L);
        deltaRaf.writeLong(0L);
        deltaRaf.writeLong(0L);
        deltaRaf.write(new byte[HEADER_SIZE - (int) deltaRaf.getFilePointer()]);
        Arrays.fill(cachedBlock, (byte) 0);
        deltaRaf.write(cachedBlock);
        dirtyBytes.clear();
        anyDirty = false;
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
        deltaRaf.seek(0);
        deltaRaf.writeInt(MAGIC);
        deltaRaf.writeInt(1);
        deltaRaf.writeLong(blockSize);
        deltaRaf.writeLong(0L);
        deltaRaf.writeLong(0L);
        byte[] dirtyArr = dirtyBytes.toByteArray();
        deltaRaf.writeInt(dirtyArr.length);
        int pad1 = 8 - (dirtyArr.length % 8);
        if (pad1 != 8) deltaRaf.write(new byte[pad1]);
        deltaRaf.writeLong(0L);
        long written = deltaRaf.getFilePointer();
        long skip = HEADER_SIZE - written;
        if (skip > 0) deltaRaf.write(new byte[(int) skip]);
        deltaRaf.seek(HEADER_SIZE);
        deltaRaf.write(cachedBlock);
        try {
            if (deltaChannel != null) deltaChannel.force(true);
        } catch (IOException ignore) {
        }
    }
}
