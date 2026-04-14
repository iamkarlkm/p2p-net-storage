package com.q3lives.ds.core;

import java.io.IOException;

/**
 * 表示在读取 block 时发生不可恢复的错误。
 *
 * <p>常见触发：</p>
 * <ul>
 *   <li>文件截断或损坏，导致无法读到完整的 block。</li>
 *   <li>校验失败（如果调用方在更高层做了 checksum 校验）。</li>
 * </ul>
 *
 * <p>offset 用于定位错误发生在底层文件的哪个偏移。</p>
 */
public class UnreadableBlockException extends IOException {
    private final long offset;

    /**
     * 创建一个不可读 block 异常。
     *
     * @param message 异常信息
     * @param offset 底层文件偏移（用于定位损坏位置）
     */
    public UnreadableBlockException(String message, long offset) {
        super(message);
        this.offset = offset;
    }

    /**
     * 返回错误发生的底层文件偏移。
     */
    public long getOffset() {
        return offset;
    }
}
