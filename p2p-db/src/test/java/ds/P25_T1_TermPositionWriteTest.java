package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;

public class P25_T1_TermPositionWriteTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private File root;
    private static final int DS1_WORD = 1;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p25t1");
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void teardown() throws IOException {
        if (store != null) try { store.close(); } catch (Exception ignore) {}
    }

    private static long parentOf(long docId, int lv) { return docId * 10L + (long)lv; }

    @Test
    public void tr11_positionSpanEqualsSegCutUnit() throws Exception {
        final int N = 6;
        String[] docs = new String[N];
        docs[0] = "全文索引倒排索引实现高效检索系统";
        docs[1] = "分布式存储引擎支持向量检索能力";
        docs[2] = "倒排索引与向量索引融合查询优化";
        docs[3] = "高性能全文检索算法BM25与RRF融合";
        docs[4] = "分层倒排索引支持海量文档快速查询";
        docs[5] = "索引存储优化减少写放大提升吞吐量";
        for (int i = 0; i < N; i++) store.putDoc(i + 1L, docs[i]);

        FulltextSegmenter seg = resolveSeg(store);
        int[] sampleDocs = {1, 3, 5};
        int[] sampleTerms = {0, 1, 2};
        for (long d : sampleDocs) {
            String text = docs[(int)(d - 1)];
            long parent = parentOf(d, DS1_WORD);
            Iterator<FulltextSegmenter.CutUnit> it = seg.cut(text, DS1_WORD, FulltextSegmenter.SegOptions.defaults());
            List<FulltextSegmenter.CutUnit> cuts = new ArrayList<>();
            it.forEachRemaining(cuts::add);
            for (int ti : sampleTerms) {
                if (ti >= cuts.size()) continue;
                FulltextSegmenter.CutUnit cu = cuts.get(ti);
                long uid = store.getTermDict().toUid(cu.text, false);
                Assert.assertTrue("uid miss: " + cu.text, uid > 0L);
                List<PositionSpan> spans = store.getTermPositionsForTest(uid, d, parent, DS1_WORD);
                Assert.assertFalse("spans non-empty for: " + cu.text, spans.isEmpty());
                boolean found = false;
                for (PositionSpan sp : spans) {
                    if (sp.start == cu.startOff && sp.end == cu.endOff) { found = true; break; }
                }
                Assert.assertTrue("span match cu: term=" + cu.text + " exp=[" + cu.startOff + "," + cu.endOff + ") act=" + spans, found);
                Assert.assertEquals("end-start eq len", cu.text.length(), cu.endOff - cu.startOff);
            }
        }
    }

    @Test
    public void tr12_fallbackSeqWhenNoStartEnd() throws Exception {
        Method m = FulltextStore.class.getDeclaredMethod("writePositionsForLevel",
                long.class, long.class, int.class, List.class);
        m.setAccessible(true);

        long docId = 111L;
        long parent = parentOf(docId, DS1_WORD);
        long u1 = store.getTermDict().toUid("alpha", true);
        long u2 = store.getTermDict().toUid("beta", true);
        store.getTermDict().retain(u1); store.getTermDict().retain(u2);

        // seqList: [uid, seq, so, eo] with so=-1, eo=-1 (or 0=0) -> fallback start=seq end=seq+1
        List<long[]> seqList = new ArrayList<>();
        seqList.add(new long[]{u1, 0, -1, -1}); // seq=0 -> [0,1)
        seqList.add(new long[]{u1, 2,  0,  0}); // seq=2 -> [2,3)
        seqList.add(new long[]{u2, 5, -1,  3}); // so=-1 -> fallback [5,6)
        seqList.add(new long[]{u2, 9,  0,  1}); // ok start=0 end=1

        m.invoke(store, docId, parent, DS1_WORD, seqList);

        List<PositionSpan> sp1 = store.getTermPositionsForTest(u1, docId, parent, DS1_WORD);
        Assert.assertEquals(2, sp1.size());
        assertContains(sp1, 0, 1);
        assertContains(sp1, 2, 3);

        List<PositionSpan> sp2 = store.getTermPositionsForTest(u2, docId, parent, DS1_WORD);
        Assert.assertEquals(2, sp2.size());
        assertContains(sp2, 5, 6);
        assertContains(sp2, 0, 1);
    }

    @Test
    public void tr13_idempotentClearOnlyTargetDoc() throws Exception {
        String share = "SHARED_COMMON_P25T13";  // ASCII: MockJieba 整成 1 term
        long d0 = 7L, d1 = 8L;
        store.putDoc(d0, "doc D0 prefix " + share + " suffix end");
        store.putDoc(d1, "doc D1 other text but also " + share + " final tail");

        long uid = store.getTermDict().toUid(share, false);
        Assert.assertTrue("uid exist for '" + share + "' got=" + uid, uid > 0L);
        long p0 = parentOf(d0, DS1_WORD);
        long p1 = parentOf(d1, DS1_WORD);

        List<PositionSpan> s0pre = store.getTermPositionsForTest(uid, d0, p0, DS1_WORD);
        List<PositionSpan> s1pre = store.getTermPositionsForTest(uid, d1, p1, DS1_WORD);
        Assert.assertFalse("D0 pre non-empty", s0pre.isEmpty());
        Assert.assertFalse("D1 pre non-empty", s1pre.isEmpty());

        store.clearDoc(d0);

        List<PositionSpan> s0post = store.getTermPositionsForTest(uid, d0, p0, DS1_WORD);
        List<PositionSpan> s1post = store.getTermPositionsForTest(uid, d1, p1, DS1_WORD);
        Assert.assertTrue("D0 post empty after clear", s0post.isEmpty());
        Assert.assertFalse("D1 post unchanged after clear D0", s1post.isEmpty());
        Assert.assertEquals("D1 spans same count", s1pre.size(), s1post.size());
        for (int i = 0; i < s1pre.size(); i++) Assert.assertEquals(s1pre.get(i), s1post.get(i));
    }

    @Test
    public void tr14_3xIdempotentAndMissEmpty() throws Exception {
        long d = 42L;
        String t = "ABCDEFxyz_abc123三次幂等写入";
        store.putDoc(d, t);
        store.putDoc(d, t);
        store.putDoc(d, t);

        FulltextSegmenter seg = resolveSeg(store);
        Iterator<FulltextSegmenter.CutUnit> it = seg.cut(t, DS1_WORD, FulltextSegmenter.SegOptions.defaults());
        List<FulltextSegmenter.CutUnit> cuts = new ArrayList<>();
        it.forEachRemaining(cuts::add);
        long parent = parentOf(d, DS1_WORD);
        for (FulltextSegmenter.CutUnit cu : cuts) {
            long uid = store.getTermDict().toUid(cu.text, false);
            if (uid <= 0L) continue;
            List<PositionSpan> sp = store.getTermPositionsForTest(uid, d, parent, DS1_WORD);
            for (PositionSpan s : sp) {
                Assert.assertEquals(cu.text + " end-start eq len", cu.endOff - cu.startOff, s.end - s.start);
            }
        }

        List<PositionSpan> miss = store.getTermPositionsForTest(99999L, 88888L, 77777L, 0);
        Assert.assertNotNull("miss non-null", miss);
        Assert.assertTrue("miss empty list", miss.isEmpty());
    }

    private static void assertContains(List<PositionSpan> lst, int s, int e) {
        for (PositionSpan p : lst) if (p.start == s && p.end == e) return;
        Assert.fail("not found span [" + s + "," + e + ") in " + lst);
    }

    private static FulltextSegmenter resolveSeg(FulltextStore s) throws Exception {
        Method m = FulltextStore.class.getDeclaredMethod("resolveSegmenter");
        m.setAccessible(true);
        return (FulltextSegmenter) m.invoke(s);
    }
}

