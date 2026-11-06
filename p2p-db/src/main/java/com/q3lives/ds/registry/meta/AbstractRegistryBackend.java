package com.q3lives.ds.registry.meta;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;
import com.q3lives.ds.registry.RegistryEntry;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongUnaryOperator;

/**
 * ORM 元数据后端统一抽象：
 *   - 全局 MetaMode（yaml / registry / hybrid）static final，运行时不可改
 *   - 提供 readLeafString / readLeafLong / writeLeafString / writeLeafLong / atomicModifyLong 5 个 typed helper
 *   - hybrid：写双份；读优先 registry，miss 则读 yaml（子类实现）并回填 registry
 *
 * 子类（每个 Metastore 一个 backend）负责实现 yaml 侧的读写；
 * 公共 registry 侧读写统一放在这里，避免散 4 处 if(mode) 代码。
 */
public abstract class AbstractRegistryBackend {

    protected final File dbRoot;
    protected final DsRegistry registry;
    protected final MetaMode mode;

    public MetaMode mode() { return mode; }

    protected AbstractRegistryBackend(File dbRoot) throws IOException {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot").getAbsoluteFile();
        this.mode = MetaMode.global();
        this.registry = MetaMode.isGlobalRegistryOrHybrid()
            ? DsRegistryInstance.get(this.dbRoot)
            : null;
    }

    // =================== Public helpers for MetaStore impl ===================

    /** 读 String leaf：按 mode 路由，hybrid miss 时回填 Registry */
    protected final String readLeafString(String registryPath,
                                          YamlSupplier<String> yamlGetter,
                                          String defaultValue) throws IOException {
        // 1) try Registry if allowed
        if (mode != MetaMode.YAML) {
            RegistryEntry e = registry.get(registryPath);
            if (e != null && e.isPresent()) {
                String s = e.asString();
                if (s != null) return s;
            }
        }
        // 2) Registry miss → try YAML
        if (mode != MetaMode.REGISTRY) {
            String v = yamlGetter.get();
            if (v != null) {
                // HYBRID: lazy 回填
                if (mode == MetaMode.HYBRID) {
                    writeRegistryString(registryPath, v);
                }
                return v;
            }
        }
        return defaultValue;
    }

    /** 读 Long（REG_DWORD/REG_QWORD 通用）leaf：按 mode 路由 */
    protected final Long readLeafLong(String registryPath,
                                      YamlSupplier<Long> yamlGetter,
                                      Long defaultValue) throws IOException {
        if (mode != MetaMode.YAML) {
            RegistryEntry e = registry.get(registryPath);
            if (e != null && e.isPresent()) {
                // long 存 REG_DWORD/REG_QWORD，asLong() 都能读
                try { return Long.valueOf(e.asLong()); } catch (Exception ignored) {}
            }
        }
        if (mode != MetaMode.REGISTRY) {
            Long v = yamlGetter.get();
            if (v != null) {
                if (mode == MetaMode.HYBRID) {
                    writeRegistryLong(registryPath, v, DataTypeId.REG_QWORD);
                }
                return v;
            }
        }
        return defaultValue;
    }

    /** 写 String leaf：双写（HYBRID）或单边（YAML/REGISTRY） */
    protected final void writeLeafString(String registryPath,
                                         String value,
                                         YamlConsumer<String> yamlSetter) throws IOException {
        if (mode != MetaMode.YAML) {
            writeRegistryString(registryPath, value);
        }
        if (mode != MetaMode.REGISTRY) {
            yamlSetter.accept(value);
        }
    }

    /** 写 Long leaf（qword=REG_QWORD 否则 REG_DWORD） */
    protected final void writeLeafLong(String registryPath,
                                       long value,
                                       boolean qword,
                                       YamlConsumer<Long> yamlSetter) throws IOException {
        if (mode != MetaMode.YAML) {
            writeRegistryLong(registryPath, value, qword ? DataTypeId.REG_QWORD : DataTypeId.REG_DWORD);
        }
        if (mode != MetaMode.REGISTRY) {
            yamlSetter.accept(Long.valueOf(value));
        }
    }

    /** 判断 leaf 是否存在（存在任一后端就 true）*/
    protected final boolean leafExists(String registryPath,
                                       YamlSupplier<Boolean> yamlExists) throws IOException {
        if (mode != MetaMode.YAML) {
            if (registry.exists(registryPath)) return true;
        }
        if (mode != MetaMode.REGISTRY) {
            Boolean b = yamlExists.get();
            if (b != null && b.booleanValue()) return true;
        }
        return false;
    }

    /** 删除 leaf（双删） */
    protected final boolean removeLeaf(String registryPath,
                                       YamlSupplier<Boolean> yamlRemover) throws IOException {
        boolean ok = false;
        if (mode != MetaMode.YAML) {
            ok |= registry.remove(registryPath, false);
        }
        if (mode != MetaMode.REGISTRY) {
            Boolean r = yamlRemover.get();
            if (r != null) ok |= r.booleanValue();
        }
        return ok;
    }

    /** mkdirs：创建 Registry 目录节点（mode=YAML 时 no-op） */
    protected final void mkdirs(String registryDir) throws IOException {
        if (mode != MetaMode.YAML) {
            registry.mkdirs(registryDir);
        }
    }

    /** 列出 directory 下 children（优先 registry，空则 yaml 侧 fallback listChildren） */
    protected final List<String> listChildren(String registryDir,
                                              YamlSupplier<List<String>> yamlLister) throws IOException {
        if (mode != MetaMode.YAML) {
            List<String> names = registry.listChildren(registryDir);
            if (names != null && !names.isEmpty()) return names;
        }
        if (mode != MetaMode.REGISTRY) {
            List<String> names = yamlLister.get();
            if (names != null && !names.isEmpty()) {
                return names;
            }
        }
        return java.util.Collections.emptyList();
    }

    /** 原子 modify Long（CAS retry，跨线程安全）。
     *  YAML 侧：通过 synchronized (yamlLock()) 保证原子 + 双写/回填。 */
    protected final long atomicModifyLong(String registryPath,
                                          LongUnaryOperator op,
                                          long seedIfAbsent,
                                          boolean qword,
                                          YamlSupplier<Long> yamlGetter,
                                          YamlConsumer<Long> yamlSetter) throws IOException {
        // 选最终 backend（REGISTRY/HYBRID → 用 Registry CAS 做权威；YAML → synchronized yaml）
        if (mode == MetaMode.YAML) {
            synchronized (yamlLock()) {
                Long cur = yamlGetter.get();
                long c = (cur == null) ? seedIfAbsent : cur.longValue();
                long n = op.applyAsLong(c);
                yamlSetter.accept(Long.valueOf(n));
                return n;
            }
        }
        // REGISTRY/HYBRID：Registry CAS 权威
        long newVal = registryAtomicModifyLong(registryPath, op, seedIfAbsent, qword);
        // HYBRID：同步 yaml（非原子但 Registry 已经是权威；失败打印 WARN 不抛，保持可回退）
        if (mode == MetaMode.HYBRID) {
            try {
                yamlSetter.accept(Long.valueOf(newVal));
            } catch (Exception ex) {
                System.err.println("[MetaBackend] WARN yaml 同步失败(Registry CAS 已成功) path="
                    + registryPath + " reason=" + ex.getMessage());
            }
        }
        return newVal;
    }

    // ---------- TAG helpers（P7 ExtAttrs DsRegistryExtAttrsHelper 复用）----------

    protected final void addTag(String registryDir, String tag) throws IOException {
        if (mode == MetaMode.YAML) return; // yaml 侧未实现 TAG 同步（非权威）
        mkdirs(registryDir);
        DsRegistryExtAttrsHelper.addTag(registry, registryDir, tag);
    }

    protected final boolean hasTag(String registryDir, String tag) throws IOException {
        if (mode == MetaMode.YAML) return false;
        return DsRegistryExtAttrsHelper.hasTag(registry, registryDir, tag);
    }

    protected final void removeTag(String registryDir, String tag) throws IOException {
        if (mode == MetaMode.YAML) return;
        DsRegistryExtAttrsHelper.removeTag(registry, registryDir, tag);
    }

    protected final void removeAllTags(String registryDir) throws IOException {
        if (mode == MetaMode.YAML) return;
        DsRegistryExtAttrsHelper.removeAllTags(registry, registryDir);
    }

    // =================== Subclass implement YAML side ========================

    /** YAML 侧全局锁（避免同文件并发）。子类可 override 成 finer grain lock。 */
    protected Object yamlLock() { return this; }

    @FunctionalInterface
    public interface YamlSupplier<T> { T get() throws IOException; }

    @FunctionalInterface
    public interface YamlConsumer<T> { void accept(T t) throws IOException; }

    // =================== Internal helpers ====================================

    private void writeRegistryString(String path, String value) throws IOException {
        // REG_SZ：putString(path, value, REG_SZ)
        registry.mkdirs(parentOf(path));
        registry.putString(path, value, DataType.REG_SZ);
    }

    private void writeRegistryLong(String path, long value, int dataTypeId) throws IOException {
        registry.mkdirs(parentOf(path));
        DataType t = (dataTypeId == DataTypeId.REG_QWORD) ? DataType.REG_QWORD : DataType.REG_DWORD;
        registry.putLong(path, t, value);
    }

    private long registryAtomicModifyLong(String path, LongUnaryOperator op, long seedIfAbsent, boolean qword)
        throws IOException {
        // DsRegistry 缺 putIfAbsent/CAS；采用 P8 striped locks 风格的 per-path CAS retry（最多 256 次 attempt）
        int attempts = 256;
        String dir = parentOf(path);
        registry.mkdirs(dir);
        Object lock = lockFor(path);
        synchronized (lock) {
            for (int i = 0; i < attempts; i++) {
                long cur;
                RegistryEntry e = registry.get(path);
                if (e == null || !e.isPresent()) {
                    cur = seedIfAbsent;
                } else {
                    try { cur = e.asLong(); } catch (Exception ex) { cur = seedIfAbsent; }
                }
                long n = op.applyAsLong(cur);
                // 写入并 verify（CAS 语义）
                writeRegistryLong(path, n, qword ? DataTypeId.REG_QWORD : DataTypeId.REG_DWORD);
                RegistryEntry verify = registry.get(path);
                if (verify != null && verify.isPresent()) {
                    try {
                        if (verify.asLong() == n) return n;
                    } catch (Exception ignored) {}
                }
            }
            throw new IOException("atomicModifyLong too many collisions path=" + path);
        }
    }

    private static final Object[] LOCKS = new Object[256];
    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }
    private static Object lockFor(String path) {
        int h = (path == null) ? 0 : path.hashCode();
        return LOCKS[(h & 0x7fffffff) % LOCKS.length];
    }

    private static String parentOf(String path) {
        if (path == null || path.isEmpty()) return "/";
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "/";
        if (slash == 0) return "/";
        return path.substring(0, slash);
    }

    private static final class DataTypeId {
        static final int REG_DWORD = 1;
        static final int REG_QWORD = 2;
    }
}
