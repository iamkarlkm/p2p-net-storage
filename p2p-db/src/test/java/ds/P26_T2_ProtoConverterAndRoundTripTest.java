package ds;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assert;
import org.junit.Test;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.FacetHit;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.SnippetHit;
import com.q3lives.ds.database.fulltext.cluster.ProtoWireConverters;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ScoredDocHitWithExtrasMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchOptionsMsg;

public class P26_T2_ProtoConverterAndRoundTripTest {

    // TR2.1 rule: ScoredDocHitWithExtras + 3 matchedSpans + 2 snippets + 10 facets → encode → decode → deepEquals
    @Test
    public void tr21_roundTripExtrasFullDeepEquals() throws Exception {
        ScoredDocHit core = new ScoredDocHit(
                101L, 10101L, 1, 3, 5001L,
                0.987654321098, 0.55555, 0.12345, 0.3, 5, 0.777, 2);
        List<PositionSpan> spans = Arrays.asList(
                new PositionSpan(0, 3),
                new PositionSpan(10, 14),
                new PositionSpan(22, 28));
        List<PositionSpan> hr1 = Arrays.asList(new PositionSpan(0, 5), new PositionSpan(11, 15));
        List<PositionSpan> hr2 = Arrays.asList(new PositionSpan(3, 7));
        List<SnippetHit> snippets = Arrays.asList(
                new SnippetHit("hello world sample snippet cherry common fruit", 0, 42, 0.81, hr1),
                new SnippetHit("dragon fruit rare tropical sweet cherry", 100, 136, 0.55, hr2));
        List<FacetHit> facets = new ArrayList<>();
        facets.add(new FacetHit(1L, "cherry", 1, 10L, 100L));
        facets.add(new FacetHit(2L, "fruit", 1, 8L, 80L));
        facets.add(new FacetHit(3L, "common", 1, 7L, 70L));
        facets.add(new FacetHit(4L, "sweet", 1, 7L, 65L)); // same docCount 7 → totalTf tiebreak
        facets.add(new FacetHit(5L, "sample", 1, 6L, 55L));
        facets.add(new FacetHit(6L, "tropical", 1, 5L, 48L));
        facets.add(new FacetHit(7L, "rare", 1, 5L, 45L));
        facets.add(new FacetHit(8L, "world", 1, 4L, 30L));
        facets.add(new FacetHit(9L, "dragon", 1, 3L, 20L));
        facets.add(new FacetHit(10L, "hello", 1, 2L, 10L));
        // sort facets by: docCount↓ → totalTf↓ → termText dict↑
        facets.sort(Comparator.<FacetHit, Long>comparing(f -> -f.docCount)
                .thenComparingLong(f -> -f.totalTf)
                .thenComparing(f -> f.termText));

        ScoredDocHitWithExtras original = new ScoredDocHitWithExtras(core, spans, snippets, facets);

        // round trip
        ScoredDocHitWithExtrasMsg m = ProtoWireConverters.toProto(original);
        byte[] bytes = m.toByteArray();
        ScoredDocHitWithExtrasMsg parsed = ScoredDocHitWithExtrasMsg.parseFrom(bytes);
        ScoredDocHitWithExtras decoded = ProtoWireConverters.fromProto(parsed);

        // core
        Assert.assertNotNull("decoded.core", decoded.core);
        Assert.assertEquals("core.docId", core.docId, decoded.core.docId);
        Assert.assertEquals("core.parentId", core.parentId, decoded.core.parentId);
        Assert.assertEquals("core.level", core.level, decoded.core.level);
        Assert.assertEquals("core.unitId", core.unitId, decoded.core.unitId);
        Assert.assertEquals("core.seq", core.seq, decoded.core.seq);
        Assert.assertEquals("core.shardId", core.shardId, decoded.core.shardId);
        Assert.assertEquals("core.matchedQueryTerms", core.matchedQueryTerms, decoded.core.matchedQueryTerms);
        Assert.assertEquals("core.finalScore@12f",
                String.format("%.12f", core.finalScore),
                String.format("%.12f", decoded.core.finalScore));
        Assert.assertEquals("core.sortScore@12f",
                String.format("%.12f", core.sortScore),
                String.format("%.12f", decoded.core.sortScore));
        Assert.assertEquals("core.rrfScore@12f",
                String.format("%.12f", core.rrfScore),
                String.format("%.12f", decoded.core.rrfScore));
        Assert.assertEquals("core.phraseBoost@12f",
                String.format("%.12f", core.phraseBoostScore),
                String.format("%.12f", decoded.core.phraseBoostScore));
        Assert.assertEquals("core.vectorScore@12f",
                String.format("%.12f", core.vectorScore),
                String.format("%.12f", decoded.core.vectorScore));

        // matchedSpans
        Assert.assertEquals("matchedSpans count", spans.size(), decoded.matchedSpans.size());
        for (int i = 0; i < spans.size(); i++) {
            Assert.assertEquals("span[" + i + "].start", spans.get(i).start, decoded.matchedSpans.get(i).start);
            Assert.assertEquals("span[" + i + "].end", spans.get(i).end, decoded.matchedSpans.get(i).end);
        }

        // snippets
        Assert.assertEquals("snippets count", snippets.size(), decoded.snippets.size());
        for (int i = 0; i < snippets.size(); i++) {
            SnippetHit a = snippets.get(i);
            SnippetHit b = decoded.snippets.get(i);
            Assert.assertEquals("snippet[" + i + "].text", a.snippetText, b.snippetText);
            Assert.assertEquals("snippet[" + i + "].startInDoc", a.startInDoc, b.startInDoc);
            Assert.assertEquals("snippet[" + i + "].endInDoc", a.endInDoc, b.endInDoc);
            Assert.assertEquals("snippet[" + i + "].hr size", a.highlightRangesInSnippet.size(), b.highlightRangesInSnippet.size());
            for (int j = 0; j < a.highlightRangesInSnippet.size(); j++) {
                PositionSpan pa = a.highlightRangesInSnippet.get(j);
                PositionSpan pb = b.highlightRangesInSnippet.get(j);
                Assert.assertEquals("hr start", pa.start, pb.start);
                Assert.assertEquals("hr end", pa.end, pb.end);
            }
        }

        // facets (same sort order 10 elements, triple key preserved)
        Assert.assertEquals("facet count", facets.size(), decoded.topFacets.size());
        for (int i = 0; i < facets.size(); i++) {
            FacetHit a = facets.get(i);
            FacetHit b = decoded.topFacets.get(i);
            Assert.assertEquals("facet[" + i + "].uid", a.unitId, b.unitId);
            Assert.assertEquals("facet[" + i + "].termText", a.termText, b.termText);
            Assert.assertEquals("facet[" + i + "].level", a.level, b.level);
            Assert.assertEquals("facet[" + i + "].docCount", a.docCount, b.docCount);
            Assert.assertEquals("facet[" + i + "].totalTf", a.totalTf, b.totalTf);
        }
    }

    // TR2.2 rule: SearchOptions 6 P25 defaults → encode → decode 6 100% match
    @Test
    public void tr22_searchOptionsSixDefaultsRoundtrip() throws Exception {
        SearchOptions o = SearchOptions.defaults();
        SearchOptionsMsg m = ProtoWireConverters.toProto(o);
        byte[] b = m.toByteArray();
        SearchOptionsMsg pm = SearchOptionsMsg.parseFrom(b);
        SearchOptions decoded = ProtoWireConverters.fromProto(pm);

        Assert.assertTrue("returnPositions=true", decoded.returnPositions);
        Assert.assertEquals("facetLevel=WORD(1)", 1, decoded.facetLevel);
        Assert.assertFalse("charLevelFacet=false", decoded.charLevelFacet);
        Assert.assertEquals("facetTopK=10", 10, decoded.facetTopK);
        Assert.assertEquals("snippetWindow=40", 40, decoded.snippetWindow);
        Assert.assertEquals("snippetMaxCount=3", 3, decoded.snippetMaxCount);
    }

    // TR2.3 rule: FacetMsg 三键排序顺序 preserved 1:1 after round trip (同分 "人"<"民")
    @Test
    public void tr23_facetTripleKeySortOrderPreserved() throws Exception {
        List<FacetHit> original = new ArrayList<>();
        // Two items with equal docCount+totalTf → termText dict order "人" < "民" (UTF-8)
        original.add(new FacetHit(1L, "民", 1, 100L, 500L));
        original.add(new FacetHit(2L, "人", 1, 100L, 500L));
        // Two more with same docCount different totalTf → totalTf descends
        original.add(new FacetHit(3L, "樱桃", 1, 90L, 400L));
        original.add(new FacetHit(4L, "苹果", 1, 90L, 350L));
        original.sort(Comparator.<FacetHit, Long>comparing(f -> -f.docCount)
                .thenComparingLong(f -> -f.totalTf)
                .thenComparing(f -> f.termText));

        byte[] bytes = com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardWithExtrasResponse
                        .newBuilder()
                        .addAllHits(Collections.singletonList(
                                ScoredDocHitWithExtrasMsg.newBuilder()
                                        .addAllTopFacets(ProtoWireConverters.toProtoFacetList(original))
                                        .build()))
                        .build()
                        .toByteArray();

        com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardWithExtrasResponse resp =
                com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchShardWithExtrasResponse.parseFrom(bytes);
        List<FacetHit> decoded = ProtoWireConverters.fromProtoFacetList(resp.getHits(0).getTopFacetsList());

        Assert.assertEquals("facet count", original.size(), decoded.size());
        Assert.assertEquals("order 0 '人' first (UTF-8 人<民)", "人", decoded.get(0).termText);
        Assert.assertEquals("order 1 '民' second", "民", decoded.get(1).termText);
        Assert.assertEquals("order 2 '樱桃' (totalTf 400>350)", "樱桃", decoded.get(2).termText);
        Assert.assertEquals("order 3 '苹果' (totalTf 350)", "苹果", decoded.get(3).termText);
    }

    // TR2.4 rubric (threshold >=4): Converter code quality (static pure + zero side-effect fields + 6 pair converters exist)
    @Test
    public void tr24_rubricConverterQuality() throws Exception {
        Class<?> cls = ProtoWireConverters.class;
        // Rubric Dimension: Static pure functional code quality 0~5
        // Anchors 1=stateful mutable; 3=OK few missing; 5=perfectly pure 12 converters all static final no mutable fields
        int score = 0;
        // 1. Final utility class
        if (java.lang.reflect.Modifier.isFinal(cls.getModifiers())) score += 1;
        // 2. Private constructor (utility)
        boolean hasPrivateCtor = false;
        for (java.lang.reflect.Constructor<?> c : cls.getDeclaredConstructors()) {
            if (java.lang.reflect.Modifier.isPrivate(c.getModifiers()) && c.getParameterCount() == 0) hasPrivateCtor = true;
        }
        if (hasPrivateCtor) score += 1;
        // 3. Zero mutable static fields (non-final or mutable container types)
        int mutableFields = 0;
        for (Field f : cls.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isFinal(f.getModifiers())) mutableFields++;
        }
        if (mutableFields == 0) score += 1;
        // 4. All 6 pair methods exist: toProto/fromProto × ScoredDocHit/ScoredDocHitWithExtras/SearchOptions/PositionSpan/SnippetHit/FacetHit = 12 static methods total
        int staticConv = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            String n = m.getName();
            if (n.equals("toProto") || n.equals("fromProto")) staticConv++;
        }
        if (staticConv >= 10) score += 1;
        if (staticConv >= 12) score += 1;
        // Threshold >= 4
        Assert.assertTrue("Rubric Quality score >=4, actual=" + score + " (anchors: 5=perfectly pure final util 12 methods, 0 mutable static, private ctor)", score >= 4);
    }

    private static int countFieldAssignments(Class<?> c) {
        Field[] fs = c.getDeclaredFields();
        int n = 0;
        for (Field f : fs) {
            if (!java.lang.reflect.Modifier.isFinal(f.getModifiers()) || f.getType() != java.lang.String.class) n++;
        }
        return n;
    }

    private static class ModifierExtractor {
        static boolean isFinalClass(Class<?> c) { return java.lang.reflect.Modifier.isFinal(c.getModifiers()); }
    }
}
