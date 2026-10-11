
package com.q3lives.ds.annotation;

import com.q3lives.ds.binlog.DsBinlogLogLevel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DsTable {

    String name() default "";

    /**
     * 是否启用 MVCC（多版本并发控制）。默认 false。
     * 启用后该表的每次写入会生成版本链，查询可指定 readTimestamp 进行快照读。
     */
    boolean mvcc() default false;

    /**
     * binlog 日志级别：控制该表写入 binlog 时记录新值、旧值还是两者。
     * 默认 {@link DsBinlogLogLevel#REDO}（只记录新值）。
     */
    DsBinlogLogLevel binlogLogLevel() default DsBinlogLogLevel.REDO;

    /**
     * 是否跨节点分布存储。默认 false。
     */
    boolean distributed() default false;

    /**
     * 是否在本地使用分区目录。默认 false。
     */
    boolean partitioned() default false;

    /**
     * 本地分区数，默认 256。仅在 {@link #partitioned()} 为 true 时生效。
     */
    int partitions() default 256;

    /**
     * 多目录绑定，空则全部落在 nodeHome。可用于把 256 个分区目录分散到多块硬盘。
     */
    String[] directories() default {};

    /**
     * 副本数，仅 {@link #distributed()} 为 true 时生效。每个 serverId 对应 R 个副本节点。
     */
    int replicas() default 1;
}
