package com.q3lives.ds.database.tx;

/**
 * 事务隔离级枚举。
 * <p>
 * MVP 仅实现 {@link #REPEATABLE_READ}（可重复读 RR，默认），其余 3 级在
 * {@code DsTransactionManager#begin(IsolationLevel)} 一律显式抛
 * {@link UnsupportedOperationException}（message 含 "MVP only RR"），
 * 禁止静默降级（P14 AC-R11 钉死）。
 */
public enum IsolationLevel {

    /** 读未提交（MVP 不支持，抛 UOE） */
    READ_UNCOMMITTED,

    /** 读已提交（MVP 不支持，抛 UOE） */
    READ_COMMITTED,

    /** 可重复读（RR，MVP 默认且唯一支持的隔离级） */
    REPEATABLE_READ,

    /** 快照（MVP 不支持，抛 UOE） */
    SNAPSHOT
}
