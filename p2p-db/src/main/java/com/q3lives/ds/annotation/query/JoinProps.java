
package com.q3lives.ds.annotation.query;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import java.lang.annotation.Repeatable;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * vo 一或多个属性自动装载
 *
 * @author iamkarl@163.com
 * @since 2020-10-17
 */
@Target(FIELD)
@Retention(RUNTIME)
@Repeatable(JoinPropsArray.class)
public @interface JoinProps {

     /**
     * 包含注解本身所属字段名，以及指定的一个或多个属性名的 entity class 
     * 
     * @return 
     */
    Class targetEntity();
    
    /**
     * 用于inner join操作的当前vo -> base class的属性名。
     * 默认取值注解本身所属字段名称
     * @return
     */
    String joinBy() default "";
    
    /**
     * 用于inner join操作的target entity class的属性名。
     * 默认标准ID字段
     * @return
     */
    String joinTo() default "id";
    /**
     * target entity class以逗号分隔的属性名列举
     * @return 
     */
    String props();
    
    /**
     * 自定义属性名自动装载
     * vo class以逗号分隔的属性名列举
     * 默认取值 props
     * @return 
     */
    String propsTo() default "";
}
