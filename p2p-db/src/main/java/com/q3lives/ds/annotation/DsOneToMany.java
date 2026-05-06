package com.q3lives.ds.annotation;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一对多字段注解-List,Set
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsOneToMany {
    
    Class<? extends DsTableAdapter> joinClass();
    String joinProp();//joinClass的joinProp属性属性必须是64位Long类型

   String props() default "";//只装载指定属性,不指定则全部属性
    
    
}
