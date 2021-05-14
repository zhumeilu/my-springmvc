package com.zml.annotation;

import java.lang.annotation.*;

/**
 * @author: maylor
 * @date: 2021/5/14 15:46
 * @description:
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyAutowired {
	String value() default ""; }
