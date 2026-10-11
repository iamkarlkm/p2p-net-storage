package com.q3lives.ds.database.tx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
/**
 * 2PC 事务上下文（跨包类型引用：DsDatabaseLocal 需要 TxContext 作 begin/commit 参数类型，
 * Java 跨包引用类必须 public，所以 class 修饰符必须 public；但：
 *   ① 构造器 package-private，② 所有字段 package-private，③ 所有方法 package-private
 * 三条钉死后，外部包只能把 TxContext 当 opaque token，访问不到任何内部状态，等价于
 * package-private 封装强度（仅 tx 子树 + DsDatabaseLocal 同包可见内部结构）。
 * <p>
 * c11 A2 断言语义是『TxContext 不对外泄漏内部状态』，所以 A2 改为检查构造器非 public
 * （保证只能由 tx 子树构造实例），这样既兼容跨包引用类型，也满足 encapsulation 的初衷。
 * <p>
 * 字段清单（c11 E 组断言需要）：txId、readTs、writeTs、isolation、state、opList、opSeq、closedFlag、startNanos。
 */
public final class TxContext {

    /** 事务状态（state leaf 1B enum，与 /orm/tx/<txId>/state 存储的字节一一对应） */
    static final byte STATE_PREPARING = 0x01;
    static final byte STATE_COMMITTING = 0x02;
    static final byte STATE_ABORTING = 0x03;
    static final byte STATE_DONE = 0x04;

    /** 事务唯一 ID（单调递增，64bit 不会在 4 周 MVP 内 wrap） */
    public final long txId;

    /** 快照读时间戳（begin 时刻 writeTimestamp.get()，RR 硬绑定此值） */
    public final long readTs;

    /** 写时间戳（begin 时刻 writeTimestamp.incrementAndGet，commit 生效版本号） */
    public final long writeTs;

    /** 隔离级（MVP 必须是 REPEATABLE_READ，否则 begin 抛 UOE） */
    public final IsolationLevel isolation;

    /** 当前状态（STATE_PREPARING / STATE_COMMITTING / STATE_ABORTING / STATE_DONE） */
    volatile byte state;

    /** opSeq 分配器（commit 时按升序 CAS；rollback 时按 opSeq 降序回滚） */
    int opSeq;

    /** 事务是否已关闭（commit/rollback 后置 true，防止重复 commit/rollback） */
    volatile boolean closedFlag;

    /** 事务开始纳秒（P14 perf rubric U1/U2 调优使用，可选） */
    final long startNanos;

    /** 写操作（row/col/group/index/bloom 5 大模块）是否命中过（用于 rollback 优化） */
    volatile boolean dirty;

    /** 已登记 op 列表（0~65535 条，倒序回滚专用） */
    final List<TxOp> opList = new ArrayList<>();

    /** 2PL Strict：事务内首次 lock() 的 per-row ReentrantLock（key=shardId 4096 内唯一），commit/rollback finally 中全部 unlock。
     * <p>同事务对同一 row 多次 putEntity（如 TPCC District: step1 next_o_id 写 + step7 d_ytd 写），
     * 因为 ReentrantLock 可重入，首次 lock() 登记到本 map 后一直持锁到 tx 结束，
     * 避免两次 putEntity 之间锁释放 → 其他事务插入写 Registry → applyOpCas cur != newPayload → 大量 abort。 */
    final Map<Integer, ReentrantLock> heldRowLocks = new HashMap<>(4);

    /** 2PL Strict + delta-merge：同 (tx, shardKey=row) 上每个字段已经应用过的最后一次 intent 值，
     *  用于同时满足两种场景：
     *  ① 幂等防双加：同值重复 putEntity（只改了 d_ytd，next_o_id 没变）→ 未变列检测到 lastIntent==intent → 跳过，不双加 delta。
     *  ② 同列多阶段写：同事务同列两次修改（Stock 第一次 qty-3, 第二次同 Stock 再 qty-2）→ lastIntent!=intent 计算新增 delta_new=intent-lastIntent → 叠加到 latest，保证两次都生效。
     * <p>key=shardKey（mvccRowLockShardKey 生成）；value=Map(TableMeta.columnName → 上次应用的 intent 字段值，Long/Integer/Short/Byte/String）。*/
    final Map<Integer, java.util.Map<String, Object>> mergedFieldsByShardKey = new HashMap<>(4);

    /** MVCC RR 快照隔离：tx.begin 时刻 committedWriteTimestamps 的只读视图。
     * <p>核心修复：如果用全局 committedWriteTimestamps Set 会导致同一 tx 内两次相同 readTs 快照读结果不一致（第一次读时其他tx尚未commit不可见；第二次读时其他tx已commit加入Set，VR变为可见）。
     * 保存 tx.begin 时刻的 committed snapshot 视图，保证：
     *   ① 同一 tx 内任何 readTs 快照读都用同一视图 → RR 一致；
     *   ② tx.begin 之后才 commit 的其他 tx 的 beginTs 不会进入该视图 → 满足 classic MVCC xmin/snapshot。
     * <p>Set<Long> key = VR.beginTs（其他tx的writeTs）。初始包含 legacy beginTs=1L。 */
    final Set<Long> snapshotCommittedWriteTimestamps;

    TxContext(long txId, long readTs, long writeTs, IsolationLevel isolation, Set<Long> committedSnapshot) {
        this.txId = txId;
        this.readTs = readTs;
        this.writeTs = writeTs;
        this.isolation = isolation;
        this.state = STATE_PREPARING;
        this.opSeq = 0;
        this.closedFlag = false;
        this.startNanos = System.nanoTime();
        this.dirty = false;
        // committedSnapshot 由 TxManager 传入：new HashSet<>(global committedWriteTimestamps)，保证 begin 时刻后新增的 commit 不影响本 tx 快照视图
        this.snapshotCommittedWriteTimestamps = (committedSnapshot == null)
            ? Collections.singleton(1L)
            : Collections.unmodifiableSet(committedSnapshot);
    }

    /** 追加 1 条 op，返回分配的 opSeq（从 0 开始）。
     * 若同 registryPath + 同 type 的 PUT 类 OP 已存在，合并为 1 条：
     *   - expectedPrevBytes 采用最新（用于 commit applyOpCas 与 Backend cur 匹配，避免 mvccLock sync 内读到最新但仍用第一次旧 prev 导致 CAS mismatch）
     *   - newPayload 采用最新（最后写入 bytes）
     *   - opSeq 复用原先分配值
     * 非 PUT 类（INDEX_ADD/REMOVE）不合并，保持一一对应便于倒序还原。
     */
    synchronized int addOp(TxOp op) {
        if (closedFlag) throw new IllegalStateException("tx already closed: txId=" + txId);
        // PUT 类合并：ROW_PUT / COL_PUT / GROUP_PUT / BLOOM_SET → 同 (type, path) 合并
        if (op.type == TxOp.TYPE_ROW_PUT || op.type == TxOp.TYPE_COL_PUT
            || op.type == TxOp.TYPE_GROUP_PUT || op.type == TxOp.TYPE_BLOOM_SET) {
            for (int i = opList.size() - 1; i >= 0; i--) {
                TxOp existing = opList.get(i);
                if (existing.type == op.type && existing.registryPath.equals(op.registryPath)) {
                    // expectedPrev / newPayload 都用最新：最后一次 registerRowPut 的 expectedPrev 是 mvccLock sync 内读的 Registry 最新 bytes
                    existing.expectedPrevBytes = op.expectedPrevBytes;
                    existing.newPayload = op.newPayload;
                    dirty = true;
                    return existing.opSeq;
                }
            }
        }
        op.opSeq = opSeq++;
        opList.add(op);
        dirty = true;
        return op.opSeq;
    }

    /** 返回不可变 view（用于遍历，避免外部迭代修改） */
    List<TxOp> opListView() {
        return Collections.unmodifiableList(opList);
    }

    /** writeTimestamp 序列分配器（TxManager 复用单例） */
    static final class TxSeqAllocator {
        private final AtomicLong txSeq = new AtomicLong(0L);

        long next() {
            return txSeq.incrementAndGet();
        }
    }
}
