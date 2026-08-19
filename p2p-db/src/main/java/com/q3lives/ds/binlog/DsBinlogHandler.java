package com.q3lives.ds.binlog;

import java.io.IOException;

/**
 * 回放回调接口。
 * @see DsBinlogStore#replayAll(DsBinlogHandler)
 */
@FunctionalInterface
public interface DsBinlogHandler {

    /**
     * @param frameOffset 本帧在 fixed 文件中的绝对偏移。
     * @param entry       解析出的帧（含 colIds / packed slots / dyn frames）。
     * @return true 继续回放；false 停止。
     */
    boolean apply(long frameOffset, DsBinlogEntry entry) throws IOException;
}
