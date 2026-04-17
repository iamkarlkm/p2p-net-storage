
package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsProp;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Administrator
 */
public class DsTableStruct {
    
    @DsProp(dataType = String.class,getMethodName = "getName")
    public Class clazz;//bucket存储空间就是clazz.getName();包名解析为路径
//    @DsProp()
//    public String name;  表名就是clazz.getSimpleName();
    @DsProp()
    public String comment;//注释
    @DsProp()
    private int nextGroupIndex = 1;
    @DsProp()
    private Map<String,DsFieldStruct> fields = new HashMap();
    //冗余数据。初始化是从列组对象提取
    private Map<Integer,DsFieldGroupFreeBits> frees = new HashMap();//可用bitCount -> DsFieldGroupFreeBits 用于列组内空间回收。

    public int getNextGroupIndex() {
        return nextGroupIndex;
    }

    public Map<String, DsFieldStruct> getFields() {
        return fields;
    }

    public Class getClazz() {
        return clazz;
    }

    public void setClazz(Class clazz) {
        this.clazz = clazz;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Map<Integer, DsFieldGroupFreeBits> getFrees() {
        return frees;
    }
    
}
