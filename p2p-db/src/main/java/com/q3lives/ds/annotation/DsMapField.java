package com.q3lives.ds.annotation;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Map字段注解
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsMapField {
    
    Class<? extends DsTableAdapter> keyClass();
    Class<? extends DsTableAdapter> valueClass();
    
}
