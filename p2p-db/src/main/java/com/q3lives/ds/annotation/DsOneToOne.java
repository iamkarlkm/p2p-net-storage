package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一对一字段注解
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsOneToOne {
    
   
    String joinProp();//该属性必须是DsTableAdapter类型,DsTableAdapter的joinProp属性必须是64位Long类型

   String props() default "";//只装载指定属性,不指定则全部属性
    
    
}
