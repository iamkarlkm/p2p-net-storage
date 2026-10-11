package com.q3lives.ds.database.columnar;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.exception.meta.MetaStoreException;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;

/**
 * 列存 RowId 单调递增序列分配器（公开签名零改，内部 backend 三态路由）
 *
 * Hot path：allocate(entityClass) 预期 T6 性能 rubric 比原 YAML FileLock 快≥30%（Registry 原子 modifyLong 比 FileLock + parse/serialize 快）
 *
 * Registry 权威：/orm/column/<entityClass>/seq/nextRowId REG_QWORD 原子 CAS
 */
public final class RowIdSequenceStore {
    private final File dbRoot;
    private final Backend backend;

    public RowIdSequenceStore(File dbRoot) {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        try {
            this.backend = new Backend(dbRoot);
        } catch (java.io.IOException e) {
            throw new MetaStoreException("fail open RowIdSequence backend", e);
        }
    }

    public long allocate(String entityClassName) {
        if (entityClassName == null || entityClassName.isBlank()) {
            throw new IllegalArgumentException("entityClassName is blank");
        }
        File metaFile = metaFile(entityClassName);
        File lockFile = new File(metaFile.getParentFile(), "rowid.meta.lock");
        // 不用外显 lock：Registry 原子 modifyLong 自带 striped lock；YAML 用 atomicModifyLong yaml lock 路径
        try {
            return backend.allocateNext(entityClassName, 1L, () -> {
                if (!metaFile.isFile()) return Long.valueOf(1L);
                try (InputStream in = new FileInputStream(metaFile)) {
                    Yaml yaml = new Yaml();
                    SeqMeta meta = yaml.loadAs(in, SeqMeta.class);
                    if (meta == null || meta.nextRowId <= 0L) return Long.valueOf(1L);
                    return Long.valueOf(meta.nextRowId);
                }
            }, newVal -> {
                SeqMeta meta = new SeqMeta();
                meta.nextRowId = newVal.longValue() + 1L; // 存"下一次分配的值"，即 allocate 返回 newVal，序列前进 1
                saveMeta(metaFile, meta);
            });
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

    private static void saveMeta(File metaFile, SeqMeta meta) throws java.io.IOException {
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
                throw new java.io.IOException("saveMeta move failed: " + metaFile, e2);
            }
        }
    }

    public static final class SeqMeta {
        public long nextRowId = 1L;
    }

    // ================== Backend ==================
    private static final class Backend extends AbstractRegistryBackend {
        Backend(File dbRoot) throws java.io.IOException { super(dbRoot); }

        long allocateNext(String entityClass, long seedIfAbsent,
                          YamlSupplier<Long> yamlGetter,
                          YamlConsumer<Long> yamlSetterAfterIncrement) throws java.io.IOException {
            String path = RegistryMetaPaths.columnNextRowId(entityClass);
            long allocated = super.atomicModifyLong(path, prev -> prev + 1L, seedIfAbsent, true, yamlGetter, prev -> {
                // atomicModifyLong 传进来的是 prev（allocate 之前的 nextRowId）
                // atomicModifyLong 内部写 yaml 时用 setter(Long n)，n 是新值(prev+1)；因此要在 setter 里写 newVal+1？
                // → 修正：atomicModifyLong op=prev→prev+1，写入的 n 已经是 prev+1；等价于分配出去的是 prev，存到 YAML 的 nextRowId 应该是 n = prev+1（恰好新值）
                // 但原行为是 SeqMeta.nextRowId 存储的是"下一次分配的值"。原 allocate：out=meta.nextRowId, meta.nextRowId=out+1, save。
                // 所以 op=prev→prev+1：分配出去 prev（下一次分配 prev+1）。存 yaml 时 setter 接收的 n 是 prev+1 → 应存 n 作为 nextRowId。
                yamlSetterAfterIncrement.accept(Long.valueOf(prev.longValue()));
            });
            // allocated 是 atomicModifyLong 返回的"新值" n=prev+1，对应 op 输出。分配出去的应该是 prev（即 allocated-1）
            return allocated - 1L;
        }
    }
}
