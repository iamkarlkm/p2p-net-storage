package com.q3lives.ds.index.record;

import com.q3lives.ds.util.DsDataUtil;

/**
 * 数据索引节点类。
 */
public class DsDataIndexNode {
    public short refCount; // 引用计数
    public short size;     // 大小
    public int offset;     // 偏移量
    public long hash;      // 哈希值

    public DsDataIndexNode(short refCount, short size, int offset, long hash) {
        this.refCount = refCount;
        this.size = size;
        this.offset = offset;
        this.hash = hash;
    }

    public DsDataIndexNode(short ref_count, short size, int offset, byte[] data) {
        this.refCount = ref_count;
        this.size = size;
        this.offset = offset;
        this.hash = DsDataUtil.hash64(data);
    }

    public DsDataIndexNode(short ref_count, short size, int offset, String str) {
        this.refCount = ref_count;
        this.size = size;
        this.offset = offset;
        this.hash = DsDataUtil.hash64(str);
    }

}
