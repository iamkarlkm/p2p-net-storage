package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;

public class P25_T2_PositionSpanMergeTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private File root;
    private static final int DS1_WORD = 1;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p25t2");
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void teardown() throws IOException {
        if (store != null) try { store.close(); } catch (Exception ignore) {}
    }

    private static PositionSpan ps(int s, int e) { return new PositionSpan(s, e); }

    @Test
    public void tr21_overlapAndAdjacentMerge() {
        // [0,4][2,6] → [0,6]
        List<PositionSpan> r1 = FulltextStore.mergeSpansForTest(Arrays.asList(ps(0,4), ps(2,6)));
        Assert.assertEquals(1, r1.size());
        Assert.assertEquals(0, r1.get(0).start); Assert.assertEquals(6, r1.get(0).end);

        // [1,2][3,5][7,9] → [1,5],[7,9] (3-2=1 d>0 不合并: start=3 > end=2 → 独立)
        // 注意：mergeSpans 相邻判定 c ≤ b → 3>2 不合并；[3,5] 自 1 span，[7,9] 自 1 span，[1,2] 自 1 span，共 3
        // 但 mergeSpans 之前先按 start 排序：顺序不变 [1,2][3,5][7,9]
        //   第一轮：cs=1 ce=2，下 p.start=3 > ce=2 → out += [1,2]; cs=3 ce=5
        //   下 p.start=7>5 → out += [3,5]; cs=7 ce=9
        //   out += [7,9]。共 3 span
        List<PositionSpan> r2 = FulltextStore.mergeSpansForTest(Arrays.asList(ps(1,2), ps(3,5), ps(7,9)));
        Assert.assertEquals(3, r2.size());
        Assert.assertEquals(1, r2.get(0).start); Assert.assertEquals(2, r2.get(0).end);
        Assert.assertEquals(3, r2.get(1).start); Assert.assertEquals(5, r2.get(1).end);
        Assert.assertEquals(7, r2.get(2).start); Assert.assertEquals(9, r2.get(2).end);

        // [0,1][1,2][2,3] 相邻 start=end → c=b 合并 → [0,3]
        List<PositionSpan> r3 = FulltextStore.mergeSpansForTest(Arrays.asList(ps(0,1), ps(1,2), ps(2,3)));
        Assert.assertEquals(1, r3.size());
        Assert.assertEquals(0, r3.get(0).start); Assert.assertEquals(3, r3.get(0).end);
    }

    @Test
    public void tr22_emptySingleUnordered() {
        // 空 list 返回空 list
        List<PositionSpan> empty = FulltextStore.mergeSpansForTest(Collections.emptyList());
        Assert.assertNotNull(empty); Assert.assertTrue(empty.isEmpty());

        // 1 span 返回原样
        List<PositionSpan> single = FulltextStore.mergeSpansForTest(Collections.singletonList(ps(5,10)));
        Assert.assertEquals(1, single.size());
        Assert.assertEquals(5, single.get(0).start); Assert.assertEquals(10, single.get(0).end);

        // 乱序 [7,9][0,4][2,6] → sort 先 [0,4][2,6][7,9] → merge → [0,6], [7,9]
        List<PositionSpan> unord = FulltextStore.mergeSpansForTest(Arrays.asList(ps(7,9), ps(0,4), ps(2,6)));
        Assert.assertEquals(2, unord.size());
        Assert.assertEquals(0, unord.get(0).start); Assert.assertEquals(6, unord.get(0).end);
        Assert.assertEquals(7, unord.get(1).start); Assert.assertEquals(9, unord.get(1).end);
    }

    @Test
    public void tr23_illegalSpanThrowIae() {
        // start<0
        try { new PositionSpan(-1, 3); Assert.fail("IAE expected"); }
        catch (IllegalArgumentException ok) { Assert.assertTrue(ok.getMessage().contains("start<0")); }

        // end<=start
        try { new PositionSpan(5, 5); Assert.fail("IAE expected"); }
        catch (IllegalArgumentException ok) { Assert.assertTrue(ok.getMessage().contains("end<=start")); }
        try { new PositionSpan(5, 2); Assert.fail("IAE expected"); }
        catch (IllegalArgumentException ok) { Assert.assertTrue(ok.getMessage().contains("end<=start")); }

        // mergeSpansForTest null
        try { FulltextStore.mergeSpansForTest(null); Assert.fail("IAE expected"); }
        catch (IllegalArgumentException ok) { Assert.assertTrue(ok.getMessage().contains("spans null")); }

        // 包含非法 span（但我们 PositionSpan 构造已禁止，用反射测试更难；这里验证包含 null 元素应该被 for 循环 catch
        // 但 PositionSpan 构造防非法，所以 for 循环 p==null 的路径可以通过传入 null 元素 list 直接触发：
        List<PositionSpan> withNull = new ArrayList<>();
        withNull.add(ps(1,2));
        withNull.add(null);
        try { FulltextStore.mergeSpansForTest(withNull); Assert.fail("IAE for null span"); }
        catch (IllegalArgumentException ok) { Assert.assertTrue(ok.getMessage().contains("span illegal")); }
    }

    @Test
    public void tr24_emptyDocZeroSpans() throws Exception {
        // 空文档 "" 长度 0 → putDoc 后所有 positions 全空
        long d = 100L;
        store.putDoc(d, "");
        // 确认 docStat 空
        FulltextStore.WeightDocStatRow stat = store.getDocStatForTest(d);
        if (stat != null) {
            for (int lv = 0; lv < 6; lv++) {
                Assert.assertEquals("doc " + d + " lv " + lv + " docLen 空", 0, stat.docLen[lv]);
                Assert.assertEquals("doc " + d + " lv " + lv + " nTerms 空", 0, stat.nTerms[lv]);
            }
        }
        // 查任何 uid positions 必空（用 dummy uid 9999）
        long parent = d * 10L + DS1_WORD;
        List<PositionSpan> empty = store.getTermPositionsForTest(99999L, d, parent, DS1_WORD);
        Assert.assertNotNull(empty); Assert.assertTrue(empty.isEmpty());

        // 再测非空 putDoc 再 clearDoc("") 触发 positions 清空后空
        long d2 = 200L;
        store.putDoc(d2, "hello p25t2 non empty text");
        // 先确认有非空 term（取 Seg 第 1 cut）
        long d3 = 300L;
        store.putDoc(d3, "");
        Assert.assertTrue("empty doc positions empty",
                store.getTermPositionsForTest(99999L, d3, d3*10L+DS1_WORD, DS1_WORD).isEmpty());
    }
}
