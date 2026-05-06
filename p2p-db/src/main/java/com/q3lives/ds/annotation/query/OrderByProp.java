
package com.q3lives.ds.annotation.query;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * vo list字段排序
 *
 * @author iamkarl@163.com
 * @since 2020-10-17
 */
@Target(FIELD)
@Retention(RUNTIME)
public @interface OrderByProp {

   /**
     * 排序的属性名
     * @return 
     */
    String value();
    /**
     * 默认正序
     * @return 
     */
    boolean asc() default true;
    
}
