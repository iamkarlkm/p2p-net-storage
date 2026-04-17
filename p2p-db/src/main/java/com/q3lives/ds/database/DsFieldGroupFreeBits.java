
package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsProp;

/**
 * 用于列组内空间回收。
 * @author Administrator
 */
public class DsFieldGroupFreeBits {
    
    public DsFieldGroupStruct group;
   
    @DsProp()
    public int groupIndex;//列组标识 column group index,如果为-1,表示独立列存储,非组合存储
    @DsProp()
    public int groupFreeOffset;//列组偏移-byte base
    @DsProp()
    public int bitCount;//二进制存储位数

    public DsFieldGroupStruct getGroup() {
        return group;
    }

    public void setGroup(DsFieldGroupStruct group) {
        this.group = group;
    }

    public int getGroupIndex() {
        return groupIndex;
    }

    public void setGroupIndex(int groupIndex) {
        this.groupIndex = groupIndex;
    }

    public int getGroupFreeOffset() {
        return groupFreeOffset;
    }

    public void setGroupFreeOffset(int groupFreeOffset) {
        this.groupFreeOffset = groupFreeOffset;
    }

    public int getBitCount() {
        return bitCount;
    }

    public void setBitCount(int bitCount) {
        this.bitCount = bitCount;
    }

   
    
}
