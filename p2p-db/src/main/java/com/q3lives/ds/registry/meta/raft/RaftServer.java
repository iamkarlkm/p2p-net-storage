package com.q3lives.ds.registry.meta.raft;

import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RaftServer {

    public static final byte CMD_NOOP = 0x00;
    public static final byte CMD_PUT_REGISTRY = 0x01;
    public static final byte CMD_REMOVE_REGISTRY = 0x02;
    public static final byte CMD_META_SAVE = 0x03;

    public static final int MAX_LOG_ENTRIES = 10000;
    public static final int HEARTBEAT_MS = 25;
    public static final int ELECTION_TIMEOUT_MIN_MS = 120;
    public static final int ELECTION_TIMEOUT_MAX_MS = 200;

    public enum Role {
        FOLLOWER, CANDIDATE, LEADER
    }

    public static final class LogEntry {

        public final long term;
        public final byte cmdType;
        public final String registryPath;
        public final byte[] payload;

        public LogEntry(long term, byte cmdType, String registryPath, byte[] payload) {
            this.term = term;
            this.cmdType = cmdType;
            this.registryPath = registryPath;
            this.payload = payload == null ? new byte[0] : payload;
        }
    }

    public static final class PeerState {

        public long nextIndex;
        public long matchIndex;
        public long lastRpcTs;
    }

    public static final class VoteResult {

        public final long term;
        public final boolean voteGranted;

        public VoteResult(long term, boolean voteGranted) {
            this.term = term;
            this.voteGranted = voteGranted;
        }
    }

    public static final class AppendResult {

        public final long term;
        public final boolean success;
        public final long matchIndex;

        public AppendResult(long term, boolean success, long matchIndex) {
            this.term = term;
            this.success = success;
            this.matchIndex = matchIndex;
        }
    }

    public static final class SnapshotResult {

        public final long term;
        public final boolean success;

        public SnapshotResult(long term, boolean success) {
            this.term = term;
            this.success = success;
        }
    }

    public static final class InstallSnapshotChunk {

        public final long term;
        public final long leaderId;
        public final long lastIncludedIndex;
        public final long lastIncludedTerm;
        public final long offset;
        public final byte[] data;
        public final boolean done;

        public InstallSnapshotChunk(long term, long leaderId, long lastIncludedIndex, long lastIncludedTerm,
                long offset, byte[] data, boolean done) {
            this.term = term;
            this.leaderId = leaderId;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.offset = offset;
            this.data = data == null ? new byte[0] : data;
            this.done = done;
        }
    }

    volatile Role role;
    volatile long currentTerm;
    volatile long votedFor;
    volatile long commitIndex;
    volatile long lastApplied;
    final List<LogEntry> logs;
    final ConcurrentHashMap<Long, PeerState> peers;
    final long localPeerId;
    long snapshotLastIncludedIndex;
    long snapshotLastIncludedTerm;
    long lastSnapshotTs;
    volatile long currentLeaderId;
    public volatile long lastHeartbeatReceivedTs;
    volatile long electionDeadlineMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;
    final File dbRoot;
    final MetaMode mode;
    private final AtomicLong nextLogIndexAlloc;
    private final ConcurrentHashMap<Long, byte[]> pendingSnapshots;
    private volatile RaftStateMachineListener stateListener;

    protected RaftServer(File dbRoot, MetaMode mode, long localPeerId, long[] peerIds) {
        this.dbRoot = dbRoot;
        this.mode = mode;
        this.localPeerId = localPeerId;
        this.role = Role.FOLLOWER;
        this.currentTerm = 0L;
        this.votedFor = 0L;
        this.commitIndex = 0L;
        this.lastApplied = 0L;
        this.logs = Collections.synchronizedList(new ArrayList<>());
        this.peers = new ConcurrentHashMap<>();
        this.snapshotLastIncludedIndex = 0L;
        this.snapshotLastIncludedTerm = 0L;
        this.lastSnapshotTs = System.currentTimeMillis();
        this.currentLeaderId = 0L;
        this.lastHeartbeatReceivedTs = System.currentTimeMillis();
        this.running = new AtomicBoolean(true);
        this.nextLogIndexAlloc = new AtomicLong(1L);
        this.pendingSnapshots = new ConcurrentHashMap<>();
        if (peerIds != null) {
            for (long p : peerIds) {
                if (p <= 0L || p == localPeerId) {
                    continue;
                }
                PeerState ps = new PeerState();
                ps.nextIndex = 1L;
                ps.matchIndex = 0L;
                ps.lastRpcTs = 0L;
                this.peers.put(p, ps);
            }
        }
        resetElectionDeadline();
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "raft-server-" + localPeerId);
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::heartbeatOrElectionTick, 5, 5, TimeUnit.MILLISECONDS);
    }

    public static RaftServer create(File dbRoot, MetaMode mode, Map<String, String> props, long localPeerId, long[] peerIds) {
        if (mode == MetaMode.YAML) {
            return null;
        }
        String raftMode = props == null ? null : props.get("p2p.orm.raft.mode");
        if ("off".equalsIgnoreCase(raftMode)) {
            return null;
        }
        if (peerIds == null || peerIds.length == 0) {
            return new RaftServer(dbRoot, mode, localPeerId, new long[0]);
        }
        return new RaftServer(dbRoot, mode, localPeerId, peerIds);
    }

    public static RaftServer create(File dbRoot, MetaMode mode, Map<String, String> props) {
        return create(dbRoot, mode, props, parseLocalPeer(props, 1L), parseMembers(props));
    }

    static long[] parseMembers(Map<String, String> props) {
        String raw = props == null ? null : props.get("p2p.orm.raft.members");
        if (raw == null || raw.trim().isEmpty()) {
            return new long[]{2L};
        }
        String[] tokens = raw.split(",", -1);
        long[] ids = new long[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            ids[i] = parsePeerId(tokens[i].trim(), "p2p.orm.raft.members");
            for (int j = 0; j < i; j++) {
                if (ids[j] == ids[i]) {
                    throw new IllegalArgumentException("duplicate peerId " + ids[i] + " in p2p.orm.raft.members");
                }
            }
        }
        return ids;
    }

    static long parseLocalPeer(Map<String, String> props, long def) {
        String raw = props == null ? null : props.get("p2p.orm.raft.local");
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        return parsePeerId(raw.trim(), "p2p.orm.raft.local");
    }

    private static long parsePeerId(String token, String key) {
        if (token.isEmpty()) {
            throw new IllegalArgumentException("empty token for " + key);
        }
        try {
            if (token.length() > 2 && (token.startsWith("0x") || token.startsWith("0X"))) {
                return Long.parseLong(token.substring(2), 16);
            }
            return Long.parseLong(token);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("invalid token '" + token + "' for " + key, nfe);
        }
    }

    public static RaftServer create(File dbRoot, MetaMode mode) {
        return create(dbRoot, mode, null, 1L, new long[] { 2L });
    }

    static int quorumOf(int clusterSize) {
        return clusterSize <= 1 ? 1 : (clusterSize / 2) + 1;
    }

    public Role getRole() {
        return role;
    }

    public long getCurrentTerm() {
        return currentTerm;
    }

    public long getVotedFor() {
        return votedFor;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public long getLocalPeerId() {
        return localPeerId;
    }

    public long getCurrentLeaderId() {
        return currentLeaderId;
    }

    public String currentLeaderAddress() {
        return "peer-" + Long.toUnsignedString(currentLeaderId, 16);
    }

    public int logSize() {
        return logs.size();
    }

    public long getSnapshotLastIncludedIndex() {
        return snapshotLastIncludedIndex;
    }

    public boolean isRunning() {
        return running.get();
    }

    public PeerState peerState(long peerId) {
        return peers.get(peerId);
    }

    void heartbeatOrElectionTick() {
        if (!running.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (role == Role.LEADER) {
            for (Map.Entry<Long, PeerState> e : peers.entrySet()) {
                long peerId = e.getKey();
                PeerState ps = e.getValue();
                ps.lastRpcTs = now;
                tryAppendEntries(peerId, true);
            }
            advanceCommitIndex();
            return;
        }
        if (now >= electionDeadlineMs) {
            if (tryLockTickForPeer(localPeerId)) {
                try {
                    startElectionOrPreVote();
                } finally {
                    unlockTickForPeer(localPeerId);
                }
            } else {
                resetElectionDeadline();
            }
        }
    }

    private static final ConcurrentHashMap<Long, Long> TICK_LOCK = new ConcurrentHashMap<>();

    static boolean tryLockTickForPeer(long peerId) {
        long now = System.nanoTime();
        Long v = TICK_LOCK.putIfAbsent(peerId, now);
        if (v == null) {
            return true;
        }
        if (now - v > 15_000_000L) {
            TICK_LOCK.replace(peerId, v, now);
            return true;
        }
        return false;
    }

    static void unlockTickForPeer(long peerId) {
        TICK_LOCK.remove(peerId);
    }

    static final ThreadLocalRandom TLS = ThreadLocalRandom.current();

    static int randElection() {
        return TLS.nextInt(ELECTION_TIMEOUT_MIN_MS, ELECTION_TIMEOUT_MAX_MS + 1);
    }

    void resetElectionDeadline() {
        this.electionDeadlineMs = System.currentTimeMillis() + randElection();
    }

    synchronized static long currentTickSeed() {
        return System.nanoTime();
    }

    static long staggerForPeer(long peerId, int round) {
        long h = (peerId * 0x9E3779B97F4A7C15L + round * 0xD1B54A32D192ED03L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        return h & 0xfffffffL;
    }

    static final AtomicInteger ELECTION_ROUND = new AtomicInteger(0);

    synchronized void startElectionOrPreVote() {
        if (role == Role.LEADER || !running.get()) {
            return;
        }
        int round = ELECTION_ROUND.incrementAndGet();
        long myStagger = staggerForPeer(localPeerId, round);
        for (Long pid : peers.keySet()) {
            if (pid == null) {
                continue;
            }
            if (staggerForPeer(pid.longValue(), round) < myStagger) {
                resetElectionDeadline();
                return;
            }
        }
        long localLastIdx = snapshotLastIncludedIndex + logs.size();
        long localLastTerm;
        if (logs.isEmpty()) {
            localLastTerm = snapshotLastIncludedTerm;
        } else {
            localLastTerm = logs.get(logs.size() - 1).term;
        }
        long preVoteTerm = currentTerm + 1L;
        int preGranted = 1;
        for (Map.Entry<Long, PeerState> e : peers.entrySet()) {
            RaftServer peer = PEERS.get(e.getKey());
            if (peer == null) {
                continue;
            }
            try {
                VoteResult r = peer.preVote(preVoteTerm, localPeerId, localLastIdx, localLastTerm);
                if (r.voteGranted) {
                    preGranted++;
                }
            } catch (Exception ignored) {
            }
        }
        int q = quorumOf(1 + peers.size());
        if (preGranted < q) {
            resetElectionDeadline();
            return;
        }
        role = Role.CANDIDATE;
        currentTerm++;
        long newTerm = currentTerm;
        votedFor = localPeerId;
        resetElectionDeadline();
        int granted = 1;
        for (Map.Entry<Long, PeerState> e : peers.entrySet()) {
            RaftServer peer = PEERS.get(e.getKey());
            if (peer == null) {
                continue;
            }
            try {
                VoteResult r = peer.requestVote(newTerm, localPeerId, localLastIdx, localLastTerm);
                if (r.term > currentTerm) {
                    stepDownToFollower(r.term, 0L);
                    return;
                }
                if (r.voteGranted) {
                    granted++;
                }
            } catch (Exception ignored) {
            }
        }
        if (granted >= q && role == Role.CANDIDATE && currentTerm == newTerm) {
            becomeLeader();
        }
    }

    synchronized VoteResult handleRequestVote(long term, long candidateId, long lastLogIndex, long lastLogTerm, boolean preVote) {
        if (term < currentTerm) {
            return new VoteResult(currentTerm, false);
        }
        if (term > currentTerm) {
            currentTerm = term;
            votedFor = 0L;
            role = Role.FOLLOWER;
        }
        long localLastIdx = snapshotLastIncludedIndex + logs.size();
        long localLastTerm;
        if (logs.isEmpty()) {
            localLastTerm = snapshotLastIncludedTerm;
        } else {
            localLastTerm = logs.get(logs.size() - 1).term;
        }
        boolean canGrant = (votedFor == 0L || votedFor == candidateId)
                && (lastLogTerm > localLastTerm || (lastLogTerm == localLastTerm && lastLogIndex >= localLastIdx));
        if (canGrant) {
            if (!preVote) {
                votedFor = candidateId;
            }
            resetElectionDeadline();
            return new VoteResult(currentTerm, true);
        }
        return new VoteResult(currentTerm, false);
    }

    public VoteResult requestVote(long term, long candidateId, long lastLogIndex, long lastLogTerm) {
        return handleRequestVote(term, candidateId, lastLogIndex, lastLogTerm, false);
    }

    public VoteResult preVote(long term, long candidateId, long lastLogIndex, long lastLogTerm) {
        return handleRequestVote(term, candidateId, lastLogIndex, lastLogTerm, true);
    }

    void becomeLeader() {
        this.role = Role.LEADER;
        this.currentLeaderId = localPeerId;
        this.votedFor = 0L;
        long nextIdx = snapshotLastIncludedIndex + logs.size() + 1L;
        for (PeerState ps : peers.values()) {
            ps.nextIndex = nextIdx;
            ps.matchIndex = snapshotLastIncludedIndex;
        }
        appendNoop();
        resetElectionDeadline();
    }

    public synchronized void stepDownToFollower(long newTerm, long leaderId) {
        if (newTerm > currentTerm) {
            currentTerm = newTerm;
            votedFor = 0L;
        }
        role = Role.FOLLOWER;
        currentLeaderId = leaderId;
        lastHeartbeatReceivedTs = System.currentTimeMillis();
        resetElectionDeadline();
    }

    synchronized long append(byte cmdType, String registryPath, byte[] payload) {
        if (role != Role.LEADER) {
            throw new IllegalStateException("Not Leader; currentLeader=" + currentLeaderAddress());
        }
        LogEntry entry = new LogEntry(currentTerm, cmdType, registryPath, payload);
        logs.add(entry);
        trimTail();
        return snapshotLastIncludedIndex + logs.size();
    }

    public synchronized long appendPutRegistry(String registryPath, byte[] payload) {
        return append(CMD_PUT_REGISTRY, registryPath, payload);
    }

    public synchronized long appendRemoveRegistry(String registryPath) {
        return append(CMD_REMOVE_REGISTRY, registryPath, null);
    }

    public synchronized long appendMetaSave(String metaPath, byte[] metaBytes) {
        return append(CMD_META_SAVE, metaPath, metaBytes);
    }

    void appendNoop() {
        LogEntry entry = new LogEntry(currentTerm, CMD_NOOP, "", new byte[0]);
        logs.add(entry);
        trimTail();
    }

    void trimTail() {
        int over = logs.size() - MAX_LOG_ENTRIES;
        if (over > 0) {
            for (int i = 0; i < over; i++) {
                logs.remove(0);
            }
            snapshotLastIncludedIndex += over;
            snapshotLastIncludedTerm = logs.isEmpty() ? currentTerm : logs.get(0).term;
        }
    }

    synchronized AppendResult tryAppendEntries(long leaderId, long term, long prevLogIndex, long prevLogTerm,
            List<LogEntry> entries, long leaderCommit) {
        if (term < currentTerm) {
            return new AppendResult(currentTerm, false, 0L);
        }
        if (term > currentTerm || role != Role.FOLLOWER) {
            stepDownToFollower(term, leaderId);
        } else {
            currentLeaderId = leaderId;
            lastHeartbeatReceivedTs = System.currentTimeMillis();
            resetElectionDeadline();
        }
        long localLast = snapshotLastIncludedIndex + logs.size();
        if (prevLogIndex > localLast) {
            return new AppendResult(currentTerm, false, snapshotLastIncludedIndex + logs.size());
        }
        long baseIndex = prevLogIndex - snapshotLastIncludedIndex;
        if (prevLogIndex == snapshotLastIncludedIndex) {
        } else if (baseIndex <= 0 || baseIndex > logs.size()) {
            return new AppendResult(currentTerm, false, snapshotLastIncludedIndex + Math.max(0, logs.size()));
        } else {
            LogEntry prev = logs.get((int) (baseIndex - 1));
            if (prev.term != prevLogTerm) {
                for (int i = (int) (baseIndex - 1); i < logs.size(); i++) {
                    logs.remove(i);
                }
                return new AppendResult(currentTerm, false, snapshotLastIncludedIndex + logs.size());
            }
        }
        if (entries != null) {
            long idx = prevLogIndex;
            for (LogEntry e : entries) {
                idx++;
                long rel = idx - snapshotLastIncludedIndex;
                if (rel <= 0) {
                    continue;
                }
                if (rel <= logs.size()) {
                    LogEntry cur = logs.get((int) (rel - 1));
                    if (cur.term != e.term) {
                        while (logs.size() >= rel) {
                            logs.remove((int) (rel - 1));
                        }
                        logs.add((int) (rel - 1), e);
                    }
                } else {
                    logs.add(e);
                }
            }
            trimTail();
        }
        if (leaderCommit > commitIndex) {
            long newLocalLast = snapshotLastIncludedIndex + logs.size();
            commitIndex = Math.min(leaderCommit, newLocalLast);
            advanceLastApplied();
        }
        return new AppendResult(currentTerm, true, snapshotLastIncludedIndex + logs.size());
    }

    public AppendResult appendEntries(long leaderId, long term, long prevLogIndex, long prevLogTerm,
            List<LogEntry> entries, long leaderCommit) {
        return tryAppendEntries(leaderId, term, prevLogIndex, prevLogTerm, entries, leaderCommit);
    }

    synchronized SnapshotResult handleInstallSnapshot(InstallSnapshotChunk chunk) {
        if (chunk.term < currentTerm) {
            return new SnapshotResult(currentTerm, false);
        }
        if (chunk.term > currentTerm || role != Role.FOLLOWER) {
            stepDownToFollower(chunk.term, chunk.leaderId);
        } else {
            currentLeaderId = chunk.leaderId;
            lastHeartbeatReceivedTs = System.currentTimeMillis();
            resetElectionDeadline();
        }
        long key = (chunk.lastIncludedIndex << 16) ^ chunk.lastIncludedTerm;
        byte[] prev = pendingSnapshots.get(key);
        int needLen = (int) chunk.offset + chunk.data.length;
        byte[] next = prev == null ? new byte[needLen] : (prev.length >= needLen ? prev : new byte[needLen]);
        System.arraycopy(chunk.data, 0, next, (int) chunk.offset, chunk.data.length);
        pendingSnapshots.put(key, next);
        if (chunk.done) {
            pendingSnapshots.remove(key);
            snapshotLastIncludedIndex = chunk.lastIncludedIndex;
            snapshotLastIncludedTerm = chunk.lastIncludedTerm;
            logs.clear();
            commitIndex = Math.max(commitIndex, snapshotLastIncludedIndex);
            lastApplied = Math.max(lastApplied, snapshotLastIncludedIndex);
            lastSnapshotTs = System.currentTimeMillis();
        }
        return new SnapshotResult(currentTerm, true);
    }

    public SnapshotResult installSnapshot(InstallSnapshotChunk chunk) {
        return handleInstallSnapshot(chunk);
    }

    void tryAppendEntries(long peerId, boolean heartbeat) {
        PeerState ps = peers.get(peerId);
        if (ps == null) {
            return;
        }
        long localLast = snapshotLastIncludedIndex + logs.size();
        long prevIndex;
        long prevTerm;
        List<LogEntry> batch;
        if (ps.nextIndex <= snapshotLastIncludedIndex) {
            long last = snapshotLastIncludedIndex;
            long lastTerm = snapshotLastIncludedTerm;
            InstallSnapshotChunk c = new InstallSnapshotChunk(currentTerm, localPeerId, last, lastTerm, 0L, new byte[0], true);
            handleInstallSnapshotLocal(ps, c);
            return;
        } else {
            prevIndex = ps.nextIndex - 1L;
            if (prevIndex == snapshotLastIncludedIndex) {
                prevTerm = snapshotLastIncludedTerm;
            } else {
                int rel = (int) (prevIndex - snapshotLastIncludedIndex);
                if (rel < 1 || rel > logs.size()) {
                    ps.nextIndex = Math.max(1L, snapshotLastIncludedIndex + 1L);
                    return;
                }
                prevTerm = logs.get(rel - 1).term;
            }
            int from = (int) (ps.nextIndex - snapshotLastIncludedIndex);
            int cap = 0;
            batch = new ArrayList<>();
            for (int i = Math.max(0, from - 1); i < logs.size() && batch.size() < 100 && cap < 64 * 1024; i++) {
                LogEntry e = logs.get(i);
                batch.add(e);
                cap += 40 + (e.registryPath == null ? 0 : e.registryPath.length()) + e.payload.length;
            }
            if (batch.isEmpty() && !heartbeat) {
                return;
            }
        }
        try {
            AppendResult r = peerAppendEntriesMock(peerId, currentTerm, localPeerId, prevIndex, prevTerm, batch, commitIndex);
            if (r == null) {
                ps.nextIndex = Math.max(1L, ps.nextIndex - 1L);
                return;
            }
            if (r.term > currentTerm) {
                stepDownToFollower(r.term, 0L);
                return;
            }
            if (r.success) {
                ps.matchIndex = Math.max(ps.matchIndex, r.matchIndex);
                ps.nextIndex = Math.max(ps.nextIndex, r.matchIndex + 1L);
                advanceCommitIndex();
            } else {
                ps.nextIndex = Math.max(1L, r.matchIndex + 1L);
            }
        } catch (Exception ex) {
            ps.nextIndex = Math.max(1L, ps.nextIndex - 1L);
        }
    }

    AppendResult peerAppendEntriesMock(long peerId, long term, long leaderId, long prevIndex, long prevTerm,
            List<LogEntry> batch, long leaderCommit) throws Exception {
        // 单进程 in-JVM 对等体：通过 peerRegistry (静态) 查找对应 RaftServer 调用 tryAppendEntries
        RaftServer peer = PEERS.get(peerId);
        if (peer == null) {
            throw new IllegalStateException("unknown peer: " + peerId);
        }
        return peer.tryAppendEntries(leaderId, term, prevIndex, prevTerm, batch, leaderCommit);
    }

    void handleInstallSnapshotLocal(PeerState ps, InstallSnapshotChunk c) {
        ps.nextIndex = c.lastIncludedIndex + 1L;
        ps.matchIndex = c.lastIncludedIndex;
    }

    void advanceCommitIndex() {
        if (role != Role.LEADER) {
            return;
        }
        int total = 1 + peers.size();
        int q = quorumOf(total);
        long selfLast = snapshotLastIncludedIndex + logs.size();
        long[] candidates = new long[total];
        candidates[0] = selfLast;
        int i = 1;
        for (PeerState ps : peers.values()) {
            candidates[i++] = ps.matchIndex;
        }
        java.util.Arrays.sort(candidates);
        long n = candidates[total - q];
        if (n <= commitIndex) {
            return;
        }
        long rel = n - snapshotLastIncludedIndex;
        long finalN = commitIndex;
        if (rel > 0 && rel <= logs.size()) {
            LogEntry e = logs.get((int) (rel - 1));
            if (e != null && e.term == currentTerm) {
                finalN = n;
            }
        }
        if (finalN > commitIndex) {
            commitIndex = finalN;
            advanceLastApplied();
        }
    }

    protected void advanceLastApplied() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            long idx = lastApplied;
            long rel = idx - snapshotLastIncludedIndex;
            if (rel <= 0 || rel > logs.size()) {
                continue;
            }
            LogEntry e = logs.get((int) (rel - 1));
            applyToStateMachine(e);
            RaftStateMachineListener l = stateListener;
            if (l != null) {
                try {
                    l.onApplied(e, idx);
                } catch (Exception ignored) {
                }
            }
        }
        tryCompact();
    }

    protected void applyToStateMachine(LogEntry e) {
    }

    protected void tryCompact() {
        long total = snapshotLastIncludedIndex + logs.size();
        long age = System.currentTimeMillis() - lastSnapshotTs;
        if (total - snapshotLastIncludedIndex >= 1000L || age >= 10L * 60L * 1000L) {
            long newIdx = total;
            long newTerm = logs.isEmpty() ? currentTerm : logs.get(logs.size() - 1).term;
            if (newIdx > snapshotLastIncludedIndex) {
                snapshotLastIncludedIndex = newIdx;
                snapshotLastIncludedTerm = newTerm;
                logs.clear();
                lastSnapshotTs = System.currentTimeMillis();
            }
        }
    }

    public void forceCompact() {
        long total = snapshotLastIncludedIndex + logs.size();
        if (total <= snapshotLastIncludedIndex) {
            return;
        }
        long newTerm = logs.isEmpty() ? currentTerm : logs.get(logs.size() - 1).term;
        snapshotLastIncludedIndex = total;
        snapshotLastIncludedTerm = newTerm;
        logs.clear();
        lastSnapshotTs = System.currentTimeMillis();
    }

    public boolean awaitApplied(long logIndex, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastNano = System.nanoTime();
        int spins = 0;
        while (lastApplied < logIndex && System.currentTimeMillis() < deadline) {
            long now = System.nanoTime();
            if (now - lastNano < 200_000L && spins < 5000) {
                Thread.onSpinWait();
                spins++;
            } else if (now - lastNano < 2_000_000L) {
                Thread.yield();
                lastNano = now;
                spins = 0;
            } else {
                Thread.sleep(1L);
                lastNano = System.nanoTime();
                spins = 0;
            }
        }
        return lastApplied >= logIndex;
    }

    public byte[] readRaftStateBytes() {
        ByteBuffer bb = ByteBuffer.allocate(32);
        bb.putLong(currentTerm);
        bb.putLong(votedFor);
        bb.putLong(commitIndex);
        bb.putLong(lastApplied);
        return bb.array();
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdownNow();
            PEERS.remove(localPeerId);
        }
    }

    public static final ConcurrentHashMap<Long, RaftServer> PEERS = new ConcurrentHashMap<>();

    public static void register(RaftServer server) {
        if (server != null) {
            PEERS.put(server.localPeerId, server);
        }
    }

    public static void deregister(long peerId) {
        PEERS.remove(peerId);
    }

    public static RaftServer byPeerId(long peerId) {
        return PEERS.get(peerId);
    }

    public void setStateMachineListener(RaftStateMachineListener listener) {
        this.stateListener = listener;
    }

    public RaftStateMachineListener getStateMachineListener() {
        return stateListener;
    }

    static {
        try {
            Class.forName(RegistryMetaPaths.class.getName());
        } catch (Throwable ignored) {
        }
    }
}
