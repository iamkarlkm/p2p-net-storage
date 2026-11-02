package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot;

/**
 * P54 T2：增量导出等价性（6 TR）。
 *
 * <p>
 * 先对 DOC_A 全量导出 base 段，再 ingest DOC_B 后仅按 DOC_B 上下文增量导出 delta 段；叠加视图
 * {@code [base,delta]} 与「当前状态全量导出」逐条等价：TR2.1 {@code ctxCount}、TR2.2
 * {@code adjCount}、TR2.3 {@code posCount}、TR2.4 逐 (ctx,srcUid) L/R 邻接全等、TR2.5 逐
 * (ctx,uid) positions 全等、TR2.6 空 {@code changedCtx} 合法段。
 */
public class P54_T2_DeltaExportEquivalenceTest {

    private static final long DOC_A = 601L;
    private static final long DOC_B = 602L;
    private static final String TEXT_A = "订阅推送服务稳定运行，提供高质量的文章订阅内容分发";
    private static final String TEXT_B = "服务端订阅数据同步，分布式推送协议实现稳定可靠";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private TermDictStore td;
    private WordNetSnapshot base;
    private WordNetSnapshot delta;
    private List<long[]> deltaCtx;
    private WordNetSegmentView view;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p54-t2-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        td = store.getTermDict();
        store.ingestDoc(DOC_A, TEXT_A);
        base = new WordNetExporter().export(store, rootDir.getAbsolutePath(), 10L);

        store.ingestDoc(DOC_B, TEXT_B);
        deltaCtx = new ArrayList<>();
        for (long[] c : store.wordNetAllContextKeys()) {
            if (c[0] == DOC_B) {
                deltaCtx.add(c);
            }
        }
        delta = new WordNetExporter().exportDelta(store, rootDir.getAbsolutePath(), 11L, deltaCtx);
        new WordNetExporter().export(store, rootDir.getAbsolutePath(), 12L);
        view = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{10L, 11L});
    }

    @After
    public void after() {
        try {
            if (view != null) {
                view.close();
            }
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
        try {
            store.close();
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
    }

    // ---------------- TR2.1 delta ctxCount == DOC_B 变更上下文键数 ----------------
    @Test(timeout = 60000)
    public void tr21_deltaCtxCount() throws IOException {
        long expect = 0L;
        for (long[] ctx : deltaCtx) {
            Set<Long> srcs = new LinkedHashSet<>();
            for (long[] n : store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2])) {
                srcs.add(n[0]);
            }
            expect += srcs.size();
        }
        Assert.assertTrue("TR2.1 DOC_B 须产生上下文键", expect > 0L);
        Assert.assertEquals("TR2.1 delta ctxCount == DOC_B (ctx,srcUid) 键数", expect, delta.ctxCount);
        Assert.assertTrue("TR2.1 base 仅含 DOC_A（ctxCount < delta 叠加）", base.ctxCount > 0L);
    }

    // ---------------- TR2.2 delta adjCount == DOC_B 邻接条目数 ----------------
    @Test(timeout = 60000)
    public void tr22_deltaAdjCount() throws IOException {
        long expect = 0L;
        for (long[] ctx : deltaCtx) {
            expect += store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2]).size();
        }
        Assert.assertTrue("TR2.2 DOC_B 须产生邻接条目", expect > 0L);
        Assert.assertEquals("TR2.2 delta adjCount == DOC_B 邻接条目数", expect, delta.adjCount);
    }

    // ---------------- TR2.3 delta posCount == DOC_B positions 区间数 ----------------
    @Test(timeout = 60000)
    public void tr23_deltaPosCount() throws IOException {
        long expect = 0L;
        for (long[] ctx : deltaCtx) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            Set<Long> uids = new LinkedHashSet<>();
            for (TuRow r : store.selectByParentLevel(docId, parentId, level)) {
                uids.add(r.unit_id());
            }
            for (Long u : uids) {
                expect += store.wordNetPositionsForCtx(u.longValue(), docId, parentId, level).size();
            }
        }
        Assert.assertTrue("TR2.3 DOC_B 须产生 positions 区间", expect > 0L);
        Assert.assertEquals("TR2.3 delta posCount == DOC_B positions 区间数", expect, delta.posCount);
    }

    // ---------------- TR2.4 叠加视图 [base,delta] 邻接 ≡ 内存真值 ----------------
    @Test(timeout = 60000)
    public void tr24_overlayAdjacencyEqualsMemory() throws IOException {
        int checked = 0;
        for (long[] ctx : store.wordNetAllContextKeys()) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            Set<Long> srcs = new LinkedHashSet<>();
            for (long[] n : store.wordNetNeighborsForCtx(docId, parentId, level)) {
                srcs.add(n[0]);
            }
            for (Long s : srcs) {
                long srcUid = s.longValue();
                Set<String> mem = toKeySet(store.neighborsWithin(td.toText(srcUid), docId, parentId, level,
                        FulltextStore.DIST_MAX));
                Set<String> seg = toKeySet(view.neighborsWithin(srcUid, docId, parentId, level,
                        FulltextStore.DIST_MAX));
                Assert.assertEquals("TR2.4 叠加邻接全等 doc=" + docId + " lv=" + level + " src=" + srcUid,
                        mem, seg);
                checked++;
            }
        }
        Assert.assertTrue("TR2.4 至少校验 1 个 (ctx,srcUid)", checked > 0);
    }

    // ---------------- TR2.5 叠加视图 [base,delta] positions ≡ 内存真值 ----------------
    @Test(timeout = 60000)
    public void tr25_overlayPositionsEqualsMemory() throws IOException {
        int checked = 0;
        for (long[] ctx : store.wordNetAllContextKeys()) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            Set<Long> uids = new LinkedHashSet<>();
            for (TuRow r : store.selectByParentLevel(docId, parentId, level)) {
                uids.add(r.unit_id());
            }
            for (Long u : uids) {
                List<PositionSpan> mem = store.getTermPositionsForTest(u.longValue(), docId, parentId, level);
                List<PositionSpan> seg = view.positions(u.longValue(), docId, parentId, level);
                Assert.assertEquals("TR2.5 叠加 positions 全等 uid=" + u + " doc=" + docId,
                        toKeyList(mem), toKeyList(seg));
                if (!mem.isEmpty()) {
                    checked++;
                }
            }
        }
        Assert.assertTrue("TR2.5 至少校验 1 个非空 positions", checked > 0);
    }

    // ---------------- TR2.6 空 changedCtx 合法段可读 ----------------
    @Test(timeout = 60000)
    public void tr26_emptyChangedCtxReadable() throws IOException {
        WordNetSnapshot s = new WordNetExporter().exportDelta(store, rootDir.getAbsolutePath(), 20L,
                new ArrayList<long[]>());
        Assert.assertEquals("TR2.6 空变更 ctxCount=0", 0L, s.ctxCount);
        Assert.assertEquals("TR2.6 空变更 adjCount=0", 0L, s.adjCount);
        Assert.assertEquals("TR2.6 空变更 posCount=0", 0L, s.posCount);
        try (WordNetSegmentView v = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{20L})) {
            Assert.assertEquals("TR2.6 段数=1", 1, v.segmentCount());
            Assert.assertTrue("TR2.6 空段邻接查询为空",
                    v.neighborsWithin(123L, DOC_A, DOC_A * 10L + 1L, 1, 5).isEmpty());
            Assert.assertTrue("TR2.6 空段 positions 查询为空",
                    v.positions(123L, DOC_A, DOC_A * 10L + 1L, 1).isEmpty());
        }
    }

    // ---------------- helpers ----------------
    private static Set<String> toKeySet(Collection<Neighbor> ns) {
        Set<String> out = new HashSet<>();
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private static List<String> toKeyList(List<PositionSpan> spans) {
        List<String> out = new ArrayList<>(spans.size());
        for (PositionSpan s : spans) {
            out.add(s.start + "-" + s.end);
        }
        return out;
    }
}
