package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段注解 - 扩展版本
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsField {
    
    /**
     * 字段名称
     */
    String name() default "";
    
    /**
     * 字段长度（字节）
     */
    int length() default 0;
    
    /**
     * 精度（用于BigDecimal）
     */
    int precision() default 18;
    
    /**
     * 小数位数（用于BigDecimal）
     */
    int scale() default 2;
    
    /**
     * 是否可为空
     */
    boolean nullable() default true;
    
    /**
     * 最小值（用于数值类型）
     */
    long min() default Long.MIN_VALUE;
    
    /**
     * 最大值（用于数值类型）
     */
    long max() default Long.MAX_VALUE;
    
    /**
     * 默认值
     */
    String defaultValue() default "";
    
    /**
     * 字段描述
     */
    String description() default "";

    /**
     * 是否为该列自动建立等值索引（@deprecated 实验性，默认 false；启用后 DsDatabaseLocal.putEntity/removeTable
     * 会在 rowId 写入/删除时同步维护 indexes 下的 eq 索引，详见 DsEqIndexStore）。
     * backward compatible：新增字段默认值 false 不改变任何既有行为，现有 64 个子类无需改动。
     */
    boolean indexed() default false;
}
