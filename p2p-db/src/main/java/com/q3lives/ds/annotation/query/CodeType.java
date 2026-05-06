
package com.q3lives.ds.annotation.query;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 数据字典编码自动装载
 *
 * @author iamkarl@163.com
 * @since 2020-10-17
 */
@Target(FIELD)
@Retention(RUNTIME)
public @interface CodeType {

    /**
     * 数据字典类别代码
     * @return
     */
    String typeCode();
    
        
    /**
     * 自动装载到 vo class属性名
     * @return 
     */
    String toProp();
}
