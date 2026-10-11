package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把多个定长字段合并为一个固定长度列组（行存效果）。
 *
 * <p>被标记的字段必须同时声明 {@link DsField#length()}，且长度 > 0。
 * 列组总尺寸等于各成员长度之和；组内各列按 {@link #groupIndex()} 排序后计算偏移。</p>
 *
 * <p>列组内新增/删除/重排列，只要不超过组的总尺寸，不需要调整 groupIndex 或 schemaId；
 * 超过组尺寸的列不允许加入，应作为独立列或新列组存储。</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsFieldGroup {

    /**
     * 列组名。
     */
    String groupName();

    /**
     * 组内顺序，从 0 开始，升序排列。
     */
    int groupIndex() default 0;
}
