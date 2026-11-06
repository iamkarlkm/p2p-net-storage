package com.q3lives.ds.registry.meta;

/**
 * ORM 元数据后端三态模式（JVM 启动后 static final，运行时不可切）
 * 读 JVM 属性：p2p.orm.meta.mode = yaml | registry | hybrid（忽略大小写，缺省 hybrid）
 */
public enum MetaMode {
    /** 纯原 YAML 实现，零 Registry 依赖（回退模式） */
    YAML,
    /** 纯 DsRegistry 后端（新库 / 已迁移的库）*/
    REGISTRY,
    /** 双写优先读 Registry：写入双份，读取 Registry miss 再读 YAML 并回填 Registry；老库迁移过渡模式（默认） */
    HYBRID;

    public static final String PROPERTY = "p2p.orm.meta.mode";
    public static final MetaMode DEFAULT = HYBRID;

    private static final MetaMode GLOBAL = resolve();

    public static MetaMode global() { return GLOBAL; }

    public static boolean isGlobalRegistryOrHybrid() {
        return GLOBAL == REGISTRY || GLOBAL == HYBRID;
    }

    public static boolean isGlobalYamlOrHybrid() {
        return GLOBAL == YAML || GLOBAL == HYBRID;
    }

    private static MetaMode resolve() {
        String raw = System.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT;
        String v = raw.trim().toLowerCase();
        switch (v) {
            case "yaml": return YAML;
            case "registry": return REGISTRY;
            case "hybrid": return HYBRID;
            default:
                System.err.println("[MetaMode] WARN 未知 mode=" + raw + "，fallback 默认 " + DEFAULT);
                return DEFAULT;
        }
    }
}
