package com.q3lives.ds.database.columnar.index;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

public final class EqIndexMetaStore {
    private final File dbRoot;

    public EqIndexMetaStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
    }

    public boolean exists(String entityClassName, String logicalName) {
        return metaFile(entityClassName, logicalName).isFile();
    }

    public IndexDef get(String entityClassName, String logicalName) {
        File f = metaFile(entityClassName, logicalName);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(f)) {
            Yaml yaml = new Yaml();
            IndexDef meta = yaml.loadAs(in, IndexDef.class);
            return meta;
        } catch (Exception e) {
            throw new MetaStoreException("failed to read eq index meta: entityClass=" + entityClassName + ", logicalName=" + logicalName, e);
        }
    }

    public List<IndexDef> list(String entityClassName) {
        File dir = idxDir(entityClassName);
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".eq.idx.yaml"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        ArrayList<IndexDef> out = new ArrayList<>(files.length);
        for (File f : files) {
            try (InputStream in = new FileInputStream(f)) {
                Yaml yaml = new Yaml();
                IndexDef meta = yaml.loadAs(in, IndexDef.class);
                if (meta != null && meta.logicalName != null && !meta.logicalName.isBlank() && meta.colId > 0) {
                    out.add(meta);
                }
            } catch (Exception e) {
                throw new MetaStoreException("failed to read eq index meta: file=" + f.getAbsolutePath(), e);
            }
        }
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
            save(metaFile, meta);
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
            return existed && !metaFile.exists();
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
}
