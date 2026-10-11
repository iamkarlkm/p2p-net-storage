package com.q3lives.ds.database.tx;

/**
 * 事务句柄：实现 {@link AutoCloseable}，用于 try-with-resources 未 commit 自动 rollback（AC-R12）。
 * <p>
 * 由 {@code DsDatabaseLocal#beginTransaction()} 系列方法创建，严禁业务层自行 new 实例。
 * close() 触发后：若未 commit/rollback → 自动 rollback；若已经 commit/rollback → close() 为 no-op。
 */
public interface Transaction extends AutoCloseable {

    /**
     * 提交事务：所有 PREPARING 阶段 op 的 CAS 必须全部成功，否则内部触发 rollback 并抛异常。
     *
     * @throws Exception 任何 2PC CAS 失败 / IO 异常 / 其他运行期异常（抛异常保证 rollback 已执行）
     */
    void commit() throws Exception;

    /**
     * 回滚事务：所有 PREPARING/COMMITTING 阶段写入的 op 按倒序还原到 prevBytes，保证 byte-for-byte 一致。
     *
     * @throws Exception 任何 IO 异常或 registry 回滚失败
     */
    void rollback() throws Exception;

    /**
     * try-with-resources 自动关闭：未 commit 自动 rollback；已 commit/rollback 为 no-op。
     *
     * @throws Exception 自动 rollback 过程中抛出的异常
     */
    @Override
    void close() throws Exception;
}
