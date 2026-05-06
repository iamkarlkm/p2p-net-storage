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
}
