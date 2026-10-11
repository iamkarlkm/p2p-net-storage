package com.q3lives.ds.database;

import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsFieldGroup;
import com.q3lives.ds.annotation.DsTable;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 {@code @DsTable} / {@code @DsField} / {@code @DsFieldGroup} 注解，
 * 生成每张表的存储布局描述：分布/分区/副本、列存储类型、列组结构。
 *
 * <p>布局只描述 ORM 元数据，不持有任何存储实例。</p>
 */
public final class DsTableLayout {

    private final Class<?> entityClass;
    private final DsTable tableAnno;
    private final Map<String, ColumnLayout> columns;
    private final Map<String, FieldGroupDef> groups;

    public DsTableLayout(Class<?> entityClass) {
        if (entityClass == null) {
            throw new NullPointerException("entityClass");
        }
        this.entityClass = entityClass;
        this.tableAnno = entityClass.getAnnotation(DsTable.class);
        this.columns = new LinkedHashMap<>();
        this.groups = new LinkedHashMap<>();
        parse();
    }

    public boolean isDistributed() {
        return tableAnno != null && tableAnno.distributed();
    }

    public boolean isPartitioned() {
        return tableAnno != null && tableAnno.partitioned();
    }

    public int getPartitionCount() {
        return tableAnno == null ? 1 : tableAnno.partitions();
    }

    public int getReplicaCount() {
        return tableAnno == null ? 1 : tableAnno.replicas();
    }

    public String[] getDirectories() {
        return tableAnno == null ? new String[0] : tableAnno.directories();
    }

    /**
     * 返回每个 partitionId 对应的本地目录。
     *
     * <p>如果 {@link #getDirectories()} 为空，所有分区都落在 {@code nodeHome}；
     * 否则按轮询把 256 个分区目录分散到指定目录。</p>
     */
    public List<File> getPartitionDirectories(File nodeHome) {
        if (nodeHome == null) {
            throw new NullPointerException("nodeHome");
        }
        String[] dirs = getDirectories();
        int count = getPartitionCount();
        if (dirs == null || dirs.length == 0) {
            List<File> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(nodeHome);
            }
            return list;
        }
        List<File> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            File base = new File(dirs[i % dirs.length]);
            if (!base.isAbsolute()) {
                base = new File(nodeHome, base.getPath());
            }
            list.add(base);
        }
        return list;
    }

    public List<FieldGroupDef> getFieldGroups() {
        return Collections.unmodifiableList(new ArrayList<>(groups.values()));
    }

    public Map<String, ColumnLayout> getColumns() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public ColumnLayout getColumn(String logicalName) {
        return columns.get(logicalName);
    }

    public boolean hasColumn(String logicalName) {
        return columns.containsKey(logicalName);
    }

    public FieldGroupDef getFieldGroup(String groupName) {
        return groups.get(groupName);
    }

    /**
     * 根据字段注解判断其独立存储时的存储类型。
     *
     * <p>若字段属于 {@code @DsFieldGroup}，外层应走列组路径；
     * 这里返回的仍是按 {@code @DsField#length()} 解析出的原始类型。</p>
     */
    public DsValueResolver.StorageType getStorageType(Field field) {
        if (field == null) {
            throw new NullPointerException("field");
        }
        DsField a = field.getAnnotation(DsField.class);
        if (a == null) {
            throw new IllegalArgumentException("field has no @DsField: " + field.getName());
        }
        return DsValueResolver.resolve(a.length());
    }

    public Class<?> getEntityClass() {
        return entityClass;
    }

    private void parse() {
        Map<String, List<MemberBuilder>> grouped = new HashMap<>();

        for (Field f : getAllFields(entityClass)) {
            DsField fieldAnno = f.getAnnotation(DsField.class);
            if (fieldAnno == null) {
                continue;
            }
            String logicalName = fieldAnno.name().isEmpty() ? f.getName() : fieldAnno.name();
            if (logicalName == null || logicalName.isBlank()) {
                throw new IllegalArgumentException("DsField name is blank: " + f.getName());
            }
            int length = fieldAnno.length();

            DsFieldGroup groupAnno = f.getAnnotation(DsFieldGroup.class);
            if (groupAnno != null) {
                if (length <= 0) {
                    throw new IllegalArgumentException("@DsFieldGroup member must have fixed length: " + logicalName);
                }
                String groupName = groupAnno.groupName();
                if (groupName == null || groupName.isBlank()) {
                    throw new IllegalArgumentException("@DsFieldGroup groupName is blank: " + logicalName);
                }
                grouped.computeIfAbsent(groupName, k -> new ArrayList<>())
                        .add(new MemberBuilder(logicalName, f.getType().getName(), length, groupAnno.groupIndex()));
                continue;
            }

            if (columns.putIfAbsent(logicalName,
                    new ColumnLayout(logicalName, f.getType().getName(), length,
                            DsValueResolver.resolve(length), null, 0, 0)) != null) {
                throw new IllegalArgumentException("duplicate column: " + logicalName);
            }
        }

        for (Map.Entry<String, List<MemberBuilder>> e : grouped.entrySet()) {
            String groupName = e.getKey();
            List<MemberBuilder> builders = e.getValue();
            if (builders.isEmpty()) {
                continue;
            }
            builders.sort(Comparator.comparingInt(m -> m.groupIndex));

            Set<Integer> seenIndexes = new HashSet<>();
            List<FieldGroupDef.Member> members = new ArrayList<>(builders.size());
            int offset = 0;
            for (MemberBuilder b : builders) {
                if (!seenIndexes.add(b.groupIndex)) {
                    throw new IllegalArgumentException("duplicate groupIndex in group " + groupName + ": " + b.groupIndex);
                }
                members.add(new FieldGroupDef.Member(b.logicalName, b.javaType, b.length, offset));
                offset += b.length;
            }

            int totalSize = offset;
            groups.put(groupName, new FieldGroupDef(groupName, totalSize, Collections.unmodifiableList(members)));

            // 为列组成员也生成 ColumnLayout，供上层按字段名索引
            for (FieldGroupDef.Member m : members) {
                if (columns.putIfAbsent(m.getLogicalName(),
                        new ColumnLayout(m.getLogicalName(), m.getJavaType(), m.getLength(),
                                DsValueResolver.StorageType.FIXED_VALUE_STORE, groupName,
                                m.getOffset(), totalSize)) != null) {
                    throw new IllegalArgumentException("duplicate column: " + m.getLogicalName());
                }
            }
        }
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        return fields;
    }

    private static final class MemberBuilder {
        final String logicalName;
        final String javaType;
        final int length;
        final int groupIndex;

        MemberBuilder(String logicalName, String javaType, int length, int groupIndex) {
            this.logicalName = logicalName;
            this.javaType = javaType;
            this.length = length;
            this.groupIndex = groupIndex;
        }
    }

    public static final class ColumnLayout {
        private final String logicalName;
        private final String javaType;
        private final int length;
        private final DsValueResolver.StorageType storageType;
        private final String groupName;
        private final int groupOffset;
        private final int groupSize;

        ColumnLayout(String logicalName, String javaType, int length,
                     DsValueResolver.StorageType storageType, String groupName,
                     int groupOffset, int groupSize) {
            this.logicalName = logicalName;
            this.javaType = javaType;
            this.length = length;
            this.storageType = storageType;
            this.groupName = groupName;
            this.groupOffset = groupOffset;
            this.groupSize = groupSize;
        }

        public String getLogicalName() {
            return logicalName;
        }

        public String getJavaType() {
            return javaType;
        }

        public int getLength() {
            return length;
        }

        public DsValueResolver.StorageType getStorageType() {
            return storageType;
        }

        public boolean isInGroup() {
            return groupName != null;
        }

        public String getGroupName() {
            return groupName;
        }

        public int getGroupOffset() {
            return groupOffset;
        }

        public int getGroupSize() {
            return groupSize;
        }
    }

    public static final class FieldGroupDef {
        private final String groupName;
        private final int totalSize;
        private final List<Member> members;

        FieldGroupDef(String groupName, int totalSize, List<Member> members) {
            this.groupName = groupName;
            this.totalSize = totalSize;
            this.members = members;
        }

        public String getGroupName() {
            return groupName;
        }

        public int getTotalSize() {
            return totalSize;
        }

        public List<Member> getMembers() {
            return members;
        }

        public static final class Member {
            private final String logicalName;
            private final String javaType;
            private final int length;
            private final int offset;

            Member(String logicalName, String javaType, int length, int offset) {
                this.logicalName = logicalName;
                this.javaType = javaType;
                this.length = length;
                this.offset = offset;
            }

            public String getLogicalName() {
                return logicalName;
            }

            public String getJavaType() {
                return javaType;
            }

            public int getLength() {
                return length;
            }

            public int getOffset() {
                return offset;
            }
        }
    }
}
