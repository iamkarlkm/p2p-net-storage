package com.q3lives.ds.database.columnar;

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
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

public final class RowIdSequenceStore {
    private final File dbRoot;

    public RowIdSequenceStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
    }

    public long allocate(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "rowid.meta.lock");
        try (FileChannel ch = new FileOutputStream(lockFile, true).getChannel();
            FileLock ignored = ch.lock()) {
            SeqMeta meta = loadMeta(metaFile);
            long out = meta.nextRowId;
            meta.nextRowId = out + 1;
            saveMeta(metaFile, meta);
            return out;
        } catch (Exception e) {
            throw new MetaStoreException("failed to allocate rowId: entityClass=" + entityClassName, e);
        }
    }

    private File metaFile(String entityClassName) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        File dir = new File(dbRoot, "indexes/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "rowid.meta.yaml");
    }

    private static SeqMeta loadMeta(File metaFile) throws Exception {
        if (!metaFile.isFile()) {
            SeqMeta fresh = new SeqMeta();
            saveMeta(metaFile, fresh);
            return fresh;
        }
        try (InputStream in = new FileInputStream(metaFile)) {
            Yaml yaml = new Yaml();
            SeqMeta meta = yaml.loadAs(in, SeqMeta.class);
            if (meta == null) {
                meta = new SeqMeta();
            }
            if (meta.nextRowId <= 0L) {
                meta.nextRowId = 1L;
            }
            return meta;
        }
    }

    private static void saveMeta(File metaFile, SeqMeta meta) throws Exception {
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

    public static final class SeqMeta {
        public long nextRowId = 1L;
    }
}

