/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.q3lives.ds.annotation.query;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *
 * @author karl
 */
@Target({java.lang.annotation.ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface DictionaryGenerate {
	public abstract String typeText();
	public String typeCode() default "";
	public int seq() default 0;
        public String dicTextNames() default "";
	public String[] dicTextArray() default {""};//数据字典项数组
	public String[] dicCodeArray() default {""};//数据字典项数组
        public String remark() default "";
}
