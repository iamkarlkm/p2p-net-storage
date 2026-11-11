package com.q3lives.ds.database.fulltext.cluster.raft;

import com.q3lives.ds.registry.meta.raft.RaftServer;

import java.io.IOException;

/**
 * P58 T2：Leader 侧 fulltext 写入口，把分片写命令经 P57 Raft 提交后再返回。
 *
 * <p>
 * 写命令被编码成 {@code registryPath + payload} 二元组（见 {@link FulltextRaftWire}），再通过
 * {@link RaftServer#appendPutRegistry} 进入复制日志。仅当多数派提交且本条日志已推进到本地 apply 位点
 * （{@link RaftServer#awaitApplied} 返回 true），本次写调用才成功返回；否则抛出
 * {@link IOException}。 各副本（含 Leader）的索引落盘由各自的 {@link RaftFulltextReplica} apply
 * worker 串行完成， 因此写调用返回时该写入已进入多数派日志、并即将在全部副本上收敛为一  *
 *  <p>
 * 
 * 本类只负责提交，不直接触碰 {@code ShardRouter}：真正的索引变更由各副本的 {@link RaftFulltextReplica}
 * 完成，Leader 自身也一样，避免"提交路径 

    */
public final class RaftFulltextCommitter {

    private static final long DEFAULT_TIMEOUT_MS = 5000L;

    private final RaftServer raft;
    private final long timeoutMs;

    public RaftFulltextCommitter(RaftServer raft) {
        this(raft, DEFAULT_TIMEOUT_MS);
    }

    public RaftFulltextCommitter(RaftServer raft, long timeoutMs) {
        if (raft == null) {
            throw new IllegalArgumentException("raft must not be null");
        }
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.raft = raft;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 提交一次 {@code putDoc}：正文经 UTF-8 编码写入日志，返回已提交并应用的日志索引。
     */
    public long putDoc(long docId, String text) throws IOException {
        return submit(FulltextRaftWire.docPath(docId), FulltextRaftWire.encodeText(text));
    }

    /**
     * 提交一次 {@code clearDoc}：删除命令不含正文，副本按 docId 定位同一 shard 清理。
     */
    public long clearDoc(long docId) throws IOException {
        return submit(FulltextRaftWire.clearPath(docId), null);
    }

    /**
     * 提交一次 {@code indexTermVector}：向量按大端 float32 编码写入日志。
     */
    public long indexTermVector(long uid, float[] vec) throws IOException {
        return submit(FulltextRaftWire.vecPath(uid), FulltextRaftWire.encodeFloats(vec));
    }

    private long submit(String path, byte[] payload) throws IOException {
        long idx;
        synchronized (raft) {
            idx = raft.appendPutRegistry(path, payload);
        }
        try {
            if (!raft.awaitApplied(idx, timeoutMs)) {
                throw new IOException("raft apply timeout: path=" + path + " index=" + idx);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while awaiting raft apply: " + path, ie);
        }
        return idx;
    }
}
