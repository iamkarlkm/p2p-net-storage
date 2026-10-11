package com.q3lives.ds.database.columnar;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.adapter.DsTableAdapter;

/**
 * 分布式路由测试用实体类。
 *
 * <p>放置在 main 源码目录，确保服务端 handler 可以通过 Class.forName 加载。</p>
 */
@DsTable(partitioned = true, partitions = 256)
public class DsDistributedTestRow extends DsTableAdapter {

    @DsField(name = "flags", length = 8)
    private Long flags;

    @DsField(name = "username", length = 16)
    private String username;

    @DsField(name = "bio", length = 0)
    private String bio;

    @DsField(name = "firstName", length = 4)
    @DsFieldGroup(groupName = "PROFILE", groupIndex = 0)
    private String firstName;

    @DsField(name = "age", length = 4)
    @DsFieldGroup(groupName = "PROFILE", groupIndex = 1)
    private Integer age;

    @DsField(name = "code", length = 4)
    @DsFieldGroup(groupName = "PROFILE", groupIndex = 2)
    private String code;
}
