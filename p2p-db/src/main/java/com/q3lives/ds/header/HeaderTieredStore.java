package com.q3lives.ds.header;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

public interface HeaderTieredStore extends Closeable {

    enum TierState {
        IDLE,
        ROLLOVER_PREP,
        MERGING,
        MERGE_SWAP_PENDING
    }

    void attachBase(ByteBuffer baseHeaderBlock) throws IOException;

    ByteBuffer getReadBuffer();

    ByteBuffer getWriteBufferForField(int offset, int len);

    void markFieldDirty(int offset, int len);

    void markFullDirty();

    boolean isDirty();

    void flush() throws IOException;

    TierState getState();

    default void rollover(String dayKey) throws IOException {
    }

    default boolean mergeRunnable() throws IOException {
        return true;
    }

    default String debugName() {
        return getClass().getSimpleName();
    }
}
