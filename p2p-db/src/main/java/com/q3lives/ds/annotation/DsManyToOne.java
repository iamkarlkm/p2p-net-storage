package com.q3lives.ds.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * N:1 聚合端字段注解（P35 OQ1 ORM 多表 Join v2 DsManyToOne MVP）。
 *
 * <p>与 {@link DsOneToOne} 对偶：1 个主表行可关联 N 个从表行（聚合端）。
 * <ul>
 *   <li>{@link #joinProp()}: 该属性必须是 DsTableAdapter 类型；DsTableAdapter 的 joinProp
 *       属性必须是 64 位 Long（外键列，指向主表主键 id）</li>
 *   <li>{@link #props()}: 装载指定属性；不指定则全属性；与 @DsOneToOne 对齐</li>
 *   <li>{@link #fetchSize()}: IN 子查询 FetchSize，∈ [1, 4096]；业务方传固定值；默认 256
 *       （P32 模板：按 DsDatabaseLocal 单表 IN listIds 拆批上限对齐）</li>
 * </ul>
 *
 * <p>硬约束：仅追加新注解，{@link DsOneToOne} 字段 1 字不删改。
 *
 * @author SP3.1 Implement Agent
 * @since P35 OQ1 ORM Join v2 MVP
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsManyToOne {

    /** 必填：从表 joinProp 列名（外键 Long 列） */
    String joinProp();

    /** 装载指定属性，""=全属性（与 @DsOneToOne.props() 对齐） */
    String props() default "";

    /** IN 子查询 FetchSize，∈ [1, 4096]；默认 256 */
    int fetchSize() default 256;
}
