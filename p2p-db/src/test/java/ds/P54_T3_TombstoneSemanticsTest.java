package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
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

/**
 * P54 T3：tombstone 语义（5 TR）。
 *
 * <p>
 * 真实词网导出数据段 1 后，对某个 srcUid 邻接键与某个 uid 的 positions 键写墓碑段 2：TR3.1 墓碑遮蔽邻接
 * （{@code neighborsWithin} 空 + {@code distanceBetween} = -1）；TR3.2 墓碑遮蔽
 * positions（空）；TR3.3 段序显式（反向段序 {2,1} 时旧数据仍可见）；TR3.4 {@code fingerprint}
 * 随墓碑段变化；TR3.5 旧读者兼容（哨兵 payload = -1 零迭代不抛）+ 墓碑键之外查询不受影响。
 */
public class P54_T3_TombstoneSemanticsTest {

    private static final long DOC = 700L;
    private static final String TEXT = "订阅推送服务稳定运行提供高质量文章订阅内容分发一致性协议";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private TermDictStore td;
    private long docId;
    private long parentId;
    private int level;
    private long srcUid;
    private long otherSrcUid;
    private long posUid;
    private long rightUid;
    private long adjKey;
    private long posKey;
    private WordNetSegmentView view12;
    private WordNetSegmentView view21;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p54-t3-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        td = store.getTermDict();
        store.ingestDoc(DOC, TEXT);

        // 选一个 srcUid 最多、且含 positions 的上下文。
        long[] bestCtx = null;
        Set<Long> bestSrcs = null;
        for (long[] ctx : store.wordNetAllContextKeys()) {
            Set<Long> srcs = new LinkedHashSet<>();
            for (long[] n : store.wordNetNeighborsForCtx(ctx[0], ctx[1], (int) ctx[2])) {
                srcs.add(n[0]);
            }
            if (bestSrcs == null || srcs.size() > bestSrcs.size()) {
                bestCtx = ctx;
                bestSrcs = srcs;
            }
        }
        Assert.assertNotNull("TR 语料须产生上下文", bestCtx);
        Assert.assertTrue("TR 语料须产生至少 2 个 srcUid", bestSrcs.size() >= 2);
        docId = bestCtx[0];
        parentId = bestCtx[1];
        level = (int) bestCtx[2];

        List<Long> srcList = new ArrayList<>(bestSrcs);
        srcUid = srcList.get(0);
        otherSrcUid = srcList.get(1);

        // 取该 srcUid 的一个右侧邻居 uid 作为 distanceBetween 的 right。
        rightUid = otherSrcUid;
        for (long[] n : store.wordNetNeighborsForCtx(docId, parentId, level)) {
            if (n[0] == srcUid) {
                rightUid = n[1];
                break;
            }
        }

        // 选一个含 positions 的 uid。
        posUid = -1L;
        for (TuRow r : store.selectByParentLevel(docId, parentId, level)) {
            if (!store.getTermPositionsForTest(r.unit_id(), docId, parentId, level).isEmpty()) {
                posUid = r.unit_id();
                break;
            }
        }
        Assert.assertTrue("TR 语料须产生 positions", posUid > 0L);

        new WordNetExporter().export(store, rootDir.getAbsolutePath(), 1L);
        adjKey = ctxKey(docId, parentId, level, srcUid);
        posKey = posKey(posUid, docId, parentId, level);
        new WordNetExporter().tombstone(rootDir.getAbsolutePath(), 2L, new long[]{adjKey}, new long[]{posKey});

        view12 = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{1L, 2L});
        view21 = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{2L, 1L});
    }

    @After
    public void after() {
        try {
            if (view12 != null) {
                view12.close();
            }
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
        try {
            if (view21 != null) {
                view21.close();
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

    // ---------------- TR3.1 墓碑遮蔽邻接 ----------------
    @Test(timeout = 60000)
    public void tr31_tombstoneHidesAdjacency() throws IOException {
        Assert.assertFalse("TR3.1 内存真值非空（前置）",
                store.neighborsWithin(td.toText(srcUid), docId, parentId, level, FulltextStore.DIST_MAX).isEmpty());
        Assert.assertTrue("TR3.1 墓碑后邻接为空",
                view12.neighborsWithin(srcUid, docId, parentId, level, FulltextStore.DIST_MAX).isEmpty());
        Assert.assertEquals("TR3.1 墓碑后距离 = -1", -1,
                view12.distanceBetween(srcUid, rightUid, docId, parentId, level));
        Assert.assertTrue("TR3.1 墓碑后 topK 为空",
                view12.topKNeighbors(srcUid, docId, parentId, level, 5).isEmpty());
    }

    // ---------------- TR3.2 墓碑遮蔽 positions ----------------
    @Test(timeout = 60000)
    public void tr32_tombstoneHidesPositions() throws IOException {
        Assert.assertFalse("TR3.2 内存真值非空（前置）",
                store.getTermPositionsForTest(posUid, docId, parentId, level).isEmpty());
        Assert.assertTrue("TR3.2 墓碑后 positions 为空",
                view12.positions(posUid, docId, parentId, level).isEmpty());
    }

    // ---------------- TR3.3 段序显式：反向段序时旧数据仍可见 ----------------
    @Test(timeout = 60000)
    public void tr33_segmentOrderDeterminesVisibility() throws IOException {
        Set<String> mem = toKeySet(store.neighborsWithin(td.toText(srcUid), docId, parentId, level,
                FulltextStore.DIST_MAX));
        Set<String> reversed = toKeySet(view21.neighborsWithin(srcUid, docId, parentId, level,
                FulltextStore.DIST_MAX));
        Assert.assertFalse("TR3.3 反向段序须恢复旧数据（非空）", mem.isEmpty());
        Assert.assertEquals("TR3.3 反向段序 {2,1} 邻接 ≡ 内存真值", mem, reversed);

        List<PositionSpan> memPos = store.getTermPositionsForTest(posUid, docId, parentId, level);
        List<PositionSpan> revPos = view21.positions(posUid, docId, parentId, level);
        Assert.assertEquals("TR3.3 反向段序 {2,1} positions ≡ 内存真值",
                toKeyList(memPos), toKeyList(revPos));
    }

    // ---------------- TR3.4 fingerprint 随墓碑段变化 ----------------
    @Test(timeout = 60000)
    public void tr34_fingerprintChangesWithTombstone() throws IOException {
        Assert.assertTrue("TR3.4 视图指纹 64-hex", view12.fingerprint().matches("[0-9a-f]{64}"));
        try (WordNetSegmentView only1 = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{1L})) {
            Assert.assertNotEquals("TR3.4 含墓碑段指纹 ≠ 单数据段指纹",
                    only1.fingerprint(), view12.fingerprint());
        }
    }

    // ---------------- TR3.5 旧读者兼容 + 墓碑键之外不受影响 ----------------
    @Test(timeout = 60000)
    public void tr35_legacyReaderCompatAndOtherKeysUnaffected() throws Exception {
        Object st = openStore(rootDir.getAbsolutePath(), 2L);
        try {
            Assert.assertEquals("TR3.5 邻接哨兵 payload = -1", -1, payloadInt(st, adjKey, true));
            Assert.assertEquals("TR3.5 positions 哨兵 payload = -1", -1, payloadInt(st, posKey, false));
        } finally {
            closeStore(st);
        }
        // 旧读者：count<0 零迭代 → 不抛异常，返回空。
        Assert.assertTrue("TR3.5 count<0 零迭代返回空",
                view12.neighborsWithin(srcUid, docId, parentId, level, FulltextStore.DIST_MAX).isEmpty());

        // 墓碑键之外：另一个 srcUid 的邻接仍可见。
        Assert.assertFalse("TR3.5 otherSrcUid 邻接仍可见",
                view12.neighborsWithin(otherSrcUid, docId, parentId, level, FulltextStore.DIST_MAX).isEmpty());
        Assert.assertEquals("TR3.5 otherSrcUid 邻接 ≡ 内存真值",
                toKeySet(store.neighborsWithin(td.toText(otherSrcUid), docId, parentId, level,
                        FulltextStore.DIST_MAX)),
                toKeySet(view12.neighborsWithin(otherSrcUid, docId, parentId, level, FulltextStore.DIST_MAX)));
    }

    // ---------------- helpers ----------------
    private static long ctxKey(long docId, long parentId, int level, long srcUid) {
        return TermDictStore.FarmHash64.mix4(docId, parentId, (long) level, srcUid);
    }

    private static long posKey(long uid, long docId, long parentId, int level) {
        return TermDictStore.FarmHash64.mix4(uid, docId, parentId,
                TermDictStore.FarmHash64.mix2((long) level, 0xDEADBEEFL));
    }

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

    private static Object openStore(String root, long segId) throws Exception {
        Class<?> sc = Class.forName("com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView$Store");
        Method open = sc.getDeclaredMethod("open", String.class, long.class);
        open.setAccessible(true);
        return open.invoke(null, root, segId);
    }

    private static void closeStore(Object st) throws Exception {
        st.getClass().getMethod("close").invoke(st);
    }

    private static int payloadInt(Object st, long key, boolean adj) throws Exception {
        Method m = st.getClass().getDeclaredMethod(adj ? "adjPayload" : "posPayload", long.class);
        m.setAccessible(true);
        byte[] p = (byte[]) m.invoke(st, key);
        Assert.assertNotNull("payload 必须命中 key=" + key, p);
        return ByteBuffer.wrap(p).getInt();
    }
}
