
package com.q3lives.ds.index.record;


    /**
     * 数据块索引节点类。
     */
public class DsBlockIndexNode32 {
        public short ref_count; // 引用计数
        public short size;      // 大小
        public int offset;      // 偏移量

        public DsBlockIndexNode32(short ref_count, short size, int offset, long hash) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
        public DsBlockIndexNode32(short ref_count, short size, int offset, byte[] data) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
        public DsBlockIndexNode32(short ref_count, short size, int offset, String str) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
    }
