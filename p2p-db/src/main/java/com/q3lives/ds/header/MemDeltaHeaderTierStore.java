package com.q3lives.ds.header;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

public class MemDeltaHeaderTierStore implements HeaderTieredStore {

    private final String name;
    private ByteBuffer base;
    private ByteBuffer todayDelta;
    private ByteBuffer mergingDelta;
    private final BitSet dirtyBytes;
    private BitSet mergingDirtyBytes;
    private boolean anyDirty;
    private volatile TierState state = TierState.IDLE;
    private final int blockSize;

    public MemDeltaHeaderTierStore(String name) {
        this(name, 64 * 1024);
    }

    public MemDeltaHeaderTierStore(String name, int blockSize) {
        if (blockSize <= 0) throw new IllegalArgumentException("blockSize");
        this.name = name;
        this.blockSize = blockSize;
        this.dirtyBytes = new BitSet(blockSize);
        this.todayDelta = ByteBuffer.allocate(blockSize);
    }

    @Override
    public synchronized void attachBase(ByteBuffer baseHeaderBlock) throws IOException {
        if (baseHeaderBlock == null) throw new NullPointerException("baseHeaderBlock");
        this.base = baseHeaderBlock;
        int cap = baseHeaderBlock.capacity();
        if (cap != blockSize) {
            throw new IOException("header blockSize mismatch: expected=" + blockSize + " actual=" + cap);
        }
        dirtyBytes.clear();
        anyDirty = false;
        byte[] arr = todayDelta.hasArray() ? todayDelta.array() : null;
        if (arr != null) Arrays.fill(arr, (byte) 0);
        else {
            todayDelta.clear();
            for (int i = 0; i < blockSize; i++) todayDelta.put((byte) 0);
            todayDelta.clear();
        }
        mergingDelta = null;
        mergingDirtyBytes = null;
        state = TierState.IDLE;
        try { DailyMergeService.getInstance().register(this); } catch (Throwable ignore) {}
    }

    @Override
    public synchronized ByteBuffer getReadBuffer() {
        if (base == null) throw new IllegalStateException("attachBase not called");
        if (state != TierState.IDLE) {
            applyReadOverlayIfNeeded();
        }
        return base;
    }

    private void applyReadOverlayIfNeeded() {
        if (base == null) return;
        if (state == TierState.MERGING || state == TierState.ROLLOVER_PREP || state == TierState.MERGE_SWAP_PENDING) {
            if (mergingDelta != null && mergingDirtyBytes != null && !mergingDirtyBytes.isEmpty()) {
                overlaySkipTodayDirty(mergingDelta, mergingDirtyBytes, dirtyBytes);
            }
        }
    }

    private void overlay(ByteBuffer src, BitSet mask) {
        if (src == null || mask == null || mask.isEmpty()) return;
        int next = -1;
        while ((next = mask.nextSetBit(next + 1)) >= 0) {
            int end = mask.nextClearBit(next);
            for (int i = next; i < end; i++) {
                byte v = src.get(i);
                base.put(i, v);
            }
            next = end - 1;
            if (next >= blockSize - 1) break;
        }
    }

    private void overlaySkipTodayDirty(ByteBuffer src, BitSet mask, BitSet todayCleanSkip) {
        if (src == null || mask == null || mask.isEmpty() || base == null) return;
        int next = -1;
        while ((next = mask.nextSetBit(next + 1)) >= 0) {
            int end = mask.nextClearBit(next);
            for (int i = next; i < end; i++) {
                if (todayCleanSkip != null && todayCleanSkip.get(i)) continue;
                byte v = src.get(i);
                base.put(i, v);
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
            ByteBuffer dst = todayDelta.duplicate();
            dst.position(offset);
            dst.limit(end);
            dst.put(src);
        } catch (Throwable ignore) {
            for (int i = offset; i < end; i++) {
                todayDelta.put(i, base.get(i));
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
            ByteBuffer dst = todayDelta.duplicate();
            dst.position(0);
            dst.limit(blockSize);
            dst.put(src);
        } catch (Throwable ignore) {
            for (int i = 0; i < blockSize; i++) {
                todayDelta.put(i, base.get(i));
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
        if (state == TierState.IDLE) {
            clearToday();
        }
    }

    @Override
    public TierState getState() {
        return state;
    }

    @Override
    public synchronized void rollover(String dayKey) throws IOException {
        if (state != TierState.IDLE) {
            throw new IOException("rollover not allowed in state: " + state);
        }
        state = TierState.ROLLOVER_PREP;
        mergingDelta = todayDelta;
        mergingDirtyBytes = dirtyBytes;
        todayDelta = ByteBuffer.allocate(blockSize);
        dirtyBytes.clear();
        anyDirty = false;
        state = TierState.MERGING;
    }

    @Override
    public synchronized boolean prepareMerge() throws IOException {
        if (state == TierState.IDLE) {
            if (!anyDirty) {
                state = TierState.MERGE_SWAP_PENDING;
                return false;
            }
            state = TierState.MERGING;
            mergingDelta = snapshotDeltaForMerge();
            mergingDirtyBytes = dirtyBitSetSnapshot();
            clearToday();
        }
        return true;
    }

    @Override
    public synchronized boolean applyMergedToBase() throws IOException {
        if (state != TierState.MERGING && state != TierState.MERGE_SWAP_PENDING) return false;
        try {
            if (mergingDelta != null && mergingDirtyBytes != null && base != null) {
                overlaySkipTodayDirty(mergingDelta, mergingDirtyBytes, dirtyBytes);
            }
            return true;
        } finally {
            mergingDelta = null;
            mergingDirtyBytes = null;
            state = TierState.IDLE;
        }
    }

    @Override
    public synchronized void cancelMerge() throws IOException {
        if (mergingDelta != null) {
            mergingDelta = null;
        }
        mergingDirtyBytes = null;
        state = TierState.IDLE;
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            try { DailyMergeService.getInstance().unregister(this); } catch (Throwable ignore) {}
            flush();
        } finally {
            base = null;
            dirtyBytes.clear();
            anyDirty = false;
            byte[] arr = todayDelta.hasArray() ? todayDelta.array() : null;
            if (arr != null) Arrays.fill(arr, (byte) 0);
            mergingDelta = null;
            mergingDirtyBytes = null;
            state = TierState.IDLE;
        }
    }

    @Override
    public String debugName() {
        return "MemDeltaHeaderTierStore[" + name + ", state=" + state + "]";
    }

    public synchronized ByteBuffer snapshotDeltaForMerge() {
        if (!anyDirty) return null;
        byte[] out = new byte[blockSize];
        Arrays.fill(out, (byte) 0);
        int next = -1;
        while ((next = dirtyBytes.nextSetBit(next + 1)) >= 0) {
            int end = dirtyBytes.nextClearBit(next);
            todayDelta.position(next);
            todayDelta.get(out, next, end - next);
            next = end - 1;
            if (next >= blockSize - 1) break;
        }
        return ByteBuffer.wrap(out);
    }

    public synchronized BitSet dirtyBitSetSnapshot() {
        return (BitSet) dirtyBytes.clone();
    }

    private void clearToday() {
        anyDirty = false;
        dirtyBytes.clear();
        byte[] arr = todayDelta.hasArray() ? todayDelta.array() : null;
        if (arr != null) Arrays.fill(arr, (byte) 0);
    }
}
