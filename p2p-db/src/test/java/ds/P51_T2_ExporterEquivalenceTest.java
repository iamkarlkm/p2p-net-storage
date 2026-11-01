package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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
 * P51 T2：导出器等价性（6 TR）。
 *
 * <p>
 * 以真实 {@code FulltextStore.ingestDoc} 小语料为源，导出词网段后逐项与内存态真值对照：TR2.1
 * {@code ctxCount}、TR2.2 {@code adjCount}、TR2.3 {@code posCount}、TR2.4 逐 (ctx,srcUid) L/R 邻接
 * 全等、TR2.5 逐 (ctx,uid) positions 区间全等、TR2.6 空词网导出（0/0/0 合法段可读）。
 */
public class P51_T2_ExporterEquivalenceTest {

    private static final long DOC_A = 101L;
    private static final long DOC_B = 202L;
    private static final String TEXT_A = "订阅推送服务稳定运行，提供高质量的文章订阅内容分发";
    private static final String TEXT_B = "服务端订阅数据同步，分布式推送协议实现稳定可靠";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private TermDictStore td;
    private WordNetSnapshot snap;
    private WordNetSegmentView view;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p51-t2-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        td = store.getTermDict();
        store.ingestDoc(DOC_A, TEXT_A);
        store.ingestDoc(DOC_B, TEXT_B);
        snap = new WordNetExporter().export(store, rootDir.getAbsolutePath(), 10L);
        view = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{10L});
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

    // ---------------- TR2.1 ctxCount == 内存态邻接索引键数 ----------------
    @Test(timeout = 60000)
    public void tr21_ctxCountEqualsMemoryAdjacencyIndexSize() throws IOException {
        long expect = 0L;
        int ctxs = 0;
        for (long[] ctx : store.wordNetAllContextKeys()) {
            ctxs++;
            Set<Long> srcs = new LinkedHashSet<>();
            for (long[] n : store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2])) {
                srcs.add(n[0]);
            }
            expect += srcs.size();
        }
        Assert.assertTrue("TR2.1 语料须产生上下文键", ctxs > 0);
        Assert.assertTrue("TR2.1 语料须产生邻接索引键", expect > 0L);
        Assert.assertEquals("TR2.1 ctxCount == 内存态 (ctx,srcUid) 键数", expect, snap.ctxCount);
    }

    // ---------------- TR2.2 adjCount == 内存态 L/R 邻接条目数 ----------------
    @Test(timeout = 60000)
    public void tr22_adjCountEqualsMemoryAdjacencyEntries() throws IOException {
        long expect = 0L;
        for (long[] ctx : store.wordNetAllContextKeys()) {
            expect += store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2]).size();
        }
        Assert.assertTrue("TR2.2 语料须产生邻接条目", expect > 0L);
        Assert.assertEquals("TR2.2 adjCount == 内存态邻接条目数", expect, snap.adjCount);
        Assert.assertTrue("TR2.2 adjCount ≥ ctxCount", snap.adjCount >= snap.ctxCount);
    }

    // ---------------- TR2.3 posCount == 内存态 positions 区间数 ----------------
    @Test(timeout = 60000)
    public void tr23_posCountEqualsMemoryPositionsSpans() throws IOException {
        long expect = 0L;
        for (long[] ctx : store.wordNetAllContextKeys()) {
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
        Assert.assertTrue("TR2.3 语料须产生 positions 区间", expect > 0L);
        Assert.assertEquals("TR2.3 posCount == 内存态 positions 区间数", expect, snap.posCount);
    }

    // ---------------- TR2.4 逐 (ctx,srcUid) L/R 邻接 (uid,dir,d) 全等 ----------------
    @Test(timeout = 60000)
    public void tr24_adjacencyPerCtxSrcUidEqual() throws IOException {
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
                Assert.assertEquals("TR2.4 邻接集全等 doc=" + docId + " p=" + parentId + " lv=" + level
                        + " src=" + srcUid + " mem=" + mem + " seg=" + seg, mem, seg);
                checked++;
            }
        }
        Assert.assertTrue("TR2.4 至少校验 1 个 (ctx,srcUid)", checked > 0);
    }

    // ---------------- TR2.5 逐 (ctx,uid) positions 区间全等 ----------------
    @Test(timeout = 60000)
    public void tr25_positionsPerCtxUidEqual() throws IOException {
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
                Assert.assertEquals("TR2.5 positions 区间全等 uid=" + u + " doc=" + docId + " lv=" + level,
                        toKeyList(mem), toKeyList(seg));
                if (!mem.isEmpty()) {
                    checked++;
                }
            }
        }
        Assert.assertTrue("TR2.5 至少校验 1 个非空 positions", checked > 0);
    }

    // ---------------- TR2.6 空词网导出（0/0/0 合法段可读） ----------------
    @Test(timeout = 60000)
    public void tr26_emptyWordNetExportReadable() throws IOException {
        File emptyRoot = tmp.newFolder("p51-t2-empty-" + UUID.randomUUID().toString().replace("-", ""));
        FulltextStore emptyStore = new FulltextStore(emptyRoot.getAbsolutePath());
        try {
            WordNetSnapshot s = new WordNetExporter().export(emptyStore, emptyRoot.getAbsolutePath(), 20L);
            Assert.assertEquals("TR2.6 空词网 ctxCount=0", 0L, s.ctxCount);
            Assert.assertEquals("TR2.6 空词网 adjCount=0", 0L, s.adjCount);
            Assert.assertEquals("TR2.6 空词网 posCount=0", 0L, s.posCount);
            Assert.assertTrue("TR2.6 段指纹 64-hex", s.segmentFingerprint.matches("[0-9a-f]{64}"));
            try (WordNetSegmentView emptyView = WordNetSegmentView.open(emptyRoot.getAbsolutePath(),
                    new long[]{20L})) {
                Assert.assertEquals("TR2.6 空段视图段数=1", 1, emptyView.segmentCount());
                Assert.assertTrue("TR2.6 空段邻接查询为空",
                        emptyView.neighborsWithin(123L, DOC_A, DOC_A * 10L + 1L, 1, 5).isEmpty());
                Assert.assertEquals("TR2.6 空段距离查询=-1", -1,
                        emptyView.distanceBetween(1L, 2L, DOC_A, DOC_A * 10L + 1L, 1));
                Assert.assertTrue("TR2.6 空段 positions 查询为空",
                        emptyView.positions(123L, DOC_A, DOC_A * 10L + 1L, 1).isEmpty());
                Assert.assertTrue("TR2.6 空段 topK 查询为空",
                        emptyView.topKNeighbors(123L, DOC_A, DOC_A * 10L + 1L, 1, 5).isEmpty());
                Assert.assertTrue("TR2.6 空段视图指纹 64-hex",
                        emptyView.fingerprint().matches("[0-9a-f]{64}"));
            }
        } finally {
            emptyStore.close();
        }
    }

    private static Set<String> toKeySet(Set<Neighbor> ns) {
        Set<String> out = new HashSet<>();
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private static Set<String> toKeySet(List<Neighbor> ns) {
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
