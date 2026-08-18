/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.util.DsPathUtil;

/**
 *
 * @author Administrator
 */
public class DsDatabaseLocal implements AutoCloseable {
    
    private static final String DEFAULT_SYSTEM_YAML_NAME = "SystemConfig.yaml";
    private static final String SYS_PROP_SYSTEM_YAML = "p2p.system.yaml";
    private static final String SYS_PROP_DB_HOME = "p2p.db.home";

    private final File root;
    private final DsFixedBucketStore bucketStore;
    private final ConcurrentHashMap<Class<? extends DsTableAdapter>, TableMeta<?>> tableMetaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsHashMap> relationMapCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsEqIndexStore> indexCache = new ConcurrentHashMap<>();

    public DsDatabaseLocal(File root) {
        this.root = Objects.requireNonNull(root, "root cannot be null");
        if (!this.root.exists()) {
            this.root.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(this.root.getAbsolutePath());
    }

    public File getRoot() {
        return root;
    }

    public void close() throws IOException {
        Exception ex = null;
        for (DsEqIndexStore idx : indexCache.values()) {
            try {
                idx.close();
            } catch (Exception e) {
                ex = e;
            }
        }
        indexCache.clear();
        if (ex != null) {
            if (ex instanceof IOException ioe) throw ioe;
            throw new IOException(ex);
        }
    }

    public static void forceResetAllIndexesForTest(File root) throws IOException {
        Objects.requireNonNull(root, "root cannot be null");
        if (!root.isDirectory()) {
            return;
        }
        File indexes = new File(root, "indexes");
        if (!indexes.exists()) {
            return;
        }
        try (var stream = java.nio.file.Files.walk(indexes.toPath())) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        java.nio.file.Files.delete(p);
                    } catch (IOException ignore) {
                    }
                });
        }
    }
    
    public static DsDatabaseLocal load() {
        File home = resolveDbHomeFromYamlOrSystem();
        return new DsDatabaseLocal(home);
    }
    
    private static File resolveDbHomeFromYamlOrSystem() {
        String home = System.getProperty(SYS_PROP_DB_HOME);
        if (home != null && !home.isBlank()) {
            return new File(home).getAbsoluteFile();
        }
        
        String yamlPath = System.getProperty(SYS_PROP_SYSTEM_YAML);
        File yamlFile = null;
        if (yamlPath != null && !yamlPath.isBlank()) {
            yamlFile = new File(yamlPath).getAbsoluteFile();
        } else {
            File local = new File(System.getProperty("user.dir", "."), DEFAULT_SYSTEM_YAML_NAME).getAbsoluteFile();
            if (local.isFile()) {
                yamlFile = local;
            }
        }
        
        if (yamlFile == null || !yamlFile.isFile()) {
            return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
        }
        
        try (InputStream in = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            Object v = map.get("DbHome");
            if (v == null) {
                v = map.get("dbHome");
            }
            if (!(v instanceof String s) || s.isBlank()) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            File f = new File(s);
            if (f.isAbsolute()) {
                return f.getAbsoluteFile();
            }
            return new File(yamlFile.getParentFile(), s).getAbsoluteFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private TableMeta<? extends DsTableAdapter> metaOf(Class<? extends DsTableAdapter> clazz) {
        return tableMetaCache.computeIfAbsent(clazz, this::createMeta);
    }

    private static final class IndexedColumn {
        final String columnName;
        final Field field;
        final Class<?> fieldType;
        final DsEqIndexStore.IndexedValueKind valueKind;

        IndexedColumn(String columnName, Field field, Class<?> fieldType, DsEqIndexStore.IndexedValueKind valueKind) {
            this.columnName = columnName;
            this.field = field;
            this.fieldType = fieldType;
            this.valueKind = valueKind;
        }
    }

    private static final class CompositeIndex {
        final String name;
        final List<IndexedColumn> columns;

        CompositeIndex(String name, List<IndexedColumn> columns) {
            this.name = name;
            this.columns = columns;
        }
    }

    private static DsEqIndexStore.IndexedValueKind resolveValueKind(Class<?> type) {
        if (type == long.class || type == Long.class) return DsEqIndexStore.IndexedValueKind.LONG;
        if (type == int.class || type == Integer.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        if (type == boolean.class || type == Boolean.class) return DsEqIndexStore.IndexedValueKind.LONG;
        if (type == Date.class) return DsEqIndexStore.IndexedValueKind.LONG;
        if (type == String.class) return DsEqIndexStore.IndexedValueKind.STRING;
        return DsEqIndexStore.IndexedValueKind.STRING;
    }

    private DsEqIndexStore resolveEqIndex(TableMeta<?> meta, IndexedColumn col) throws IOException {
        String cacheKey = meta.space + "#" + meta.schemaId + "#" + col.columnName;
        DsEqIndexStore idx = indexCache.get(cacheKey);
        if (idx != null) return idx;
        String safeSpace = DsPathUtil.toSafeFileName(meta.space, 80);
        String safeCol = DsPathUtil.toSafeFileName(col.columnName, 64);
        String space = "indexes/" + safeSpace + "/" + meta.schemaId;
        String indexName = "eq_" + safeCol;
        DsEqIndexStore created = new DsEqIndexStore(this.root, space, indexName, col.valueKind);
        DsEqIndexStore prev = indexCache.putIfAbsent(cacheKey, created);
        if (prev != null) {
            created.close();
            return prev;
        }
        return created;
    }

    private DsEqIndexStore resolveCompositeIndex(TableMeta<?> meta, CompositeIndex ci) throws IOException {
        String cacheKey = meta.space + "#" + meta.schemaId + "#cidx_" + ci.name;
        DsEqIndexStore idx = indexCache.get(cacheKey);
        if (idx != null) return idx;
        String safeSpace = DsPathUtil.toSafeFileName(meta.space, 80);
        String safeName = DsPathUtil.toSafeFileName(ci.name, 64);
        String space = "indexes/" + safeSpace + "/" + meta.schemaId;
        String indexName = "cidx_" + safeName;
        DsEqIndexStore created = new DsEqIndexStore(this.root, space, indexName, DsEqIndexStore.IndexedValueKind.LONG);
        DsEqIndexStore prev = indexCache.putIfAbsent(cacheKey, created);
        if (prev != null) {
            created.close();
            return prev;
        }
        return created;
    }

    private static long toIndexLongValue(IndexedColumn col, Object value) {
        if (value == null) return 0L;
        Class<?> t = col.fieldType;
        if (t == long.class || t == Long.class) return (Long) value;
        if (t == int.class || t == Integer.class) return ((Integer) value).longValue();
        if (t == short.class || t == Short.class) return ((Short) value).longValue();
        if (t == byte.class || t == Byte.class) return ((Byte) value).longValue();
        if (t == boolean.class || t == Boolean.class) return ((Boolean) value) ? 1L : 0L;
        if (t == Date.class) return ((Date) value).getTime();
        return 0L;
    }

    private static String toIndexStringValue(IndexedColumn col, Object value) {
        if (value == null) return "";
        return value.toString();
    }

    /**
     * 将复合索引涉及列的值组合成一个字符串，然后 FNV-1a 哈希为 long 作为索引键。
     * NULL 值统一编码为 "\0"，保证可区分且稳定。
     */
    private static long compositeKeyValue(CompositeIndex ci, Object owner) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < ci.columns.size(); i++) {
            if (i > 0) sb.append('\u0001');
            IndexedColumn col = ci.columns.get(i);
            Object val = readFieldValue(col.field, owner);
            if (val == null) {
                sb.append('\u0000');
            } else if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                sb.append(toIndexLongValue(col, val));
            } else {
                sb.append(val.toString());
            }
        }
        return fnv1a64(sb.toString());
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * 与 DsEqIndexStore.hashString 保持一致的 FNV-1a 哈希，用于批量索引聚合。
     */
    private static long hashStringForIndex(String s) {
        if (s == null || s.isEmpty()) return 0L;
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h == 0L ? 1L : h;
    }

    private static Object readFieldValue(Field f, Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private <T extends DsTableAdapter> void removeRowFromIndexes(TableMeta<T> meta, T row) throws IOException {
        if (row == null) return;
        long rowId = row.getId() == null ? 0L : row.getId();
        if (rowId == 0L) return;
        for (IndexedColumn col : meta.indexedColumns) {
            Object val = readFieldValue(col.field, row);
            DsEqIndexStore idx = resolveEqIndex(meta, col);
            switch (col.valueKind) {
                case LONG:
                    idx.removeIndex(toIndexLongValue(col, val), rowId);
                    break;
                case STRING:
                    idx.removeIndex(toIndexStringValue(col, val), rowId);
                    break;
            }
        }
        for (CompositeIndex ci : meta.compositeIndexes) {
            DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
            idx.removeIndex(compositeKeyValue(ci, row), rowId);
        }
    }

    private <T extends DsTableAdapter> void putRowIntoIndexes(TableMeta<T> meta, T row) throws IOException {
        if (row == null) return;
        long rowId = row.getId() == null ? 0L : row.getId();
        if (rowId == 0L) return;
        for (IndexedColumn col : meta.indexedColumns) {
            Object val = readFieldValue(col.field, row);
            DsEqIndexStore idx = resolveEqIndex(meta, col);
            switch (col.valueKind) {
                case LONG:
                    idx.putIndex(toIndexLongValue(col, val), rowId);
                    break;
                case STRING:
                    idx.putIndex(toIndexStringValue(col, val), rowId);
                    break;
            }
        }
        for (CompositeIndex ci : meta.compositeIndexes) {
            DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
            idx.putIndex(compositeKeyValue(ci, row), rowId);
        }
    }
    
    private TableMeta<? extends DsTableAdapter> createMeta(Class<? extends DsTableAdapter> clazz) {
        String space = clazz.getName();
        EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(root, clazz);
        int sample = sampleRowLength(clazz);
        EntitySchemaUtil.SchemaDef curSchema = EntitySchemaUtil.schemaOf(clazz);
        List<IndexedColumn> indexedColumns = new ArrayList<>();
        Map<String, IndexedColumn> colByName = new LinkedHashMap<>();
        for (EntitySchemaUtil.ColumnDef col : curSchema.getColumns()) {
            Field f = col.declaredField;
            f.setAccessible(true);
            DsEqIndexStore.IndexedValueKind vk = resolveValueKind(f.getType());
            IndexedColumn ic = new IndexedColumn(col.name, f, f.getType(), vk);
            colByName.put(col.name, ic);
            if (col.indexed) {
                indexedColumns.add(ic);
            }
        }
        List<CompositeIndex> compositeIndexes = new ArrayList<>();
        for (EntitySchemaUtil.CompositeIndexDef ci : curSchema.getCompositeIndexes()) {
            List<IndexedColumn> cols = new ArrayList<>(ci.columns.size());
            for (EntitySchemaUtil.ColumnDef c : ci.columns) {
                IndexedColumn ic = colByName.get(c.name);
                if (ic == null) {
                    throw new IllegalStateException("Composite index " + ci.name + " references unknown column: " + c.name);
                }
                cols.add(ic);
            }
            compositeIndexes.add(new CompositeIndex(ci.name, cols));
        }
        int finalRowLength;
        int legacyRowLength;
        if (sample != index.rowLength) {
            legacyRowLength = index.rowLength;
            finalRowLength = sample;
            System.err.println("[DsDatabaseLocal] WARN Schema row length mismatch (Online DDL compat ON): "
                + "class=" + clazz.getName()
                + ", storedLegacyRowLength=" + index.rowLength
                + ", currentSchemaRowLength=" + sample
                + ". Old row data auto-fallback @DsField.defaultValue on read; new writes use new length.");
        } else {
            legacyRowLength = -1;
            finalRowLength = sample;
        }
        return new TableMeta<>(clazz, space, index.rowType, finalRowLength, legacyRowLength, curSchema.schemaId,
                indexedColumns, compositeIndexes);
    }
    
    private static int sampleRowLength(Class<? extends DsTableAdapter> clazz) {
        DsTableAdapter sample = newInstance(clazz);
        ByteBuffer buf = sample.toBytes();
        return buf.remaining();
    }
    
    private static <T extends DsTableAdapter> T newInstance(Class<T> clazz) {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("实体必须包含无参构造: " + clazz.getName(), e);
        }
    }
    
    public <T extends DsTableAdapter> long putEntity(T value, boolean assignIdIfMissing) throws IOException {
        Objects.requireNonNull(value, "value cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(value.getClass());

        Long id = value.getId();
        if (assignIdIfMissing && (id == null || id == 0L)) {
            long newId = bucketStore.getNewId(meta.space, meta.type, meta.rowLength);
            value.setId(newId);
            id = newId;
        }
        if (id == null || id == 0L) {
            throw new IllegalArgumentException("entity id is missing");
        }

        ByteBuffer buf = value.toBytes();
        if (buf.remaining() != meta.rowLength) {
            System.err.println("[DsDatabaseLocal] WARN putEntity row length mismatch (compat): expected=" + meta.rowLength + ", actual=" + buf.remaining() + ", class=" + value.getClass().getName());
        }
        byte[] bytes;
        if (buf.remaining() == meta.rowLength) {
            bytes = buf.array();
        } else {
            bytes = new byte[meta.rowLength];
            buf.rewind();
            int copyN = Math.min(buf.remaining(), meta.rowLength);
            buf.get(bytes, 0, copyN);
        }

        T oldRow = null;
        try {
            byte[] oldBytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
            if (oldBytes != null) {
                oldRow = newInstance(meta.clazz);
                oldRow.setId(id);
                oldRow.load(ByteBuffer.wrap(oldBytes));
            }
        } catch (Exception ignore) {
            oldRow = null;
        }
        removeRowFromIndexes(meta, oldRow);

        bucketStore.update(meta.space, meta.type, id, bytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);

        putRowIntoIndexes(meta, value);
        return id;
    }

    /**
     * 批量 putEntity。对索引列按 indexedValue 聚合后只做一次 read-modify-write，
     * 减少小批量写入时的 I/O 放大。
     *
     * @param values             待保存实体
     * @param assignIdIfMissing  是否自动分配缺失的 ID
     * @return 保存后的 ID 列表（顺序与输入一致）
     * @throws IOException 当读写失败时
     */
    public <T extends DsTableAdapter> List<Long> putEntities(List<T> values, boolean assignIdIfMissing) throws IOException {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(values.get(0), "entity cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(values.get(0).getClass());

        int n = values.size();
        List<Long> ids = new ArrayList<>(n);
        List<T> oldRows = new ArrayList<>(n);
        List<byte[]> newBytesList = new ArrayList<>(n);

        // Phase 1: 分配 ID、序列化、读取旧行
        for (T value : values) {
            Objects.requireNonNull(value, "entity cannot be null");
            Long id = value.getId();
            if (assignIdIfMissing && (id == null || id == 0L)) {
                long newId = bucketStore.getNewId(meta.space, meta.type, meta.rowLength);
                value.setId(newId);
                id = newId;
            }
            if (id == null || id == 0L) {
                throw new IllegalArgumentException("entity id is missing");
            }
            ids.add(id);

            ByteBuffer buf = value.toBytes();
            if (buf.remaining() != meta.rowLength) {
                System.err.println("[DsDatabaseLocal] WARN putEntities row length mismatch (compat): expected=" + meta.rowLength + ", actual=" + buf.remaining() + ", class=" + value.getClass().getName());
            }
            byte[] bytes;
            if (buf.remaining() == meta.rowLength) {
                bytes = buf.array();
            } else {
                bytes = new byte[meta.rowLength];
                buf.rewind();
                int copyN = Math.min(buf.remaining(), meta.rowLength);
                buf.get(bytes, 0, copyN);
            }
            newBytesList.add(bytes);

            T oldRow = null;
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
                if (oldBytes != null) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(id);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
            oldRows.add(oldRow);
        }

        // Phase 2: 批量更新主表
        for (int i = 0; i < n; i++) {
            bucketStore.update(meta.space, meta.type, ids.get(i), newBytesList.get(i), DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        }

        // Phase 3: 批量更新单字段索引
        for (IndexedColumn col : meta.indexedColumns) {
            batchUpdateIndex(meta, col, ids, oldRows, values);
        }

        // Phase 4: 批量更新复合索引
        for (CompositeIndex ci : meta.compositeIndexes) {
            batchUpdateCompositeIndex(meta, ci, ids, oldRows, values);
        }

        return ids;
    }

    private <T extends DsTableAdapter> void batchUpdateIndex(TableMeta<T> meta, IndexedColumn col,
            List<Long> ids, List<T> oldRows, List<T> newRows) throws IOException {
        DsEqIndexStore idx = resolveEqIndex(meta, col);
        Map<Long, Set<Long>> target = new HashMap<>();
        Set<Long> touchedValues = new HashSet<>();

        int n = ids.size();
        for (int i = 0; i < n; i++) {
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) touchedValues.add(indexValueOf(col, oldRow));
            touchedValues.add(indexValueOf(col, newRow));
        }
        for (Long v : touchedValues) {
            long[] current = idx.findByIndex(v);
            Set<Long> set = new LinkedHashSet<>();
            if (current != null) {
                for (long r : current) set.add(r);
            }
            target.put(v, set);
        }
        for (int i = 0; i < n; i++) {
            long rowId = ids.get(i);
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                Set<Long> set = target.get(indexValueOf(col, oldRow));
                if (set != null) set.remove(rowId);
            }
            target.get(indexValueOf(col, newRow)).add(rowId);
        }
        Map<Long, long[]> batch = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : target.entrySet()) {
            Set<Long> set = e.getValue();
            long[] arr = new long[set.size()];
            int pos = 0;
            for (Long r : set) arr[pos++] = r;
            batch.put(e.getKey(), arr);
        }
        idx.applyIndexBatch(batch);
    }

    private static long indexValueOf(IndexedColumn col, Object row) {
        Object val = readFieldValue(col.field, row);
        if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
            return toIndexLongValue(col, val);
        } else {
            return hashStringForIndex(toIndexStringValue(col, val));
        }
    }

    private <T extends DsTableAdapter> void batchUpdateCompositeIndex(TableMeta<T> meta, CompositeIndex ci,
            List<Long> ids, List<T> oldRows, List<T> newRows) throws IOException {
        DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
        Map<Long, Set<Long>> target = new HashMap<>();
        Set<Long> touchedValues = new HashSet<>();

        int n = ids.size();
        for (int i = 0; i < n; i++) {
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) touchedValues.add(compositeKeyValue(ci, oldRow));
            touchedValues.add(compositeKeyValue(ci, newRow));
        }
        for (Long v : touchedValues) {
            long[] current = idx.findByIndex(v);
            Set<Long> set = new LinkedHashSet<>();
            if (current != null) {
                for (long r : current) set.add(r);
            }
            target.put(v, set);
        }
        for (int i = 0; i < n; i++) {
            long rowId = ids.get(i);
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                Set<Long> set = target.get(compositeKeyValue(ci, oldRow));
                if (set != null) set.remove(rowId);
            }
            target.get(compositeKeyValue(ci, newRow)).add(rowId);
        }
        Map<Long, long[]> batch = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : target.entrySet()) {
            Set<Long> set = e.getValue();
            long[] arr = new long[set.size()];
            int pos = 0;
            for (Long r : set) arr[pos++] = r;
            batch.put(e.getKey(), arr);
        }
        idx.applyIndexBatch(batch);
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        try {
            byte[] bytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
            T obj = newInstance(clazz);
            obj.load(ByteBuffer.wrap(bytes));
            return obj;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public long putTable(DsTableAdapter value, boolean withRelations) throws IOException {
        long id = putEntity(value, true);
        if (!withRelations) {
            return id;
        }
        persistRelations(value);
        return id;
    }
    
    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, boolean withRelations) {
        T obj = getTable(clazz, id);
        if (!withRelations) {
            return obj;
        }
        loadRelations(obj);
        return obj;
    }

    public <T extends DsTableAdapter> boolean removeTable(Class<T> clazz, long id, boolean withRelations) throws IOException {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);

        T oldRow = null;
        try {
            byte[] oldBytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
            if (oldBytes != null) {
                oldRow = newInstance(meta.clazz);
                oldRow.setId(id);
                oldRow.load(ByteBuffer.wrap(oldBytes));
            }
        } catch (Exception ignore) {
            oldRow = null;
        }
        removeRowFromIndexes(meta, oldRow);

        if (withRelations) {
            removeRelations(clazz, id);
        }
        bucketStore.remove(meta.space, meta.type, id);
        return true;
    }

    private void removeRelations(Class<?> ownerClass, long ownerId) throws IOException {
        Field[] fields = ownerClass.getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                relationMap(ownerClass, f.getName(), "one_to_one").remove(ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "one_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "many_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                removeVarRelation(ownerClass, f.getName(), "map", "pairs", ownerId);
            }
        }
    }

    private void removeVarRelation(Class<?> ownerClass, String fieldName, String kind, String type, long ownerId) throws IOException {
        DsHashMap map = relationMap(ownerClass, fieldName, kind);
        long stored = map.getOrDefault(ownerId, 0L);
        map.remove(ownerId);
        if (stored != 0L) {
            bucketStore.remove(relSpace(ownerClass, fieldName, kind), type, stored);
        }
    }
    
    public long putTableWithRelations(DsTableAdapter value) throws IOException {
        return putTable(value, true);
    }
    
    public <T extends DsTableAdapter> T getTableWithRelations(Class<T> clazz, long id) {
        return getTable(clazz, id, true);
    }
    
    private void persistRelations(DsTableAdapter owner) throws IOException {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                persistOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                persistOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                persistManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                persistMapField(owner, f);
            }
        }
    }
    
    private void loadRelations(DsTableAdapter owner) {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                loadOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                loadOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                loadManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                loadMapField(owner, f);
            }
        }
    }
    
    private void persistOneToOne(DsTableAdapter owner, Field field) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof DsTableAdapter join)) {
            throw new IllegalArgumentException("DsOneToOne field must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        long joinId = putEntity(join, true);
        map.put(owner.getId().longValue(), joinId);
    }
    
    private void loadOneToOne(DsTableAdapter owner, Field field) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        Long joinId = map.get(owner.getId());
        if (joinId == null || joinId == 0L) {
            return;
        }
        Class<?> joinClass = field.getType();
        if (!DsTableAdapter.class.isAssignableFrom(joinClass)) {
            throw new IllegalArgumentException("DsOneToOne field type must extend DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        @SuppressWarnings("unchecked")
        DsTableAdapter join = getTable((Class<? extends DsTableAdapter>) joinClass, joinId);
        try {
            field.setAccessible(true);
            field.set(owner, join);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void persistOneToMany(DsTableAdapter owner, Field field) throws IOException {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }
    
    private void loadOneToMany(DsTableAdapter owner, Field field) {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }
    
    private void persistManyToMany(DsTableAdapter owner, Field field) throws IOException {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }
    
    private void loadManyToMany(DsTableAdapter owner, Field field) {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }
    
    private void persistIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Collection<?> col)) {
            throw new IllegalArgumentException("relation field must be Collection: " + owner.getClass().getName() + "." + field.getName());
        }
        if (col.isEmpty()) {
            map.remove(owner.getId());
            return;
        }
        
        long[] ids = new long[col.size()];
        int i = 0;
        for (Object item : col) {
            if (!(item instanceof DsTableAdapter join)) {
                throw new IllegalArgumentException("collection element must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
            }
            if (!joinClass.isInstance(join)) {
                throw new IllegalArgumentException("collection element type mismatch: expected=" + joinClass.getName());
            }
            ids[i++] = putEntity(join, true);
        }
        byte[] payload = encodeIdList(ids);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", old, payload);
        map.put(owner.getId().longValue(), stored);
    }
    
    private void loadIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", stored);
        long[] ids = decodeIdList(payload);
        if (ids.length == 0) {
            return;
        }
        List<DsTableAdapter> out = new ArrayList<>(ids.length);
        for (long id : ids) {
            out.add(getTable(joinClass, id));
        }
        try {
            field.setAccessible(true);
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            if (Collection.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            throw new IllegalArgumentException("relation field must be List/Collection: " + owner.getClass().getName() + "." + field.getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void persistMapField(DsTableAdapter owner, Field field) throws IOException {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("DsMapField must be Map: " + owner.getClass().getName() + "." + field.getName());
        }
        if (m.isEmpty()) {
            map.remove(owner.getId());
            return;
        }
        
        long[] pairs = new long[m.size() * 2];
        int idx = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof DsTableAdapter k)) {
                throw new IllegalArgumentException("map key must be DsTableAdapter");
            }
            if (!(e.getValue() instanceof DsTableAdapter val)) {
                throw new IllegalArgumentException("map value must be DsTableAdapter");
            }
            if (!ann.keyClass().isInstance(k)) {
                throw new IllegalArgumentException("map key type mismatch: expected=" + ann.keyClass().getName());
            }
            if (!ann.valueClass().isInstance(val)) {
                throw new IllegalArgumentException("map value type mismatch: expected=" + ann.valueClass().getName());
            }
            pairs[idx++] = putEntity(k, true);
            pairs[idx++] = putEntity(val, true);
        }
        byte[] payload = encodeIdPairs(pairs);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", old, payload);
        map.put(owner.getId().longValue(), stored);
    }
    
    private void loadMapField(DsTableAdapter owner, Field field) {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", stored);
        long[] pairs = decodeIdPairs(payload);
        if (pairs.length == 0) {
            return;
        }
        Map<DsTableAdapter, DsTableAdapter> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            DsTableAdapter k = getTable(ann.keyClass(), pairs[i]);
            DsTableAdapter v = getTable(ann.valueClass(), pairs[i + 1]);
            out.put(k, v);
        }
        try {
            field.setAccessible(true);
            field.set(owner, out);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    
    private DsHashMap relationMap(Class<?> ownerClass, String fieldName, String kind) {
        DsPathUtil.validateSegment(fieldName, "fieldName");
        DsPathUtil.validateSegment(kind, "relation kind");
        String key = ownerClass.getName() + "#" + fieldName + "#" + kind;
        return relationMapCache.computeIfAbsent(key, k -> {
            File f = relationMapFile(ownerClass, fieldName, kind);
            return new DsHashMap(f);
        });
    }
    
    private File relationMapFile(Class<?> ownerClass, String fieldName, String kind) {
        String spacePath = DsPathUtil.dottedToLinuxPath(ownerClass.getName(), "table");
        String relRoot = new File(root, "relations").getAbsolutePath().replace('\\', '/');
        String full = relRoot + "/" + spacePath + "/" + kind;
        File dir = new File(full);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fieldName + ".map");
    }
    
    private static String relSpace(Class<?> ownerClass, String fieldName, String kind) {
        return ownerClass.getName() + "." + kind + "." + fieldName;
    }
    
    private long storeVarBytes(String space, String type, long oldId, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        byte[] data = wrapVarPayload(payload);
        if (oldId != 0L) {
            return bucketStore.update(space, type, oldId, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        }
        long id = bucketStore.getNewId(space, type, data.length);
        bucketStore.update(space, type, id, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        return id;
    }
    
    private byte[] loadVarBytes(String space, String type, long id) {
        int unit = bucketUnitSize(id);
        try {
            byte[] raw = bucketStore.get(space, type, id, 0, unit);
            return unwrapVarPayload(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    private static int bucketUnitSize(long id) {
        int power = (int) ((id >>> DsFixedBucketStore.ID_OFFSET_BITS) & 0xFF);
        return 1 << power;
    }
    
    private static byte[] wrapVarPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(4 + payload.length);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }
    
    private static byte[] unwrapVarPayload(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int len = buf.getInt();
        if (len <= 0) {
            return new byte[0];
        }
        if (len > raw.length - 4) {
            throw new IllegalStateException("corrupted var payload length");
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }
    
    private static byte[] encodeIdList(long[] ids) {
        ByteBuffer buf = ByteBuffer.allocate(4 + ids.length * 8);
        buf.putInt(ids.length);
        for (long id : ids) {
            buf.putLong(id);
        }
        return buf.array();
    }
    
    private static long[] decodeIdList(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        if (payload.length != 4 + n * 8) {
            throw new IllegalStateException("id list payload size mismatch");
        }
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = buf.getLong();
        }
        return ids;
    }
    
    private static byte[] encodeIdPairs(long[] pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("pairs length must be even");
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + pairs.length * 8);
        buf.putInt(pairs.length / 2);
        for (long id : pairs) {
            buf.putLong(id);
        }
        return buf.array();
    }
    
    private static long[] decodeIdPairs(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        int expect = 4 + n * 16;
        if (payload.length != expect) {
            throw new IllegalStateException("pairs payload size mismatch");
        }
        long[] out = new long[n * 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getLong();
        }
        return out;
    }
    
    private static final class TableMeta<T extends DsTableAdapter> {
        final Class<T> clazz;
        final String space;
        final String type;
        final int rowLength;
        final int legacyRowLength;
        final String schemaId;
        final List<IndexedColumn> indexedColumns;
        final List<CompositeIndex> compositeIndexes;

        TableMeta(Class<T> clazz, String space, String type, int rowLength, int legacyRowLength, String schemaId,
                  List<IndexedColumn> indexedColumns, List<CompositeIndex> compositeIndexes) {
            this.clazz = clazz;
            this.space = space;
            this.type = type;
            this.rowLength = rowLength;
            this.legacyRowLength = legacyRowLength;
            this.schemaId = schemaId;
            this.indexedColumns = indexedColumns == null || indexedColumns.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(indexedColumns));
            this.compositeIndexes = compositeIndexes == null || compositeIndexes.isEmpty()
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(new ArrayList<>(compositeIndexes));
        }
    }
    
    /**
     * 存储DsTableAdapter
     * @param value
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter value) throws IOException {
        return putTable(value, false);
       
    }
    
    /**
     * 装载DsTableAdapter
     * @param key
     * @return 
     */
    public DsTableAdapter getTable(long key) {
        throw new UnsupportedOperationException("use getTable(Class, long) because id alone cannot determine table class");
    }
    
    /**
     * 存储DsTableAdapter内部一对一字段
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsOneToOne field) throws IOException {
        // TODO file(root,field.getClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueId  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储value.toBytes() 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部一对一字段
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsOneToOne field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueId  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
    /**
     * 存储DsTableAdapter内部一对多字段 List/Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsOneToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.joinProp() -> valueIds) 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部一对多字段 List/Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsOneToMany field) {
        try {
            // TODO file(root,field.joinClass().getName() +"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取一对多Ids.
            // TODO table.getId() -> key : field.joinProp()  -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
     /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsManyToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.id() -> valueIds) 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsManyToMany field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }
    
    /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return
     * @throws IOException 
     */
    public Long putTable(DsTableAdapter table,DsMapField field) throws IOException {
        // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter key)
       // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储List{key,value}
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List{key,value} 返回64位valueId
        //return add(key);
        return null;
       
    }
    
    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     * @param table
     * @param field
     * @return 
     */
    public DsTableAdapter getTable(DsTableAdapter table,DsMapField field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取List{key,value}
            // TODO table.getId() -> key : valueId -> List{key,value}  DsHashMap
           // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long keyId)
       // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long valueId)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 立即对指定表执行 Online DDL 兼容迁移。
     * 实际策略：把当前缓存的 TableMeta 清空，下次调用 createMeta() 会重新对比当前 sampleRowLength 与存储的 legacy rowLength。
     * 业务可在加字段后手动调这个按钮，或配合后续 putEntity/getTable 的懒加载自动回退 default 值机制。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void forceApplyOnlineDDL(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        tableMetaCache.remove(clazz);
        metaOf(clazz);
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 取消 Online DDL 状态。
     * 等价于把当前缓存的 TableMeta 从 tableMetaCache 中删除，下次 createMeta 会重新走 schema 对比流程。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void cancelOnlineDDL(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        tableMetaCache.remove(clazz);
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 回滚到上一个 schema 版本。
     * 当前实现等价于 cancelOnlineDDL（清空缓存重算），未来可拓展为切换 ids_<oldSchemaId>.set 的真实回滚指针。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void forceRollbackSchema(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        tableMetaCache.remove(clazz);
        metaOf(clazz);
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁，测试专用静态重置）。
     * 递归删除 indexes/ 根目录下所有 schema_*.dat 与 ids_*.set 文件（保留 ids.set legacy 不删，避免老数据全丢）。
     *
     * @param dbRoot DsDatabaseLocal 根目录
     */
    public static void forceResetSchemaMetaForTest(File dbRoot) {
        Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        File indexesDir = new File(dbRoot, "indexes");
        if (!indexesDir.exists() || !indexesDir.isDirectory()) {
            return;
        }
        deleteSchemaFilesRecursive(indexesDir);
    }

    private static void deleteSchemaFilesRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                deleteSchemaFilesRecursive(f);
                continue;
            }
            String name = f.getName();
            if (name.startsWith("ids_") && name.endsWith(".set")) {
                f.delete();
                continue;
            }
            if (name.startsWith("schema_") && name.endsWith(".dat")) {
                f.delete();
            }
        }
    }

}
