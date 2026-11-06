package com.q3lives.ds.backup;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.q3lives.ds.backup.DsFullBackup.BackupEntry;
import com.q3lives.ds.backup.DsFullBackup.BackupRole;
import com.q3lives.ds.binlog.DsBinlogPosition;

/**
 * 增量备份工具类。
 *
 * <p>基于目录化全量备份格式扩展，只备份自基线（FULL/SNAPSHOT/INCREMENTAL）以来
 * 新增或发生变化的文件。当前实现按<b>文件级</b>差异做增量，重点覆盖：
 * <ul>
 *   <li>追加型 binlog 文件（大小变化即重新备份）；</li>
 *   <li>其他新增或修改的数据/索引/元数据文件。</li>
 * </ul>
 *
 * <p>物理 WAL 已移除，增量备份不再处理 {@code _wal_archive/}；
 * 时间线恢复配合 {@link DsTimelineRestore} 使用：先恢复基线，再按顺序恢复增量 binlog 文件，
 * 最后按目标 {@link DsBinlogPosition} 截断并回放 binlog。
 */
public final class DsIncrementalBackup {

    private static final String TYPE_INCREMENTAL = "INCREMENTAL";

    private DsIncrementalBackup() {
    }

    private static String newBackupId() {
        return Instant.now().toString().replace(":", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建基于 baseBackupDir 的增量备份。
     *
     * @param dbHome        数据库主目录（已 syncStore、暂停写）
     * @param backupRoot    增量备份根目录，通常为 {@code <dbHome>/backups/incr}
     * @param baseBackupDir 基线备份目录（FULL / SNAPSHOT / INCREMENTAL 均可）
     * @return 增量备份目录
     * @throws IOException 当扫描、拷贝或写清单失败时
     */
    public static File create(File dbHome, File backupRoot, File baseBackupDir) throws IOException {
        return create(dbHome, backupRoot, baseBackupDir, newBackupId(), null);
    }

    /**
     * 使用指定 backupId 创建增量备份。
     */
    public static File create(File dbHome, File backupRoot, File baseBackupDir, String backupId) throws IOException {
        return create(dbHome, backupRoot, baseBackupDir, backupId, null);
    }

    /**
     * 创建增量备份并记录当前 binlog 水位。
     */
    public static File create(File dbHome, File backupRoot, File baseBackupDir, DsBinlogPosition currentPosition) throws IOException {
        return create(dbHome, backupRoot, baseBackupDir, newBackupId(), currentPosition);
    }

    /**
     * 使用指定 backupId 创建增量备份并记录当前 binlog 水位。
     */
    public static File create(File dbHome, File backupRoot, File baseBackupDir, String backupId, DsBinlogPosition currentPosition) throws IOException {
        Objects.requireNonNull(dbHome, "dbHome cannot be null");
        Objects.requireNonNull(backupRoot, "backupRoot cannot be null");
        Objects.requireNonNull(baseBackupDir, "baseBackupDir cannot be null");
        if (!dbHome.isDirectory()) {
            throw new IllegalArgumentException("dbHome is not a directory: " + dbHome);
        }
        File baseManifestFile = new File(baseBackupDir, "manifest.yaml");
        if (!baseManifestFile.isFile()) {
            throw new IOException("base manifest not found: " + baseManifestFile);
        }

        Map<String, Object> baseManifest = DsFullBackup.readYaml(baseManifestFile);
        String baseBackupId = readBackupId(baseManifest);
        DsBinlogPosition baseBinlogPosition = readBinlogPosition(baseManifest);
        List<BackupEntry> baseEntries = DsFullBackup.parseEntries(baseManifest, baseBackupDir);
        Map<String, String> baseChecksumByRel = new LinkedHashMap<>();
        for (BackupEntry e : baseEntries) {
            baseChecksumByRel.put(e.relativePath, e.checksum);
        }

        List<BackupEntry> currentEntries = DsFullBackup.scan(dbHome);

        List<BackupEntry> incrementalEntries = new ArrayList<>();
        for (BackupEntry e : currentEntries) {
            // binlog-first 架构下，增量备份只携带 binlog 文件（fixed/dyn/bytes）。
            // 数据/索引/元数据文件一律由基线提供，中间状态通过 [basePos, targetPos) 回放生成。
            if (e.role != BackupRole.BINLOG_FIXED && e.role != BackupRole.BINLOG_DYN && e.role != BackupRole.BINLOG_BYTES) {
                continue;
            }
            // 基于 binlog 水位跳过基线水位之前的 binlog 日文件
            if (baseBinlogPosition != null) {
                String dayKey = extractBinlogDayKey(e.relativePath);
                if (dayKey != null && dayKey.compareTo(baseBinlogPosition.dayKey()) < 0) {
                    continue;
                }
            }
            String baseChecksum = baseChecksumByRel.get(e.relativePath);
            if (baseChecksum == null || !baseChecksum.equalsIgnoreCase(e.checksum)) {
                incrementalEntries.add(e);
            }
        }

        File backupDir = new File(backupRoot, backupId);
        if (backupDir.exists()) {
            throw new IOException("backup directory already exists: " + backupDir);
        }
        backupDir.mkdirs();

        for (BackupEntry e : incrementalEntries) {
            File target = new File(backupDir, e.relativePath);
            target.getParentFile().mkdirs();
            DsFullBackup.linkOrCopy(e.source, target);
        }

        Map<String, Object> manifest = DsFullBackup.buildManifest(backupId, dbHome, incrementalEntries, TYPE_INCREMENTAL, currentPosition);
        Map<String, Object> backup = (Map<String, Object>) manifest.get("backup");
        backup.put("parentBackupId", baseBackupId);
        backup.put("parentBackupDir", baseBackupDir.getAbsolutePath());
        DsFullBackup.writeManifest(backupDir, manifest);
        return backupDir;
    }

    /**
     * 校验增量备份目录与 manifest 一致。
     */
    public static void validate(File backupDir) throws IOException {
        validate(backupDir, true);
    }

    /**
     * 校验增量备份目录。
     *
     * @param checksum 是否计算并比对 CRC32C
     */
    public static void validate(File backupDir, boolean checksum) throws IOException {
        Objects.requireNonNull(backupDir, "backupDir cannot be null");
        File manifestFile = new File(backupDir, "manifest.yaml");
        if (!manifestFile.isFile()) {
            throw new IOException("manifest not found: " + manifestFile);
        }
        Map<String, Object> manifest = DsFullBackup.readYaml(manifestFile);
        List<BackupEntry> entries = DsFullBackup.parseEntries(manifest, backupDir);
        for (BackupEntry e : entries) {
            File f = new File(backupDir, e.relativePath);
            if (!f.isFile()) {
                throw new IOException("missing backup file: " + e.relativePath);
            }
            if (f.length() != e.size) {
                throw new IOException("size mismatch: " + e.relativePath + " expected=" + e.size + " actual=" + f.length());
            }
            if (checksum) {
                String actual = DsFullBackup.checksumCrc32c(f);
                if (!actual.equalsIgnoreCase(e.checksum)) {
                    throw new IOException("checksum mismatch: " + e.relativePath);
                }
            }
        }
    }

    static String readBackupId(Map<String, Object> manifest) throws IOException {
        Object backupObj = manifest.get("backup");
        if (!(backupObj instanceof Map)) {
            throw new IOException("invalid manifest backup section");
        }
        Map<String, Object> backup = (Map<String, Object>) backupObj;
        Object id = backup.get("backupId");
        if (id == null) {
            throw new IOException("missing backupId in manifest");
        }
        return String.valueOf(id);
    }

    @SuppressWarnings("unchecked")
    private static DsBinlogPosition readBinlogPosition(Map<String, Object> manifest) throws IOException {
        Object backupObj = manifest.get("backup");
        if (!(backupObj instanceof Map)) {
            throw new IOException("invalid manifest backup section");
        }
        Object bp = ((Map<String, Object>) backupObj).get("binlogPosition");
        if (!(bp instanceof Map)) {
            return null;
        }
        Map<String, Object> m = (Map<String, Object>) bp;
        String dayKey = String.valueOf(m.getOrDefault("dayKey", ""));
        long fixedOffset = ((Number) m.getOrDefault("fixedOffset", 0L)).longValue();
        long sequence = ((Number) m.getOrDefault("sequence", 0L)).longValue();
        return new DsBinlogPosition(dayKey, fixedOffset, sequence);
    }

    private static String extractBinlogDayKey(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        String prefix = "dsbinlog_";
        if (!name.startsWith(prefix)) return null;
        String suffixFixed = "_fixed.dat";
        String suffixDyn = "_dyn.dat";
        if (name.endsWith(suffixFixed)) {
            return name.substring(prefix.length(), name.length() - suffixFixed.length());
        }
        if (name.endsWith(suffixDyn)) {
            return name.substring(prefix.length(), name.length() - suffixDyn.length());
        }
        return null;
    }

}
