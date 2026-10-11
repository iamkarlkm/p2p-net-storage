package com.q3lives.ds.database.columnar.index;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

/**
 * 列存等值索引元数据存储（公开签名零改，内部 backend 三态路由）
 *
 * Registry：/orm/column/<entityClass>/indexes/eq/<logicalName>/colId  REG_QWORD
 *
 * Non-Goal #1 不碰真实数据：mapFile / lockFile 方法体零改（仍指向原 indexes/<spacePath>/idx/*.map
 */
public final class EqIndexMetaStore {
    private final File dbRoot;
    private final Backend backend;

    public EqIndexMetaStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        try {
            this.backend = new Backend(dbRoot);
        } catch (java.io.IOException e) {
            throw new MetaStoreException("fail open EqIndexMeta backend", e);
        }
    }

    public boolean exists(String entityClassName, String logicalName) {
        if (entityClassName == null || entityClassName.isBlank()) return false;
        if (logicalName == null || logicalName.isBlank()) return false;
        boolean yaml = metaFile(entityClassName, logicalName).isFile();
        try {
            boolean reg = backend.registryExists(entityClassName, logicalName);
            return yaml || reg;
        } catch (java.io.IOException e) {
            return yaml;
        }
    }

    public IndexDef get(String entityClassName, String logicalName) {
        File f = metaFile(entityClassName, logicalName);
        IndexDef y = null;
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                Yaml yaml = new Yaml();
                y = yaml.loadAs(in, IndexDef.class);
            } catch (Exception e) {
                throw new MetaStoreException("failed to read eq index meta: entityClass=" + entityClassName + ", logicalName=" + logicalName, e);
            }
        }
        try {
            IndexDef reg = backend.registryGet(entityClassName, logicalName);
            if (reg != null && reg.colId > 0) {
                // HYBRID：YAML 已同步 Registry：如果 YAML 存在但 Registry 不存在，在 put 时再写；此处直接返回 Registry
                return reg;
            }
        } catch (java.io.IOException e) {
            // fallthrough 降级
        }
        return y;
    }

    public List<IndexDef> list(String entityClassName) {
        File dir = idxDir(entityClassName);
        ArrayList<IndexDef> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".eq.idx.yaml"));
        if (files != null) {
            for (File f : files) {
                try (InputStream in = new FileInputStream(f)) {
                    Yaml yaml = new Yaml();
                    IndexDef meta = yaml.loadAs(in, IndexDef.class);
                    if (meta != null && meta.logicalName != null && !meta.logicalName.isBlank() && meta.colId > 0) {
                        out.add(meta);
                        seen.add(meta.logicalName);
                    }
                } catch (Exception e) {
                    throw new MetaStoreException("failed to read eq index meta: file=" + f.getAbsolutePath(), e);
                }
            }
        }
        // Registry 侧补充
        try {
            List<String> names = backend.registryList(entityClassName);
            for (String n : names) {
                if (n == null || n.isEmpty() || seen.contains(n)) continue;
                IndexDef reg = backend.registryGet(entityClassName, n);
                if (reg != null && reg.colId > 0) {
                    out.add(reg);
                    seen.add(n);
                }
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    public void put(IndexDef meta) {
        Objects.requireNonNull(meta, "meta cannot be null");
        if (meta.entityClassName == null || meta.entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (meta.logicalName == null || meta.logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        if (meta.colId <= 0L) {
            throw new IllegalArgumentException("colId must be > 0");
        }
        File metaFile = metaFile(meta.entityClassName, meta.logicalName);
        File lockFile = new File(metaFile.getParentFile(), metaFile.getName() + ".lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            // YAML side（HYBRID/YAML
            if (MetaMode.isGlobalYamlOrHybrid()) {
                save(metaFile, meta);
            }
            // Registry side
            backend.registryPut(meta);
        } catch (Exception e) {
            throw new MetaStoreException("failed to write eq index meta: entityClass=" + meta.entityClassName + ", logicalName=" + meta.logicalName, e);
        }
    }

    public boolean delete(String entityClassName, String logicalName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName is blank");
        }
        File metaFile = metaFile(entityClassName, logicalName);
        File lockFile = new File(metaFile.getParentFile(), metaFile.getName() + ".lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
             FileLock ignored = ch.lock()) {
            boolean existed = metaFile.exists();
            if (metaFile.exists()) {
                metaFile.delete();
            }
            boolean regDeleted = false;
            try {
                regDeleted = backend.registryDelete(entityClassName, logicalName);
            } catch (java.io.IOException ex) {}
            return (existed && !metaFile.exists()) || regDeleted;
        } catch (Exception e) {
            throw new MetaStoreException("failed to delete eq index meta: entityClass=" + entityClassName + ", logicalName=" + logicalName, e);
        }
    }

    public File mapFile(String entityClassName, long colId) {
        File dir = idxDir(entityClassName);
        return new File(dir, "eq_" + colId + ".map");
    }

    public File lockFile(String entityClassName, long colId) {
        File dir = idxDir(entityClassName);
        return new File(dir, "eq_" + colId + ".lock");
    }

    private File metaFile(String entityClassName, String logicalName) {
        File dir = idxDir(entityClassName);
        return new File(dir, logicalName + ".eq.idx.yaml");
    }

    private File idxDir(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath + "/idx");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static void save(File metaFile, IndexDef meta) throws Exception {
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

    public static final class IndexDef {
        public String entityClassName;
        public String logicalName;
        public long colId;
    }

    // ================== Backend ==================
    private static final class Backend extends AbstractRegistryBackend {
        Backend(File dbRoot) throws java.io.IOException { super(dbRoot); }

        boolean registryExists(String entity, String name) throws java.io.IOException {
            if (mode == MetaMode.YAML) return false;
            String p = RegistryMetaPaths.columnEqIndexColId(entity, name);
            RegistryEntry e = registry.get(p);
            return e != null && e.isPresent();
        }

        IndexDef registryGet(String entity, String name) throws java.io.IOException {
            if (mode == MetaMode.YAML) return null;
            String p = RegistryMetaPaths.columnEqIndexColId(entity, name);
            RegistryEntry e = registry.get(p);
            if (e == null || !e.isPresent()) return null;
            try {
                long colId = e.asLong();
                if (colId <= 0) return null;
                IndexDef d = new IndexDef();
                d.entityClassName = entity;
                d.logicalName = name;
                d.colId = colId;
                return d;
            } catch (Exception ex) { return null; }
        }

        List<String> registryList(String entity) throws java.io.IOException {
            if (mode == MetaMode.YAML) return java.util.Collections.emptyList();
            String dir = RegistryMetaPaths.columnEqDir(entity);
            return super.listChildren(dir, () -> java.util.Collections.emptyList());
        }

        void registryPut(IndexDef meta) throws java.io.IOException {
            if (mode == MetaMode.YAML) return;
            String p = RegistryMetaPaths.columnEqIndexColId(meta.entityClassName, meta.logicalName);
            super.mkdirs(RegistryMetaPaths.columnEqIndexDir(meta.entityClassName, meta.logicalName));
            super.writeLeafLong(p, meta.colId, true, l -> {});
        }

        boolean registryDelete(String entity, String name) throws java.io.IOException {
            if (mode == MetaMode.YAML) return false;
            String p = RegistryMetaPaths.columnEqIndexColId(entity, name);
            return registry.remove(p, false);
        }
    }
}
