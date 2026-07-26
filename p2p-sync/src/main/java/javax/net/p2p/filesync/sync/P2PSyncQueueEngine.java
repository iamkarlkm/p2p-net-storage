package javax.net.p2p.filesync.sync;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.p2p.filesync.sync.P2PSyncStateStore.QueueKey;
import javax.net.p2p.filesync.sync.P2PSyncStateStore.QueueStage;

final class P2PSyncQueueEngine {

    private static final String RETRY_LIMIT_EXCEEDED = "retry_limit_exceeded";

    private static final QueueDef[] ORDER = new QueueDef[] {
        new QueueDef(QueueKey.DIR_CREATE, FileSyncEventType.CREATE, true),
        new QueueDef(QueueKey.FILE_CREATE, FileSyncEventType.CREATE, false),
        new QueueDef(QueueKey.FILE_MODIFY, FileSyncEventType.MODIFY, false),
        new QueueDef(QueueKey.FILE_DELETE, FileSyncEventType.DELETE, false),
        new QueueDef(QueueKey.DIR_DELETE, FileSyncEventType.DELETE, true)
    };

    private final int maxRetryCount;
    private final long retryBackoffMillis;

    P2PSyncQueueEngine() {
        this(3, 2000L);
    }

    P2PSyncQueueEngine(int maxRetryCount) {
        this(maxRetryCount, 2000L);
    }

    P2PSyncQueueEngine(int maxRetryCount, long retryBackoffMillis) {
        this.maxRetryCount = maxRetryCount <= 0 ? 3 : maxRetryCount;
        this.retryBackoffMillis = retryBackoffMillis < 0L ? 2000L : retryBackoffMillis;
    }

    boolean isEmpty(P2PSyncStateStore store, QueueStage stage) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(stage, "stage");
        for (QueueDef def : ORDER) {
            if (!store.queueRef(def.key, stage).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    int processBatch(P2PSyncStateStore store, QueueStage fromStage, int maxBatchSize, Path rootDir, FileSyncEventHandler handler, AtomicBoolean running) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(fromStage, "fromStage");
        Objects.requireNonNull(rootDir, "rootDir");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(running, "running");

        if (maxBatchSize <= 0) {
            return 0;
        }

        int processed = 0;
        for (QueueDef def : ORDER) {
            if (processed >= maxBatchSize) {
                break;
            }
            processed += processOneQueue(store, def, fromStage, maxBatchSize - processed, rootDir, handler, running);
        }

        if (processed > 0) {
            for (QueueDef def : ORDER) {
                store.queueRef(def.key, fromStage).sync();
                store.queueRef(def.key, QueueStage.INFLIGHT).sync();
            }
            store.fileIdToLastModifiedMap().sync();
            store.fileIdToKindMap().sync();
        }
        return processed;
    }

    private int processOneQueue(P2PSyncStateStore store, QueueDef def, QueueStage fromStage, int maxBatchSize, Path rootDir, FileSyncEventHandler handler, AtomicBoolean running) {
        if (maxBatchSize <= 0) {
            return 0;
        }
        PersistentLongQueue queue = store.queueRef(def.key, fromStage);
        PersistentLongQueue inflight = store.queueRef(def.key, QueueStage.INFLIGHT);
        Iterator<Long> it = queue.iterator();
        int processed = 0;
        while (it.hasNext() && processed < maxBatchSize && running.get()) {
            long fileId = it.next();
            // Keep same-path events serialized across queue types so CREATE/DELETE
            // can finish before a follow-up MODIFY for the same file is dispatched.
            if (isInflight(store, fileId)) {
                continue;
            }
            if (fromStage == QueueStage.ACTIVE && isRetryBackoffPending(store, def, fileId)) {
                continue;
            }
            inflight.add(fileId);
            it.remove();
            String relativePath = store.getRelativePath(fileId);
            Path abs = relativePath == null ? null : rootDir.resolve(relativePath);
            handler.handle(def.type, fileId, relativePath, abs, def.directory, new InflightAcker(store, def, queue, inflight, fileId, maxRetryCount));
            processed++;
        }
        return processed;
    }

    private boolean isRetryBackoffPending(P2PSyncStateStore store, QueueDef def, long fileId) {
        if (retryBackoffMillis == 0L) {
            return false;
        }
        if (store.getRetryCount(def.type, def.directory, fileId) <= 0) {
            return false;
        }
        long lastRetriedAtMillis = store.getLastRetriedAtMillis(def.type, def.directory, fileId);
        if (lastRetriedAtMillis <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - lastRetriedAtMillis < retryBackoffMillis;
    }

    private static boolean isInflight(P2PSyncStateStore store, long fileId) {
        for (QueueDef def : ORDER) {
            if (store.queueRef(def.key, QueueStage.INFLIGHT).iterator().hasNext()) {
                Iterator<Long> inflightIt = store.queueRef(def.key, QueueStage.INFLIGHT).iterator();
                while (inflightIt.hasNext()) {
                    if (inflightIt.next().longValue() == fileId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final class QueueDef {
        private final QueueKey key;
        private final FileSyncEventType type;
        private final boolean directory;

        private QueueDef(QueueKey key, FileSyncEventType type, boolean directory) {
            this.key = key;
            this.type = type;
            this.directory = directory;
        }
    }

    private static final class InflightAcker implements FileSyncAcker {

        private final P2PSyncStateStore store;
        private final QueueDef def;
        private final PersistentLongQueue queue;
        private final PersistentLongQueue inflight;
        private final long fileId;
        private final int retryLimit;
        private final AtomicBoolean done = new AtomicBoolean(false);

        private InflightAcker(P2PSyncStateStore store, QueueDef def, PersistentLongQueue queue, PersistentLongQueue inflight, long fileId, int retryLimit) {
            this.store = store;
            this.def = def;
            this.queue = queue;
            this.inflight = inflight;
            this.fileId = fileId;
            this.retryLimit = retryLimit;
        }

        @Override
        public void ack() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            inflight.remove(fileId);
            inflight.sync();
            store.advanceLastSuccessRunMillis(store.getLastModifiedMillis(fileId) == null ? 0L : store.getLastModifiedMillis(fileId).longValue());
            store.clearRetryCount(def.type, def.directory, fileId);
            if (def.type == FileSyncEventType.DELETE) {
                store.removeKind(fileId);
                store.removeLastModifiedMillis(fileId);
                store.fileIdToLastModifiedMap().sync();
                store.fileIdToKindMap().sync();
            }
        }

        @Override
        public void retry() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            inflight.remove(fileId);
            int currentRetryCount = store.getRetryCount(def.type, def.directory, fileId);
            if (currentRetryCount >= retryLimit) {
                inflight.sync();
                store.markFailed(def.type, def.directory, fileId, RETRY_LIMIT_EXCEEDED);
                return;
            }
            store.incrementRetryCount(def.type, def.directory, fileId);
            store.markRetriedNow(def.type, def.directory, fileId);
            queue.add(fileId);
            inflight.sync();
            queue.sync();
        }

        @Override
        public void fail(String reason) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            inflight.remove(fileId);
            inflight.sync();
            store.markFailed(def.type, def.directory, fileId, reason == null ? "" : reason);
        }

    }
}
