package com.q3lives.ds.registry.meta;

import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * P10 T9 DsDatabaseMetaMigrator
 * 可逆迁移工具：yaml ↔ registry 双向，dryRun 不写盘，SHA256 manifest 一致性校验。
 * 约束：不改业务数据，仅改元数据描述与指向（符合 NonGoal #1）。
 *
 * 支持的 YAML 模式（基于约定，列在 /orm/row/ 与 /orm/column/ 下）：
 *  1) Row ClassIdSet: <entityClass>.ids.meta.yaml → /orm/row/<entity>/idSet/*
 *  2) Row Index:    indexes/<space>/idx/*.idx.yaml → /orm/row/<entity>/indexes/*
 *  3) Column TableMeta:   columnar/<space>/tables/<entity>.table.yaml → /orm/column/<entity>/table/*
 *  4) Column ColumnRegistry: columnar/<space>/columns/<entity>.columns.meta.yaml → /orm/column/<entity>/columns/*
 *  5) Column RowIdSeq:  columnar/<space>/sequences/<entity>.rowid.meta.yaml → /orm/column/<entity>/seq/*
 *  6) Column EqIndexMeta: indexes/<space>/idx/eq_*.eq.idx.yaml → /orm/column/<entity>/indexes/eq/*
 */
public class DsDatabaseMetaMigrator implements AutoCloseable {

    public static final String MANIFEST_FILE = ".meta-migration-manifest.yaml";
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_$.\\-]+$");

    private final File dbRoot;
    private final DsRegistry registry;
    private final boolean dryRun;
    private final List<String> logEntries = new ArrayList<>();

    public DsDatabaseMetaMigrator(File dbRoot) throws java.io.IOException {
        this(dbRoot, false);
    }

    public DsDatabaseMetaMigrator(File dbRoot, boolean dryRun) throws java.io.IOException {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        this.registry = DsRegistryInstance.get(dbRoot);
        this.dryRun = dryRun;
        if (!this.dbRoot.exists() || !this.dbRoot.isDirectory()) {
            throw new java.io.FileNotFoundException("dbRoot 不是目录: " + dbRoot);
        }
    }

    public boolean isDryRun() { return dryRun; }
    public List<String> lastLog() { return Collections.unmodifiableList(logEntries); }

    // ================= migrate 主入口 =================

    public MigrateReport migrateAll() throws java.io.IOException {
        logEntries.clear();
        MigrateReport report = new MigrateReport();
        Map<String, String> manifest = new LinkedHashMap<>();

        File manifestFile = manifestFile();
        if (manifestFile.isFile()) {
            throw new java.io.IOException("发现已有 manifest，先 rollbackAll 再 migrate: " + manifestFile);
        }

        // ROW ClassIdSet
        scanFor(
            root -> listFilesBySuffix(root, ".ids.meta.yaml"),
            this::migrateRowIdSet,
            manifest, report
        );

        // ROW Index
        scanFor(
            root -> listFilesBySuffixRecursive(new File(root, "indexes"), ".idx.yaml",
                /*excludeEq*/ true),
            this::migrateRowIndex,
            manifest, report
        );

        // COLUMN TableMeta
        scanFor(
            root -> listFilesBySuffixRecursive(new File(root, "columnar"), ".table.yaml", false),
            this::migrateColumnTableMeta,
            manifest, report
        );

        // COLUMN ColumnRegistry
        scanFor(
            root -> listFilesBySuffixRecursive(new File(root, "columnar"), ".columns.meta.yaml", false),
            this::migrateColumnRegistry,
            manifest, report
        );

        // COLUMN RowIdSeq
        scanFor(
            root -> listFilesBySuffixRecursive(new File(root, "columnar"), ".rowid.meta.yaml", false),
            this::migrateColumnRowIdSeq,
            manifest, report
        );

        // COLUMN EqIndexMeta
        scanFor(
            root -> listFilesBySuffixRecursive(new File(root, "indexes"), ".eq.idx.yaml", false),
            this::migrateColumnEqIndex,
            manifest, report
        );

        report.manifestFile = manifestFile.getAbsolutePath();
        writeManifest(manifestFile, manifest, "migrate");
        report.migrated = manifest.size();
        return report;
    }

    public MigrateReport rollbackAll() throws java.io.IOException {
        logEntries.clear();
        MigrateReport report = new MigrateReport();
        File manifestFile = manifestFile();
        if (!manifestFile.isFile()) {
            throw new java.io.FileNotFoundException("manifest 不存在，无法 rollback: " + manifestFile);
        }
        Manifest manifest = loadManifest(manifestFile);
        if (!"migrate".equals(manifest.phase)) {
            throw new java.io.IOException("manifest phase= " + manifest.phase + " != migrate，拒绝 rollback");
        }

        // 反向遍历，从最后一个迁移的开始
        List<Map.Entry<String, String>> entries = new ArrayList<>(manifest.items.entrySet());
        Collections.reverse(entries);
        for (Map.Entry<String, String> e : entries) {
            String yamlPath = e.getKey();
            String expectedSha256 = e.getValue();
            File migrated = new File(yamlPath + ".migrated");
            if (!migrated.isFile()) {
                warn("ROLLBACK skip（无 .migrated 对应文件，可能已手动清理）: " + migrated);
                report.skipped++;
                continue;
            }
            String actual = sha256(migrated);
            if (!expectedSha256.equalsIgnoreCase(actual)) {
                throw new java.io.IOException("SHA256 不匹配，拒绝回滚（防止非预期覆盖）："
                    + migrated + " expected=" + expectedSha256 + " actual=" + actual);
            }

            // 清对应 Registry entry（按 yamlPath 反向派生）
            cleanRegistryFor(yamlPath);

            File yaml = new File(yamlPath);
            if (yaml.exists()) {
                throw new java.io.IOException("回滚目标 yaml 已存在（冲突）：" + yaml);
            }
            if (!dryRun) {
                if (!migrated.renameTo(yaml)) {
                    throw new java.io.IOException("rename .migrated→yaml 失败：" + migrated);
                }
            }
            log("ROLLBACK ok: " + yamlPath);
            report.rolledBack++;
        }

        report.manifestFile = manifestFile.getAbsolutePath();
        if (!dryRun) {
            if (!manifestFile.delete()) {
                warn("ROLLBACK 完成但 manifest 删除失败：" + manifestFile);
            }
        }
        return report;
    }

    private File manifestFile() { return new File(dbRoot, MANIFEST_FILE); }

    // ============ 每类迁移：一行一个 lambda ============

    private void migrateRowIdSet(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        String entity = safeName(stripSuffix(yamlFile.getName(), ".ids.meta.yaml"));
        if (entity == null) { r.skipped++; return; }

        Map<String, Object> parsed = parseYaml(yamlFile);
        String legacySchemaId = asString(parsed.get("legacySchemaId"));
        String idsFileAbsolutePath = asString(parsed.get("idsFileAbsolutePath"));
        Long createdAtEpochMs = asLong(parsed.get("createdAtEpochMs"));

        if (legacySchemaId == null || idsFileAbsolutePath == null) {
            warn("ROW idSet 缺关键字段，skip: " + yamlFile); r.skipped++; return;
        }
        String dir = RegistryMetaPaths.rowIdSetRoot(entity);
        prepareRegistryDir(dir);

        // SHA256 进 manifest 后 rename→migrated
        writeManifestEntry(yamlFile, manifest);
        registry.putString(RegistryMetaPaths.rowIdSetLegacySchemaId(entity), legacySchemaId, DataType.REG_SZ);
        registry.putString(RegistryMetaPaths.rowIdSetAbsolutePath(entity), idsFileAbsolutePath, DataType.REG_SZ);
        registry.putLong(RegistryMetaPaths.rowIdSetCreatedAtMs(entity), DataType.REG_QWORD,
            createdAtEpochMs != null ? createdAtEpochMs : yamlFile.lastModified());
        renameMigrated(yamlFile);
        log("MIGRATE row/idSet: " + yamlFile);
        r.migrated++;
    }

    private void migrateRowIndex(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        Map<String, Object> parsed = parseYaml(yamlFile);
        String entity = asString(parsed.get("entityClass"));
        String logicalKey = asString(parsed.get("logicalKey"));
        String kind = asString(parsed.get("kind"));
        String schemaId = asString(parsed.get("schemaId"));
        String mvcc = asString(parsed.get("mvcc"));
        String valueKind = asString(parsed.get("valueKind"));
        String dirAbsolutePath = asString(parsed.get("dirAbsolutePath"));

        if (entity == null || logicalKey == null || dirAbsolutePath == null) {
            warn("ROW index 缺关键字段，skip: " + yamlFile); r.skipped++; return;
        }
        String root = RegistryMetaPaths.rowIndexRoot(safeName(entity), safeName(logicalKey));
        prepareRegistryDir(root);
        writeManifestEntry(yamlFile, manifest);
        safePutStr(RegistryMetaPaths.rowIndexKind(entity, logicalKey), kind);
        safePutStr(RegistryMetaPaths.rowIndexSchemaId(entity, logicalKey), schemaId);
        safePutStr(RegistryMetaPaths.rowIndexMvcc(entity, logicalKey), mvcc);
        safePutStr(RegistryMetaPaths.rowIndexValueKind(entity, logicalKey), valueKind);
        safePutStr(RegistryMetaPaths.rowIndexDir(entity, logicalKey), dirAbsolutePath);
        renameMigrated(yamlFile);
        log("MIGRATE row/index: " + yamlFile);
        r.migrated++;
    }

    private void migrateColumnTableMeta(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        Map<String, Object> parsed = parseYaml(yamlFile);
        String entity = asString(parsed.get("entityClass"));
        if (entity == null) {
            entity = asString(parsed.get("entityClassName"));
        }
        if (entity == null) { warn("COL table 缺 entityClass/entityClassName，skip: " + yamlFile); r.skipped++; return; }

        writeManifestEntry(yamlFile, manifest);
        safePutStr(RegistryMetaPaths.columnTableSignature(entity), asString(parsed.get("signature")));

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> cols = (List<Map<String,Object>>) parsed.get("columns");
        if (cols != null) {
            for (Map<String,Object> c : cols) {
                String name = asString(c.get("name"));
                if (name == null) continue;
                String javaType = asString(c.get("javaType"));
                if (javaType == null) javaType = asString(c.get("type"));
                Long colId = asLong(c.get("colId"));
                if (colId != null && colId.longValue() > 0L) {
                    safePutInt(RegistryMetaPaths.columnTableColColId(entity, name), colId);
                }
                safePutStr(RegistryMetaPaths.columnTableColDir(entity, name) + "/name", name);
                if (javaType != null) safePutStr(RegistryMetaPaths.columnTableColType(entity, name), javaType);
                String colKey = asString(c.get("colKey"));
                if (colKey != null) safePutStr(RegistryMetaPaths.columnTableColDir(entity, name) + "/colKey", colKey);
                Long len = asLong(c.get("length"));
                if (len != null) safePutInt(RegistryMetaPaths.columnTableColDir(entity, name) + "/length", len);
                Long p = asLong(c.get("precision"));
                if (p != null) safePutInt(RegistryMetaPaths.columnTableColDir(entity, name) + "/precision", p);
                Long sc = asLong(c.get("scale"));
                if (sc != null) safePutInt(RegistryMetaPaths.columnTableColDir(entity, name) + "/scale", sc);
                Long ord = asLong(c.get("ordinal"));
                if (ord != null) safePutInt(RegistryMetaPaths.columnTableColOrdinal(entity, name), ord);
                safePutStr(RegistryMetaPaths.columnTableColDefault(entity, name), asString(c.get("defaultValue")));
                safePutStr(RegistryMetaPaths.columnTableColSchemaId(entity, name), asString(c.get("schemaId")));
                safePutStr(RegistryMetaPaths.columnTableColFlags(entity, name), asString(c.get("flags")));
                safePutStr(RegistryMetaPaths.columnTableColComment(entity, name), asString(c.get("comment")));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Map<String,Object>> groups = (Map<String, Map<String,Object>>) parsed.get("compositeGroups");
        if (groups != null) {
            for (Map.Entry<String,Map<String,Object>> g : groups.entrySet()) {
                String gn = g.getKey();
                if (gn == null) continue;
                Map<String,Object> gm = g.getValue();
                safePutStr(RegistryMetaPaths.columnTableCgOperator(entity, gn), asString(gm.get("operator")));
                Long cnt = asLong(gm.get("count"));
                if (cnt != null) safePutInt(RegistryMetaPaths.columnTableCgCount(entity, gn), cnt);
                Long gcolId = asLong(gm.get("colId"));
                if (gcolId != null && gcolId.longValue() > 0L) {
                    safePutInt(RegistryMetaPaths.columnTableCompositeGroupDir(entity, gn) + "/colId", gcolId);
                }
                String gcolKey = asString(gm.get("colKey"));
                if (gcolKey != null) safePutStr(RegistryMetaPaths.columnTableCompositeGroupDir(entity, gn) + "/colKey", gcolKey);
                Long glen = asLong(gm.get("length"));
                if (glen != null) safePutInt(RegistryMetaPaths.columnTableCompositeGroupDir(entity, gn) + "/length", glen);
                safePutStr(RegistryMetaPaths.columnTableCgSchemaId(entity, gn), asString(gm.get("schemaId")));
                safePutStr(RegistryMetaPaths.columnTableCgUnique(entity, gn), asString(gm.get("unique")));
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> items = (List<Map<String,Object>>) gm.get("items");
                if (items != null) {
                    for (Map<String,Object> it : items) {
                        int idx = (asLong(it.get("idx")) != null ? asLong(it.get("idx")).intValue() : 0);
                        safePutStr(RegistryMetaPaths.columnTableCgItemName(entity, gn, idx), asString(it.get("colKey")));
                        safePutStr(RegistryMetaPaths.columnTableCgItemType(entity, gn, idx), asString(it.get("opType")));
                        Long sb = asLong(it.get("startBits"));
                        if (sb != null) safePutInt(RegistryMetaPaths.columnTableCgItemDir(entity, gn, idx) + "/startBits", sb);
                        Long eb = asLong(it.get("endBits"));
                        if (eb != null) safePutInt(RegistryMetaPaths.columnTableCgItemDir(entity, gn, idx) + "/endBits", eb);
                        String iname = asString(it.get("name"));
                        if (iname != null) safePutStr(RegistryMetaPaths.columnTableCgItemDir(entity, gn, idx) + "/name", iname);
                    }
                }
            }
        }
        renameMigrated(yamlFile);
        log("MIGRATE column/table: " + yamlFile);
        r.migrated++;
    }

    private void migrateColumnRegistry(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        Map<String, Object> parsed = parseYaml(yamlFile);
        String entity = safeName(stripSuffix(yamlFile.getName(), ".columns.meta.yaml"));
        if (entity == null) { r.skipped++; return; }
        Long nextColId = asLong(parsed.get("nextColId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byKey = (Map<String, Object>) parsed.get("colKeyToId");
        @SuppressWarnings("unchecked")
        Map<String, Object> byId = (Map<String, Object>) parsed.get("colIdToKey");
        @SuppressWarnings("unchecked")
        List<String> deleted = (List<String>) parsed.get("deletedColIds");

        String root = RegistryMetaPaths.columnColumnsRoot(entity);
        prepareRegistryDir(root);
        writeManifestEntry(yamlFile, manifest);
        if (nextColId != null) {
            registry.putLong(RegistryMetaPaths.columnNextColId(entity), DataType.REG_QWORD, nextColId);
        }
        if (byKey != null) {
            for (Map.Entry<String,Object> e : byKey.entrySet()) {
                Long id = asLong(e.getValue());
                if (id != null) {
                    registry.putLong(RegistryMetaPaths.columnByKey(entity, safeName(e.getKey())), DataType.REG_QWORD, id);
                }
            }
        }
        if (byId != null) {
            for (Map.Entry<String,Object> e : byId.entrySet()) {
                String key = asString(e.getValue());
                Long id = safeParseLong(e.getKey());
                if (key != null && id != null && id.longValue() > 0L) {
                    registry.putString(RegistryMetaPaths.columnById(entity, id.longValue()), key, DataType.REG_SZ);
                }
            }
        }
        if (deleted != null) {
            // 用 deleted/<id> = 1 leaf pattern（和 ColumnRegistry.Backend 一致）
            for (String s : deleted) {
                if (s == null || s.trim().isEmpty()) continue;
                Long id = safeParseLong(s.trim());
                if (id != null && id.longValue() > 0L) {
                    registry.putLong(RegistryMetaPaths.columnDeleted(entity, id.longValue()), DataType.REG_DWORD, 1);
                }
            }
        }
        renameMigrated(yamlFile);
        log("MIGRATE column/columns: " + yamlFile);
        r.migrated++;
    }

    private void migrateColumnRowIdSeq(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        Map<String, Object> parsed = parseYaml(yamlFile);
        String entity = safeName(stripSuffix(yamlFile.getName(), ".rowid.meta.yaml"));
        if (entity == null) { r.skipped++; return; }
        Long nextRowId = asLong(parsed.get("nextRowId"));
        if (nextRowId == null) { warn("COL rowIdSeq 缺 nextRowId: " + yamlFile); r.skipped++; return; }
        prepareRegistryDir(RegistryMetaPaths.columnSeqRoot(entity));
        writeManifestEntry(yamlFile, manifest);
        registry.putLong(RegistryMetaPaths.columnNextRowId(entity), DataType.REG_QWORD, nextRowId);
        renameMigrated(yamlFile);
        log("MIGRATE column/rowIdSeq: " + yamlFile);
        r.migrated++;
    }

    private void migrateColumnEqIndex(File yamlFile, Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        Map<String, Object> parsed = parseYaml(yamlFile);
        String entity = asString(parsed.get("entityClass"));
        String logicalName = asString(parsed.get("logicalName"));
        Long colId = asLong(parsed.get("colId"));
        if (entity == null || logicalName == null || colId == null) {
            warn("COL eqIndex 缺字段，skip: " + yamlFile); r.skipped++; return;
        }
        String root = RegistryMetaPaths.columnEqIndexDir(entity, logicalName);
        prepareRegistryDir(root);
        writeManifestEntry(yamlFile, manifest);
        registry.putLong(RegistryMetaPaths.columnEqIndexColId(entity, logicalName), DataType.REG_QWORD, colId);
        renameMigrated(yamlFile);
        log("MIGRATE column/eqIndex: " + yamlFile);
        r.migrated++;
    }

    // ============ Registry 清理（rollback 阶段）============
    private void cleanRegistryFor(String yamlPath) throws java.io.IOException {
        String name = new File(yamlPath).getName();
        if (name.endsWith(".ids.meta.yaml")) {
            String entity = safeName(stripSuffix(name, ".ids.meta.yaml"));
            if (entity != null) registry.remove(RegistryMetaPaths.rowIdSetRoot(entity), true);
        } else if (name.endsWith(".idx.yaml") && !name.endsWith(".eq.idx.yaml")) {
            // 行存 index：从 yaml 读 entity+logicalKey 再删
            try {
                Map<String,Object> m = parseYaml(new File(yamlPath + ".migrated"));
                String en = asString(m.get("entityClass"));
                String lk = asString(m.get("logicalKey"));
                if (en != null && lk != null) registry.remove(RegistryMetaPaths.rowIndexRoot(en, lk), true);
            } catch (java.io.IOException ignored) { /* 非致命 */ }
        } else if (name.endsWith(".table.yaml")) {
            try {
                Map<String,Object> m = parseYaml(new File(yamlPath + ".migrated"));
                String en = asString(m.get("entityClass"));
                if (en != null) registry.remove(RegistryMetaPaths.columnTableRoot(en), true);
            } catch (java.io.IOException ignored) { }
        } else if (name.endsWith(".columns.meta.yaml")) {
            String entity = safeName(stripSuffix(name, ".columns.meta.yaml"));
            if (entity != null) registry.remove(RegistryMetaPaths.columnColumnsRoot(entity), true);
        } else if (name.endsWith(".rowid.meta.yaml")) {
            String entity = safeName(stripSuffix(name, ".rowid.meta.yaml"));
            if (entity != null) registry.remove(RegistryMetaPaths.columnSeqRoot(entity), true);
        } else if (name.endsWith(".eq.idx.yaml")) {
            try {
                Map<String,Object> m = parseYaml(new File(yamlPath + ".migrated"));
                String en = asString(m.get("entityClass"));
                String ln = asString(m.get("logicalName"));
                if (en != null && ln != null) registry.remove(RegistryMetaPaths.columnEqIndexDir(en, ln), true);
            } catch (java.io.IOException ignored) { }
        }
    }

    // ============ helpers ============
    private void safePutStr(String p, String v) throws java.io.IOException { if (v != null) registry.putString(p, v, DataType.REG_SZ); }
    private void safePutInt(String p, Long v) throws java.io.IOException { if (v != null) registry.putLong(p, DataType.REG_DWORD, v.intValue()); }

    private void prepareRegistryDir(String dir) throws java.io.IOException {
        if (dryRun) return;
        registry.mkdirs(dir);
    }

    private void writeManifestEntry(File yaml, Map<String,String> manifest) throws java.io.IOException {
        String sha = sha256(yaml);
        manifest.put(yaml.getAbsolutePath(), sha);
    }

    private void renameMigrated(File yaml) throws java.io.IOException {
        if (dryRun) return;
        File out = new File(yaml.getAbsolutePath() + ".migrated");
        if (!yaml.renameTo(out)) {
            throw new java.io.IOException("rename yaml→migrated 失败：" + yaml);
        }
    }

    // --------- file scan ---------
    @FunctionalInterface
    private interface FsScanner { List<File> scan(File root) throws java.io.IOException; }
    @FunctionalInterface
    private interface MigrateOne { void apply(File f, Map<String,String> m, MigrateReport r) throws java.io.IOException; }

    private void scanFor(FsScanner scanner, MigrateOne one,
                         Map<String,String> manifest, MigrateReport r) throws java.io.IOException {
        List<File> files;
        try { files = scanner.scan(dbRoot); }
        catch (java.io.FileNotFoundException fnf) { files = Collections.emptyList(); }
        if (files == null) files = Collections.emptyList();
        for (File f : files) one.apply(f, manifest, r);
    }

    private List<File> listFilesBySuffix(File dir, String suffix) {
        if (!dir.isDirectory()) return Collections.emptyList();
        File[] arr = dir.listFiles(f -> f.isFile() && f.getName().endsWith(suffix));
        if (arr == null) return Collections.emptyList();
        return Arrays.asList(arr);
    }

    private List<File> listFilesBySuffixRecursive(File dir, String suffix, boolean excludeEq) {
        List<File> out = new ArrayList<>();
        _walkSuffix(dir, suffix, excludeEq, out);
        return out;
    }
    private static void _walkSuffix(File dir, String suffix, boolean excludeEq, List<File> out) {
        if (!dir.isDirectory()) return;
        File[] arr = dir.listFiles();
        if (arr == null) return;
        for (File f : arr) {
            if (f.isDirectory()) { _walkSuffix(f, suffix, excludeEq, out); continue; }
            String n = f.getName();
            if (!n.endsWith(suffix)) continue;
            if (excludeEq && n.endsWith(".eq.idx.yaml")) continue;
            if (n.endsWith(".migrated")) continue;
            out.add(f);
        }
    }

    // --------- yaml/hash helpers ---------
    @SuppressWarnings("unchecked")
    private static Map<String,Object> parseYaml(File f) throws java.io.IOException {
        try (InputStream in = new FileInputStream(f)) {
            Object r = new Yaml().load(in);
            if (r == null) return new LinkedHashMap<>();
            if (r instanceof Map) return (Map<String,Object>) r;
            throw new java.io.IOException("yaml root 不是 map: " + f);
        }
    }

    private static String asString(Object o) { return o == null ? null : String.valueOf(o); }
    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); }
        catch (Exception x) { return null; }
    }
    private static Long safeParseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception x) { return null; }
    }
    private static String stripSuffix(String name, String suffix) {
        if (name == null) return null;
        if (name.endsWith(suffix)) return name.substring(0, name.length() - suffix.length());
        return null;
    }
    private static String safeName(String s) {
        if (s == null) return null;
        String x = s.trim();
        if (x.isEmpty()) return null;
        if (SAFE_NAME.matcher(x).matches()) return x;
        // 兼容 DsPathUtil 的点号转义
        return x;
    }

    private static String sha256(File f) throws java.io.IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = safeMd();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return toHex(md.digest());
        }
    }
    private static MessageDigest safeMd() throws java.io.IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new java.io.IOException("SHA-256 不可用", e); }
    }
    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // --------- manifest YAML ---------
    private void writeManifest(File mf, Map<String,String> items, String phase) throws java.io.IOException {
        Map<String,Object> root = new LinkedHashMap<>();
        root.put("dbRoot", dbRoot.getAbsolutePath());
        root.put("phase", phase);
        root.put("dryRun", dryRun);
        root.put("createdAt", System.currentTimeMillis());
        root.put("items", items);
        byte[] buf = dumpYaml(root).getBytes(StandardCharsets.UTF_8);
        if (dryRun) return;
        try (OutputStream os = new FileOutputStream(mf)) { os.write(buf); }
    }

    private static Manifest loadManifest(File mf) throws java.io.IOException {
        Map<String,Object> r = parseYaml(mf);
        Manifest m = new Manifest();
        m.phase = asString(r.get("phase"));
        @SuppressWarnings("unchecked")
        Map<String,Object> its = (Map<String,Object>) r.get("items");
        m.items = new LinkedHashMap<>();
        if (its != null) {
            for (Map.Entry<String,Object> e : its.entrySet()) {
                m.items.put(e.getKey(), asString(e.getValue()));
            }
        }
        return m;
    }

    private static String dumpYaml(Map<String,Object> r) {
        return new Yaml().dump(r);
    }

    private void log(String s) { logEntries.add("[INFO ] " + s); }
    private void warn(String s) { logEntries.add("[WARN ] " + s); }

    @Override public void close() { /* registry 走 DsRegistryInstance 单例管理，无需显式关 */ }

    // ============== value objects ==============
    public static class MigrateReport implements Serializable {
        private static final long serialVersionUID = 1L;
        public int migrated = 0;
        public int rolledBack = 0;
        public int skipped = 0;
        public String manifestFile = "";

        @Override public String toString() {
            return "MigrateReport{migrated=" + migrated + ", rolledBack=" + rolledBack
                + ", skipped=" + skipped + ", manifestFile='" + manifestFile + "'}";
        }
    }

    private static final class Manifest {
        String phase;
        Map<String,String> items;
    }
}
