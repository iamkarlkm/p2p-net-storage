package javax.net.p2p.filesync.sync;

import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.core.DsString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.p2p.utils.XXHashUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class P2PSyncStateStore implements AutoCloseable {

    private final Path dsHome;
    private final DsString fileIdStrings;
    private final DsString failureReasonStrings;
    private final DsHashMap pathHashToFileId;
    private final DsHashMap meta;
    private final DsHashMap fileIdToLastModified;
    private final DsHashMap fileIdToKind;
    private final DsHashMap failedKeyToReasonId;

    private DsHashSet fileCreatesActive;
    private DsHashSet fileModifiesActive;
    private DsHashSet fileDeletesActive;
    private DsHashSet dirCreatesActive;
    private DsHashSet dirDeletesActive;

    private DsHashSet fileCreatesStartup;
    private DsHashSet fileModifiesStartup;
    private DsHashSet fileDeletesStartup;
    private DsHashSet dirCreatesStartup;
    private DsHashSet dirDeletesStartup;

    private DsHashSet fileCreatesInflight;
    private DsHashSet fileModifiesInflight;
    private DsHashSet fileDeletesInflight;
    private DsHashSet dirCreatesInflight;
    private DsHashSet dirDeletesInflight;

    private DsHashSet fileCreatesFailed;
    private DsHashSet fileModifiesFailed;
    private DsHashSet fileDeletesFailed;
    private DsHashSet dirCreatesFailed;
    private DsHashSet dirDeletesFailed;

    public P2PSyncStateStore(Path dsHome) {
        try {
            this.dsHome = dsHome.toAbsolutePath().normalize();
            Files.createDirectories(this.dsHome);
            Path stringsHome = this.dsHome.resolve("strings");
            Files.createDirectories(stringsHome);

            this.fileIdStrings = new DsString(stringsHome.toString());
            Path failedStringsHome = this.dsHome.resolve("strings_failed");
            Files.createDirectories(failedStringsHome);
            this.failureReasonStrings = new DsString(failedStringsHome.toString());
            this.pathHashToFileId = new DsHashMap(this.dsHome.resolve("path_to_id.map").toFile());
            this.meta = new DsHashMap(this.dsHome.resolve("meta.map").toFile());
            this.fileIdToLastModified = new DsHashMap(this.dsHome.resolve("last_modified.map").toFile());
            this.fileIdToLastModified.setSyncModeStrong100ms();
            this.fileIdToKind = new DsHashMap(this.dsHome.resolve("id_kind.map").toFile());
            this.failedKeyToReasonId = new DsHashMap(this.dsHome.resolve("failed_reason.map").toFile());
            this.failedKeyToReasonId.setSyncModeStrong100ms();
            this.fileCreatesActive = new DsHashSet(this.dsHome.resolve("events_file_create.active.set").toFile());
            this.fileModifiesActive = new DsHashSet(this.dsHome.resolve("events_file_modify.active.set").toFile());
            this.fileDeletesActive = new DsHashSet(this.dsHome.resolve("events_file_delete.active.set").toFile());
            this.dirCreatesActive = new DsHashSet(this.dsHome.resolve("events_dir_create.active.set").toFile());
            this.dirDeletesActive = new DsHashSet(this.dsHome.resolve("events_dir_delete.active.set").toFile());

            this.fileCreatesStartup = new DsHashSet(this.dsHome.resolve("events_file_create.startup.set").toFile());
            this.fileModifiesStartup = new DsHashSet(this.dsHome.resolve("events_file_modify.startup.set").toFile());
            this.fileDeletesStartup = new DsHashSet(this.dsHome.resolve("events_file_delete.startup.set").toFile());
            this.dirCreatesStartup = new DsHashSet(this.dsHome.resolve("events_dir_create.startup.set").toFile());
            this.dirDeletesStartup = new DsHashSet(this.dsHome.resolve("events_dir_delete.startup.set").toFile());

            this.fileCreatesInflight = new DsHashSet(this.dsHome.resolve("events_file_create.inflight.set").toFile());
            this.fileModifiesInflight = new DsHashSet(this.dsHome.resolve("events_file_modify.inflight.set").toFile());
            this.fileDeletesInflight = new DsHashSet(this.dsHome.resolve("events_file_delete.inflight.set").toFile());
            this.dirCreatesInflight = new DsHashSet(this.dsHome.resolve("events_dir_create.inflight.set").toFile());
            this.dirDeletesInflight = new DsHashSet(this.dsHome.resolve("events_dir_delete.inflight.set").toFile());

            this.fileCreatesFailed = new DsHashSet(this.dsHome.resolve("events_file_create.failed.set").toFile());
            this.fileModifiesFailed = new DsHashSet(this.dsHome.resolve("events_file_modify.failed.set").toFile());
            this.fileDeletesFailed = new DsHashSet(this.dsHome.resolve("events_file_delete.failed.set").toFile());
            this.dirCreatesFailed = new DsHashSet(this.dsHome.resolve("events_dir_create.failed.set").toFile());
            this.dirDeletesFailed = new DsHashSet(this.dsHome.resolve("events_dir_delete.failed.set").toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public long getLastSuccessRunMillis() {
        Long v = meta.get(Long.valueOf(1L));
        return v == null ? 0L : v.longValue();
    }

    public void setLastSuccessRunMillis(long epochMillis) {
        meta.put(Long.valueOf(1L), Long.valueOf(epochMillis));
        meta.sync();
    }

    public Path getDsHome() {
        return dsHome;
    }

    public long getOrCreateFileId(String relativePath) {
        long pathHash = hashPath(relativePath);
        Long existing = pathHashToFileId.get(Long.valueOf(pathHash));
        if (existing != null) {
            return existing;
        }
        long fileId = addFileIdString(relativePath);
        pathHashToFileId.put(Long.valueOf(pathHash), Long.valueOf(fileId));
        pathHashToFileId.sync();
        return fileId;
    }

    public Long getLastModifiedMillis(long fileId) {
        return fileIdToLastModified.get(Long.valueOf(fileId));
    }

    public void putLastModifiedMillis(long fileId, long lastModifiedMillis) {
        fileIdToLastModified.put(Long.valueOf(fileId), Long.valueOf(lastModifiedMillis));
    }

    public void removeLastModifiedMillis(long fileId) {
        fileIdToLastModified.remove(Long.valueOf(fileId));
    }

    public Boolean isDirectory(long fileId) {
        Long kind = fileIdToKind.get(Long.valueOf(fileId));
        if (kind == null) {
            return null;
        }
        return kind.longValue() == 1L;
    }

    public void putKind(long fileId, boolean directory) {
        fileIdToKind.put(Long.valueOf(fileId), Long.valueOf(directory ? 1L : 0L));
    }

    public void removeKind(long fileId) {
        fileIdToKind.remove(Long.valueOf(fileId));
    }

    public void enqueueFileCreate(long fileId) {
        fileCreatesActive.add(Long.valueOf(fileId));
    }

    public void enqueueFileModify(long fileId) {
        fileModifiesActive.add(Long.valueOf(fileId));
    }

    public void enqueueFileDelete(long fileId) {
        fileDeletesActive.add(Long.valueOf(fileId));
    }

    public void enqueueDirCreate(long fileId) {
        dirCreatesActive.add(Long.valueOf(fileId));
    }

    public void enqueueDirDelete(long fileId) {
        dirDeletesActive.add(Long.valueOf(fileId));
    }

    public DsHashSet fileCreatesActive() {
        return fileCreatesActive;
    }

    public DsHashSet fileModifiesActive() {
        return fileModifiesActive;
    }

    public DsHashSet fileDeletesActive() {
        return fileDeletesActive;
    }

    public DsHashSet dirCreatesActive() {
        return dirCreatesActive;
    }

    public DsHashSet dirDeletesActive() {
        return dirDeletesActive;
    }

    public DsHashSet fileCreatesStartup() {
        return fileCreatesStartup;
    }

    public DsHashSet fileModifiesStartup() {
        return fileModifiesStartup;
    }

    public DsHashSet fileDeletesStartup() {
        return fileDeletesStartup;
    }

    public DsHashSet dirCreatesStartup() {
        return dirCreatesStartup;
    }

    public DsHashSet dirDeletesStartup() {
        return dirDeletesStartup;
    }

    public DsHashSet fileCreatesInflight() {
        return fileCreatesInflight;
    }

    public DsHashSet fileModifiesInflight() {
        return fileModifiesInflight;
    }

    public DsHashSet fileDeletesInflight() {
        return fileDeletesInflight;
    }

    public DsHashSet dirCreatesInflight() {
        return dirCreatesInflight;
    }

    public DsHashSet dirDeletesInflight() {
        return dirDeletesInflight;
    }

    public DsHashSet fileCreatesFailed() {
        return fileCreatesFailed;
    }

    public DsHashSet fileModifiesFailed() {
        return fileModifiesFailed;
    }

    public DsHashSet fileDeletesFailed() {
        return fileDeletesFailed;
    }

    public DsHashSet dirCreatesFailed() {
        return dirCreatesFailed;
    }

    public DsHashSet dirDeletesFailed() {
        return dirDeletesFailed;
    }

    public void markFailed(FileSyncEventType type, boolean directory, long fileId, String reason) {
        int code = codeFor(type, directory);
        if (code < 0) {
            return;
        }
        DsHashSet target = failedSetForCode(code);
        if (target == null) {
            return;
        }
        target.add(Long.valueOf(fileId));
        target.sync();
        if (reason == null) {
            reason = "";
        }
        long key = failedKey(code, fileId);
        try {
            long reasonId = failureReasonStrings.add(reason);
            failedKeyToReasonId.put(Long.valueOf(key), Long.valueOf(reasonId));
            failedKeyToReasonId.sync();
        } catch (Exception e) {
            log.warn("store failed reason error: {}", e.getMessage());
        }
    }

    public String getFailedReason(FileSyncEventType type, boolean directory, long fileId) {
        int code = codeFor(type, directory);
        if (code < 0) {
            return "";
        }
        long key = failedKey(code, fileId);
        Long reasonId = failedKeyToReasonId.get(Long.valueOf(key));
        if (reasonId == null) {
            return "";
        }
        try {
            return failureReasonStrings.get(reasonId.longValue());
        } catch (Exception e) {
            return "";
        }
    }

    public boolean retryFailed(FileSyncEventType type, boolean directory, long fileId) {
        int code = codeFor(type, directory);
        if (code < 0) {
            return false;
        }
        DsHashSet failed = failedSetForCode(code);
        DsHashSet active = activeSetForCode(code);
        if (failed == null || active == null) {
            return false;
        }
        if (!failed.remove(Long.valueOf(fileId))) {
            return false;
        }
        active.add(Long.valueOf(fileId));
        failed.sync();
        active.sync();
        removeFailedReason(code, fileId);
        return true;
    }

    public boolean discardFailed(FileSyncEventType type, boolean directory, long fileId) {
        int code = codeFor(type, directory);
        if (code < 0) {
            return false;
        }
        DsHashSet failed = failedSetForCode(code);
        if (failed == null) {
            return false;
        }
        if (!failed.remove(Long.valueOf(fileId))) {
            return false;
        }
        failed.sync();
        removeFailedReason(code, fileId);
        return true;
    }

    public void requeueInflightToActive() {
        moveAll(fileCreatesInflight, fileCreatesActive);
        moveAll(fileModifiesInflight, fileModifiesActive);
        moveAll(fileDeletesInflight, fileDeletesActive);
        moveAll(dirCreatesInflight, dirCreatesActive);
        moveAll(dirDeletesInflight, dirDeletesActive);
        fileCreatesInflight.sync();
        fileModifiesInflight.sync();
        fileDeletesInflight.sync();
        dirCreatesInflight.sync();
        dirDeletesInflight.sync();
        fileCreatesActive.sync();
        fileModifiesActive.sync();
        fileDeletesActive.sync();
        dirCreatesActive.sync();
        dirDeletesActive.sync();
    }

    public DsHashMap fileIdToLastModifiedMap() {
        return fileIdToLastModified;
    }

    public DsHashMap fileIdToKindMap() {
        return fileIdToKind;
    }

    public void swapEventTablesForStartup() {
        swapPairForStartupFile();
        swapPairForStartupDir();
    }

    private void swapPairForStartupFile() {
        moveAll(fileCreatesStartup, fileCreatesActive);
        DsHashSet t1 = fileCreatesActive;
        fileCreatesActive = fileCreatesStartup;
        fileCreatesStartup = t1;

        moveAll(fileModifiesStartup, fileModifiesActive);
        DsHashSet t2 = fileModifiesActive;
        fileModifiesActive = fileModifiesStartup;
        fileModifiesStartup = t2;

        moveAll(fileDeletesStartup, fileDeletesActive);
        DsHashSet t3 = fileDeletesActive;
        fileDeletesActive = fileDeletesStartup;
        fileDeletesStartup = t3;
    }

    private void swapPairForStartupDir() {
        moveAll(dirCreatesStartup, dirCreatesActive);
        DsHashSet t1 = dirCreatesActive;
        dirCreatesActive = dirCreatesStartup;
        dirCreatesStartup = t1;

        moveAll(dirDeletesStartup, dirDeletesActive);
        DsHashSet t2 = dirDeletesActive;
        dirDeletesActive = dirDeletesStartup;
        dirDeletesStartup = t2;
    }

    private static void moveAll(DsHashSet from, DsHashSet to) {
        if (from.isEmpty()) {
            return;
        }
        for (Long v : from) {
            to.add(v);
        }
        from.clear();
    }

    private static long failedKey(int code, long fileId) {
        return (((long) code) << 56) | (fileId & 0x00FFFFFFFFFFFFFFL);
    }

    private void removeFailedReason(int code, long fileId) {
        long key = failedKey(code, fileId);
        failedKeyToReasonId.remove(Long.valueOf(key));
        failedKeyToReasonId.sync();
    }

    private static int codeFor(FileSyncEventType type, boolean directory) {
        if (directory) {
            if (type == FileSyncEventType.CREATE) return 0;
            if (type == FileSyncEventType.DELETE) return 1;
            return -1;
        }
        if (type == FileSyncEventType.CREATE) return 2;
        if (type == FileSyncEventType.MODIFY) return 3;
        if (type == FileSyncEventType.DELETE) return 4;
        return -1;
    }

    private DsHashSet activeSetForCode(int code) {
        if (code == 0) return dirCreatesActive;
        if (code == 1) return dirDeletesActive;
        if (code == 2) return fileCreatesActive;
        if (code == 3) return fileModifiesActive;
        if (code == 4) return fileDeletesActive;
        return null;
    }

    private DsHashSet failedSetForCode(int code) {
        if (code == 0) return dirCreatesFailed;
        if (code == 1) return dirDeletesFailed;
        if (code == 2) return fileCreatesFailed;
        if (code == 3) return fileModifiesFailed;
        if (code == 4) return fileDeletesFailed;
        return null;
    }

    public String getRelativePath(long fileId) {
        try {
            return fileIdStrings.get(fileId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static long hashPath(String relativePath) {
        if (relativePath == null) {
            relativePath = "";
        }
        byte[] data = relativePath.getBytes(StandardCharsets.UTF_8);
        return XXHashUtil.hash64(data);
    }

    private long addFileIdString(String relativePath) {
        try {
            return fileIdStrings.add(relativePath);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        fileCreatesActive.sync();
        fileCreatesActive.close();
        fileModifiesActive.sync();
        fileModifiesActive.close();
        fileDeletesActive.sync();
        fileDeletesActive.close();
        dirCreatesActive.sync();
        dirCreatesActive.close();
        dirDeletesActive.sync();
        dirDeletesActive.close();

        fileCreatesStartup.sync();
        fileCreatesStartup.close();
        fileModifiesStartup.sync();
        fileModifiesStartup.close();
        fileDeletesStartup.sync();
        fileDeletesStartup.close();
        dirCreatesStartup.sync();
        dirCreatesStartup.close();
        dirDeletesStartup.sync();
        dirDeletesStartup.close();

        fileCreatesInflight.sync();
        fileCreatesInflight.close();
        fileModifiesInflight.sync();
        fileModifiesInflight.close();
        fileDeletesInflight.sync();
        fileDeletesInflight.close();
        dirCreatesInflight.sync();
        dirCreatesInflight.close();
        dirDeletesInflight.sync();
        dirDeletesInflight.close();
        fileCreatesFailed.sync();
        fileCreatesFailed.close();
        fileModifiesFailed.sync();
        fileModifiesFailed.close();
        fileDeletesFailed.sync();
        fileDeletesFailed.close();
        dirCreatesFailed.sync();
        dirCreatesFailed.close();
        dirDeletesFailed.sync();
        dirDeletesFailed.close();
        meta.sync();
        meta.close();
        fileIdToLastModified.sync();
        fileIdToLastModified.close();
        fileIdToKind.sync();
        fileIdToKind.close();
        failedKeyToReasonId.sync();
        failedKeyToReasonId.close();
        pathHashToFileId.sync();
        pathHashToFileId.close();
        fileIdStrings.close();
        failureReasonStrings.close();
    }
}
