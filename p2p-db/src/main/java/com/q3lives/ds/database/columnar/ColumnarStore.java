package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsFixedValueStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashMapInline;
import com.q3lives.ds.database.DsDatabaseLocal;
import com.q3lives.ds.database.DsTableLayout;
import com.q3lives.ds.database.DsValueResolver;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.exception.meta.MetaDeletedColumnException;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier;
import com.q3lives.ds.registry.meta.AbstractRegistryDataBackend;
import com.q3lives.ds.registry.meta.AbstractRegistryReplicator;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;

public final class ColumnarStore implements AutoCloseable {
    private final File dbRoot;
    private final DsFixedBucketStore bucketStore;
    private final ColumnRegistry registry;
    private final TableMetaStore tableMetaStore;
    /** P11 OQ1：单列 valueBytes 额外双写 Registry /orm/data/column 子树；YAML 模式下 =null 零开销 */
    private final ColumnarColumnDataBackend columnDataBackend;
    /** P11 OQ1：FieldGroup + CompositeGroup 打包 bytes 双写 Registry /orm/data/group 子树；YAML =null */
    private final ColumnarGroupDataBackend groupDataBackend;
    /** P12 OQ2：跨节点列/组同步 Replicator。YAML/sync=off → null 短路零 new；package-private 便于同包测试取 frameQueue */
    final AbstractRegistryReplicator registryReplicator;
    /** P12 OQ2：列 replicate 写版本号（单调递增保证 applyRegistryFrame CAS version 乱序高赢） */
    final AtomicLong colWriteVersion = new AtomicLong(1);
    private final ConcurrentHashMap<String, DsHashMap> colMaps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsFixedValueStore> colValueStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsColumnGroupStore> groupStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsTableLayout> layouts = new ConcurrentHashMap<>();
    private volatile int compressionThreshold = 64;

    /**
     * 变长列自定义头部：非压缩数据包装，避免与 {@link ColumnCompression} 的 LZ 魔数冲突。
     * 格式：[2B magic = 0x00 0x56][1B version = 1][4B originalLen][data]
     */
    private static final byte[] VARIABLE_UNCOMPRESSED_MAGIC = {0x00, 0x56};
    private static final byte VARIABLE_VERSION = 1;
    private static final int VARIABLE_HEADER_SIZE = 7;

    public ColumnarStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.bucketStore = new DsFixedBucketStore(this.dbRoot.getAbsolutePath());
        this.registry = new ColumnRegistry(this.dbRoot);
        this.tableMetaStore = new TableMetaStore(this.dbRoot);
        ColumnarColumnDataBackend cdb = null;
        ColumnarGroupDataBackend gdb = null;
        AbstractRegistryReplicator rr = null;
        if (MetaMode.global() != MetaMode.YAML) {
            try {
                cdb = new ColumnarColumnDataBackend(this.dbRoot);
            } catch (Exception e) {
                cdb = null;
                System.err.println("[ColumnarStore] WARN 初始化 ColumnDataBackend 失败（保持仅 bucket 基线，已与原行为一致）: " + e.getMessage());
            }
            try {
                gdb = new ColumnarGroupDataBackend(this.dbRoot);
            } catch (Exception e) {
                gdb = null;
                System.err.println("[ColumnarStore] WARN 初始化 GroupDataBackend 失败（保持仅 bucket 基线，已与原行为一致）: " + e.getMessage());
            }
            try {
                rr = AbstractRegistryReplicator.create(this.dbRoot, MetaMode.global());
            } catch (Exception e) {
                rr = null;
                System.err.println("[ColumnarStore] WARN 初始化 AbstractRegistryReplicator 失败（sync=off，降级为单节点模式，不影响正确性）: " + e.getMessage());
            }
        }
        this.columnDataBackend = cdb;
        this.groupDataBackend = gdb;
        this.registryReplicator = rr;
    }

    public File getDbRoot() {
        return dbRoot;
    }

    public static ColumnarStore load() {
        return new ColumnarStore(DsDatabaseLocal.load().getRoot());
    }

    DsFixedBucketStore getBucketStore() {
        return bucketStore;
    }

    TableMetaStore getTableMetaStore() {
        return tableMetaStore;
    }

    public int getCompressionThreshold() {
        return compressionThreshold;
    }

    public void setCompressionThreshold(int compressionThreshold) {
        if (compressionThreshold < 0) {
            throw new IllegalArgumentException("compressionThreshold must be >= 0");
        }
        this.compressionThreshold = compressionThreshold;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (DsHashMap map : colMaps.values()) {
            try {
                map.close();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = new IOException(e);
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        colMaps.clear();
        for (DsFixedValueStore store : colValueStores.values()) {
            try {
                store.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        colValueStores.clear();
        for (DsColumnGroupStore store : groupStores.values()) {
            try {
                store.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        groupStores.clear();
        try {
            bucketStore.close();
        } catch (IOException e) {
            if (first == null) {
                first = e;
            } else {
                first.addSuppressed(e);
            }
        }
        if (first != null) {
            throw first;
        }
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
        DsTableLayout.ColumnLayout col = layoutColumn(entityClass.getName(), logicalName);
        if (col == null) {
            throw new IllegalArgumentException("unknown column: " + logicalName);
        }
        final int fixedLen = col.getLength();
        byte[] padded = valueBytes;
        if (fixedLen > 0 && valueBytes.length < fixedLen) {
            padded = Arrays.copyOf(valueBytes, fixedLen);
        }
        long valueId = putColumnValue(entityClass.getName(), col, rowId, padded);
        if (columnDataBackend != null) {
            columnDataBackend.writeColumn(entityClass.getName(), logicalName, rowId, padded);
        }
        if (registryReplicator != null) {
            long v = colWriteVersion.incrementAndGet();
            String p = RegistryMetaPaths.dataColumnPayload(entityClass.getName(), logicalName, rowId);
            registryReplicator.replicateKey(p, padded, v, new long[]{0L});
        }
        return valueId;
    }

    public long putValue(String entityClassName, String logicalName, long rowId, byte[] valueBytes) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        valueBytes = normalizeBytes(valueBytes);

        long valueId;
        DsTableLayout.ColumnLayout col = layoutColumn(entityClassName, logicalName);
        byte[] padded = valueBytes;
        if (col != null) {
            final int fixedLen = col.getLength();
            if (fixedLen > 0 && valueBytes.length < fixedLen) {
                padded = Arrays.copyOf(valueBytes, fixedLen);
            }
            valueId = putColumnValue(entityClassName, col, rowId, padded);
        } else {
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityClassName);
            ResolvedColumn c = resolveColumn(entityClassName, logicalName, meta);
            if (registry.isDeleted(entityClassName, c.colId)) {
                throw new MetaDeletedColumnException("column is deleted: " + c.colKey);
            }
            if (c.length > 0 && valueBytes.length < c.length) {
                padded = Arrays.copyOf(valueBytes, c.length);
            }
            valueId = putBucketColumn(entityClassName, c, rowId, padded);
        }
        if (columnDataBackend != null) {
            columnDataBackend.writeColumn(entityClassName, logicalName, rowId, padded);
        }
        if (registryReplicator != null) {
            long v = colWriteVersion.incrementAndGet();
            String p = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
            registryReplicator.replicateKey(p, padded, v, new long[]{0L});
        }
        return valueId;
    }

    public <T extends DsTableAdapter> byte[] getValue(Class<T> entityClass, String logicalName, long rowId) throws IOException {
        return getValue(entityClass, logicalName, rowId, 0);
    }

    public <T extends DsTableAdapter> byte[] getValue(Class<T> entityClass, String logicalName, long rowId, int length) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (logicalName == null || logicalName.isBlank()) return null;
        if (rowId <= 0L) return null;
        final String entityName = entityClass.getName();
        final int readLen = length;
        DsTableLayout.ColumnLayout cPre = layoutColumn(entityName, logicalName);
        if (cPre == null) {
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityName);
            ResolvedColumn rc = resolveColumn(entityName, logicalName, meta);
            if (rc == null) return null;
            if (registry.isDeleted(entityName, rc.colId)) return null;
        }
        YamlSupplier<byte[]> orig = () -> {
            DsTableLayout.ColumnLayout c = layoutColumn(entityName, logicalName);
            if (c != null) return getColumnValue(entityName, c, rowId);
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityName);
            ResolvedColumn rc = resolveColumn(entityName, logicalName, meta);
            if (rc == null) return null;
            if (registry.isDeleted(entityName, rc.colId)) return null;
            int len = (readLen > 0) ? readLen : rc.length;
            return getBucketColumnValue(entityName, rc, rowId, len);
        };
        if (columnDataBackend == null) {
            try { return orig.get(); } catch (RuntimeException r) {
                Throwable c = r.getCause() != null ? r.getCause() : r;
                if (c instanceof IOException) throw (IOException) c;
                throw r;
            }
        }
        return columnDataBackend.readColumn(entityName, logicalName, rowId, orig);
    }

    public byte[] getValue(String entityClassName, String logicalName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) return null;
        if (rowId <= 0L) return null;
        final String entityName = entityClassName;
        final String colName = logicalName;
        final long rId = rowId;
        DsTableLayout.ColumnLayout cPre = layoutColumn(entityName, colName);
        if (cPre == null) {
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityName);
            ResolvedColumn rc = resolveColumn(entityName, colName, meta);
            if (rc == null) return null;
            if (registry.isDeleted(entityName, rc.colId)) return null;
        }
        YamlSupplier<byte[]> orig = () -> {
            DsTableLayout.ColumnLayout c = layoutColumn(entityName, colName);
            if (c != null) return getColumnValue(entityName, c, rId);
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityName);
            ResolvedColumn rc = resolveColumn(entityName, colName, meta);
            if (rc == null) return null;
            if (registry.isDeleted(entityName, rc.colId)) return null;
            return getBucketColumnValue(entityName, rc, rId, rc.length);
        };
        if (columnDataBackend == null) {
            try { return orig.get(); } catch (RuntimeException r) {
                Throwable c = r.getCause() != null ? r.getCause() : r;
                if (c instanceof IOException) throw (IOException) c;
                throw r;
            }
        }
        return columnDataBackend.readColumn(entityName, logicalName, rowId, orig);
    }

    public boolean removeValue(String entityClassName, String logicalName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }

        DsTableLayout.ColumnLayout col = layoutColumn(entityClassName, logicalName);
        boolean removed;
        if (col != null) {
            removed = removeColumnValue(entityClassName, col, rowId);
        } else {
            TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityClassName);
            ResolvedColumn c = resolveColumn(entityClassName, logicalName, meta);
            if (registry.isDeleted(entityClassName, c.colId)) {
                removed = false;
            } else {
                removed = removeBucketColumnValue(entityClassName, c.colId, rowId);
            }
        }
        if (removed && columnDataBackend != null) {
            columnDataBackend.removeColumn(entityClassName, logicalName, rowId);
        }
        if (removed && registryReplicator != null) {
            long v = colWriteVersion.incrementAndGet();
            String p = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
            registryReplicator.replicateKey(p, new byte[0], v, new long[]{0L});
        }
        return removed;
    }

    public boolean removeRow(String entityClassName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }

        DsTableLayout layout = layout(entityClassName);
        boolean removedAny = false;
        for (DsTableLayout.ColumnLayout col : layout.getColumns().values()) {
            if (col.isInGroup()) {
                continue;
            }
            if (registry.isDeleted(entityClassName, registry.getOrCreateColId(entityClassName, entityClassName + "#" + col.getLogicalName()))) {
                continue;
            }
            boolean r = removeColumnValue(entityClassName, col, rowId);
            removedAny |= r;
            if (r && columnDataBackend != null) {
                columnDataBackend.removeColumn(entityClassName, col.getLogicalName(), rowId);
            }
        }
        for (DsTableLayout.FieldGroupDef group : layout.getFieldGroups()) {
            boolean r = groupStore(entityClassName, group).remove(rowId);
            removedAny |= r;
            if (r && groupDataBackend != null) {
                groupDataBackend.removeGroup(entityClassName, group.getGroupName(), rowId);
            }
        }

        TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityClassName);
        if (meta.columns != null) {
            for (TableMetaStore.ColumnDef c : meta.columns) {
                if (c == null || c.colId <= 0L) {
                    continue;
                }
                DsTableLayout.ColumnLayout lc = layout.getColumn(c.name);
                if (lc != null && lc.isInGroup()) {
                    continue;
                }
                if (registry.isDeleted(entityClassName, c.colId)) {
                    continue;
                }
                removedAny |= removeValueByColId(entityClassName, c.colId, rowId);
                if (columnDataBackend != null && c.name != null && !c.name.isBlank()) {
                    // 额外尝试清 Registry（即使 removeValueByColId 因 colId 查不到 logicalName 也不影响：重复 remove 幂等）
                    columnDataBackend.removeColumn(entityClassName, c.name, rowId);
                }
            }
        }
        if (meta.compositeGroups != null) {
            for (TableMetaStore.CompositeGroup g : meta.compositeGroups.values()) {
                if (g == null || g.colId <= 0L) {
                    continue;
                }
                if (registry.isDeleted(entityClassName, g.colId)) {
                    continue;
                }
                removedAny |= removeValueByColId(entityClassName, g.colId, rowId);
                if (groupDataBackend != null && g.group != null && !g.group.isBlank()) {
                    groupDataBackend.removeGroup(entityClassName, g.group, rowId);
                }
            }
        }
        return removedAny;
    }

    public <T extends DsTableAdapter> long putFieldGroup(Class<T> entityClass, String groupName, long rowId, byte[] groupBytes) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        if (groupBytes == null) {
            groupBytes = new byte[0];
        }
        long id = putFieldGroup(entityClass.getName(), groupName, rowId, groupBytes);
        return id;
    }

    public long putFieldGroup(String entityClassName, String groupName, long rowId, byte[] groupBytes) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        if (groupBytes == null) {
            groupBytes = new byte[0];
        }
        DsTableLayout.FieldGroupDef group = groupDef(entityClassName, groupName);
        long valueId = groupStore(entityClassName, group).put(rowId, groupBytes);
        if (groupDataBackend != null) {
            groupDataBackend.writeGroup(entityClassName, groupName, rowId, groupBytes);
        }
        if (registryReplicator != null) {
            long v = colWriteVersion.incrementAndGet();
            String p = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
            registryReplicator.replicateKey(p, groupBytes, v, new long[]{0L});
        }
        return valueId;
    }

    public <T extends DsTableAdapter> byte[] getFieldGroup(Class<T> entityClass, String groupName, long rowId) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            return null;
        }
        if (rowId <= 0L) {
            return null;
        }
        return getFieldGroup(entityClass.getName(), groupName, rowId);
    }

    public byte[] getFieldGroup(String entityClassName, String groupName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) return null;
        if (groupName == null || groupName.isBlank()) return null;
        if (rowId <= 0L) return null;
        final String eName = entityClassName;
        final String gName = groupName;
        final long rId = rowId;
        YamlSupplier<byte[]> orig = () -> {
            DsTableLayout.FieldGroupDef group = groupDef(eName, gName);
            return groupStore(eName, group).get(rId);
        };
        if (groupDataBackend == null) {
            try { return orig.get(); } catch (RuntimeException r) {
                Throwable c = r.getCause() != null ? r.getCause() : r;
                if (c instanceof IOException) throw (IOException) c;
                throw r;
            }
        }
        return groupDataBackend.readGroup(entityClassName, groupName, rowId, orig);
    }

    public <T extends DsTableAdapter> boolean removeFieldGroup(Class<T> entityClass, String groupName, long rowId) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        return removeFieldGroup(entityClass.getName(), groupName, rowId);
    }

    public boolean removeFieldGroup(String entityClassName, String groupName, long rowId) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("rowId must be > 0");
        }
        DsTableLayout.FieldGroupDef group = groupDef(entityClassName, groupName);
        boolean removed = groupStore(entityClassName, group).remove(rowId);
        if (removed && groupDataBackend != null) {
            groupDataBackend.removeGroup(entityClassName, groupName, rowId);
        }
        return removed;
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
        byte[] padded = valueBytes;
        if (g.length > 0 && valueBytes.length < g.length) {
            padded = Arrays.copyOf(valueBytes, g.length);
        }

        DsHashMap map = colMap(entityClass, g.colId);
        long oldValueId = map.getOrDefault(rowId, 0L);
        String type = valueType(g.colId);
        byte[] storedBytes = maybeCompress(padded);
        long newValueId;
        if (oldValueId == 0L) {
            newValueId = bucketStore.getNewId(entityClass.getName(), type, g.length);
            bucketStore.overwrite(entityClass.getName(), type, newValueId, storedBytes);
            map.put(rowId, newValueId);
        } else {
            newValueId = bucketStore.update(entityClass.getName(), type, oldValueId, storedBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
            if (newValueId != oldValueId) {
                map.put(rowId, newValueId);
            }
        }
        if (groupDataBackend != null) {
            groupDataBackend.writeGroup(entityClass.getName(), groupName, rowId, padded);
        }
        if (registryReplicator != null) {
            long v = colWriteVersion.incrementAndGet();
            String p = RegistryMetaPaths.dataGroupPayload(entityClass.getName(), groupName, rowId);
            registryReplicator.replicateKey(p, padded, v, new long[]{0L});
        }
        return newValueId;
    }

    public <T extends DsTableAdapter> byte[] getCompositeGroup(Class<T> entityClass, String groupName, long rowId) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) return null;
        if (rowId <= 0L) return null;
        final Class<T> eClass = entityClass;
        final String gName = groupName;
        final long rId = rowId;
        TableMetaStore.TableMeta metaPre = tableMetaStore.ensureMeta(eClass);
        TableMetaStore.CompositeGroup gPre = metaPre.compositeGroups.get(gName);
        if (gPre == null) return null;
        if (registry.isDeleted(eClass, gPre.colId)) return null;
        YamlSupplier<byte[]> orig = () -> {
            try {
                TableMetaStore.TableMeta meta = tableMetaStore.ensureMeta(eClass);
                TableMetaStore.CompositeGroup g = meta.compositeGroups.get(gName);
                if (g == null) return null;
                if (registry.isDeleted(eClass, g.colId)) return null;
                DsHashMap map = colMap(eClass, g.colId);
                long valueId = map.getOrDefault(rId, 0L);
                if (valueId == 0L) return null;
                byte[] raw = readAndMaybeDecompress(eClass.getName(), valueId, valueType(g.colId), g.length);
                if (raw == null) return null;
                if (g.length >= raw.length) return raw;
                byte[] out = new byte[g.length];
                System.arraycopy(raw, 0, out, 0, g.length);
                return out;
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        };
        if (groupDataBackend == null) {
            try { return orig.get(); } catch (RuntimeException r) {
                Throwable c = r.getCause() != null ? r.getCause() : r;
                if (c instanceof IOException) throw (IOException) c;
                throw r;
            }
        }
        return groupDataBackend.readGroup(entityClass.getName(), groupName, rowId, orig);
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
        String entityClassName = entityClass.getName();
        DsTableLayout.ColumnLayout col = layoutColumn(entityClassName, logicalName);
        if (col != null && col.isInGroup()) {
            throw new IllegalArgumentException("cannot delete group member column individually: " + logicalName);
        }

        String colKey = entityClassName + "#" + logicalName;
        Long colId = registry.findColId(entityClassName, colKey);
        if (colId == null || colId <= 0L) {
            return;
        }

        DsHashMap map = colMap(entityClassName, colId);
        String type = valueType(colId);
        DsValueResolver.StorageType storageType = col != null ? col.getStorageType() : storageTypeForLength(colId, entityClassName, logicalName);
        DsFixedValueStore valueStore = null;
        if (storageType == DsValueResolver.StorageType.FIXED_VALUE_STORE) {
            int recordSize = col != null ? col.getLength() : fallbackLength(entityClassName, logicalName);
            valueStore = colValueStore(entityClassName, colId, recordSize);
        }

        while (true) {
            long[] kv = new long[batchSize * 2];
            int[] emitted = new int[]{0};
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
                    if (storageType == DsValueResolver.StorageType.VARIABLE_BUCKET) {
                        bucketStore.remove(entityClassName, type, valueId);
                    } else if (storageType == DsValueResolver.StorageType.FIXED_VALUE_STORE && valueStore != null) {
                        valueStore.recycle(valueId);
                    }
                }
                map.remove(rowId);
                if (columnDataBackend != null) {
                    columnDataBackend.removeColumn(entityClassName, logicalName, rowId);
                }
            }
        }

        registry.markDeleted(entityClassName, colId);
        map.clear();
        deleteColumnMapFiles(entityClassName, colId);
        colMaps.remove(colMapCacheKey(entityClassName, colId));
        if (valueStore != null) {
            valueStore.close();
            colValueStores.remove(colValueStoreCacheKey(entityClassName, colId));
            deleteValueStoreFiles(colMapFile(entityClassName, colId));
        }
    }

    private DsTableLayout layout(String entityClassName) {
        return layouts.computeIfAbsent(entityClassName, k -> {
            try {
                Class<?> clazz = Class.forName(entityClassName);
                return new DsTableLayout(clazz);
            } catch (ClassNotFoundException e) {
                throw new IllegalArgumentException("entity class not found: " + entityClassName, e);
            }
        });
    }

    private DsTableLayout.ColumnLayout layoutColumn(String entityClassName, String logicalName) {
        if (logicalName.startsWith("@composite:")) {
            return null;
        }
        return layout(entityClassName).getColumn(logicalName);
    }

    private DsTableLayout.FieldGroupDef groupDef(String entityClassName, String groupName) {
        DsTableLayout.FieldGroupDef group = layout(entityClassName).getFieldGroup(groupName);
        if (group == null) {
            throw new IllegalArgumentException("unknown field group: " + groupName);
        }
        return group;
    }

    private long putColumnValue(String entityClassName, DsTableLayout.ColumnLayout col, long rowId, byte[] valueBytes) throws IOException {
        if (col.isInGroup()) {
            throw new IllegalArgumentException("column belongs to a field group, use putFieldGroup: " + col.getLogicalName());
        }
        String colKey = entityClassName + "#" + col.getLogicalName();
        long colId = registry.getOrCreateColId(entityClassName, colKey);
        if (registry.isDeleted(entityClassName, colId)) {
            throw new MetaDeletedColumnException("column is deleted: " + colKey);
        }
        validateValueLength(col.getLogicalName(), valueBytes, col.getLength());
        if (col.getLength() > 0 && valueBytes.length < col.getLength()) {
            valueBytes = padToLength(valueBytes, col.getLength());
        }

        switch (col.getStorageType()) {
            case INLINE:
                return putInline(entityClassName, colId, rowId, valueBytes);
            case FIXED_VALUE_STORE:
                return putFixedValueStore(entityClassName, colId, col.getLength(), rowId, valueBytes);
            case VARIABLE_BUCKET:
                return putVariableBucket(entityClassName, colId, rowId, valueBytes);
            default:
                throw new IllegalStateException("unsupported storage type: " + col.getStorageType());
        }
    }

    private long putInline(String entityClassName, long colId, long rowId, byte[] valueBytes) throws IOException {
        DsHashMap map = colMap(entityClassName, colId);
        long encoded = DsHashMapInline.encode(valueBytes);
        map.put(rowId, encoded);
        return encoded;
    }

    private long putFixedValueStore(String entityClassName, long colId, int recordSize, long rowId, byte[] valueBytes) throws IOException {
        DsHashMap map = colMap(entityClassName, colId);
        DsFixedValueStore valueStore = colValueStore(entityClassName, colId, recordSize);
        Long oldRecordId = map.get(rowId);
        long recordId = (oldRecordId == null || oldRecordId == 0L) ? valueStore.allocateRecord() : oldRecordId;
        valueStore.write(recordId, valueBytes);
        if (oldRecordId == null || oldRecordId == 0L) {
            map.put(rowId, recordId);
        }
        return recordId;
    }

    private long putVariableBucket(String entityClassName, long colId, long rowId, byte[] valueBytes) throws IOException {
        DsHashMap map = colMap(entityClassName, colId);
        String type = valueType(colId);
        byte[] storedBytes = wrapVariable(valueBytes);
        Long oldValueId = map.get(rowId);
        long newValueId;
        if (oldValueId == null || oldValueId == 0L) {
            newValueId = bucketStore.getNewId(entityClassName, type, storedBytes.length);
            bucketStore.overwrite(entityClassName, type, newValueId, storedBytes);
            map.put(rowId, newValueId);
            return newValueId;
        }
        newValueId = bucketStore.update(entityClassName, type, oldValueId, storedBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        if (newValueId != oldValueId) {
            map.put(rowId, newValueId);
        }
        return newValueId;
    }

    private byte[] getColumnValue(String entityClassName, DsTableLayout.ColumnLayout col, long rowId) throws IOException {
        if (col.isInGroup()) {
            DsColumnGroupStore store = groupStore(entityClassName, col.getGroupName(), col.getGroupSize());
            return store.getColumn(rowId, col.getGroupOffset(), col.getLength());
        }
        String colKey = entityClassName + "#" + col.getLogicalName();
        Long colId = registry.findColId(entityClassName, colKey);
        if (colId == null || colId <= 0L) {
            return null;
        }
        if (registry.isDeleted(entityClassName, colId)) {
            return null;
        }
        DsHashMap map = colMap(entityClassName, colId);
        Long value = map.get(rowId);
        if (value == null) {
            return null;
        }
        switch (col.getStorageType()) {
            case INLINE:
                return DsHashMapInline.decode(value, col.getLength());
            case FIXED_VALUE_STORE:
                DsFixedValueStore valueStore = colValueStore(entityClassName, colId, col.getLength());
                return valueStore.read(value);
            case VARIABLE_BUCKET:
                return readVariableBucket(entityClassName, colId, value);
            default:
                throw new IllegalStateException("unsupported storage type: " + col.getStorageType());
        }
    }

    private boolean removeColumnValue(String entityClassName, DsTableLayout.ColumnLayout col, long rowId) throws IOException {
        if (col.isInGroup()) {
            throw new IllegalArgumentException("cannot remove group member column individually: " + col.getLogicalName());
        }
        String colKey = entityClassName + "#" + col.getLogicalName();
        Long colId = registry.findColId(entityClassName, colKey);
        if (colId == null || colId <= 0L) {
            return false;
        }
        if (registry.isDeleted(entityClassName, colId)) {
            return false;
        }
        DsHashMap map = colMap(entityClassName, colId);
        Long value = map.get(rowId);
        if (value == null) {
            return false;
        }
        switch (col.getStorageType()) {
            case INLINE:
                map.remove(rowId);
                return true;
            case FIXED_VALUE_STORE:
                DsFixedValueStore valueStore = colValueStore(entityClassName, colId, col.getLength());
                valueStore.recycle(value);
                map.remove(rowId);
                return true;
            case VARIABLE_BUCKET:
                bucketStore.remove(entityClassName, valueType(colId), value);
                map.remove(rowId);
                return true;
            default:
                throw new IllegalStateException("unsupported storage type: " + col.getStorageType());
        }
    }

    private long putBucketColumn(String entityClassName, ResolvedColumn c, long rowId, byte[] valueBytes) throws IOException {
        validateValueLength(c.colKey, valueBytes, c.length);
        if (c.length > 0 && valueBytes.length < c.length) {
            byte[] padded = new byte[c.length];
            System.arraycopy(valueBytes, 0, padded, 0, valueBytes.length);
            valueBytes = padded;
        }
        DsHashMap map = colMap(entityClassName, c.colId);
        long oldValueId = map.getOrDefault(rowId, 0L);
        String type = valueType(c.colId);
        byte[] storedBytes = maybeCompress(valueBytes);
        long newValueId;
        if (oldValueId == 0L) {
            int allocLen = c.length > 0 ? c.length : storedBytes.length;
            newValueId = bucketStore.getNewId(entityClassName, type, allocLen);
            bucketStore.overwrite(entityClassName, type, newValueId, storedBytes);
            map.put(rowId, newValueId);
            return newValueId;
        }
        newValueId = bucketStore.update(entityClassName, type, oldValueId, storedBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        if (newValueId != oldValueId) {
            map.put(rowId, newValueId);
        }
        return newValueId;
    }

    private byte[] getBucketColumnValue(String entityClassName, ResolvedColumn c, long rowId, int length) throws IOException {
        DsHashMap map = colMap(entityClassName, c.colId);
        long valueId = map.getOrDefault(rowId, 0L);
        if (valueId == 0L) {
            return null;
        }
        int len = length > 0 ? length : c.length;
        if (len <= 0) {
            return new byte[0];
        }
        byte[] raw = readAndMaybeDecompress(entityClassName, valueId, valueType(c.colId), len);
        if (raw == null) {
            return null;
        }
        if (len >= raw.length) {
            return raw;
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, 0, len);
        return out;
    }

    private boolean removeBucketColumnValue(String entityClassName, long colId, long rowId) throws IOException {
        DsHashMap map = colMap(entityClassName, colId);
        long valueId = map.getOrDefault(rowId, 0L);
        if (valueId == 0L) {
            return false;
        }
        bucketStore.remove(entityClassName, valueType(colId), valueId);
        map.remove(rowId);
        return true;
    }

    private boolean removeValueByColId(String entityClassName, long colId, long rowId) throws IOException {
        DsHashMap map = colMap(entityClassName, colId);
        long valueId = map.getOrDefault(rowId, 0L);
        if (valueId == 0L) {
            return false;
        }
        bucketStore.remove(entityClassName, valueType(colId), valueId);
        map.remove(rowId);
        return true;
    }

    private DsFixedValueStore colValueStore(String entityClassName, long colId, int recordSize) {
        String key = colValueStoreCacheKey(entityClassName, colId);
        return colValueStores.computeIfAbsent(key, k -> {
            try {
                return new DsFixedValueStore(new File(colMapFile(entityClassName, colId).getAbsolutePath() + ".values"), recordSize);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private DsColumnGroupStore groupStore(String entityClassName, DsTableLayout.FieldGroupDef group) {
        return groupStore(entityClassName, group.getGroupName(), group.getTotalSize());
    }

    private DsColumnGroupStore groupStore(String entityClassName, String groupName, int groupSize) {
        String key = groupStoreCacheKey(entityClassName, groupName);
        return groupStores.computeIfAbsent(key, k -> {
            try {
                return new DsColumnGroupStore(groupMapFile(entityClassName, groupName), groupSize);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private <T extends DsTableAdapter> DsHashMap colMap(Class<T> entityClass, long colId) {
        return colMap(entityClass.getName(), colId);
    }

    private DsHashMap colMap(String entityClassName, long colId) {
        String key = colMapCacheKey(entityClassName, colId);
        return colMaps.computeIfAbsent(key, k -> new DsHashMap(colMapFile(entityClassName, colId)));
    }

    private static String valueType(long colId) {
        return "col_" + colId;
    }

    private static <T extends DsTableAdapter> String colMapCacheKey(Class<T> entityClass, long colId) {
        return colMapCacheKey(entityClass.getName(), colId);
    }

    private static String colMapCacheKey(String entityClassName, long colId) {
        return entityClassName + ":" + colId;
    }

    private static String colValueStoreCacheKey(String entityClassName, long colId) {
        return entityClassName + ":" + colId + ":values";
    }

    private static String groupStoreCacheKey(String entityClassName, String groupName) {
        return entityClassName + "#group:" + groupName;
    }

    private <T extends DsTableAdapter> File colMapFile(Class<T> entityClass, long colId) {
        return colMapFile(entityClass.getName(), colId);
    }

    private File colMapFile(String entityClassName, long colId) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath + "/cols");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, colId + ".map");
    }

    private File groupMapFile(String entityClassName, String groupName) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath + "/groups");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, groupName + ".map");
    }

    private <T extends DsTableAdapter> void deleteColumnMapFiles(Class<T> entityClass, long colId) {
        File base = colMapFile(entityClass, colId);
        DsHashMapFiles.deleteAll(base);
    }

    private void deleteColumnMapFiles(String entityClassName, long colId) {
        File base = colMapFile(entityClassName, colId);
        DsHashMapFiles.deleteAll(base);
    }

    private void deleteValueStoreFiles(File colMapBase) {
        if (colMapBase == null) {
            return;
        }
        File values = new File(colMapBase.getAbsolutePath() + ".values");
        File valuesFree = new File(values.getAbsolutePath() + ".free");
        values.delete();
        valuesFree.delete();
    }

    private static byte[] normalizeBytes(byte[] valueBytes) {
        return valueBytes == null ? new byte[0] : valueBytes;
    }

    private static byte[] padToLength(byte[] valueBytes, int length) {
        byte[] padded = new byte[length];
        System.arraycopy(valueBytes, 0, padded, 0, Math.min(valueBytes.length, length));
        return padded;
    }

    private static void validateValueLength(String logicalName, byte[] valueBytes, int expectedLen) {
        if (expectedLen <= 0) {
            return;
        }
        if (valueBytes.length > expectedLen) {
            throw new IllegalArgumentException("valueBytes overflow column length: name=" + logicalName + ", " + valueBytes.length + " > " + expectedLen);
        }
    }

    private static ResolvedColumn resolveColumn(String entityClassName, String logicalName, TableMetaStore.TableMeta meta) {
        if (meta == null) {
            throw new IllegalArgumentException("missing table meta: entityClass=" + entityClassName);
        }
        if (logicalName.startsWith("@composite:")) {
            String groupName = logicalName.substring("@composite:".length());
            if (meta.compositeGroups == null) {
                throw new IllegalArgumentException("unknown composite group: " + groupName);
            }
            TableMetaStore.CompositeGroup g = meta.compositeGroups.get(groupName);
            if (g == null) {
                throw new IllegalArgumentException("unknown composite group: " + groupName);
            }
            return new ResolvedColumn(g.colId, g.length, g.colKey);
        }
        if (meta.columns != null) {
            for (TableMetaStore.ColumnDef c : meta.columns) {
                if (c == null) {
                    continue;
                }
                if (logicalName.equals(c.name)) {
                    return new ResolvedColumn(c.colId, c.length, c.colKey);
                }
            }
        }
        throw new IllegalArgumentException("unknown column: " + logicalName);
    }

    private static final class ResolvedColumn {
        final long colId;
        final int length;
        final String colKey;

        ResolvedColumn(long colId, int length, String colKey) {
            this.colId = colId;
            this.length = length;
            this.colKey = colKey == null ? "" : colKey;
        }
    }

    private DsValueResolver.StorageType storageTypeForLength(long colId, String entityClassName, String logicalName) {
        TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityClassName);
        ResolvedColumn c = resolveColumn(entityClassName, logicalName, meta);
        return DsValueResolver.resolve(c.length);
    }

    private int fallbackLength(String entityClassName, String logicalName) {
        TableMetaStore.TableMeta meta = tableMetaStore.getMeta(entityClassName);
        ResolvedColumn c = resolveColumn(entityClassName, logicalName, meta);
        return c.length;
    }

    private byte[] maybeCompress(byte[] valueBytes) {
        int threshold = compressionThreshold;
        if (threshold <= 0 || valueBytes == null || valueBytes.length == 0) {
            return valueBytes;
        }
        byte[] compressed = ColumnCompression.compress(valueBytes, threshold);
        return compressed == null ? valueBytes : compressed;
    }

    private byte[] wrapVariable(byte[] valueBytes) {
        int threshold = compressionThreshold;
        if (threshold > 0 && valueBytes.length > 0) {
            byte[] compressed = ColumnCompression.compress(valueBytes, threshold);
            if (compressed != null) {
                return compressed;
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(VARIABLE_HEADER_SIZE + valueBytes.length);
        buf.put(VARIABLE_UNCOMPRESSED_MAGIC[0]);
        buf.put(VARIABLE_UNCOMPRESSED_MAGIC[1]);
        buf.put(VARIABLE_VERSION);
        buf.putInt(valueBytes.length);
        buf.put(valueBytes);
        return buf.array();
    }

    private byte[] readVariableBucket(String entityClassName, long colId, long valueId) throws IOException {
        String type = valueType(colId);
        byte[] header = bucketStore.get(entityClassName, type, valueId, 0, VARIABLE_HEADER_SIZE);
        if (header == null || header.length < 2) {
            return null;
        }
        // LZ 压缩头只有 2 字节魔数，需先识别再读取完整 11 字节头
        if (header[0] == ColumnCompression.MAGIC[0] && header[1] == ColumnCompression.MAGIC[1]) {
            byte[] fullHeader = bucketStore.get(entityClassName, type, valueId, 0, ColumnCompression.HEADER_SIZE);
            if (fullHeader == null || fullHeader.length < ColumnCompression.HEADER_SIZE) {
                return null;
            }
            if (!ColumnCompression.isCompressed(fullHeader)) {
                return fullHeader;
            }
            ByteBuffer buf = ByteBuffer.wrap(fullHeader);
            buf.position(7);
            int compressedLen = buf.getInt();
            int totalLen = ColumnCompression.HEADER_SIZE + compressedLen;
            byte[] full = bucketStore.get(entityClassName, type, valueId, 0, totalLen);
            return ColumnCompression.decompress(full);
        }
        if (header[0] == VARIABLE_UNCOMPRESSED_MAGIC[0]
                && header[1] == VARIABLE_UNCOMPRESSED_MAGIC[1]
                && header.length >= VARIABLE_HEADER_SIZE
                && header[2] == VARIABLE_VERSION) {
            ByteBuffer buf = ByteBuffer.wrap(header);
            int len = buf.getInt(3);
            if (len < 0) {
                return null;
            }
            if (len == 0) {
                return new byte[0];
            }
            return bucketStore.get(entityClassName, type, valueId, VARIABLE_HEADER_SIZE, len);
        }
        // 旧数据兼容：原样返回
        return header;
    }

    private byte[] readAndMaybeDecompress(String entityClassName, long valueId, String type, int expectedLength) throws IOException {
        if (expectedLength <= 0) {
            return new byte[0];
        }
        int headerReadLen = Math.min(ColumnCompression.HEADER_SIZE, expectedLength);
        byte[] header = bucketStore.get(entityClassName, type, valueId, 0, headerReadLen);
        if (header != null && header.length == ColumnCompression.HEADER_SIZE && ColumnCompression.isCompressed(header)) {
            ByteBuffer buf = ByteBuffer.wrap(header);
            buf.position(7);
            int compressedLen = buf.getInt();
            int totalLen = ColumnCompression.HEADER_SIZE + compressedLen;
            byte[] full = bucketStore.get(entityClassName, type, valueId, 0, totalLen);
            byte[] decompressed = ColumnCompression.decompress(full);
            if (decompressed != null) {
                return decompressed;
            }
        }
        return bucketStore.get(entityClassName, type, valueId, 0, expectedLength);
    }

    static final class ColumnarColumnDataBackend extends AbstractRegistryDataBackend {
        ColumnarColumnDataBackend(File dbRoot) throws IOException { super(dbRoot); }
        void writeColumn(String entityClassName, String logicalName, long rowId, byte[] valueBytes) throws IOException {
            String path = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
            writeLeafBytes(path, valueBytes, v -> {});
        }
        byte[] readColumn(String entityClassName, String logicalName, long rowId, YamlSupplier<byte[]> bucketGetter) throws IOException {
            String path = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
            return readLeafBytes(path, bucketGetter, null);
        }
        void removeColumn(String entityClassName, String logicalName, long rowId) throws IOException {
            String path = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
            removeLeafBytes(path, () -> Boolean.FALSE);
        }
    }

    static final class ColumnarGroupDataBackend extends AbstractRegistryDataBackend {
        ColumnarGroupDataBackend(File dbRoot) throws IOException { super(dbRoot); }
        void writeGroup(String entityClassName, String groupName, long rowId, byte[] groupBytes) throws IOException {
            String path = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
            writeLeafBytes(path, groupBytes, v -> {});
        }
        byte[] readGroup(String entityClassName, String groupName, long rowId, YamlSupplier<byte[]> bucketGetter) throws IOException {
            String path = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
            return readLeafBytes(path, bucketGetter, null);
        }
        void removeGroup(String entityClassName, String groupName, long rowId) throws IOException {
            String path = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
            removeLeafBytes(path, () -> Boolean.FALSE);
        }
    }

    // ========== P13 OQ4: 列存批量 put（≥ 原单行 for 循环性能）public final NEW 方法，不碰老签名 ==========

    public final int putValueBatch(String entityClassName, Map<Long, Map<String, byte[]>> rowIdToColNameToValue) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (rowIdToColNameToValue == null) {
            return 0;
        }
        final List<Long> rowIdsSorted = new ArrayList<>(rowIdToColNameToValue.keySet());
        rowIdsSorted.sort(null);
        final Map<String, DsTableLayout.ColumnLayout> layoutCache = new HashMap<>();
        final Map<String, ResolvedColumn> resolvedCache = new HashMap<>();
        final Map<String, TableMetaStore.TableMeta> metaCache = new HashMap<>();
        int cells = 0;
        for (long rowId : rowIdsSorted) {
            Map<String, byte[]> cols = rowIdToColNameToValue.get(rowId);
            if (cols == null || cols.isEmpty()) continue;
            for (Map.Entry<String, byte[]> e : cols.entrySet()) {
                String logicalName = e.getKey();
                byte[] valueBytes = normalizeBytes(e.getValue());
                String cacheKey = logicalName;
                DsTableLayout.ColumnLayout col = layoutCache.get(cacheKey);
                if (col == null && !layoutCache.containsKey(cacheKey)) {
                    col = layoutColumn(entityClassName, logicalName);
                    layoutCache.put(cacheKey, col);
                }
                byte[] padded = valueBytes;
                long valueId;
                if (col != null) {
                    final int fixedLen = col.getLength();
                    if (fixedLen > 0 && valueBytes.length < fixedLen) {
                        padded = Arrays.copyOf(valueBytes, fixedLen);
                    }
                    valueId = putColumnValue(entityClassName, col, rowId, padded);
                } else {
                    ResolvedColumn rc = resolvedCache.get(cacheKey);
                    TableMetaStore.TableMeta meta = metaCache.get("_");
                    if (meta == null) {
                        meta = tableMetaStore.getMeta(entityClassName);
                        metaCache.put("_", meta);
                    }
                    if (rc == null) {
                        rc = resolveColumn(entityClassName, logicalName, meta);
                        if (rc == null) {
                            throw new IllegalArgumentException("unknown column: " + logicalName);
                        }
                        if (registry.isDeleted(entityClassName, rc.colId)) {
                            throw new MetaDeletedColumnException("column is deleted: " + rc.colKey);
                        }
                        resolvedCache.put(cacheKey, rc);
                    }
                    if (rc.length > 0 && valueBytes.length < rc.length) {
                        padded = Arrays.copyOf(valueBytes, rc.length);
                    }
                    valueId = putBucketColumn(entityClassName, rc, rowId, padded);
                }
                if (columnDataBackend != null) {
                    columnDataBackend.writeColumn(entityClassName, logicalName, rowId, padded);
                }
                if (registryReplicator != null) {
                    long v = colWriteVersion.incrementAndGet();
                    String p = RegistryMetaPaths.dataColumnPayload(entityClassName, logicalName, rowId);
                    registryReplicator.replicateKey(p, padded, v, new long[]{0L});
                }
                cells++;
            }
        }
        return cells;
    }

    public final int putFieldGroupBatch(String entityClassName, String groupName, Map<Long, byte[]> rowIdToGroupBytes) throws IOException {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowIdToGroupBytes == null) {
            return 0;
        }
        DsTableLayout.FieldGroupDef group = groupDef(entityClassName, groupName);
        DsColumnGroupStore store = groupStore(entityClassName, group);
        List<Long> rowIdsSorted = new ArrayList<>(rowIdToGroupBytes.keySet());
        rowIdsSorted.sort(null);
        int count = 0;
        for (long rowId : rowIdsSorted) {
            if (rowId <= 0L) continue;
            byte[] groupBytes = rowIdToGroupBytes.get(rowId);
            if (groupBytes == null) groupBytes = new byte[0];
            store.put(rowId, groupBytes);
            if (groupDataBackend != null) {
                groupDataBackend.writeGroup(entityClassName, groupName, rowId, groupBytes);
            }
            if (registryReplicator != null) {
                long v = colWriteVersion.incrementAndGet();
                String p = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
                registryReplicator.replicateKey(p, groupBytes, v, new long[]{0L});
            }
            count++;
        }
        return count;
    }

    public final int putCompositeGroupBatch(Class<? extends DsTableAdapter> entityClass, String groupName, Map<Long, byte[]> rowIdToValue) throws IOException {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        if (groupName == null || groupName.isBlank()) {
            throw new IllegalArgumentException("groupName is blank");
        }
        if (rowIdToValue == null) {
            return 0;
        }
        String entityClassName = entityClass.getName();
        TableMetaStore.TableMeta meta = tableMetaStore.ensureMeta(entityClass);
        TableMetaStore.CompositeGroup g = meta.compositeGroups.get(groupName);
        if (g == null) {
            throw new IllegalArgumentException("unknown composite group: " + groupName);
        }
        if (registry.isDeleted(entityClass, g.colId)) {
            throw new MetaDeletedColumnException("column is deleted: " + g.colKey);
        }
        DsHashMap map = colMap(entityClass, g.colId);
        String type = valueType(g.colId);
        List<Long> rowIdsSorted = new ArrayList<>(rowIdToValue.keySet());
        rowIdsSorted.sort(null);
        int count = 0;
        for (long rowId : rowIdsSorted) {
            if (rowId <= 0L) continue;
            byte[] valueBytes = rowIdToValue.get(rowId);
            if (valueBytes == null) valueBytes = new byte[0];
            if (valueBytes.length > g.length) {
                throw new IllegalArgumentException("valueBytes overflow group length: rowId=" + rowId
                    + " len=" + valueBytes.length + " > " + g.length);
            }
            byte[] padded = valueBytes;
            if (g.length > 0 && valueBytes.length < g.length) {
                padded = Arrays.copyOf(valueBytes, g.length);
            }
            byte[] storedBytes = maybeCompress(padded);
            long oldValueId = map.getOrDefault(rowId, 0L);
            if (oldValueId == 0L) {
                long newValueId = bucketStore.getNewId(entityClassName, type, g.length);
                bucketStore.overwrite(entityClassName, type, newValueId, storedBytes);
                map.put(rowId, newValueId);
            } else {
                long newId = bucketStore.update(entityClassName, type, oldValueId, storedBytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
                if (newId != oldValueId) {
                    map.put(rowId, newId);
                }
            }
            if (groupDataBackend != null) {
                groupDataBackend.writeGroup(entityClassName, groupName, rowId, padded);
            }
            if (registryReplicator != null) {
                long v = colWriteVersion.incrementAndGet();
                String p = RegistryMetaPaths.dataGroupPayload(entityClassName, groupName, rowId);
                registryReplicator.replicateKey(p, padded, v, new long[]{0L});
            }
            count++;
        }
        return count;
    }

    private boolean hasMvcc(String entityClassName) {
        return false;
    }
}
