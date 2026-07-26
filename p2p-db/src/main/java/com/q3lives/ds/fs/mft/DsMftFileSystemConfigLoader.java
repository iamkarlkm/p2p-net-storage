package com.q3lives.ds.fs.mft;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import org.yaml.snakeyaml.Yaml;

/**
 * DsMftFileSystem YAML 配置加载器。
 *
 * <p>默认查找当前目录下的 {@code dsfs.yaml}，可通过系统属性 {@code -Dds.fs.yaml=/path/to/config.yaml} 指定。</p>
 */
public final class DsMftFileSystemConfigLoader {
    private static final String SYS_PROP = "ds.fs.yaml";
    private static final String DEFAULT_NAME = "dsfs.yaml";

    private DsMftFileSystemConfigLoader() {
    }

    /**
     * 从默认位置或系统属性指定的 YAML 文件加载配置。
     */
    public static LoadedConfig load() {
        File yamlFile = resolveYamlFile();
        return loadFromFile(yamlFile);
    }

    /**
     * 从指定路径加载配置。
     */
    public static LoadedConfig loadFromFile(File yamlFile) {
        try (InputStream in = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            DsMftFileSystemConfig cfg = yaml.loadAs(in, DsMftFileSystemConfig.class);
            if (cfg == null) {
                cfg = new DsMftFileSystemConfig();
            }
            applyDefaults(cfg);
            normalizePaths(cfg, yamlFile);
            validate(cfg);
            return new LoadedConfig(cfg, yamlFile);
        } catch (Exception e) {
            throw new RuntimeException("failed to load fs config from " + yamlFile, e);
        }
    }

    /**
     * 从指定路径加载配置（便捷方法）。
     */
    public static LoadedConfig loadFromPath(Path path) {
        return loadFromFile(path.toFile());
    }

    private static void applyDefaults(DsMftFileSystemConfig cfg) {
        if (cfg.getTagsInitialRingCap() <= 0) {
            cfg.setTagsInitialRingCap(64);
        }
    }

    private static void normalizePaths(DsMftFileSystemConfig cfg, File yamlFile) {
        String ns = cfg.getNamespaceDir();
        if (ns == null || ns.isBlank()) {
            return;
        }
        Path p = Path.of(ns);
        if (!p.isAbsolute()) {
            File base = yamlFile.getParentFile();
            if (base == null) {
                base = new File(System.getProperty("user.dir", "."));
            }
            p = base.toPath().toAbsolutePath().normalize().resolve(p).normalize();
            cfg.setNamespaceDir(p.toString());
        } else {
            cfg.setNamespaceDir(p.toAbsolutePath().normalize().toString());
        }
    }

    private static void validate(DsMftFileSystemConfig cfg) {
        if (cfg.getNamespaceDir() == null || cfg.getNamespaceDir().isBlank()) {
            throw new IllegalArgumentException("namespaceDir is required");
        }
        if (cfg.getTagsInitialRingCap() <= 0) {
            throw new IllegalArgumentException("tagsInitialRingCap must be > 0");
        }
    }

    private static File resolveYamlFile() {
        String p = System.getProperty(SYS_PROP);
        if (p != null && !p.isBlank()) {
            return new File(p).getAbsoluteFile();
        }
        return new File(System.getProperty("user.dir", "."), DEFAULT_NAME).getAbsoluteFile();
    }

    public static final class LoadedConfig {
        public final DsMftFileSystemConfig config;
        public final File yamlFile;

        public LoadedConfig(DsMftFileSystemConfig config, File yamlFile) {
            this.config = config;
            this.yamlFile = yamlFile;
        }
    }
}
