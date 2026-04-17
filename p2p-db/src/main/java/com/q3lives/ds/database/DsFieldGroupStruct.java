/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsProp;
import com.q3lives.ds.core.DsBytes;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Administrator
 */
public class DsFieldGroupStruct {
    
    public DsBytes store;
   
    @DsProp()
    public int groupIndex;//列组标识 column group index,如果为-1,表示独立列存储,非组合存储
    @DsProp()
    public int groupNextOffset;//剩余容量:列组偏移-byte base
    @DsProp()
    public int bitRestCount;//剩余容量:二进制存储位数
    
    @DsProp()
    private Map<Integer,DsFieldGroupFreeBits> frees = new HashMap();//可用bitCount -> DsFieldGroupFreeBits 用于列组内空间回收。

    public DsBytes getStore() {
        return store;
    }

    public int getGroupIndex() {
        return groupIndex;
    }

    public int getGroupNextOffset() {
        return groupNextOffset;
    }

    public int getBitRestCount() {
        return bitRestCount;
    }

    public Map<Integer, DsFieldGroupFreeBits> getFrees() {
        return frees;
    }

    public void setStore(DsBytes store) {
        this.store = store;
    }

    public void setGroupIndex(int groupIndex) {
        this.groupIndex = groupIndex;
    }

    public void setGroupNextOffset(int groupNextOffset) {
        this.groupNextOffset = groupNextOffset;
    }

    public void setBitRestCount(int bitRestCount) {
        this.bitRestCount = bitRestCount;
    }

   
    
}
