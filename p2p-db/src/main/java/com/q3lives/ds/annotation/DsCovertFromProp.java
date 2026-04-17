
package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *  DS数据库存储对象时调用指定方法，从对象属性值转换为数据库存储值。方法无参数,而且返回一个有效的数据库存储值。
 * @author Administrator
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsCovertFromProp {
    String methodName();
}
