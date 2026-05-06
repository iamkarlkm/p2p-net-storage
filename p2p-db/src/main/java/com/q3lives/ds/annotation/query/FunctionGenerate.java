/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.q3lives.ds.annotation.query;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *
 * @author karl
 */
@Target({java.lang.annotation.ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FunctionGenerate {

    public String tableName();

    public abstract String value();

    public String description() default "";

    public String relationClass() default "";

    public String relationPropertyName() default "";//关联属性名

    public short relationType() default 2;//默认明细一对多关联

    public String iconCls() default "";

    public boolean isImport() default false;//是否可导入导出

    public boolean isSystem() default false;//是否框架公共模块

    public boolean isTree() default false;//是否标准Tree结构类

    public boolean isRelation() default false;//是否中间关系表，联合主键结构

    /**
     * 附加 createBy,createDate,updateBy,updateDate等审计信息字段
     *
     * @return
     */
    public boolean isAudit() default false;

    /**
     * 附加 version 版本字段 及 createBy,createDate,updateBy,updateDate等审计信息字段
     *
     * @return
     */
    public boolean isVersionAudit() default false;

    /**
     * 附加 version 版本字段
     *
     * @return
     */
    public boolean isVersion() default false;

    public String treeTextField() default "";

    public String datagridMergeCells() default "";
    
    public String buttons() default "";

}
