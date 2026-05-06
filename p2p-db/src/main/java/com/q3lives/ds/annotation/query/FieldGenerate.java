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
@Target({java.lang.annotation.ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface FieldGenerate {

    public abstract String value();

    public String columnName() default "";

    public String description() default "";

    public String validType() default "";

    public boolean isDataChecking() default false;

    public boolean isAutoI18n() default false;

    public boolean isRequired() default false;

    public boolean isSearched() default false;//字段是否可搜索

    public boolean isSearchWithRange() default false;//是否启用范围搜索

    public boolean isHidden() default false;

    public boolean isUnique() default false;

    public boolean isIcon() default false;

    public boolean isMedia() default false;

    public boolean isTree() default false;

    public boolean isCombobox() default false;

    public boolean isForeignField() default false;

    public boolean isComboGrid() default false;

    public boolean isComboGridSearch() default false;

    public boolean isOneToOne() default false;

    public boolean isOneToMany() default false;

    public boolean isManyToOne() default false;

    public boolean isManyToMany() default false;

    public boolean isRelationId() default false;//是否中间关系表的主键ID

    /**
     * 启用中间关系表的搜索(系统将会自动生成joinPropertyName和当前字段名的中间关系表类)
     * 当前字段为虚拟字段不对应生成entity字段
     *
     * @return
     */
    public boolean enableRelationSearch() default false;

    public String joinTableName() default "";//关联实体类属性名称

    public boolean isInverseMany() default false;

    public String manyOrderBy() default "";//一对多，多对多，排序

    public boolean isEditable() default true;

    public String comboIdField() default "id";//comboGrid id

    public String comboTextField() default "name";//comboGrid text

    public String comboGridFields() default "";//comboGrid 显示数据列

    public boolean isFileManager() default false;

    public boolean isDictionary() default false;

    public String dictionaryType() default "";//数据字典项类型

    public String[] dicTextArray() default {""};//数据字典项数组

    public String defaultValue() default "";//字段默认值

    public short precision() default 0;

    public short length() default 0;

    public int seq() default 0;

    public int viewFieldWidth() default 120;//视图字段宽度

    public String dynamicDataUrl() default "";//动态数据Url

    public String foreignClass() default "";//tree,combobox等

    /**
     * 定义外键关联页面model字段 外键实例对象属性名：页面model映射属性名，以逗号分隔 -> "a:b,x1:x2"
     *
     * @return
     */
    public String foreignFields() default "";

    public String mappedBy() default "";//外部关联实体字段等

    public String joinPropertyName() default "";//关联实体类属性名称

    public String align() default "center";//关联实体类属性名称

    public boolean isImport() default false;//是否可导入导出

}
