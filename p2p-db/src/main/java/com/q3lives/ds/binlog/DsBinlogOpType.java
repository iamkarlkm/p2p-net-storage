package com.q3lives.ds.binlog;

/**
 * binlog 行操作类型（对齐 MySQL Write_rows/Update_rows/Delete_rows Event + 扩展）。
 */
public enum DsBinlogOpType {
    /** 保留占位，帧为无效/tombstone。 */
    NOOP,
    /** 插入一行。 */
    INSERT,
    /** 更新已有行。 */
    UPDATE,
    /** 删除行。 */
    DELETE,
    /** DDL/元数据变更（tableId 指定目标表）。 */
    DDL,
    /** 业务自定义事务边界 begin。 */
    XA_BEGIN,
    /** 业务自定义事务边界 commit。 */
    XA_COMMIT,
    /** 业务自定义事务边界 rollback。 */
    XA_ROLLBACK,
    /** 心跳/保活帧。 */
    HEARTBEAT
}
