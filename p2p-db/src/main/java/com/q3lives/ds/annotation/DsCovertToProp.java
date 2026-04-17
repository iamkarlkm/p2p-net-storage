
package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *  DS数据库装载对象时调用指定方法，从数据库存储值转换为对象属性值。方法只能有一个数据库存储值参数,而且无返回->void。方法负责更新对象属性值。
 * @author Administrator
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsCovertToProp {
    String methodName();
}
