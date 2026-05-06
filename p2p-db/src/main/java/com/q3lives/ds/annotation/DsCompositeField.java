
package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
     * 复合列存储 (Fixed-length Composite Columns-Oriented Storage / DSM - Decomposition Storage Model)
     * 
     * 特点：
     * - 将多个定长列的数据对齐组合存储
     * - 适合OLAP场景，分析查询
     * - 列级聚合、扫描效率高
     * - 压缩率高（同类型数据）
     * - 结合行存储和列存储优点
     * - 缓存友好，减少CPU cache miss
     * - 适合混合负载
     * 
     * 存储示例（每个页内）：-> 应用编译器对齐技术。
     * Page 1: [id列: 1,2,3] [name列: "A","B","C"] [age列: 25,30,35]
     * Page 2: [id列: 4,5,6] [name列: "D","E","F"] [age列: 40,45,50]
     */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsCompositeField {
    String name() default "";
    String group() default "STD";
    int length() default 128;//byte-based group columns/fields,store bytes -> 合并存储为固定长度的复合数据,八字节对齐。
    int startBits();//起始2进制位索引
    int endBits();//结束2进制位索引
}
