package com.q3lives.ds.database.columnar;

import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.util.DsPathUtil;
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

public final class ColumnRegistry {
    private final File dbRoot;

    public ColumnRegistry(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
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
            // 关键点：Windows 下对同一文件加锁后再读同一文件可能失败，因此用独立 lock 文件串行化更新
            RegistryMeta meta = loadMeta(metaFile);
            Long existing = meta.colKeyToId.get(colKey);
            if (existing != null) {
                return existing;
            }
            long newId = meta.nextColId;
            meta.nextColId = newId + 1;
            meta.colKeyToId.put(colKey, newId);
            meta.colIdToKey.put(newId, colKey);
            saveMeta(metaFile, meta);
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
        File metaFile = metaFile(entityClassName);
        if (!metaFile.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            RegistryMeta meta = parseYaml(in);
            return meta.colKeyToId.get(colKey);
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
            // 删除只做标记，colId 永不复用（只增不回收）
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

    private static RegistryMeta loadMeta(File metaFile) throws Exception {
        if (!metaFile.isFile()) {
            RegistryMeta fresh = new RegistryMeta();
            saveMeta(metaFile, fresh);
            return fresh;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            return parseYaml(in);
        }
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

    private static void saveMeta(File metaFile, RegistryMeta meta) throws Exception {
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

    public static final class RegistryMeta {
        public long nextColId = 1L;
        public Map<String, Long> colKeyToId = new LinkedHashMap<>();
        public Map<Long, String> colIdToKey = new LinkedHashMap<>();
        public Set<Long> deleted = new LinkedHashSet<>();
    }
}
