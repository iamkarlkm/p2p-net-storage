package com.q3lives.ds.database.columnar;

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

import javax.net.p2p.model.DbColumnSchema;
import javax.net.p2p.model.DbCompositeGroupSchema;
import javax.net.p2p.model.DbCompositeItemSchema;
import javax.net.p2p.model.DbTableSchema;

import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.exception.meta.MetaCompositeBitOverlapException;
import com.q3lives.ds.exception.meta.MetaCompositeGroupLengthMismatchException;
import com.q3lives.ds.exception.meta.MetaDuplicateColumnDefinitionException;
import com.q3lives.ds.exception.meta.MetaSchemaException;
import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 列存 TableMeta 存储器（签名零改，内部 backend 路由三态）
 *
 * Registry 持久化：signature (REG_SZ) + columns cache + compositeGroups cache
 *   - columns/<logicalName>/{colId QWORD, colKey SZ, name SZ, javaType SZ, length DWORD, precision DWORD, scale DWORD}
 *   - compositeGroups/<group>/{colId QWORD, colKey SZ, length DWORD, items/<item>/startBits DWORD, items/<item>/endBits DWORD}
 *
 * 幂等：signature 相等则不覆写；HYBRID：首次 Registry miss 读 YAML 后写回 Registry，并重命名 table.meta.yaml → table.meta.yaml.migrated
 */
public final class TableMetaStore {
    private final File dbRoot;
    private final ColumnRegistry columnRegistry;
    private final Backend backend;

    public TableMetaStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.columnRegistry = new ColumnRegistry(dbRoot);
        Backend b;
        try {
            b = new Backend(dbRoot);
        } catch (java.io.IOException e) {
            throw new MetaStoreException("fail open TableMeta backend", e);
        }
        this.backend = b;
    }

    public TableMeta getMeta(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        File metaFile = metaFile(entityClassName);
        try {
            // ---- 1) 优先 Registry（HYBRID/REGISTRY）----
            TableMeta reg = backend.loadFromRegistry(entityClassName);
            if (reg != null && reg.signature != null && !reg.signature.isEmpty()) {
                return reg;
            }
            // ---- 2) fallback YAML ----
            TableMeta y = load(metaFile);
            if (MetaMode.global() == MetaMode.HYBRID && y != null && y.signature != null && !y.signature.isEmpty()) {
                backend.saveToRegistry(entityClassName, y);
                renameToMigratedIfExists(metaFile);
            }
            return y;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaStoreException("failed to read table meta: entityClass=" + entityClassName, e);
        }
    }

    public <T extends DsTableAdapter> TableMeta ensureMeta(Class<T> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        File metaFile = metaFile(entityClass);
        File lockFile = new File(metaFile.getParentFile(), "table.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            TableMeta meta = getMeta(entityClass.getName());
            TableMeta fresh = build(entityClass);
            if (!Objects.equals(meta.signature, fresh.signature)) {
                save(entityClass.getName(), metaFile, fresh);
                return fresh;
            }
            return meta;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaStoreException("failed to ensure table meta: entityClass=" + entityClass.getName(), e);
        }
    }

    public TableMeta ensureMeta(String entityClassName, DbTableSchema schema, boolean overwrite) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        Objects.requireNonNull(schema, "schema cannot be null");

        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "table.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            TableMeta meta = getMeta(entityClassName);
            if (!overwrite && meta.signature != null && !meta.signature.isBlank()) {
                return meta;
            }
            TableMeta fresh = build(entityClassName, schema);
            if (overwrite || !Objects.equals(meta.signature, fresh.signature)) {
                save(entityClassName, metaFile, fresh);
                return fresh;
            }
            return meta;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaStoreException("failed to ensure table meta: entityClass=" + entityClassName, e);
        }
    }

    private void save(String entityClassName, File yamlFile, TableMeta meta) throws Exception {
        if (MetaMode.isGlobalYamlOrHybrid()) {
            save(yamlFile, meta);
        }
        if (MetaMode.isGlobalRegistryOrHybrid()) {
            backend.saveToRegistry(entityClassName, meta);
            if (MetaMode.global() == MetaMode.HYBRID) {
                renameToMigratedIfExists(yamlFile);
            }
        }
    }

    private static void renameToMigratedIfExists(File yamlFile) {
        if (!yamlFile.isFile()) return;
        try {
            File dst = new File(yamlFile.getParentFile(), yamlFile.getName() + ".migrated");
            if (dst.exists()) {
                yamlFile.delete();
            } else {
                Files.move(yamlFile.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }

    // ====== 原有 build/load/save 方法体零改（仅去掉 private static → static private 顺序不变）======
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
                long colId = columnRegistry.getOrCreateColId(entityClass, colKey);
                ColumnDef c = new ColumnDef();
                c.colId = colId;
                c.colKey = colKey;
                c.name = logicalName;
                c.javaType = f.getType().getName();
                c.length = a.length();
                c.precision = a.precision();
                c.scale = a.scale();
                c.nullable = Boolean.valueOf(a.nullable());
                ColumnDef old = uniqueColumnsByName.get(logicalName);
                if (old == null) {
                    uniqueColumnsByName.put(logicalName, c);
                    meta.columns.add(c);
                } else {
                    if (!Objects.equals(old.javaType, c.javaType) || old.length != c.length || old.precision != c.precision || old.scale != c.scale || !Objects.equals(old.nullable, c.nullable)) {
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

    private TableMeta build(String entityClassName, DbTableSchema schema) {
        TableMeta meta = new TableMeta();
        meta.entityClassName = entityClassName;
        meta.columns = new ArrayList<>();
        meta.compositeGroups = new LinkedHashMap<>();
        Map<String, ColumnDef> uniqueColumnsByName = new HashMap<>();

        if (schema.columns != null) {
            for (DbColumnSchema in : schema.columns) {
                if (in == null) {
                    continue;
                }
                String logicalName = in.name;
                if (logicalName == null || logicalName.isBlank()) {
                    throw new MetaSchemaException("column name is blank: entityClass=" + entityClassName);
                }
                String javaType = in.javaType;
                if (javaType == null || javaType.isBlank()) {
                    throw new MetaSchemaException("column javaType is blank: entityClass=" + entityClassName + ", name=" + logicalName);
                }
                String colKey = entityClassName + "#" + logicalName;
                long colId = columnRegistry.getOrCreateColId(entityClassName, colKey);

                ColumnDef c = new ColumnDef();
                c.colId = colId;
                c.colKey = colKey;
                c.name = logicalName;
                c.javaType = javaType;
                c.length = in.length;
                c.precision = in.precision;
                c.scale = in.scale;

                ColumnDef old = uniqueColumnsByName.get(logicalName);
                if (old == null) {
                    uniqueColumnsByName.put(logicalName, c);
                    meta.columns.add(c);
                } else {
                    if (!Objects.equals(old.javaType, c.javaType) || old.length != c.length || old.precision != c.precision || old.scale != c.scale || !Objects.equals(old.nullable, c.nullable)) {
                        throw new MetaDuplicateColumnDefinitionException("duplicate column name with mismatch: name=" + logicalName);
                    }
                }
            }
        }

        if (schema.compositeGroups != null) {
            for (DbCompositeGroupSchema in : schema.compositeGroups.values()) {
                if (in == null) {
                    continue;
                }
                if (in.group == null || in.group.isBlank()) {
                    throw new MetaSchemaException("composite group is blank: entityClass=" + entityClassName);
                }
                if (in.length <= 0) {
                    throw new MetaSchemaException("composite group length must be > 0: group=" + in.group);
                }
                CompositeGroup g = meta.compositeGroups.computeIfAbsent(in.group, k -> new CompositeGroup());
                g.group = in.group;
                if (g.length == 0) {
                    g.length = in.length;
                } else if (g.length != in.length) {
                    throw new MetaCompositeGroupLengthMismatchException("composite group length mismatch: group=" + in.group + ", " + g.length + " vs " + in.length);
                }
                if (in.items != null) {
                    for (DbCompositeItemSchema itemIn : in.items) {
                        if (itemIn == null) {
                            continue;
                        }
                        CompositeItem item = new CompositeItem();
                        item.name = itemIn.name;
                        item.startBits = itemIn.startBits;
                        item.endBits = itemIn.endBits;
                        validateAndAddCompositeItem(g, item);
                    }
                }
            }
        }

        for (CompositeGroup g : meta.compositeGroups.values()) {
            String colKey = entityClassName + "#@composite:" + g.group;
            long colId = columnRegistry.getOrCreateColId(entityClassName, colKey);
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

    private static boolean colNullable(ColumnDef c) {
        return c == null || c.nullable == null ? true : c.nullable.booleanValue();
    }

    private static String computeSignature(TableMeta meta) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("v1|").append(meta.entityClassName).append('\n');
        for (ColumnDef c : meta.columns) {
            sb.append("F|").append(c.colId).append('|').append(c.colKey).append('|').append(c.javaType)
              .append('|').append(c.length).append('|').append(c.precision).append('|').append(c.scale)
              .append('|').append(colNullable(c)).append('\n');
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
        return metaFile(entityClass.getName());
    }

    private File metaFile(String entityClassName) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
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
            if (meta.columns != null) {
                for (ColumnDef c : meta.columns) {
                    if (c == null) continue;
                }
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
        public Boolean nullable;
        public Boolean unique;
        public String checkExpr;
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

    // ================== TableMeta Backend（Registry 双向实现）==================
    public static class Backend extends AbstractRegistryBackend {
        public Backend(File dbRoot) throws java.io.IOException { super(dbRoot); }

        TableMeta loadFromRegistry(String entityClassName) throws java.io.IOException {
            if (mode == MetaMode.YAML) return null;
            String sig = super.readLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                () -> null, null);
            if (sig == null || sig.isEmpty()) return null;
            TableMeta m = new TableMeta();
            m.entityClassName = entityClassName;
            m.signature = sig;
            m.columns = new ArrayList<>();
            m.compositeGroups = new LinkedHashMap<>();

            List<String> cols = super.listChildren(RegistryMetaPaths.columnTableColumnsDir(entityClassName),
                () -> java.util.Collections.emptyList());
            for (String cSeg : cols) {
                try {
                    ColumnDef cd = new ColumnDef();
                    String colDir = RegistryMetaPaths.columnTableColumnsDir(entityClassName) + "/" + cSeg;
                    cd.colId = readLongOr0(colDir + "/colId");
                    cd.colKey = readStr(colDir + "/colKey");
                    cd.name = readStr(colDir + "/name");
                    cd.javaType = readStr(colDir + "/javaType");
                    cd.length = (int) readLongOr0(colDir + "/length");
                    cd.precision = (int) readLongOr0(colDir + "/precision");
                    cd.scale = (int) readLongOr0(colDir + "/scale");
                    String nullableStr = readStr(colDir + "/nullable");
                    if (nullableStr == null || nullableStr.isEmpty()) {
                        cd.nullable = null;
                    } else {
                        cd.nullable = Boolean.valueOf(Boolean.parseBoolean(nullableStr));
                    }
                    if (cd.colId > 0 && cd.name != null && cd.javaType != null) {
                        m.columns.add(cd);
                    }
                } catch (Exception ignored) {}
            }
            List<String> grps = super.listChildren(RegistryMetaPaths.columnTableCompositeGroupsDir(entityClassName),
                () -> java.util.Collections.emptyList());
            for (String gSeg : grps) {
                try {
                    CompositeGroup cg = new CompositeGroup();
                    String gDir = RegistryMetaPaths.columnTableCompositeGroupsDir(entityClassName) + "/" + gSeg;
                    // HOTFIX: losslessly recover raw group+item name from dedicated
                    // /group and /items/<iSeg>/name leaves (columnSegment not reversible).
                    String gRaw = readStr(gDir + "/group");
                    cg.group = (gRaw != null && !gRaw.isEmpty()) ? gRaw : gSeg;
                    cg.colId = readLongOr0(gDir + "/colId");
                    cg.colKey = readStr(gDir + "/colKey");
                    cg.length = (int) readLongOr0(gDir + "/length");
                    if (cg.items == null) cg.items = new ArrayList<>();
                    List<String> items = super.listChildren(gDir + "/items",
                        () -> java.util.Collections.emptyList());
                    for (String iSeg : items) {
                        try {
                            CompositeItem ci = new CompositeItem();
                            String iDir = gDir + "/items/" + iSeg;
                            String iRaw = readStr(iDir + "/name");
                            ci.name = (iRaw != null && !iRaw.isEmpty()) ? iRaw : iSeg;
                            ci.startBits = (int) readLongOr0(iDir + "/startBits");
                            ci.endBits = (int) readLongOr0(iDir + "/endBits");
                            cg.items.add(ci);
                        } catch (Exception ignored) {}
                    }
                    if (cg.length > 0 && cg.colId > 0) {
                        m.compositeGroups.put(cg.group, cg);
                    }
                } catch (Exception ignored) {}
            }
            return m;
        }

        void saveToRegistry(String entityClassName, TableMeta m) throws java.io.IOException {
            if (mode == MetaMode.YAML) return;
            super.mkdirs(RegistryMetaPaths.columnTableRoot(entityClassName));
            super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                m.signature == null ? "" : m.signature, v -> {});
            if (m.columns != null) {
                super.mkdirs(RegistryMetaPaths.columnTableColumnsDir(entityClassName));
                for (ColumnDef c : m.columns) {
                    if (c.name == null) continue;
                    String dir = RegistryMetaPaths.columnTableColDir(entityClassName, c.name);
                    super.mkdirs(dir);
                    putQ(dir + "/colId", c.colId);
                    putS(dir + "/colKey", c.colKey);
                    putS(dir + "/name", c.name);
                    putS(dir + "/javaType", c.javaType);
                    putD(dir + "/length", c.length);
                    putD(dir + "/precision", c.precision);
                    putD(dir + "/scale", c.scale);
                    putS(dir + "/nullable", String.valueOf(colNullable(c)));
                }
            }
            if (m.compositeGroups != null) {
                super.mkdirs(RegistryMetaPaths.columnTableCompositeGroupsDir(entityClassName));
                for (Map.Entry<String, CompositeGroup> e : m.compositeGroups.entrySet()) {
                    CompositeGroup g = e.getValue();
                    String rawGroup = e.getKey();
                    if (rawGroup == null || rawGroup.isEmpty()) continue;
                    String dir = RegistryMetaPaths.columnTableCompositeGroupDir(entityClassName, rawGroup);
                    super.mkdirs(dir);
                    putQ(dir + "/colId", g.colId);
                    putS(dir + "/colKey", g.colKey);
                    putD(dir + "/length", g.length);
                    // ============================================================
                    // HOTFIX (P10 regression found by ColumnarStore smoke):
                    //   Registry write leaf must store raw group+item name at a
                    //   well-known path so loadFromRegistry can look them up
                    //   losslessly (columnSegment() adds a _8hex suffix that is
                    //   not 1:1 reversible for arbitrary unicode/group names).
                    //   Without this:
                    //     loadFromRegistry reads gSeg = "cg_<hex>" from disk,
                    //     sets cg.group = gSeg (≠ raw group), downstream caller
                    //     meta.compositeGroups.get("<originalGroup>") → null →
                    //     testCompositeGroupBehavesLikeAColumn +
                    //     shouldCompressCompositeGroup both fail with AssertionError.
                    putS(dir + "/group", rawGroup);
                    if (g.items != null) {
                        super.mkdirs(dir + "/items");
                        for (CompositeItem i : g.items) {
                            if (i.name == null) continue;
                            String idir = dir + "/items/" + RegistryMetaPaths.columnSegment(i.name);
                            super.mkdirs(idir);
                            putD(idir + "/startBits", i.startBits);
                            putD(idir + "/endBits", i.endBits);
                            putS(idir + "/name", i.name);
                        }
                    }
                }
            }
        }

        void updateColumnDef(String entityClassName, long colId, ColumnDef newDef) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entityClass blank");
            if (colId <= 0L)
                throw new IllegalArgumentException("colId <= 0: " + colId);
            if (newDef == null)
                throw new IllegalArgumentException("newDef null");
            if (mode == MetaMode.YAML) return;
            TableMeta cur = loadFromRegistry(entityClassName);
            if (cur == null || cur.columns == null)
                throw new IllegalArgumentException("no existing meta for entity: " + entityClassName);
            String colName = null;
            for (ColumnDef c : cur.columns) if (c.colId == colId) { colName = c.name; break; }
            if (colName == null)
                throw new IllegalArgumentException("colId not found: " + colId);
            String colDir = RegistryMetaPaths.columnTableColDir(entityClassName, colName);
            if (newDef.javaType != null) putS(colDir + "/javaType", newDef.javaType);
            putD(colDir + "/length", newDef.length);
            putD(colDir + "/precision", newDef.precision);
            putD(colDir + "/scale", newDef.scale);
            putS(colDir + "/nullable", String.valueOf(colNullable(newDef)));
            TableMeta fresh = loadFromRegistry(entityClassName);
            if (fresh == null) return;
            String newSig = TableMetaStore.computeSignature(fresh);
            super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                newSig == null ? "" : newSig, v -> {});
        }

        @SuppressWarnings({"rawtypes","unchecked"})
        void updateConstraint(String entityClassName, Object spec, boolean skipExist, java.util.function.Supplier<Boolean> enbOverride) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entity blank");
            if (spec == null)
                throw new IllegalArgumentException("spec null");
            try {
                java.lang.reflect.Method getCst = spec.getClass().getMethod("cstName");
                getCst.setAccessible(true);
                String cst = (String) getCst.invoke(spec);
                java.lang.reflect.Method getType = spec.getClass().getMethod("type");
                getType.setAccessible(true);
                String type = (String) getType.invoke(spec);
                if (skipExist) {
                    String existT = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_TYPE));
                    if (existT != null && existT.equals(type)) return;
                    if (existT != null && !existT.equals(type))
                        throw new IllegalArgumentException("type 已存在异 type: old=" + existT + " new=" + type);
                }
                java.lang.reflect.Method getCol = spec.getClass().getMethod("col");
                getCol.setAccessible(true);
                long col = ((Long) getCol.invoke(spec)).longValue();
                java.lang.reflect.Method getCk = spec.getClass().getMethod("ck");
                getCk.setAccessible(true);
                String ck = (String) getCk.invoke(spec);
                java.lang.reflect.Method getUk = spec.getClass().getMethod("uk");
                getUk.setAccessible(true);
                boolean uk = ((Boolean) getUk.invoke(spec)).booleanValue();
                java.lang.reflect.Method getNn = spec.getClass().getMethod("nn");
                getNn.setAccessible(true);
                boolean nn = ((Boolean) getNn.invoke(spec)).booleanValue();
                java.lang.reflect.Method getStrict = spec.getClass().getMethod("st");
                getStrict.setAccessible(true);
                boolean strict = ((Boolean) getStrict.invoke(spec)).booleanValue();
                String existCa = skipExist ? readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CREATED_AT)) : null;
                Boolean enbVal = null;
                if (enbOverride != null) {
                    try { enbVal = enbOverride.get(); } catch (Exception ignored) {}
                }
                if (enbVal == null) {
                    try {
                        java.lang.reflect.Method ge = spec.getClass().getMethod("enb");
                        if (ge.getReturnType() == boolean.class || ge.getReturnType() == Boolean.class) {
                            ge.setAccessible(true);
                            enbVal = (Boolean) ge.invoke(spec);
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
                putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_TYPE), type);
                putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_COL), Long.toString(col));
                if (ck != null) putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CK), ck);
                putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_UK), String.valueOf(uk));
                putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_NN), String.valueOf(nn));
                putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ST), String.valueOf(strict));
                String oldEnb = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ENABLED));
                String writeEnb = (enbVal != null) ? String.valueOf(enbVal) : (oldEnb == null || oldEnb.isBlank() ? "true" : oldEnb);
                if (!safeEq(writeEnb, oldEnb))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ENABLED), writeEnb);
                if (existCa == null || existCa.isBlank())
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CREATED_AT),
                            String.valueOf(System.currentTimeMillis()));
                TableMeta fresh = loadFromRegistry(entityClassName);
                if (fresh != null) {
                    String newSig = TableMetaStore.computeSignature(fresh);
                    super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                        newSig == null ? "" : newSig, v -> {});
                }
            } catch (IllegalArgumentException | ReflectiveOperationException rex) {
                if (rex instanceof IllegalArgumentException) throw (IllegalArgumentException) rex;
                throw new IllegalArgumentException("spec reflect fail: " + rex.getMessage(), rex);
            }
        }

        void updateConstraint(String entityClassName, Object spec, boolean skipExist) throws java.io.IOException {
            updateConstraint(entityClassName, spec, skipExist, null);
        }

        void updateConstraint(String entityClassName, Object spec) throws java.io.IOException {
            updateConstraint(entityClassName, spec, false, null);
        }

        void enableConstraint(String entityClassName, String cstName, boolean enabled) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entity blank");
            if (cstName == null || cstName.isBlank())
                throw new IllegalArgumentException("cstName blank");
            putS(RegistryMetaPaths.odCstLeaf(entityClassName, cstName, RegistryMetaPaths.OD_CST_ENABLED), String.valueOf(enabled));
            TableMeta fresh = loadFromRegistry(entityClassName);
            if (fresh != null) {
                String newSig = TableMetaStore.computeSignature(fresh);
                super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                    newSig == null ? "" : newSig, v -> {});
            }
        }

        @SuppressWarnings({"rawtypes","unchecked"})
        void alterCst(String entityClassName, Object spec, boolean enbOnly) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entity blank");
            if (spec == null) throw new IllegalArgumentException("spec null");
            try {
                java.lang.reflect.Method getCst = spec.getClass().getMethod("cstName");
                getCst.setAccessible(true);
                String cst = (String) getCst.invoke(spec);
                java.lang.reflect.Method getType = spec.getClass().getMethod("type");
                getType.setAccessible(true);
                String type = (String) getType.invoke(spec);
                String existType = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_TYPE));
                if (existType != null && type != null && !existType.equals(type))
                    throw new IllegalArgumentException("type 已存在异 type: old=" + existType + " new=" + type);
                if (enbOnly) {
                    Boolean enbVal = null;
                    try {
                        java.lang.reflect.Method ge = spec.getClass().getMethod("enb");
                        if (ge.getReturnType() == boolean.class || ge.getReturnType() == Boolean.class) {
                            ge.setAccessible(true);
                            enbVal = (Boolean) ge.invoke(spec);
                        }
                    } catch (NoSuchMethodException ignored) {}
                    if (enbVal != null) {
                        putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ENABLED), String.valueOf(enbVal));
                    }
                    return;
                }
                java.lang.reflect.Method getCol = spec.getClass().getMethod("col");
                getCol.setAccessible(true);
                long col = ((Long) getCol.invoke(spec)).longValue();
                java.lang.reflect.Method getCk = spec.getClass().getMethod("ck");
                getCk.setAccessible(true);
                String ck = (String) getCk.invoke(spec);
                java.lang.reflect.Method getUk = spec.getClass().getMethod("uk");
                getUk.setAccessible(true);
                boolean uk = ((Boolean) getUk.invoke(spec)).booleanValue();
                java.lang.reflect.Method getNn = spec.getClass().getMethod("nn");
                getNn.setAccessible(true);
                boolean nn = ((Boolean) getNn.invoke(spec)).booleanValue();
                java.lang.reflect.Method getStrict = spec.getClass().getMethod("st");
                getStrict.setAccessible(true);
                boolean strict = ((Boolean) getStrict.invoke(spec)).booleanValue();
                String oldType = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_TYPE));
                String oldColS = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_COL));
                String oldCk = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CK));
                String oldUk = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_UK));
                String oldNn = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_NN));
                String oldSt = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ST));
                String oldEnb = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ENABLED));
                String oldCa = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CREATED_AT));
                if (!safeEq(type, oldType))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_TYPE), type);
                String newColS = Long.toString(col);
                if (!safeEq(newColS, oldColS))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_COL), newColS);
                if (ck == null) {
                    if (oldCk != null)
                        removeLeaf(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CK), () -> Boolean.FALSE);
                } else if (!safeEq(ck, oldCk)) {
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CK), ck);
                }
                String newUk = String.valueOf(uk);
                if (!safeEq(newUk, oldUk))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_UK), newUk);
                String newNn = String.valueOf(nn);
                if (!safeEq(newNn, oldNn))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_NN), newNn);
                String newSt = String.valueOf(strict);
                if (!safeEq(newSt, oldSt))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ST), newSt);
                Boolean enbVal = null;
                try {
                    java.lang.reflect.Method ge = spec.getClass().getMethod("enb");
                    if (ge.getReturnType() == boolean.class || ge.getReturnType() == Boolean.class) {
                        ge.setAccessible(true);
                        enbVal = (Boolean) ge.invoke(spec);
                    }
                } catch (NoSuchMethodException ignored) {}
                String defaultEnb = (oldEnb == null || oldEnb.isBlank()) ? "true" : oldEnb;
                String writeEnb = (enbVal != null) ? String.valueOf(enbVal) : defaultEnb;
                if (!safeEq(writeEnb, oldEnb))
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_ENABLED), writeEnb);
                if (oldCa == null || oldCa.isBlank())
                    putS(RegistryMetaPaths.odCstLeaf(entityClassName, cst, RegistryMetaPaths.OD_CST_CREATED_AT),
                            String.valueOf(System.currentTimeMillis()));
                TableMeta fresh = loadFromRegistry(entityClassName);
                if (fresh != null) {
                    String newSig = TableMetaStore.computeSignature(fresh);
                    super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                        newSig == null ? "" : newSig, v -> {});
                }
            } catch (IllegalArgumentException | ReflectiveOperationException rex) {
                if (rex instanceof IllegalArgumentException) throw (IllegalArgumentException) rex;
                throw new IllegalArgumentException("spec reflect fail: " + rex.getMessage(), rex);
            }
        }

        void dropCst(String entityClassName, String cstName) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entity blank");
            if (cstName == null || cstName.isBlank())
                throw new IllegalArgumentException("cstName blank");
            if (!cstName.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
                throw new IllegalArgumentException("cstName nonAlnum: " + cstName);
            String dir = RegistryMetaPaths.cstDir("default", entityClassName, cstName);
            java.util.List<String> subs;
            try { subs = listChildren(dir, () -> java.util.Collections.emptyList()); }
            catch (Exception ex) { subs = java.util.Collections.emptyList(); }
            if (subs != null) {
                for (String sub : subs) {
                    String leaf = dir + "/" + sub;
                    removeLeaf(leaf, () -> Boolean.FALSE);
                }
            }
            try { registry.remove(dir, true); } catch (Exception ignored) {}
            try {
                String listDir = RegistryMetaPaths.cstListDir("default", entityClassName);
                java.util.List<String> listSubs = listChildren(listDir, () -> java.util.Collections.emptyList());
                if (listSubs != null) {
                    for (String mig : listSubs) {
                        String subLeaf = listDir + "/" + mig;
                        String v = readStr(subLeaf);
                        if (cstName.equals(v)) removeLeaf(subLeaf, () -> Boolean.FALSE);
                    }
                }
            } catch (Exception ignored) {}
            TableMeta fresh = loadFromRegistry(entityClassName);
            if (fresh != null) {
                String newSig = TableMetaStore.computeSignature(fresh);
                super.writeLeafString(RegistryMetaPaths.columnTableSignature(entityClassName),
                    newSig == null ? "" : newSig, v -> {});
            }
        }

        @SuppressWarnings({"rawtypes","unchecked"})
        java.util.List<Object> listCsts(String entityClassName) throws java.io.IOException {
            if (entityClassName == null || entityClassName.isBlank())
                throw new IllegalArgumentException("entity blank");
            String root = RegistryMetaPaths.columnTableConstraintsDir(entityClassName);
            java.util.List<String> subs = listChildren(root, () -> java.util.Collections.emptyList());
            java.util.List<Object> out = new java.util.ArrayList<>(Math.max(0, (subs == null ? 0 : subs.size()) - 2));
            if (subs == null || subs.isEmpty()) return out;
            Class<?> cstSpecCls;
            java.lang.reflect.Constructor<?> ctor;
            try {
                cstSpecCls = Class.forName("com.q3lives.ds.database.DsDatabaseLocal$OnlineDdlCoordinator$CstSpec");
                ctor = cstSpecCls.getDeclaredConstructor(String.class, String.class, long.class, String.class,
                        boolean.class, boolean.class, boolean.class, boolean.class);
                ctor.setAccessible(true);
            } catch (Exception ex) {
                throw new IllegalArgumentException("CstSpec reflect fail: " + ex.getMessage(), ex);
            }
            java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList("list", "manifests"));
            for (String name : subs) {
                if (name == null || skip.contains(name)) continue;
                String type = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_TYPE));
                if (type == null) continue;
                String colS = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_COL));
                long col = 0L;
                if (colS != null) { try { col = Long.parseLong(colS); } catch (NumberFormatException ignored) {} }
                String ck = readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_CK));
                boolean uk = "true".equalsIgnoreCase(readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_UK)));
                boolean nn = "true".equalsIgnoreCase(readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_NN)));
                boolean st = "true".equalsIgnoreCase(readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_ST)));
                boolean enb = !"false".equalsIgnoreCase(readStr(RegistryMetaPaths.odCstLeaf(entityClassName, name, RegistryMetaPaths.OD_CST_ENABLED)));
                try {
                    out.add(ctor.newInstance(name, type, Long.valueOf(col), ck,
                            Boolean.valueOf(uk), Boolean.valueOf(nn), Boolean.valueOf(st), Boolean.valueOf(enb)));
                } catch (ReflectiveOperationException rex) {
                    throw new IllegalArgumentException("CstSpec new fail: " + rex.getMessage(), rex);
                }
            }
            return out;
        }

        private static boolean safeEq(String a, String b) {
            if (a == null && b == null) return true;
            if (a == null || b == null) return false;
            return a.equals(b);
        }

        private String readStr(String p) throws java.io.IOException {
            RegistryEntry e = registry.get(p);
            if (e == null || !e.isPresent()) return null;
            String s = e.asString();
            return (s == null || s.isEmpty()) ? null : s;
        }

        private long readLongOr0(String p) throws java.io.IOException {
            RegistryEntry e = registry.get(p);
            if (e == null || !e.isPresent()) return 0L;
            try { return e.asLong(); } catch (Exception ex) { return 0L; }
        }

        private void putS(String path, String v) throws java.io.IOException {
            if (v == null) v = "";
            registry.mkdirs(parent(path));
            registry.putString(path, v, DataType.REG_SZ);
        }

        private void putD(String path, int v) throws java.io.IOException {
            registry.mkdirs(parent(path));
            registry.putLong(path, DataType.REG_DWORD, (long) v);
        }

        private void putQ(String path, long v) throws java.io.IOException {
            registry.mkdirs(parent(path));
            registry.putLong(path, DataType.REG_QWORD, v);
        }

        private static String parent(String path) {
            if (path == null || path.isEmpty()) return "/";
            int i = path.lastIndexOf('/');
            if (i <= 0) return "/";
            return path.substring(0, i);
        }
    }
}
