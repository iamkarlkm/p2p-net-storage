
package com.q3lives.ds.index.record;


    /**
     * 数据块索引节点类。
     */
public class DsBlockIndexNode64 {
        public int ref_count; // 引用计数
        public int size;      // 大小
        public long offset;      // 偏移量

        public DsBlockIndexNode64(int ref_count, int size, long offset, long hash) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
        public DsBlockIndexNode64(int ref_count, int size, long offset, byte[] data) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
        public DsBlockIndexNode64(int ref_count, int size, long offset, String str) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
        }
        
    }
