package com.q3lives.ds.backup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.binlog.DsBinlogPosition;

/**
 * 全量备份目录化格式工具类。
 *
 * <p>设计要点（与 {@code .brain/resources/changes/full-backup-directory-format.md} 对应）：
 * <ul>
 *   <li>备份目录：{@code <dbHome>/backups/full/<backupId>/}</li>
 *   <li>顶层按 role 分 {@code bucket_data/}、{@code index_eq/}、{@code meta/}、{@code binlog_fixed/}、{@code binlog_dyn/}、{@code binlog_bytes/}</li>
 *   <li>binlog 承担崩溃恢复与跨节点同步，物理 WAL 已移除</li>
 *   <li>binlog 文件按 fixed/dyn/bytes 拆分到 {@code binlog_*}/ 子目录；checkpoint 文件归 {@code meta/}</li>
 * </ul>
 */
public final class DsFullBackup {

    private static final int MANIFEST_VERSION = 1;
    private static final String TYPE_FULL = "FULL";
    private static final String TYPE_SNAPSHOT = "SNAPSHOT";
    private static final String CHECK_ALGORITHM = "CRC32C";

    public enum BackupRole {
        MEMORY_DATA,
        BUCKET_DATA,
        INDEX_EQ,
        INDEX_MAP,
        INDEX_SET,
        STRING_DICT,
        BINLOG_FIXED,
        BINLOG_DYN,
        BINLOG_BYTES,
        META
    }

    public static final class BackupEntry {
        public final BackupRole role;
        public final File source;
        public final String relativePath;
        public final String originalPath;
        public final long size;
        public final String checksum;

        BackupEntry(BackupRole role, File source, String relativePath, String originalPath, long size, String checksum) {
            this.role = role;
            this.source = source;
            this.relativePath = relativePath;
            this.originalPath = originalPath;
            this.size = size;
            this.checksum = checksum;
        }
    }

    private DsFullBackup() {
    }

    private static String newBackupId() {
        return Instant.now().toString().replace(":", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建全量备份。
     *
     * @param dbHome     数据库主目录（已 syncStore、暂停写）
     * @param backupRoot 备份根目录，通常为 {@code <dbHome>/backups/full}
     * @return 备份目录
     * @throws IOException 当扫描、拷贝或写清单失败时
     */
    public static File create(File dbHome, File backupRoot) throws IOException {
        return create(dbHome, backupRoot, newBackupId());
    }

    /**
     * 创建全量备份并记录 binlog 水位。
     */
    public static File create(File dbHome, File backupRoot, DsBinlogPosition binlogPosition) throws IOException {
        return create(dbHome, backupRoot, newBackupId(), binlogPosition);
    }

    /**
     * 使用指定 backupId 创建全量备份。
     */
    public static File create(File dbHome, File backupRoot, String backupId) throws IOException {
        return createWithType(dbHome, backupRoot, backupId, TYPE_FULL, null);
    }

    /**
     * 使用指定 backupId 创建全量备份并记录 binlog 水位。
     */
    public static File create(File dbHome, File backupRoot, String backupId, DsBinlogPosition binlogPosition) throws IOException {
        return createWithType(dbHome, backupRoot, backupId, TYPE_FULL, binlogPosition);
    }

    /**
     * 创建硬链接 snapshot（type=SNAPSHOT）。语义与全量备份相同，均在 syncStore 后执行；
     * 差异仅在于 manifest 的 type 字段，用于后续时间线恢复识别基线类型。
     */
    public static File createSnapshot(File dbHome, File snapshotRoot) throws IOException {
        return createSnapshot(dbHome, snapshotRoot, newBackupId());
    }

    /**
     * 创建 snapshot 并记录 binlog 水位，用于在线热备基线。
     */
    public static File createSnapshot(File dbHome, File snapshotRoot, DsBinlogPosition binlogPosition) throws IOException {
        return createSnapshot(dbHome, snapshotRoot, newBackupId(), binlogPosition);
    }

    /**
     * 使用指定 backupId 创建 snapshot。
     */
    public static File createSnapshot(File dbHome, File snapshotRoot, String backupId) throws IOException {
        return createWithType(dbHome, snapshotRoot, backupId, TYPE_SNAPSHOT, null);
    }

    /**
     * 使用指定 backupId 创建 snapshot 并记录 binlog 水位。
     */
    public static File createSnapshot(File dbHome, File snapshotRoot, String backupId, DsBinlogPosition binlogPosition) throws IOException {
        return createWithType(dbHome, snapshotRoot, backupId, TYPE_SNAPSHOT, binlogPosition);
    }

    private static File createWithType(File dbHome, File backupRoot, String backupId, String type, DsBinlogPosition binlogPosition) throws IOException {
        Objects.requireNonNull(dbHome, "dbHome cannot be null");
        Objects.requireNonNull(backupRoot, "backupRoot cannot be null");
        if (!dbHome.isDirectory()) {
            throw new IllegalArgumentException("dbHome is not a directory: " + dbHome);
        }

        File backupDir = new File(backupRoot, backupId);
        if (backupDir.exists()) {
            throw new IOException("backup directory already exists: " + backupDir);
        }
        backupDir.mkdirs();

        List<BackupEntry> entries = scan(dbHome);
        boolean snapshotCopy = TYPE_SNAPSHOT.equals(type);
        for (BackupEntry e : entries) {
            File target = new File(backupDir, e.relativePath);
            target.getParentFile().mkdirs();
            if (snapshotCopy) {
                // SNAPSHOT 用于在线热备的时点定格，必须独立拷贝，不能使用硬链接。
                // 硬链接会与源库共享 inode，源库后续写入会污染快照。
                Files.copy(e.source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                linkOrCopy(e.source, target);
            }
        }

        Map<String, Object> manifest = buildManifest(backupId, dbHome, entries, type, binlogPosition);
        writeManifest(backupDir, manifest);
        return backupDir;
    }

    /**
     * 从全量备份恢复到目标 dbHome。
     *
     * @param backupDir    备份目录
     * @param targetDbHome 目标数据库主目录
     * @throws IOException 当校验或拷贝失败时
     */
    public static void restore(File backupDir, File targetDbHome) throws IOException {
        Objects.requireNonNull(backupDir, "backupDir cannot be null");
        Objects.requireNonNull(targetDbHome, "targetDbHome cannot be null");
        File manifestFile = new File(backupDir, "manifest.yaml");
        if (!manifestFile.isFile()) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        Map<String, Object> manifest = readYaml(manifestFile);
        List<BackupEntry> entries = parseEntries(manifest, backupDir);

        ensureEmptyOrNew(targetDbHome);

        for (BackupEntry e : entries) {
            File source = new File(backupDir, e.relativePath);
            String originalRel = stripRolePrefix(e.relativePath);
            File target = new File(targetDbHome, originalRel.replace('/', File.separatorChar));
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (target.length() != e.size) {
                throw new IOException("size mismatch after restore: " + target);
            }
            String actual = checksumCrc32c(target);
            if (!actual.equalsIgnoreCase(e.checksum)) {
                throw new IOException("checksum mismatch after restore: " + target);
            }
        }
    }

    /**
     * 校验备份目录与 manifest 一致。
     */
    public static void validate(File backupDir) throws IOException {
        validate(backupDir, true);
    }

    /**
     * 校验备份目录。
     *
     * @param checksum 是否计算并比对 CRC32C
     */
    public static void validate(File backupDir, boolean checksum) throws IOException {
        Objects.requireNonNull(backupDir, "backupDir cannot be null");
        File manifestFile = new File(backupDir, "manifest.yaml");
        if (!manifestFile.isFile()) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        Map<String, Object> manifest = readYaml(manifestFile);
        List<BackupEntry> entries = parseEntries(manifest, backupDir);
        for (BackupEntry e : entries) {
            File f = new File(backupDir, e.relativePath);
            if (!f.isFile()) {
                throw new IOException("missing backup file: " + e.relativePath);
            }
            if (f.length() != e.size) {
                throw new IOException("size mismatch: " + e.relativePath + " expected=" + e.size + " actual=" + f.length());
            }
            if (checksum) {
                String actual = checksumCrc32c(f);
                if (!actual.equalsIgnoreCase(e.checksum)) {
                    throw new IOException("checksum mismatch: " + e.relativePath);
                }
            }
        }
    }

    static List<BackupEntry> scan(File dbHome) throws IOException {
        List<BackupEntry> entries = new ArrayList<>();
        Path rootPath = dbHome.toPath().normalize();
        try (var stream = Files.walk(rootPath)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String rel = normalizeRel(rootPath.relativize(path));
                    if (shouldSkip(rel)) {
                        return;
                    }
                    BackupRole role = classify(rel, path.getFileName().toString());
                    String backupRel = role.name().toLowerCase() + "/" + rel;
                    File f = path.toFile();
                    String checksum = checksumCrc32c(f);
                    entries.add(new BackupEntry(role, f, backupRel, f.getAbsolutePath(), f.length(), checksum));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return entries;
    }

    static boolean shouldSkip(String rel) {
        // 跳过备份目录自身与隐藏文件
        if (rel.startsWith("backups/")) {
            return true;
        }
        if (rel.startsWith(".") || rel.contains("/.")) {
            return true;
        }
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return name.startsWith(".") || name.endsWith("~");
    }

    static BackupRole classify(String rel, String fileName) {
        if (rel.startsWith("_binlog/")) {
            if (rel.startsWith("_binlog/_binlog_checkpoint/")) {
                return BackupRole.META;
            }
            if (fileName.startsWith("dsbinlog_") && fileName.endsWith("_fixed.dat")) {
                return BackupRole.BINLOG_FIXED;
            }
            if (fileName.startsWith("dsbinlog_") && fileName.endsWith("_dyn.dat")) {
                return BackupRole.BINLOG_DYN;
            }
            return BackupRole.BINLOG_BYTES;
        }
        if (rel.startsWith("indexes/")) {
            return BackupRole.INDEX_EQ;
        }
        if (rel.startsWith("relations/")) {
            return BackupRole.INDEX_MAP;
        }
        if (rel.startsWith("mvcc/heads/")) {
            return BackupRole.INDEX_MAP;
        }
        if (rel.startsWith("meta/")) {
            return BackupRole.META;
        }
        // bucket / memory 数据文件（物理 WAL 已移除，*_wal.log 不再出现）
        if (fileName.startsWith("data_") && fileName.endsWith(".dat")) {
            return BackupRole.BUCKET_DATA;
        }
        if (fileName.endsWith(".dat.free")) {
            return BackupRole.BUCKET_DATA;
        }
        if (fileName.endsWith(".map")) {
            return BackupRole.INDEX_MAP;
        }
        if (fileName.endsWith(".set")) {
            return BackupRole.INDEX_SET;
        }
        if (fileName.endsWith(".str")) {
            return BackupRole.STRING_DICT;
        }
        if (fileName.endsWith(".dat")) {
            return BackupRole.MEMORY_DATA;
        }
        return BackupRole.META;
    }

    static Map<String, Object> buildManifest(String backupId, File dbHome, List<BackupEntry> entries, String type, DsBinlogPosition binlogPosition) {
        Map<String, Object> manifest = new LinkedHashMap<>();

        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("version", MANIFEST_VERSION);
        backup.put("backupId", backupId);
        backup.put("createdAt", Instant.now().toString());
        backup.put("dbHome", dbHome.getAbsolutePath());
        backup.put("type", type);
        if (binlogPosition != null && !binlogPosition.isLatest()) {
            Map<String, Object> bp = new LinkedHashMap<>();
            bp.put("dayKey", binlogPosition.dayKey());
            bp.put("fixedOffset", binlogPosition.fixedOffset());
            bp.put("sequence", binlogPosition.sequence());
            backup.put("binlogPosition", bp);
        }
        manifest.put("backup", backup);

        List<Map<String, Object>> list = new ArrayList<>(entries.size());
        for (BackupEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", e.role.name());
            m.put("relativePath", e.relativePath);
            m.put("originalPath", e.originalPath);
            m.put("size", e.size);
            Map<String, Object> checksum = new LinkedHashMap<>();
            checksum.put("algorithm", CHECK_ALGORITHM);
            checksum.put("value", e.checksum);
            m.put("checksum", checksum);
            list.add(m);
        }
        manifest.put("entries", list);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tables", Collections.emptyList());
        manifest.put("metadata", metadata);

        return manifest;
    }

    static void writeManifest(File backupDir, Map<String, Object> manifest) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml yaml = new Yaml(options);
        File f = new File(backupDir, "manifest.yaml");
        try (FileOutputStream out = new FileOutputStream(f)) {
            yaml.dump(manifest, new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readYaml(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map)) {
                throw new IOException("invalid manifest: " + f);
            }
            return (Map<String, Object>) loaded;
        }
    }

    @SuppressWarnings("unchecked")
    static List<BackupEntry> parseEntries(Map<String, Object> manifest, File backupDir) throws IOException {
        Object entriesObj = manifest.get("entries");
        if (!(entriesObj instanceof List)) {
            throw new IOException("invalid manifest entries");
        }
        List<BackupEntry> result = new ArrayList<>();
        for (Object o : (List<?>) entriesObj) {
            if (!(o instanceof Map)) {
                throw new IOException("invalid entry in manifest");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            BackupRole role = BackupRole.valueOf(String.valueOf(m.get("role")));
            String relativePath = String.valueOf(m.get("relativePath"));
            String originalPath = String.valueOf(m.get("originalPath"));
            long size = ((Number) m.get("size")).longValue();
            String checksum = "";
            Object checksumObj = m.get("checksum");
            if (checksumObj instanceof Map) {
                Object value = ((Map<String, Object>) checksumObj).get("value");
                if (value != null) {
                    checksum = String.valueOf(value);
                }
            }
            result.add(new BackupEntry(role, new File(backupDir, relativePath), relativePath, originalPath, size, checksum));
        }
        return result;
    }

    static String stripRolePrefix(String backupRelativePath) {
        int idx = backupRelativePath.indexOf('/');
        if (idx < 0) {
            throw new IllegalArgumentException("invalid backup relative path: " + backupRelativePath);
        }
        return backupRelativePath.substring(idx + 1);
    }

    static void linkOrCopy(File source, File target) throws IOException {
        try {
            Files.createLink(target.toPath(), source.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void ensureEmptyOrNew(File dir) throws IOException {
        if (!dir.exists()) {
            dir.mkdirs();
            return;
        }
        if (!dir.isDirectory()) {
            throw new IOException("target dbHome is not a directory: " + dir);
        }
        File[] children = dir.listFiles();
        if (children != null && children.length > 0) {
            // 安全策略：仅当子项为 backups/ 时允许保留；其它情况要求空目录
            boolean onlyBackups = true;
            for (File c : children) {
                if (!c.getName().equals("backups")) {
                    onlyBackups = false;
                    break;
                }
            }
            if (!onlyBackups) {
                throw new IOException("target dbHome is not empty: " + dir);
            }
        }
    }

    static String normalizeRel(Path rel) {
        String s = rel.toString().replace('\\', '/');
        if (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    static String checksumCrc32c(File f) throws IOException {
        CRC32C crc = new CRC32C();
        byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                crc.update(buf, 0, n);
            }
        }
        long value = crc.getValue();
        return Long.toHexString(value);
    }
}
