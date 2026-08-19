package com.q3lives.ds.binlog;

/**
 * 回放/写入线程上下文。
 * <p>{@link #setInReplay(boolean)} 设置为 true 后，当前线程对
 * {@link DsBinlogStore#append} 的任何调用都会直接返回 -1（不写入），避免
 * 从 binlog 回放 apply → 业务层又 append → binlog 无限膨胀的死循环。
 */
public final class DsBinlogContext {

    private static final ThreadLocal<Boolean> IN_REPLAY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private DsBinlogContext() {
    }

    public static boolean isInReplay() {
        return Boolean.TRUE.equals(IN_REPLAY.get());
    }

    public static void setInReplay(boolean value) {
        IN_REPLAY.set(value);
    }

    public static void clear() {
        IN_REPLAY.remove();
    }
}
