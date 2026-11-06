package com.q3lives.ds.registry.meta;

import java.io.IOException;
import java.util.Objects;
import java.util.function.UnaryOperator;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.RegistryEntry;

/**
 * P11 OQ1：DML 真实数据（Row/Column/Group payload bytes）Backend 抽象。
 * 继承 P10 已交付 AbstractRegistryBackend（String/Long 路由 + per-path 256 striped lock），
 * 新增 4 个 binary typed helper + 1 个 atomic CAS helper，路由风格与 String/Long 完全一致：
 *
 *   - HYBRID 模式：双写双读；读优先 Registry miss→读 bucket→writeRegistryBytes 回填→返回原字节；
 *   - REGISTRY 模式：只 Registry；
 *   - YAML 模式：只 bucket（完全短路，零 new Registry 实例，与 P10 收官基线字节级等价）。
 *
 * 子类（RowDataBackend / ColumnDataBackend / GroupDataBackend 每个 Metastore 一个）
 * 负责实现 bucket side 的 YamlSupplier<byte[]> / YamlConsumer<byte[]>；公共 registry 侧逻辑全部落在这里，
 * 业务 DsDatabaseLocal / ColumnarStore 不得直接 if (mode)，避免散 3 处模式判断。
 */
public abstract class AbstractRegistryDataBackend extends AbstractRegistryBackend {

    protected AbstractRegistryDataBackend(java.io.File dbRoot) throws IOException {
        super(dbRoot);
    }

    // =================== Public binary helpers (MetaMode routing + HYBRID backfill) ===================

    /**
     * 读 byte[] leaf：按 mode 路由。
     *   - REGISTRY/HYBRID：优先读 Registry（REG_BINARY / REG_BINARY_ZIP / REG_BINARY_SETUP 等 asBytes() 统一处理）；
     *   - HYBRID miss：bucketGetter.get() 读 bucket → 成功则 writeRegistryBytes 回填 → 返回原 byte[]；
     *   - YAML：仅 bucketGetter。
     * 三侧都 miss → 返回 defaultValue（null 或调用方提供 tombstone）。
     */
    protected final byte[] readLeafBytes(String registryPath,
                                         YamlSupplier<byte[]> bucketGetter,
                                         byte[] defaultValue) throws IOException {
        if (mode != MetaMode.YAML) {
            RegistryEntry e = registry.get(registryPath);
            if (e != null && e.isPresent()) {
                byte[] b = e.asBytes();
                if (b != null) return b;
            }
        }
        if (mode != MetaMode.REGISTRY) {
            byte[] v = bucketGetter.get();
            if (v != null) {
                if (mode == MetaMode.HYBRID) {
                    writeRegistryBytes(registryPath, v);
                }
                return v;
            }
        }
        return defaultValue;
    }

    /**
     * 写 byte[] leaf：双写或单边。
     *   - REGISTRY/HYBRID：registry 写成功（权威）；
     *   - HYBRID bucketConsumer.accept(v) 抛异常：WARN 不抛（Registry 已写权威可回退），日志含 4 要素；
     *   - YAML：仅 bucketConsumer。
     * WARN 仅用于可观测性，不会破坏写入语义（Registry 权威返回成功即视为双写成功）。
     */
    protected final void writeLeafBytes(String registryPath,
                                        byte[] value,
                                        YamlConsumer<byte[]> bucketConsumer) throws IOException {
        byte[] v = (value == null) ? new byte[0] : value;
        if (mode != MetaMode.YAML) {
            writeRegistryBytes(registryPath, v);
        }
        if (mode != MetaMode.REGISTRY) {
            try {
                bucketConsumer.accept(v);
            } catch (Exception ex) {
                String root = (dbRoot == null) ? "<null-root>" : dbRoot.getAbsolutePath();
                String simplified = simplify(registryPath);
                System.err.println("[DataBackend] WARN HYBRID bucket 写失败(Registry 已写权威，可回退对齐脚本恢复) "
                    + " registryPath=" + simplified + " rootDir=" + root
                    + " bytesLen=" + v.length + " reason=" + ex.getMessage());
            }
        }
    }

    /**
     * 删除 leaf（Registry remove + bucket 双删）。任一后端 true → overall ok=true。
     */
    protected final boolean removeLeafBytes(String registryPath,
                                            YamlSupplier<Boolean> bucketRemover) throws IOException {
        boolean ok = false;
        if (mode != MetaMode.YAML) {
            ok |= registry.remove(registryPath, false);
        }
        if (mode != MetaMode.REGISTRY) {
            Boolean r = bucketRemover.get();
            if (r != null) ok |= r.booleanValue();
        }
        return ok;
    }

    /** 存在性判断：任一后端存在 → true */
    protected final boolean leafBytesExists(String registryPath,
                                            YamlSupplier<Boolean> bucketExistsSupplier) throws IOException {
        if (mode != MetaMode.YAML) {
            if (registry.exists(registryPath)) return true;
        }
        if (mode != MetaMode.REGISTRY) {
            Boolean b = bucketExistsSupplier.get();
            if (b != null && b.booleanValue()) return true;
        }
        return false;
    }

    // =================== Atomic CAS helper（预留 OQ3 事务） ===================

    /**
     * 原子修改 byte[]：per-path striped lock（复用父类 256-entry LOCKS），
     * 最多 attempt=256 次；每次读→op.apply→write→verify 闭环。
     * REGISTRY/HYBRID 以 Registry CAS 为权威；HYBRID CAS 成功后同步 bucket，失败 WARN（不抛）。
     * YAML 模式下使用 synchronized (yamlLock()) 保护桶侧文件读写。
     */
    protected final byte[] atomicModifyBytes(String registryPath,
                                             UnaryOperator<byte[]> op,
                                             byte[] seedIfAbsent,
                                             YamlSupplier<byte[]> bucketGetter,
                                             YamlConsumer<byte[]> bucketSetter) throws IOException {
        Objects.requireNonNull(op, "op");
        if (mode == MetaMode.YAML) {
            synchronized (yamlLock()) {
                byte[] cur = bucketGetter.get();
                byte[] c = (cur == null) ? cloneBytes(seedIfAbsent) : cur;
                byte[] n = op.apply(c);
                byte[] res = cloneBytes(n);
                bucketSetter.accept(res);
                return res;
            }
        }
        final int attempts = 256;
        Object lock = lockForInternal(registryPath);
        synchronized (lock) {
            String dir = parentOfInternal(registryPath);
            registry.mkdirs(dir);
            for (int i = 0; i < attempts; i++) {
                byte[] cur;
                RegistryEntry e = registry.get(registryPath);
                if (e == null || !e.isPresent()) {
                    cur = cloneBytes(seedIfAbsent);
                } else {
                    byte[] eb = e.asBytes();
                    cur = (eb == null) ? cloneBytes(seedIfAbsent) : eb;
                }
                byte[] n = op.apply(cur);
                byte[] nb = (n == null) ? new byte[0] : n;
                writeRegistryBytes(registryPath, nb);
                RegistryEntry verify = registry.get(registryPath);
                if (verify != null && verify.isPresent()) {
                    byte[] vb = verify.asBytes();
                    if (vb != null && java.util.Arrays.equals(vb, nb)) {
                        if (mode == MetaMode.HYBRID) {
                            try {
                                bucketSetter.accept(nb);
                            } catch (Exception ex) {
                                String root = (dbRoot == null) ? "<null>" : dbRoot.getAbsolutePath();
                                System.err.println("[DataBackend] WARN HYBRID CAS 后 bucket 同步失败"
                                    + " path=" + simplify(registryPath) + " root=" + root
                                    + " reason=" + ex.getMessage());
                            }
                        }
                        return nb;
                    }
                }
            }
            throw new IOException("atomicModifyBytes too many CAS collisions (256) path=" + simplify(registryPath));
        }
    }

    // =================== Internal helpers（复用父类工具，同包可见 + 反射安全） ===================

    private void writeRegistryBytes(String path, byte[] value) throws IOException {
        byte[] v = (value == null) ? new byte[0] : value;
        registry.mkdirs(parentOfInternal(path));
        DataType t = DataType.REG_BINARY;
        if (v.length <= 8) {
            // ≤8B 的小 payload 直接 INLINE（DsRegistry.putBytes 内部处理，这里保持 REG_BINARY 统一）
        }
        registry.putBytes(path, v, t);
    }

    private static byte[] cloneBytes(byte[] src) {
        if (src == null) return new byte[0];
        byte[] r = new byte[src.length];
        System.arraycopy(src, 0, r, 0, src.length);
        return r;
    }

    private static String simplify(String p) {
        if (p == null || p.length() <= 96) return p;
        return "..." + p.substring(p.length() - 92);
    }

    // 复用父类 LOCKS / parentOf 等 protected helper（子类同包可直接访问；
    // 但父类 lockFor/parentOf 是 private，这里用等价实现避免反射）
    private static final Object[] LOCKS = new Object[256];
    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }
    private static Object lockForInternal(String path) {
        int h = (path == null) ? 0 : path.hashCode();
        return LOCKS[(h & 0x7fffffff) % LOCKS.length];
    }
    private static String parentOfInternal(String path) {
        if (path == null || path.isEmpty()) return "/";
        int slash = path.lastIndexOf('/');
        if (slash < 0) return "/";
        if (slash == 0) return "/";
        return path.substring(0, slash);
    }

    // 为子类暴露 DsRegistry 只读引用（仅允许调用方 verify roundtrip 或 debug，禁止业务直接绕 Backend 写 Registry）
    protected final DsRegistry registryReadOnlyForVerify() { return registry; }
}
