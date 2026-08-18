package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 复合索引声明（类级注解）。
 *
 * <p>声明形如 (a,b,c) 的复合索引，查询时支持最左前缀匹配：</p>
 * <ul>
 *   <li>a = ?</li>
 *   <li>a = ? AND b = ?</li>
 *   <li>a = ? AND b = ? AND c = ?</li>
 * </ul>
 *
 * <p>支持重复标注以声明多个复合索引：</p>
 * <pre>
 * &#64;DsCompositeIndex(name = "idx_ab", columns = {"a", "b"})
 * &#64;DsCompositeIndex(name = "idx_ac", columns = {"a", "c"})
 * public class MyEntity extends DsTableAdapter { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(DsCompositeIndex.List.class)
public @interface DsCompositeIndex {

    /**
     * 复合索引名（仅用于磁盘文件命名，不暴露为 SQL 名）。
     */
    String name();

    /**
     * 组成复合索引的列名，按最左前缀顺序填写。
     */
    String[] columns();

    /**
     * 多个复合索引的容器注解。
     */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        DsCompositeIndex[] value();
    }
}
