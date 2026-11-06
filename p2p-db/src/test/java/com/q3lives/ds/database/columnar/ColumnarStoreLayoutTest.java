package com.q3lives.ds.database.columnar;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.database.DsTableLayout;
import com.q3lives.ds.database.DsValueResolver;
import com.q3lives.ds.database.adapter.DsTableAdapter;

public class ColumnarStoreLayoutTest {

    @Test
    public void testDefaultTableLayout() {
        DsTableLayout layout = new DsTableLayout(DefaultRow.class);
        Assertions.assertFalse(layout.isDistributed());
        Assertions.assertFalse(layout.isPartitioned());
        Assertions.assertEquals(1, layout.getPartitionCount());
        Assertions.assertEquals(1, layout.getReplicaCount());
        Assertions.assertEquals(0, layout.getDirectories().length);
        Assertions.assertTrue(layout.getFieldGroups().isEmpty());
    }

    @Test
    public void testDistributedPartitionedLayout() {
        DsTableLayout layout = new DsTableLayout(DistributedRow.class);
        Assertions.assertTrue(layout.isDistributed());
        Assertions.assertTrue(layout.isPartitioned());
        Assertions.assertEquals(256, layout.getPartitionCount());
        Assertions.assertEquals(3, layout.getReplicaCount());
        Assertions.assertArrayEquals(new String[]{"/data1", "/data2"}, layout.getDirectories());
    }

    @Test
    public void testPartitionDirectories() throws Exception {
        DsTableLayout layout = new DsTableLayout(DistributedRow.class);
        File nodeHome = Files.createTempDirectory("dsdb-node").toFile();
        List<File> dirs = layout.getPartitionDirectories(nodeHome);
        Assertions.assertEquals(256, dirs.size());
        // 绝对路径直接使用；相对路径解析到 nodeHome 下
        Assertions.assertTrue(dirs.get(0).getAbsolutePath().endsWith("data1"));
        Assertions.assertTrue(dirs.get(1).getAbsolutePath().endsWith("data2"));
        Assertions.assertTrue(dirs.get(2).getAbsolutePath().endsWith("data1"));
    }

    @Test
    public void testColumnStorageTypes() {
        DsTableLayout layout = new DsTableLayout(TypedRow.class);

        DsTableLayout.ColumnLayout inline = layout.getColumn("flags");
        Assertions.assertNotNull(inline);
        Assertions.assertEquals(8, inline.getLength());
        Assertions.assertEquals(DsValueResolver.StorageType.INLINE, inline.getStorageType());
        Assertions.assertFalse(inline.isInGroup());

        DsTableLayout.ColumnLayout fixed = layout.getColumn("username");
        Assertions.assertNotNull(fixed);
        Assertions.assertEquals(16, fixed.getLength());
        Assertions.assertEquals(DsValueResolver.StorageType.FIXED_VALUE_STORE, fixed.getStorageType());

        DsTableLayout.ColumnLayout variable = layout.getColumn("bio");
        Assertions.assertNotNull(variable);
        Assertions.assertEquals(0, variable.getLength());
        Assertions.assertEquals(DsValueResolver.StorageType.VARIABLE_BUCKET, variable.getStorageType());
    }

    @Test
    public void testFieldGroupLayout() {
        DsTableLayout layout = new DsTableLayout(TypedRow.class);

        DsTableLayout.FieldGroupDef group = layout.getFieldGroup("PROFILE");
        Assertions.assertNotNull(group);
        Assertions.assertEquals(12, group.getTotalSize());
        Assertions.assertEquals(3, group.getMembers().size());

        DsTableLayout.FieldGroupDef.Member first = group.getMembers().get(0);
        Assertions.assertEquals("firstName", first.getLogicalName());
        Assertions.assertEquals(0, first.getOffset());
        Assertions.assertEquals(4, first.getLength());

        DsTableLayout.FieldGroupDef.Member second = group.getMembers().get(1);
        Assertions.assertEquals("ageAlias", second.getLogicalName());
        Assertions.assertEquals(4, second.getOffset());
        Assertions.assertEquals(4, second.getLength());

        DsTableLayout.FieldGroupDef.Member third = group.getMembers().get(2);
        Assertions.assertEquals("code", third.getLogicalName());
        Assertions.assertEquals(8, third.getOffset());
        Assertions.assertEquals(4, third.getLength());

        DsTableLayout.ColumnLayout firstCol = layout.getColumn("firstName");
        Assertions.assertNotNull(firstCol);
        Assertions.assertTrue(firstCol.isInGroup());
        Assertions.assertEquals("PROFILE", firstCol.getGroupName());
        Assertions.assertEquals(0, firstCol.getGroupOffset());
        Assertions.assertEquals(12, firstCol.getGroupSize());
    }

    @Test
    public void testGroupMemberMustBeFixedLength() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new DsTableLayout(BadGroupRow.class));
    }

    public static class DefaultRow extends DsTableAdapter {
        @DsField(name = "id", length = 8)
        private Long id;
    }

    @DsTable(distributed = true, partitioned = true, partitions = 256, replicas = 3, directories = {"/data1", "/data2"})
    public static class DistributedRow extends DsTableAdapter {
        @DsField(name = "id", length = 8)
        private Long id;
    }

    public static class TypedRow extends DsTableAdapter {
        @DsField(name = "flags", length = 8)
        private Long flags;

        @DsField(name = "username", length = 16)
        private String username;

        @DsField(name = "bio", length = 0)
        private String bio;

        @DsField(name = "firstName", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 0)
        private String firstName;

        @DsField(name = "ageAlias", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 1)
        private Integer ageAlias;

        @DsField(name = "code", length = 4)
        @DsFieldGroup(groupName = "PROFILE", groupIndex = 2)
        private String code;
    }

    public static class BadGroupRow extends DsTableAdapter {
        @DsField(name = "text", length = 0)
        @DsFieldGroup(groupName = "BAD", groupIndex = 0)
        private String text;
    }
}
