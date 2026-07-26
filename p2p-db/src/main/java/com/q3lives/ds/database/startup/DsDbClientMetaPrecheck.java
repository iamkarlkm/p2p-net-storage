package com.q3lives.ds.database.startup;

import com.q3lives.ds.database.DsDatabaseServer;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.config.DsDatabaseClientConfig;
import com.q3lives.ds.util.DsPathUtil;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.net.p2p.model.DbMetaGetResponse;
import org.yaml.snakeyaml.Yaml;

public final class DsDbClientMetaPrecheck {
    private DsDbClientMetaPrecheck() {
    }

    public static void runOrThrow(DsDatabaseServer server, File localDbRoot, DsDatabaseClientConfig.MetaCheck cfg) {
        if (cfg == null || !cfg.enabled) {
            return;
        }
        if (localDbRoot == null) {
            if (cfg.strict) {
                throw new IllegalStateException("metaCheck enabled but local.dbHome is not configured");
            }
            return;
        }
        List<String> entities = resolveEntityClassNames(cfg);
        if (entities.isEmpty()) {
            if (cfg.strict) {
                throw new IllegalStateException("metaCheck enabled but entityClasses/entityPackages is empty");
            }
            return;
        }
        ArrayList<String> issues = new ArrayList<>();
        for (String entityClassName : entities) {
            if (entityClassName == null || entityClassName.isBlank()) {
                continue;
            }
            checkOne(server, localDbRoot, cfg, entityClassName, issues);
        }
        if (!issues.isEmpty() && cfg.strict) {
            int limit = Math.min(30, issues.size());
            throw new IllegalStateException("client meta precheck failed (" + issues.size() + "): " + issues.subList(0, limit));
        }
    }

    private static void checkOne(
        DsDatabaseServer server,
        File localDbRoot,
        DsDatabaseClientConfig.MetaCheck cfg,
        String entityClassName,
        List<String> issues
    ) {
        File dir = metaDir(localDbRoot, entityClassName);
        File localTable = new File(dir, "table.meta.yaml");
        File localColumns = new File(dir, "columns.meta.yaml");

        byte[] localTableBytes = readIfExists(localTable);
        byte[] localColumnsBytes = readIfExists(localColumns);
        if (cfg.requireCache && (localTableBytes == null || localColumnsBytes == null)) {
            issues.add(entityClassName + ": missing local cached meta files");
            return;
        }

        DbMetaGetResponse remote;
        try {
            remote = server.getMeta(entityClassName, cfg.ensureFresh);
        } catch (Exception e) {
            issues.add(entityClassName + ": remote meta get failed: " + e.getMessage());
            return;
        }
        byte[] remoteTableBytes = remote == null ? null : remote.tableMetaYaml;
        byte[] remoteColumnsBytes = remote == null ? null : remote.columnsMetaYaml;

        String localTableDigest = sha256Hex(localTableBytes);
        String localColumnsDigest = sha256Hex(localColumnsBytes);
        String remoteTableDigest = normalizeHex(remote == null ? null : remote.tableMetaSha256);
        String remoteColumnsDigest = normalizeHex(remote == null ? null : remote.columnsMetaSha256);

        boolean tableMismatch = isMismatch(localTableDigest, remoteTableDigest, localTableBytes, remoteTableBytes);
        boolean columnsMismatch = isMismatch(localColumnsDigest, remoteColumnsDigest, localColumnsBytes, remoteColumnsBytes);

        if (tableMismatch || columnsMismatch) {
            issues.add(entityClassName + ": meta changed");
            if (cfg.strict) {
                return;
            }
        }

        if (remoteTableBytes != null || remoteColumnsBytes != null) {
            writeMetaFiles(localDbRoot, entityClassName, remoteTableBytes, remoteColumnsBytes);
        }
    }

    private static boolean isMismatch(String localDigest, String remoteDigest, byte[] localBytes, byte[] remoteBytes) {
        if (localDigest != null && remoteDigest != null) {
            return !localDigest.equalsIgnoreCase(remoteDigest);
        }
        String localSig = signatureOf(localBytes);
        String remoteSig = signatureOf(remoteBytes);
        if (localSig == null || remoteSig == null) {
            return false;
        }
        return !localSig.equals(remoteSig);
    }

    private static List<String> resolveEntityClassNames(DsDatabaseClientConfig.MetaCheck cfg) {
        ArrayList<String> out = new ArrayList<>();
        if (cfg.entityClasses != null) {
            out.addAll(cfg.entityClasses);
        }
        List<Class<? extends DsTableAdapter>> scanned = ClassPathEntityScanner.scanPackages(cfg.entityPackages == null ? List.of() : cfg.entityPackages);
        for (Class<? extends DsTableAdapter> c : scanned) {
            if (c != null) {
                out.add(c.getName());
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static byte[] readIfExists(File f) {
        if (f == null || !f.exists() || !f.isFile()) {
            return null;
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            throw new IllegalStateException("read meta failed: " + f.getAbsolutePath() + ", " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String normalizeHex(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        return s.isEmpty() ? null : s;
    }

    private static String signatureOf(byte[] tableMetaYaml) {
        if (tableMetaYaml == null || tableMetaYaml.length == 0) {
            return null;
        }
        try {
            String text = new String(tableMetaYaml, StandardCharsets.UTF_8);
            Object obj = new Yaml().load(text);
            if (obj instanceof Map<?, ?> m) {
                Object sig = m.get("signature");
                if (sig == null) {
                    return null;
                }
                String s = String.valueOf(sig);
                return s.isBlank() ? null : s;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static File metaDir(File dbRoot, String entityClassName) {
        String spacePath = DsPathUtil.dottedToLinuxPath(entityClassName, "entityClass");
        return new File(dbRoot, "indexes/" + spacePath);
    }

    private static void writeMetaFiles(File dbRoot, String entityClassName, byte[] tableMetaYaml, byte[] columnsMetaYaml) {
        File dir = metaDir(dbRoot, entityClassName);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        try {
            if (tableMetaYaml != null) {
                Files.write(new File(dir, "table.meta.yaml").toPath(), tableMetaYaml);
            }
            if (columnsMetaYaml != null) {
                Files.write(new File(dir, "columns.meta.yaml").toPath(), columnsMetaYaml);
            }
        } catch (Exception e) {
            throw new IllegalStateException("write meta failed: " + entityClassName + ", " + e.getMessage(), e);
        }
    }
}
