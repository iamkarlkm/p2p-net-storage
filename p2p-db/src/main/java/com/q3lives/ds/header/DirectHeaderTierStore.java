package com.q3lives.ds.header;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DirectHeaderTierStore implements HeaderTieredStore {

    private final String name;
    private ByteBuffer headerBlock;
    private boolean dirtyFlag;

    public DirectHeaderTierStore(String name) {
        this.name = name;
    }

    @Override
    public void attachBase(ByteBuffer baseHeaderBlock) throws IOException {
        this.headerBlock = baseHeaderBlock;
        this.dirtyFlag = false;
    }

    @Override
    public ByteBuffer getReadBuffer() {
        return headerBlock;
    }

    @Override
    public ByteBuffer getWriteBufferForField(int offset, int len) {
        return headerBlock;
    }

    @Override
    public void markFieldDirty(int offset, int len) {
        this.dirtyFlag = true;
    }

    @Override
    public void markFullDirty() {
        this.dirtyFlag = true;
    }

    @Override
    public boolean isDirty() {
        return dirtyFlag;
    }

    @Override
    public void flush() throws IOException {
        dirtyFlag = false;
    }

    @Override
    public TierState getState() {
        return TierState.IDLE;
    }

    @Override
    public void close() throws IOException {
        headerBlock = null;
    }

    @Override
    public String debugName() {
        return "DirectHeaderTierStore[" + name + "]";
    }
}
