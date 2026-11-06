package com.q3lives.ds.registry.meta;

import com.q3lives.ds.registry.DsRegistry;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 dbRoot 绝对路径单例持有 DsRegistry，避免每类 MetaStore 各自重复创建
 * （serverId=0, partitionId=0：单机单进程内 ORM 元数据默认 0/0，与 DsRegistryOrmBridge 构造一致）
 */
public final class DsRegistryInstance {

    private static final ConcurrentHashMap<String, DsRegistry> BY_ROOT = new ConcurrentHashMap<>();

    /**
     * @param dbRoot 数据库根目录（null/空抛 IAE）
     * @return 同一绝对路径下唯一 DsRegistry 实例
     */
    public static DsRegistry get(File dbRoot) throws IOException {
        Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        if (!dbRoot.exists()) {
            if (!dbRoot.mkdirs()) {
                throw new IOException("fail mkdirs dbRoot: " + dbRoot);
            }
        }
        String abs = dbRoot.getAbsolutePath();
        DsRegistry existing = BY_ROOT.get(abs);
        if (existing != null) return existing;
        synchronized (DsRegistryInstance.class) {
            existing = BY_ROOT.get(abs);
            if (existing != null) return existing;
            DsRegistry fresh = new DsRegistry(abs, 0, 0);
            BY_ROOT.put(abs, fresh);
            return fresh;
        }
    }

    /** 测试/迁移工具专用：强制 clear 所有 cached instances（JVM 不重启多轮测试隔离） */
    public static void clearForTests() {
        for (DsRegistry r : BY_ROOT.values()) {
            try { r.close(); } catch (Exception ignored) {}
        }
        BY_ROOT.clear();
    }

    private DsRegistryInstance() {}
}
