package com.q3lives.ds.database.fulltext.cluster.raft;

import com.q3lives.ds.database.fulltext.FulltextStore.ShardRouter;
import com.q3lives.ds.registry.meta.raft.RaftServer;
import com.q3lives.ds.registry.meta.raft.RaftStateMachineListener;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P58 T2: replica-side state machine that replays committed fulltext shard
 * write commands onto the local {@link ShardRouter}.
 *
 * <p>
 * Each Raft node holds one instance registered as a
 * {@link RaftStateMachineListener}: the Leader applies locally after commit,
 * and Followers apply once AppendEntries advances their commit index, so the
 * same ordered log yields an identical index state on every replica.</p>
 *
 * <p>
 * Log entries without the {@code /ft/} prefix (registry/meta, etc.) are
 * ignored, keeping this state machine decoupled from the pre-existing P57
 * registry state machine.</p>
 *
 * <p>
 * Apply is decoupled from consensus: {@link #onApplied} is invoked on a Raft
 * scheduler thread while shard indexing performs heavy disk I/O. Replaying
 * synchronously inside that callback would stretch heartbeats beyond the
 * election timeout and trigger leadership churn. The callback therefore only
 * enqueues (O(1)) and a dedicated single-thread apply worker replays entries
 * strictly in enqueue order, matching the standard Raft split where consensus
 * replicates the log and a separate apply thread drives the state machine.</p>
 */
public final class RaftFulltextReplica implements RaftStateMachineListener, AutoCloseable {

    private static final int MAX_PENDING = 4096;

    private final ShardRouter router;
    private final LinkedBlockingQueue<RaftServer.LogEntry> pending = new LinkedBlockingQueue<>(MAX_PENDING);
    private final AtomicLong applied = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;
    private volatile Throwable lastFailure;

    /**
     * Creates one replica bound to a local shard router and starts its apply
     * worker.
     *
     * @param router the local shard router used for replay, must not be null
     */
    public RaftFulltextReplica(ShardRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("router must not be null");
        }
        this.router = router;
        this.worker = new Thread(this::drain, "ft-raft-replica");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Attaches a replica to a Raft node state-machine slot and returns it for
     * observing {@code appliedCount/lastFailure}.
     *
     * <p>
     * Call once per node: once bound, that node's local {@link ShardRouter}
     * keeps replaying fulltext write commands as the log advances.</p>
     *
     * @param raft the Raft server to attach to, must not be null
     * @param router the local shard router for this node
     * @return the created replica
     */
    public static RaftFulltextReplica attach(RaftServer raft, ShardRouter router) {
        if (raft == null) {
            throw new IllegalArgumentException("raft must not be null");
        }
        RaftFulltextReplica replica = new RaftFulltextReplica(router);
        raft.setStateMachineListener(replica);
        return replica;
    }

    @Override
    public void onApplied(RaftServer.LogEntry entry, long appliedIndex) {
        if (entry == null || !FulltextRaftWire.isFulltextPath(entry.registryPath)) {
            return;
        }
        if (!running.get()) {
            return;
        }
        try {
            pending.put(entry);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain() {
        while (running.get() || !pending.isEmpty()) {
            RaftServer.LogEntry entry;
            try {
                entry = pending.poll(50L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (entry == null) {
                continue;
            }
            try {
                apply(entry);
                applied.incrementAndGet();
            } catch (IOException | RuntimeException ex) {
                lastFailure = ex;
            }
        }
    }

    private void apply(RaftServer.LogEntry entry) throws IOException {
        String path = entry.registryPath;
        if (path.startsWith(FulltextRaftWire.OP_PUT_DOC)) {
            long docId = FulltextRaftWire.parseId(path, FulltextRaftWire.OP_PUT_DOC);
            router.putDocCluster(docId, FulltextRaftWire.decodeText(entry.payload));
        } else if (path.startsWith(FulltextRaftWire.OP_CLEAR_DOC)) {
            router.clearDocCluster(FulltextRaftWire.parseId(path, FulltextRaftWire.OP_CLEAR_DOC));
        } else if (path.startsWith(FulltextRaftWire.OP_INDEX_VEC)) {
            long uid = FulltextRaftWire.parseId(path, FulltextRaftWire.OP_INDEX_VEC);
            router.indexTermVectorCluster(uid, FulltextRaftWire.decodeFloats(entry.payload));
        } else {
            throw new IllegalStateException("unknown fulltext command: " + path);
        }
    }

    /**
     * Number of fulltext commands successfully replayed (comparable across
     * replicas for consistency assertions). A command may still be queued when
     * the write callback returns, so the count reflects durable application
     * rather than enqueue.
     *
     * @return applied command count
     */
    public long appliedCount() {
        return applied.get();
    }

    /**
     * The most recent replay failure; successful replays never clear a past
     * failure.
     *
     * @return last failure or null when none occurred
     */
    public Throwable lastFailure() {
        return lastFailure;
    }

    /**
     * Whether the pending replay queue is empty.
     *
     * @return true when no command waits to be replayed
     */
    public boolean isIdle() {
        return pending.isEmpty();
    }

    /**
     * Stops the apply worker and waits for it to exit, best-effort draining
     * queued entries.
     */
    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            worker.interrupt();
            try {
                worker.join(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
