
package com.q3lives.ds.fs;


@FunctionalInterface
public interface ObjectFilter<T> {

   
    boolean accept(T t);
}