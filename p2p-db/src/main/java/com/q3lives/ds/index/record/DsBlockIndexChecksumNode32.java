
package com.q3lives.ds.index.record;

import com.q3lives.ds.util.DsDataUtil;


    /**
     * 数据块索引节点类。
     */
public class DsBlockIndexChecksumNode32 {
        public short ref_count; // 引用计数
        public short size;      // 大小
        public int offset;      // 偏移量
        public long hash;       // 哈希值,用于校验数据

        public DsBlockIndexChecksumNode32(short ref_count, short size, int offset, long hash) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = hash;
        }
        
        public DsBlockIndexChecksumNode32(short ref_count, short size, int offset, byte[] data) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(data);
        }
        
        public DsBlockIndexChecksumNode32(short ref_count, short size, int offset, String str) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(str);
        }
        
    }
