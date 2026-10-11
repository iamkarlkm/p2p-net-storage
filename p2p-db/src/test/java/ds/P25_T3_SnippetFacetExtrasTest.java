package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.FacetHit;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.SnippetHit;

public class P25_T3_SnippetFacetExtrasTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private File root;

    @Before
    public void setup() throws IOException {
        root = tmp.newFolder("p25t3");
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void teardown() throws IOException {
        if (store != null) try { store.close(); } catch (Exception ignore) {}
    }

    private static PositionSpan ps(int s, int e) { return new PositionSpan(s, e); }

    @Test
    public void tr31_snippetWindowHandCalc() {
        String raw = "0123456789".repeat(5); // len=50: idx 0..49
        List<PositionSpan> spans = Arrays.asList(ps(10,12), ps(25,27));
        SearchOptions o = SearchOptions.defaults();
        o.snippetWindow = 10;  // ±5
        o.snippetMaxCount = 3;
        List<SnippetHit> snips = FulltextStore.buildSnippetsForTest(raw, spans, o);
        // 2 span 生成 2 条 snippet：按 startInDoc 升序排序后
        Assert.assertEquals(2, snips.size());
        // snippet 1: [6,16)
        SnippetHit s1 = snips.get(0);
        Assert.assertEquals(6, s1.startInDoc); Assert.assertEquals(16, s1.endInDoc);
        Assert.assertEquals(raw.substring(6, 16), s1.snippetText);
        Assert.assertEquals(1, s1.highlightRangesInSnippet.size());
        Assert.assertEquals(4, s1.highlightRangesInSnippet.get(0).start);
        Assert.assertEquals(6, s1.highlightRangesInSnippet.get(0).end);
        // snippet 2: [21,31)
        SnippetHit s2 = snips.get(1);
        Assert.assertEquals(21, s2.startInDoc); Assert.assertEquals(31, s2.endInDoc);
        Assert.assertEquals(raw.substring(21, 31), s2.snippetText);
        Assert.assertEquals(1, s2.highlightRangesInSnippet.size());
        Assert.assertEquals(4, s2.highlightRangesInSnippet.get(0).start);
        Assert.assertEquals(6, s2.highlightRangesInSnippet.get(0).end);
    }

    @Test
    public void tr32_snippetBoundaryClip() {
        String raw = "0123456789".repeat(5); // 0..49
        // left 左边界：span(0,2) center=1, window=10 half=5 → 1-5=-4→0 / 1+5=6 → [0,6]
        SearchOptions o = SearchOptions.defaults();
        o.snippetWindow = 10; o.snippetMaxCount = 5;
        List<SnippetHit> left = FulltextStore.buildSnippetsForTest(raw, Collections.singletonList(ps(0,2)), o);
        Assert.assertFalse("left non-empty", left.isEmpty());
        SnippetHit sl = left.get(0);
        Assert.assertEquals("left start", 0, sl.startInDoc);
        Assert.assertEquals("left end", 6, sl.endInDoc);
        // 高亮: 原 span(0,2) → snippet start=0 end=6 → rs=0-0=0, re=2-0=2 → [0,2)
        Assert.assertTrue("left highlight exist", sl.highlightRangesInSnippet.stream()
                .anyMatch(p -> p.start == 0 && p.end == 2));
        // right 右边界: span(47,49) center=48 half=5 → 48-5=43 / 48+5=53 -> min(50,53)=50
        List<SnippetHit> right = FulltextStore.buildSnippetsForTest(raw, Collections.singletonList(ps(47,49)), o);
        Assert.assertFalse("right non-empty", right.isEmpty());
        SnippetHit sr = right.get(0);
        Assert.assertEquals("right start", 43, sr.startInDoc);
        Assert.assertEquals("right end", 50, sr.endInDoc);
        // highlight: 原 47..49, snippet start=43 → rs=4, re=6
        Assert.assertTrue("right highlight exist", sr.highlightRangesInSnippet.stream()
                .anyMatch(p -> p.start == (47-43) && p.end == (49-43)));
    }

    @Test
    public void tr33_facetHandAndSort() {
        // 构造 5 个假 Facet：term,uid,lv,dc,tt
        // 国 dc=5,tt=6 / 中 dc=4,tt=5 / 民 dc=3,tt=4 / 人 dc=2,tt=3 / 共 dc=1,tt=1
        List<FacetHit> ins = new ArrayList<>();
        ins.add(new FacetHit(5L, "中", 1, 4L, 5L));
        ins.add(new FacetHit(2L, "共", 1, 1L, 1L));
        ins.add(new FacetHit(1L, "国", 1, 5L, 6L));
        ins.add(new FacetHit(3L, "人", 1, 3L, 4L)); // dc=2,tt=3  vs 民:dc=3,tt=4, people 3L 4
        ins.add(new FacetHit(4L, "民", 1, 3L, 3L)); // dc=3,tt=3 < 人的 4
        List<FacetHit> sorted = FulltextStore.sortFacetsForTest(ins, 10);
        // 顺序：国(5,6) → 中(4,5) → 人(3,4) → 民(3,3) → 共(1,1)
        Assert.assertEquals(5, sorted.size());
        Assert.assertEquals("国", sorted.get(0).termText);
        Assert.assertEquals(5L, sorted.get(0).docCount); Assert.assertEquals(6L, sorted.get(0).totalTf);
        Assert.assertEquals("中", sorted.get(1).termText);
        Assert.assertEquals("人", sorted.get(2).termText);
        Assert.assertEquals(3L, sorted.get(2).docCount); Assert.assertEquals(4L, sorted.get(2).totalTf);
        Assert.assertEquals("民", sorted.get(3).termText);  // 同分 dc=3，人 tt=3<4
        Assert.assertEquals(3L, sorted.get(3).totalTf);
        Assert.assertEquals("共", sorted.get(4).termText);

        // 同分同 totalTf 再同分：按 termText 字典序升序。两 term dc=3,tt=4，"人" < "民" → 人在前（上面例子中 "人" 字典序在 "民" 之前，所以 ↑ 正确）
        // facetTopK=3 → 只取前 3
        List<FacetHit> top3 = FulltextStore.sortFacetsForTest(ins, 3);
        Assert.assertEquals(3, top3.size());
        Assert.assertEquals("国", top3.get(0).termText);
        Assert.assertEquals("中", top3.get(1).termText);
        Assert.assertEquals("人", top3.get(2).termText);
    }

    @Test
    public void tr34_searchWithExtrasParamDefense() throws Exception {
        SearchOptions o = SearchOptions.defaults();

        // K <=0 或 query null/empty -> empty
        List<ScoredDocHitWithExtras> e1 = store.searchWithExtras("CHERRY", null, 0, o);
        Assert.assertTrue("K=0 empty", e1.isEmpty());
        List<ScoredDocHitWithExtras> e2 = store.searchWithExtras("CHERRY", null, -1, o);
        Assert.assertTrue("K=-1 empty", e2.isEmpty());
        List<ScoredDocHitWithExtras> e3 = store.searchWithExtras("", null, 10, o);
        Assert.assertTrue("q empty empty", e3.isEmpty());
        List<ScoredDocHitWithExtras> e4 = store.searchWithExtras(null, null, 10, o);
        Assert.assertTrue("q null empty", e4.isEmpty());

        // 先造数据后 returnPositions=false -> matchedSpans 空
        for (int i = 1; i <= 5; i++) store.putDoc(i, "DOC" + i + " CONTENT cherry banana apple CHERRY term_abc123 long_ascii_word");

        SearchOptions noPos = SearchOptions.defaults();
        noPos.returnPositions = false;
        noPos.vectorRecall = false;
        Map<Long,String> rawMap = new HashMap<>();
        for (int i = 1; i <= 5; i++) rawMap.put((long)i, "DOC" + i + " CONTENT cherry banana apple CHERRY term_abc123");

        List<ScoredDocHitWithExtras> noPosList = store.searchWithExtras("cherry", rawMap, 5, noPos);
        // returnPos: noPosList 若 not 空; matchedSpans 空（因为 "cherry" 命中但 returnPositions=false）
        if (!noPosList.isEmpty()) {
            for (ScoredDocHitWithExtras h : noPosList) {
                Assert.assertTrue("matchedSpans empty when returnPositions=false", h.matchedSpans.isEmpty());
            }
        }

        // rawMap = null -> snippets list should be empty（即使 matchedSpans 非空，也不生成 snippet
        SearchOptions yesPos = SearchOptions.defaults();
        yesPos.vectorRecall = false;
        List<ScoredDocHitWithExtras> noRaw = store.searchWithExtras("cherry", null, 5, yesPos);
        for (ScoredDocHitWithExtras h : noRaw) {
            Assert.assertTrue("snippets empty when rawMap=null", h.snippets.isEmpty());
        }

        // facetTopK=3 -> facets <= 3
        SearchOptions fac3 = SearchOptions.defaults();
        fac3.vectorRecall = false;
        fac3.facetTopK = 3;
        List<ScoredDocHitWithExtras> smallFacet = store.searchWithExtras("cherry", rawMap, 5, fac3);
        if (!smallFacet.isEmpty()) {
            // facets 可能多个 doc 共享 facet list reference（MVP 实现每个 doc 挂全 facet 深拷贝 / 引用同一 instance；测试 size 就行）
            Assert.assertTrue("facetTopK=3 limit " + smallFacet.get(0).topFacets.size(), smallFacet.get(0).topFacets.size() <= 3);
        }
    }
}
