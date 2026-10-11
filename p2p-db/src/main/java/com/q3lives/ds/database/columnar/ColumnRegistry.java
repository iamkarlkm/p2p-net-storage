package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 列存列名 → colId 注册器（公开签名零改；内部 backend 三态路由）
 *
 * 不变式：colId 单调递增、只增不回收；deleted 只打标记。
 *
 * Registry 权威（HYBRID/REGISTRY）子树：
 *   /orm/column/<entityClass>/columns/
 *     nextColId REG_QWORD  ← 原子递增
 *     byKey/<colKey> REG_QWORD  = colId
 *     byId/<colId>  REG_SZ     = colKey
 *     deleted/<colId> REG_DWORD = 1
 */
public final class ColumnRegistry {
    private final File dbRoot;
    private final Backend backend;

    public ColumnRegistry(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        try {
            this.backend = new Backend(dbRoot);
        } catch (java.io.IOException e) {
            throw new MetaStoreException("failed open ColumnRegistry backend", e);
        }
    }

    public long getOrCreateColId(Class<? extends DsTableAdapter> entityClass, String colKey) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        return getOrCreateColId(entityClass.getName(), colKey);
    }

    public long getOrCreateColId(String entityClassName, String colKey) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colKey == null || colKey.isBlank()) {
            throw new IllegalArgumentException("colKey is blank");
        }

        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "columns.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            // ---- 1) Registry 优先 ----
            Long regId = backend.registryFindColId(entityClassName, colKey);
            if (regId != null) return regId.longValue();

            // ---- 2) YAML fallback（HYBRID 读 YAML 后同步 Registry）----
            RegistryMeta meta = loadMeta(metaFile);
            Long existing = meta.colKeyToId.get(colKey);
            if (existing != null) {
                backend.syncFromYaml(entityClassName, meta);
                renameToMigratedIfExists(metaFile);
                return existing.longValue();
            }
            // ---- 3) 新建 colId：原子 allocate ----
            long newId = backend.allocateColId(entityClassName, colKey, meta.nextColId, yMeta -> {
                yMeta.colKeyToId.put(colKey, Long.valueOf(yMeta.nextColId));
                yMeta.colIdToKey.put(Long.valueOf(yMeta.nextColId), colKey);
                yMeta.nextColId += 1;
                saveMeta(metaFile, yMeta);
            });
            return newId;
        } catch (Exception e) {
            throw new MetaStoreException("failed to write columns meta: entityClass=" + entityClassName + ", colKey=" + colKey, e);
        }
    }

    public Long findColId(Class<? extends DsTableAdapter> entityClass, String colKey) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        return findColId(entityClass.getName(), colKey);
    }

    public Long findColId(String entityClassName, String colKey) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colKey == null || colKey.isBlank()) {
            return null;
        }
        Long reg;
        try {
            reg = backend.registryFindColId(entityClassName, colKey);
        } catch (java.io.IOException ex) {
            reg = null;
        }
        if (reg != null) return reg;
        File metaFile = metaFile(entityClassName);
        if (!metaFile.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            RegistryMeta meta = parseYaml(in);
            Long id = meta.colKeyToId.get(colKey);
            if (id != null) {
                backend.syncFromYaml(entityClassName, meta);
                renameToMigratedIfExists(metaFile);
            }
            return id;
        } catch (Exception e) {
            throw new MetaStoreException("failed to read columns meta: entityClass=" + entityClassName + ", colKey=" + colKey, e);
        }
    }

    public void markDeleted(Class<? extends DsTableAdapter> entityClass, long colId) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        markDeleted(entityClass.getName(), colId);
    }

    public void markDeleted(String entityClassName, long colId) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colId <= 0L) {
            throw new IllegalArgumentException("colId must be > 0");
        }
        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "columns.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            backend.markDeleted(entityClassName, colId);
            // YAML side
            if (backend.mode() == com.q3lives.ds.registry.meta.MetaMode.REGISTRY) return;
            RegistryMeta meta = loadMeta(metaFile);
            meta.deleted.add(colId);
            saveMeta(metaFile, meta);
        } catch (Exception e) {
            throw new MetaStoreException("failed to mark column deleted: entityClass=" + entityClassName + ", colId=" + colId, e);
        }
    }

    public boolean isDeleted(Class<? extends DsTableAdapter> entityClass, long colId) {
        Objects.requireNonNull(entityClass, "entityClass cannot be null");
        return isDeleted(entityClass.getName(), colId);
    }

    public boolean isDeleted(String entityClassName, long colId) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colId <= 0L) {
            return false;
        }
        try {
            if (backend.registryIsDeleted(entityClassName, colId)) return true;
        } catch (java.io.IOException ignored) {}
        File metaFile = metaFile(entityClassName);
        if (!metaFile.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            RegistryMeta meta = parseYaml(in);
            return meta.deleted.contains(colId);
        } catch (Exception e) {
            throw new MetaStoreException("failed to read deleted flags: entityClass=" + entityClassName + ", colId=" + colId, e);
        }
    }

    public void unDelete(String entityClassName, long colId) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colId <= 0L) {
            throw new IllegalArgumentException("colId must be > 0");
        }
        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "columns.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            backend.unDelete(entityClassName, colId);
            if (backend.mode() == com.q3lives.ds.registry.meta.MetaMode.REGISTRY) return;
            RegistryMeta meta = loadMeta(metaFile);
            meta.deleted.remove(Long.valueOf(colId));
            saveMeta(metaFile, meta);
        } catch (Exception e) {
            throw new MetaStoreException("failed to unDelete column: entityClass=" + entityClassName + ", colId=" + colId, e);
        }
    }

    String findColIdByColId(String entityClassName, long colId) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (colId <= 0L) {
            return null;
        }
        try {
            String reg = backend.registryFindColKeyByColId(entityClassName, colId);
            if (reg != null) return reg;
        } catch (java.io.IOException ignored) {}
        File metaFile = metaFile(entityClassName);
        if (!metaFile.isFile()) return null;
        try (InputStream in = new FileInputStream(metaFile)) {
            RegistryMeta meta = parseYaml(in);
            return meta.colIdToKey.get(Long.valueOf(colId));
        } catch (Exception e) {
            throw new MetaStoreException("failed to read colKey by colId: entityClass=" + entityClassName + ", colId=" + colId, e);
        }
    }

    public void renameColKey(String entityClassName, String oldColKey, String newColKey) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (oldColKey == null || oldColKey.isBlank()) {
            throw new IllegalArgumentException("oldColKey is blank");
        }
        if (newColKey == null || newColKey.isBlank()) {
            throw new IllegalArgumentException("newColKey is blank");
        }
        if (oldColKey.equals(newColKey)) return;
        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "columns.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            Long existing = backend.registryFindColId(entityClassName, oldColKey);
            if (existing == null) {
                RegistryMeta m0 = loadMeta(metaFile);
                existing = m0.colKeyToId.get(oldColKey);
            }
            if (existing == null) {
                throw new IllegalArgumentException("oldColKey not found: " + oldColKey);
            }
            long colId = existing.longValue();
            Long conflict = backend.registryFindColId(entityClassName, newColKey);
            if (conflict == null) {
                RegistryMeta m0 = loadMeta(metaFile);
                conflict = m0.colKeyToId.get(newColKey);
            }
            if (conflict != null && conflict.longValue() == colId) {
                return;
            }
            backend.renameColKey(entityClassName, oldColKey, newColKey, colId);
            if (backend.mode() == com.q3lives.ds.registry.meta.MetaMode.REGISTRY) return;
            RegistryMeta meta = loadMeta(metaFile);
            meta.colKeyToId.remove(oldColKey);
            meta.colKeyToId.put(newColKey, Long.valueOf(colId));
            meta.colIdToKey.put(Long.valueOf(colId), newColKey);
            saveMeta(metaFile, meta);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new MetaStoreException("failed to rename colKey: entityClass=" + entityClassName
                + ", old=" + oldColKey + ", new=" + newColKey, e);
        }
    }

    private static void renameToMigratedIfExists(File yamlFile) {
        if (!yamlFile.isFile()) return;
        try {
            File dst = new File(yamlFile.getParentFile(), yamlFile.getName() + ".migrated");
            if (dst.exists()) yamlFile.delete();
            else Files.move(yamlFile.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {}
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
        return new File(dir, "columns.meta.yaml");
    }

    private static RegistryMeta loadMeta(File metaFile) throws java.io.IOException {
        if (!metaFile.isFile()) {
            RegistryMeta fresh = new RegistryMeta();
            try { saveMeta(metaFile, fresh); } catch (Exception e) { throw wrapIO(e); }
            return fresh;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            return parseYaml(in);
        } catch (java.io.IOException e) { throw e; }
        catch (Exception e) { throw wrapIO(e); }
    }

    private static java.io.IOException wrapIO(Exception e) {
        if (e instanceof java.io.IOException) return (java.io.IOException) e;
        return new java.io.IOException("loadMeta failed", e);
    }

    private static RegistryMeta parseYaml(InputStream in) {
        Yaml yaml = new Yaml();
        RegistryMeta meta = yaml.loadAs(in, RegistryMeta.class);
        if (meta == null) {
            meta = new RegistryMeta();
        }
        if (meta.colKeyToId == null) {
            meta.colKeyToId = new LinkedHashMap<>();
        }
        if (meta.colIdToKey == null) {
            meta.colIdToKey = new LinkedHashMap<>();
        }
        if (meta.deleted == null) {
            meta.deleted = new LinkedHashSet<>();
        }
        if (meta.nextColId <= 0L) {
            meta.nextColId = 1L;
        }
        return meta;
    }

    private static void saveMeta(File metaFile, RegistryMeta meta) throws java.io.IOException {
        File tmp = new File(metaFile.getAbsolutePath() + ".tmp");
        Yaml yaml = new Yaml();
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            yaml.dump(meta, w);
        }
        try {
            Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) { throw e; }
        catch (Exception ignored) {
            try {
                Files.move(tmp.toPath(), metaFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e2) {
                throw new java.io.IOException("saveMeta move failed", e2);
            }
        }
    }

    public static final class RegistryMeta {
        public long nextColId = 1L;
        public Map<String, Long> colKeyToId = new LinkedHashMap<>();
        public Map<Long, String> colIdToKey = new LinkedHashMap<>();
        public Set<Long> deleted = new LinkedHashSet<>();
    }

    // ================== Backend ==================
    private static final class Backend extends AbstractRegistryBackend {
        Backend(File dbRoot) throws java.io.IOException { super(dbRoot); }

        Long registryFindColId(String entityClass, String colKey) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return null;
            String byKeyPath = RegistryMetaPaths.columnByKey(entityClass, colKey);
            RegistryEntry e = registry.get(byKeyPath);
            if (e != null && e.isPresent()) {
                try { return Long.valueOf(e.asLong()); } catch (Exception ignored) {}
            }
            return null;
        }

        boolean registryIsDeleted(String entityClass, long colId) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return false;
            RegistryEntry e = registry.get(RegistryMetaPaths.columnDeleted(entityClass, colId));
            if (e != null && e.isPresent()) {
                try { return e.asLong() == 1L; } catch (Exception ignored) {}
            }
            return false;
        }

        long allocateColId(String entityClass, String colKey, long yamlNextSeed,
                           YamlConsumer<RegistryMeta> yamlApply) throws java.io.IOException {
            // 双写：先写 Registry（原子 allocate）再写 YAML
            String nextColIdPath = RegistryMetaPaths.columnNextColId(entityClass);
            long newId = super.atomicModifyLong(nextColIdPath, prev -> prev + 1, yamlNextSeed, true,
                () -> null,  // YAML 写入在外面 apply
                v -> {});    // 空（HYBRID 由下面显式 yamlApply + registry put 保证）
            // ---- registry byKey / byId 登记 ----
            if (mode != com.q3lives.ds.registry.meta.MetaMode.YAML) {
                String colKeySeg = RegistryMetaPaths.columnSegment(colKey);
                registry.mkdirs(RegistryMetaPaths.columnByKeyDir(entityClass));
                registry.mkdirs(RegistryMetaPaths.columnByIdDir(entityClass));
                registry.putLong(RegistryMetaPaths.columnByKeyDir(entityClass) + "/" + colKeySeg, DataType.REG_QWORD, newId);
                registry.putString(RegistryMetaPaths.columnById(entityClass, newId), colKey, DataType.REG_SZ);
            }
            // ---- YAML apply ----
            if (mode != com.q3lives.ds.registry.meta.MetaMode.REGISTRY) {
                RegistryMeta m = new RegistryMeta();
                m.nextColId = yamlNextSeed;
                yamlApply.accept(m);
            }
            return newId;
        }

        void markDeleted(String entityClass, long colId) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return;
            String p = RegistryMetaPaths.columnDeleted(entityClass, colId);
            registry.mkdirs(RegistryMetaPaths.columnDeletedDir(entityClass));
            registry.putLong(p, DataType.REG_DWORD, 1L);
        }

        void unDelete(String entityClass, long colId) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return;
            String p = RegistryMetaPaths.columnDeleted(entityClass, colId);
            registry.remove(p, false);
        }

        String registryFindColKeyByColId(String entityClass, long colId) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return null;
            String byIdPath = RegistryMetaPaths.columnById(entityClass, colId);
            RegistryEntry e = registry.get(byIdPath);
            if (e != null && e.isPresent()) {
                try {
                    String s = e.asString();
                    return (s == null || s.isEmpty()) ? null : s;
                } catch (Exception ignored) {}
            }
            return null;
        }

        void renameColKey(String entityClass, String oldColKey, String newColKey, long colId) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return;
            String oldByKeyPath = RegistryMetaPaths.columnByKey(entityClass, oldColKey);
            String newByKeyPath = RegistryMetaPaths.columnByKey(entityClass, newColKey);
            String byIdPath = RegistryMetaPaths.columnById(entityClass, colId);
            registry.mkdirs(RegistryMetaPaths.columnByKeyDir(entityClass));
            registry.mkdirs(RegistryMetaPaths.columnByIdDir(entityClass));
            registry.remove(oldByKeyPath, false);
            registry.remove(newByKeyPath, false);
            registry.putLong(newByKeyPath, DataType.REG_QWORD, colId);
            registry.putString(byIdPath, newColKey, DataType.REG_SZ);
        }

        /** HYBRID: Registry miss 时把 YAML 已有 colKey/colId 对 + nextColId 全量同步进 Registry */
        void syncFromYaml(String entityClass, RegistryMeta yaml) throws java.io.IOException {
            if (mode == com.q3lives.ds.registry.meta.MetaMode.YAML) return;
            super.mkdirs(RegistryMetaPaths.columnColumnsRoot(entityClass));
            super.mkdirs(RegistryMetaPaths.columnByKeyDir(entityClass));
            super.mkdirs(RegistryMetaPaths.columnByIdDir(entityClass));
            // 先 nextColId（QWORD）
            super.writeLeafLong(RegistryMetaPaths.columnNextColId(entityClass), yaml.nextColId, true, l -> {});
            for (Map.Entry<String, Long> e : yaml.colKeyToId.entrySet()) {
                String colKey = e.getKey();
                long colId = e.getValue() == null ? 0L : e.getValue().longValue();
                if (colId <= 0 || colKey == null) continue;
                String byKeyPath = RegistryMetaPaths.columnByKey(entityClass, colKey);
                RegistryEntry existing = registry.get(byKeyPath);
                if (existing != null && existing.isPresent()) continue;
                String colKeySeg = RegistryMetaPaths.columnSegment(colKey);
                registry.putLong(RegistryMetaPaths.columnByKeyDir(entityClass) + "/" + colKeySeg, DataType.REG_QWORD, colId);
                registry.putString(RegistryMetaPaths.columnById(entityClass, colId), colKey, DataType.REG_SZ);
            }
            for (Long colId : yaml.deleted) {
                if (colId == null || colId.longValue() <= 0) continue;
                markDeleted(entityClass, colId.longValue());
            }
        }
    }
}
