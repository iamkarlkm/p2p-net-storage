
package com.q3lives.ds.index.record;

import com.q3lives.ds.util.DsDataUtil;


    /**
     * 数据块索引节点类。
     */
public class DsBlockIndexChecksumNode64 {
        public int ref_count; // 引用计数
        public int size;      // 大小
        public long offset;      // 偏移量
        public long hash;       // 哈希值,用于校验数据

        public DsBlockIndexChecksumNode64(int ref_count, int size, long offset, long hash) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = hash;
        }
        
        public DsBlockIndexChecksumNode64(int ref_count, int size, long  offset, byte[] data) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(data);
        }
        
        public DsBlockIndexChecksumNode64(int ref_count, int size, long  offset, String str) {
            this.ref_count = ref_count;
            this.size = size;
            this.offset = offset;
            this.hash = DsDataUtil.hash64(str);
        }
        
    }
