package com.q3lives.ds.database.fulltext.compact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P40 段合并编排器（Compaction Runner）：把一份 {@link SegmentCompactionPlan} 落成一次真实合并。
 *
 * <p>
 * 执行顺序严格遵循「<b>append-before-remove</b>」（R9 崩溃安全）：
 * <ol>
 *   <li>打开全部源段 reader（旧 → 新 = segId 升序），驱动 {@link SegmentCompactor} 产出新段文件；</li>
 *   <li>{@code store.append(mergedSegId, merged)} —— <b>先</b>登记产物段；</li>
 *   <li>关闭全部源 reader（释放 mmap，Windows 下才可删除源段文件）；</li>
 *   <li>{@code store.remove(sourceSegId)} —— <b>后</b>删除源段。</li>
 * </ol>
 *
 * <p>
 * 崩溃安全：任意中断点下「源段 + 产物段」至多共存（不会丢数据）；此时以「新段优先」的
 * {@link SegmentMergeView} 查询结果与合并前逐条等价，故中断后重跑即可收敛（产物 id 已注册时
 * {@link #run} 会按守卫抛 IAE，由调用方取新的 {@code max+1}）。
 *
 * <p>4 条 IAE 守卫：① store 为 null ② plan 为 null ③ 源段未注册 ④ mergedSegId 已注册。
 *
 * <p>红线：本类位于新子包 {@code fulltext.compact}（ZeroLeak3=3 永保）；P37 源码 0 改。
 */
public final class SegmentCompactionRunner implements AutoCloseable {

    private final DsIndexSegmentStore store;

    /**
     * 创建编排器。
     *
     * @param store 目标段仓库，非 null
     */
    public SegmentCompactionRunner(DsIndexSegmentStore store) {
        if (store == null) {
            throw new IllegalArgumentException("runner store null");
        }
        this.store = store;
    }

    /**
     * 执行一次合并：产新段 → append 先 → remove 后。
     *
     * @param plan 合并计划，非 null；源段须已注册、mergedSegId 须未注册
     * @return 产物段描述对象
     */
    public DsIndexSegment run(SegmentCompactionPlan plan) throws IOException {
        if (plan == null) {
            throw new IllegalArgumentException("runner plan null");
        }
        long[] registered = store.list();
        for (long id : plan.sourceSegIds) {
            if (!contains(registered, id)) {
                throw new IllegalArgumentException("runner source not registered: " + id);
            }
        }
        if (contains(registered, plan.mergedSegId)) {
            throw new IllegalArgumentException("runner merged already registered: " + plan.mergedSegId);
        }

        List<DsIndexSegmentReader> readers = new ArrayList<>(plan.sourceSegIds.length);
        DsIndexSegment merged;
        try {
            for (long id : plan.sourceSegIds) {
                readers.add(store.openReader(id));
            }
            merged = new SegmentCompactor(store).compact(store.segmentFile(plan.mergedSegId), readers);
            store.append(plan.mergedSegId, merged);
        } finally {
            for (DsIndexSegmentReader r : readers) {
                r.close();
            }
        }
        for (long id : plan.sourceSegIds) {
            store.remove(id);
        }
        return merged;
    }

    /**
     * 关闭底层段仓库（{@link DsIndexSegmentStore#close()} 为幂等空实现）。
     */
    @Override
    public void close() {
        store.close();
    }

    private static boolean contains(long[] ids, long v) {
        for (long id : ids) {
            if (id == v) {
                return true;
            }
        }
        return false;
    }
}
