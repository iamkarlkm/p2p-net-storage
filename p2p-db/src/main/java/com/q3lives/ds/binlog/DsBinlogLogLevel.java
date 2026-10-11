package com.q3lives.ds.binlog;

/**
 * binlog 日志级别：控制数据变更写入 binlog 时记录的是新值、旧值还是两者。
 *
 * <ul>
 *   <li>{@link #REDO}：只记录新值（after image），回放时直接应用。默认级别。</li>
 *   <li>{@link #UNDO}：只记录旧值（before image），帧带 {@link DsBinlogLayout#FLAG_BIT_UNDO_LOG} 标志；
 *       回放时默认跳过，仅用于手动回滚或审计。</li>
 *   <li>{@link #FULL}：同时记录旧值和新值：先写一帧旧值（UNDO），再写一帧新值（REDO）。</li>
 * </ul>
 */
public enum DsBinlogLogLevel {
    REDO,
    UNDO,
    FULL
}
