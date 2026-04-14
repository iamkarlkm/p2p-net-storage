package com.q3lives.ds.index.record;

/**
 * 索引包装器。
 * <p>
 * 用于在闭包或匿名内部类中传递和修改整型变量。
 * 类似于 `AtomicInteger`，但这里主要用于单线程或已加锁的上下文中，避免了额外的开销。
 * </p>
 */
public class DsIndexWrapper{
    public int value = 0;
}
