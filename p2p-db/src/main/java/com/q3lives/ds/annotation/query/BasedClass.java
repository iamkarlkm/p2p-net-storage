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
 * 页面模型vo 基础entity class定义（每个vo必须注解）
 *
 * @author iamkarl@163.com
 * @since 2020-10-15
 */
@Target({java.lang.annotation.ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface BasedClass {

    Class value();
}
