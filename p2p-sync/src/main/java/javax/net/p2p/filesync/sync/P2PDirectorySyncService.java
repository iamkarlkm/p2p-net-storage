package javax.net.p2p.filesync.sync;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.q3lives.ds.collections.DsHashSet;
import javax.net.p2p.filesync.config.P2PSyncConfig;
import javax.net.p2p.filesync.sync.P2PSyncStateStore.QueueStage;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class P2PDirectorySyncService implements AutoCloseable {

    private final P2PSyncConfig config;
    private final FileSyncEventHandler eventHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean watchReady = new AtomicBoolean(false);
    private final ExecutorService watchExecutor;
    private final ExecutorService eventExecutor;
    private final ScheduledExecutorService heartbeatExecutor;

    private volatile WatchService watchService;
    private volatile P2PSyncStateStore store;
    private volatile Path rootDir;
    private final P2PSyncQueueEngine queueEngine = new P2PSyncQueueEngine();

    public P2PSyncStateStore getStore() {
        return store;
    }

    boolean isWatchReady() {
        return watchReady.get();
    }

    public P2PDirectorySyncService(P2PSyncConfig config, FileSyncEventHandler eventHandler) {
        this.config = Objects.requireNonNull(config, "config");
        this.eventHandler = eventHandler == null ? (t, id, rel, abs, dir, acker) -> acker.ack() : eventHandler;
        this.watchExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "p2p-sync-watch"));
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "p2p-sync-events"));
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "p2p-sync-heartbeat"));
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        this.rootDir = Paths.get(config.getLocalDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Path dsHome = resolveDsHome(config, rootDir);
        this.store = new P2PSyncStateStore(dsHome);

        store.requeueInflightToActive();
        initialScan();
        store.swapEventTablesForStartup();
        drainStage(store, QueueStage.STARTUP, 2048);

        // 记录最近成功运行时间：每秒落一次，用于下次启动时按 lastModified 过滤需要入队的事件
        this.heartbeatExecutor.scheduleAtFixedRate(() -> {
            P2PSyncStateStore s = this.store;
            if (s != null && running.get()) {
                s.setLastSuccessRunMillis(System.currentTimeMillis());
            }
        }, 0, 1, TimeUnit.SECONDS);

        this.eventExecutor.submit(this::eventLoop);
        this.watchExecutor.submit(this::watchLoop);
    }

    private static Path resolveDsHome(P2PSyncConfig config, Path rootDir) {
        if (config.getDsHome() != null && !config.getDsHome().trim().isEmpty()) {
            return Paths.get(config.getDsHome()).toAbsolutePath().normalize();
        }
        return rootDir.resolve(".p2p-sync").resolve("task-" + config.getTaskId()).toAbsolutePath().normalize();
    }

    private void initialScan() {
        P2PSyncStateStore localStore = this.store;
        Path root = this.rootDir;
        // 启动扫描按“上次成功运行时间”过滤：只为 lastModified > lastRun 的文件生成新增/修改事件
        long lastRunMillis = localStore.getLastSuccessRunMillis();

        try {
            Files.walk(root).forEach(p -> scanOnePath(localStore, root, p, lastRunMillis));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        for (Object entryObj : localStore.fileIdToKindMap().entrySet()) {
            Map.Entry<Long, Long> entry = (Map.Entry<Long, Long>) entryObj;
            long fileId = entry.getKey().longValue();
            boolean directory = entry.getValue() != null && entry.getValue().longValue() == 1L;
            String relativePath = localStore.getRelativePath(fileId);
            if (relativePath == null || relativePath.trim().isEmpty()) {
                continue;
            }
            Path abs = root.resolve(relativePath);
            if (!Files.exists(abs)) {
                if (directory) {
                    localStore.enqueueDirDelete(fileId);
                } else {
                    localStore.enqueueFileDelete(fileId);
                    localStore.removeLastModifiedMillis(fileId);
                }
            }
        }

        localStore.fileIdToLastModifiedMap().sync();
        localStore.fileIdToKindMap().sync();
        localStore.fileCreatesActive().sync();
        localStore.fileModifiesActive().sync();
        localStore.fileDeletesActive().sync();
        localStore.dirCreatesActive().sync();
        localStore.dirDeletesActive().sync();
    }

    private void scanOnePath(P2PSyncStateStore localStore, Path root, Path absolutePath, long lastRunMillis) {
        if (absolutePath.equals(root)) {
            return;
        }
        if (Files.isDirectory(absolutePath)) {
            scanOneDir(localStore, root, absolutePath, lastRunMillis);
            return;
        }
        if (Files.isRegularFile(absolutePath)) {
            scanOneFile(localStore, root, absolutePath, lastRunMillis);
        }
    }

    private void scanOneDir(P2PSyncStateStore localStore, Path root, Path absoluteDir, long lastRunMillis) {
        String relativePath = toStableRelativePath(root, absoluteDir);
        long id = localStore.getOrCreateFileId(relativePath);
        Boolean prevDir = localStore.isDirectory(id);
        localStore.putKind(id, true);
        if (prevDir == null) {
            if (shouldEnqueueByLastRun(absoluteDir, lastRunMillis)) {
                localStore.enqueueDirCreate(id);
            }
        } else if (!prevDir) {
            localStore.enqueueFileDelete(id);
            localStore.removeLastModifiedMillis(id);
            if (shouldEnqueueByLastRun(absoluteDir, lastRunMillis)) {
                localStore.enqueueDirCreate(id);
            }
        }
    }

    private void scanOneFile(P2PSyncStateStore localStore, Path root, Path absoluteFile, long lastRunMillis) {
        String relativePath = toStableRelativePath(root, absoluteFile);
        long id = localStore.getOrCreateFileId(relativePath);
        Boolean prevDir = localStore.isDirectory(id);
        localStore.putKind(id, false);
        if (prevDir != null && prevDir) {
            localStore.enqueueDirDelete(id);
        }
        long lastModifiedMillis;
        try {
            lastModifiedMillis = Files.getLastModifiedTime(absoluteFile).toMillis();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Long prev = localStore.getLastModifiedMillis(id);
        if (lastRunMillis > 0L && lastModifiedMillis <= lastRunMillis) {
            localStore.putLastModifiedMillis(id, lastModifiedMillis);
            return;
        }
        if (prev == null) {
            localStore.enqueueFileCreate(id);
        } else if (!prev.equals(lastModifiedMillis)) {
            localStore.enqueueFileModify(id);
        }
        localStore.putLastModifiedMillis(id, lastModifiedMillis);
    }

    private void watchLoop() {
        Map<WatchKey, Path> keyToDir = new HashMap<>();
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            this.watchService = ws;
            registerAllDirs(rootDir, ws, keyToDir);
            watchReady.set(true);
            while (running.get()) {
                WatchKey key = ws.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                Path dir = keyToDir.get(key);
                if (dir != null) {
                    handleWatchKey(dir, key, ws, keyToDir);
                }
                boolean valid = key.reset();
                if (!valid) {
                    keyToDir.remove(key);
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("watchLoop failed", e);
            }
        } finally {
            watchReady.set(false);
            this.watchService = null;
        }
    }

    private void handleWatchKey(Path dir, WatchKey key, WatchService ws, Map<WatchKey, Path> keyToDir) {
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            Path name = (Path) event.context();
            Path child = dir.resolve(name);

            if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                if (Files.isDirectory(child)) {
                    registerAllDirs(child, ws, keyToDir);
                    onDirCreate(child);
                    continue;
                }
                onFileCreate(child);
                continue;
            }

            if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                if (!Files.isRegularFile(child)) {
                    continue;
                }
                onFileModify(child);
                continue;
            }

            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                onDelete(child);
            }
        }
    }

    private void onDirCreate(Path absoluteDir) {
        P2PSyncStateStore localStore = this.store;
        if (localStore == null) {
            return;
        }
        String relativePath = toStableRelativePath(rootDir, absoluteDir);
        long id = localStore.getOrCreateFileId(relativePath);
        localStore.putKind(id, true);
        localStore.enqueueDirCreate(id);
    }

    private void onFileCreate(Path absoluteFile) {
        P2PSyncStateStore localStore = this.store;
        if (localStore == null) {
            return;
        }
        String relativePath = toStableRelativePath(rootDir, absoluteFile);
        long id = localStore.getOrCreateFileId(relativePath);
        localStore.putKind(id, false);
        long lastModifiedMillis;
        try {
            lastModifiedMillis = Files.getLastModifiedTime(absoluteFile).toMillis();
        } catch (IOException e) {
            return;
        }
        localStore.putLastModifiedMillis(id, lastModifiedMillis);
        localStore.enqueueFileCreate(id);
    }

    private void onFileModify(Path absoluteFile) {
        P2PSyncStateStore localStore = this.store;
        if (localStore == null) {
            return;
        }
        String relativePath = toStableRelativePath(rootDir, absoluteFile);
        long id = localStore.getOrCreateFileId(relativePath);
        localStore.putKind(id, false);
        long lastModifiedMillis;
        try {
            lastModifiedMillis = Files.getLastModifiedTime(absoluteFile).toMillis();
        } catch (IOException e) {
            return;
        }
        localStore.putLastModifiedMillis(id, lastModifiedMillis);
        localStore.enqueueFileModify(id);
    }

    private void onDelete(Path absolutePath) {
        P2PSyncStateStore localStore = this.store;
        if (localStore == null) {
            return;
        }
        String relativePath = toStableRelativePath(rootDir, absolutePath);
        long id = localStore.getOrCreateFileId(relativePath);
        Boolean directory = localStore.isDirectory(id);
        if (directory == null) {
            directory = localStore.getLastModifiedMillis(id) == null;
        }
        if (directory) {
            localStore.enqueueDirDelete(id);
        } else {
            localStore.enqueueFileDelete(id);
            localStore.removeLastModifiedMillis(id);
        }
    }

    private void eventLoop() {
        while (running.get()) {
            P2PSyncStateStore localStore = this.store;
            if (localStore == null) {
                sleepQuietly(200);
                continue;
            }
            if (queueEngine.isEmpty(localStore, QueueStage.ACTIVE)) {
                sleepQuietly(200);
                continue;
            }
            processStage(localStore, QueueStage.ACTIVE, 1024);
        }
    }

    private void processStage(P2PSyncStateStore localStore, QueueStage stage, int maxBatchSize) {
        try {
            queueEngine.processBatch(localStore, stage, maxBatchSize, rootDir, eventHandler, running);
        } catch (Exception e) {
            log.error("process stage error: stage={}", stage, e);
        }
    }

    private void drainStage(P2PSyncStateStore localStore, QueueStage stage, int maxBatchSize) {
        int remaining = maxBatchSize;
        while (remaining > 0 && running.get()) {
            int processed = queueEngine.processBatch(localStore, stage, remaining, rootDir, eventHandler, running);
            if (processed <= 0) {
                return;
            }
            remaining -= processed;
        }
    }

    private static void registerAllDirs(Path start, WatchService ws, Map<WatchKey, Path> keyToDir) {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    WatchKey key = dir.register(ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                    keyToDir.put(key, dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String toStableRelativePath(Path root, Path absolute) {
        String rel = root.relativize(absolute.toAbsolutePath().normalize()).toString();
        return rel.replace('\\', '/');
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean shouldEnqueueByLastRun(Path path, long lastRunMillis) {
        if (lastRunMillis <= 0L) {
            return true;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis() > lastRunMillis;
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        close();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        watchReady.set(false);
        WatchService ws = this.watchService;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException e) {
                log.warn("close watchService failed", e);
            }
        }
        shutdownExecutor(heartbeatExecutor);
        shutdownExecutor(watchExecutor);
        shutdownExecutor(eventExecutor);

        P2PSyncStateStore localStore = this.store;
        this.store = null;
        if (localStore != null) {
            localStore.close();
        }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
