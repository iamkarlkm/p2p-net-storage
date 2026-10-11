package com.q3lives.ds.database.tx;

import java.util.Arrays;

/**
 * 2PC 7 Op 类型 → 业务模块的接入分发点。
 * <p>
 * 本类不直接做真正的写操作（写操作仍由原模块 DsDatabaseLocal/ColumnarStore/EqIndexStore 执行），
 * 只负责：① 在事务 begin 期内，捕获每条写入的 {@code (registryPath, expectedPrevBytes, newPayload)}；
 * ② 把这些三元组包装成 {@link TxOp} 调用 TxContext.addOp 登记；③ 调用方保证登记后 commit/rollback 会由
 * DsTransactionManager 统一 applyOpCas / restoreOpPrev 完成 CAS 与 byte-for-byte 还原。
 * <p>
 * 零新增 Maven：所有读 prevBytes 复用 P11 RowDataBackend/Column/Group Backend 提供的 readLeafBytes；
 * 写 CAS 复用 P11 AbstractRegistryDataBackend.atomicModifyBytes（256-striped lock）。
 */
public final class TxOpRegistry {

    private TxOpRegistry() {}

    /** 登记 ROW_PUT：entityClass + rowId 行 payload 更新，强制记录 oldBytes（rollback 100% 还原）。 */
    public static int registerRowPut(TxContext ctx,
                                     String entityClassName,
                                     long rowId,
                                     String dataRowPayloadRegistryPath,
                                     byte[] oldBytes,
                                     byte[] newRowPayloadBytes) {
        ensureTx(ctx, "ROW_PUT");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        byte[] payload = (newRowPayloadBytes == null) ? new byte[0] : newRowPayloadBytes;
        TxOp op = new TxOp(TxOp.TYPE_ROW_PUT, dataRowPayloadRegistryPath,
            Arrays.copyOf(prev, prev.length),
            Arrays.copyOf(payload, payload.length));
        return ctx.addOp(op);
    }

    /** 登记 ROW_DEL：行 payload 删除（newPayload = 0B；oldBytes 必须由调用方 readLeafBytes 先取）。 */
    public static int registerRowDel(TxContext ctx,
                                     String entityClassName,
                                     long rowId,
                                     String dataRowPayloadRegistryPath,
                                     byte[] oldBytes) {
        ensureTx(ctx, "ROW_DEL");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        TxOp op = new TxOp(TxOp.TYPE_ROW_DEL, dataRowPayloadRegistryPath,
            Arrays.copyOf(prev, prev.length),
            new byte[0]);
        return ctx.addOp(op);
    }

    /** 登记 COL_PUT：列单值 payload 更新。prev 必须 = 更新前 bytes（若 absent = 0B）。 */
    public static int registerColPut(TxContext ctx,
                                     String entityClassName,
                                     String logicalColName,
                                     long rowId,
                                     String dataColumnPayloadRegistryPath,
                                     byte[] oldBytes,
                                     byte[] newColValueBytes) {
        ensureTx(ctx, "COL_PUT");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        byte[] payload = (newColValueBytes == null) ? new byte[0] : newColValueBytes;
        TxOp op = new TxOp(TxOp.TYPE_COL_PUT, dataColumnPayloadRegistryPath,
            Arrays.copyOf(prev, prev.length),
            Arrays.copyOf(payload, payload.length));
        return ctx.addOp(op);
    }

    /** 登记 GROUP_PUT：复合组 payload（package-private 组内部结构）更新。 */
    public static int registerGroupPut(TxContext ctx,
                                       String entityClassName,
                                       String groupName,
                                       long rowId,
                                       String dataGroupPayloadRegistryPath,
                                       byte[] oldBytes,
                                       byte[] newGroupBytes) {
        ensureTx(ctx, "GROUP_PUT");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        byte[] payload = (newGroupBytes == null) ? new byte[0] : newGroupBytes;
        TxOp op = new TxOp(TxOp.TYPE_GROUP_PUT, dataGroupPayloadRegistryPath,
            Arrays.copyOf(prev, prev.length),
            Arrays.copyOf(payload, payload.length));
        return ctx.addOp(op);
    }

    /** 登记 INDEX_ADD：eq 索引 rowId→valueBytes 键增加（newPayload 可能 = rowId 8B；prev = 删除前值或 0B）。 */
    public static int registerIndexAdd(TxContext ctx,
                                       String eqIndexRegistryPath,
                                       byte[] oldBytes,
                                       byte[] rowIdOrComposite) {
        ensureTx(ctx, "INDEX_ADD");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        byte[] payload = (rowIdOrComposite == null) ? new byte[0] : rowIdOrComposite;
        TxOp op = new TxOp(TxOp.TYPE_INDEX_ADD, eqIndexRegistryPath,
            Arrays.copyOf(prev, prev.length),
            Arrays.copyOf(payload, payload.length));
        return ctx.addOp(op);
    }

    /** 登记 INDEX_REMOVE：eq 索引删除（prev 必须由调用方先取，用于 ABORTING 回滚还原原 rowId）。 */
    public static int registerIndexRemove(TxContext ctx,
                                          String eqIndexRegistryPath,
                                          byte[] oldBytes) {
        ensureTx(ctx, "INDEX_REMOVE");
        byte[] prev = (oldBytes == null) ? new byte[0] : oldBytes;
        TxOp op = new TxOp(TxOp.TYPE_INDEX_REMOVE, eqIndexRegistryPath,
            Arrays.copyOf(prev, prev.length),
            new byte[0]);
        return ctx.addOp(op);
    }

    /** 登记 BLOOM_SET：BloomFilter64 全量 toBytes 覆盖写（P13 BloomFilter64#toBytes）。 */
    public static int registerBloomSet(TxContext ctx,
                                       String indexEqBloomLeafRegistryPath,
                                       byte[] oldBloomBytes,
                                       byte[] newBloomBytes) {
        ensureTx(ctx, "BLOOM_SET");
        byte[] prev = (oldBloomBytes == null) ? new byte[0] : oldBloomBytes;
        byte[] payload = (newBloomBytes == null) ? new byte[0] : newBloomBytes;
        TxOp op = new TxOp(TxOp.TYPE_BLOOM_SET, indexEqBloomLeafRegistryPath,
            Arrays.copyOf(prev, prev.length),
            Arrays.copyOf(payload, payload.length));
        return ctx.addOp(op);
    }

    private static void ensureTx(TxContext ctx, String opType) {
        if (ctx == null) throw new IllegalStateException("no active TxContext for op=" + opType);
        if (ctx.closedFlag) throw new IllegalStateException("tx closed: op=" + opType + " txId=" + ctx.txId);
    }
}
