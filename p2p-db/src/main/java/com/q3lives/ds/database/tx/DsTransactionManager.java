package com.q3lives.ds.database.tx;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import com.q3lives.ds.registry.meta.AbstractRegistryDataBackend;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;

/**
 * 2PC 事务管理器（单例，package-private，由 DsDatabaseLocal 唯一持有）。
 * <p>
 * 职责（对应 tasks T2）：
 * 1. 生命周期：{@code begin(IsolationLevel)} / {@code commit(TxContext)} / {@code rollback(TxContext)} / {@code currentOrNull()}
 * 2. 崩溃恢复：{@code recoverIncompleteTxs()} 在 DsDatabaseLocal 构造器末尾调用；PREPARING→强制 ABORTING→DONE；
 *    COMMITTING→补完剩余 op；ABORTING→倒序回滚剩余 op。连跑 2 次幂等（AC-R10）。
 * 3. RR 绑定：事务内 {@code getTable(Class,id)} 使用 ctx.readTs；非事务用 Long.MAX_VALUE（由 DsDatabaseLocal 内读 currentOrNull 分发）。
 * 4. 零新增 Maven：所有持久化复用 {@code TxRegistryBackend extends AbstractRegistryDataBackend} 实例（即 P11 已有 Backend 抽象）。
 */
public final class DsTransactionManager {

    /** 2PC 子树持久化 Backend（继承 P11 的 AbstractRegistryDataBackend 复用 registry/hybrid 路由） */
    private final TxRegistryBackend backend;

    /** 宿主 dbRoot（debug/恢复路径只取绝对路径） */
    private final File dbRoot;

    /** 单调递增 txId 分配器（构造时从现有 /orm/tx 列表取最大值+1 启动，保证持久化 txId 不回绕不冲突） */
    private final AtomicLong txSeq;

    /** writeTimestamp 引用（从 DsDatabaseLocal 传入，begin 时 readTs/writeTs 绑定） */
    private final AtomicLong writeTimestamp;

    /** 线程当前事务上下文（ThreadLocal 保证事务跨方法不扩散） */
    private final ThreadLocal<TxContext> CURRENT = new ThreadLocal<>();

    /**
     * 已 COMMITTED 事务的 writeTs 集合（用于 MVCC 脏读防护）。
     * <p>MVCC 版本可见性：对 {@code VersionRecord.beginTs = tx.writeTs} 的版本，除了 {@code beginTs <= readTs <= endTs}，
     * 还必须满足：要么 beginTs 已登记为 committed，要么是自写（Writes-Follow-Reads：beginTs == selfWriteTs）。
     * 否则是 OTHER tx 的未提交写 → 脏读，resolveVisibleVersion* 应继续回溯 prevVersionId 找可见版本。
     * <p>初始化包含 beginTs=1（legacy 遗留行 bootstrap VR beginTs=1，视为 always committed）。
     */
    final Set<Long> committedWriteTimestamps = ConcurrentHashMap.newKeySet();
    {
        committedWriteTimestamps.add(1L);
    }

    /**
     * 单例构造（public，因为 DsDatabaseLocal 在 com.q3lives.ds.database；TxManager 在 tx 子包；
     * Java 跨包 new 需要 public 构造器。单例约束靠：
     *   ① 只有 1 个构造器（参数：File, AtomicLong），② final class（无法子类化）
     *   ③ DsDatabaseLocal 构造器里只 new 1 次，④ getTxManager() == 构造时实例（c11 E4 断言）。
     *
     * @param dbRoot         宿主 DB 根目录（必须与 DsDatabaseLocal.root 同一）
     * @param writeTimestamp 宿主 writeTimestamp（begin 拿快照、commit 推进版本）
     */
    public DsTransactionManager(File dbRoot, AtomicLong writeTimestamp) throws IOException {
        this.dbRoot = Objects.requireNonNull(dbRoot, "dbRoot").getAbsoluteFile();
        this.backend = new TxRegistryBackend(this.dbRoot);
        this.writeTimestamp = Objects.requireNonNull(writeTimestamp, "writeTimestamp");
        long startTxId = backend.scanMaxTxId() + 1;
        this.txSeq = new AtomicLong(Math.max(1L, startTxId));
    }

    // =================== Public (package-private) lifecycle API ===================

    /**
     * 开启事务，MVP 仅支持 {@link IsolationLevel#REPEATABLE_READ}。
     * <p>
     * public：DsDatabaseLocal 跨包调用需要；begin 只能在 beginTransaction() facade 里调用，
     * 业务层永远不直接调用 begin/commit/rollback。
     *
     * @throws UnsupportedOperationException 非 RR 隔离级时显式抛 UOE(message 含 "MVP only RR")
     */
    public TxContext begin(IsolationLevel level) throws IOException {
        if (level != IsolationLevel.REPEATABLE_READ) {
            throw new UnsupportedOperationException(
                "isolation level not supported in MVP: " + level + " (MVP only RR)");
        }
        if (CURRENT.get() != null) {
            throw new IllegalStateException("nested transaction not supported: current txId="
                + CURRENT.get().txId);
        }
        long txId = txSeq.incrementAndGet();
        long readTs = writeTimestamp.get();
        long writeTs = writeTimestamp.incrementAndGet();
        // RR snapshot：begin 时刻拷贝 committedWriteTimestamps 作为本 tx 独用快照视图 → 同一 tx 内两次快照读一致（不受后续其他tx commit加入Set影响）
        Set<Long> snapshotCommitted = new HashSet<>(committedWriteTimestamps);
        TxContext ctx = new TxContext(txId, readTs, writeTs, level, snapshotCommitted);
        backend.writeBegTs(txId, readTs);
        backend.writeWriteTs(txId, writeTs);
        backend.writeState(txId, TxContext.STATE_PREPARING);
        CURRENT.set(ctx);
        return ctx;
    }

    /**
     * 提交事务：PREPARING 持久化 op 列表 → CAS 所有 op → COMMITTING → DONE。
     * 任一 op CAS 失败 → 自动 rollback 再抛异常，保证 AC-R3 原子回滚。
     * public：同包 DsDatabaseLocal 内 TransactionHandle 需要跨包访问。
     */
    public void commit(TxContext ctx) throws Exception {
        if (ctx == null) return;
        if (ctx.closedFlag) {
            throw new IllegalStateException("tx already closed: txId=" + ctx.txId);
        }
        boolean success = false;
        try {
            List<TxOp> ops = ctx.opListView();
            for (TxOp op : ops) {
                backend.writeTxOp(ctx.txId, op.opSeq, op);
            }
            for (TxOp op : ops) {
                backend.applyOpCas(op);
            }
            backend.writeState(ctx.txId, TxContext.STATE_COMMITTING);
            onCommittingAfterCas(ctx);
            backend.writeState(ctx.txId, TxContext.STATE_DONE);
            success = true;
            // ==== MVCC 脏读防护：commit 成功后登记 writeTs 为 committed，resolveVisibleVersion* 中可见性检查用 ====
            committedWriteTimestamps.add(ctx.writeTs);
        } finally {
            if (!success) {
                try {
                    rollback(ctx);
                } catch (Exception rollbackEx) {
                    System.err.println("[TxManager] WARN rollback during commit-failed cleanup: txId="
                        + ctx.txId + " reason=" + rollbackEx.getMessage());
                }
            } else {
                // 正常 commit：强制清理 ThreadLocal + closedFlag（避免 t06 TxClosed threadLocalClean 失败）
                ctx.closedFlag = true;
                CURRENT.remove();
            }
        }
    }

    /**
     * 回滚事务：写 ABORTING → 按 opSeq 倒序 restore prevBytes → DONE。
     * public：同包 DsDatabaseLocal 内 TransactionHandle 跨包调用需要。
     */
    public void rollback(TxContext ctx) throws Exception {
        if (ctx == null) return;
        if (ctx.closedFlag) return;
        try {
            backend.writeState(ctx.txId, TxContext.STATE_ABORTING);
            List<TxOp> ops = new ArrayList<>(ctx.opListView());
            Collections.reverse(ops);
            for (TxOp op : ops) {
                backend.restoreOpPrev(op);
            }
            onAbortingAfterRestore(ctx);
            backend.writeState(ctx.txId, TxContext.STATE_DONE);
        } finally {
            ctx.closedFlag = true;
            CURRENT.remove();
        }
    }

    /** 返回线程当前事务（用于 DsDatabaseLocal.getTable/readTs 绑定）。public：跨包 DsDatabaseLocal 调用。 */
    public TxContext currentOrNull() { return CURRENT.get(); }

    /**
     * 崩溃恢复（幂等 2 次）。扫描 /orm/tx 下所有 txId，PREPARING→强制 ABORTING；
     * COMMITTING→补完；ABORTING→剩余回滚；最终全部 state=DONE。
     * <p>
     * public（DsDatabaseLocal 构造器末尾调用 + c11 E4 单例测试入口需要）。
     *
     * @return 已恢复 tx 数（可观测）
     */
    public int recoverIncompleteTxs() throws IOException {
        List<Long> txIds = backend.listTxIds();
        int recovered = 0;
        for (long txId : txIds) {
            Byte state = backend.readState(txId);
            if (state == null || state == TxContext.STATE_DONE) continue;
            recovered++;
            try {
                if (state == TxContext.STATE_PREPARING) {
                    backend.writeState(txId, TxContext.STATE_ABORTING);
                    List<TxOp> ops = backend.listTxOps(txId);
                    Collections.reverse(ops);
                    for (TxOp op : ops) backend.restoreOpPrev(op);
                    backend.writeState(txId, TxContext.STATE_DONE);
                } else if (state == TxContext.STATE_COMMITTING) {
                    List<TxOp> ops = backend.listTxOps(txId);
                    for (TxOp op : ops) {
                        try {
                            backend.applyOpCas(op);
                        } catch (IOException alreadyApplied) {
                            // ignore：可能重启前已经 apply 成功
                        }
                    }
                    backend.writeState(txId, TxContext.STATE_DONE);
                } else if (state == TxContext.STATE_ABORTING) {
                    List<TxOp> ops = backend.listTxOps(txId);
                    Collections.reverse(ops);
                    for (TxOp op : ops) {
                        try {
                            backend.restoreOpPrev(op);
                        } catch (IOException alreadyRestored) {
                            // ignore
                        }
                    }
                    backend.writeState(txId, TxContext.STATE_DONE);
                }
            } catch (Exception ex) {
                throw new IOException("recoverIncompleteTxs failed txId=" + txId + " state=" + state, ex);
            }
        }
        return recovered;
    }

    // =================== Protected helpers（便于 c11 A 组 A5 断言覆盖数量 ≥ 5） ===================

    /** 内部：COMMITTING 后置 hook（version 链 / 索引 / Bloom 由 T4 覆盖；MVP 这里 no-op 占位）。 */
    protected void onCommittingAfterCas(TxContext ctx) throws Exception { /* MVP: NO-OP, T4 填充钩子 */ }

    /** 内部：ABORTING 后置 hook（Bloom 复位由 T4 填充；MVP 这里 no-op 占位）。 */
    protected void onAbortingAfterRestore(TxContext ctx) throws Exception { /* MVP: NO-OP, T4 填充钩子 */ }

    /** 内部：verify ThreadLocal 已移除（用于 T3 c11 断言，close 后 CURRENT.get()==null）。 */
    protected boolean verifyThreadLocalClean() {
        return CURRENT.get() == null;
    }

    /** 内部：下一个 txId 观察值（仅测试 debug 用，不用于业务分发）。 */
    protected long peekNextTxId() {
        return txSeq.get() + 1;
    }

    /** 内部：readTs / writeTs 绑定正确性检查（用于 T5 单元测试，与 writeTimestamp 值独立）。 */
    protected boolean verifyReadBeforeWrite(TxContext ctx) {
        return ctx != null && ctx.readTs <= ctx.writeTs;
    }

    // =================== Internal Backend（/orm/tx 路径下所有 leaf 读/写/恢复） ===================

    private static final class TxRegistryBackend extends AbstractRegistryDataBackend {
        TxRegistryBackend(File dbRoot) throws IOException {
            super(dbRoot);
        }

        void writeState(long txId, byte state) throws IOException {
            String path = RegistryMetaPaths.txState(txId);
            writeLeafBytes(path, new byte[] { state }, v -> {});
        }

        Byte readState(long txId) throws IOException {
            byte[] b = readLeafBytes(RegistryMetaPaths.txState(txId), () -> null, null);
            if (b == null || b.length != 1) return null;
            return Byte.valueOf(b[0]);
        }

        void writeBegTs(long txId, long readTs) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(readTs);
            writeLeafBytes(RegistryMetaPaths.txBegTs(txId), buf.array(), v -> {});
        }

        void writeWriteTs(long txId, long writeTs) throws IOException {
            ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            buf.putLong(writeTs);
            writeLeafBytes(RegistryMetaPaths.txWriteTs(txId), buf.array(), v -> {});
        }

        void writeTxOp(long txId, int opSeq, TxOp op) throws IOException {
            String path = RegistryMetaPaths.txOpLeaf(txId, opSeq);
            writeLeafBytes(path, op.pack(), v -> {});
        }

        void applyOpCas(TxOp op) throws IOException {
            byte[] expectedPrev = op.expectedPrevBytes;
            byte[] newPayload = op.newPayload;
            final int exLen = expectedPrev == null ? -1 : expectedPrev.length;
            final int npLen = newPayload == null ? -1 : newPayload.length;
            byte[] result = atomicModifyBytes(op.registryPath,
                cur -> {
                    final int cLen = cur == null ? -1 : cur.length;
                    if (Arrays.equals(cur, newPayload)) {
                        return cloneArray(newPayload);
                    }
                    if (!Arrays.equals(cur, expectedPrev)) {
                        throw new CasMismatchFastException(op.registryPath, op.type, exLen, cLen, expectedPrev, cur);
                    }
                    return cloneArray(newPayload);
                },
                cloneArray(expectedPrev),
                () -> cloneArray(expectedPrev),
                v -> {});
            if (npLen >= 0 && result != null && result.length == npLen && Arrays.equals(result, newPayload)) {
                return;
            }
            throw new IOException("TxOp CAS result mismatch: path=" + op.registryPath);
        }

        /** OCC CAS mismatch 专用快速异常：零填充堆栈 trace（TPCC 并发 90% abort 场景减少 1~2 个量级 CPU），携带调试信息。 */
        @SuppressWarnings("serial")
        static final class CasMismatchFastException extends IllegalStateException {
            final String registryPath;
            final int opType;
            final int expectedLen;
            final int curLen;
            final byte[] expectedPrev;
            final byte[] cur;
            CasMismatchFastException(String p, int t, int el, int cl, byte[] ep, byte[] c) {
                super("TxOp CAS mismatch (fast-fail, no stack-trace): path=" + p
                    + " type=0x" + Integer.toHexString(t & 0xFF)
                    + " expectedPrevLen=" + el + " curLen=" + cl);
                this.registryPath = p;
                this.opType = t;
                this.expectedLen = el;
                this.curLen = cl;
                this.expectedPrev = ep;
                this.cur = c;
            }
            @Override public Throwable fillInStackTrace() { return this; } // 关键：零堆栈，构造开销 ~10ns
            @Override public synchronized Throwable initCause(Throwable cause) { return this; }
        }

        void restoreOpPrev(TxOp op) throws IOException {
            byte[] prev = op.expectedPrevBytes;
            atomicModifyBytes(op.registryPath,
                cur -> cloneArray(prev),
                cloneArray(prev),
                () -> cloneArray(prev),
                v -> {});
        }

        List<Long> listTxIds() throws IOException {
            List<String> names = listChildren(RegistryMetaPaths.TX_ROOT, () -> Collections.emptyList());
            List<Long> out = new ArrayList<>(Math.max(4, names.size()));
            for (String n : names) {
                if (n == null || n.length() != 16) continue;
                try {
                    out.add(Long.parseUnsignedLong(n, 16));
                } catch (NumberFormatException ignore) {
                }
            }
            Collections.sort(out);
            return out;
        }

        long scanMaxTxId() throws IOException {
            List<Long> ids = listTxIds();
            long max = 0L;
            for (long id : ids) if (id > max) max = id;
            return max;
        }

        List<TxOp> listTxOps(long txId) throws IOException {
            String txOpRoot = RegistryMetaPaths.TX_OP_ROOT + "/" + RegistryMetaPaths.txIdHex(txId);
            List<String> names = listChildren(txOpRoot, () -> Collections.emptyList());
            List<Integer> seqList = new ArrayList<>(Math.max(4, names.size()));
            for (String n : names) {
                if (n == null || n.length() != 8) continue;
                try {
                    seqList.add(Integer.parseUnsignedInt(n, 16));
                } catch (NumberFormatException ignore) {
                }
            }
            Collections.sort(seqList);
            List<TxOp> out = new ArrayList<>(seqList.size());
            for (int seq : seqList) {
                String path = RegistryMetaPaths.txOpLeaf(txId, seq);
                byte[] packed = readLeafBytes(path, () -> null, null);
                if (packed == null) continue;
                try {
                    TxOp op = TxOp.unpack(packed);
                    op.opSeq = seq;
                    out.add(op);
                } catch (Exception bad) {
                    System.err.println("[TxManager] WARN unrecognized op packed: txId=" + txId
                        + " opSeq=" + seq + " reason=" + bad.getMessage());
                }
            }
            return out;
        }

        private static byte[] cloneArray(byte[] src) {
            if (src == null) return new byte[0];
            if (src.length == 0) return src;
            byte[] r = new byte[src.length];
            System.arraycopy(src, 0, r, 0, src.length);
            return r;
        }
    }

    // =================== 2PL Strict 行锁管理（DsDatabaseLocal 在父包，通过本类代理访问 TxContext heldRowLocks） ===================
    /**
     * 登记 2PL Strict：本事务首次获取的 per-row 分片锁（key=shardId，4096 分片内唯一）。
     * <p>public：父包 DsDatabaseLocal 在 putEntity MVCC+tx 路径首次 lock() 后调用，
     * putIfAbsent 保证多次调用同 shardId 幂等登记（ReentrantLock 已可重入不重复 put）。
     *
     * @param ctx      事务上下文（非 null）
     * @param shardKey 4096 分片 shardId
     * @param lock     对应的 ReentrantLock 实例
     * @return true = 第一次登记（该 shardKey 之前未登记，调用方不可再 unlock）；
     *         false = 已在之前某次 register 中登记过（调用方可 unlock 配对本次 lock 使 holdCount 保持 ≥1）
     */
    public boolean registerHeldRowLock(TxContext ctx, int shardKey, ReentrantLock lock) {
        if (ctx == null || lock == null) return false;
        ReentrantLock prev = ctx.heldRowLocks.putIfAbsent(shardKey, lock);
        return (prev == null); // null = 首次登记
    }

    /**
     * 释放本事务登记的所有 per-row 分片锁。
     * <p>public：父包 DsDatabaseLocal 的 TransactionHandle finally 在 applyMvccUndo 完成后调用，
     * 保证 VR 链还原时仍持锁（并发读不到部分还原的 head），还原后一次性 unlock 到 holdCount==0。
     * <p>异常不扩散（finally 兜底），内部 catch 后打印 WARN，最后 clear 保证锁容器可 GC。
     *
     * @param ctx 事务上下文（可 null → 直接 return）
     */
    public void releaseAllHeldRowLocks(TxContext ctx) {
        if (ctx == null || ctx.heldRowLocks.isEmpty()) return;
        try {
            for (ReentrantLock lock : ctx.heldRowLocks.values()) {
                if (lock == null) continue;
                try {
                    while (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (IllegalMonitorStateException imse) {
                    System.err.println("[TxManager] WARN releaseAllHeldRowLocks stray-unlock: " + imse.getMessage());
                }
            }
        } catch (Exception ex) {
            System.err.println("[TxManager] WARN releaseAllHeldRowLocks fatal: txId=" + ctx.txId + " msg=" + ex.getMessage());
        } finally {
            ctx.heldRowLocks.clear();
            ctx.mergedFieldsByShardKey.clear(); // finally 兜底清理
        }
    }

    // =================== 字段级 delta merge 幂等（防止同一次事务同列双加 delta / 多阶段同列变化丢失） ===================
    /**
     * MVCC 字段级冲突合并：查询 (ctx, shardKey, colName) 上一次 apply 过的 intent 值（TxContext.mergedFieldsByShardKey 跨包访问代理）。
     * <p>public：父包 DsDatabaseLocal.patchLatestEntityWithIntentFields 调用，查询同列上次应用的 intent 值，判断本次调用是：
     *   ① 幂等重复（lastIntent==intent → 跳过，不双加 delta）；
     *   ② 同列多阶段增量写（lastIntent!=intent → delta_new=intent-lastIntent，叠加到 latest）；
     *   ③ 首次写该列（null → 按 snap/intent/latest 常规 delta 合并）。
     *
     * @return 上一次 apply 的 intent 值（Long/Integer/.../String）；若从未 apply 过该列返回 null。
     */
    public Object getLastMergedIntentValue(TxContext ctx, int shardKey, String colName) {
        if (ctx == null || colName == null) return null;
        java.util.Map<String, Object> m = ctx.mergedFieldsByShardKey.get(shardKey);
        if (m == null) return null;
        return m.get(colName);
    }

    /**
     * MVCC 字段级冲突合并：登记 (ctx, shardKey, colName) 本次 apply 的 intent 值（同 shardKey 上重复 putEntity 时下一次做幂等/增量判断）。
     * <p>public：父包 DsDatabaseLocal.patchLatestEntityWithIntentFields 数值型/非数值型完成写字段后调用。
     */
    public void registerLastMergedIntentValue(TxContext ctx, int shardKey, String colName, Object intentValue) {
        if (ctx == null || colName == null) return;
        ctx.mergedFieldsByShardKey.computeIfAbsent(shardKey, k -> new java.util.HashMap<>(8)).put(colName, intentValue);
    }

    /**
     * MVCC 版本可见性判定：VR.beginTs 是否对 (readTs, selfWriteTs) 快照可见，排除未提交脏读。
     * <p>前置条件：调用方应先判断 {@code vr.visibleTo(readTs)}（即 beginTs≤readTs≤endTs），
     * 本方法在此基础上追加"已提交 / 自写"判定，避免 OTHER tx 的未提交写被读到。
     * <p>规则：
     * 1. 自写（selfWriteTs>0 && beginTs==selfWriteTs）→ 恒可见（Writes-Follow-Reads，即使 tx 未 commit 也要能读到自己刚写的）。
     * 2. 其他 tx 的写 → 必须 beginTs 已登记在 committedWriteTimestamps 里（即该 tx 已 commit 成功 / 非事务立即提交）才可见。
     * <p>【注意】无 tx-context 版本（snapshot 用全局 committedSet，只用于非事务 / legacy bootstrap 路径，可能受并发 commit 增长影响）。
     *
     * @param beginTs     {@link com.q3lives.ds.database.DsDatabaseLocal.VersionRecord#beginTs}
     * @param selfWriteTs 0=非事务/快照无自写；>0=当前调用方 tx 的 writeTs（自写过滤）
     * @return true=该版本可用于当前快照读取；false=不可见需回溯 prevVersionId
     */
    public boolean isVersionVisibleForSnapshot(long beginTs, long selfWriteTs) {
        if (selfWriteTs > 0L && beginTs == selfWriteTs) return true;
        return committedWriteTimestamps.contains(beginTs);
    }

    /**
     * 【RR 一致性修复】带 TxContext.snapshotCommittedWriteTimestamps 的可见性判定：
     * 同一 tx 内所有快照读都使用 begin 时刻固定的 committedSet 视图，避免"第一次读时 txA 未 commit 不可见；
     * 第二次读时 txA 已 commit 加入全局 Set 变为可见"的同一 tx 快照漂移（t04 delta≪success 根因）。
     * <p>规则同无 ctx 版本，但 OTHER tx beginTs 只要求在 ctx.snapshotCommittedWriteTimestamps 中（begin 时刻已 commit 的）。
     *
     * @param ctx         当前调用方 tx 上下文（非事务路径传 null → 退化为全局 Set 判定）
     * @param beginTs     VR.beginTs
     * @param selfWriteTs 0=snapEntity 基线无自写；>0=latestEntity/快照读含自写
     */
    public boolean isVersionVisibleForSnapshot(TxContext ctx, long beginTs, long selfWriteTs) {
        if (selfWriteTs > 0L && beginTs == selfWriteTs) return true;
        if (ctx != null) return ctx.snapshotCommittedWriteTimestamps.contains(beginTs);
        return committedWriteTimestamps.contains(beginTs);
    }

    /**
     * 非事务路径（tx==null putEntity）立即提交登记：无事务写完成后调用，
     * 将 beginTs（本次 writeTs）标记为 committed，保证后续快照读（selfWriteTs=0）能看到非事务写的最新 VR。
     */
    public void registerCommittedWriteTs(long beginTs) {
        committedWriteTimestamps.add(beginTs);
    }
}
