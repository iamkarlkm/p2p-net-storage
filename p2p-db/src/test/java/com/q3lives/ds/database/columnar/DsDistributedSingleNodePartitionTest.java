package com.q3lives.ds.database.columnar;

import java.io.File;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.database.DsTableLayout;
import com.q3lives.ds.database.adapter.DsTableAdapter;

public class DsDistributedSingleNodePartitionTest {

    @Test
    public void test256PartitionsWriteRead() throws Exception {
        File nodeHome = Files.createTempDirectory("dsdb-partition").toFile();
        try (DsPartitionManager manager = new DsPartitionManager(
                nodeHome, new DsTableLayout(PartitionedRow.class))) {

            int[] partitionIds = {0, 1, 127, 128, 255};
            for (int pid : partitionIds) {
                long rowId = DsIdLayout.compose(0, pid, pid + 1L);
                ColumnarStore store = manager.getStore(rowId);

                byte[] inline = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
                store.putValue(PartitionedRow.class, "flags", rowId, inline);

                byte[] fixed = "sixteen chars!!!".getBytes();
                store.putValue(PartitionedRow.class, "username", rowId, fixed);

                byte[] variable = "hello partition".getBytes();
                store.putValue(PartitionedRow.class, "bio", rowId, variable);

                byte[] group = new byte[12];
                System.arraycopy("abcd".getBytes(), 0, group, 0, 4);
                System.arraycopy(new byte[] {0, 0, 0, 25}, 0, group, 4, 4);
                System.arraycopy(new byte[] {0, 0, 0, 7}, 0, group, 8, 4);
                store.putFieldGroup(PartitionedRow.class, "PROFILE", rowId, group);
            }

            for (int pid : partitionIds) {
                long rowId = DsIdLayout.compose(0, pid, pid + 1L);
                ColumnarStore store = manager.getStore(rowId);

                Assertions.assertArrayEquals(
                        new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
                        store.getValue(PartitionedRow.class, "flags", rowId));
                Assertions.assertArrayEquals(
                        "sixteen chars!!!".getBytes(),
                        store.getValue(PartitionedRow.class, "username", rowId));
                Assertions.assertArrayEquals(
                        "hello partition".getBytes(),
                        store.getValue(PartitionedRow.class, "bio", rowId));

                byte[] group = store.getFieldGroup(PartitionedRow.class, "PROFILE", rowId);
                Assertions.assertNotNull(group);
                Assertions.assertEquals(12, group.length);
            }
        }
    }

    @Test
    public void testPartitionDirectoriesCreated() throws Exception {
        File nodeHome = Files.createTempDirectory("dsdb-partition-dirs").toFile();
        try (DsPartitionManager manager = new DsPartitionManager(
                nodeHome, new DsTableLayout(PartitionedRow.class))) {
            for (int i = 0; i < 256; i++) {
                Assertions.assertTrue(manager.getPartitionRoot(i).isDirectory(),
                        "partition " + i + " directory should exist");
            }
        }
    }

    @Test
    public void testMultiDirectoryDistribution() throws Exception {
        File nodeHome = Files.createTempDirectory("dsdb-partition-md").toFile();
        File data1 = new File(nodeHome, "disk1");
        File data2 = new File(nodeHome, "disk2");
        data1.mkdirs();
        data2.mkdirs();

        try (DsPartitionManager manager = new DsPartitionManager(
                nodeHome, 256, new String[] {"disk1", "disk2"})) {
            Set<File> usedDirs = new HashSet<>();
            for (int i = 0; i < 256; i++) {
                usedDirs.add(manager.getPartitionRoot(i).getParentFile().getParentFile());
            }
            Assertions.assertEquals(2, usedDirs.size());
            Assertions.assertTrue(usedDirs.contains(data1));
            Assertions.assertTrue(usedDirs.contains(data2));
        }
    }

    @DsTable(partitioned = true, partitions = 256)
    public static class PartitionedRow extends DsTableAdapter {
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
}
