package com.q3lives.ds.database.columnar;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.exception.meta.MetaCompositeBitOverlapException;
import com.q3lives.ds.exception.meta.MetaCompositeGroupLengthMismatchException;
import com.q3lives.ds.exception.meta.MetaDuplicateColumnDefinitionException;
import com.q3lives.ds.exception.meta.MetaSchemaException;
import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

public final class TableMetaStore {
    private final File dbRoot;
    private final ColumnRegistry columnRegistry;

    public TableMetaStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.columnRegistry = new ColumnRegistry(dbRoot);
    }

    public <T extends DsTableAdapter> TableMeta ensureMeta(Class<T> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        File metaFile = metaFile(entityClass);
        File lockFile = new File(metaFile.getParentFile(), "table.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
            FileLock ignored = ch.lock()) {
            // 关键点：元数据更新要幂等；signature 不变时不覆写文件，减少无意义磁盘写入
            TableMeta meta = load(metaFile);
            TableMeta fresh = build(entityClass);
            if (!Objects.equals(meta.signature, fresh.signature)) {
                save(metaFile, fresh);
                return fresh;
            }
            return meta;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaStoreException("failed to ensure table meta: entityClass=" + entityClass.getName(), e);
        }
    }

    private <T extends DsTableAdapter> TableMeta build(Class<T> entityClass) {
        TableMeta meta = new TableMeta();
        meta.entityClassName = entityClass.getName();
        meta.columns = new ArrayList<>();
        meta.compositeGroups = new LinkedHashMap<>();
        Map<String, ColumnDef> uniqueColumnsByName = new HashMap<>();

        for (Field f : getAllFields(entityClass)) {
            if (f.isAnnotationPresent(DsCompositeField.class)) {
                DsCompositeField a = f.getAnnotation(DsCompositeField.class);
                String group = a.group();
                if (group == null || group.isBlank()) {
                    throw new MetaSchemaException("composite group is blank: field=" + f.getName());
                }
                if (a.length() <= 0) {
                    throw new MetaSchemaException("composite group length must be > 0: group=" + group);
                }
                CompositeGroup g = meta.compositeGroups.computeIfAbsent(group, k -> new CompositeGroup());
                g.group = group;
                if (g.length == 0) {
                    g.length = a.length();
                } else if (g.length != a.length()) {
                    throw new MetaCompositeGroupLengthMismatchException("composite group length mismatch: group=" + group + ", " + g.length + " vs " + a.length());
                }
                CompositeItem item = new CompositeItem();
                item.name = a.name().isEmpty() ? f.getName() : a.name();
                item.startBits = a.startBits();
                item.endBits = a.endBits();
                validateAndAddCompositeItem(g, item);
            }
            if (f.isAnnotationPresent(DsField.class)) {
                DsField a = f.getAnnotation(DsField.class);
                String logicalName = a.name().isEmpty() ? f.getName() : a.name();
                if (logicalName == null || logicalName.isBlank()) {
                    throw new MetaSchemaException("DsField name is blank: field=" + f.getName());
                }
                String colKey = entityClass.getName() + "#" + logicalName;
                // colId 只增不回收：同一个 colKey 永远拿到同一个 colId；重命名/改 name 会分配新列
                long colId = columnRegistry.getOrCreateColId(entityClass, colKey);
                ColumnDef c = new ColumnDef();
                c.colId = colId;
                c.colKey = colKey;
                c.name = logicalName;
                c.javaType = f.getType().getName();
                c.length = a.length();
                c.precision = a.precision();
                c.scale = a.scale();
                ColumnDef old = uniqueColumnsByName.get(logicalName);
                if (old == null) {
                    uniqueColumnsByName.put(logicalName, c);
                    meta.columns.add(c);
                } else {
                    if (!Objects.equals(old.javaType, c.javaType) || old.length != c.length || old.precision != c.precision || old.scale != c.scale) {
                        throw new MetaDuplicateColumnDefinitionException("duplicate DsField name with mismatch: name=" + logicalName);
                    }
                }
            }
        }

        for (CompositeGroup g : meta.compositeGroups.values()) {
            String colKey = entityClass.getName() + "#@composite:" + g.group;
            long colId = columnRegistry.getOrCreateColId(entityClass, colKey);
            g.colId = colId;
            g.colKey = colKey;
        }

        meta.signature = computeSignature(meta);
        return meta;
    }

    private static void validateAndAddCompositeItem(CompositeGroup g, CompositeItem item) {
        if (item.name == null || item.name.isBlank()) {
            throw new MetaSchemaException("composite item name is blank: group=" + g.group);
        }
        if (item.startBits < 0 || item.endBits < 0 || item.endBits < item.startBits) {
            throw new MetaSchemaException("composite item bit range invalid: group=" + g.group + ", name=" + item.name);
        }
        int maxBit = g.length * 8 - 1;
        if (item.endBits > maxBit) {
            throw new MetaSchemaException("composite item bit range overflow: group=" + g.group + ", name=" + item.name);
        }
        Set<String> names = new HashSet<>();
        for (CompositeItem existing : g.items) {
            names.add(existing.name);
            if (isOverlap(existing.startBits, existing.endBits, item.startBits, item.endBits)) {
                throw new MetaCompositeBitOverlapException("composite bit overlap: group=" + g.group + ", name=" + item.name);
            }
        }
        if (names.contains(item.name)) {
            throw new MetaSchemaException("duplicate composite item name: group=" + g.group + ", name=" + item.name);
        }
        g.items.add(item);
    }

    private static boolean isOverlap(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart <= bEnd && bStart <= aEnd;
    }

    private static String computeSignature(TableMeta meta) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("v1|").append(meta.entityClassName).append('\n');
        for (ColumnDef c : meta.columns) {
            sb.append("F|").append(c.colId).append('|').append(c.colKey).append('|').append(c.javaType)
                .append('|').append(c.length).append('|').append(c.precision).append('|').append(c.scale).append('\n');
        }
        for (CompositeGroup g : meta.compositeGroups.values()) {
            sb.append("C|").append(g.group).append('|').append(g.length).append('|').append(g.colId).append('|').append(g.colKey).append('\n');
            for (CompositeItem i : g.items) {
                sb.append("I|").append(i.name).append('|').append(i.startBits).append('|').append(i.endBits).append('\n');
            }
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private static List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            Field[] declared = c.getDeclaredFields();
            for (Field f : declared) {
                fields.add(f);
            }
            c = c.getSuperclass();
        }
        return fields;
    }

    private File metaFile(Class<? extends DsTableAdapter> entityClass) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClass.getName(), "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "table.meta.yaml");
    }

    private static TableMeta load(File metaFile) throws Exception {
        if (!metaFile.isFile()) {
            TableMeta meta = new TableMeta();
            meta.signature = "";
            return meta;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            Yaml yaml = new Yaml();
            TableMeta meta = yaml.loadAs(in, TableMeta.class);
            if (meta == null) {
                meta = new TableMeta();
            }
            if (meta.columns == null) {
                meta.columns = new ArrayList<>();
            }
            if (meta.compositeGroups == null) {
                meta.compositeGroups = new LinkedHashMap<>();
            }
            for (CompositeGroup g : meta.compositeGroups.values()) {
                if (g.items == null) {
                    g.items = new ArrayList<>();
                }
                if (g.colKey == null) {
                    g.colKey = "";
                }
            }
            if (meta.signature == null) {
                meta.signature = "";
            }
            return meta;
        }
    }

    private static void save(File metaFile, TableMeta meta) throws Exception {
        File tmp = new File(metaFile.getAbsolutePath() + ".tmp");
        Yaml yaml = new Yaml();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            yaml.dump(meta, w);
        }
        try {
            Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class TableMeta {
        public String entityClassName;
        public String signature;
        public List<ColumnDef> columns;
        public Map<String, CompositeGroup> compositeGroups;
    }

    public static final class ColumnDef {
        public long colId;
        public String colKey;
        public String name;
        public String javaType;
        public int length;
        public int precision;
        public int scale;
    }

    public static final class CompositeGroup {
        public String group;
        public long colId;
        public String colKey;
        public int length;
        public List<CompositeItem> items = new ArrayList<>();
    }

    public static final class CompositeItem {
        public String name;
        public int startBits;
        public int endBits;
    }
}
