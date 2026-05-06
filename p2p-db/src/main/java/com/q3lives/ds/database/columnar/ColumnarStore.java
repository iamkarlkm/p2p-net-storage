package com.q3lives.ds.database.columnar;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.exception.meta.MetaDeletedColumnException;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ColumnarStore {
    private final File dbRoot;
    private final DsFixedBucketStore bucketStore;
    private final ColumnRegistry registry;
    private final TableMetaStore tableMetaStore;
    private final ConcurrentHashMap<String, DsHashMap> colMaps = new ConcurrentHashMap<>();

    public ColumnarStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.bucketStore = new DsFixedBucketStore(this.dbRoot.getAbsolutePath());
        this.registry = new ColumnRegistry(this.dbRoot);
        this.tableMetaStore = new TableMetaStore(this.dbRoot);
    }

    public static ColumnarStore load() {
        return new ColumnarStore(DsDatabaseLocal.load().getRoot());
    }

    public <T extends DsTableAdapter> long putValue(Class<T> entityClass, String logicalName, long rowId, byte[] valueBytes) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        if (valueBytes == null) {
            valueBytes = new byte[0];
        }

        tableMetaStore.ensureMeta(entityClass);

        String colKey = entityClass.getName() + "#" + logicalName;
        long colId = registry.getOrCreateColId(entityClass, colKey);
        if (registry.isDeleted(entityClass, colId)) {
            throw new MetaDeletedColumnException("column is deleted: " + colKey);
        }

        DsHashMap map = colMap(entityClass, colId);
        long oldValueId = map.getOrDefault(rowId, 0L);
        String type = valueType(colId);
        long newValueId;
        if (oldValueId == 0L) {
            newValueId = bucketStore.getNewId(entityClass.getName(), type, valueBytes.length);
            bucketStore.overwrite(entityClass.getName(), type, newValueId, valueBytes);
            map.put(rowId, newValueId);
            return newValueId;
        }

        // 关键点：利用 bucket.update 的语义。只有发生迁移（返回新 id）时才需要更新列 map 指针
        newValueId = bucketStore.update(entityClass.getName(), type, oldValueId, valueBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        if (newValueId != oldValueId) {
            map.put(rowId, newValueId);
        }
        return newValueId;
    }

    public <T extends DsTableAdapter> byte[] getValue(Class<T> entityClass, String logicalName, long rowId, int length) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (logicalName == null || logicalName.isBlank()) {
            return null;
        }
        if (rowId <= 0L) {
            return null;
        }
        if (length <= 0) {
            return new byte[0];
        }

        String colKey = entityClass.getName() + "#" + logicalName;
        Long colId = registry.findColId(entityClass, colKey);
        if (colId == null || colId <= 0L) {
            return null;
        }
        if (registry.isDeleted(entityClass, colId)) {
            return null;
        }
        DsHashMap map = colMap(entityClass, colId);
        long valueId = map.getOrDefault(rowId, 0L);
        if (valueId == 0L) {
            return null;
        }
        return bucketStore.get(entityClass.getName(), valueType(colId), valueId, 0, length);
    }

    public <T extends DsTableAdapter> long putCompositeGroup(Class<T> entityClass, String groupName, long rowId, byte[] valueBytes) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        if (valueBytes == null) {
            valueBytes = new byte[0];
        }

        TableMetaStore.TableMeta meta = tableMetaStore.ensureMeta(entityClass);
        TableMetaStore.CompositeGroup g = meta.compositeGroups.get(groupName);
        if (g == null) {
            throw new IllegalArgumentException("unknown composite group: " + groupName);
        }
        if (valueBytes.length > g.length) {
            throw new IllegalArgumentException("valueBytes overflow group length: " + valueBytes.length + " > " + g.length);
        }
        if (registry.isDeleted(entityClass, g.colId)) {
            throw new MetaDeletedColumnException("column is deleted: " + g.colKey);
        }

        DsHashMap map = colMap(entityClass, g.colId);
        long oldValueId = map.getOrDefault(rowId, 0L);
        String type = valueType(g.colId);
        long newValueId;
        if (oldValueId == 0L) {
            newValueId = bucketStore.getNewId(entityClass.getName(), type, g.length);
            bucketStore.overwrite(entityClass.getName(), type, newValueId, valueBytes);
            map.put(rowId, newValueId);
            return newValueId;
        }
        newValueId = bucketStore.update(entityClass.getName(), type, oldValueId, valueBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        if (newValueId != oldValueId) {
            map.put(rowId, newValueId);
        }
        return newValueId;
    }

    public <T extends DsTableAdapter> byte[] getCompositeGroup(Class<T> entityClass, String groupName, long rowId) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            return null;
        }
        if (rowId <= 0L) {
            return null;
        }

        TableMetaStore.TableMeta meta = tableMetaStore.ensureMeta(entityClass);
        TableMetaStore.CompositeGroup g = meta.compositeGroups.get(groupName);
        if (g == null) {
            return null;
        }
        if (registry.isDeleted(entityClass, g.colId)) {
            return null;
        }

        DsHashMap map = colMap(entityClass, g.colId);
        long valueId = map.getOrDefault(rowId, 0L);
        if (valueId == 0L) {
            return null;
        }
        return bucketStore.get(entityClass.getName(), valueType(g.colId), valueId, 0, g.length);
    }

    public <T extends DsTableAdapter> void deleteColumnHard(Class<T> entityClass, String logicalName, int batchSize) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        if (batchSize <= 0) {
            batchSize = 1024;
        }

        tableMetaStore.ensureMeta(entityClass);

        String colKey = entityClass.getName() + "#" + logicalName;
        Long colId = registry.findColId(entityClass, colKey);
        if (colId == null || colId <= 0L) {
            return;
        }

        DsHashMap map = colMap(entityClass, colId);
        String type = valueType(colId);

        while (true) {
            long[] kv = new long[batchSize * 2];
            int[] emitted = new int[] {0};
            int got = map.forEachRange(0, batchSize, (k, v) -> {
                int idx = emitted[0] * 2;
                kv[idx] = k;
                kv[idx + 1] = v;
                emitted[0]++;
            });
            if (got <= 0) {
                break;
            }
            for (int i = 0; i < got; i++) {
                long rowId = kv[i * 2];
                long valueId = kv[i * 2 + 1];
                if (valueId != 0L) {
                    bucketStore.remove(entityClass.getName(), type, valueId);
                }
                map.remove(rowId);
            }
        }

        // 删除列：先标记 deleted，避免后续写入；再做 best-effort 删除 map 文件（Windows 下 mmap 可能导致删除失败）
        registry.markDeleted(entityClass, colId);
        map.clear();
        deleteColumnMapFiles(entityClass, colId);
        colMaps.remove(colMapCacheKey(entityClass, colId));
    }

    private <T extends DsTableAdapter> DsHashMap colMap(Class<T> entityClass, long colId) {
        String key = colMapCacheKey(entityClass, colId);
        return colMaps.computeIfAbsent(key, k -> new DsHashMap(colMapFile(entityClass, colId)));
    }

    private static String valueType(long colId) {
        return "col_" + colId;
    }

    private static <T extends DsTableAdapter> String colMapCacheKey(Class<T> entityClass, long colId) {
        return entityClass.getName() + ":" + colId;
    }

    private <T extends DsTableAdapter> File colMapFile(Class<T> entityClass, long colId) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClass.getName(), "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath + "/cols");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, colId + ".map");
    }

    private <T extends DsTableAdapter> void deleteColumnMapFiles(Class<T> entityClass, long colId) {
        File base = colMapFile(entityClass, colId);
        DsHashMapFiles.deleteAll(base);
    }
}
