package com.q3lives.ds.backup;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.backup.DsFullBackup.BackupEntry;
import com.q3lives.ds.binlog.DsBinlogCheckpoint;
import com.q3lives.ds.binlog.DsBinlogLayout;
import com.q3lives.ds.binlog.DsBinlogPosition;

/**
 * 时间线恢复工具类。
 *
 * <p>恢复链格式：
 * <pre>
 * [0] FULL 或 SNAPSHOT 基线
 * [1..n] INCREMENTAL 增量备份（按创建顺序）
 * </pre>
 *
 * <p>恢复流程：
 * <ol>
 *   <li>恢复基线到 targetDbHome；</li>
 *   <li>依次恢复每个增量备份的文件（主要为 binlog 日文件与变更的数据文件）；</li>
 *   <li>若指定了目标 {@link DsBinlogPosition}，则截断最后一个 binlog 日文件到该排他水位，
 *       并删除目标日之后的所有 binlog 日文件；</li>
 *   <li>将 checkpoint 重置为基线 binlog 水位，由调用方/下次启动自动回放 [base, target)。</li>
 * </ol>
 *
 * <p>物理 WAL 已彻底移除，恢复不再涉及 {@code _wal.log} 或 {@code _wal_archive/}。
 */
public final class DsTimelineRestore {

    private static final String TYPE_INCREMENTAL = "INCREMENTAL";

    private DsTimelineRestore() {
    }

    /**
     * 恢复到增量链中的最新 binlog 位点。
     */
    public static void restoreTimeline(List<File> chain, File targetDbHome) throws IOException {
        restoreTimeline(chain, targetDbHome, null);
    }

    /**
     * 恢复到指定的 binlog 排他水位。
     *
     * @param chain             恢复链，第一个为基线，后续为增量备份目录
     * @param targetDbHome      目标数据库主目录
     * @param targetPosition    目标 binlog 水位；null 或 {@link DsBinlogPosition#LATEST} 表示保留当前所有 binlog
     * @throws IOException 当校验、拷贝或截断失败时
     */
    public static void restoreTimeline(List<File> chain, File targetDbHome, DsBinlogPosition targetPosition) throws IOException {
        Objects.requireNonNull(chain, "chain cannot be null");
        Objects.requireNonNull(targetDbHome, "targetDbHome cannot be null");
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("chain cannot be empty");
        }

        DsFullBackup.ensureEmptyOrNew(targetDbHome);

        // 1. 恢复基线
        File baseDir = chain.get(0);
        Map<String, Object> baseManifest = DsFullBackup.readYaml(new File(baseDir, "manifest.yaml"));
        DsBinlogPosition baseBinlogPosition = readBinlogPosition(baseManifest);
        restoreBackupEntries(baseDir, targetDbHome, DsFullBackup.parseEntries(baseManifest, baseDir));

        // 2. 恢复增量
        for (int i = 1; i < chain.size(); i++) {
            File incrDir = chain.get(i);
            Map<String, Object> incrManifest = DsFullBackup.readYaml(new File(incrDir, "manifest.yaml"));
            String type = readType(incrManifest);
            if (!TYPE_INCREMENTAL.equals(type)) {
                throw new IOException("backup is not incremental: " + incrDir);
            }
            restoreBackupEntries(incrDir, targetDbHome, DsFullBackup.parseEntries(incrManifest, incrDir));
        }

        // 3. 按目标位点截断 binlog
        if (targetPosition != null && !targetPosition.isLatest()) {
            truncateBinlogTo(targetDbHome, targetPosition);
        }

        // 4. 重置 checkpoint 为基线位点，启动时自动回放 [base, target)
        if (baseBinlogPosition != null && !baseBinlogPosition.isLatest()) {
            DsBinlogCheckpoint checkpoint = new DsBinlogCheckpoint(
                    baseBinlogPosition, baseBinlogPosition.sequence(), baseBinlogPosition.sequence(), System.currentTimeMillis());
            checkpoint.save(new File(targetDbHome, "_binlog"));
        }
    }

    private static void restoreBackupEntries(File backupDir, File targetDbHome, List<BackupEntry> entries) throws IOException {
        for (BackupEntry e : entries) {
            File source = new File(backupDir, e.relativePath);
            String originalRel = DsFullBackup.stripRolePrefix(e.relativePath);
            File target = new File(targetDbHome, originalRel.replace('/', File.separatorChar));
            target.getParentFile().mkdirs();
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (target.length() != e.size) {
                throw new IOException("size mismatch after restore: " + target);
            }
            String actual = DsFullBackup.checksumCrc32c(target);
            if (!actual.equalsIgnoreCase(e.checksum)) {
                throw new IOException("checksum mismatch after restore: " + target);
            }
        }
    }

    private static void truncateBinlogTo(File targetDbHome, DsBinlogPosition position) throws IOException {
        File binlogDir = new File(targetDbHome, "_binlog");
        if (!binlogDir.isDirectory()) {
            return;
        }
        List<String> dayKeys = listBinlogDayKeys(binlogDir);
        for (String dayKey : dayKeys) {
            int cmp = dayKey.compareTo(position.dayKey());
            if (cmp > 0) {
                deleteBinlogDayFiles(binlogDir, dayKey);
            } else if (cmp == 0) {
                truncateBinlogDay(binlogDir, dayKey, position.fixedOffset());
            }
        }
    }

    private static List<String> listBinlogDayKeys(File binlogDir) {
        List<String> result = new ArrayList<>();
        File[] files = binlogDir.listFiles();
        if (files == null) return result;
        String prefix = DsBinlogLayout.FILE_PREFIX;
        String suffix = DsBinlogLayout.FIXED_SUFFIX;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(prefix) && n.endsWith(suffix)) {
                String dayKey = n.substring(prefix.length(), n.length() - suffix.length());
                if (!dayKey.isEmpty()) result.add(dayKey);
            }
        }
        result.sort(null);
        return result;
    }

    private static void deleteBinlogDayFiles(File binlogDir, String dayKey) throws IOException {
        File fixedFile = new File(binlogDir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.FIXED_SUFFIX);
        File dynFile = new File(binlogDir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.DYN_SUFFIX);
        Files.deleteIfExists(fixedFile.toPath());
        Files.deleteIfExists(dynFile.toPath());
    }

    private static void truncateBinlogDay(File binlogDir, String dayKey, long fixedOffset) throws IOException {
        File fixedFile = new File(binlogDir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.FIXED_SUFFIX);
        File dynFile = new File(binlogDir, DsBinlogLayout.FILE_PREFIX + dayKey + DsBinlogLayout.DYN_SUFFIX);

        if (fixedOffset == 0) {
            Files.deleteIfExists(fixedFile.toPath());
            Files.deleteIfExists(dynFile.toPath());
            return;
        }

        if (fixedOffset % DsBinlogLayout.FIXED_FRAME_SIZE != 0) {
            throw new IOException("fixedOffset not frame-aligned: " + fixedOffset);
        }
        if (!fixedFile.isFile() || fixedFile.length() < fixedOffset) {
            throw new IOException("fixed file too short for truncate: " + fixedFile);
        }

        long dynTruncateTo = 0L;
        try (RandomAccessFile raf = new RandomAccessFile(fixedFile, "r")) {
            long frames = fixedOffset / DsBinlogLayout.FIXED_FRAME_SIZE;
            byte[] frame = new byte[DsBinlogLayout.FIXED_FRAME_SIZE];
            for (int i = 0; i < frames; i++) {
                raf.seek((long) i * DsBinlogLayout.FIXED_FRAME_SIZE);
                raf.readFully(frame);
                ByteBuffer buf = ByteBuffer.wrap(frame);
                long dynStart = buf.getLong(DsBinlogLayout.OFF_DYN_START);
                int dynLen = buf.getInt(DsBinlogLayout.OFF_DYN_LEN);
                if (dynStart >= 0 && dynLen > 0) {
                    long end = dynStart + dynLen;
                    if (end > dynTruncateTo) {
                        dynTruncateTo = end;
                    }
                }
            }
        }

        try (RandomAccessFile raf = new RandomAccessFile(fixedFile, "rw")) {
            raf.setLength(fixedOffset);
        }
        if (dynFile.isFile()) {
            try (RandomAccessFile raf = new RandomAccessFile(dynFile, "rw")) {
                raf.setLength(dynTruncateTo);
            }
        }
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

    private static String readType(Map<String, Object> manifest) throws IOException {
        Object backupObj = manifest.get("backup");
        if (!(backupObj instanceof Map)) {
            throw new IOException("invalid manifest backup section");
        }
        Object type = ((Map<String, Object>) backupObj).get("type");
        return type == null ? "" : String.valueOf(type);
    }
}
