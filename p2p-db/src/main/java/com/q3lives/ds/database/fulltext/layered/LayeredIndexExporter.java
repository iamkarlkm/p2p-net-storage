package com.q3lives.ds.database.fulltext.layered;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P42 分层倒排导出器（Layered Index Exporter）。
 *
 * <p>
 * 把 P22 {@code WeightStore} 已驻内存的分层倒排三列 —— docStat（6 level {@code docLen}/{@code nTerms}）、
 * termStat（{@code idf} + {@code payload}）、tf（{@code mix4(uid,docId,parentId,level)} 键）—— 原样搬运
 * 进 P37 {@link DsIndexSegmentWriter}，由 writer 完成排序 / 列式打包 / LZ4 / 4KB 对齐 / 4×CRC32，
 * 最后 {@code finish()} 原子落盘并 {@code store.append()} 注册，产出不可变 P37 段（R5：段格式 0 变更）。
 *
 * <p>
 * <b>导出即快照</b>：不重编码 uid（R6 USER-HC-0：uid 全局唯一，直接复用 WeightStore 键）、不新增 level
 * 维度（分层已由段格式承载）→ 同源同批导出的段指纹跨进程 / 跨 run 恒定（R7）。
 *
 * <p>
 * <b>崩溃安全（R9）</b>：严格 {@code writer.finish()} <b>先于</b> {@code store.append()}。任一崩溃点下，
 * 未注册的半成品段不在 manifest，{@code list()} 不可见。
 *
 * <p>
 * <b>唯一上游触碰（R4 / §0 方案 A）</b>：为枚举 termStat 键集与 tf 键→值映射，本类依赖 P21
 * {@code FulltextStore} append-only 追加的 2 个只读 accessor
 * （{@code weightStoreAllTermKeys} / {@code weightStoreAllTfEntries}）；P21 既有方法 / 内嵌类型 0 改。
 *
 * <p>
 * 4 条 IAE 守卫：① ft 为 null ② store 为 null ③ segId &lt; 0 ④ segId 已注册（防重复导出覆盖）。
 *
 * <p>
 * 红线：本类位于新子包 {@code fulltext.layered}（ZeroLeak3=3 永保）；P37 {@code segment/} 与 P40
 * {@code compact/} 源码 0 改；0 新增 Maven dep（R2 NG-3）。
 */
public final class LayeredIndexExporter {

    /**
     * 创建无状态导出器（0 字段、0 资源持有；导出参数全部由 {@link #export} 传入）。
     */
    public LayeredIndexExporter() {
    }

    /**
     * 把 {@code ft} 的内存分层倒排导出为 P37 段并注册到 {@code store}。
     *
     * @param ft     源全文存储（提供分层倒排只读枚举），非 null
     * @param store  目标段仓库（{@code rootDir/segment/}），非 null
     * @param segId  新段 id，≥ 0 且尚未注册
     * @return 本次导出的不可变快照描述
     */
    public LayeredSnapshot export(FulltextStore ft, DsIndexSegmentStore store, long segId) throws IOException {
        if (ft == null) {
            throw new IllegalArgumentException("export ft null");
        }
        if (store == null) {
            throw new IllegalArgumentException("export store null");
        }
        if (segId < 0) {
            throw new IllegalArgumentException("export segId < 0: " + segId);
        }
        for (long registered : store.list()) {
            if (registered == segId) {
                throw new IllegalArgumentException("export segId already registered: " + segId);
            }
        }
        DsIndexSegmentWriter writer = new DsIndexSegmentWriter(store.segmentFile(segId));

        Set<Long> docKeys = ft.weightStoreAllDocKeysForTest();
        for (Long docId : docKeys) {
            if (docId == null) {
                continue;
            }
            FulltextStore.WeightDocStatRow row = ft.getDocStatForTest(docId);
            if (row == null) {
                continue;
            }
            writer.addDocStat(docId, row.docLen, row.nTerms);
        }

        Set<Long> termKeys = ft.weightStoreAllTermKeys();
        for (Long uid : termKeys) {
            if (uid == null) {
                continue;
            }
            FulltextStore.WeightTermStatRow row = ft.getTermStatForTest(uid);
            if (row == null) {
                continue;
            }
            writer.addTermStat(uid, row.idf, row.payload);
        }

        Map<Long, Integer> tfEntries = ft.weightStoreAllTfEntries();
        for (Map.Entry<Long, Integer> e : tfEntries.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            writer.addTf(e.getKey(), e.getValue());
        }

        DsIndexSegment seg = writer.finish();
        store.append(segId, seg);
        return new LayeredSnapshot(segId, seg.docCount, seg.termCount, seg.tfCount, seg.fingerprint());
    }
}
