package com.q3lives.ds.database.startup;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

final class DsDbStartupCheckConfig {
    final boolean enabled;
    final boolean strict;
    final List<String> entityClasses;
    final List<String> entityPackages;
    final boolean failOnSuperclassRelationFields;

    private DsDbStartupCheckConfig(
        boolean enabled,
        boolean strict,
        List<String> entityClasses,
        List<String> entityPackages,
        boolean failOnSuperclassRelationFields
    ) {
        this.enabled = enabled;
        this.strict = strict;
        this.entityClasses = entityClasses;
        this.entityPackages = entityPackages;
        this.failOnSuperclassRelationFields = failOnSuperclassRelationFields;
    }

    static DsDbStartupCheckConfig load() {
        Map<String, Object> root = loadSystemConfigYaml();
        if (root == null) {
            return disabled();
        }
        Object raw = root.get("DsDbStartupCheck");
        if (!(raw instanceof Map<?, ?> m)) {
            return disabled();
        }
        boolean enabled = asBool(m.get("enabled"), false);
        boolean strict = asBool(m.get("strict"), true);
        boolean failOnSuperclassRelationFields = asBool(m.get("failOnSuperclassRelationFields"), true);
        List<String> entityClasses = asStringList(m.get("entityClasses"));
        List<String> entityPackages = asStringList(m.get("entityPackages"));
        return new DsDbStartupCheckConfig(enabled, strict, entityClasses, entityPackages, failOnSuperclassRelationFields);
    }

    private static DsDbStartupCheckConfig disabled() {
        return new DsDbStartupCheckConfig(false, true, List.of(), List.of(), true);
    }

    private static Map<String, Object> loadSystemConfigYaml() {
        Path yaml = resolveSystemYamlPath();
        if (yaml == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            Object obj = new Yaml().load(in);
            if (obj instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> out = (Map<String, Object>) map;
                return out;
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load system yaml: " + yaml + ", " + e.getMessage(), e);
        }
    }

    private static Path resolveSystemYamlPath() {
        String sys = System.getProperty("p2p.system.yaml");
        if (sys != null && !sys.isBlank()) {
            Path p = Paths.get(sys);
            if (Files.exists(p)) {
                return p;
            }
        }
        Path cwd = Paths.get(System.getProperty("user.dir", "."));
        Path p = cwd.resolve("SystemConfig.yaml");
        if (Files.exists(p)) {
            return p;
        }
        return null;
    }

    private static boolean asBool(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return def;
    }

    private static List<String> asStringList(Object v) {
        if (!(v instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        ArrayList<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o == null) {
                continue;
            }
            String s = String.valueOf(o).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }
}

