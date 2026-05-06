
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
@Repeatable(UnionJoinPropsArray.class)
public @interface UnionJoinProps {

     /**
     * 包含注解本身所属字段名，以及指定的一个或多个属性名的 entity class 
     * 
     * @return 
     */
    Class targetEntity();
    
    /**
     * 用于inner join操作的当前vo -> base class的以逗号分隔的属性名列举,至少两个以上。
     * @return
     */
    String joinBy();
    
    /**
     * joinBy列举属性固定值(name必须在joinBy存在),json格式
     * @return
     */
    String joinByFixedValues() default "";
    
    /**
     * 用于inner join操作的target entity class的以逗号分隔的属性名列举,至少两个以上,必须与joinBy列举属性一一对应。
     * @return
     */
    String joinTo();
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
    
    /**
     * 以逗号分隔的,基于属性列举的排序字符串,例如 p1 asc,p2 desc...
     * @return 
     */
    String orders() default "";
}
