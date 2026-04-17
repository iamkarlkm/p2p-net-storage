/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsProp;

/**
 *
 * @author Administrator
 */
public class DsFieldStruct {
    
    public DsFieldGroupStruct group;
    
    @DsProp()
    public String name;
    @DsProp()
    public String comment;//注释
    
    @DsProp(dataType = String.class,getMethodName = "getName")
    public Class clazz;
    @DsProp()
    public int groupIndex;//列组标识 column group index,如果为-1,表示独立列存储,非组合存储
    @DsProp()
    public short groupOffset;//列组偏移-byte base
    @DsProp()
    public short bitCount;//二进制存储位数
    @DsProp()
    public int length;//以字节计数的限额长度。
    @DsProp()
    public short precision;
    @DsProp()
    public short scale;

    
}
