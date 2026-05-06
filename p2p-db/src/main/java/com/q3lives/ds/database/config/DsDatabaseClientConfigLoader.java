package com.q3lives.ds.database.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import org.yaml.snakeyaml.Yaml;

public final class DsDatabaseClientConfigLoader {
    private static final String SYS_PROP = "ds.db.yaml";
    private static final String DEFAULT_NAME = "dsdb.yaml";
    
    private DsDatabaseClientConfigLoader() {
    }
    
    public static LoadedConfig load() {
        File yamlFile = resolveYamlFile();
        try (InputStream in = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            DsDatabaseClientConfig cfg = yaml.loadAs(in, DsDatabaseClientConfig.class);
            if (cfg == null) {
                cfg = new DsDatabaseClientConfig();
            }
            return new LoadedConfig(cfg, yamlFile);
        } catch (Exception e) {
            throw new RuntimeException(e);
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
        public final DsDatabaseClientConfig config;
        public final File yamlFile;
        
        public LoadedConfig(DsDatabaseClientConfig config, File yamlFile) {
            this.config = config;
            this.yamlFile = yamlFile;
        }
    }
}

