package com.q3lives.ds.header;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;

public class MemDeltaHeaderTierStore implements HeaderTieredStore {

    private final String name;
    private ByteBuffer base;
    private ByteBuffer todayDelta;
    private final BitSet dirtyBytes;
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
        state = TierState.IDLE;
    }

    @Override
    public synchronized ByteBuffer getReadBuffer() {
        if (base == null) throw new IllegalStateException("attachBase not called");
        return base;
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
        for (int i = offset; i < end; i++) {
            dirtyBytes.set(i);
            todayDelta.put(i, base.get(i));
        }
        anyDirty = true;
    }

    @Override
    public synchronized void markFullDirty() {
        if (base == null) return;
        for (int i = 0; i < blockSize; i++) {
            if (!dirtyBytes.get(i)) {
                dirtyBytes.set(i);
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
        clearToday();
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
        flush();
        state = TierState.IDLE;
    }

    @Override
    public synchronized void close() throws IOException {
        flush();
        base = null;
    }

    @Override
    public String debugName() {
        return "MemDeltaHeaderTierStore[" + name + "]";
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
