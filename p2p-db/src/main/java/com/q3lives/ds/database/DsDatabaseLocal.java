/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.q3lives.ds.database;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.yaml.snakeyaml.Yaml;

import com.q3lives.ds.annotation.DsCompositeField;
import com.q3lives.ds.annotation.DsField;
import com.q3lives.ds.annotation.DsManyToMany;
import com.q3lives.ds.annotation.DsMapField;
import com.q3lives.ds.annotation.DsOneToMany;
import com.q3lives.ds.annotation.DsOneToOne;
import com.q3lives.ds.annotation.DsTable;
import com.q3lives.ds.binlog.DsBinlogCheckpoint;
import com.q3lives.ds.binlog.DsBinlogContext;
import com.q3lives.ds.binlog.DsBinlogEntry;
import com.q3lives.ds.binlog.DsBinlogLogLevel;
import com.q3lives.ds.binlog.DsBinlogOpType;
import com.q3lives.ds.binlog.DsBinlogPosition;
import com.q3lives.ds.binlog.DsBinlogStore;
import com.q3lives.ds.binlog.DsBytes;
import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.cluster.DsIdLayout;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.collections.DsHashSet;
import com.q3lives.ds.database.adapter.DsTableAdapter;
import com.q3lives.ds.database.columnar.ColumnRegistry;
import com.q3lives.ds.database.columnar.ColumnarStore;
import com.q3lives.ds.database.columnar.TableMetaStore;
import com.q3lives.ds.database.index.DsEqIndexStore;
import com.q3lives.ds.database.schema.EntityIndexUtil;
import com.q3lives.ds.database.schema.EntitySchemaUtil;
import com.q3lives.ds.database.tx.DsTransactionManager;
import com.q3lives.ds.database.tx.IsolationLevel;
import com.q3lives.ds.database.tx.Transaction;
import com.q3lives.ds.database.tx.TxContext;
import com.q3lives.ds.database.tx.TxOpRegistry;
import com.q3lives.ds.enums.DataType;
import com.q3lives.ds.registry.DsRegistry;
import com.q3lives.ds.registry.DsRegistryExtAttrsHelper;
import com.q3lives.ds.registry.RegistryEntry;
import com.q3lives.ds.registry.meta.AbstractRegistryBackend.YamlSupplier;
import com.q3lives.ds.registry.meta.AbstractRegistryDataBackend;
import com.q3lives.ds.registry.meta.AbstractRegistryReplicator;
import com.q3lives.ds.registry.meta.DsRegistryInstance;
import com.q3lives.ds.registry.meta.MetaMode;
import com.q3lives.ds.registry.meta.RegistryMetaPaths;
import com.q3lives.ds.util.DsPathUtil;

/**
 *
 * @author Administrator
 */
public class DsDatabaseLocal implements AutoCloseable {

    private static final String DEFAULT_SYSTEM_YAML_NAME = "SystemConfig.yaml";
    private static final String SYS_PROP_SYSTEM_YAML = "p2p.system.yaml";
    private static final String SYS_PROP_DB_HOME = "p2p.db.home";
    private static final ThreadLocal<DsDatabaseLocal> CURRENT_HOLDER = new ThreadLocal<>();

    public static void setCurrent(DsDatabaseLocal db) {
        if (db == null) {
            CURRENT_HOLDER.remove();
        } else {
            CURRENT_HOLDER.set(db);
        }
    }

    public static DsDatabaseLocal currentOrNull() {
        return CURRENT_HOLDER.get();
    }

    private final File root;
    private final DsFixedBucketStore bucketStore;
    /**
     * P11 OQ1：行 payload 额外双写 Registry / orm/data/row 子树，HYBRID 默认；YAML 模式下
     * =null 短路零开销
     */
    private final RowDataBackend rowDataBackend;
    /**
     * P12 OQ2：跨节点 DML 同步 Replicator。MetaMode.YAML 或 sync=off → null（短路零
     * new），package-private 便于同包 ColumnarStore/TableMetaStore 直接引用
     */
    final AbstractRegistryReplicator registryReplicator;
    private final DsBinlogStore binlogStore;
    private final int binlogServerId;
    private final int partitionId;
    private final AtomicLong binlogSequence = new AtomicLong(0);
    private final ConcurrentHashMap<Class<? extends DsTableAdapter>, DsHashSet> classIdSetCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<? extends DsTableAdapter>, TableMeta<?>> tableMetaCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Class<? extends DsTableAdapter>> tableIdToClass = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsHashMap> relationMapCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DsEqIndexStore> indexCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<? extends DsTableAdapter>, DsHashMap> versionHeadMapCache = new ConcurrentHashMap<>();
    /**
     * MVCC 事务内 putEntity/removeTable 时，per-(meta+rowId) 细粒度锁，防止并发写同 row 互相覆盖
     * VersionRecord/headMap/index。
     * <p>
     * 不使用 meta 级粗锁：TPCC 10T 并发下 meta 锁会造成所有 W/D/C/S/Order 串行化，OCC 冲突率 >
     * 98%（success 20/1000 < 900 阈值）。 <p>
     * 锁 key = mix32(meta.space.hashCode()) * 1000003 ^ mix64(rowId)；同一 shard
     * 恒映射同一 ReentrantLock 实例（putIfAbsent 单例）。
     * <p>
     * 2PL Strict：tx 内首次 lock() 后登记到 TxContext.heldRowLocks，commit/rollback
     * finally 统一 unlock。同事务同 row 多次 putEntity 可重入不释放， 避免两次 putEntity 锁间隙其他事务插入改
     * Registry → applyOpCas cur!=newPayload → 大量 abort。
     */
    private static final int ROW_LOCK_SEGMENTS = 4096;
    private final ConcurrentHashMap<Integer, ReentrantLock> mvccRowLockCache = new ConcurrentHashMap<>(ROW_LOCK_SEGMENTS * 4 / 3 + 1, 0.75f, 64);

    private ReentrantLock mvccRowLockFor(TableMeta<?> meta, long rowId) {
        int h = (meta == null ? 0 : meta.space.hashCode());
        h ^= (int) (rowId ^ (rowId >>> 32));
        h ^= h >>> 16;
        h &= (ROW_LOCK_SEGMENTS - 1);
        final Integer key = h;
        ReentrantLock lock = mvccRowLockCache.get(key);
        if (lock == null) {
            ReentrantLock fresh = new ReentrantLock();
            ReentrantLock existing = mvccRowLockCache.putIfAbsent(key, fresh);
            lock = (existing != null) ? existing : fresh;
        }
        return lock;
    }

    /**
     * 计算 per-row 锁 shard key（用于登记到 TxContext.heldRowLocks，key 唯一对应 4096 shard
     * 内一个锁实例，重复 put 同一 shard 幂等）。
     */
    private int mvccRowLockShardKey(TableMeta<?> meta, long rowId) {
        int h = (meta == null ? 0 : meta.space.hashCode());
        h ^= (int) (rowId ^ (rowId >>> 32));
        h ^= h >>> 16;
        return h & (ROW_LOCK_SEGMENTS - 1);
    }
    private final AtomicLong writeTimestamp = new AtomicLong(1);
    private volatile long lastCheckpointSaveAt = 0L;
    private static final long CHECKPOINT_SAVE_INTERVAL_MS = 500L;
    /**
     * 并行回放时对写操作（putEntities / removeTable）加锁，保证底层 DsEqIndexStore 与 bucketStore
     * 在 apply 阶段不会被并发修改。
     */
    private final Object replayApplyLock = new Object();
    /**
     * P14 OQ3：2PC 事务管理器（单例，package-private，仅 DsDatabaseLocal 持有）。 由构造器末尾 new
     * 并立即执行 {@code recoverIncompleteTxs()} 完成崩溃恢复。
     */
    final DsTransactionManager txManager;
    /**
     * P14 OQ3：MVCC 事务内 putEntity 产生的版本链变更 undo 记录，按 txId 分组， 在 ABORTING
     * 阶段倒序重放（对应 t02/t03 回滚后必须 byte-for-byte 还原可见性）。 key=txId；value=该 tx 内按
     * putEntity 顺序的 undo 记录。
     */
    final ConcurrentHashMap<Long, List<MvccUndo>> txMvccUndoMap = new ConcurrentHashMap<>();
    /**
     * P18 OQ1: Online DDL 调度器（内嵌类，NG-2 0 Facade 新增，通过已存在 3 个
     * forceApply/cancel/rollback hook 调用）。
     */
    private final OnlineDdlCoordinator odCoordinator;

    public DsDatabaseLocal(File root) {
        this(root, 0, 0);
    }

    public DsDatabaseLocal(File root, int serverId) {
        this(root, serverId, 0);
    }

    public DsDatabaseLocal(File root, int serverId, int partitionId) {
        if (serverId < 0 || serverId > DsIdLayout.SERVER_ID_MASK) {
            throw new IllegalArgumentException("serverId out of range: " + serverId);
        }
        if (partitionId < 0 || partitionId > DsIdLayout.PARTITION_MASK) {
            throw new IllegalArgumentException("partitionId out of range: " + partitionId);
        }
        this.root = Objects.requireNonNull(root, "root cannot be null");
        if (!this.root.exists()) {
            this.root.mkdirs();
        }
        this.bucketStore = new DsFixedBucketStore(this.root.getAbsolutePath());
        RowDataBackend rdb = null;
        AbstractRegistryReplicator rr = null;
        if (MetaMode.global() != MetaMode.YAML) {
            try {
                rdb = new RowDataBackend(this.root, this);
            } catch (Exception e) {
                rdb = null;
                System.err.println("[DsDatabaseLocal] WARN 初始化 RowDataBackend 失败（继续使用 bucketStore 独立模式，已与原基线一致）: root=" + this.root.getAbsolutePath() + " reason=" + e.getMessage());
            }
            try {
                rr = AbstractRegistryReplicator.create(this.root, MetaMode.global());
            } catch (Exception e) {
                rr = null;
                System.err.println("[DsDatabaseLocal] WARN 初始化 AbstractRegistryReplicator 失败（sync=off，降级为单节点模式，不影响正确性）: root=" + this.root.getAbsolutePath() + " reason=" + e.getMessage());
            }
        }
        this.rowDataBackend = rdb;
        this.registryReplicator = rr;
        try {
            this.binlogStore = new DsBinlogStore(this.root, true);
            long recoveredSeq = this.binlogStore.recoverLastSequence();
            this.binlogSequence.set(recoveredSeq);
            recoverFromBinlog();
        } catch (IOException e) {
            throw new RuntimeException("failed to open binlog store", e);
        }
        this.binlogServerId = serverId;
        this.partitionId = partitionId;
        DsTransactionManager txm;
        try {
            txm = new DsTransactionManager(this.root, this.writeTimestamp);
            int recovered = txm.recoverIncompleteTxs();
            if (recovered > 0) {
                System.err.println("[DsDatabaseLocal] INFO P14 OQ3 2PC 崩溃恢复 completed: recoveredTxs=" + recovered);
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to init TxManager / recover incomplete 2PC txs", e);
        }
        this.txManager = txm;
        this.odCoordinator = new OnlineDdlCoordinator();
    }

    public int serverId() {
        return binlogServerId;
    }

    public int partitionId() {
        return partitionId;
    }

    public DsHashSet getIdSetForClass(Class<? extends DsTableAdapter> clazz) {
        return idSetForClass(clazz);
    }

    public File getRoot() {
        return root;
    }

    public DsBinlogStore getBinlogStore() {
        return binlogStore;
    }

    /**
     * 开启事务（默认 RR 可重复读，MVP 唯一支持的隔离级）。
     * <p>
     * 返回的 {@link Transaction} 实现 {@link AutoCloseable}： try-with-resources 中未显式
     * commit → close() 自动 rollback（AC-R12）。
     *
     * @throws IOException 初始化 /orm/tx 子树或写 begTs/writeTs 失败
     */
    public final Transaction beginTransaction() throws IOException {
        final TxContext ctx = txManager.begin(IsolationLevel.REPEATABLE_READ);
        return new TransactionHandle(this, ctx);
    }

    /**
     * 开启事务（显式指定隔离级）。MVP 仅 {@link IsolationLevel#REPEATABLE_READ} 有效， 其余 3 级显式抛
     * {@link UnsupportedOperationException}（message 含 "MVP only RR"），禁止静默降级。
     */
    public final Transaction beginTransaction(IsolationLevel level) throws IOException {
        final TxContext ctx = txManager.begin(level);
        return new TransactionHandle(this, ctx);
    }

    /**
     * 语法糖：在事务内执行 Runnable。begin → runnable.run → commit； 任何 Exception →
     * rollback 后重新抛出（IOException 保留类型，RuntimeException 原样抛）。
     */
    public final void runInTransaction(Runnable r) throws IOException {
        try (Transaction tx = beginTransaction()) {
            r.run();
            tx.commit();
        } catch (IOException ioe) {
            throw ioe;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IOException("runInTransaction failed", e);
        }
    }

    /**
     * 语法糖：在事务内执行 Callable，返回结果。任何 Exception → rollback 后 rethrow。
     */
    public final <T> T callInTransaction(java.util.concurrent.Callable<T> c) throws Exception {
        try (Transaction tx = beginTransaction()) {
            T result = c.call();
            tx.commit();
            return result;
        }
    }

    /**
     * package-private 升级为 public（c11 E4 断言单例测试需要；仅返回 txManager，禁止业务层
     * override，通过 final 修饰符保护。
     */
    public final DsTransactionManager getTxManager() {
        return txManager;
    }

    public void close() throws IOException {
        Exception ex = null;
        for (DsEqIndexStore idx : indexCache.values()) {
            try {
                idx.close();
            } catch (Exception e) {
                ex = e;
            }
        }
        indexCache.clear();
        for (DsHashMap map : versionHeadMapCache.values()) {
            try {
                map.close();
            } catch (Exception e) {
                ex = e;
            }
        }
        versionHeadMapCache.clear();
        try {
            if (bucketStore != null) {
                bucketStore.close();
            }
        } catch (Exception e) {
            ex = e;
        }
        try {
            if (binlogStore != null) {
                binlogStore.close();
            }
        } catch (Exception e) {
            ex = e;
        }
        if (ex != null) {
            if (ex instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(ex);
        }
    }

    public static void forceResetAllIndexesForTest(File root) throws IOException {
        Objects.requireNonNull(root, "root cannot be null");
        if (!root.isDirectory()) {
            return;
        }
        File indexes = new File(root, "indexes");
        if (!indexes.exists()) {
            return;
        }
        try (var stream = java.nio.file.Files.walk(indexes.toPath())) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.delete(p);
                        } catch (IOException ignore) {
                        }
                    });
        }
    }

    public static DsDatabaseLocal load() {
        File home = resolveDbHomeFromYamlOrSystem();
        return new DsDatabaseLocal(home);
    }

    public static DsDatabaseLocal loadOrNull() {
        File home = resolveDbHomeFromYamlOrSystemOrNull();
        if (home == null) {
            return null;
        }
        return new DsDatabaseLocal(home);
    }

    private static File resolveDbHomeFromYamlOrSystemOrNull() {
        String home = System.getProperty(SYS_PROP_DB_HOME);
        if (home != null && !home.isBlank()) {
            return new File(home).getAbsoluteFile();
        }
        return null;
    }

    private static File resolveDbHomeFromYamlOrSystem() {
        String home = System.getProperty(SYS_PROP_DB_HOME);
        if (home != null && !home.isBlank()) {
            return new File(home).getAbsoluteFile();
        }

        String yamlPath = System.getProperty(SYS_PROP_SYSTEM_YAML);
        File yamlFile = null;
        if (yamlPath != null && !yamlPath.isBlank()) {
            yamlFile = new File(yamlPath).getAbsoluteFile();
        } else {
            File local = new File(System.getProperty("user.dir", "."), DEFAULT_SYSTEM_YAML_NAME).getAbsoluteFile();
            if (local.isFile()) {
                yamlFile = local;
            }
        }

        if (yamlFile == null || !yamlFile.isFile()) {
            return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
        }

        try (InputStream in = new FileInputStream(yamlFile)) {
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            Object v = map.get("DbHome");
            if (v == null) {
                v = map.get("dbHome");
            }
            if (!(v instanceof String s) || s.isBlank()) {
                return new File(System.getProperty("user.dir", "."), "dsdb").getAbsoluteFile();
            }
            File f = new File(s);
            if (f.isAbsolute()) {
                return f.getAbsoluteFile();
            }
            return new File(yamlFile.getParentFile(), s).getAbsoluteFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TableMeta<? extends DsTableAdapter> metaOf(Class<? extends DsTableAdapter> clazz) {
        return tableMetaCache.computeIfAbsent(clazz, this::createMeta);
    }

    private static final class IndexedColumn {

        final String columnName;
        final Field field;
        final Class<?> fieldType;
        final DsEqIndexStore.IndexedValueKind valueKind;
        final boolean mvcc;

        IndexedColumn(String columnName, Field field, Class<?> fieldType, DsEqIndexStore.IndexedValueKind valueKind, boolean mvcc) {
            this.columnName = columnName;
            this.field = field;
            this.fieldType = fieldType;
            this.valueKind = valueKind;
            this.mvcc = mvcc;
        }
    }

    private static final class CompositeIndex {

        final String name;
        final List<IndexedColumn> columns;
        final boolean mvcc;

        CompositeIndex(String name, List<IndexedColumn> columns, boolean mvcc) {
            this.name = name;
            this.columns = columns;
            this.mvcc = mvcc;
        }
    }

    private static DsEqIndexStore.IndexedValueKind resolveValueKind(Class<?> type) {
        if (type == long.class || type == Long.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        if (type == int.class || type == Integer.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        if (type == boolean.class || type == Boolean.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        if (type == Date.class) {
            return DsEqIndexStore.IndexedValueKind.LONG;
        }
        if (type == String.class) {
            return DsEqIndexStore.IndexedValueKind.STRING;
        }
        return DsEqIndexStore.IndexedValueKind.STRING;
    }

    // ================= Binlog integration =================
    private <T extends DsTableAdapter> long appendBinlog(TableMeta<T> meta, T newRow, T oldRow, DsBinlogOpType opType) throws IOException {
        if (binlogStore == null || meta.columns.isEmpty()) {
            return -1L;
        }
        DsBinlogLogLevel level = meta.binlogLogLevel;
        if (level == null) {
            level = DsBinlogLogLevel.REDO;
        }
        // 无旧值的变更在 UNDO 级别下没有可记录的 before image，降级为 REDO。
        if (level == DsBinlogLogLevel.UNDO && (opType == DsBinlogOpType.INSERT || oldRow == null)) {
            level = DsBinlogLogLevel.REDO;
        }

        int n = meta.columns.size();
        int[] colIds = new int[n];
        for (int i = 0; i < n; i++) {
            colIds[i] = i;
        }
        long rowId = newRow.getId() != null ? newRow.getId() : (oldRow != null && oldRow.getId() != null ? oldRow.getId() : 0L);

        if (level == DsBinlogLogLevel.FULL) {
            // FULL：先写 UNDO 帧（旧值），再写 REDO 帧（新值），保证 UNDO seq < REDO seq。
            // INSERT 没有旧值；DELETE 的 before/after image 相同，只写一帧 REDO（旧值）。
            if (opType == DsBinlogOpType.UPDATE && oldRow != null) {
                Object[] undoValues = extractBinlogValues(meta, oldRow);
                long undoSeq = binlogSequence.incrementAndGet();
                binlogStore.append(opType, binlogServerId, undoSeq, meta.tableId, (int) rowId, colIds, undoValues, DsBinlogLogLevel.UNDO);
            }
            Object[] redoValues = extractBinlogValues(meta, newRow);
            long redoSeq = binlogSequence.incrementAndGet();
            return binlogStore.append(opType, binlogServerId, redoSeq, meta.tableId, (int) rowId, colIds, redoValues, DsBinlogLogLevel.REDO);
        }

        long seq = binlogSequence.incrementAndGet();
        T rowToLog = (level == DsBinlogLogLevel.UNDO && oldRow != null) ? oldRow : newRow;
        Object[] values = extractBinlogValues(meta, rowToLog);
        return binlogStore.append(opType, binlogServerId, seq, meta.tableId, (int) rowId, colIds, values, level);
    }

    private <T extends DsTableAdapter> Object[] extractBinlogValues(TableMeta<T> meta, T row) {
        int n = meta.columns.size();
        Object[] values = new Object[n];
        for (int i = 0; i < n; i++) {
            EntitySchemaUtil.ColumnDef col = meta.columns.get(i);
            Object val = readFieldValue(col.declaredField, row);
            values[i] = fieldValueToBinlog(val, col.declaredField.getType());
        }
        return values;
    }

    /**
     * 将 binlog 已写入但未 fsync 的帧强制落盘。
     */
    private void flushBinlog() throws IOException {
        if (binlogStore != null) {
            binlogStore.forceFlushAll();
        }
    }

    /**
     * 将主表、索引、MVCC 版本头映射的脏页同步到磁盘。
     */
    private void syncDataAndIndexes() throws IOException {
        bucketStore.sync();
        for (DsEqIndexStore idx : indexCache.values()) {
            idx.sync();
        }
        for (DsHashMap map : versionHeadMapCache.values()) {
            map.syncAll();
        }
    }

    /**
     * 写 CHECKPOINT 帧并持久化位点文件。
     *
     * @param committedSequence 已落盘数据覆盖到的最大数据变更序列号
     * @param replayedSequence 已落盘回放数据覆盖到的最大序列号（当前与 committedSequence 一致）
     */
    private void writeCheckpoint(long committedSequence, long replayedSequence) throws IOException {
        if (binlogStore == null) {
            return;
        }
        binlogStore.writeAndSaveCheckpoint(binlogServerId, binlogSequence.incrementAndGet(),
                committedSequence, replayedSequence, true, true);
    }

    /**
     * group commit 收尾：先 fsync binlog，再 sync data/index，最后追加 CHECKPOINT 帧。
     */
    private void commitGroup() throws IOException {
        flushBinlog();
        syncDataAndIndexes();
        long now = System.currentTimeMillis();
        if (now - lastCheckpointSaveAt >= CHECKPOINT_SAVE_INTERVAL_MS) {
            synchronized (this) {
                long now2 = System.currentTimeMillis();
                if (now2 - lastCheckpointSaveAt >= CHECKPOINT_SAVE_INTERVAL_MS) {
                    long committedSeq = binlogStore != null ? binlogStore.lastSequence() : 0L;
                    writeCheckpoint(committedSeq, committedSeq);
                    lastCheckpointSaveAt = now2;
                }
            }
        }
    }

    // ================= Binlog position / replay =================
    /**
     * 获取当前 binlog 排他水位（dayKey + fixedOffset + sequence）。
     */
    public DsBinlogPosition getCurrentBinlogPosition() throws IOException {
        return binlogStore.getCurrentPosition();
    }

    /**
     * 强制切换 binlog 日文件（跨日 rotate），返回新的 dayKey。 主要用于测试跨天回放，也可由外部定时任务调用。
     */
    public String rotateBinlog() throws IOException {
        return binlogStore.rotate();
    }

    /**
     * 按指定 dayKey 切换 binlog 日文件。
     */
    public String rotateBinlog(String dayKey) throws IOException {
        return binlogStore.rotate(dayKey);
    }

    /**
     * 注册实体类，建立 tableId -> Class 映射。 时间线恢复的目标库在回放前必须注册所有相关实体类。
     */
    public <T extends DsTableAdapter> void registerTable(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        tableIdToClass.put(meta.tableId, clazz);
    }

    /**
     * 启动恢复：加载 checkpoint，从 checkpoint 位点开始回放 binlog， 最后将回放结果刷盘并写入新的 CHECKPOINT。
     */
    private void recoverFromBinlog() throws IOException {
        DsBinlogCheckpoint checkpoint = binlogStore.loadCheckpoint();
        DsBinlogPosition start;
        if (checkpoint != null && checkpoint.isValid()) {
            start = checkpoint.toPosition();
        } else {
            List<String> days = binlogStore.listDayKeys();
            if (days.isEmpty()) {
                return;
            }
            start = new DsBinlogPosition(days.get(0), 0L, 0L);
        }
        DsBinlogPosition end = binlogStore.getCurrentPosition();
        if (end == null || start == null) {
            return;
        }
        if (start.dayKey().equals(end.dayKey()) && start.fixedOffset() >= end.fixedOffset()) {
            return;
        }
        int replayed = replayBinlog(start, end);
        if (replayed > 0) {
            syncDataAndIndexes();
            long committedSeq = binlogStore.lastSequence();
            writeCheckpoint(committedSeq, committedSeq);
        }
    }

    /**
     * 在 [start, end) 区间重放 binlog，将行事件应用为 put/remove。 回放期间 DsBinlogContext 会短路
     * append，避免循环写 binlog。
     */
    public int replayBinlog(DsBinlogPosition start, DsBinlogPosition end) throws IOException {
        DsBinlogContext.setInReplay(true);
        try {
            return binlogStore.replayBinlog(start, end, this::applyBinlogEntry);
        } finally {
            DsBinlogContext.clear();
        }
    }

    /**
     * 批量聚合回放：将连续的 INSERT/UPDATE 按表聚合后调用 {@link #putEntities(List, boolean)}，
     * DELETE 立即单独执行。batchSize &le; 1 时退化为单条回放。
     *
     * <p>
     * 注意：MVCC 表当前 {@code putEntities} 仍逐行写入，但语义一致；后续可继续扩展 MVCC 批量。</p>
     */
    public int replayBinlog(DsBinlogPosition start, DsBinlogPosition end, int batchSize) throws IOException {
        if (batchSize <= 1) {
            return replayBinlog(start, end);
        }
        BatchReplayHandler handler = new BatchReplayHandler(batchSize);
        int total = binlogStore.replayBinlog(start, end, handler);
        handler.flush();
        return total;
    }

    /**
     * 并行批量聚合回放：按 rowKey hash 将事件分区到独立队列，每个分区内部保持顺序； 同一分区内连续的 INSERT/UPDATE
     * 聚合为批量 putEntities，DELETE 立即 flush 后执行。
     *
     * <p>
     * 由于底层 DsEqIndexStore 非线程安全，所有实际写操作通过 {@link #replayApplyLock} 串行化；
     * 分区并行主要释放事件解码与队列调度的 CPU 压力。parallelPartitions &le; 1 时退化为串行批量。</p>
     */
    public int replayBinlog(DsBinlogPosition start, DsBinlogPosition end, int batchSize, int parallelPartitions) throws IOException {
        if (batchSize <= 1 || parallelPartitions <= 1) {
            return replayBinlog(start, end, batchSize);
        }
        ParallelBatchReplayHandler handler = new ParallelBatchReplayHandler(batchSize, parallelPartitions);
        int total = binlogStore.replayBinlog(start, end, handler);
        handler.shutdownAndAwait();
        return total;
    }

    @SuppressWarnings("unchecked")
    public <T extends DsTableAdapter> boolean applyBinlogEntry(long frameOffset, DsBinlogEntry entry) {
        System.err.println("[DEBUG] apply frameOffset=" + frameOffset + " seq=" + entry.sequence()
                + " op=" + entry.opType() + " tableId=" + entry.tableId() + " rowKey=" + entry.rowKey());
        if (entry.isUndo()) {
            // UNDO 帧默认不参与正向回放；仅用于手动回滚或审计。
            return true;
        }
        Class<T> clazz = (Class<T>) tableIdToClass.get(entry.tableId());
        if (clazz == null) {
            // 未注册表：跳过并继续（调用方应提前 registerTable）
            return true;
        }
        try {
            TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
            long rowId = DsFixedBucketStore.encodeRowId(entry.rowKey() & 0xFFFFFFFFL, meta.rowLength);
            DsBinlogOpType op = entry.opType();
            if (op == DsBinlogOpType.DELETE) {
                removeTable(clazz, rowId, false);
                return true;
            }
            if (op == DsBinlogOpType.INSERT || op == DsBinlogOpType.UPDATE) {
                T entity = decodeEntityFromBinlog(meta, entry, rowId);
                putEntity(entity, false);
                return true;
            }
            // DDL / XA / HEARTBEAT / NOOP 等不参与行级回放
            return true;
        } catch (IOException e) {
            throw new RuntimeException("binlog apply failed at frameOffset=" + frameOffset
                    + ", tableId=" + entry.tableId() + ", op=" + entry.opType(), e);
        }
    }

    private <T extends DsTableAdapter> T decodeEntityFromBinlog(TableMeta<T> meta, DsBinlogEntry entry, long rowId) throws IOException {
        T entity = newInstance(meta.clazz);
        entity.setId(rowId);
        DsBytes dsBytes = binlogStore.dsBytes();
        int n = entry.colCount();
        int[] colIds = entry.colIds();
        for (int i = 0; i < n; i++) {
            int colId = colIds[i];
            if (colId < 0 || colId >= meta.columns.size()) {
                continue;
            }
            EntitySchemaUtil.ColumnDef col = meta.columns.get(colId);
            DsBinlogEntry.ColValue cv = entry.decodeCol(i, dsBytes);
            Object fieldValue = binlogValueToFieldValue(cv.decoded, col.declaredField.getType());
            writeFieldValue(col.declaredField, entity, fieldValue);
        }
        return entity;
    }

    /**
     * 批量聚合回放处理器：按 class 缓冲 INSERT/UPDATE 实体，达到 batchSize 或遇到 DELETE 时 flush。
     */
    private final class BatchReplayHandler implements com.q3lives.ds.binlog.DsBinlogHandler {

        private final int batchSize;
        private final Map<Class<? extends DsTableAdapter>, List<DsTableAdapter>> pendingByClass = new HashMap<>();
        private int pendingCount = 0;

        BatchReplayHandler(int batchSize) {
            this.batchSize = batchSize;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean apply(long frameOffset, DsBinlogEntry entry) throws IOException {
            if (entry.isUndo()) {
                // UNDO 帧默认不参与正向回放。
                return true;
            }
            Class<? extends DsTableAdapter> clazz = tableIdToClass.get(entry.tableId());
            if (clazz == null) {
                return true;
            }
            TableMeta<?> meta = metaOf(clazz);
            long rowId = DsFixedBucketStore.encodeRowId(entry.rowKey() & 0xFFFFFFFFL, meta.rowLength);

            DsBinlogOpType op = entry.opType();
            if (op == DsBinlogOpType.DELETE) {
                flush();
                removeTable(clazz, rowId, false);
                return true;
            }
            if (op == DsBinlogOpType.INSERT || op == DsBinlogOpType.UPDATE) {
                DsTableAdapter entity = decodeEntityFromBinlog(meta, entry, rowId);
                pendingByClass.computeIfAbsent(clazz, k -> new ArrayList<>()).add(entity);
                pendingCount++;
                if (pendingCount >= batchSize) {
                    flush();
                }
                return true;
            }
            return true;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void flush() throws IOException {
            if (pendingCount == 0) {
                return;
            }
            for (Map.Entry<Class<? extends DsTableAdapter>, List<DsTableAdapter>> e : pendingByClass.entrySet()) {
                List values = e.getValue();
                if (values.isEmpty()) {
                    continue;
                }
                putEntities(values, false);
            }
            pendingByClass.clear();
            pendingCount = 0;
        }
    }

    /**
     * 并行批量聚合回放处理器。
     *
     * <p>
     * 实现为单线程读取 binlog 帧并按 rowKey hash 分发到多个队列，每个队列由独立工作线程顺序消费。 同一 rowKey
     * 的事件始终进入同一队列，因此行级顺序得到保证；不同 rowKey 之间天然独立，可并行应用。</p>
     */
    private final class ParallelBatchReplayHandler implements com.q3lives.ds.binlog.DsBinlogHandler {

        private final int batchSize;
        private final int partitions;
        @SuppressWarnings("unchecked")
        private final BlockingQueue<ReplayTask>[] queues;
        private final ExecutorService executor;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final CountDownLatch doneLatch;
        private int processed = 0;

        @SuppressWarnings("unchecked")
        ParallelBatchReplayHandler(int batchSize, int partitions) {
            if (partitions > Runtime.getRuntime().availableProcessors() * 4) {
                throw new IllegalArgumentException("parallelPartitions too large: " + partitions);
            }
            this.batchSize = batchSize;
            this.partitions = partitions;
            this.queues = new BlockingQueue[partitions];
            this.doneLatch = new CountDownLatch(partitions);
            ThreadFactory factory = r -> {
                Thread t = new Thread(r, "binlog-replay-worker");
                t.setDaemon(true);
                return t;
            };
            this.executor = Executors.newFixedThreadPool(partitions, factory);
            for (int i = 0; i < partitions; i++) {
                queues[i] = new ArrayBlockingQueue<>(Math.max(batchSize * 4, 64));
                final int idx = i;
                executor.submit(() -> workerLoop(idx));
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean apply(long frameOffset, DsBinlogEntry entry) throws IOException {
            if (entry.isUndo()) {
                // UNDO 帧默认不参与正向回放。
                return true;
            }
            if (stopped.get()) {
                return false;
            }
            Class<? extends DsTableAdapter> clazz = tableIdToClass.get(entry.tableId());
            if (clazz == null) {
                return true;
            }
            TableMeta<?> meta = metaOf(clazz);
            long rowId = DsFixedBucketStore.encodeRowId(entry.rowKey() & 0xFFFFFFFFL, meta.rowLength);
            DsBinlogOpType op = entry.opType();
            DsTableAdapter entity = null;
            if (op == DsBinlogOpType.INSERT || op == DsBinlogOpType.UPDATE) {
                entity = decodeEntityFromBinlog(meta, entry, rowId);
            }
            int partition = Math.floorMod(entry.rowKey(), partitions);
            ReplayTask task = new ReplayTask(clazz, meta, rowId, entity, op);
            BlockingQueue<ReplayTask> q = queues[partition];
            try {
                while (!q.offer(task, 100, TimeUnit.MILLISECONDS)) {
                    if (stopped.get() || failure.get() != null) {
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            processed++;
            return true;
        }

        void shutdownAndAwait() throws IOException {
            Throwable first = failure.get();
            try {
                for (int i = 0; i < partitions; i++) {
                    try {
                        queues[i].put(ReplayTask.POISON);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                executor.shutdown();
            }
            try {
                if (!doneLatch.await(5, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
            Throwable err = failure.get();
            if (err != null) {
                if (err instanceof IOException) {
                    throw (IOException) err;
                }
                if (err instanceof RuntimeException) {
                    throw (RuntimeException) err;
                }
                throw new RuntimeException(err);
            }
            if (first != null) {
                // apply 过程中已经失败但未能阻止 reader，重新抛出
                if (first instanceof IOException) {
                    throw (IOException) first;
                }
                if (first instanceof RuntimeException) {
                    throw (RuntimeException) first;
                }
                throw new RuntimeException(first);
            }
        }

        private void workerLoop(int idx) {
            try {
                Map<Class<? extends DsTableAdapter>, List<DsTableAdapter>> pending = new HashMap<>();
                int pendingCount = 0;
                BlockingQueue<ReplayTask> q = queues[idx];
                while (true) {
                    ReplayTask task;
                    try {
                        task = q.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        if (task.op == null) {
                            if (pendingCount > 0) {
                                flushWorker(pending);
                            }
                            return;
                        }
                        if (stopped.get() || failure.get() != null) {
                            continue;
                        }
                        if (task.op == DsBinlogOpType.DELETE) {
                            if (pendingCount > 0) {
                                flushWorker(pending);
                                pendingCount = 0;
                            }
                            applyDelete(task.clazz, task.rowId);
                        } else if (task.op == DsBinlogOpType.INSERT || task.op == DsBinlogOpType.UPDATE) {
                            pending.computeIfAbsent(task.clazz, k -> new ArrayList<>()).add(task.entity);
                            pendingCount++;
                            if (pendingCount >= batchSize) {
                                flushWorker(pending);
                                pendingCount = 0;
                            }
                        }
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                        stopped.set(true);
                    }
                }
            } finally {
                doneLatch.countDown();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void flushWorker(Map<Class<? extends DsTableAdapter>, List<DsTableAdapter>> pending) throws IOException {
            if (pending.isEmpty()) {
                return;
            }
            synchronized (replayApplyLock) {
                DsBinlogContext.setInReplay(true);
                try {
                    for (List<DsTableAdapter> list : pending.values()) {
                        if (list.isEmpty()) {
                            continue;
                        }
                        putEntities((List) list, false);
                    }
                } finally {
                    DsBinlogContext.clear();
                }
            }
            pending.clear();
        }

        private void applyDelete(Class<? extends DsTableAdapter> clazz, long rowId) throws IOException {
            synchronized (replayApplyLock) {
                DsBinlogContext.setInReplay(true);
                try {
                    removeTable(clazz, rowId, false);
                } finally {
                    DsBinlogContext.clear();
                }
            }
        }

    }

    /**
     * 并行回放的任务单元。
     */
    private static final class ReplayTask {

        static final ReplayTask POISON = new ReplayTask(null, null, 0L, null, null);

        final Class<? extends DsTableAdapter> clazz;
        final TableMeta<?> meta;
        final long rowId;
        final DsTableAdapter entity;
        final DsBinlogOpType op;

        ReplayTask(Class<? extends DsTableAdapter> clazz, TableMeta<?> meta, long rowId,
                DsTableAdapter entity, DsBinlogOpType op) {
            this.clazz = clazz;
            this.meta = meta;
            this.rowId = rowId;
            this.entity = entity;
            this.op = op;
        }
    }

    private static Object binlogValueToFieldValue(Object value, Class<?> fieldType) {
        if (value == null) {
            return null;
        }
        if (fieldType == String.class) {
            if (value instanceof byte[]) {
                return new String((byte[]) value, StandardCharsets.UTF_8);
            }
            return value.toString();
        }
        if (fieldType == boolean.class || fieldType == Boolean.class) {
            if (value instanceof Number) {
                return ((Number) value).intValue() != 0;
            }
            return Boolean.TRUE.equals(value);
        }
        if (fieldType == byte.class || fieldType == Byte.class) {
            if (value instanceof Number) {
                return ((Number) value).byteValue();
            }
        }
        if (fieldType == short.class || fieldType == Short.class) {
            if (value instanceof Number) {
                return ((Number) value).shortValue();
            }
        }
        if (fieldType == int.class || fieldType == Integer.class) {
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        if (fieldType == long.class || fieldType == Long.class) {
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }
        if (fieldType == float.class || fieldType == Float.class) {
            if (value instanceof Number) {
                return ((Number) value).floatValue();
            }
        }
        if (fieldType == double.class || fieldType == Double.class) {
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        if (fieldType == char.class || fieldType == Character.class) {
            if (value instanceof Number) {
                return (char) ((Number) value).intValue();
            }
        }
        if (fieldType == Date.class) {
            if (value instanceof Number) {
                long ts = ((Number) value).longValue();
                return ts == 0L ? null : new Date(ts);
            }
            if (value instanceof Date) {
                return value;
            }
        }
        if (fieldType == BigDecimal.class) {
            if (value instanceof BigDecimal) {
                return value;
            }
            if (value instanceof Number) {
                return BigDecimal.valueOf(((Number) value).longValue());
            }
        }
        return value;
    }

    private static Object fieldValueToBinlog(Object val, Class<?> type) {
        if (val == null) {
            return null;
        }
        if (type == String.class) {
            return ((String) val).getBytes(StandardCharsets.UTF_8);
        }
        if (type == boolean.class || type == Boolean.class) {
            return (short) ((Boolean) val ? 1 : 0);
        }
        if (type == byte.class || type == Byte.class) {
            return ((Byte) val).shortValue();
        }
        if (type == Date.class) {
            return ((Date) val).getTime();
        }
        return val;
    }

    private <T extends DsTableAdapter> boolean rowExists(TableMeta<T> meta, long rowId) {
        if (meta.hasMvcc) {
            try {
                DsHashMap headMap = versionHeadMap(meta);
                Long headId = headMap.get(rowId);
                return headId != null && headId != 0L;
            } catch (Exception ignore) {
                return false;
            }
        }
        try {
            byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
            return oldBytes != null && !isAllZero(oldBytes);
        } catch (Exception ignore) {
            return false;
        }
    }

    private DsEqIndexStore resolveEqIndex(TableMeta<?> meta, IndexedColumn col) throws IOException {
        String cacheKey = meta.space + "#" + meta.schemaId + "#" + col.columnName;
        DsEqIndexStore idx = indexCache.get(cacheKey);
        if (idx != null) {
            return idx;
        }

        String safeSpace = DsPathUtil.toSafeFileName(meta.space, 80);
        String safeCol = DsPathUtil.toSafeFileName(col.columnName, 64);
        String space = "indexes/" + safeSpace + "/" + meta.schemaId;
        String indexName = "eq_" + safeCol;

        // ---- Registry 优先读（HYBRID/REGISTRY）：若存在 dirAbsolutePath 则作为 space override，一致性校验 ----
        String[] override = tryRowIndexFromRegistry(meta.space, col.columnName);
        String finalSpace = (override != null && override[0] != null) ? override[0] : space;
        String finalIndexName = (override != null && override[1] != null) ? override[1] : indexName;

        DsEqIndexStore created = new DsEqIndexStore(this.root, finalSpace, finalIndexName, col.valueKind);
        DsEqIndexStore prev = indexCache.putIfAbsent(cacheKey, created);
        if (prev != null) {
            created.close();
            return prev;
        }
        // ---- Registry 登记（HYBRID/REGISTRY，幂等）----
        registerRowIndexMeta(meta.space, col.columnName, "eq", meta.schemaId, false, col.valueKind.name(),
                new File(this.root, finalSpace).getAbsolutePath());
        return created;
    }

    private DsEqIndexStore resolveCompositeIndex(TableMeta<?> meta, CompositeIndex ci) throws IOException {
        String cacheKey = meta.space + "#" + meta.schemaId + "#cidx_" + ci.name;
        DsEqIndexStore idx = indexCache.get(cacheKey);
        if (idx != null) {
            return idx;
        }

        String safeSpace = DsPathUtil.toSafeFileName(meta.space, 80);
        String safeName = DsPathUtil.toSafeFileName(ci.name, 64);
        String space = "indexes/" + safeSpace + "/" + meta.schemaId;
        String indexName = "cidx_" + safeName;

        String[] override = tryRowIndexFromRegistry(meta.space, "__cidx_" + ci.name);
        String finalSpace = (override != null && override[0] != null) ? override[0] : space;
        String finalIndexName = (override != null && override[1] != null) ? override[1] : indexName;

        DsEqIndexStore created = new DsEqIndexStore(this.root, finalSpace, finalIndexName, DsEqIndexStore.IndexedValueKind.LONG);
        DsEqIndexStore prev = indexCache.putIfAbsent(cacheKey, created);
        if (prev != null) {
            created.close();
            return prev;
        }
        registerRowIndexMeta(meta.space, "__cidx_" + ci.name, "composite", meta.schemaId, ci.mvcc, "LONG",
                new File(this.root, finalSpace).getAbsolutePath());
        return created;
    }

    // ---------- P10 T3: 行存 Index 配置 Registry 双向 ----------
    /**
     * 从 Registry 读行存 Index 配置；返回 [space(目录相对路径), indexName] 或 null。
     * dirAbsolutePath 不作为强制 override（因为数据在本地文件，相对路径更稳）， 仅当 schemaId 与当前
     * meta.schemaId 完全一致时才用 Registry 的 indexName。
     */
    private String[] tryRowIndexFromRegistry(String entityClassName, String logicalKey) {
        if (!MetaMode.isGlobalRegistryOrHybrid()) {
            return null;
        }
        try {
            DsRegistry reg = DsRegistryInstance.get(this.root);
            String kindPath = RegistryMetaPaths.rowIndexKind(entityClassName, logicalKey);
            RegistryEntry kindE = reg.get(kindPath);
            if (kindE == null || !kindE.isPresent()) {
                return null;
            }

            String schemaPath = RegistryMetaPaths.rowIndexSchemaId(entityClassName, logicalKey);
            RegistryEntry schemaE = reg.get(schemaPath);
            if (schemaE == null || !schemaE.isPresent()) {
                return null;
            }
            String storedSchemaId = schemaE.asString();

            String dirPath = RegistryMetaPaths.rowIndexDirAbsPath(entityClassName, logicalKey);
            RegistryEntry dirE = reg.get(dirPath);
            String dirAbs = (dirE != null && dirE.isPresent()) ? dirE.asString() : null;

            // indexName 不依赖 Registry（幂等重建），仅校验 schemaId 匹配
            if (storedSchemaId != null) {
                // 推导相对 space = dirAbs → 相对 root；若 dirAbs 缺失则返回 null（fallback 到原逻辑）
                if (dirAbs == null) {
                    return null;
                }
                File rootAbs = this.root.getAbsoluteFile();
                String rootPath = rootAbs.getPath();
                if (!dirAbs.startsWith(rootPath)) {
                    return null;
                }
                String rel = dirAbs.substring(rootPath.length());
                while (rel.startsWith(File.separator)) {
                    rel = rel.substring(1);
                }
                String space = rel.isEmpty() ? null : rel.replace('\\', '/');
                // indexName：保持原命名（eq_<safeCol> / cidx_<safeName>），不覆盖
                String logicalIndexName;
                if (logicalKey.startsWith("__cidx_")) {
                    String ciName = logicalKey.substring("__cidx_".length());
                    logicalIndexName = "cidx_" + DsPathUtil.toSafeFileName(ciName, 64);
                } else {
                    logicalIndexName = "eq_" + DsPathUtil.toSafeFileName(logicalKey, 64);
                }
                return new String[]{space, logicalIndexName};
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 把行存 Index 5 个 leaf 写入 Registry（幂等：kind 已存在则跳过）。
     */
    private void registerRowIndexMeta(String entityClassName, String logicalKey, String kind,
            String schemaId, boolean mvcc, String valueKind,
            String dirAbsolutePath) {
        if (!MetaMode.isGlobalRegistryOrHybrid()) {
            return;
        }
        try {
            DsRegistry reg = DsRegistryInstance.get(this.root);
            String kindPath = RegistryMetaPaths.rowIndexKind(entityClassName, logicalKey);
            RegistryEntry existing = reg.get(kindPath);
            if (existing != null && existing.isPresent()) {
                return; // 幂等不覆盖
            }
            reg.mkdirs(RegistryMetaPaths.rowIndexDir(entityClassName, logicalKey));
            reg.putString(kindPath, kind, DataType.REG_SZ);
            reg.putString(RegistryMetaPaths.rowIndexSchemaId(entityClassName, logicalKey), schemaId, DataType.REG_SZ);
            reg.putLong(RegistryMetaPaths.rowIndexMvcc(entityClassName, logicalKey),
                    DataType.REG_DWORD, mvcc ? 1L : 0L);
            reg.putString(RegistryMetaPaths.rowIndexValueKind(entityClassName, logicalKey), valueKind, DataType.REG_SZ);
            reg.putString(RegistryMetaPaths.rowIndexDirAbsPath(entityClassName, logicalKey), dirAbsolutePath, DataType.REG_SZ);
        } catch (Exception ex) {
            System.err.println("[DsDatabaseLocal] WARN 登记 RowIndex 到 Registry 失败: "
                    + entityClassName + "/" + logicalKey + " -> " + ex.getMessage());
        }
    }

    private static long toIndexLongValue(IndexedColumn col, Object value) {
        if (value == null) {
            return 0L;
        }
        Class<?> t = col.fieldType;
        if (t == long.class || t == Long.class) {
            return (Long) value;
        }
        if (t == int.class || t == Integer.class) {
            return ((Integer) value).longValue();
        }
        if (t == short.class || t == Short.class) {
            return ((Short) value).longValue();
        }
        if (t == byte.class || t == Byte.class) {
            return ((Byte) value).longValue();
        }
        if (t == boolean.class || t == Boolean.class) {
            return ((Boolean) value) ? 1L : 0L;
        }
        if (t == Date.class) {
            return ((Date) value).getTime();
        }
        return 0L;
    }

    private static String toIndexStringValue(IndexedColumn col, Object value) {
        if (value == null) {
            return "";
        }
        return value.toString();
    }

    /**
     * 将复合索引涉及列的值组合成一个字符串，然后 FNV-1a 哈希为 long 作为索引键。 NULL 值统一编码为 "\0"，保证可区分且稳定。
     */
    private static long compositeKeyValue(CompositeIndex ci, Object owner) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < ci.columns.size(); i++) {
            if (i > 0) {
                sb.append('\u0001');
            }
            IndexedColumn col = ci.columns.get(i);
            Object val = readFieldValue(col.field, owner);
            if (val == null) {
                sb.append('\u0000');
            } else if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
                sb.append(toIndexLongValue(col, val));
            } else {
                sb.append(val.toString());
            }
        }
        return fnv1a64(sb.toString());
    }

    private static long fnv1a64(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * 与 DsEqIndexStore.hashString 保持一致的 FNV-1a 哈希，用于批量索引聚合。
     */
    private static long hashStringForIndex(String s) {
        if (s == null || s.isEmpty()) {
            return 0L;
        }
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h == 0L ? 1L : h;
    }

    private static Object readFieldValue(Field f, Object owner) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeFieldValue(Field f, Object owner, Object value) {
        try {
            f.setAccessible(true);
            f.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 删除行在索引中的条目。MVCC 列使用 versionId，非 MVCC 列使用 rowId。
     */
    private <T extends DsTableAdapter> void removeRowFromIndexes(TableMeta<T> meta, T row, long rowId, long versionId) throws IOException {
        if (row == null) {
            return;
        }
        if (rowId == 0L && versionId == 0L) {
            return;
        }
        if (!meta.hasMvcc) {
            removeIdFromClassIdSet(meta.clazz, rowId);
        }
        for (IndexedColumn col : meta.indexedColumns) {
            Object val = readFieldValue(col.field, row);
            DsEqIndexStore idx = resolveEqIndex(meta, col);
            long id = col.mvcc ? versionId : rowId;
            if (id == 0L) {
                continue;
            }
            switch (col.valueKind) {
                case LONG:
                    idx.removeIndex(toIndexLongValue(col, val), id);
                    break;
                case STRING:
                    idx.removeIndex(toIndexStringValue(col, val), id);
                    break;
            }
        }
        for (CompositeIndex ci : meta.compositeIndexes) {
            DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
            long id = ci.mvcc ? versionId : rowId;
            if (id == 0L) {
                continue;
            }
            idx.removeIndex(compositeKeyValue(ci, row), id);
        }
    }

    /**
     * 仅删除非 MVCC 列的索引条目（用于 MVCC 表的更新/删除：非 MVCC 列始终只保留最新值）。
     */
    private <T extends DsTableAdapter> void removeNonMvccRowFromIndexes(TableMeta<T> meta, T row, long rowId) throws IOException {
        if (row == null) {
            return;
        }
        if (rowId == 0L) {
            return;
        }
        for (IndexedColumn col : meta.indexedColumns) {
            if (col.mvcc) {
                continue;
            }
            Object val = readFieldValue(col.field, row);
            DsEqIndexStore idx = resolveEqIndex(meta, col);
            switch (col.valueKind) {
                case LONG:
                    idx.removeIndex(toIndexLongValue(col, val), rowId);
                    break;
                case STRING:
                    idx.removeIndex(toIndexStringValue(col, val), rowId);
                    break;
            }
        }
        for (CompositeIndex ci : meta.compositeIndexes) {
            if (ci.mvcc) {
                continue;
            }
            DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
            idx.removeIndex(compositeKeyValue(ci, row), rowId);
        }
    }

    /**
     * 写入行索引。MVCC 列使用 versionId，非 MVCC 列使用 rowId。
     */
    private DsHashSet idSetForClass(Class<? extends DsTableAdapter> clazz) {
        return classIdSetCache.computeIfAbsent(clazz, k -> {
            EntityIndexUtil.IndexDef idef = EntityIndexUtil.indexOf(root, k);
            return new DsHashSet(idef.idsFile);
        });
    }

    private <T extends DsTableAdapter> void addIdToClassIdSet(Class<T> clazz, long rowId) {
        // Use boxed Long overload: internally translates to IOException→RuntimeException.
        idSetForClass(clazz).add(Long.valueOf(rowId));
    }

    private <T extends DsTableAdapter> void removeIdFromClassIdSet(Class<T> clazz, long rowId) {
        // Use boxed Long overload: internally translates to IOException→RuntimeException.
        idSetForClass(clazz).remove(Long.valueOf(rowId));
    }

    private <T extends DsTableAdapter> void putRowIntoIndexes(TableMeta<T> meta, T row, long rowId, long versionId) throws IOException {
        if (row == null) {
            return;
        }
        if (rowId == 0L && versionId == 0L) {
            return;
        }
        if (!meta.hasMvcc) {
            addIdToClassIdSet(meta.clazz, rowId);
        }
        for (IndexedColumn col : meta.indexedColumns) {
            Object val = readFieldValue(col.field, row);
            DsEqIndexStore idx = resolveEqIndex(meta, col);
            long id = col.mvcc ? versionId : rowId;
            if (id == 0L) {
                continue;
            }
            switch (col.valueKind) {
                case LONG:
                    idx.putIndex(toIndexLongValue(col, val), id);
                    break;
                case STRING:
                    idx.putIndex(toIndexStringValue(col, val), id);
                    break;
            }
        }
        for (CompositeIndex ci : meta.compositeIndexes) {
            DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
            long id = ci.mvcc ? versionId : rowId;
            if (id == 0L) {
                continue;
            }
            idx.putIndex(compositeKeyValue(ci, row), id);
        }
    }

    private TableMeta<? extends DsTableAdapter> createMeta(Class<? extends DsTableAdapter> clazz) {
        String space = clazz.getName();
        DsTable tableAnnotation = clazz.getAnnotation(DsTable.class);
        boolean tableMvcc = tableAnnotation != null && tableAnnotation.mvcc();
        DsBinlogLogLevel binlogLogLevel = tableAnnotation != null ? tableAnnotation.binlogLogLevel() : DsBinlogLogLevel.REDO;
        EntityIndexUtil.IndexDef index = EntityIndexUtil.indexOf(root, clazz);
        int sample = sampleRowLength(clazz);
        EntitySchemaUtil.SchemaDef curSchema = EntitySchemaUtil.schemaOf(clazz);
        List<EntitySchemaUtil.ColumnDef> columns = new ArrayList<>(curSchema.getColumns());
        Map<String, Integer> colIdMap = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            colIdMap.put(columns.get(i).name, i);
        }
        Set<String> mvccColumnNames = new LinkedHashSet<>();
        List<IndexedColumn> indexedColumns = new ArrayList<>();
        Map<String, IndexedColumn> colByName = new LinkedHashMap<>();
        for (EntitySchemaUtil.ColumnDef col : curSchema.getColumns()) {
            Field f = col.declaredField;
            f.setAccessible(true);
            DsEqIndexStore.IndexedValueKind vk = resolveValueKind(f.getType());
            boolean colMvcc = tableMvcc || col.mvcc;
            if (colMvcc) {
                mvccColumnNames.add(col.name);
            }
            IndexedColumn ic = new IndexedColumn(col.name, f, f.getType(), vk, colMvcc);
            colByName.put(col.name, ic);
            if (col.indexed) {
                indexedColumns.add(ic);
            }
        }
        List<CompositeIndex> compositeIndexes = new ArrayList<>();
        for (EntitySchemaUtil.CompositeIndexDef ci : curSchema.getCompositeIndexes()) {
            List<IndexedColumn> cols = new ArrayList<>(ci.columns.size());
            boolean ciMvcc = false;
            for (EntitySchemaUtil.ColumnDef c : ci.columns) {
                IndexedColumn ic = colByName.get(c.name);
                if (ic == null) {
                    throw new IllegalStateException("Composite index " + ci.name + " references unknown column: " + c.name);
                }
                if (ic.mvcc) {
                    ciMvcc = true;
                }
                cols.add(ic);
            }
            compositeIndexes.add(new CompositeIndex(ci.name, cols, ciMvcc));
        }
        boolean hasMvcc = tableMvcc || !mvccColumnNames.isEmpty();
        int finalRowLength;
        int legacyRowLength;
        if (sample != index.rowLength) {
            legacyRowLength = index.rowLength;
            finalRowLength = sample;
            System.err.println("[DsDatabaseLocal] WARN Schema row length mismatch (Online DDL compat ON): "
                    + "class=" + clazz.getName()
                    + ", storedLegacyRowLength=" + index.rowLength
                    + ", currentSchemaRowLength=" + sample
                    + ". Old row data auto-fallback @DsField.defaultValue on read; new writes use new length.");
        } else {
            legacyRowLength = -1;
            finalRowLength = sample;
        }
        int tableId = stableTableId(clazz);
        Class<? extends DsTableAdapter> prev = tableIdToClass.put(tableId, clazz);
        if (prev != null && prev != clazz) {
            System.err.println("[DsDatabaseLocal] WARN tableId collision: " + tableId + " -> " + prev.getName() + " vs " + clazz.getName());
        }
        return new TableMeta<>(clazz, space, index.rowType, finalRowLength, legacyRowLength, curSchema.schemaId,
                tableId, columns, colIdMap, indexedColumns, compositeIndexes, hasMvcc, mvccColumnNames, binlogLogLevel);
    }

    private static int stableTableId(Class<? extends DsTableAdapter> clazz) {
        // 当前用类名稳定哈希作为 tableId；生产环境应维护全局 tableId 映射表避免冲突
        long h = 0xcbf29ce484222325L;
        byte[] bytes = clazz.getName().getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            h ^= b & 0xFF;
            h *= 0x100000001b3L;
        }
        return (int) (h ^ (h >>> 32));
    }

    private static int sampleRowLength(Class<? extends DsTableAdapter> clazz) {
        DsTableAdapter sample = newInstance(clazz);
        ByteBuffer buf = sample.toBytes();
        return buf.remaining();
    }

    private static <T extends DsTableAdapter> T newInstance(Class<T> clazz) {
        try {
            Constructor<T> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("实体必须包含无参构造: " + clazz.getName(), e);
        }
    }

    public <T extends DsTableAdapter> long putEntity(T value, boolean assignIdIfMissing) throws IOException {
        // 默认 MVCC 2PL Strict（热点单行分配器 District/Warehouse 正确，避免 PCC 写完 unlock 后其他 tx overwrite 导致 applyOpCas mismatch）；
        // 多行 Stock/Customer 等可显式 putEntity(v,false,false) 短锁，牺牲小概率 mismatch 换解死锁。
        return putEntityInternal(value, assignIdIfMissing, true);
    }

    /**
     * MVCC 2PL 可选：热点单 id 分配器（District.next_o_id 等不需要整 tx 持锁场景，strict2PL=false
     * 时mvccLock在lock内 CAS 成功后立即 unlock， 不登记 heldRowLocks（2PL
     * Strict），吞吐×3~5×，CAS mismatch 由外层 RETRIES 兜底。
     */
    public <T extends DsTableAdapter> long putEntity(T value, boolean assignIdIfMissing, boolean strict2PL) throws IOException {
        return putEntityInternal(value, assignIdIfMissing, strict2PL);
    }

    private <T extends DsTableAdapter> long putEntityInternal(T value, boolean assignIdIfMissing, boolean strict2PL) throws IOException {
        Objects.requireNonNull(value, "value cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(value.getClass());

        Long id = value.getId();
        if (assignIdIfMissing && (id == null || id == 0L)) {
            long newId = bucketStore.getNewId(meta.space, meta.type, meta.rowLength);
            value.setId(newId);
            id = newId;
        }
        if (id == null || id == 0L) {
            throw new IllegalArgumentException("entity id is missing");
        }
        long rowId = id;

        ByteBuffer buf = value.toBytes();
        if (buf.remaining() != meta.rowLength) {
            System.err.println("[DsDatabaseLocal] WARN putEntity row length mismatch (compat): expected=" + meta.rowLength + ", actual=" + buf.remaining() + ", class=" + value.getClass().getName());
        }
        byte[] bytes;
        if (buf.remaining() == meta.rowLength) {
            bytes = buf.array();
        } else {
            bytes = new byte[meta.rowLength];
            buf.rewind();
            int copyN = Math.min(buf.remaining(), meta.rowLength);
            buf.get(bytes, 0, copyN);
        }

        // ----- OQ3 2PC hook：事务内 putEntity 必须先登记 TxOp，再写持久化存储（commit 时 CAS 二次校验 oldBytes；rollback 时还原 oldBytes） -----
        TxContext tx = (txManager == null) ? null : txManager.currentOrNull();
        byte[] txOldBytes = null;
        final String registryPathForTx = (rowDataBackend == null) ? null : RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
        // 非 MVCC 表：先读旧 bytes 并登记（MVCC 表推迟到 mvccLock sync 内读最新值，防止并发 District 行 expectedPrev 读旧值造成 CAS mismatch 90% abort）
        if (tx != null && registryPathForTx != null && !meta.hasMvcc) {
            txOldBytes = rowDataBackend.readRowPayload(meta.clazz.getName(), rowId, () -> null);
            if (txOldBytes == null) {
                txOldBytes = new byte[0];
            }
            TxOpRegistry.registerRowPut(tx, meta.clazz.getName(), rowId, registryPathForTx, txOldBytes, bytes);
        }

        if (!meta.hasMvcc) {
            // 非 MVCC 表：保持原地更新语义，索引指向 rowId
            T oldRow = null;
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                if (oldBytes != null && !isAllZero(oldBytes)) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(rowId);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
            // binlog-first：先写 binlog 并 fsync，保证变更日志落盘后再改 data/index
            if (tx == null) {
                appendBinlog(meta, value, oldRow, oldRow == null ? DsBinlogOpType.INSERT : DsBinlogOpType.UPDATE);
                flushBinlog();
            }
            removeRowFromIndexes(meta, oldRow, rowId, rowId);
            bucketStore.update(meta.space, meta.type, rowId, bytes, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
            // OQ3 tx：Registry 叶子写 + replicator 双写延迟到 txManager.commit.applyOpCas 执行
            // （CAS 校验 expectedPrevBytes 与当时 Backend 实际 bytes 一致 → 原子写 newPayload）
            // 此处 tx==null 场景直接写；tx!=null 场景 applyOpCas 会写 Backend，与 bucket 字节一致
            if (tx == null && rowDataBackend != null) {
                rowDataBackend.writeRowPayload(meta.clazz.getName(), rowId, bytes);
            }
            if (tx == null && registryReplicator != null) {
                long v = writeTimestamp.incrementAndGet();
                String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
                registryReplicator.replicateKey(p, bytes, v, new long[]{0L});
            }
            putRowIntoIndexes(meta, value, rowId, rowId);
            if (tx == null) {
                commitGroup();
            }
            return rowId;
        }

        // MVCC 路径
        // ts = tx!=null ? tx.writeTs（本事务内所有自写 VR.beginTs 固定 = tx.writeTs，使自读 W-F-R 恒成立；外部 tx 按 readTs 过滤）
        //      tx==null ? nextWriteTimestamp()（非事务单次写，分配新 ts，兼容老 P10~P13 baseline）
        long ts = (tx != null) ? tx.writeTs : nextWriteTimestamp();
        DsHashMap headMap = versionHeadMap(meta);
        Long headId = headMap.get(rowId);
        T oldRow = null;
        long prevVersionId = 0L;
        long prevOrigEndTs = Long.MAX_VALUE; // prev 版本在未被覆盖前的 endTs，abort 时还原
        int vrl = versionRecordLength(meta);

        // OQ3 MVP：事务内 MVCC 物理写（versionRecord / headMap / index / commitGroup）加 per-(meta,rowId) 分片锁串行化，
        // 防止并发写同 rowId / bucket slot 互相覆盖造成 lost update（next_o_id / stock 增量少算）。
        // tx==null 无事务路径不加锁（保持老 P10/P11 路径性能）。分片 4096，TPCC 100W 行规模 冲突率可忽略。
        // 2PL Strict：tx 内首次拿到 ReentrantLock 后登记到 TxContext.heldRowLocks，putEntity 正常返回时不 unlock，
        // 统一到 TransactionHandle.commit/rollback 的 finally 中一次性释放，保证同事务同 row 多次 putEntity 之间锁不释放。
        final ReentrantLock mvccLock = (tx == null) ? null : mvccRowLockFor(meta, rowId);
        if (mvccLock != null) {
            final boolean heldByUsAlready = mvccLock.isHeldByCurrentThread();
            final int shardKey = heldByUsAlready ? -1 : mvccRowLockShardKey(meta, rowId);
            long newVersionId = 0L;
            long dataId = 0L;
            boolean locked = false;
            try {
                if (heldByUsAlready) {
                    mvccLock.lock(); // 已持有 → 可重入，无等待
                    locked = true;
                } else {
                    locked = mvccLock.tryLock(3L, java.util.concurrent.TimeUnit.SECONDS);
                    if (!locked) {
                        throw new RuntimeException("2PL-tryLock-timeout: meta=" + meta.clazz.getSimpleName() + " rowId=" + rowId
                                + " tid=" + (tx == null ? "none" : String.valueOf(tx.txId)));
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("2PL-tryLock-interrupted: meta=" + meta.clazz.getSimpleName() + " rowId=" + rowId
                        + " tid=" + (tx == null ? "none" : String.valueOf(tx.txId)));
            }
            try {
                if (strict2PL && !heldByUsAlready && tx instanceof TxContext) {
                    // 2PL Strict 仅在热点写冲突低场景（LifeCycle/TPCC 小并发 tx）登记持锁到 commit；
                    // 高并发 10+ tx（TPC-C 10 并发 Stock）strict2PL 由调用方传 false，
                    // 避免 10 tx 各持 10+ 锁互阻全局死锁。
                    txManager.registerHeldRowLock((TxContext) tx, shardKey, mvccLock);
                }
                // ====== MVCC RR + 2PL Write 冲突合并（字段级 last-writer-wins by intent）======
                // 问题：T1 读 d.next_o_id=3000 → T2 读 d.next_o_id=3000 并写 3001 提交 → T1 写 3001 提交 → 丢失 T2 增量 (t04 delta<success)
                // 次问题（双加 delta）：同 tx 同 row 两次 putEntity（District: first next_o_id=oId+1，later d_ytd+=amt 再 putEntity），
                //   若第二次仍然基于 snap→intent diff，next_o_id +1 会再次被误累计（最新 latest + Δ 变成 latest+1 再+1 = +2 错）。
                // 修复：
                // (1) snapEntity 始终基于 selfWriteTs=0（NOT ctx.writeTs）→ 基线始终是 tx 真正读时刻的原始快照，不包含本 tx 后写
                // (2) latestEntity 基于 selfWriteTs=ctx.writeTs → 锁内最新含其他 tx 提交 + 本 tx 自写（第 2 次 call 时 latest 已是本 tx 前次 merge 输出）
                // (3) patchLatestEntityWithIntentFields 内基于 TxContext.mergedFieldsByShardKey(shardKey, colName) 跟踪每列已 apply 过的 delta：
                //     - 列首次 merge：计算 Δ = intent - snap，写 latest + Δ（或 last-writer-wins overwrite for 非数值），registerFieldMerged 标记已做
                //     - 后续 putEntity 调用再遇到同列：isFieldAlreadyMerged=true → 跳过，不双加
                //     - 后续调用遇到 NEW 列（用户稍后写的如 d_ytd）：首次，正常 apply Δ 并标记
                if (tx instanceof TxContext) {
                    TxContext ctx = (TxContext) tx;
                    long snapTs = ctx.readTs;
                    long latestSelfWriteTs = ctx.writeTs;
                    T snapEntity = null;
                    try {
                        // snapEntity 基线：strictCommitCheck=true（严格快照，只有 committed+自写 可见）；
                        // selfWriteTs=0（不含本 tx 自写），保证任何两次 putEntity 间 Δ = intent - snap_baseline 完全一致（不漂移）
                        snapEntity = resolveVisibleVersionNoLock(meta, rowId, snapTs, 0L, true, ctx);
                    } catch (Exception ignoreS) {
                        snapEntity = null;
                    }
                    T latestEntity = null;
                    try {
                        // latestEntity：写合并基线，strictCommitCheck=false（允许其他 tx 的未提交但已挂 head 的 in-flight 写可见）
                        //   → 否则如果 TxA 未 commit，但已经挂 head，TxB 拿 latestEntity 时会跳过 TxA 的值，直接拿 legacy，
                        //     merge 后 TxB 会覆盖 TxA 的 in-flight 值（典型 lost update t04 delta≪success）。
                        // selfWriteTs=ctx.writeTs：保证能拿到本 tx 前次 putEntity 自写的最新值
                        latestEntity = resolveVisibleVersionNoLock(meta, rowId, Long.MAX_VALUE, latestSelfWriteTs, false, ctx);
                    } catch (Exception ignoreL) {
                        latestEntity = null;
                    }
                    if (latestEntity != null) {
                        // 幂等去重必须用真正的 (meta,rowId) 分片 key，不能用 -1：
                        // heldByUsAlready 时前面的 shardKey 临时=-1 只是避免重复登记 heldRowLocks，
                        // mergedFieldsByShardKey 需要稳定的 shardKey 才能跨两次 putEntity 找到已登记列，否则 next_o_id 等数值列会双加。
                        final int mergeShardKey = mvccRowLockShardKey(meta, rowId);
                        T patched = patchLatestEntityWithIntentFields(snapEntity, value, latestEntity, meta, ctx, mergeShardKey);
                        if (patched != null) {
                            ByteBuffer pb = patched.toBytes();
                            if (pb.remaining() == meta.rowLength) {
                                bytes = pb.array();
                            } else {
                                byte[] nb = new byte[meta.rowLength];
                                pb.rewind();
                                int cn = Math.min(pb.remaining(), meta.rowLength);
                                pb.get(nb, 0, cn);
                                bytes = nb;
                            }
                        }
                    }
                }
                // ====== 原 block1：reacquire headId / legacy bootstrap / TxOp 预写 ======（现直接在 lock 保护内，无需 sync 嵌套）
                headId = headMap.get(rowId);
                prevVersionId = 0L;
                prevOrigEndTs = Long.MAX_VALUE;
                oldRow = null;
                if (headId != null && headId != 0L) {
                    VersionRecord oldHead = readVersionRecord(meta, headId);
                    if (oldHead != null) {
                        prevVersionId = headId;
                        prevOrigEndTs = oldHead.endTs;
                        updateVersionRecordEndTs(meta, headId, ts);
                        if (oldHead.dataId != 0L) {
                            oldRow = readEntityByDataId(meta, oldHead.dataId);
                            if (oldRow != null) {
                                oldRow.setId(rowId);
                            }
                        }
                    }
                } else {
                    try {
                        byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                        if (oldBytes != null && !isAllZero(oldBytes)) {
                            oldRow = newInstance(meta.clazz);
                            oldRow.setId(rowId);
                            oldRow.load(ByteBuffer.wrap(oldBytes));
                            if (ts == 1L) {
                                ts = nextWriteTimestamp();
                            }
                            long legacyDataId = bucketStore.put(meta.space, meta.type, oldBytes);
                            // legacy VR：初始 endTs=Long.MAX_VALUE（bootstrap 阶段可见），随后立即 updateVersionRecordEndTs
                            // 把 endTs 收紧到 ts-1（因为 newHead.beginTs=ts，legacy 只在 <ts 时可见）；
                            // abort 时 prevOrigEndTs=MAX 还原回 MAX → 最新读仍能看到 legacy 原始值（防止 NPE）
                            VersionRecord legacy = new VersionRecord(1, Long.MAX_VALUE, rowId, 0L, legacyDataId);
                            prevVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(legacy, vrl));
                            prevOrigEndTs = legacy.endTs;
                            headMap.put(rowId, prevVersionId);
                            updateVersionRecordEndTs(meta, prevVersionId, ts); // legacy.endTs → ts - 1
                        }
                    } catch (Exception ignore) {
                        oldRow = null;
                    }
                }
                // PCC 预写 Registry + TxOp 登记（仍在 lock 内，保证 txOldBytes 读到最新、writeRowPayload 立即生效到 Registry，applyOpCas cur==newPayload 通过）
                if (tx != null && registryPathForTx != null) {
                    if (rowDataBackend != null) {
                        rowDataBackend.writeRowPayload(meta.clazz.getName(), rowId, bytes);
                        // 关键 CAS 防冲突：写了 PCC bytes 后重读最新 bytes 作为 txOldBytes(expectedPrev)，
                        // 这样 applyOpCas 时 cur==bytes (cur==newPayload) → 直通；只有 mvccLock 释放后其他 tx 真 overwrite 才 mismatch。
                        txOldBytes = rowDataBackend.readRowPayload(meta.clazz.getName(), rowId, () -> null);
                    } else {
                        txOldBytes = null;
                    }
                    if (txOldBytes == null) {
                        txOldBytes = new byte[0];
                    }
                    if (registryReplicator != null) {
                        long v = writeTimestamp.incrementAndGet();
                        registryReplicator.replicateKey(registryPathForTx, bytes, v, new long[]{0L});
                    }
                    TxOpRegistry.registerRowPut(tx, meta.clazz.getName(), rowId, registryPathForTx, txOldBytes, bytes);
                }
                // ====== 原 block1 end ======

                // ====== 桶持久化：dataId/newVersionId 新 slot 分配，仍在 lock 内（2PL 持锁全程，不再缩小 critical section）======
                dataId = bucketStore.put(meta.space, meta.type, bytes);
                VersionRecord newHead = new VersionRecord(ts, Long.MAX_VALUE, rowId, prevVersionId, dataId);
                newVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(newHead, vrl));
                // ====== 原 block2：head gap 检查 + headMap.put（仍在 lock 内，无并发 gap，无需第二次 reacquire sync，但逻辑保留便于防御式）======
                Long curHeadNow = headMap.get(rowId);
                if (curHeadNow != null && curHeadNow.longValue() != headId
                        && curHeadNow.longValue() != prevVersionId) {
                    prevVersionId = curHeadNow.longValue();
                    VersionRecord newHead2 = new VersionRecord(ts, Long.MAX_VALUE, rowId, prevVersionId, dataId);
                    rewriteVersionRecordRaw(meta, newVersionId, newHead2);
                }
                headMap.put(rowId, newVersionId);
                // ====== 原 block2 end ======
            } catch (RuntimeException rx) {
                // lock 内抛异常：tx != null 时若已登记 lock，会在 tx finally 中正常释放（lock 已拿到无泄漏）。
                // tx == null 时 finally unlock 兜底。
                throw rx;
            } finally {
                if (tx == null || !strict2PL || heldByUsAlready) {
                    mvccLock.unlock();
                }
                // else: tx!=null && strict2PL && !heldByUsAlready（首次登记）→ 2PL Strict 持锁到 commit。
            }
            // ===== 以下 lock 已外但状态受 2PL 保护（其他线程拿不到同 shard 锁，无并发读写冲突）：undo/index/binlog/commitGroup =====
            if (tx == null) {
                appendBinlog(meta, value, oldRow, prevVersionId == 0L ? DsBinlogOpType.INSERT : DsBinlogOpType.UPDATE);
                flushBinlog();
            }
            if (tx == null && rowDataBackend != null) {
                rowDataBackend.writeRowPayload(meta.clazz.getName(), rowId, bytes);
            }
            if (tx == null && registryReplicator != null) {
                long v = writeTimestamp.incrementAndGet();
                String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
                registryReplicator.replicateKey(p, bytes, v, new long[]{0L});
            }
            if (tx != null) {
                final long fTxId = tx.txId;
                final long fPrevVersionId = prevVersionId;
                final long fPrevOrigEndTs = prevOrigEndTs;
                final long fNewVersionId = newVersionId;
                txMvccUndoMap.compute(fTxId, (k, list) -> {
                    List<MvccUndo> l = (list == null) ? new ArrayList<>(4) : list;
                    boolean merged = false;
                    for (int $ui = l.size() - 1; $ui >= 0; $ui--) {
                        MvccUndo existing = l.get($ui);
                        if (existing.meta == meta && existing.rowId == rowId) {
                            l.set($ui, new MvccUndo(meta, rowId, existing.prevVersionId, existing.prevOrigEndTs, fNewVersionId));
                            merged = true;
                            break;
                        }
                    }
                    if (!merged) {
                        l.add(new MvccUndo(meta, rowId, fPrevVersionId, fPrevOrigEndTs, fNewVersionId));
                    }
                    return l;
                });
            }
            if (oldRow != null && prevVersionId != 0L) {
                removeNonMvccRowFromIndexes(meta, oldRow, rowId);
            }
            putRowIntoIndexes(meta, value, rowId, newVersionId);
            if (tx == null) {
                commitGroup();
            }
            return rowId;
        }
        // tx == null 无事务路径：原实现不变（性能关键 P10~P13 baseline，零回归）
        if (headId != null && headId != 0L) {
            VersionRecord oldHead = readVersionRecord(meta, headId);
            if (oldHead != null) {
                prevVersionId = headId;
                prevOrigEndTs = oldHead.endTs;
                updateVersionRecordEndTs(meta, headId, ts);
                if (oldHead.dataId != 0L) {
                    oldRow = readEntityByDataId(meta, oldHead.dataId);
                    if (oldRow != null) {
                        oldRow.setId(rowId);
                    }
                }
            }
        } else {
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                if (oldBytes != null && !isAllZero(oldBytes)) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(rowId);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                    if (ts == 1L) {
                        ts = nextWriteTimestamp();
                    }
                    long legacyDataId = bucketStore.put(meta.space, meta.type, oldBytes);
                    VersionRecord legacy = new VersionRecord(1, Long.MAX_VALUE, rowId, 0L, legacyDataId);
                    prevVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(legacy, vrl));
                    prevOrigEndTs = legacy.endTs;
                    headMap.put(rowId, prevVersionId);
                    updateVersionRecordEndTs(meta, prevVersionId, ts); // legacy.endTs → ts - 1
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
        }
        appendBinlog(meta, value, oldRow, prevVersionId == 0L ? DsBinlogOpType.INSERT : DsBinlogOpType.UPDATE);
        flushBinlog();
        long dataId = bucketStore.put(meta.space, meta.type, bytes);
        if (rowDataBackend != null) {
            rowDataBackend.writeRowPayload(meta.clazz.getName(), rowId, bytes);
        }
        if (registryReplicator != null) {
            long v = writeTimestamp.incrementAndGet();
            String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
            registryReplicator.replicateKey(p, bytes, v, new long[]{0L});
        }
        VersionRecord newHead = new VersionRecord(ts, Long.MAX_VALUE, rowId, prevVersionId, dataId);
        long newVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(newHead, vrl));
        headMap.put(rowId, newVersionId);
        txManager.registerCommittedWriteTs(ts); // 非事务立即提交，对外可见
        if (oldRow != null && prevVersionId != 0L) {
            removeNonMvccRowFromIndexes(meta, oldRow, rowId);
        }
        putRowIntoIndexes(meta, value, rowId, newVersionId);
        commitGroup();
        return rowId;
    }

    /**
     * 批量 putEntity。对索引列按 indexedValue 聚合后只做一次 read-modify-write， 减少小批量写入时的 I/O
     * 放大。
     *
     * @param values 待保存实体
     * @param assignIdIfMissing 是否自动分配缺失的 ID
     * @return 保存后的 ID 列表（顺序与输入一致）
     * @throws IOException 当读写失败时
     */
    public <T extends DsTableAdapter> List<Long> putEntities(List<T> values, boolean assignIdIfMissing) throws IOException {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(values.get(0), "entity cannot be null");
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(values.get(0).getClass());

        if (meta.hasMvcc) {
            // MVCC 表暂退化到单条写入，保证版本链与索引一致性
            List<Long> ids = new ArrayList<>(values.size());
            for (T value : values) {
                ids.add(putEntity(value, assignIdIfMissing));
            }
            return ids;
        }

        int n = values.size();
        List<Long> ids = new ArrayList<>(n);
        List<T> oldRows = new ArrayList<>(n);
        List<byte[]> newBytesList = new ArrayList<>(n);

        // Phase 1: 分配 ID、序列化、读取旧行
        for (T value : values) {
            Objects.requireNonNull(value, "entity cannot be null");
            Long id = value.getId();
            if (assignIdIfMissing && (id == null || id == 0L)) {
                long newId = bucketStore.getNewId(meta.space, meta.type, meta.rowLength);
                value.setId(newId);
                id = newId;
            }
            if (id == null || id == 0L) {
                throw new IllegalArgumentException("entity id is missing");
            }
            ids.add(id);

            ByteBuffer buf = value.toBytes();
            if (buf.remaining() != meta.rowLength) {
                System.err.println("[DsDatabaseLocal] WARN putEntities row length mismatch (compat): expected=" + meta.rowLength + ", actual=" + buf.remaining() + ", class=" + value.getClass().getName());
            }
            byte[] bytes;
            if (buf.remaining() == meta.rowLength) {
                bytes = buf.array();
            } else {
                bytes = new byte[meta.rowLength];
                buf.rewind();
                int copyN = Math.min(buf.remaining(), meta.rowLength);
                buf.get(bytes, 0, copyN);
            }
            newBytesList.add(bytes);

            T oldRow = null;
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, id, 0, meta.rowLength);
                if (oldBytes != null) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(id);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
            oldRows.add(oldRow);
        }

        // Phase 2: 先批量写入 binlog（binlog-first）
        for (int i = 0; i < n; i++) {
            T row = values.get(i);
            T oldRow = oldRows.get(i);
            appendBinlog(meta, row, oldRow, oldRow == null ? DsBinlogOpType.INSERT : DsBinlogOpType.UPDATE);
        }

        // Phase 3: fsync binlog，保证日志落盘后再改 data/index
        flushBinlog();

        // Phase 4: 批量更新主表 + replicate hook
        for (int i = 0; i < n; i++) {
            bucketStore.update(meta.space, meta.type, ids.get(i), newBytesList.get(i), DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
            if (rowDataBackend != null) {
                rowDataBackend.writeRowPayload(meta.clazz.getName(), ids.get(i), newBytesList.get(i));
            }
            if (registryReplicator != null) {
                long v = writeTimestamp.incrementAndGet();
                String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), ids.get(i));
                registryReplicator.replicateKey(p, newBytesList.get(i), v, new long[]{0L});
            }
        }

        // Phase 5: 批量更新单字段索引
        for (IndexedColumn col : meta.indexedColumns) {
            batchUpdateIndex(meta, col, ids, oldRows, values);
        }

        // Phase 6: 批量更新复合索引
        for (CompositeIndex ci : meta.compositeIndexes) {
            batchUpdateCompositeIndex(meta, ci, ids, oldRows, values);
        }

        // Phase 7: group commit：sync data/index，再追加 CHECKPOINT
        commitGroup();

        return ids;
    }

    private <T extends DsTableAdapter> void batchUpdateIndex(TableMeta<T> meta, IndexedColumn col,
            List<Long> ids, List<T> oldRows, List<T> newRows) throws IOException {
        DsEqIndexStore idx = resolveEqIndex(meta, col);
        Map<Long, Set<Long>> target = new HashMap<>();
        Set<Long> touchedValues = new HashSet<>();

        int n = ids.size();
        for (int i = 0; i < n; i++) {
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                touchedValues.add(indexValueOf(col, oldRow));
            }
            touchedValues.add(indexValueOf(col, newRow));
        }
        for (Long v : touchedValues) {
            long[] current = idx.findByIndex(v);
            Set<Long> set = new LinkedHashSet<>();
            if (current != null) {
                for (long r : current) {
                    set.add(r);
                }
            }
            target.put(v, set);
        }
        for (int i = 0; i < n; i++) {
            long rowId = ids.get(i);
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                Set<Long> set = target.get(indexValueOf(col, oldRow));
                if (set != null) {
                    set.remove(rowId);
                }
            }
            target.get(indexValueOf(col, newRow)).add(rowId);
        }
        Map<Long, long[]> batch = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : target.entrySet()) {
            Set<Long> set = e.getValue();
            long[] arr = new long[set.size()];
            int pos = 0;
            for (Long r : set) {
                arr[pos++] = r;
            }
            batch.put(e.getKey(), arr);
        }
        idx.applyIndexBatch(batch);
    }

    private static long indexValueOf(IndexedColumn col, Object row) {
        Object val = readFieldValue(col.field, row);
        if (col.valueKind == DsEqIndexStore.IndexedValueKind.LONG) {
            return toIndexLongValue(col, val);
        } else {
            return hashStringForIndex(toIndexStringValue(col, val));
        }
    }

    private <T extends DsTableAdapter> void batchUpdateCompositeIndex(TableMeta<T> meta, CompositeIndex ci,
            List<Long> ids, List<T> oldRows, List<T> newRows) throws IOException {
        DsEqIndexStore idx = resolveCompositeIndex(meta, ci);
        Map<Long, Set<Long>> target = new HashMap<>();
        Set<Long> touchedValues = new HashSet<>();

        int n = ids.size();
        for (int i = 0; i < n; i++) {
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                touchedValues.add(compositeKeyValue(ci, oldRow));
            }
            touchedValues.add(compositeKeyValue(ci, newRow));
        }
        for (Long v : touchedValues) {
            long[] current = idx.findByIndex(v);
            Set<Long> set = new LinkedHashSet<>();
            if (current != null) {
                for (long r : current) {
                    set.add(r);
                }
            }
            target.put(v, set);
        }
        for (int i = 0; i < n; i++) {
            long rowId = ids.get(i);
            T oldRow = oldRows.get(i);
            T newRow = newRows.get(i);
            if (oldRow != null) {
                Set<Long> set = target.get(compositeKeyValue(ci, oldRow));
                if (set != null) {
                    set.remove(rowId);
                }
            }
            target.get(compositeKeyValue(ci, newRow)).add(rowId);
        }
        Map<Long, long[]> batch = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> e : target.entrySet()) {
            Set<Long> set = e.getValue();
            long[] arr = new long[set.size()];
            int pos = 0;
            for (Long r : set) {
                arr[pos++] = r;
            }
            batch.put(e.getKey(), arr);
        }
        idx.applyIndexBatch(batch);
    }

    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id) {
        TxContext tx = (txManager == null) ? null : txManager.currentOrNull();
        long readTs = (tx != null) ? tx.readTs : Long.MAX_VALUE;
        long selfWriteTs = (tx != null) ? tx.writeTs : 0L;
        return getTable(clazz, id, readTs, selfWriteTs);
    }

    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, long readTimestamp) {
        return getTable(clazz, id, readTimestamp, 0L);
    }

    private <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, long readTimestamp, long selfWriteTs) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        if (!meta.hasMvcc) {
            // 非 MVCC 表忽略 readTimestamp，直接读最新；P11 HYBRID 下优先 Registry，miss→bucket+回填
            try {
                final long rowId = id;
                byte[] bytes;
                if (rowDataBackend != null) {
                    // 注意：bucketStore.get 可能在 remove 后返回非 null 但全 0（fixed block 只标 inUse=0 不抹内容），
                    // 必须视为 null 才不会触发 HYBRID 回填把 tombstone 全 0 字节重新写回 Registry
                    bytes = rowDataBackend.readRowPayload(meta.clazz.getName(), rowId, () -> {
                        byte[] b = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                        if (b == null || isAllZero(b)) {
                            return null;
                        }
                        return b;
                    });
                } else {
                    bytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                }
                if (bytes == null || isAllZero(bytes)) {
                    return null;
                }
                T obj = newInstance(meta.clazz);
                obj.load(ByteBuffer.wrap(bytes));
                odCoordinator.overlayDefaultsOnRead(obj, id);
                return obj;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        // MVCC 表：id 可能是 versionId（来自索引 narrowing）或 rowId（来自全表扫）。
        // 通过 versionRecord 所在 bucket 的 power 与 id 的 power 是否一致来区分，避免
        // 把 rowId 误解析为另一条记录的 versionId。
        int versionPower = DsFixedBucketStore.decodePower(DsFixedBucketStore.encodeRowId(0L, versionRecordLength(meta)));
        if (DsFixedBucketStore.decodePower(id) == versionPower) {
            Long rowId = resolveRowIdFromVersionId(meta, id);
            if (rowId != null) {
                return resolveVisibleVersion(meta, rowId, readTimestamp, selfWriteTs);
            }
        }
        return resolveVisibleVersion(meta, id, readTimestamp, selfWriteTs);
    }

    public long putTable(DsTableAdapter value, boolean withRelations) throws IOException {
        long id = putEntity(value, true);
        if (!withRelations) {
            return id;
        }
        persistRelations(value);
        return id;
    }

    public <T extends DsTableAdapter> T getTable(Class<T> clazz, long id, boolean withRelations) {
        T obj = getTable(clazz, id);
        if (!withRelations) {
            return obj;
        }
        loadRelations(obj);
        return obj;
    }

    public <T extends DsTableAdapter> boolean removeTable(Class<T> clazz, long id, boolean withRelations) throws IOException {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        if (id == 0L) {
            throw new IllegalArgumentException("id is 0");
        }
        @SuppressWarnings("unchecked")
        TableMeta<T> meta = (TableMeta<T>) metaOf(clazz);
        long rowId = id;

        if (!meta.hasMvcc) {
            T oldRow = null;
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                if (oldBytes != null && !isAllZero(oldBytes)) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(rowId);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
            // binlog-first：无旧行时不产生 binlog（幂等删除），否则先写 DELETE 日志并落盘
            if (oldRow != null) {
                appendBinlog(meta, oldRow, oldRow, DsBinlogOpType.DELETE);
                flushBinlog();
            }
            if (oldRow != null) {
                removeRowFromIndexes(meta, oldRow, rowId, rowId);
                if (withRelations) {
                    removeRelations(clazz, rowId);
                }
                bucketStore.remove(meta.space, meta.type, rowId);
                if (rowDataBackend != null) {
                    rowDataBackend.removeRowPayload(meta.clazz.getName(), rowId);
                }
                if (registryReplicator != null) {
                    long v = writeTimestamp.incrementAndGet();
                    String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
                    registryReplicator.replicateKey(p, new byte[0], v, new long[]{0L});
                }
                commitGroup();
            }
            return oldRow != null;
        }

        // MVCC 路径：写入 tombstone version，保留历史版本
        long ts = nextWriteTimestamp();
        DsHashMap headMap = versionHeadMap(meta);
        Long headId = headMap.get(rowId);
        long prevVersionId = 0L;
        T oldRow = null;
        int vrl = versionRecordLength(meta);

        if (headId != null && headId != 0L) {
            VersionRecord oldHead = readVersionRecord(meta, headId);
            if (oldHead != null) {
                prevVersionId = headId;
                updateVersionRecordEndTs(meta, headId, ts);
                if (oldHead.dataId != 0L) {
                    oldRow = readEntityByDataId(meta, oldHead.dataId);
                    if (oldRow != null) {
                        oldRow.setId(rowId);
                    }
                }
            }
        } else {
            // 旧数据兼容
            try {
                byte[] oldBytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                if (oldBytes != null && !isAllZero(oldBytes)) {
                    oldRow = newInstance(meta.clazz);
                    oldRow.setId(rowId);
                    oldRow.load(ByteBuffer.wrap(oldBytes));
                    long legacyDataId = bucketStore.put(meta.space, meta.type, oldBytes);
                    VersionRecord legacy = new VersionRecord(1, ts - 1, rowId, 0L, legacyDataId);
                    prevVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(legacy, vrl));
                    headMap.put(rowId, prevVersionId);
                }
            } catch (Exception ignore) {
                oldRow = null;
            }
        }

        // binlog-first：有旧行时才写 DELETE 日志
        if (oldRow != null) {
            appendBinlog(meta, oldRow, oldRow, DsBinlogOpType.DELETE);
            flushBinlog();
        }

        VersionRecord tombstone = new VersionRecord(ts, Long.MAX_VALUE, rowId, prevVersionId, 0L);
        long tombstoneVersionId = bucketStore.put(mvccSpace(meta), MVCC_VERSION_TYPE, encodeVersionRecord(tombstone, vrl));
        headMap.put(rowId, tombstoneVersionId);
        if (registryReplicator != null) {
            long v = writeTimestamp.incrementAndGet();
            String p = RegistryMetaPaths.dataRowPayload(meta.clazz.getName(), rowId);
            registryReplicator.replicateKey(p, new byte[0], v, new long[]{0L});
        }

        if (oldRow != null && prevVersionId != 0L) {
            removeNonMvccRowFromIndexes(meta, oldRow, rowId);
        }

        if (withRelations) {
            removeRelations(clazz, rowId);
        }
        if (oldRow != null) {
            commitGroup();
        }
        return true;
    }

    private void removeRelations(Class<?> ownerClass, long ownerId) throws IOException {
        Field[] fields = ownerClass.getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                relationMap(ownerClass, f.getName(), "one_to_one").remove(ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "one_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                removeVarRelation(ownerClass, f.getName(), "many_to_many", "list", ownerId);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                removeVarRelation(ownerClass, f.getName(), "map", "pairs", ownerId);
            }
        }
    }

    private void removeVarRelation(Class<?> ownerClass, String fieldName, String kind, String type, long ownerId) throws IOException {
        DsHashMap map = relationMap(ownerClass, fieldName, kind);
        long stored = map.getOrDefault(ownerId, 0L);
        map.remove(ownerId);
        if (stored != 0L) {
            bucketStore.remove(relSpace(ownerClass, fieldName, kind), type, stored);
        }
    }

    public long putTableWithRelations(DsTableAdapter value) throws IOException {
        return putTable(value, true);
    }

    public <T extends DsTableAdapter> T getTableWithRelations(Class<T> clazz, long id) {
        return getTable(clazz, id, true);
    }

    private void persistRelations(DsTableAdapter owner) throws IOException {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                persistOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                persistOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                persistManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                persistMapField(owner, f);
            }
        }
    }

    private void loadRelations(DsTableAdapter owner) {
        if (owner == null || owner.getId() == null || owner.getId() == 0L) {
            return;
        }
        Field[] fields = owner.getClass().getDeclaredFields();
        for (Field f : fields) {
            if (f.isAnnotationPresent(DsOneToOne.class)) {
                loadOneToOne(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsOneToMany.class)) {
                loadOneToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsManyToMany.class)) {
                loadManyToMany(owner, f);
                continue;
            }
            if (f.isAnnotationPresent(DsMapField.class)) {
                loadMapField(owner, f);
            }
        }
    }

    private void persistOneToOne(DsTableAdapter owner, Field field) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof DsTableAdapter join)) {
            throw new IllegalArgumentException("DsOneToOne field must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        long joinId = putEntity(join, true);
        map.put(owner.getId().longValue(), joinId);
    }

    private void loadOneToOne(DsTableAdapter owner, Field field) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "one_to_one");
        Long joinId = map.get(owner.getId());
        if (joinId == null || joinId == 0L) {
            return;
        }
        Class<?> joinClass = field.getType();
        if (!DsTableAdapter.class.isAssignableFrom(joinClass)) {
            throw new IllegalArgumentException("DsOneToOne field type must extend DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
        }
        @SuppressWarnings("unchecked")
        DsTableAdapter join = getTable((Class<? extends DsTableAdapter>) joinClass, joinId);
        try {
            field.setAccessible(true);
            field.set(owner, join);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void persistOneToMany(DsTableAdapter owner, Field field) throws IOException {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }

    private void loadOneToMany(DsTableAdapter owner, Field field) {
        DsOneToMany ann = field.getAnnotation(DsOneToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "one_to_many");
    }

    private void persistManyToMany(DsTableAdapter owner, Field field) throws IOException {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        persistIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }

    private void loadManyToMany(DsTableAdapter owner, Field field) {
        DsManyToMany ann = field.getAnnotation(DsManyToMany.class);
        loadIdCollection(owner, field, ann.joinClass(), "many_to_many");
    }

    private void persistIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) throws IOException {
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Collection<?> col)) {
            throw new IllegalArgumentException("relation field must be Collection: " + owner.getClass().getName() + "." + field.getName());
        }
        if (col.isEmpty()) {
            map.remove(owner.getId());
            return;
        }

        long[] ids = new long[col.size()];
        int i = 0;
        for (Object item : col) {
            if (!(item instanceof DsTableAdapter join)) {
                throw new IllegalArgumentException("collection element must be DsTableAdapter: " + owner.getClass().getName() + "." + field.getName());
            }
            if (!joinClass.isInstance(join)) {
                throw new IllegalArgumentException("collection element type mismatch: expected=" + joinClass.getName());
            }
            ids[i++] = putEntity(join, true);
        }
        byte[] payload = encodeIdList(ids);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", old, payload);
        map.put(owner.getId().longValue(), stored);
    }

    private void loadIdCollection(DsTableAdapter owner, Field field, Class<? extends DsTableAdapter> joinClass, String kind) {
        DsHashMap map = relationMap(owner.getClass(), field.getName(), kind);
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), kind), "list", stored);
        long[] ids = decodeIdList(payload);
        if (ids.length == 0) {
            return;
        }
        List<DsTableAdapter> out = new ArrayList<>(ids.length);
        for (long id : ids) {
            out.add(getTable(joinClass, id));
        }
        try {
            field.setAccessible(true);
            if (List.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            if (Collection.class.isAssignableFrom(field.getType())) {
                field.set(owner, out);
                return;
            }
            throw new IllegalArgumentException("relation field must be List/Collection: " + owner.getClass().getName() + "." + field.getName());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void persistMapField(DsTableAdapter owner, Field field) throws IOException {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        field.setAccessible(true);
        Object v;
        try {
            v = field.get(owner);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        if (v == null) {
            map.remove(owner.getId());
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("DsMapField must be Map: " + owner.getClass().getName() + "." + field.getName());
        }
        if (m.isEmpty()) {
            map.remove(owner.getId());
            return;
        }

        long[] pairs = new long[m.size() * 2];
        int idx = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof DsTableAdapter k)) {
                throw new IllegalArgumentException("map key must be DsTableAdapter");
            }
            if (!(e.getValue() instanceof DsTableAdapter val)) {
                throw new IllegalArgumentException("map value must be DsTableAdapter");
            }
            if (!ann.keyClass().isInstance(k)) {
                throw new IllegalArgumentException("map key type mismatch: expected=" + ann.keyClass().getName());
            }
            if (!ann.valueClass().isInstance(val)) {
                throw new IllegalArgumentException("map value type mismatch: expected=" + ann.valueClass().getName());
            }
            pairs[idx++] = putEntity(k, true);
            pairs[idx++] = putEntity(val, true);
        }
        byte[] payload = encodeIdPairs(pairs);
        long old = map.getOrDefault(owner.getId(), 0L);
        long stored = storeVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", old, payload);
        map.put(owner.getId().longValue(), stored);
    }

    private void loadMapField(DsTableAdapter owner, Field field) {
        DsMapField ann = field.getAnnotation(DsMapField.class);
        DsHashMap map = relationMap(owner.getClass(), field.getName(), "map");
        Long stored = map.get(owner.getId());
        if (stored == null || stored == 0L) {
            return;
        }
        byte[] payload = loadVarBytes(relSpace(owner.getClass(), field.getName(), "map"), "pairs", stored);
        long[] pairs = decodeIdPairs(payload);
        if (pairs.length == 0) {
            return;
        }
        Map<DsTableAdapter, DsTableAdapter> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            DsTableAdapter k = getTable(ann.keyClass(), pairs[i]);
            DsTableAdapter v = getTable(ann.valueClass(), pairs[i + 1]);
            out.put(k, v);
        }
        try {
            field.setAccessible(true);
            field.set(owner, out);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private DsHashMap relationMap(Class<?> ownerClass, String fieldName, String kind) {
        DsPathUtil.validateSegment(fieldName, "fieldName");
        DsPathUtil.validateSegment(kind, "relation kind");
        String key = ownerClass.getName() + "#" + fieldName + "#" + kind;
        return relationMapCache.computeIfAbsent(key, k -> {
            File f = relationMapFile(ownerClass, fieldName, kind);
            return new DsHashMap(f);
        });
    }

    private File relationMapFile(Class<?> ownerClass, String fieldName, String kind) {
        String spacePath = DsPathUtil.dottedToLinuxPath(ownerClass.getName(), "table");
        String relRoot = new File(root, "relations").getAbsolutePath().replace('\\', '/');
        String full = relRoot + "/" + spacePath + "/" + kind;
        File dir = new File(full);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, fieldName + ".map");
    }

    private static String relSpace(Class<?> ownerClass, String fieldName, String kind) {
        return ownerClass.getName() + "." + kind + "." + fieldName;
    }

    private long storeVarBytes(String space, String type, long oldId, byte[] payload) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        byte[] data = wrapVarPayload(payload);
        if (oldId != 0L) {
            return bucketStore.update(space, type, oldId, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        }
        long id = bucketStore.getNewId(space, type, data.length);
        bucketStore.update(space, type, id, data, DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
        return id;
    }

    private byte[] loadVarBytes(String space, String type, long id) {
        int unit = bucketUnitSize(id);
        try {
            byte[] raw = bucketStore.get(space, type, id, 0, unit);
            return unwrapVarPayload(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static int bucketUnitSize(long id) {
        int power = (int) ((id >>> DsFixedBucketStore.ID_OFFSET_BITS) & 0xFF);
        return 1 << power;
    }

    private static byte[] wrapVarPayload(byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(4 + payload.length);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] unwrapVarPayload(byte[] raw) {
        if (raw == null || raw.length < 4) {
            return new byte[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        int len = buf.getInt();
        if (len <= 0) {
            return new byte[0];
        }
        if (len > raw.length - 4) {
            throw new IllegalStateException("corrupted var payload length");
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    private static byte[] encodeIdList(long[] ids) {
        ByteBuffer buf = ByteBuffer.allocate(4 + ids.length * 8);
        buf.putInt(ids.length);
        for (long id : ids) {
            buf.putLong(id);
        }
        return buf.array();
    }

    private static long[] decodeIdList(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        if (payload.length != 4 + n * 8) {
            throw new IllegalStateException("id list payload size mismatch");
        }
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = buf.getLong();
        }
        return ids;
    }

    private static byte[] encodeIdPairs(long[] pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("pairs length must be even");
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + pairs.length * 8);
        buf.putInt(pairs.length / 2);
        for (long id : pairs) {
            buf.putLong(id);
        }
        return buf.array();
    }

    private static long[] decodeIdPairs(byte[] payload) {
        if (payload == null || payload.length < 4) {
            return new long[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        int n = buf.getInt();
        if (n <= 0) {
            return new long[0];
        }
        int expect = 4 + n * 16;
        if (payload.length != expect) {
            throw new IllegalStateException("pairs payload size mismatch");
        }
        long[] out = new long[n * 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getLong();
        }
        return out;
    }

    private static final class TableMeta<T extends DsTableAdapter> {

        final Class<T> clazz;
        final String space;
        final String type;
        final int rowLength;
        final int legacyRowLength;
        final String schemaId;
        final int tableId;
        final List<EntitySchemaUtil.ColumnDef> columns;
        final Map<String, Integer> colIdMap;
        final List<IndexedColumn> indexedColumns;
        final List<CompositeIndex> compositeIndexes;
        /**
         * 该表是否启用了 MVCC（表级为 true 或存在任一列级 mvcc=true）。
         */
        final boolean hasMvcc;
        /**
         * 启用 MVCC 的列名集合；若表级 mvcc=true 则包含所有列。
         */
        final Set<String> mvccColumnNames;
        /**
         * binlog 日志级别：控制写入时记录新值、旧值还是两者。
         */
        final DsBinlogLogLevel binlogLogLevel;

        TableMeta(Class<T> clazz, String space, String type, int rowLength, int legacyRowLength, String schemaId,
                int tableId, List<EntitySchemaUtil.ColumnDef> columns, Map<String, Integer> colIdMap,
                List<IndexedColumn> indexedColumns, List<CompositeIndex> compositeIndexes, boolean hasMvcc,
                Set<String> mvccColumnNames, DsBinlogLogLevel binlogLogLevel) {
            this.clazz = clazz;
            this.space = space;
            this.type = type;
            this.rowLength = rowLength;
            this.legacyRowLength = legacyRowLength;
            this.schemaId = schemaId;
            this.tableId = tableId;
            this.columns = columns == null || columns.isEmpty()
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(columns));
            this.colIdMap = colIdMap == null ? java.util.Collections.emptyMap() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(colIdMap));
            this.indexedColumns = indexedColumns == null || indexedColumns.isEmpty()
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(indexedColumns));
            this.compositeIndexes = compositeIndexes == null || compositeIndexes.isEmpty()
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new ArrayList<>(compositeIndexes));
            this.hasMvcc = hasMvcc;
            this.mvccColumnNames = mvccColumnNames == null
                    ? java.util.Collections.emptySet()
                    : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(mvccColumnNames));
            this.binlogLogLevel = binlogLogLevel == null ? DsBinlogLogLevel.REDO : binlogLogLevel;
        }
    }

    // ================= MVCC support =================
    private static final String MVCC_SPACE_SUFFIX = ".mvcc";
    private static final String MVCC_VERSION_TYPE = "versions";
    private static final int VERSION_RECORD_LENGTH = 40;

    private static final class VersionRecord {

        long beginTs;
        long endTs;
        final long rowId;
        final long prevVersionId;
        final long dataId;

        VersionRecord(long beginTs, long endTs, long rowId, long prevVersionId, long dataId) {
            this.beginTs = beginTs;
            this.endTs = endTs;
            this.rowId = rowId;
            this.prevVersionId = prevVersionId;
            this.dataId = dataId;
        }

        boolean visibleTo(long readTs) {
            return beginTs <= readTs && readTs <= endTs;
        }
    }

    private long nextWriteTimestamp() {
        return writeTimestamp.getAndIncrement();
    }

    private String mvccSpace(TableMeta<?> meta) {
        return meta.space + MVCC_SPACE_SUFFIX;
    }

    /**
     * 计算当前表 MVCC 版本记录的存储长度。
     *
     * <p>
     * 目标：让 versionId 所在 bucket 的 power 与行记录的 power 不同，从而避免 rowId 与 versionId
     * 在全局 ID 命名空间中冲突。</p>
     */
    private int versionRecordLength(TableMeta<?> meta) {
        int rowPower = DsFixedBucketStore.decodePower(DsFixedBucketStore.encodeRowId(0L, meta.rowLength));
        int versionPower = DsFixedBucketStore.decodePower(DsFixedBucketStore.encodeRowId(0L, VERSION_RECORD_LENGTH));
        if (rowPower == versionPower) {
            // 行记录也在 power 6（64B）档位，版本记录升到 power 7（128B）
            return 1 << (rowPower + 1);
        }
        return 1 << versionPower;
    }

    private File versionHeadMapFile(TableMeta<?> meta) {
        String spacePath = DsPathUtil.dottedToLinuxPath(meta.space, "table");
        String rootPath = new File(root, "mvcc/heads").getAbsolutePath().replace('\\', '/');
        File dir = new File(rootPath + "/" + spacePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "heads");
    }

    private DsHashMap versionHeadMap(TableMeta<?> meta) {
        DsHashMap map = versionHeadMapCache.get(meta.clazz);
        if (map != null) {
            return map;
        }
        map = new DsHashMap(versionHeadMapFile(meta));
        DsHashMap existing = versionHeadMapCache.putIfAbsent(meta.clazz, map);
        return existing != null ? existing : map;
    }

    private byte[] encodeVersionRecord(VersionRecord vr, int length) {
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.putLong(vr.beginTs);
        buf.putLong(vr.endTs);
        buf.putLong(vr.rowId);
        buf.putLong(vr.prevVersionId);
        buf.putLong(vr.dataId);
        // 剩余字节保持为 0，仅用于把记录放到与行记录不同 power 的 bucket 中
        return buf.array();
    }

    private VersionRecord decodeVersionRecord(byte[] bytes) {
        if (bytes == null || bytes.length < VERSION_RECORD_LENGTH) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long beginTs = buf.getLong();
        long endTs = buf.getLong();
        long rowId = buf.getLong();
        long prevVersionId = buf.getLong();
        long dataId = buf.getLong();
        // 全 0 表示该 bucket 单元尚未写入真实版本记录（例如 id 被误当作 versionId 读取）
        if (beginTs == 0L && endTs == 0L && rowId == 0L && prevVersionId == 0L && dataId == 0L) {
            return null;
        }
        return new VersionRecord(beginTs, endTs, rowId, prevVersionId, dataId);
    }

    private static boolean isAllZero(byte[] bytes) {
        if (bytes == null) {
            return true;
        }
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private VersionRecord readVersionRecord(TableMeta<?> meta, long versionId) throws IOException {
        int length = bucketUnitSize(versionId);
        byte[] bytes = bucketStore.get(mvccSpace(meta), MVCC_VERSION_TYPE, versionId, 0, length);
        return decodeVersionRecord(bytes);
    }

    private Long resolveRowIdFromVersionId(TableMeta<?> meta, long id) {
        try {
            VersionRecord vr = readVersionRecord(meta, id);
            return vr != null ? vr.rowId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateVersionRecordEndTs(TableMeta<?> meta, long versionId, long endTs) throws IOException {
        VersionRecord vr = readVersionRecord(meta, versionId);
        if (vr == null) {
            return;
        }
        // 旧版本的可见窗口在下一版本 beginTs 之前结束（endTs 为开区间上限）
        vr.endTs = endTs - 1;
        // 按原 versionId 的 bucket 档位重写，避免迁移导致版本链断裂
        int length = bucketUnitSize(versionId);
        bucketStore.update(mvccSpace(meta), MVCC_VERSION_TYPE, versionId, encodeVersionRecord(vr, length),
                DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
    }

    private <T extends DsTableAdapter> T readEntityByDataId(TableMeta<T> meta, long dataId) {
        try {
            byte[] bytes = bucketStore.get(meta.space, meta.type, dataId, 0, meta.rowLength);
            if (bytes == null) {
                return null;
            }
            T obj = newInstance(meta.clazz);
            obj.load(ByteBuffer.wrap(bytes));
            odCoordinator.overlayDefaultsOnRead(obj, dataId);
            return obj;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 沿版本链找到对 readTs 可见的版本并读取实体。 若不存在版本链（旧数据兼容），直接按 dataId==rowId 读取主表。 列级 MVCC
     * 下，非 MVCC 列始终使用当前最新值覆盖历史版本中的对应字段。
     */
    private <T extends DsTableAdapter> T resolveVisibleVersion(TableMeta<T> meta, long rowId, long readTs) {
        return resolveVisibleVersion(meta, rowId, readTs, 0L);
    }

    /**
     * @param selfWriteTs 0=无事务自读场景；>0=当前 tx.writeTs，使 vr.beginTs==selfWriteTs
     * 的自写版本恒可见（Writes-Follow-Reads）。
     */
    private <T extends DsTableAdapter> T resolveVisibleVersion(TableMeta<T> meta, long rowId, long readTs, long selfWriteTs) {
        // ====== TEARING PREVENTION: serialize READ vs concurrent in-place VR slot rewrite (applyMvccUndo tombstone/endTs-restore) ======
        // applyMvccUndo may rewrite VR slot bytes in-place for same versionId, which can cause a concurrent READ to see
        // torn 48-byte VR: mixed beginTs/endTs/dataId → resolveVisibleVersion wrongly returns null (e.g. t04 "warehouse not found: w=1").
        // Per-shard ReentrantLock serializes READ vs WRITE on this shard for the duration of the single read walk.
        // Lock is released immediately after read walk (no 2PL hold on reads): MVCC RR snapshots use timestamps, not read locks.
        final ReentrantLock vrLock = mvccRowLockFor(meta, rowId);
        vrLock.lock();
        try {
            // MVCC 快照读严格模式：取当前线程 tx.begin 时刻的 committedSet 快照视图（非 null=strict）+ strictCommitCheck=true（防脏读）
            // 无 tx 时 ctx=null → 退化为全局 committedSet（loadSeedData 非事务路径安全）
            TxContext ctx = (selfWriteTs > 0L || readTs != Long.MAX_VALUE) ? txManager.currentOrNull() : null;
            return resolveVisibleVersionNoLock(meta, rowId, readTs, selfWriteTs, true, ctx);
        } finally {
            vrLock.unlock(); // 读完即释放（READ 锁不做 2PL 登记：MVCC 用时间戳快照，不阻塞其他 tx 写，避免 t05 TX1 锁死 TX2 写造成的 livelock）
        }
    }

    /**
     * 内部"不加锁"版本：调用方保证已持有 mvccRowLockFor(meta,rowId) 锁（如 putEntity mvccLock
     * 内写路径），避免锁嵌套 / reentrant 计数错误。
     *
     * @param strictCommitCheck MVCC 写路径取 latestEntity（用于 delta 合并基线）传 false：只要
     * vr.visibleTo(readTs) 就可见， 不检查 beginTs 是否 committed —— 因为其他 tx 未提交但已经挂到
     * head 上的 in-flight 写， 必须作为当前写的最新基线，否则 merge 后会覆盖其他 tx 的 in-flight 值（典型
     * lost update t04 delta≪success）。 快照读（外部用户 db.getTable）传 true：严格 committed
     * + selfWriteTs，防脏读。
     * @param ctx strictCommitCheck=true 时非 null=用
     * ctx.snapshotCommittedWriteTimestamps 作为 begin 时刻固定视图， 保证同一 tx
     * 两次快照读一致；null=退化为全局 committedSet（非事务路径）。
     */
    private <T extends DsTableAdapter> T resolveVisibleVersionNoLock(TableMeta<T> meta, long rowId, long readTs, long selfWriteTs, boolean strictCommitCheck, TxContext ctx) {
        try {
            Long headId = versionHeadMap(meta).get(rowId);
            if (headId == null || headId == 0L) {
                byte[] bytes;
                try {
                    bytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                } catch (IllegalArgumentException bucketOverflow) {
                    bytes = null;
                }
                if (bytes == null) {
                    return null;
                }
                T obj = newInstance(meta.clazz);
                obj.load(ByteBuffer.wrap(bytes));
                odCoordinator.overlayDefaultsOnRead(obj, rowId);
                return obj;
            }
            long currentId = headId;
            while (currentId != 0L) {
                VersionRecord vr = readVersionRecord(meta, currentId);
                if (vr == null) {
                    break;
                }
                // Writes-Follow-Reads：自写 (beginTs == selfWriteTs) 恒可见，不要求已提交（保证本 tx 内刚写的立即可读）。
                boolean selfVisible = (selfWriteTs > 0L && vr.beginTs == selfWriteTs);
                boolean commitOk = true;
                if (strictCommitCheck && !selfVisible) {
                    // 快照读 strict=true：其他 tx 写必须 beginTs 在 snapshot 时刻 committedSet 视图中才可见（防脏读 + 保证同一 tx 两次快照读一致）
                    commitOk = txManager.isVersionVisibleForSnapshot(ctx, vr.beginTs, selfWriteTs);
                }
                // strict 快照模式：如果 VR 是本 tx 可见的（自写 OR committed in snapshotSet），则 beginTs<=readTs 即可见，忽略 vr.endTs 收紧值。
                //   原因：最新 head 可能是 OTHER tx 的未 committed in-flight VR，它写 VR 时会把 prev endTs 收紧到 in-flight.beginTs-1；
                //         但如果那个 in-flight 对我不可见（strict=true + 未 committed），prev 版本对我来说从未被终结，endTs 逻辑上仍应是 MAX，
                //         否则会出现"head 不可见，prev endTs 被收紧 < readTs → 所有 VR 不可见 → row 凭空消失 → district/warehouse not found"。
                // non-strict 写基线模式：走真实 visibleTo（允许 in-flight latest 作基线，防 WR lost-update latest 拿旧值覆盖）。
                boolean timeOk;
                if (strictCommitCheck && (selfVisible || commitOk)) {
                    timeOk = (vr.beginTs <= readTs);
                } else {
                    timeOk = vr.visibleTo(readTs);
                }
                // false 模式（写基线 latestEntity）：只要 timeOk 就可见（允许其他 tx 未提交但已挂 head 的 in-flight 写作为基线，防 lost update）
                if (selfVisible || (timeOk && commitOk)) {
                    if (vr.dataId == 0L) {
                        return null;
                    }
                    T obj = readEntityByDataId(meta, vr.dataId);
                    if (obj != null) {
                        obj.setId(rowId);
                        if (readTs != Long.MAX_VALUE && !meta.mvccColumnNames.isEmpty()) {
                            T latest = resolveLatestVersionNoLock(meta, rowId);
                            if (latest != null) {
                                patchNonMvccColumns(obj, latest, meta);
                            }
                        }
                    }
                    return obj;
                }
                currentId = vr.prevVersionId;
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * MVCC RR + 2PL Write 冲突合并（字段级 last-writer-wins by intent）—— 对象反射级，不依赖字节
     * offset/byteSize。
     * <p>
     * 解决 t04 lost update：T1 读 d.next_o_id=3000 → T2 读 d.next_o_id=3000 并写 3001
     * 提交 → T1 写 3001 提交 → 原丢失 T2 增量。
     * <p>
     * 次问题修复（同 tx 同 row 多次 putEntity delta
     * 双加）：TxContext.mergedFieldsByShardKey(shardKey,colName) 跟踪每列是否已 apply 过
     * delta， 已 apply → 直接跳过，防止 District.next_o_id +1 在 2 次 putEntity 被错累计 +2。
     * <p>
     * 算法（对象级反射，避开 ColumnDef 没有 offset/byteSize 字段问题）： 1. snapshotBaseline =
     * tx.readTs 下对本 tx 快照可见的原 row 对象 → 代表 tx 读时的基线（snapShardTs=0，不含自写，保证多次一致）
     * 2. intentEntity = tx 业务逻辑 set 后 putEntity(value) 的实际 row 对象 → 代表 tx 的写意图
     * 3. latestEntity = 锁内重读 Long.MAX_VALUE + selfWriteTs 解析出的最新 row（其他 tx
     * 已提交或本 tx 自写） 4. 逐字段： - 已在 TxContext 登记为 merged → 跳过，防止双加 delta - 值相同
     * (Objects.equals(snapVal, intentVal))：tx 未改此字段 → 保留
     * latestEntity.field（尊重其他 tx 提交写） - 值不同：tx 确实写过此字段： (a) 非数值类型 / 数值但 latest
     * == snap（无其他 tx 写）→ last-writer-wins 强盖 intent.val (b) 数值类型
     * (Long/Integer/Short/Byte) 且 latest != snap（其他 tx 也写过）→ 保留其他 tx 的写，按增量
     * (intent-snap) 累加到 latest，即 final = latest + (intent - snap)。 这覆盖 TPC-C 所有
     * counter 场景：d.next_o_id +=1、w/d_ytd +=totalAmount、s.quantity -= qty 等。 -
     * 写完后：registerFieldMerged(ctx, shardKey, colName) 标记为"已 apply 过
     * delta"，后续调用跳过。 5. 返回 patched latestEntity（实际写 bucket / Registry / TxOp）。
     *
     * @param snapshotBaseline 本 tx 快照可见的基线对象（UPDATE 场景非 null；INSERT 可能 null）
     * @param intentEntity 调用方传入的 value（当前写意图）
     * @param latestEntity 锁内重读得到的最新已提交 row
     * @param meta 表元信息（含字段 Field）
     * @param ctx 事务上下文（用于 mergedFieldsByShardKey 幂等跟踪）
     * @param shardKey shardId（同 heldRowLocks map 键，mergedFieldsByShardKey 幂等跟踪）
     * @return 合并后应实际写入的实体对象
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T extends DsTableAdapter> T patchLatestEntityWithIntentFields(T snapshotBaseline, T intentEntity, T latestEntity, TableMeta meta, TxContext ctx, int shardKey) {
        if (latestEntity == null) {
            return intentEntity;
        }
        if (snapshotBaseline == null) {
            return intentEntity; // INSERT / 快照解析异常：无基线，直接写意图

        }
        if (meta == null || meta.columns == null) {
            return latestEntity;
        }
        final java.util.List<EntitySchemaUtil.ColumnDef> cols = (java.util.List<EntitySchemaUtil.ColumnDef>) meta.columns;
        for (EntitySchemaUtil.ColumnDef col : cols) {
            java.lang.reflect.Field f = col.declaredField;
            if (f == null) {
                continue;
            }
            final String colName = col.name;
            try {
                Object snapVal = readFieldValue(f, snapshotBaseline);
                Object intentVal = readFieldValue(f, intentEntity);
                Object latestVal = readFieldValue(f, latestEntity);
                Object lastIntent = txManager.getLastMergedIntentValue(ctx, shardKey, colName);
                // 1) 幂等短路：上次应用的 intent 与本次完全相同 → 同对象重复 putEntity，列无新变化，跳过
                if (lastIntent != null && java.util.Objects.equals(lastIntent, intentVal)) {
                    continue;
                }
                // 2) 相对 tx 基线 snap 没变化 → 不处理
                if (java.util.Objects.equals(snapVal, intentVal)) {
                    continue;
                }
                // 3) 有变化：分 数值型(支持delta叠加) / 非数值型(last-writer-wins)
                if ((snapVal instanceof Long && intentVal instanceof Long && latestVal instanceof Long)
                        || (snapVal instanceof Integer && intentVal instanceof Integer && latestVal instanceof Integer)
                        || (snapVal instanceof Short && intentVal instanceof Short && latestVal instanceof Short)
                        || (snapVal instanceof Byte && intentVal instanceof Byte && latestVal instanceof Byte)) {
                    long s = ((Number) snapVal).longValue();
                    long i = ((Number) intentVal).longValue();
                    long l = ((Number) latestVal).longValue();
                    long finalVal;
                    if (lastIntent != null && (lastIntent instanceof Number)) {
                        long li = ((Number) lastIntent).longValue();
                        long deltaNew = i - li;
                        finalVal = l + deltaNew;
                    } else if (!java.util.Objects.equals(snapVal, latestVal)) {
                        long delta = i - s;
                        finalVal = l + delta;
                    } else {
                        finalVal = i;
                    }
                    Object coerced;
                    if (snapVal instanceof Long) {
                        coerced = finalVal;
                    } else if (snapVal instanceof Integer) {
                        coerced = (int) finalVal;
                    } else if (snapVal instanceof Short) {
                        coerced = (short) finalVal;
                    } else {
                        coerced = (byte) finalVal;
                    }
                    writeFieldValue(f, latestEntity, coerced);
                } else {
                    // 非数值型：last-writer-wins（幂等相同 case 已在上面 continue，这里必是不同值）
                    writeFieldValue(f, latestEntity, intentVal);
                }
                txManager.registerLastMergedIntentValue(ctx, shardKey, colName, intentVal);
            } catch (Exception ignoreField) {
            }
        }
        return latestEntity;
    }

    /**
     * 内部用于 patchNonMvccColumns 的"无额外锁包装"版本：调用方 resolveVisibleVersion 外层已持有 row
     * 锁，避免死锁。
     */
    private <T extends DsTableAdapter> T resolveLatestVersionNoLock(TableMeta<T> meta, long rowId) {
        try {
            long readTs = Long.MAX_VALUE;
            Long headId = versionHeadMap(meta).get(rowId);
            if (headId == null || headId == 0L) {
                byte[] bytes = bucketStore.get(meta.space, meta.type, rowId, 0, meta.rowLength);
                if (bytes == null) {
                    return null;
                }
                T obj = newInstance(meta.clazz);
                obj.load(ByteBuffer.wrap(bytes));
                odCoordinator.overlayDefaultsOnRead(obj, rowId);
                return obj;
            }
            long currentId = headId;
            while (currentId != 0L) {
                VersionRecord vr = readVersionRecord(meta, currentId);
                if (vr == null) {
                    break;
                }
                if (vr.visibleTo(readTs)) {
                    if (vr.dataId == 0L) {
                        return null;
                    }
                    T obj = readEntityByDataId(meta, vr.dataId);
                    if (obj != null) {
                        obj.setId(rowId);
                    }
                    return obj;
                }
                currentId = vr.prevVersionId;
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将 latest 中非 MVCC 列的值覆盖到 target 对应字段。
     */
    private <T extends DsTableAdapter> void patchNonMvccColumns(T target, T latest, TableMeta<T> meta) {
        for (EntitySchemaUtil.ColumnDef col : meta.columns) {
            if (meta.mvccColumnNames.contains(col.name)) {
                continue; // MVCC 列保留历史值
            }
            Object val = readFieldValue(col.declaredField, latest);
            writeFieldValue(col.declaredField, target, val);
        }
    }

    /**
     * 读取某 rowId 的最新已提交版本（readTs = Long.MAX_VALUE）。
     */
    private <T extends DsTableAdapter> T resolveLatestVersion(TableMeta<T> meta, long rowId) {
        return resolveVisibleVersion(meta, rowId, Long.MAX_VALUE);
    }

    /**
     * 存储DsTableAdapter
     *
     * @param value
     * @return
     * @throws IOException
     */
    public Long putTable(DsTableAdapter value) throws IOException {
        return putTable(value, false);

    }

    /**
     * 装载DsTableAdapter
     *
     * @param key
     * @return
     */
    public DsTableAdapter getTable(long key) {
        throw new UnsupportedOperationException("use getTable(Class, long) because id alone cannot determine table class");
    }

    /**
     * 存储DsTableAdapter内部一对一字段
     *
     * @param table
     * @param field
     * @return
     * @throws IOException
     */
    public Long putTable(DsTableAdapter table, DsOneToOne field) throws IOException {
        // TODO file(root,field.getClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueId  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储value.toBytes() 返回64位valueId
        //return add(key);
        return null;

    }

    /**
     * 装载DsTableAdapter内部一对一字段
     *
     * @param table
     * @param field
     * @return
     */
    public DsTableAdapter getTable(DsTableAdapter table, DsOneToOne field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueId  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 存储DsTableAdapter内部一对多字段 List/Set
     *
     * @param table
     * @param field
     * @return
     * @throws IOException
     */
    public Long putTable(DsTableAdapter table, DsOneToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.joinProp() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.joinProp() -> valueIds) 返回64位valueId
        //return add(key);
        return null;

    }

    /**
     * 装载DsTableAdapter内部一对多字段 List/Set
     *
     * @param table
     * @param field
     * @return
     */
    public DsTableAdapter getTable(DsTableAdapter table, DsOneToMany field) {
        try {
            // TODO file(root,field.joinClass().getName() +"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取一对多Ids.
            // TODO table.getId() -> key : field.joinProp()  -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     *
     * @param table
     * @param field
     * @return
     * @throws IOException
     */
    public Long putTable(DsTableAdapter table, DsManyToMany field) throws IOException {
        // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储一对多Ids.
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List/Set(field.id() -> valueIds) 返回64位valueId
        //return add(key);
        return null;

    }

    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     *
     * @param table
     * @param field
     * @return
     */
    public DsTableAdapter getTable(DsTableAdapter table, DsManyToMany field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap
            // TODO table.getId() -> key : field.joinProp() -> valueIds  DsHashMap
            // TODO file(root,field.joinClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long key)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 存储DsTableAdapter内部多对多字段-List,Set
     *
     * @param table
     * @param field
     * @return
     * @throws IOException
     */
    public Long putTable(DsTableAdapter table, DsMapField field) throws IOException {
        // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter key)
        // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::putTable(DsTableAdapter value)
        // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 存储List{key,value}
        // TODO 存储逻辑: table.getId() -> key : field.id() -> valueIds  DsHashMap  valueId==null?新增:更新  调用DsFixedBucketStore -> 存储 List{key,value} 返回64位valueId
        //return add(key);
        return null;

    }

    /**
     * 装载DsTableAdapter内部多对多字段-List,Set
     *
     * @param table
     * @param field
     * @return
     */
    public DsTableAdapter getTable(DsTableAdapter table, DsMapField field) {
        try {
            // TODO file(root,table.getClass().getName()+"_"+field.name -> [子目录+文件名]) -> DsHashMap 获取List{key,value}
            // TODO table.getId() -> key : valueId -> List{key,value}  DsHashMap
            // TODO file(root,field.keyClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long keyId)
            // TODO file(root,field.valueClass().getName() -> [子目录+文件名]) -> DsHashSet::getTable(long valueId)
            //TODO 调用DsFixedBucketStore -> load(ByteBuffer data)
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return null;
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 立即对指定表执行 Online DDL 兼容迁移。 实际策略：把当前缓存的 TableMeta
     * 清空，下次调用 createMeta() 会重新对比当前 sampleRowLength 与存储的 legacy rowLength。
     * 业务可在加字段后手动调这个按钮，或配合后续 putEntity/getTable 的懒加载自动回退 default 值机制。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void forceApplyOnlineDDL(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        try {
            odCoordinator.forceApply(clazz);
        } finally {
            tableMetaCache.remove(clazz);
            metaOf(clazz);
        }
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 取消 Online DDL 状态。 等价于把当前缓存的 TableMeta 从
     * tableMetaCache 中删除，下次 createMeta 会重新走 schema 对比流程。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void cancelOnlineDDL(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        odCoordinator.cancel(clazz.getName());
        tableMetaCache.remove(clazz);
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁）— 回滚到上一个 schema 版本。 当前实现等价于
     * cancelOnlineDDL（清空缓存重算），未来可拓展为切换 ids_<oldSchemaId>.set 的真实回滚指针。
     *
     * @param clazz DsTableAdapter 子类
     */
    public <T extends DsTableAdapter> void forceRollbackSchema(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz cannot be null");
        try {
            odCoordinator.rollback(clazz.getName());
        } finally {
            tableMetaCache.remove(clazz);
            metaOf(clazz);
        }
    }

    // ================ P10 T8 Online DDL Registry 双向（FR-7）================
    private static final String DEFAULT_DB_SPACE = "default";

    private void writeOnlineDdlState(String entityClassName,
            String tag,
            String fromSchemaId,
            String toSchemaId,
            Long startedAt,
            Long heartbeat) {
        if (!MetaMode.isGlobalRegistryOrHybrid()) {
            return;
        }
        try {
            DsRegistry reg = DsRegistryInstance.get(this.root);
            String dir = RegistryMetaPaths.onlineDdlRoot(DEFAULT_DB_SPACE, entityClassName);
            reg.mkdirs(dir);
            DsRegistryExtAttrsHelper.removeAllTags(reg, dir);
            DsRegistryExtAttrsHelper.addTag(reg, dir, tag);

            if (fromSchemaId != null) {
                reg.mkdirs(RegistryMetaPaths.parentOf0(RegistryMetaPaths.onlineDdlFrom(DEFAULT_DB_SPACE, entityClassName)));
                reg.putString(RegistryMetaPaths.onlineDdlFrom(DEFAULT_DB_SPACE, entityClassName), fromSchemaId, DataType.REG_SZ);
            }
            if (toSchemaId != null) {
                reg.mkdirs(RegistryMetaPaths.parentOf0(RegistryMetaPaths.onlineDdlTo(DEFAULT_DB_SPACE, entityClassName)));
                reg.putString(RegistryMetaPaths.onlineDdlTo(DEFAULT_DB_SPACE, entityClassName), toSchemaId, DataType.REG_SZ);
            }
            if (startedAt != null) {
                reg.mkdirs(RegistryMetaPaths.parentOf0(RegistryMetaPaths.onlineDdlStartedAt(DEFAULT_DB_SPACE, entityClassName)));
                reg.putLong(RegistryMetaPaths.onlineDdlStartedAt(DEFAULT_DB_SPACE, entityClassName), DataType.REG_QWORD, startedAt.longValue());
            }
            if (heartbeat != null) {
                reg.mkdirs(RegistryMetaPaths.parentOf0(RegistryMetaPaths.onlineDdlLastHeartbeat(DEFAULT_DB_SPACE, entityClassName)));
                reg.putLong(RegistryMetaPaths.onlineDdlLastHeartbeat(DEFAULT_DB_SPACE, entityClassName), DataType.REG_QWORD, heartbeat.longValue());
            }
        } catch (Exception ex) {
            System.err.println("[DsDatabaseLocal] WARN 写 OnlineDDL state 到 Registry 失败: entity="
                    + entityClassName + " tag=" + tag + " reason=" + ex.getMessage());
        }
    }

    /**
     * 人工重试按钮（永不禁用，无状态门禁，测试专用静态重置）。 递归删除 indexes/ 根目录下所有 schema_*.dat 与
     * ids_*.set 文件（保留 ids.set legacy 不删，避免老数据全丢）。
     *
     * @param dbRoot DsDatabaseLocal 根目录
     */
    public static void forceResetSchemaMetaForTest(File dbRoot) {
        Objects.requireNonNull(dbRoot, "dbRoot cannot be null");
        File indexesDir = new File(dbRoot, "indexes");
        if (!indexesDir.exists() || !indexesDir.isDirectory()) {
            return;
        }
        deleteSchemaFilesRecursive(indexesDir);
    }

    private static void deleteSchemaFilesRecursive(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                deleteSchemaFilesRecursive(f);
                continue;
            }
            String name = f.getName();
            if (name.startsWith("ids_") && name.endsWith(".set")) {
                f.delete();
                continue;
            }
            if (name.startsWith("schema_") && name.endsWith(".dat")) {
                f.delete();
            }
        }
    }

    // =================== P18 OQ1：Online DDL 调度器（内嵌类，NG-2 0 Facade 新增） ===================
    private final class OnlineDdlCoordinator {

        private static final String SPACE = "default";
        private static final int DEFAULT_BATCH = 2048;
        private static final long DEFAULT_RETENTION = 604800000L;
        private static final String PHASE_IDLE = "IDLE";
        private static final String PHASE_PREP = "PREPARING";
        private static final String PHASE_APPLY = "APPLYING";
        private static final String PHASE_DONE = "DONE";
        private static final String PHASE_CANCEL = "CANCELLED";
        private static final String PHASE_RB = "ROLLBACK";
        private static final String CP_SUBDIR = "checkpoints";
        private final ConcurrentHashMap<String, Object> entLocks = new ConcurrentHashMap<>();
        private TableMetaStore tms;
        private ColumnarStore cs;
        private ColumnRegistry cr;
        private MessageDigest md256;
        private final char[] HEX = "0123456789abcdef".toCharArray();

        OnlineDdlCoordinator() {
            try {
                this.md256 = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new RuntimeException("SHA-256 not avail", e);
            }
        }

        private TableMetaStore tms() {
            if (tms == null) {
                tms = new TableMetaStore(root);
            }
            return tms;
        }

        private ColumnarStore cs() {
            if (cs == null) {
                cs = new ColumnarStore(root);
            }
            return cs;
        }

        private ColumnRegistry cr() {
            if (cr == null) {
                cr = new ColumnRegistry(root);
            }
            return cr;
        }

        private Object lockFor(String ent) {
            return entLocks.computeIfAbsent(ent, k -> new Object());
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void forceApply(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    String curPhase = safeGetString(reg, phaseLeaf);
                    if (PHASE_DONE.equals(curPhase)) {
                        tms().ensureMeta(clazz);
                        return;
                    }
                    long migId = ensureMigId(reg, ent);
                    AddDiff diff;
                    if (curPhase == null || PHASE_IDLE.equals(curPhase)
                            || PHASE_CANCEL.equals(curPhase) || PHASE_RB.equals(curPhase)
                            || PHASE_PREP.equals(curPhase)) {
                        diff = computeAddDiff(clazz);
                        if (diff.newCols.isEmpty()) {
                            tms().ensureMeta(clazz);
                            return;
                        }
                        writePrepareManifest(reg, ent, migId, diff);
                        tms().ensureMeta(clazz);
                    } else {
                        diff = loadDiff(clazz, reg, ent);
                        tms().ensureMeta(clazz);
                    }
                    applyAddInternal(clazz, diff, DEFAULT_BATCH, false);
                    tms().ensureMeta(clazz);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException("forceApply failed: ent=" + ent, e);
                }
            }
        }

        void cancel(String ent) {
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    String cp = safeGetString(reg, phaseLeaf);
                    if (cp == null
                            || PHASE_DONE.equals(cp)
                            || PHASE_RB.equals(cp)
                            || PHASE_IDLE.equals(cp)
                            || PHASE_CANCEL.equals(cp)) {
                        throw new IllegalArgumentException("cannot cancel od phase=" + cp);
                    }
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_CANCEL, DataType.REG_SZ);
                    String dir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                    reg.mkdirs(dir);
                    DsRegistryExtAttrsHelper.removeAllTags(reg, dir);
                    DsRegistryExtAttrsHelper.addTag(reg, dir, RegistryMetaPaths.TAG_SCHEMA_MIGRATION_CANCELLED);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("cancel od failed: ent=" + ent, e);
                }
            }
        }

        String status(String ent) {
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phase = safeGetString(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent));
                    if (phase == null) {
                        phase = PHASE_IDLE;
                    }
                    String prog = safeGetString(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent));
                    int progress = 0;
                    try {
                        if (prog != null && !prog.isBlank()) {
                            progress = Integer.parseInt(prog);

                        }
                    } catch (Exception ignored) {
                    }
                    String migIdStr = safeGetString(reg, RegistryMetaPaths.odCurrentMigId(SPACE, ent));
                    long migId = 0L;
                    try {
                        if (migIdStr != null && !migIdStr.isBlank()) {
                            migId = Long.parseLong(migIdStr);

                        }
                    } catch (Exception ignored) {
                    }
                    String ver = safeGetString(reg, RegistryMetaPaths.colSchVersion(ent));
                    long schVer = 0L;
                    try {
                        if (ver != null && !ver.isBlank()) {
                            schVer = Long.parseLong(ver);

                        }
                    } catch (Exception ignored) {
                    }
                    String startedAtS = safeGetString(reg, RegistryMetaPaths.onlineDdlStartedAt(SPACE, ent));
                    long startedAt = 0L;
                    try {
                        if (startedAtS != null && !startedAtS.isBlank()) {
                            startedAt = Long.parseLong(startedAtS);

                        }
                    } catch (Exception ignored) {
                    }
                    int cpCount = 0;
                    int tombs = 0;
                    try {
                        String manDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
                        String tombDir = RegistryMetaPaths.odTombDir(SPACE, ent);
                        java.util.List<String> cps = reg.listChildren(manDir + "/checkpoints");
                        if (cps != null) {
                            cpCount = cps.size();
                        }
                        java.util.List<String> tlist = reg.listChildren(tombDir);
                        if (tlist != null) {
                            tombs = tlist.size();
                        }
                    } catch (Exception ignored) {
                    }
                    StringBuilder sb = new StringBuilder(256);
                    sb.append('{');
                    sb.append("\"phase\":\"").append(escJson(phase)).append('"').append(',');
                    sb.append("\"progress\":").append(progress).append(',');
                    sb.append("\"checkpointCount\":").append(cpCount).append(',');
                    sb.append("\"mId\":").append(migId).append(',');
                    sb.append("\"sVer\":").append(schVer).append(',');
                    sb.append("\"startedAt\":").append(startedAt).append(',');
                    sb.append("\"tbs\":").append(tombs);
                    sb.append('}');
                    return sb.toString();
                } catch (Exception e) {
                    return "{\"phase\":\"IDLE\",\"progress\":0,\"checkpointCount\":0,\"mId\":0,\"sVer\":0,\"startedAt\":0,\"tbs\":0,\"error\":\"" + escJson(e.getMessage() == null ? "" : e.getMessage()) + "\"}";
                }
            }
        }

        private static String escJson(String s) {
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            return sb.toString();
        }

        private static String checkpointSha256(String step, long colId, byte[] payloadPrefix) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                md.update(step.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) ':');
                md.update(String.valueOf(colId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (payloadPrefix != null) {
                    md.update(payloadPrefix, 0, Math.min(32, payloadPrefix.length));
                }
                byte[] d = md.digest();
                StringBuilder sb = new StringBuilder(64);
                for (byte b : d) {
                    sb.append(String.format("%02x", b));
                }
                return sb.toString();
            } catch (Exception e) {
                return "c_" + step.hashCode() + "_" + colId;
            }
        }

        private boolean checkpointExistsAndSkip(DsRegistry reg, String ent, String cpId, String expectedSha) {
            try {
                String manDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
                String leaf = manDir.endsWith("/") ? manDir + "checkpoints/" + cpId : manDir + "/checkpoints/" + cpId;
                reg.mkdirs(RegistryMetaPaths.parentOf0(leaf));
                com.q3lives.ds.registry.RegistryEntry e = reg.get(leaf);
                if (e != null && e.isPresent()) {
                    String s = e.asString();
                    if (s != null && s.equals(expectedSha)) {
                        return true;
                    }
                }
                reg.putString(leaf, expectedSha, DataType.REG_SZ);
                return false;
            } catch (Exception ex) {
                return false;
            }
        }

        void rollback(String ent) {
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_RB, DataType.REG_SZ);
                    String dir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                    reg.mkdirs(dir);
                    DsRegistryExtAttrsHelper.removeAllTags(reg, dir);
                    DsRegistryExtAttrsHelper.addTag(reg, dir, RegistryMetaPaths.TAG_SCHEMA_ROLLBACK);
                } catch (Exception e) {
                    throw new RuntimeException("rollback od failed: ent=" + ent, e);
                }
            }
        }

        static final class NewCol {

            final String name;
            final String colKey;
            final String javaType;
            final long colId;
            final boolean nullable;
            final String dvLit;
            final byte[] dvBytes;

            NewCol(String n, String ck, String jt, long ci, boolean nb, String dv, byte[] db) {
                name = n;
                colKey = ck;
                javaType = jt;
                colId = ci;
                nullable = nb;
                dvLit = dv;
                dvBytes = db;
            }
        }

        static final class AlterCol {

            final long colId;
            final String colKey;
            final String cMode;
            final String oJType;
            final String nJType;
            final int oLen;
            final int nLen;
            final Boolean oNull;
            final Boolean nNull;
            final boolean strict;

            AlterCol(long ci, String ck, String cm, String ojt, String njt,
                    int ol, int nl, Boolean on, Boolean nn, boolean st) {
                colId = ci;
                colKey = ck;
                cMode = cm;
                oJType = ojt;
                nJType = njt;
                oLen = ol;
                nLen = nl;
                oNull = on;
                nNull = nn;
                strict = st;
            }
        }

        static final class CstSpec {
            final String cst;
            final String type;
            final long col;
            final String ck;
            final boolean uk;
            final boolean nn;
            final boolean st;
            final boolean enb;

            CstSpec(String cstName, String typ, long ci, String check, boolean uniq, boolean notn, boolean stt) {
                this(cstName, typ, ci, check, uniq, notn, stt, true);
            }

            CstSpec(String cstName, String typ, long ci, String check, boolean uniq, boolean notn, boolean stt, boolean enbL) {
                cst = cstName;
                type = typ;
                col = ci;
                ck = check;
                uk = uniq;
                nn = notn;
                st = stt;
                enb = enbL;
            }

            public String cstName() { return cst; }
            public String type() { return type; }
            public long col() { return col; }
            public String ck() { return ck; }
            public boolean uk() { return uk; }
            public boolean nn() { return nn; }
            public boolean st() { return st; }
            public boolean enb() { return enb; }
        }

        enum CstCmd {
            ADD_CST, ALT_CST, DRP_CST, ENB_CST, DSB_CST
        }

        static CstCmd toCstCmdType(String rawHead) {
            if (rawHead == null) throw new IllegalArgumentException("cmd blank");
            switch (rawHead) {
                case "ADD_CST": return CstCmd.ADD_CST;
                case "ALT_CST": return CstCmd.ALT_CST;
                case "DRP_CST": return CstCmd.DRP_CST;
                case "ENB_CST": return CstCmd.ENB_CST;
                case "DSB_CST": return CstCmd.DSB_CST;
                default: throw new IllegalArgumentException("cmd 非法: " + rawHead);
            }
        }

        static final class CstCmdRec {
            final CstCmd cmd;
            final String cst;
            final String type;
            final long col;
            final String ck;
            final boolean uk;
            final boolean nn;
            final boolean enb;
            final boolean st;

            CstCmdRec(CstCmd c, String n, String t, long ci, String check, boolean u, boolean notn, boolean en, boolean stt) {
                cmd = c;
                cst = n;
                type = t;
                col = ci;
                ck = check;
                uk = u;
                nn = notn;
                enb = en;
                st = stt;
            }

            public CstCmd cmd() { return cmd; }
            public String cst() { return cst; }
            public String type() { return type; }
            public long col() { return col; }
            public String ck() { return ck; }
            public boolean uk() { return uk; }
            public boolean nn() { return nn; }
            public boolean enb() { return enb; }
            public boolean st() { return st; }

            CstSpec toCstSpec() {
                return new CstSpec(cst, type, col, ck, uk, nn, st, enb);
            }

            boolean enbOnly() {
                return (cmd == CstCmd.ALT_CST)
                        && (type == null || type.isBlank())
                        && (ck == null || ck.isBlank())
                        && !uk && !nn
                        && !st;
            }
        }

        static CstCmdRec parseCstCmd(String raw) {
            if (raw == null || raw.isBlank())
                throw new IllegalArgumentException("raw blank");
            String[] segs = raw.split("\\|");
            if (segs.length < 4)
                throw new IllegalArgumentException("段数不足: " + segs.length);
            CstCmd cmd = toCstCmdType(segs[0]);
            String cst = null, type = null, ck = null;
            long col = -1L;
            boolean uk = false, nn = false, enb = true, st = false;
            boolean hasCst = false, hasType = false, hasCol = false;
            java.util.Set<String> validSegs = new java.util.HashSet<>(java.util.Arrays.asList(
                    "cst","type","col","ck","uk","nn","enb","st"));
            for (int i = 1; i < segs.length; i++) {
                String seg = segs[i];
                int eq = seg.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("段无=: " + seg);
                String k = seg.substring(0, eq).trim();
                String v = seg.substring(eq + 1);
                if (!validSegs.contains(k)) throw new IllegalArgumentException("未知段名: " + k);
                if (v == null) v = "";
                switch (k) {
                    case "cst":
                        if (v.isBlank()) throw new IllegalArgumentException("cstName blank");
                        if (!v.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
                            throw new IllegalArgumentException("cstName nonAlnum: " + v);
                        cst = v; hasCst = true; break;
                    case "type":
                        if (!"UNIQUE".equals(v) && !"CHECK".equals(v) && !"NN".equals(v))
                            throw new IllegalArgumentException("type 非法: " + v);
                        type = v; hasType = true; break;
                    case "col":
                        try { col = Long.parseLong(v); }
                        catch (NumberFormatException nfe) { throw new IllegalArgumentException("col nonNum: " + v); }
                        hasCol = true; break;
                    case "ck":
                        ck = v.isEmpty() ? null : v; break;
                    case "uk":
                        if (!"true".equals(v) && !"false".equals(v))
                            throw new IllegalArgumentException("bool 非法: uk=" + v);
                        uk = Boolean.parseBoolean(v); break;
                    case "nn":
                        if (!"true".equals(v) && !"false".equals(v))
                            throw new IllegalArgumentException("bool 非法: nn=" + v);
                        nn = Boolean.parseBoolean(v); break;
                    case "enb":
                        if (!"true".equals(v) && !"false".equals(v))
                            throw new IllegalArgumentException("bool 非法: enb=" + v);
                        enb = Boolean.parseBoolean(v); break;
                    case "st":
                        if (!"true".equals(v) && !"false".equals(v))
                            throw new IllegalArgumentException("bool 非法: st=" + v);
                        st = Boolean.parseBoolean(v); break;
                }
            }
            if (!hasCst) throw new IllegalArgumentException("缺 cst 段");
            if (cmd == CstCmd.ADD_CST || cmd == CstCmd.ALT_CST) {
                if (!hasType) throw new IllegalArgumentException("缺 type 段");
                if (!hasCol) throw new IllegalArgumentException("缺 col 段");
                if ("UNIQUE".equals(type) && !uk)
                    throw new IllegalArgumentException("UNIQUE 需 uk=true");
                if ("CHECK".equals(type) && (ck == null || ck.length() < 3))
                    throw new IllegalArgumentException("CHECK 需 ck 非空");
                if ("NN".equals(type) && !nn)
                    throw new IllegalArgumentException("NN 需 nn=true");
            }
            return new CstCmdRec(cmd, cst, type, col, ck, uk, nn, enb, st);
        }

        static boolean validateCstSpec(CstSpec s, TableMetaStore.TableMeta tm) {
            if (tm == null) throw new IllegalArgumentException("tm null");
            if (s == null) throw new IllegalArgumentException("spec null");
            if (s.cst == null || s.cst.isBlank() ||
                    !s.cst.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
                throw new IllegalArgumentException("cst nonAlnum");
            if (s.col < 0) throw new IllegalArgumentException("col<0");
            if (s.type == null || s.type.isBlank())
                throw new IllegalArgumentException("type blank");
            if ("UNIQUE".equals(s.type) && !s.uk)
                throw new IllegalArgumentException("UNIQUE→uk true");
            if ("CHECK".equals(s.type) && (s.ck == null || s.ck.length() < 3))
                throw new IllegalArgumentException("CHECK→ck 非空");
            if ("NN".equals(s.type) && !s.nn)
                throw new IllegalArgumentException("NN→nn true");
            if (tm.columns == null || tm.columns.isEmpty())
                throw new IllegalArgumentException("tm 无 columns");
            boolean found = false;
            for (TableMetaStore.ColumnDef cd : tm.columns) if (cd.colId == s.col) { found = true; break; }
            if (!found) throw new IllegalArgumentException("tm 无 col: " + s.col);
            return true;
        }

        static void execCstCmd(CstCmdRec cmd, TableMetaStore.Backend backend,
                               TableMetaStore.TableMeta tm) throws Exception {
            if (cmd == null) throw new IllegalArgumentException("cmd null");
            if (backend == null) throw new IllegalArgumentException("backend null");
            CstSpec spec = cmd.toCstSpec();
            if (cmd.cmd() == CstCmd.ADD_CST || cmd.cmd() == CstCmd.ALT_CST) {
                validateCstSpec(spec, tm);
            }
            String ent = tm != null ? tm.entityClassName : null;
            if (ent == null || ent.isBlank()) throw new IllegalArgumentException("tm.entity blank");
            java.lang.reflect.Method updC = null;
            java.lang.reflect.Method enbC = null;
            java.lang.reflect.Method altC = null;
            java.lang.reflect.Method drpC = null;
            try {
                updC = backend.getClass().getDeclaredMethod("updateConstraint", String.class, Object.class);
                enbC = backend.getClass().getDeclaredMethod("enableConstraint", String.class, String.class, boolean.class);
                altC = backend.getClass().getDeclaredMethod("alterCst", String.class, Object.class, boolean.class);
                drpC = backend.getClass().getDeclaredMethod("dropCst", String.class, String.class);
                updC.setAccessible(true); enbC.setAccessible(true);
                altC.setAccessible(true); drpC.setAccessible(true);
            } catch (Exception re) {
                throw new IllegalArgumentException("backend reflect fail: " + re.getMessage(), re);
            }
            switch (cmd.cmd()) {
                case ADD_CST:
                    String existType = null;
                    try {
                        java.lang.reflect.Method rs = backend.getClass().getDeclaredMethod("readStr", String.class);
                        rs.setAccessible(true);
                        existType = (String) rs.invoke(backend,
                                RegistryMetaPaths.odCstLeaf(ent, cmd.cst(), RegistryMetaPaths.OD_CST_TYPE));
                    } catch (Exception ignored) {}
                    if (existType != null && !existType.equals(spec.type())) {
                        throw new IllegalArgumentException("type 已存在异 type");
                    }
                    final boolean useEnb = cmd.enb();
                    java.util.function.Supplier<Boolean> enbSup = () -> Boolean.valueOf(useEnb);
                    try {
                        java.lang.reflect.Method updSkip = backend.getClass().getDeclaredMethod("updateConstraint",
                                String.class, Object.class, boolean.class, java.util.function.Supplier.class);
                        updSkip.setAccessible(true);
                        updSkip.invoke(backend, ent, spec, Boolean.TRUE, enbSup);
                    } catch (NoSuchMethodException nsme) {
                        try {
                            java.lang.reflect.Method updSkip2 = backend.getClass().getDeclaredMethod("updateConstraint",
                                    String.class, Object.class, boolean.class);
                            updSkip2.setAccessible(true);
                            updSkip2.invoke(backend, ent, spec, Boolean.TRUE);
                            if (!cmd.enb()) enbC.invoke(backend, ent, cmd.cst(), Boolean.FALSE);
                        } catch (NoSuchMethodException nsme2) {
                            updC.invoke(backend, ent, spec);
                            if (!cmd.enb()) enbC.invoke(backend, ent, cmd.cst(), Boolean.FALSE);
                        }
                    }
                    break;
                case ALT_CST:
                    altC.invoke(backend, ent, spec, Boolean.valueOf(cmd.enbOnly()));
                    break;
                case DRP_CST:
                    drpC.invoke(backend, ent, cmd.cst());
                    break;
                case ENB_CST:
                    enbC.invoke(backend, ent, cmd.cst(), Boolean.TRUE);
                    break;
                case DSB_CST:
                    enbC.invoke(backend, ent, cmd.cst(), Boolean.FALSE);
                    break;
                default:
                    throw new IllegalArgumentException("cmd 非法: " + cmd.cmd());
            }
        }

        @SuppressWarnings({"rawtypes","unchecked"})
        static final class DdlCstPipe {

            static java.util.List<String> prepare(String ent, Object[] cstArr, Object backend) throws Exception {
                if (ent == null || ent.isBlank()) throw new IllegalArgumentException("entity blank");
                if (cstArr == null) throw new IllegalArgumentException("cstArr null");
                if (backend == null) throw new IllegalArgumentException("backend null");
                java.util.Set<String> seen = new java.util.HashSet<>();
                long base = System.currentTimeMillis() * 100L;
                java.util.List<String> migList = new java.util.ArrayList<>(cstArr.length);
                Class<?> backendCls = backend.getClass();
                java.lang.reflect.Method wls = findWriteLeafString(backendCls);
                for (int i = 0; i < cstArr.length; i++) {
                    Object c = cstArr[i];
                    if (c == null) throw new IllegalArgumentException("cst[" + i + "] null");
                    java.lang.reflect.Method gc = c.getClass().getMethod("cstName"); gc.setAccessible(true);
                    String cst = (String) gc.invoke(c);
                    if (cst == null || !cst.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_'))
                        throw new IllegalArgumentException("cst nonAlnum: " + cst);
                    if (!seen.add(cst)) throw new IllegalArgumentException("cst 重名: " + cst);
                    java.lang.reflect.Method gt = c.getClass().getMethod("type"); gt.setAccessible(true);
                    String type = (String) gt.invoke(c);
                    java.lang.reflect.Method gcol = c.getClass().getMethod("col"); gcol.setAccessible(true);
                    long col = ((Long) gcol.invoke(c)).longValue();
                    java.lang.reflect.Method gck = c.getClass().getMethod("ck"); gck.setAccessible(true);
                    String ck = (String) gck.invoke(c);
                    java.lang.reflect.Method guk = c.getClass().getMethod("uk"); guk.setAccessible(true);
                    boolean uk = ((Boolean) guk.invoke(c)).booleanValue();
                    java.lang.reflect.Method gnn = c.getClass().getMethod("nn"); gnn.setAccessible(true);
                    boolean nn = ((Boolean) gnn.invoke(c)).booleanValue();
                    java.lang.reflect.Method gst = c.getClass().getMethod("st"); gst.setAccessible(true);
                    boolean st = ((Boolean) gst.invoke(c)).booleanValue();
                    java.lang.reflect.Method genb = null;
                    boolean enb = true;
                    try {
                        genb = c.getClass().getMethod("enb");
                        if (genb.getReturnType() == boolean.class || genb.getReturnType() == Boolean.class) {
                            genb.setAccessible(true);
                            enb = ((Boolean) genb.invoke(c)).booleanValue();
                        }
                    } catch (NoSuchMethodException ignored) {}
                    if ("UNIQUE".equals(type) && !uk)
                        throw new IllegalArgumentException("UNIQUE 需 uk=true: cst=" + cst);
                    if ("CHECK".equals(type) && st && (ck == null || ck.isBlank()))
                        throw new IllegalArgumentException("CHECK strict ck 空: cst=" + cst);
                    String migId = "" + (base + i);
                    String leaf = RegistryMetaPaths.cstManifestNameListDir("default", ent) + "/" + migId;
                    String val = "type=" + (type == null ? "" : type)
                            + "|cst=" + cst
                            + "|col=" + col
                            + "|uk=" + uk
                            + "|nn=" + nn
                            + "|ck=" + (ck == null ? "" : ck)
                            + "|st=" + st
                            + "|enb=" + enb;
                    wls.invoke(backend, leaf, val);
                    migList.add(leaf);
                }
                return migList;
            }

            static void execute(String ent, Object[] cstArr, java.util.List<String> migList, Object backend) throws Exception {
                if (ent == null || ent.isBlank()) throw new IllegalArgumentException("entity blank");
                if (cstArr == null) throw new IllegalArgumentException("cstArr null");
                if (backend == null) throw new IllegalArgumentException("backend null");
                Class<?> backendCls = backend.getClass();
                java.lang.reflect.Method listC = backendCls.getDeclaredMethod("listCsts", String.class);
                listC.setAccessible(true);
                java.util.List<Object> olds = (java.util.List<Object>) listC.invoke(backend, ent);
                java.util.Map<String,String> oldTypes = new java.util.HashMap<>();
                if (olds != null) {
                    for (Object o : olds) {
                        java.lang.reflect.Method n = o.getClass().getMethod("cstName"); n.setAccessible(true);
                        String cst = (String) n.invoke(o);
                        java.lang.reflect.Method t = o.getClass().getMethod("type"); t.setAccessible(true);
                        String tp = (String) t.invoke(o);
                        if (cst != null) oldTypes.put(cst, tp == null ? "" : tp);
                    }
                }
                String tableName = lastSimpleName(ent);
                Class<?> eqCls = null;
                java.lang.reflect.Constructor<?> eqCtor3 = null;
                java.lang.reflect.Method putU = null;
                Object sharedEqI = null;
                File eqRoot = new File(System.getProperty("p2p.db.home"));
                try {
                    eqCls = Class.forName("com.q3lives.ds.database.index.DsEqIndexStore");
                    eqCtor3 = eqCls.getDeclaredConstructor(File.class, String.class, String.class);
                    eqCtor3.setAccessible(true);
                    for (java.lang.reflect.Method m : eqCls.getDeclaredMethods()) {
                        if (m.getName().equals("putIndexUnique") && m.getParameterCount() == 2) {
                            putU = m; break;
                        }
                    }
                    if (eqCtor3 != null) {
                        sharedEqI = eqCtor3.newInstance(eqRoot, "cst_uk", tableName + "_uk_all");
                    }
                } catch (ClassNotFoundException | NoSuchMethodException ignored) {}
                if (putU != null) putU.setAccessible(true);
                java.lang.reflect.Method upd = backendCls.getDeclaredMethod("updateConstraint", String.class, Object.class, boolean.class);
                upd.setAccessible(true);
                for (int i = 0; i < cstArr.length; i++) {
                    Object c = cstArr[i];
                    java.lang.reflect.Method gc = c.getClass().getMethod("cstName"); gc.setAccessible(true);
                    String cst = (String) gc.invoke(c);
                    java.lang.reflect.Method gt = c.getClass().getMethod("type"); gt.setAccessible(true);
                    String type = (String) gt.invoke(c);
                    java.lang.reflect.Method gcol = c.getClass().getMethod("col"); gcol.setAccessible(true);
                    long col = ((Long) gcol.invoke(c)).longValue();
                    String ot = oldTypes.get(cst);
                    if (ot != null && type != null && !ot.equals(type)) {
                        throw new IllegalArgumentException("type 已存在异 type: old=" + ot + " new=" + type
                                + " cst=" + cst + " migList=" + migList);
                    }
                    upd.invoke(backend, ent, c, Boolean.FALSE);
                    if ("UNIQUE".equals(type) && sharedEqI != null && putU != null) {
                        java.lang.reflect.Method guk = c.getClass().getMethod("uk"); guk.setAccessible(true);
                        boolean uk = ((Boolean) guk.invoke(c)).booleanValue();
                        if (uk) {
                            long rowId = 1L + i;
                            Object kv = Long.valueOf(rowId * 13L + col);
                            try {
                                putU.invoke(sharedEqI, kv, Long.valueOf(rowId));
                            } catch (java.lang.reflect.InvocationTargetException ite) {
                                Throwable cause = ite.getCause() == null ? ite : ite.getCause();
                                throw new RuntimeException("UK 冲突: cst=" + cst + " key=" + kv
                                        + " migList=" + migList, cause);
                            }
                        }
                    }
                }
            }

            static void commit(String ent, java.util.List<String> migList, Object backend) throws Exception {
                commit(ent, migList, true, backend);
            }

            static void commit(String ent, java.util.List<String> migList, boolean keepCa, Object backend) throws Exception {
                if (ent == null || ent.isBlank()) throw new IllegalArgumentException("entity blank");
                if (migList == null) throw new IllegalArgumentException("migList null");
                if (backend == null) throw new IllegalArgumentException("backend null");
                Class<?> backendCls = backend.getClass();
                java.lang.reflect.Method rs = backendCls.getDeclaredMethod("readStr", String.class); rs.setAccessible(true);
                java.lang.reflect.Method wls = findWriteLeafString(backendCls);
                Class<?> yamlSupCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryBackend$YamlSupplier");
                for (String m : migList) {
                    if (m == null) continue;
                    String val;
                    try { val = (String) rs.invoke(backend, m); } catch (Exception ignored) { val = null; }
                    String cst = null;
                    if (val != null) {
                        for (String seg : val.split("\\|")) {
                            if (seg.startsWith("cst=")) cst = seg.substring(4);
                        }
                    }
                    if (cst != null && keepCa) {
                        String caLeaf = RegistryMetaPaths.odCstLeaf(ent, cst, RegistryMetaPaths.OD_CST_CREATED_AT);
                        String existCa;
                        try { existCa = (String) rs.invoke(backend, caLeaf); } catch (Exception ignored) { existCa = null; }
                        if (existCa == null || existCa.isBlank()) {
                            wls.invoke(backend, caLeaf, String.valueOf(System.currentTimeMillis()));
                        }
                    }
                    try {
                        java.lang.reflect.Method rl;
                        try {
                            rl = backendCls.getDeclaredMethod("removeLeaf", String.class, yamlSupCls);
                        } catch (NoSuchMethodException nsme) {
                            rl = backendCls.getSuperclass().getDeclaredMethod("removeLeaf", String.class, yamlSupCls);
                        }
                        rl.setAccessible(true);
                        Object ys = java.lang.reflect.Proxy.newProxyInstance(yamlSupCls.getClassLoader(),
                                new Class<?>[] { yamlSupCls },
                                (p, mth, a) -> Boolean.FALSE);
                        rl.invoke(backend, m, ys);
                    } catch (Exception ignored) {}
                }
            }

            static void rollback(String ent, java.util.List<String> migList, Object backend) throws Exception {
                if (ent == null || ent.isBlank()) throw new IllegalArgumentException("entity blank");
                if (migList == null) throw new IllegalArgumentException("migList null");
                if (backend == null) throw new IllegalArgumentException("backend null");
                Class<?> backendCls = backend.getClass();
                java.lang.reflect.Method drop = backendCls.getDeclaredMethod("dropCst", String.class, String.class);
                drop.setAccessible(true);
                java.lang.reflect.Method rs = backendCls.getDeclaredMethod("readStr", String.class); rs.setAccessible(true);
                Class<?> yamlSupCls = Class.forName("com.q3lives.ds.registry.meta.AbstractRegistryBackend$YamlSupplier");
                for (String m : migList) {
                    if (m == null) continue;
                    String val;
                    try { val = (String) rs.invoke(backend, m); } catch (Exception ignored) { val = null; }
                    String cst = null;
                    if (val != null) {
                        for (String seg : val.split("\\|")) {
                            if (seg.startsWith("cst=")) cst = seg.substring(4);
                        }
                    }
                    if (cst != null) {
                        try { drop.invoke(backend, ent, cst); } catch (Exception ignored) {}
                    }
                    try {
                        java.lang.reflect.Method rl;
                        try {
                            rl = backendCls.getDeclaredMethod("removeLeaf", String.class, yamlSupCls);
                        } catch (NoSuchMethodException nsme) {
                            rl = backendCls.getSuperclass().getDeclaredMethod("removeLeaf", String.class, yamlSupCls);
                        }
                        rl.setAccessible(true);
                        Object ys = java.lang.reflect.Proxy.newProxyInstance(yamlSupCls.getClassLoader(),
                                new Class<?>[] { yamlSupCls },
                                (p, mth, a) -> Boolean.FALSE);
                        rl.invoke(backend, m, ys);
                    } catch (Exception ignored) {}
                }
                String tableName = lastSimpleName(ent);
                Class<?> eqCls = null;
                java.lang.reflect.Constructor<?> eqCtor3 = null;
                java.lang.reflect.Method clearP = null;
                File eqRoot = new File(System.getProperty("p2p.db.home"));
                try {
                    eqCls = Class.forName("com.q3lives.ds.database.index.DsEqIndexStore");
                    eqCtor3 = eqCls.getDeclaredConstructor(File.class, String.class, String.class);
                    eqCtor3.setAccessible(true);
                    for (java.lang.reflect.Method m : eqCls.getDeclaredMethods()) {
                        if (m.getName().equals("clearPartition") && m.getParameterCount() == 1) {
                            clearP = m; break;
                        }
                    }
                } catch (ClassNotFoundException | NoSuchMethodException ignored) {}
                if (clearP != null && eqCtor3 != null) {
                    clearP.setAccessible(true);
                    try {
                        Object eqI = eqCtor3.newInstance(eqRoot, "cst_uk", tableName + "_uk_all");
                        clearP.invoke(eqI, Integer.valueOf(0));
                    } catch (Exception ignored) {}
                }
            }

            private static java.lang.reflect.Method findWriteLeafString(Class<?> backendCls) throws Exception {
                java.lang.reflect.Method wls = backendCls.getDeclaredMethod("putS", String.class, String.class);
                wls.setAccessible(true);
                return wls;
            }

            private static String lastSimpleName(String ent) {
                if (ent == null) return "";
                int dot = ent.lastIndexOf('.');
                return (dot < 0 || dot + 1 >= ent.length()) ? ent : ent.substring(dot + 1);
            }
        }

        static final class AddDiff {

            final String fromSig;
            final String toSig;
            final List<NewCol> newCols;

            AddDiff(String a, String b, List<NewCol> c) {
                fromSig = a;
                toSig = b;
                newCols = c;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        List<AlterCol> prepareAlter(Class<? extends DsTableAdapter> clazz, boolean dryRun, boolean strict) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    TableMetaStore tmsInst = tms();
                    TableMetaStore.TableMeta preCheck = tmsInst.getMeta(ent);
                    if (preCheck == null || preCheck.columns == null || preCheck.columns.isEmpty()) {
                        tmsInst.ensureMeta(clazz);
                    }
                    TableMetaStore.TableMeta stored = tmsInst.getMeta(ent);
                    TableMetaStore.TableMeta fresh = buildFresh(clazz);
                    java.util.Map<String, long[]> nm = new java.util.HashMap<>();
                    if (stored.columns != null) {
                        for (TableMetaStore.ColumnDef s : stored.columns) {
                            if (s != null && s.name != null) {
                                nm.put(s.name, new long[]{s.colId});
                            }
                        }
                    }
                    if (fresh.columns != null) {
                        for (TableMetaStore.ColumnDef f : fresh.columns) {
                            if (f != null && f.name != null && nm.containsKey(f.name)) {
                                long[] p = nm.get(f.name);
                                if (p != null && p.length > 0) {
                                    f.colId = p[0];
                                }
                            }
                        }
                    }
                    java.util.Set<Long> sIds = new java.util.HashSet<>();
                    java.util.Map<Long, TableMetaStore.ColumnDef> sMap = new java.util.HashMap<>();
                    if (stored.columns != null) {
                        for (TableMetaStore.ColumnDef c : stored.columns) {
                            sIds.add(c.colId);
                            sMap.put(c.colId, c);
                        }
                    }
                    java.util.Set<Long> fIds = new java.util.HashSet<>();
                    java.util.Map<Long, TableMetaStore.ColumnDef> fMap = new java.util.HashMap<>();
                    if (fresh.columns != null) {
                        for (TableMetaStore.ColumnDef c : fresh.columns) {
                            fIds.add(c.colId);
                            fMap.put(c.colId, c);
                        }
                    }
                    if (!sIds.equals(fIds)) {
                        throw new IllegalArgumentException("ALTER cannot change colId");
                    }
                    for (java.util.Map.Entry<Long, TableMetaStore.ColumnDef> e : sMap.entrySet()) {
                        TableMetaStore.ColumnDef sc0 = e.getValue();
                        TableMetaStore.ColumnDef fc0 = fMap.get(e.getKey());
                        if (!Objects.equals(sc0.name, fc0.name)) {
                            throw new IllegalArgumentException("ALTER cannot change colId");
                        }
                    }
                    List<AlterCol> res = new ArrayList<>();
                    int idx = 0;
                    long migId = -1L;
                    if (!dryRun) {
                        migId = ensureMigId(reg, ent);
                    }
                    String fromSig = (stored.signature == null) ? "" : stored.signature;
                    String toSig = computeSigLocal(fresh);
                    if (stored.columns != null) {
                        for (TableMetaStore.ColumnDef sc : stored.columns) {
                            TableMetaStore.ColumnDef fc = fMap.get(sc.colId);
                            if (fc == null) {
                                continue;
                            }
                            boolean oNullB = colNull(sc);
                            boolean nNullB = colNull(fc);
                            boolean nDiff = oNullB != nNullB;
                            boolean lDiff = sc.length != fc.length;
                            boolean pDiff = sc.precision != fc.precision;
                            boolean sDiff = sc.scale != fc.scale;
                            String scJt = sc.javaType == null ? "" : sc.javaType;
                            String fcJt = fc.javaType == null ? "" : fc.javaType;
                            boolean jDiff = !scJt.isEmpty() && !fcJt.isEmpty() && !scJt.equals(fcJt);
                            if (!nDiff && !lDiff && !pDiff && !sDiff && !jDiff) {
                                continue;
                            }
                            boolean safeUp = isSafeUpcast(scJt, fcJt);
                            String cMode;
                            if (nDiff && !lDiff && !pDiff && !sDiff && !jDiff) {
                                cMode = "METADATA_ONLY";
                            } else if ((lDiff && fc.length > sc.length) || (pDiff && fc.precision >= sc.precision) || (sDiff && fc.scale >= sc.scale) || (jDiff && safeUp)) {
                                cMode = "SAFE_UPCAST";
                            } else if (lDiff || pDiff || sDiff || jDiff) {
                                if (strict) {
                                    throw new IllegalArgumentException("narrowing requires strict=false: col=" + sc.colKey + " oldLen=" + sc.length + " newLen=" + fc.length + " oldType=" + sc.javaType + " newType=" + fc.javaType + " oldPrec=" + sc.precision + " newPrec=" + fc.precision + " oldScale=" + sc.scale + " newScale=" + fc.scale);
                                }
                                cMode = "NARROW_WARN";
                            } else {
                                cMode = "SAFE_UPCAST";
                            }
                            AlterCol ac = new AlterCol(sc.colId, sc.colKey, cMode,
                                    sc.javaType, fc.javaType, sc.length, fc.length, sc.nullable, fc.nullable, strict);
                            res.add(ac);
                            if (!dryRun) {
                                String mStr = String.valueOf(migId);
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "colId"), String.valueOf(sc.colId));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "colKey"), sc.colKey == null ? "" : sc.colKey);
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, RegistryMetaPaths.OD_ALTER_CAST), cMode);
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, RegistryMetaPaths.OD_ALTER_OLD_JTYPE), sc.javaType == null ? "" : sc.javaType);
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "newJavaType"), fc.javaType == null ? "" : fc.javaType);
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "oldLength"), String.valueOf(sc.length));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "newLength"), String.valueOf(fc.length));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "oldNullable"), sc.nullable == null ? "" : String.valueOf(sc.nullable));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "newNullable"), fc.nullable == null ? "" : String.valueOf(fc.nullable));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "oldPrecision"), String.valueOf(sc.precision));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "newPrecision"), String.valueOf(fc.precision));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "oldScale"), String.valueOf(sc.scale));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "newScale"), String.valueOf(fc.scale));
                                writeStr(reg, RegistryMetaPaths.odManifestAlterIdx(SPACE, ent, mStr, idx, "strict"), String.valueOf(strict));
                                idx++;
                            }
                        }
                    }
                    if (!dryRun) {
                        String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                        String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
                        reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                        writeStr(reg, phaseLeaf, PHASE_PREP);
                        writeStr(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent), String.valueOf(migId));
                        writeStr(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent), fromSig);
                        writeStr(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent), toSig);
                        writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "0");
                        String rootDir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                        reg.mkdirs(rootDir);
                        DsRegistryExtAttrsHelper.removeAllTags(reg, rootDir);
                        DsRegistryExtAttrsHelper.addTag(reg, rootDir, RegistryMetaPaths.TAG_SCHEMA_MIGRATING);
                        String cpDir = mDir + "/" + CP_SUBDIR;
                        reg.mkdirs(cpDir);
                        md256.reset();
                        md256.update(longBE(migId));
                        md256.update(fromSig.getBytes(StandardCharsets.UTF_8));
                        md256.update(toSig.getBytes(StandardCharsets.UTF_8));
                        for (AlterCol ac : res) {
                            md256.update((ac.colId + ":" + ac.cMode).getBytes(StandardCharsets.UTF_8));
                        }
                        String sha = hexOf(md256.digest());
                        String cp0 = cpDir + "/0";
                        reg.mkdirs(RegistryMetaPaths.parentOf0(cp0));
                        reg.putString(cp0, sha, DataType.REG_SZ);
                    }
                    return res;
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("prepareAlter failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        int applyAlter(Class<? extends DsTableAdapter> clazz, int batchSize) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    String curPhase = safeGetString(reg, phaseLeaf);
                    if (PHASE_DONE.equals(curPhase)) {
                        tms().ensureMeta(clazz);
                        return 0;
                    }
                    if (curPhase == null || PHASE_IDLE.equals(curPhase) || PHASE_CANCEL.equals(curPhase) || PHASE_RB.equals(curPhase)) {
                        prepareAlter(clazz, false, true);
                        curPhase = safeGetString(reg, phaseLeaf);
                    }
                    String migIdStr = safeGetString(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent));
                    if (migIdStr == null || migIdStr.isBlank()) {
                        throw new IllegalArgumentException("no migId for alter");
                    }
                    String altDir = RegistryMetaPaths.odManifestAlterDir(SPACE, ent, migIdStr);
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_APPLY, DataType.REG_SZ);
                    Field backendF = TableMetaStore.class.getDeclaredField("backend");
                    backendF.setAccessible(true);
                    Object backend = backendF.get(tms());
                    Class<?> bc = null;
                    for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) {
                        if ("Backend".equals(ic.getSimpleName())) {
                            bc = ic;
                            break;
                        }
                    }
                    java.lang.reflect.Method upd = bc.getDeclaredMethod("updateColumnDef", String.class, long.class, TableMetaStore.ColumnDef.class);
                    upd.setAccessible(true);
                    int count = 0;
                    for (int i = 0; i < 4096; i++) {
                        String cIdS = safeGetString(reg, altDir + "/" + i + "/colId");
                        if (cIdS == null || cIdS.isBlank()) {
                            break;
                        }
                        long colId = Long.parseLong(cIdS);
                        String cMode = safeGetString(reg, altDir + "/" + i + "/" + RegistryMetaPaths.OD_ALTER_CAST);
                        String oJType = safeGetString(reg, altDir + "/" + i + "/" + RegistryMetaPaths.OD_ALTER_OLD_JTYPE);
                        String nJType = safeGetString(reg, altDir + "/" + i + "/newJavaType");
                        String oLenS = safeGetString(reg, altDir + "/" + i + "/oldLength");
                        String nLenS = safeGetString(reg, altDir + "/" + i + "/newLength");
                        String nNullS = safeGetString(reg, altDir + "/" + i + "/newNullable");
                        String nPrecS = safeGetString(reg, altDir + "/" + i + "/newPrecision");
                        String nScaleS = safeGetString(reg, altDir + "/" + i + "/newScale");
                        String colKey = safeGetString(reg, altDir + "/" + i + "/colKey");
                        int oLen = 0;
                        try {
                            if (oLenS != null && !oLenS.isBlank()) {
                                oLen = Integer.parseInt(oLenS);
                            }
                        } catch (Exception ignore) {
                        }
                        int nLen = 0;
                        try {
                            if (nLenS != null && !nLenS.isBlank()) {
                                nLen = Integer.parseInt(nLenS);

                            }
                        } catch (Exception ignore) {
                        }
                        Boolean nNull = null;
                        if (nNullS != null && !nNullS.isBlank()) {
                            nNull = Boolean.valueOf(Boolean.parseBoolean(nNullS));
                        }
                        int nPrec = 0;
                        try {
                            if (nPrecS != null && !nPrecS.isBlank()) {
                                nPrec = Integer.parseInt(nPrecS);
                            }
                        } catch (Exception ignore) {
                        }
                        int nScale = 0;
                        try {
                            if (nScaleS != null && !nScaleS.isBlank()) {
                                nScale = Integer.parseInt(nScaleS);
                            }
                        } catch (Exception ignore) {
                        }
                        if ("METADATA_ONLY".equals(cMode)) {
                            TableMetaStore.ColumnDef nd = new TableMetaStore.ColumnDef();
                            nd.colId = colId;
                            nd.colKey = colKey;
                            nd.javaType = nJType;
                            nd.length = nLen;
                            nd.nullable = nNull;
                            nd.precision = nPrec;
                            nd.scale = nScale;
                            upd.invoke(backend, ent, Long.valueOf(colId), nd);
                            count++;
                        } else if ("SAFE_UPCAST".equals(cMode) || "NARROW_WARN".equals(cMode)) {
                            TableMetaStore.ColumnDef nd = new TableMetaStore.ColumnDef();
                            nd.colId = colId;
                            nd.colKey = colKey;
                            nd.javaType = nJType;
                            nd.length = nLen;
                            nd.nullable = nNull;
                            nd.precision = nPrec;
                            nd.scale = nScale;
                            upd.invoke(backend, ent, Long.valueOf(colId), nd);
                            TableMetaStore.TableMeta sm = tms().getMeta(ent);
                            String colName = null;
                            if (sm != null && sm.columns != null) {
                                for (TableMetaStore.ColumnDef c : sm.columns) {
                                    if (c.colId == colId) {
                                        colName = c.name;
                                        break;
                                    }
                                }
                            }
                            if (colName != null && nLen > 0 && nLen != oLen) {
                                String cpDir0 = altDir + "/" + CP_SUBDIR;
                                reg.mkdirs(cpDir0);
                                long rid = 1;
                                long maxRow = 200000L;
                                long processed = 0;
                                long total = -1;
                                long batchIdx = 0;
                                long emptyRun = 0;
                                java.util.List<Long> batch = new java.util.ArrayList<>(Math.max(1, batchSize));
                                while (rid <= maxRow) {
                                    byte[] probe = null;
                                    try {
                                        probe = cs().getValue(ent, colName, rid);
                                    } catch (Exception ignore) {
                                    }
                                    if (probe == null || probe.length == 0) {
                                        emptyRun++;
                                        if (emptyRun > 1024L) {
                                            break;

                                        }
                                        rid++;
                                        continue;
                                    }
                                    emptyRun = 0;
                                    batch.add(Long.valueOf(rid));
                                    if (batch.size() >= Math.max(1, batchSize) || rid == maxRow) {
                                        String cpId = "LEN-" + colId + "-" + batchIdx;
                                        String cpLeaf = cpDir0 + "/" + cpId;
                                        String cpDone = safeGetString(reg, cpLeaf);
                                        if (cpDone == null || cpDone.isBlank()) {
                                            for (Long br : batch) {
                                                long brr = br.longValue();
                                                try {
                                                    byte[] ov = cs().getValue(ent, colName, brr);
                                                    if (ov != null && ov.length > 0) {
                                                        boolean jd = (oJType != null && nJType != null && !oJType.isBlank() && !nJType.isBlank() && !oJType.equals(nJType));
                                                        byte[] nv = jd ? castBytes(ov, oLen, nLen, oJType, nJType) : java.util.Arrays.copyOf(ov, nLen);
                                                        cs().putValue(ent, colName, brr, nv);
                                                        processed++;
                                                    }
                                                } catch (Exception ignore) {
                                                }
                                            }
                                            String sha = Long.toString(processed) + ":" + batch.get(0);
                                            reg.mkdirs(RegistryMetaPaths.parentOf0(cpLeaf));
                                            reg.putString(cpLeaf, sha, DataType.REG_SZ);
                                        }
                                        batch.clear();
                                        batchIdx++;
                                        if (total > 0) {
                                            int pct = (int) Math.min(99L, (processed * 100L) / total);
                                            writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), String.valueOf(pct));
                                        }
                                    }
                                    rid++;
                                }
                                if (!batch.isEmpty()) {
                                    String cpId = "LEN-" + colId + "-" + batchIdx;
                                    String cpLeaf = cpDir0 + "/" + cpId;
                                    String cpDone = safeGetString(reg, cpLeaf);
                                    if (cpDone == null || cpDone.isBlank()) {
                                        for (Long br : batch) {
                                            long brr = br.longValue();
                                            try {
                                                byte[] ov = cs().getValue(ent, colName, brr);
                                                if (ov != null && ov.length > 0) {
                                                    boolean jd = (oJType != null && nJType != null && !oJType.isBlank() && !nJType.isBlank() && !oJType.equals(nJType));
                                                    byte[] nv = jd ? castBytes(ov, oLen, nLen, oJType, nJType) : java.util.Arrays.copyOf(ov, nLen);
                                                    cs().putValue(ent, colName, brr, nv);
                                                    processed++;
                                                }
                                            } catch (Exception ignore) {
                                            }
                                        }
                                        String sha = Long.toString(processed) + ":" + batch.get(0);
                                        reg.mkdirs(RegistryMetaPaths.parentOf0(cpLeaf));
                                        reg.putString(cpLeaf, sha, DataType.REG_SZ);
                                    }
                                    batch.clear();
                                    batchIdx++;
                                    if (total > 0) {
                                        int pct = (int) Math.min(99L, (processed * 100L) / total);
                                        writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), String.valueOf(pct));
                                    }
                                }
                            }
                            count++;
                        }
                    }
                    reg.putString(phaseLeaf, PHASE_DONE, DataType.REG_SZ);
                    writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "100");
                    return count;
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("applyAlter failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void rollbackAlter(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_RB, DataType.REG_SZ);
                    String migIdStr = safeGetString(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent));
                    if (migIdStr == null || migIdStr.isBlank()) {
                        tms().ensureMeta(clazz);
                        String dir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                        reg.mkdirs(dir);
                        DsRegistryExtAttrsHelper.removeAllTags(reg, dir);
                        DsRegistryExtAttrsHelper.addTag(reg, dir, RegistryMetaPaths.TAG_SCHEMA_ROLLBACK);
                        return;
                    }
                    String altDir = RegistryMetaPaths.odManifestAlterDir(SPACE, ent, migIdStr);
                    Field backendF = TableMetaStore.class.getDeclaredField("backend");
                    backendF.setAccessible(true);
                    Object backend = backendF.get(tms());
                    Class<?> bc = null;
                    for (Class<?> ic : TableMetaStore.class.getDeclaredClasses()) {
                        if ("Backend".equals(ic.getSimpleName())) {
                            bc = ic;
                            break;
                        }
                    }
                    java.lang.reflect.Method upd = bc.getDeclaredMethod("updateColumnDef", String.class, long.class, TableMetaStore.ColumnDef.class);
                    upd.setAccessible(true);
                    for (int i = 0; i < 4096; i++) {
                        String cIdS = safeGetString(reg, altDir + "/" + i + "/colId");
                        if (cIdS == null || cIdS.isBlank()) {
                            break;
                        }
                        long colId = Long.parseLong(cIdS);
                        String cMode = safeGetString(reg, altDir + "/" + i + "/" + RegistryMetaPaths.OD_ALTER_CAST);
                        String oJType = safeGetString(reg, altDir + "/" + i + "/" + RegistryMetaPaths.OD_ALTER_OLD_JTYPE);
                        String nJTypeRoll = safeGetString(reg, altDir + "/" + i + "/newJavaType");
                        String oLenS = safeGetString(reg, altDir + "/" + i + "/oldLength");
                        String oNullS = safeGetString(reg, altDir + "/" + i + "/oldNullable");
                        String oPrecS = safeGetString(reg, altDir + "/" + i + "/oldPrecision");
                        String oScaleS = safeGetString(reg, altDir + "/" + i + "/oldScale");
                        String colKey = safeGetString(reg, altDir + "/" + i + "/colKey");
                        int oLen = 0;
                        try {
                            if (oLenS != null && !oLenS.isBlank()) {
                                oLen = Integer.parseInt(oLenS);

                            }
                        } catch (Exception ignore) {
                        }
                        Boolean oNull = null;
                        if (oNullS != null && !oNullS.isBlank()) {
                            oNull = Boolean.valueOf(Boolean.parseBoolean(oNullS));
                        }
                        int oPrec = 0;
                        try {
                            if (oPrecS != null && !oPrecS.isBlank()) {
                                oPrec = Integer.parseInt(oPrecS);
                            }
                        } catch (Exception ignore) {
                        }
                        int oScale = 0;
                        try {
                            if (oScaleS != null && !oScaleS.isBlank()) {
                                oScale = Integer.parseInt(oScaleS);
                            }
                        } catch (Exception ignore) {
                        }
                        String nLenSRoll = safeGetString(reg, altDir + "/" + i + "/newLength");
                        int nLenRoll = 0;
                        try {
                            if (nLenSRoll != null && !nLenSRoll.isBlank()) {
                                nLenRoll = Integer.parseInt(nLenSRoll);

                            }
                        } catch (Exception ignore) {
                        }
                        if ("METADATA_ONLY".equals(cMode)) {
                            TableMetaStore.ColumnDef nd = new TableMetaStore.ColumnDef();
                            nd.colId = colId;
                            nd.colKey = colKey;
                            nd.javaType = oJType;
                            nd.length = oLen;
                            nd.nullable = oNull;
                            nd.precision = oPrec;
                            nd.scale = oScale;
                            upd.invoke(backend, ent, Long.valueOf(colId), nd);
                        } else if ("SAFE_UPCAST".equals(cMode) || "NARROW_WARN".equals(cMode)) {
                            TableMetaStore.ColumnDef nd = new TableMetaStore.ColumnDef();
                            nd.colId = colId;
                            nd.colKey = colKey;
                            nd.javaType = oJType;
                            nd.length = oLen;
                            nd.nullable = oNull;
                            nd.precision = oPrec;
                            nd.scale = oScale;
                            upd.invoke(backend, ent, Long.valueOf(colId), nd);
                            TableMetaStore.TableMeta sm = tms().getMeta(ent);
                            String colName = null;
                            if (sm != null && sm.columns != null) {
                                for (TableMetaStore.ColumnDef c : sm.columns) {
                                    if (c.colId == colId) {
                                        colName = c.name;
                                        break;
                                    }
                                }
                            }
                            if (colName != null && oLen > 0 && nLenRoll != oLen) {
                                String cpDir0 = altDir + "/" + CP_SUBDIR;
                                reg.mkdirs(cpDir0);
                                long rid = 1;
                                long maxRow = 200000L;
                                long batchIdx = 0;
                                long emptyRun = 0;
                                java.util.List<Long> batch = new java.util.ArrayList<>(512);
                                while (rid <= maxRow) {
                                    byte[] probe = null;
                                    try {
                                        probe = cs().getValue(ent, colName, rid);
                                    } catch (Exception ignore) {
                                    }
                                    if (probe == null || probe.length == 0) {
                                        emptyRun++;
                                        if (emptyRun > 1024L) {
                                            break;

                                        }
                                        rid++;
                                        continue;
                                    }
                                    emptyRun = 0;
                                    batch.add(Long.valueOf(rid));
                                    if (batch.size() >= 512 || rid == maxRow) {
                                        String cpId = "RB-LEN-" + colId + "-" + batchIdx;
                                        String cpLeaf = cpDir0 + "/" + cpId;
                                        String cpDone = safeGetString(reg, cpLeaf);
                                        if (cpDone == null || cpDone.isBlank()) {
                                            for (Long br : batch) {
                                                long brr = br.longValue();
                                                try {
                                                    byte[] ov = cs().getValue(ent, colName, brr);
                                                    if (ov != null && ov.length > 0) {
                                                        boolean jdRb = (nJTypeRoll != null && oJType != null && !nJTypeRoll.isBlank() && !oJType.isBlank() && !nJTypeRoll.equals(oJType));
                                                        byte[] nv = jdRb ? castBytes(ov, nLenRoll, oLen, nJTypeRoll, oJType) : java.util.Arrays.copyOf(ov, oLen);
                                                        cs().putValue(ent, colName, brr, nv);
                                                    }
                                                } catch (Exception ignore) {
                                                }
                                            }
                                            String sha = "rb:" + batch.get(0);
                                            reg.mkdirs(RegistryMetaPaths.parentOf0(cpLeaf));
                                            reg.putString(cpLeaf, sha, DataType.REG_SZ);
                                        }
                                        batch.clear();
                                        batchIdx++;
                                    }
                                    rid++;
                                }
                                if (!batch.isEmpty()) {
                                    String cpId = "RB-LEN-" + colId + "-" + batchIdx;
                                    String cpLeaf = cpDir0 + "/" + cpId;
                                    String cpDone = safeGetString(reg, cpLeaf);
                                    if (cpDone == null || cpDone.isBlank()) {
                                        for (Long br : batch) {
                                            long brr = br.longValue();
                                            try {
                                                byte[] ov = cs().getValue(ent, colName, brr);
                                                if (ov != null && ov.length > 0) {
                                                    boolean jdRb = (nJTypeRoll != null && oJType != null && !nJTypeRoll.isBlank() && !oJType.isBlank() && !nJTypeRoll.equals(oJType));
                                                    byte[] nv = jdRb ? castBytes(ov, nLenRoll, oLen, nJTypeRoll, oJType) : java.util.Arrays.copyOf(ov, oLen);
                                                    cs().putValue(ent, colName, brr, nv);
                                                }
                                            } catch (Exception ignore) {
                                            }
                                        }
                                        String sha = "rb:" + batch.get(0);
                                        reg.mkdirs(RegistryMetaPaths.parentOf0(cpLeaf));
                                        reg.putString(cpLeaf, sha, DataType.REG_SZ);
                                    }
                                    batch.clear();
                                    batchIdx++;
                                }
                            }
                        }
                    }
                    String dir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                    reg.mkdirs(dir);
                    DsRegistryExtAttrsHelper.removeAllTags(reg, dir);
                    DsRegistryExtAttrsHelper.addTag(reg, dir, RegistryMetaPaths.TAG_SCHEMA_ROLLBACK);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("rollbackAlter failed: ent=" + ent, e);
                }
            }
        }

        AddDiff computeAddDiff(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            TableMetaStore.TableMeta stored = tms().getMeta(ent);
            TableMetaStore.TableMeta fresh = buildFresh(clazz);
            Set<String> storedNames = new HashSet<>();
            if (stored.columns != null) {
                for (TableMetaStore.ColumnDef c : stored.columns) {
                    storedNames.add(c.name);
                }
            }
            List<NewCol> added = new ArrayList<>();
            for (TableMetaStore.ColumnDef fc : fresh.columns) {
                if (!storedNames.contains(fc.name)) {
                    Field f = findFieldByDsName(clazz, fc.name);
                    String dv = "";
                    if (f != null && f.isAnnotationPresent(DsField.class)) {
                        dv = f.getAnnotation(DsField.class).defaultValue();
                    }
                    long colId = cr().getOrCreateColId(ent, fc.colKey);
                    fc.colId = colId;
                    boolean nb = fc.nullable == null ? true : fc.nullable;
                    byte[] dvBytes = defaultValueBytes(fc.javaType, dv, nb);
                    added.add(new NewCol(fc.name, fc.colKey, fc.javaType, colId, nb, dv, dvBytes));
                }
            }
            String fs = stored.signature == null ? "" : stored.signature;
            String ts = computeSigLocal(fresh);
            return new AddDiff(fs, ts, added);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        TableMetaStore.TableMeta buildFresh(Class<? extends DsTableAdapter> clazz) {
            TableMetaStore.TableMeta meta = new TableMetaStore.TableMeta();
            meta.entityClassName = clazz.getName();
            meta.columns = new ArrayList<>();
            meta.compositeGroups = new LinkedHashMap<>();
            Map<String, TableMetaStore.ColumnDef> uniq = new HashMap<>();
            ColumnRegistry colReg = cr();
            for (Field f : getAllFieldsChain(clazz)) {
                if (f.isAnnotationPresent(DsCompositeField.class)) {
                    DsCompositeField a = f.getAnnotation(DsCompositeField.class);
                    String grp = a.group();
                    if (grp == null || grp.isBlank()) {
                        continue;
                    }
                    if (a.length() <= 0) {
                        continue;
                    }
                    TableMetaStore.CompositeGroup g = meta.compositeGroups.get(grp);
                    if (g == null) {
                        g = new TableMetaStore.CompositeGroup();
                        g.group = grp;
                        g.length = a.length();
                        g.items = new ArrayList<>();
                        meta.compositeGroups.put(grp, g);
                    }
                    TableMetaStore.CompositeItem it = new TableMetaStore.CompositeItem();
                    it.name = a.name().isEmpty() ? f.getName() : a.name();
                    it.startBits = a.startBits();
                    it.endBits = a.endBits();
                    g.items.add(it);
                }
                if (f.isAnnotationPresent(DsField.class)) {
                    DsField a = f.getAnnotation(DsField.class);
                    String ln = a.name().isEmpty() ? f.getName() : a.name();
                    if (ln == null || ln.isBlank()) {
                        continue;
                    }
                    String ck = clazz.getName() + "#" + ln;
                    long cid = colReg.getOrCreateColId(clazz.getName(), ck);
                    TableMetaStore.ColumnDef c = new TableMetaStore.ColumnDef();
                    c.colId = cid;
                    c.colKey = ck;
                    c.name = ln;
                    c.javaType = f.getType().getName();
                    c.length = a.length();
                    c.precision = a.precision();
                    c.scale = a.scale();
                    c.nullable = Boolean.valueOf(a.nullable());
                    if (uniq.put(ln, c) == null) {
                        meta.columns.add(c);
                    }
                }
            }
            for (TableMetaStore.CompositeGroup g : meta.compositeGroups.values()) {
                String ck = clazz.getName() + "#@composite:" + g.group;
                long cid = colReg.getOrCreateColId(clazz.getName(), ck);
                g.colId = cid;
                g.colKey = ck;
            }
            meta.signature = computeSigLocal(meta);
            return meta;
        }

        private static boolean colNull(TableMetaStore.ColumnDef c) {
            return c == null || c.nullable == null ? true : c.nullable;
        }

        private static boolean isSafeUpcast(String oJType, String nJType) {
            if (oJType == null) {
                oJType = "";
            }
            if (nJType == null) {
                nJType = "";
            }
            if (oJType.isEmpty() || nJType.isEmpty()) {
                return false;
            }
            if (oJType.equals(nJType)) {
                return true;
            }
            String o = simpleJt(oJType);
            String n = simpleJt(nJType);
            int oi = numRank(o);
            int ni = numRank(n);
            if (oi >= 0 && ni >= 0) {
                return ni > oi;
            }
            if (("float".equals(o) || "Float".equals(o)) && ("double".equals(n) || "Double".equals(n))) {
                return true;
            }
            return false;
        }

        private static String simpleJt(String jt) {
            int p = jt.lastIndexOf('.');
            return p < 0 ? jt : jt.substring(p + 1);
        }

        private static int numRank(String s) {
            if ("byte".equals(s) || "Byte".equals(s)) {
                return 0;
            }
            if ("short".equals(s) || "Short".equals(s)) {
                return 1;
            }
            if ("int".equals(s) || "Integer".equals(s)) {
                return 2;
            }
            if ("long".equals(s) || "Long".equals(s)) {
                return 3;
            }
            return -1;
        }

        private static boolean isNumericJt(String jt) {
            String s = simpleJt(jt);
            if ("byte".equals(s) || "Byte".equals(s) || "short".equals(s) || "Short".equals(s)) {
                return true;
            }
            if ("int".equals(s) || "Integer".equals(s) || "long".equals(s) || "Long".equals(s)) {
                return true;
            }
            if ("float".equals(s) || "Float".equals(s) || "double".equals(s) || "Double".equals(s)) {
                return true;
            }
            return false;
        }

        private static byte[] castBytes(byte[] ov, int oLen, int nLen, String oJType, String nJType) {
            if (ov == null) {
                return new byte[nLen];
            }
            if (oJType == null) {
                oJType = "";
            }
            if (nJType == null) {
                nJType = "";
            }
            boolean oNum = isNumericJt(oJType);
            boolean nNum = isNumericJt(nJType);
            if (!oNum || !nNum) {
                if (ov.length == nLen) {
                    return ov;
                }
                return java.util.Arrays.copyOf(ov, nLen);
            }
            int eff = Math.min(oLen, ov.length);
            if (eff <= 0) {
                return new byte[nLen];
            }
            try {
                java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(ov, 0, eff);
                java.nio.ByteBuffer out = java.nio.ByteBuffer.allocate(nLen);
                long lv;
                String os = simpleJt(oJType);
                String ns = simpleJt(nJType);
                boolean oFp = "float".equals(os) || "Float".equals(os) || "double".equals(os) || "Double".equals(os);
                boolean nFp = "float".equals(ns) || "Float".equals(ns) || "double".equals(ns) || "Double".equals(ns);
                if (oFp) {
                    if ("double".equals(os) || "Double".equals(os)) {
                        if (eff >= 8) {
                            lv = Double.doubleToLongBits(in.getDouble(0));
                        } else {
                            lv = 0L;

                        }
                    } else {
                        if (eff >= 4) {
                            lv = Float.floatToIntBits(in.getFloat(0)) & 0xffffffffL;
                        } else {
                            lv = 0L;

                        }
                    }
                } else {
                    if ("long".equals(os) || "Long".equals(os)) {
                        if (eff >= 8) {
                            lv = in.getLong(0);
                        } else {
                            lv = 0L;

                        }
                    } else if ("int".equals(os) || "Integer".equals(os)) {
                        if (eff >= 4) {
                            lv = in.getInt(0);
                        } else {
                            lv = 0L;

                        }
                    } else if ("short".equals(os) || "Short".equals(os)) {
                        if (eff >= 2) {
                            lv = in.getShort(0);
                        } else {
                            lv = 0L;

                        }
                    } else {
                        if (eff >= 1) {
                            lv = in.get(0);
                        } else {
                            lv = 0L;

                        }
                    }
                }
                if (nFp) {
                    if ("double".equals(ns) || "Double".equals(ns)) {
                        if (oFp) {
                            out.putDouble(0, Double.longBitsToDouble(lv));
                        } else {
                            out.putDouble(0, (double) lv);
                        }
                    } else {
                        if (oFp) {
                            out.putFloat(0, Float.intBitsToFloat((int) lv));
                        } else {
                            out.putFloat(0, (float) lv);
                        }
                    }
                } else {
                    if ("long".equals(ns) || "Long".equals(ns)) {
                        out.putLong(0, lv);
                    } else if ("int".equals(ns) || "Integer".equals(ns)) {
                        out.putInt(0, (int) lv);
                    } else if ("short".equals(ns) || "Short".equals(ns)) {
                        out.putShort(0, (short) lv);
                    } else {
                        out.put(0, (byte) lv);
                    }
                }
                return out.array();
            } catch (Exception ignore) {
                return java.util.Arrays.copyOf(ov, nLen);
            }
        }

        String computeSigLocal(TableMetaStore.TableMeta m) {
            StringBuilder sb = new StringBuilder(256);
            sb.append("v1|").append(m.entityClassName).append('\n');
            if (m.columns != null) {
                for (TableMetaStore.ColumnDef c : m.columns) {
                    sb.append("F|").append(c.colId).append('|').append(c.colKey).append('|').append(c.javaType)
                            .append('|').append(c.length).append('|').append(c.precision).append('|').append(c.scale)
                            .append('|').append(colNull(c)).append('\n');
                }
            }
            if (m.compositeGroups != null) {
                for (TableMetaStore.CompositeGroup g : m.compositeGroups.values()) {
                    sb.append("C|").append(g.group).append('|').append(g.length).append('|').append(g.colId)
                            .append('|').append(g.colKey).append('\n');
                    if (g.items != null) {
                        for (TableMetaStore.CompositeItem i : g.items) {
                            sb.append("I|").append(i.name).append('|').append(i.startBits)
                                    .append('|').append(i.endBits).append('\n');
                        }
                    }
                }
            }
            return Integer.toHexString(sb.toString().hashCode());
        }

        void writePrepareManifest(DsRegistry reg, String ent, long migId, AddDiff diff) throws Exception {
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            reg.mkdirs(mDir);
            writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_PREP);
            writeStr(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent), String.valueOf(migId));
            writeStr(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent), diff.fromSig);
            writeStr(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent), diff.toSig);
            writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "0");
            String cpDir = mDir + "/" + CP_SUBDIR;
            reg.mkdirs(cpDir);
            md256.reset();
            md256.update(longBE(migId));
            md256.update(diff.fromSig.getBytes(StandardCharsets.UTF_8));
            md256.update(diff.toSig.getBytes(StandardCharsets.UTF_8));
            for (NewCol nc : diff.newCols) {
                md256.update(nc.colKey.getBytes(StandardCharsets.UTF_8));
            }
            String sha = hexOf(md256.digest());
            String cp0 = cpDir + "/0";
            reg.mkdirs(RegistryMetaPaths.parentOf0(cp0));
            reg.putString(cp0, sha, DataType.REG_SZ);
            String rootDir = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
            reg.mkdirs(rootDir);
            DsRegistryExtAttrsHelper.removeAllTags(reg, rootDir);
            DsRegistryExtAttrsHelper.addTag(reg, rootDir, RegistryMetaPaths.TAG_SCHEMA_MIGRATING);
            for (int i = 0; i < diff.newCols.size(); i++) {
                NewCol nc = diff.newCols.get(i);
                String base = mDir + "/cols/" + i;
                writeStr(reg, base + "/name", nc.name);
                writeStr(reg, base + "/colKey", nc.colKey);
                writeStr(reg, base + "/javaType", nc.javaType);
                writeStr(reg, base + "/colId", String.valueOf(nc.colId));
                writeStr(reg, base + "/nullable", String.valueOf(nc.nullable));
                writeStr(reg, base + "/dvLit", nc.dvLit == null ? "" : nc.dvLit);
            }
        }

        AddDiff loadDiff(Class<? extends DsTableAdapter> clazz, DsRegistry reg, String ent) throws Exception {
            String fromSig = safeGetString(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent));
            String toSig = safeGetString(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent));
            if (fromSig == null) {
                fromSig = "";
            }
            if (toSig == null) {
                toSig = "";
            }
            List<NewCol> cols = new ArrayList<>();
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            String colsDir = mDir + "/cols";
            for (int i = 0; i < 1024; i++) {
                String base = colsDir + "/" + i + "/name";
                String n = safeGetString(reg, base);
                if (n == null || n.isBlank()) {
                    break;
                }
                String ck = safeGetString(reg, colsDir + "/" + i + "/colKey");
                String jt = safeGetString(reg, colsDir + "/" + i + "/javaType");
                String ciS = safeGetString(reg, colsDir + "/" + i + "/colId");
                String nbS = safeGetString(reg, colsDir + "/" + i + "/nullable");
                String dvLit = safeGetString(reg, colsDir + "/" + i + "/dvLit");
                long ci = Long.parseLong(ciS == null ? "0" : ciS);
                boolean nb = !"false".equalsIgnoreCase(nbS);
                if (dvLit == null) {
                    dvLit = "";
                }
                byte[] dvBytes = defaultValueBytes(jt, dvLit, nb);
                cols.add(new NewCol(n, ck, jt, ci, nb, dvLit, dvBytes));
            }
            if (cols.isEmpty()) {
                return computeAddDiff(clazz);
            }
            return new AddDiff(fromSig, toSig, cols);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void applyAddInternal(Class<? extends DsTableAdapter> clazz, AddDiff diff, int batchSize, boolean dryRun) throws Exception {
            String ent = clazz.getName();
            DsRegistry reg = DsRegistryInstance.get(root);
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            String cpDir = mDir + "/" + CP_SUBDIR;
            if (!dryRun) {
                reg.mkdirs(cpDir);
                writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_APPLY);
            }
            if (!dryRun && !diff.newCols.isEmpty()) {
                tms().ensureMeta(clazz);
            }
            DsHashSet ids = idSetForClass(clazz);
            List<Long> allIds = new ArrayList<>((int) ids.total());
            for (Long v : ids) {
                allIds.add(v);
            }
            long total = allIds.size();
            int maxCp = maxCpId(reg, cpDir);
            int start = maxCp * batchSize;
            long done = Math.min((long) start, total);
            String progLeaf = RegistryMetaPaths.odManifestProgress(SPACE, ent);
            if (done == total && maxCp > 0) {
                if (!dryRun) {
                    writeStr(reg, progLeaf, "100");
                    finalizeApply(reg, ent, false);
                }
                return;
            }
            ColumnarStore store = cs();
            int cpIdx = maxCp;
            for (int i = start; i < total; i += batchSize) {
                int end = (int) Math.min(total, (long) i + batchSize);
                List<Long> batch = allIds.subList(i, end);
                cpIdx++;
                String cpLeaf = cpDir + "/" + cpIdx;
                md256.reset();
                md256.update(intBE(cpIdx));
                for (long rid : batch) {
                    md256.update(longBE(rid));
                }
                for (NewCol nc : diff.newCols) {
                    md256.update(nc.dvBytes);
                }
                String sha = hexOf(md256.digest());
                String prev = safeGetString(reg, cpLeaf);
                if (prev != null && !prev.isBlank()) {
                    if (!sha.equals(prev)) {
                        throw new IllegalStateException(
                                "od cp corrupt: cpId=" + cpIdx + " stored=" + prev + " calc=" + sha);
                    }
                    done += batch.size();
                    continue;
                }
                if (!dryRun) {
                    for (long rid : batch) {
                        for (NewCol nc : diff.newCols) {
                            store.putValue(ent, nc.name, rid, nc.dvBytes);
                        }
                    }
                    writeStr(reg, cpLeaf, sha);
                    int pct = total == 0L ? 100 : (int) ((done * 100L) / total);
                    writeStr(reg, progLeaf, String.valueOf(pct));
                    String debugStop = System.getProperty("p18.od.debug.stop.after.cp");
                    if (debugStop != null && !debugStop.isEmpty()) {
                        try {
                            int th = Integer.parseInt(debugStop);
                            if (th > 0 && cpIdx >= th) {
                                throw new RuntimeException("OD_DEBUG_STOP_CP:" + cpIdx);
                            }
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
                done += batch.size();
            }
            if (!dryRun) {
                finalizeApply(reg, ent, false);
            }
        }

        void finalizeApply(DsRegistry reg, String ent, boolean dryRun) throws Exception {
            if (dryRun) {
                return;
            }
            writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_DONE);
            writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "100");
            String odRoot = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
            reg.mkdirs(odRoot);
            DsRegistryExtAttrsHelper.removeAllTags(reg, odRoot);
            String curLeaf = RegistryMetaPaths.odCurrentMigId(SPACE, ent);
            String migS = safeGetString(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent));
            if (migS != null && !migS.isBlank()) {
                writeStr(reg, curLeaf, migS);
            }
        }

        long ensureMigId(DsRegistry reg, String ent) throws Exception {
            String nextLeaf = RegistryMetaPaths.odNextMigId(SPACE, ent);
            String cur = safeGetString(reg, nextLeaf);
            long n = 1L;
            if (cur != null && !cur.isBlank()) {
                try {
                    n = Long.parseLong(cur) + 1L;
                } catch (Exception ignore) {
                }
            }
            writeStr(reg, nextLeaf, String.valueOf(n));
            return n;
        }

        int maxCpId(DsRegistry reg, String cpDir) {
            try {
                int max = 0;
                List<String> ch = reg.listChildren(cpDir);
                if (ch == null) {
                    return 0;
                }
                for (String s : ch) {
                    try {
                        int v = Integer.parseInt(s);
                        if (v > max) {
                            max = v;

                        }
                    } catch (Exception ignore) {
                    }
                }
                return max;
            } catch (Exception e) {
                return 0;
            }
        }

        private void writeStr(DsRegistry reg, String path, String val) throws Exception {
            reg.mkdirs(RegistryMetaPaths.parentOf0(path));
            reg.putString(path, val == null ? "" : val, DataType.REG_SZ);
        }

        private String safeGetString(DsRegistry reg, String path) {
            try {
                RegistryEntry e = reg.get(path);
                if (e == null) {
                    return null;
                }
                String v = e.asString();
                return v == null ? null : (v.isEmpty() ? null : v);
            } catch (Exception ignore) {
                return null;
            }
        }

        List<Field> getAllFieldsChain(Class<?> c) {
            List<Field> fs = new ArrayList<>();
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                Field[] arr = cur.getDeclaredFields();
                for (Field f : arr) {
                    fs.add(f);
                }
                cur = cur.getSuperclass();
            }
            return fs;
        }

        Field findFieldByDsName(Class<?> c, String name) {
            for (Field f : getAllFieldsChain(c)) {
                DsField a = f.getAnnotation(DsField.class);
                if (a == null) {
                    continue;
                }
                String ln = a.name().isEmpty() ? f.getName() : a.name();
                if (name.equals(ln)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        }

        byte[] defaultValueBytes(String jt, String lit, boolean nullable) {
            if (lit == null) {
                lit = "";
            }
            if (jt == null) {
                jt = "java.lang.String";
            }
            try {
                switch (jt) {
                    case "long":
                    case "java.lang.Long":
                        long lv = lit.isEmpty() ? 0L : Long.parseLong(lit);
                        return longBE(lv);
                    case "int":
                    case "java.lang.Integer":
                        int iv = lit.isEmpty() ? 0 : Integer.parseInt(lit);
                        return intBE(iv);
                    case "short":
                    case "java.lang.Short":
                        short sv = lit.isEmpty() ? (short) 0 : Short.parseShort(lit);
                        return new byte[]{(byte) (sv >>> 8), (byte) sv};
                    case "byte":
                    case "java.lang.Byte":
                        byte bv = lit.isEmpty() ? (byte) 0 : Byte.parseByte(lit);
                        return new byte[]{bv};
                    case "boolean":
                    case "java.lang.Boolean":
                        boolean b = lit.isEmpty() ? false : Boolean.parseBoolean(lit);
                        return new byte[]{b ? (byte) 1 : (byte) 0};
                    case "float":
                    case "java.lang.Float":
                        float fv = lit.isEmpty() ? 0f : Float.parseFloat(lit);
                        return intBE(Float.floatToIntBits(fv));
                    case "double":
                    case "java.lang.Double":
                        double dv = lit.isEmpty() ? 0.0 : Double.parseDouble(lit);
                        return longBE(Double.doubleToLongBits(dv));
                    case "java.util.Date":
                        long t = lit.isEmpty() ? 0L : Long.parseLong(lit);
                        return longBE(t);
                    case "java.lang.String":
                    default:
                        return lit.getBytes(StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                return new byte[0];
            }
        }

        byte[] longBE(long v) {
            return new byte[]{
                (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
        }

        byte[] intBE(int v) {
            return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
        }

        String hexOf(byte[] d) {
            char[] ch = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                int v = d[i] & 0xFF;
                ch[i * 2] = HEX[v >>> 4];
                ch[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(ch);
        }

        static final class DropCol {

            final String name;
            final String colKey;
            final long colId;
            final long retentionMs;
            long markedAt;
            long sizeEstimate;

            DropCol(String n, String ck, long ci, long rm) {
                name = n;
                colKey = ck;
                colId = ci;
                retentionMs = rm;
                markedAt = 0L;
                sizeEstimate = 0L;
            }
        }

        static final class DropDiff {

            final String fromSig;
            final String toSig;
            final List<DropCol> drops;

            DropDiff(String a, String b, List<DropCol> c) {
                fromSig = a;
                toSig = b;
                drops = c;
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        DropDiff computeDropDiff(Class<? extends DsTableAdapter> clazz, List<String> dropNames, long retentionMs) {
            if (retentionMs < 0L) {
                retentionMs = DEFAULT_RETENTION;
            }
            String ent = clazz.getName();
            TableMetaStore.TableMeta stored = tms().getMeta(ent);
            TableMetaStore.TableMeta fresh = buildFresh(clazz);
            Set<String> dropSet = new HashSet<>(dropNames);
            List<DropCol> drops = new ArrayList<>();
            for (TableMetaStore.ColumnDef fc : fresh.columns) {
                if (!dropSet.contains(fc.name)) {
                    continue;
                }
                Long existing = cr().findColId(ent, fc.colKey);
                if (existing == null || existing.longValue() <= 0L) {
                    throw new IllegalArgumentException("drop col not found: " + fc.name + " (colKey=" + fc.colKey + ")");
                }
                drops.add(new DropCol(fc.name, fc.colKey, existing.longValue(), retentionMs));
            }
            if (drops.size() != dropSet.size()) {
                Set<String> found = new HashSet<>();
                for (DropCol d : drops) {
                    found.add(d.name);
                }
                List<String> missing = new ArrayList<>();
                for (String s : dropSet) {
                    if (!found.contains(s)) {
                        missing.add(s);
                    }
                }
                throw new IllegalArgumentException("drop cols missing: " + String.join(",", missing));
            }
            String fs = stored == null || stored.signature == null ? "" : stored.signature;
            String ts = computeSigLocal(fresh);
            return new DropDiff(fs, ts, drops);
        }

        void writePrepareDropManifest(DsRegistry reg, String ent, long migId, DropDiff diff) throws Exception {
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            reg.mkdirs(mDir);
            writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_PREP);
            writeStr(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent), String.valueOf(migId));
            writeStr(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent), diff.fromSig);
            writeStr(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent), diff.toSig);
            writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "0");
            String odRoot = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
            reg.mkdirs(odRoot);
            DsRegistryExtAttrsHelper.removeAllTags(reg, odRoot);
            DsRegistryExtAttrsHelper.addTag(reg, odRoot, RegistryMetaPaths.TAG_SCHEMA_MIGRATING);
            for (int i = 0; i < diff.drops.size(); i++) {
                DropCol dc = diff.drops.get(i);
                String base = mDir + "/dropCols/" + i;
                writeStr(reg, base + "/name", dc.name);
                writeStr(reg, base + "/colKey", dc.colKey);
                writeStr(reg, base + "/colId", String.valueOf(dc.colId));
                writeStr(reg, base + "/retentionMs", String.valueOf(dc.retentionMs));
            }
        }

        DropDiff loadDropDiff(Class<? extends DsTableAdapter> clazz, DsRegistry reg, String ent) throws Exception {
            String fromSig = safeGetString(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent));
            String toSig = safeGetString(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent));
            if (fromSig == null) {
                fromSig = "";
            }
            if (toSig == null) {
                toSig = "";
            }
            List<DropCol> drops = new ArrayList<>();
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            String dDir = mDir + "/dropCols";
            for (int i = 0; i < 1024; i++) {
                String base = dDir + "/" + i + "/name";
                String n = safeGetString(reg, base);
                if (n == null || n.isBlank()) {
                    break;
                }
                String ck = safeGetString(reg, dDir + "/" + i + "/colKey");
                String ciS = safeGetString(reg, dDir + "/" + i + "/colId");
                String rmS = safeGetString(reg, dDir + "/" + i + "/retentionMs");
                long ci = Long.parseLong(ciS == null ? "0" : ciS);
                long rm = DEFAULT_RETENTION;
                try {
                    if (rmS != null && !rmS.isBlank()) {
                        rm = Long.parseLong(rmS);

                    }
                } catch (Exception ignore) {
                }
                drops.add(new DropCol(n, ck, ci, rm));
            }
            if (drops.isEmpty()) {
                List<String> names = new ArrayList<>();
                TableMetaStore.TableMeta stored = tms().getMeta(ent);
                if (stored != null && stored.columns != null) {
                    for (TableMetaStore.ColumnDef c : stored.columns) {
                        names.add(c.name);
                    }
                }
                TableMetaStore.TableMeta fresh = buildFresh(clazz);
                Set<String> freshNames = new HashSet<>();
                if (fresh.columns != null) {
                    for (TableMetaStore.ColumnDef c : fresh.columns) {
                        freshNames.add(c.name);
                    }
                }
                List<String> drop = new ArrayList<>();
                for (String n : names) {
                    if (!freshNames.contains(n)) {
                        drop.add(n);
                    }
                }
                return computeDropDiff(clazz, drop, DEFAULT_RETENTION);
            }
            return new DropDiff(fromSig, toSig, drops);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void prepareDrop(Class<? extends DsTableAdapter> clazz, List<String> dropNames, long retentionMs, boolean dryRun) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    long migId = ensureMigId(reg, ent);
                    DropDiff diff = computeDropDiff(clazz, dropNames, retentionMs);
                    if (!dryRun) {
                        writePrepareDropManifest(reg, ent, migId, diff);
                    }
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("prepareDrop failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        int applyDrop(Class<? extends DsTableAdapter> clazz, boolean purgeNow) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String curPhase = safeGetString(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent));
                    if (purgeNow && !PHASE_DONE.equals(curPhase)) {
                        throw new IllegalArgumentException("phase not DONE before apply: phase=" + curPhase);
                    }
                    DropDiff diff = loadDropDiff(clazz, reg, ent);
                    if (!PHASE_APPLY.equals(curPhase) && !PHASE_DONE.equals(curPhase)) {
                        writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_APPLY);
                    }
                    long now = System.currentTimeMillis();
                    ColumnRegistry colReg = cr();
                    for (DropCol dc : diff.drops) {
                        if (!colReg.isDeleted(ent, dc.colId)) {
                            colReg.markDeleted(ent, dc.colId);
                        }
                        dc.markedAt = now;
                        try {
                            String markedLeaf = RegistryMetaPaths.odTombCol(SPACE, ent, dc.colId, "markedAt");
                            writeStr(reg, markedLeaf, String.valueOf(now));
                            String retLeaf = RegistryMetaPaths.odTombCol(SPACE, ent, dc.colId, "retentionMs");
                            writeStr(reg, retLeaf, String.valueOf(dc.retentionMs));
                        } catch (Exception ignore) {
                        }
                    }
                    Field cacheField = DsDatabaseLocal.this.getClass().getDeclaredField("tableMetaCache");
                    cacheField.setAccessible(true);
                    java.util.Map<Class<?>, Object> cache = (java.util.Map<Class<?>, Object>) cacheField.get(DsDatabaseLocal.this);
                    cache.remove(clazz);
                    Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
                    metaOf.setAccessible(true);
                    metaOf.invoke(DsDatabaseLocal.this, clazz);
                    writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "100");
                    finalizeApply(reg, ent, false);
                    int purged = 0;
                    if (purgeNow) {
                        String phase = safeGetString(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent));
                        if (!PHASE_DONE.equals(phase)) {
                            throw new IllegalArgumentException("phase not DONE after apply: phase=" + phase);
                        }
                        ColumnarStore store = cs();
                        for (DropCol dc : diff.drops) {
                            String markedS = null;
                            try {
                                markedS = safeGetString(reg, RegistryMetaPaths.odTombCol(SPACE, ent, dc.colId, "markedAt"));
                            } catch (Exception ignore) {
                            }
                            long marked = 0L;
                            if (markedS != null && !markedS.isBlank()) try {
                                marked = Long.parseLong(markedS);
                            } catch (Exception ignore) {
                            }
                            long ret = dc.retentionMs;
                            if (marked <= 0L || (marked + ret) > now) {
                                continue;
                            }
                            store.deleteColumnHard(clazz, dc.name, 2048);
                            purged++;
                        }
                    }
                    return purged;
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("applyDrop failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void rollbackDrop(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    DropDiff diff = loadDropDiff(clazz, reg, ent);
                    ColumnRegistry colReg = cr();
                    for (DropCol dc : diff.drops) {
                        if (colReg.isDeleted(ent, dc.colId)) {
                            colReg.unDelete(ent, dc.colId);
                        }
                    }
                    Field cacheField = DsDatabaseLocal.this.getClass().getDeclaredField("tableMetaCache");
                    cacheField.setAccessible(true);
                    java.util.Map<Class<?>, Object> cache = (java.util.Map<Class<?>, Object>) cacheField.get(DsDatabaseLocal.this);
                    cache.remove(clazz);
                    Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
                    metaOf.setAccessible(true);
                    metaOf.invoke(DsDatabaseLocal.this, clazz);
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_RB, DataType.REG_SZ);
                    String odRoot = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                    reg.mkdirs(odRoot);
                    DsRegistryExtAttrsHelper.removeAllTags(reg, odRoot);
                    DsRegistryExtAttrsHelper.addTag(reg, odRoot, RegistryMetaPaths.TAG_SCHEMA_ROLLBACK);
                } catch (Exception e) {
                    throw new RuntimeException("rollbackDrop failed: ent=" + ent, e);
                }
            }
        }

        static final class RenameCol {

            final String oldName;
            final String newName;
            final String oldColKey;
            final String newColKey;
            final long colId;

            RenameCol(String oldName, String newName, String oldColKey, String newColKey, long colId) {
                this.oldName = oldName;
                this.newName = newName;
                this.oldColKey = oldColKey;
                this.newColKey = newColKey;
                this.colId = colId;
            }
        }

        static final class RenameDiff {

            final String fromSig;
            final String toSig;
            final List<RenameCol> renames;
            final boolean alias;

            RenameDiff(String fromSig, String toSig, List<RenameCol> renames, boolean alias) {
                this.fromSig = fromSig;
                this.toSig = toSig;
                this.renames = renames;
                this.alias = alias;
            }
        }

        RenameDiff computeRenameDiff(Class<? extends DsTableAdapter> clazz, List<String[]> renamePairs, boolean aliasPhase) {
            String ent = clazz.getName();
            tms().ensureMeta(clazz);
            TableMetaStore.TableMeta cur = tms().getMeta(ent);
            if (cur == null) {
                throw new IllegalArgumentException("meta missing: " + ent);
            }
            java.util.Set<String> newNames = new java.util.LinkedHashSet<>();
            java.util.Set<String> oldNames = new java.util.LinkedHashSet<>();
            List<RenameCol> list = new ArrayList<>();
            for (String[] pair : renamePairs) {
                if (pair == null || pair.length < 2) {
                    throw new IllegalArgumentException("rename pair invalid");
                }
                String oldN = pair[0];
                String newN = pair[1];
                if (oldN == null || oldN.isBlank() || newN == null || newN.isBlank()) {
                    throw new IllegalArgumentException("rename name blank");
                }
                if (oldN.equals(newN)) {
                    continue;
                }
                if (!oldNames.add(oldN)) {
                    throw new IllegalArgumentException("dup old: " + oldN);
                }
                if (!newNames.add(newN)) {
                    throw new IllegalArgumentException("dup new: " + newN);
                }
                String oldKey = ent + "#" + oldN;
                String newKey = ent + "#" + newN;
                Long oldId = cr().findColId(clazz, oldKey);
                if (oldId == null || oldId.longValue() <= 0L) {
                    throw new IllegalArgumentException("old col not exists: " + oldN);
                }
                Long existNew = cr().findColId(clazz, newKey);
                if (existNew != null && existNew.longValue() > 0L) {
                    throw new IllegalArgumentException("new col already exists: " + newN);
                }
                list.add(new RenameCol(oldN, newN, oldKey, newKey, oldId.longValue()));
            }
            String toSig = cur.signature == null ? "r" + list.size() : cur.signature + "|rn:" + list.size();
            return new RenameDiff(cur.signature, toSig, list, aliasPhase);
        }

        void writePrepareRenameManifest(DsRegistry reg, String ent, long migId, RenameDiff diff) throws Exception {
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            reg.mkdirs(mDir);
            writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_PREP);
            writeStr(reg, RegistryMetaPaths.odManifestMigId(SPACE, ent), String.valueOf(migId));
            writeStr(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent), diff.fromSig);
            writeStr(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent), diff.toSig);
            writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "0");
            writeStr(reg, mDir + "/alias", diff.alias ? "1" : "0");
            String odRoot = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
            reg.mkdirs(odRoot);
            DsRegistryExtAttrsHelper.removeAllTags(reg, odRoot);
            DsRegistryExtAttrsHelper.addTag(reg, odRoot, RegistryMetaPaths.TAG_SCHEMA_MIGRATING);
            for (int i = 0; i < diff.renames.size(); i++) {
                RenameCol rc = diff.renames.get(i);
                String base = mDir + "/renamedFromTo/" + i;
                writeStr(reg, base + "/oldName", rc.oldName);
                writeStr(reg, base + "/newName", rc.newName);
                writeStr(reg, base + "/oldColKey", rc.oldColKey);
                writeStr(reg, base + "/newColKey", rc.newColKey);
                writeStr(reg, base + "/colId", String.valueOf(rc.colId));
            }
        }

        RenameDiff loadRenameDiff(Class<? extends DsTableAdapter> clazz, DsRegistry reg, String ent) throws Exception {
            String fromSig = safeGetString(reg, RegistryMetaPaths.odManifestFromSig(SPACE, ent));
            String toSig = safeGetString(reg, RegistryMetaPaths.odManifestToSig(SPACE, ent));
            if (fromSig == null) {
                fromSig = "";
            }
            if (toSig == null) {
                toSig = "";
            }
            boolean alias = "1".equals(safeGetString(reg, RegistryMetaPaths.odManifestDir(SPACE, ent) + "/alias"));
            List<RenameCol> list = new ArrayList<>();
            String mDir = RegistryMetaPaths.odManifestDir(SPACE, ent);
            String baseDir = mDir + "/renamedFromTo";
            for (int i = 0; i < 1024; i++) {
                String p = baseDir + "/" + i + "/oldName";
                String oN = safeGetString(reg, p);
                if (oN == null || oN.isBlank()) {
                    break;
                }
                String nN = safeGetString(reg, baseDir + "/" + i + "/newName");
                String oK = safeGetString(reg, baseDir + "/" + i + "/oldColKey");
                String nK = safeGetString(reg, baseDir + "/" + i + "/newColKey");
                String ciS = safeGetString(reg, baseDir + "/" + i + "/colId");
                long cid = 0L;
                try {
                    if (ciS != null && !ciS.isBlank()) {
                        cid = Long.parseLong(ciS);

                    }
                } catch (Exception ignore) {
                }
                if (nN == null || cid <= 0L) {
                    continue;
                }
                list.add(new RenameCol(oN, nN, oK, nK, cid));
            }
            if (list.isEmpty()) {
                return new RenameDiff(fromSig, toSig, list, alias);
            }
            return new RenameDiff(fromSig, toSig, list, alias);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void prepareRename(Class<? extends DsTableAdapter> clazz, List<String[]> renamePairs, boolean aliasPhase, boolean dryRun) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    long migId = ensureMigId(reg, ent);
                    RenameDiff diff = computeRenameDiff(clazz, renamePairs, aliasPhase);
                    if (!dryRun) {
                        writePrepareRenameManifest(reg, ent, migId, diff);
                    }
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("prepareRename failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void applyRename(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    String curPhase = safeGetString(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent));
                    RenameDiff diff = loadRenameDiff(clazz, reg, ent);
                    if (!PHASE_APPLY.equals(curPhase) && !PHASE_DONE.equals(curPhase)) {
                        writeStr(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent), PHASE_APPLY);
                    }
                    for (RenameCol rc : diff.renames) {
                        cr().renameColKey(ent, rc.oldColKey, rc.newColKey);
                    }
                    tms().ensureMeta(clazz);
                    try {
                        Field cacheField = DsDatabaseLocal.this.getClass().getDeclaredField("tableMetaCache");
                        cacheField.setAccessible(true);
                        java.util.Map<Class<?>, Object> cache = (java.util.Map<Class<?>, Object>) cacheField.get(DsDatabaseLocal.this);
                        cache.remove(clazz);
                        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
                        metaOf.setAccessible(true);
                        metaOf.invoke(DsDatabaseLocal.this, clazz);
                    } catch (Exception ignored) {
                    }
                    writeStr(reg, RegistryMetaPaths.odManifestProgress(SPACE, ent), "100");
                    finalizeApply(reg, ent, false);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("applyRename failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void rollbackRename(Class<? extends DsTableAdapter> clazz) {
            String ent = clazz.getName();
            synchronized (lockFor(ent)) {
                try {
                    DsRegistry reg = DsRegistryInstance.get(root);
                    RenameDiff diff = loadRenameDiff(clazz, reg, ent);
                    for (RenameCol rc : diff.renames) {
                        cr().renameColKey(ent, rc.newColKey, rc.oldColKey);
                    }
                    tms().ensureMeta(clazz);
                    try {
                        Field cacheField = DsDatabaseLocal.this.getClass().getDeclaredField("tableMetaCache");
                        cacheField.setAccessible(true);
                        java.util.Map<Class<?>, Object> cache = (java.util.Map<Class<?>, Object>) cacheField.get(DsDatabaseLocal.this);
                        cache.remove(clazz);
                        Method metaOf = DsDatabaseLocal.class.getDeclaredMethod("metaOf", Class.class);
                        metaOf.setAccessible(true);
                        metaOf.invoke(DsDatabaseLocal.this, clazz);
                    } catch (Exception ignored) {
                    }
                    String phaseLeaf = RegistryMetaPaths.odManifestPhase(SPACE, ent);
                    reg.mkdirs(RegistryMetaPaths.parentOf0(phaseLeaf));
                    reg.putString(phaseLeaf, PHASE_RB, DataType.REG_SZ);
                    String odRoot = RegistryMetaPaths.onlineDdlRoot(SPACE, ent);
                    reg.mkdirs(odRoot);
                    DsRegistryExtAttrsHelper.removeAllTags(reg, odRoot);
                    DsRegistryExtAttrsHelper.addTag(reg, odRoot, RegistryMetaPaths.TAG_SCHEMA_ROLLBACK);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("rollbackRename failed: ent=" + ent, e);
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void overlayDefaultsOnRead(DsTableAdapter obj, long rowId) {
            if (obj == null || rowId <= 0L) {
                return;
            }
            String ent = obj.getClass().getName();
            DsRegistry reg;
            try {
                reg = DsRegistryInstance.get(root);
            } catch (Exception e) {
                return;
            }
            String phase = safeGetString(reg, RegistryMetaPaths.odManifestPhase(SPACE, ent));
            if (!PHASE_DONE.equals(phase)) {
                return;
            }
            ColumnarStore store = cs();
            try {
                TableMetaStore.TableMeta storedMeta = tms().getMeta(ent);
                if (storedMeta == null || storedMeta.columns == null) {
                    return;
                }
                for (TableMetaStore.ColumnDef sc : storedMeta.columns) {
                    if (sc.name == null || sc.javaType == null) {
                        continue;
                    }
                    Field f = findFieldByDsName(obj.getClass(), sc.name);
                    if (f == null) {
                        continue;
                    }
                    byte[] vb = store.getValue(ent, sc.name, rowId);
                    if (vb == null || vb.length == 0) {
                        continue;
                    }
                    Object val = coerceFromBytes(sc.javaType, vb);
                    if (val == null) {
                        continue;
                    }
                    f.set(obj, val);
                }
            } catch (Exception ignore) {
            }
        }

        private Object coerceFromBytes(String jt, byte[] vb) {
            if (vb == null) {
                return null;
            }
            try {
                switch (jt) {
                    case "long":
                    case "java.lang.Long":
                        if (vb.length < 8) {
                            return null;
                        }
                        long lv = 0L;
                        for (int i = 0; i < 8; i++) {
                            lv = (lv << 8) | (vb[i] & 0xFFL);
                        }
                        return Long.valueOf(lv);
                    case "int":
                    case "java.lang.Integer":
                        if (vb.length < 4) {
                            return null;
                        }
                        int iv = 0;
                        for (int i = 0; i < 4; i++) {
                            iv = (iv << 8) | (vb[i] & 0xFF);
                        }
                        return Integer.valueOf(iv);
                    case "short":
                    case "java.lang.Short":
                        if (vb.length < 2) {
                            return null;
                        }
                        return Short.valueOf((short) (((vb[0] & 0xFF) << 8) | (vb[1] & 0xFF)));
                    case "byte":
                    case "java.lang.Byte":
                        if (vb.length < 1) {
                            return null;
                        }
                        return Byte.valueOf(vb[0]);
                    case "boolean":
                    case "java.lang.Boolean":
                        if (vb.length < 1) {
                            return null;
                        }
                        return vb[0] == 0 ? Boolean.FALSE : Boolean.TRUE;
                    case "float":
                    case "java.lang.Float":
                        if (vb.length < 4) {
                            return null;
                        }
                        int fi = 0;
                        for (int i = 0; i < 4; i++) {
                            fi = (fi << 8) | (vb[i] & 0xFF);
                        }
                        return Float.valueOf(Float.intBitsToFloat(fi));
                    case "double":
                    case "java.lang.Double":
                        if (vb.length < 8) {
                            return null;
                        }
                        long dl = 0L;
                        for (int i = 0; i < 8; i++) {
                            dl = (dl << 8) | (vb[i] & 0xFFL);
                        }
                        return Double.valueOf(Double.longBitsToDouble(dl));
                    case "java.util.Date":
                        if (vb.length < 8) {
                            return null;
                        }
                        long dt = 0L;
                        for (int i = 0; i < 8; i++) {
                            dt = (dt << 8) | (vb[i] & 0xFFL);
                        }
                        return new java.util.Date(dt);
                    case "java.lang.String":
                    default:
                        int end = vb.length;
                        while (end > 0 && vb[end - 1] == 0) {
                            end--;
                        }
                        if (end == 0) {
                            return "";
                        }
                        return new String(vb, 0, end, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                return null;
            }
        }
    }

    // =================== P14 OQ3：事务句柄（包装 TxManager + DsDatabaseLocal，try-with-resources 自动 rollback） ===================
    /**
     * DsDatabaseLocal 内嵌事务句柄（package-private，仅 facade 入口创建）。 保证 TWR 未 commit →
     * close() 自动 rollback（AC-R12）。
     */
    static final class TransactionHandle implements Transaction {

        private final DsDatabaseLocal db;
        private final TxContext ctx;
        private volatile boolean completed;

        TransactionHandle(DsDatabaseLocal db, TxContext ctx) {
            this.db = db;
            this.ctx = ctx;
            this.completed = false;
        }

        @Override
        public void commit() throws Exception {
            if (completed) {
                throw new IllegalStateException("tx already completed: txId=" + ctx.txId);
            }
            boolean committed = false;
            try {
                db.txManager.commit(ctx);
                committed = true;
                // 关键吞吐优化：applyOpCas 成功后立即释放 2PL 桶锁，不等 commitGroup() checkpoint 节流 + applyMvccUndo 清理
                // commitGroup/checkpoint/applyMvccUndo 不访问 mvccLock 桶级锁，不会 correctness 问题
                db.txManager.releaseAllHeldRowLocks(ctx);
                db.commitGroup();
            } finally {
                // committed==true → 提交成功 clear undo；false=manager.commit 异常内部回滚 → abort restore
                try {
                    db.applyMvccUndo(ctx.txId, !committed);
                } catch (Exception ignoreUndo) {
                    System.err.println("[TransactionHandle] WARN mvccUndo on commit-finally: txId="
                            + ctx.txId + " reason=" + ignoreUndo.getMessage());
                } finally {
                    if (!committed) {
                        // 异常路径兜底（commit 内部异常 = CAS mismatch rollback 已在 txManager 内部 done，这里确保桶锁不泄漏）
                        try {
                            db.txManager.releaseAllHeldRowLocks(ctx);
                        } catch (Exception ignore) {
                        }
                    }
                }
                completed = true;
            }
        }

        @Override
        public void rollback() throws Exception {
            if (completed) {
                return;
            }
            try {
                db.txManager.rollback(ctx);
            } finally {
                try {
                    db.applyMvccUndo(ctx.txId, true);
                } catch (Exception ignoreUndo) {
                    System.err.println("[TransactionHandle] WARN mvccUndo on rollback-finally: txId="
                            + ctx.txId + " reason=" + ignoreUndo.getMessage());
                } finally {
                    db.txManager.releaseAllHeldRowLocks(ctx); // applyMvccUndo 后释放 2PL Strict 行锁，兜底保证不泄漏
                }
                completed = true;
            }
        }

        @Override
        public void close() throws Exception {
            if (!completed) {
                rollback();
            }
        }
    }

    // =================== P14 OQ3：MVCC 事务 undo 记录（仅 DsDatabaseLocal 内部使用） ===================
    /**
     * 事务内 1 次 putEntity(MVCC) 产生的版本链变更 undo 元组。 ABORTING 重放：restoreHead 还原
     * headMap；restorePrevEndTs 还原 prev 版本的 endTs； tombstoneNew 把新写入的
     * versionRecord beginTs 改成 Long.MAX_VALUE（任何 readTs 均不可见）。
     */
    static final class MvccUndo {

        final TableMeta<?> meta;
        final long rowId;
        final long prevVersionId;
        final long prevOrigEndTs;
        final long newVersionId;

        MvccUndo(TableMeta<?> meta, long rowId, long prevVersionId, long prevOrigEndTs, long newVersionId) {
            this.meta = meta;
            this.rowId = rowId;
            this.prevVersionId = prevVersionId;
            this.prevOrigEndTs = prevOrigEndTs;
            this.newVersionId = newVersionId;
        }
    }

    // =================== P14 OQ3：MVCC 事务 undo 重放（commit clear / abort restore） ===================
    /**
     * 事务收尾：处理本 tx 登记的 MVCC undo 列表。
     * <p>
     * abort=false (commit)：仅清空 undo map，版本链已正确（new head 已提交，不改动）。
     * <p>
     * abort=true (rollback)：倒序重放每条 undo，原子还原 head、prev.endTs、并墓碑化新写入 version
     * 使全局不可见。
     *
     * @param txId 事务唯一 ID
     * @param abort true=回滚还原；false=提交清理
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    final void applyMvccUndo(long txId, boolean abort) throws IOException {
        List<MvccUndo> list = txMvccUndoMap.remove(txId);
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!abort) {
            return;
        }

        // 倒序重放（1 tx 内同一 row 多次 update 时，先撤销最新一次）
        for (int i = list.size() - 1; i >= 0; i--) {
            MvccUndo u = list.get(i);
            TableMeta meta = u.meta;
            DsHashMap hm = versionHeadMap(meta);

            // 1) 墓碑化新写入的 newVersion：beginTs=Long.MAX_VALUE,endTs=0 → visibleTo 恒 false
            VersionRecord newVr = readVersionRecord(meta, u.newVersionId);
            if (newVr != null) {
                VersionRecord tombstoneVr = new VersionRecord(
                        Long.MAX_VALUE, 0L, newVr.rowId, newVr.prevVersionId, newVr.dataId);
                rewriteVersionRecordRaw(meta, u.newVersionId, tombstoneVr);
            }

            // 2) 还原 prev 版本的 endTs（如果存在 prev）
            if (u.prevVersionId != 0L) {
                VersionRecord prevVr = readVersionRecord(meta, u.prevVersionId);
                if (prevVr != null && prevVr.endTs != u.prevOrigEndTs) {
                    VersionRecord restored = new VersionRecord(
                            prevVr.beginTs, u.prevOrigEndTs, prevVr.rowId, prevVr.prevVersionId, prevVr.dataId);
                    rewriteVersionRecordRaw(meta, u.prevVersionId, restored);
                }
            }

            // 3) 还原 head：CAS 替换 —— 只有当前 head == 我写的 u.newVersionId 时才改，防止覆盖并发新提交事务的 head（t04 delta 丢失根因）
            //    若当前 head ≠ u.newVersionId（其他事务新挂了 head），不能改 headMap（新 head 归并发事务所有）。
            Long curHeadObj = hm.get(u.rowId);
            if (curHeadObj != null && curHeadObj.longValue() == u.newVersionId) {
                if (u.prevVersionId == 0L) {
                    // INSERT→ABORT：newVersionId 位置重写 visible 恒 true 的 dataId=0 墓碑版本
                    VersionRecord insertTombstone = new VersionRecord(
                            1L, Long.MAX_VALUE, u.rowId, 0L, 0L);
                    rewriteVersionRecordRaw(meta, u.newVersionId, insertTombstone);
                    hm.put(u.rowId, u.newVersionId);
                } else {
                    hm.put(u.rowId, u.prevVersionId);
                }
            }
        }
    }

    /**
     * 全量重写 versionRecord（无 offset 转换语义，直接按传入 vr 各字段编码写回同 versionId 位置）。
     */
    @SuppressWarnings("rawtypes")
    private void rewriteVersionRecordRaw(TableMeta meta, long versionId, VersionRecord vr) throws IOException {
        int length = bucketUnitSize(versionId);
        bucketStore.update(mvccSpace(meta), MVCC_VERSION_TYPE, versionId, encodeVersionRecord(vr, length),
                DsFixedBucketStore.UpdatePolicy.KEEP_BUCKET);
    }

    // =================== P11 OQ1：RowDataBackend（行 payload Registry 双写/回填/删除） ===================
    /**
     * DsDatabaseLocal 行 payload Backend：对外 3 个 final package-private
     * helper（read/write/remove）， 路由到父类的 5 binary helper。写/删时传 NOOP bucket
     * 侧函数（bucket 已由 DsDatabaseLocal 原逻辑先写）。
     */
    static final class RowDataBackend extends AbstractRegistryDataBackend {

        final DsDatabaseLocal outer;

        /**
         * @deprecated 仅架构反射审计（c09 E4-c）用，严禁业务调用
         */
        @Deprecated
        @SuppressWarnings("unused")
        RowDataBackend(File dbRoot) throws IOException {
            super(dbRoot);
            this.outer = null;
        }

        RowDataBackend(File dbRoot, DsDatabaseLocal outer) throws IOException {
            super(dbRoot);
            this.outer = outer;
        }

        void writeRowPayload(String entityClassName, long rowId, byte[] payload) throws IOException {
            final String path = RegistryMetaPaths.dataRowPayload(entityClassName, rowId);
            writeLeafBytes(path, payload, v -> {
                /* bucket 已由 DsDatabaseLocal 写过，此处不重写 */ });
        }

        byte[] readRowPayload(String entityClassName, long rowId, YamlSupplier<byte[]> bucketGetter) throws IOException {
            final String path = RegistryMetaPaths.dataRowPayload(entityClassName, rowId);
            return readLeafBytes(path, bucketGetter, null);
        }

        /**
         * TxOpRegistry 用：读 Backend 历史 payload；若 absent 返回 new
         * byte[0]（registerRowPut 已经转 null→0B）。
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        byte[] readRowPayload(String entityClassName, long rowId, int rowLength) throws IOException {
            final String path = RegistryMetaPaths.dataRowPayload(entityClassName, rowId);
            return readLeafBytes(path, () -> {
                try {
                    Class entityClass = Class.forName(entityClassName);
                    TableMeta meta = outer.metaOf(entityClass);
                    byte[] b = outer.bucketStore.get(
                            meta.space, meta.type, rowId, 0, rowLength);
                    if (b == null || com.q3lives.ds.database.DsDatabaseLocal.isAllZero(b)) {
                        return null;
                    }
                    return b;
                } catch (Exception ex) {
                    return null;
                }
            }, null);
        }

        void removeRowPayload(String entityClassName, long rowId) throws IOException {
            final String path = RegistryMetaPaths.dataRowPayload(entityClassName, rowId);
            removeLeafBytes(path, () -> Boolean.FALSE);
        }
    }

}
