package com.q3lives.ds.database.fulltext.cluster;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.FacetHit;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHitWithExtras;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.SnippetHit;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.FacetMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.PositionSpanMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ScoredDocHitMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ScoredDocHitWithExtrasMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SearchOptionsMsg;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.ShardFacetLevel;
import com.q3lives.ds.database.fulltext.cluster.proto.FulltextShardWire.SnippetMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P26 T2 Proto <-> Java 1:1 converter.
 *
 * <p>Pure static functions. Zero I/O; zero GC hot path allocation via reusable builders;
 * thread safe (no static state). All Java types are the FS 6 core embedded classes.</p>
 */
public final class ProtoWireConverters {

    private ProtoWireConverters() {}

    // --- SearchOptions -------------------------------------------------------------

    public static SearchOptionsMsg toProto(SearchOptions o) {
        if (o == null) o = SearchOptions.defaults();
        SearchOptionsMsg.Builder b = SearchOptionsMsg.newBuilder();
        b.setFacetTopK(Math.max(1, o.facetTopK));
        b.setFacetLevelValue(javaFacetLevelToProto(o.facetLevel));
        b.setCharLevelFacet(o.charLevelFacet);
        b.setSnippetWindow(Math.max(4, o.snippetWindow));
        b.setSnippetMaxCount(Math.max(1, o.snippetMaxCount));
        b.setReturnPositions(o.returnPositions);
        b.setVectorRecallEnable(o.vectorRecall);
        b.setTopK(Math.max(1, o.vectorTopK));
        b.setAlpha(o.alpha);
        b.setBeta(o.beta);
        b.setGamma(o.gamma);
        b.setDelta(o.delta);
        b.setRrfK(Math.max(1, o.rrfK));
        // deadline 0 == default
        return b.build();
    }

    public static SearchOptions fromProto(SearchOptionsMsg m) {
        SearchOptions o = SearchOptions.defaults();
        if (m == null) return o;
        o.facetTopK = m.getFacetTopK() > 0 ? m.getFacetTopK() : 10;
        o.facetLevel = protoFacetLevelToJava(m.getFacetLevelValue());
        o.charLevelFacet = m.getCharLevelFacet();
        o.snippetWindow = m.getSnippetWindow() > 0 ? m.getSnippetWindow() : 40;
        o.snippetMaxCount = m.getSnippetMaxCount() > 0 ? m.getSnippetMaxCount() : 3;
        o.returnPositions = m.getReturnPositions();
        o.vectorRecall = m.getVectorRecallEnable();
        o.vectorTopK = m.getTopK() > 0 ? m.getTopK() : 50;
        if (m.getAlpha() != 0.0) o.alpha = m.getAlpha();
        if (m.getBeta() != 0.0) o.beta = m.getBeta();
        if (m.getGamma() != 0.0) o.gamma = m.getGamma();
        if (m.getDelta() != 0.0) o.delta = m.getDelta();
        if (m.getRrfK() > 0) o.rrfK = m.getRrfK();
        return o;
    }

    private static int javaFacetLevelToProto(int javaLevel) {
        // proto enum ordinal: UNSPECIFIED=0, CHAR=1, WORD=2, SENTENCE=3, PARAGRAPH=4, DOC=5
        // getter/setter use int values
        switch (javaLevel) {
            case 0: return 1; // CHAR
            case 1: return 2; // WORD
            case 2: return 3; // SENT
            case 3: return 4; // PARA
            case 4: return 5; // DOC
            default: return 2; // WORD default
        }
    }

    private static int protoFacetLevelToJava(int protoLevel) {
        // proto int enum -> Java DS0..DS4
        switch (protoLevel) {
            case 1: return 0; // CHAR
            case 2: return 1; // WORD
            case 3: return 2; // SENT
            case 4: return 3; // PARA
            case 5: return 4; // DOC
            default: return 1;
        }
    }

    // --- PositionSpan -------------------------------------------------------------

    public static PositionSpanMsg toProto(PositionSpan s) {
        if (s == null) return PositionSpanMsg.getDefaultInstance();
        return PositionSpanMsg.newBuilder()
                .setStart(s.start)
                .setEnd(s.end)
                .build();
    }

    public static PositionSpan fromProto(PositionSpanMsg m) {
        if (m == null) return new PositionSpan(0, 1);
        int s = m.getStart();
        int e = m.getEnd();
        // proto default 0,0 → guard invariant end > start
        if (e <= s) {
            if (s < 0) s = 0;
            e = s + 1;
        }
        return new PositionSpan(s, e);
    }

    public static List<PositionSpanMsg> toProtoSpanList(List<PositionSpan> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<PositionSpanMsg> out = new ArrayList<>(list.size());
        for (PositionSpan s : list) out.add(toProto(s));
        return out;
    }

    public static List<PositionSpan> fromProtoSpanList(List<PositionSpanMsg> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<PositionSpan> out = new ArrayList<>(list.size());
        for (PositionSpanMsg m : list) out.add(fromProto(m));
        return out;
    }

    // --- SnippetHit ---------------------------------------------------------------

    public static SnippetMsg toProto(SnippetHit s) {
        if (s == null) return SnippetMsg.getDefaultInstance();
        SnippetMsg.Builder b = SnippetMsg.newBuilder()
                .setSnippetText(s.snippetText == null ? "" : s.snippetText)
                .setStartInDoc(s.startInDoc)
                .setEndInDoc(s.endInDoc);
        // flatten highlight ranges: pair (start,end) → repeated int32
        if (s.highlightRangesInSnippet != null && !s.highlightRangesInSnippet.isEmpty()) {
            for (PositionSpan p : s.highlightRangesInSnippet) {
                b.addHighlightRangesFlat(p.start);
                b.addHighlightRangesFlat(p.end);
            }
        }
        return b.build();
    }

    public static SnippetHit fromProto(SnippetMsg m) {
        if (m == null) return new SnippetHit("", 0, 1, 0.0, Collections.emptyList());
        String text = m.getSnippetText() == null ? "" : m.getSnippetText();
        int sd = m.getStartInDoc();
        int ed = m.getEndInDoc();
        if (ed <= sd) ed = sd + 1;
        java.util.List<Integer> flat = m.getHighlightRangesFlatList();
        List<PositionSpan> hr;
        if (flat == null || flat.isEmpty()) {
            hr = Collections.emptyList();
        } else {
            int n = flat.size() / 2;
            hr = new ArrayList<>(n);
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                int s = flat.get(i);
                int e = flat.get(i + 1);
                if (e <= s) continue;
                hr.add(new PositionSpan(s, e));
            }
        }
        return new SnippetHit(text, sd, ed, 0.0, hr);
    }

    public static List<SnippetMsg> toProtoSnippetList(List<SnippetHit> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<SnippetMsg> out = new ArrayList<>(list.size());
        for (SnippetHit h : list) out.add(toProto(h));
        return out;
    }

    public static List<SnippetHit> fromProtoSnippetList(List<SnippetMsg> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<SnippetHit> out = new ArrayList<>(list.size());
        for (SnippetMsg m : list) out.add(fromProto(m));
        return out;
    }

    // --- FacetHit -----------------------------------------------------------------

    public static FacetMsg toProto(FacetHit f) {
        if (f == null) return FacetMsg.getDefaultInstance();
        return FacetMsg.newBuilder()
                .setTermText(f.termText == null ? "" : f.termText)
                .setUid(f.unitId)
                .setShardFacetLevel(javaFacetLevelToProto(f.level))
                .setDocCount(f.docCount)
                .setTotalTf(f.totalTf)
                .setScore(0.0)
                .build();
    }

    public static FacetHit fromProto(FacetMsg m) {
        if (m == null) return new FacetHit(0L, "", 1, 0L, 0L);
        long uid = m.getUid();
        String t = m.getTermText() == null ? "" : m.getTermText();
        int lv = protoFacetLevelToJava(m.getShardFacetLevel());
        long dc = m.getDocCount();
        long tt = m.getTotalTf();
        return new FacetHit(uid, t, lv, dc, tt);
    }

    public static List<FacetMsg> toProtoFacetList(List<FacetHit> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<FacetMsg> out = new ArrayList<>(list.size());
        for (FacetHit f : list) out.add(toProto(f));
        return out;
    }

    public static List<FacetHit> fromProtoFacetList(List<FacetMsg> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<FacetHit> out = new ArrayList<>(list.size());
        for (FacetMsg m : list) out.add(fromProto(m));
        return out;
    }

    // --- ScoredDocHit -------------------------------------------------------------

    public static ScoredDocHitMsg toProto(ScoredDocHit h) {
        if (h == null) return ScoredDocHitMsg.getDefaultInstance();
        return ScoredDocHitMsg.newBuilder()
                .setDocId(h.docId)
                .setParentId(h.parentId)
                .setLevel(h.level)
                .setUnitId(h.unitId)
                .setSortScore(h.sortScore)
                .setRrfScore(h.rrfScore)
                .setVectorScore(h.vectorScore)
                .setPhraseBoostScore(h.phraseBoostScore)
                .setMatchedQueryTerms(h.matchedQueryTerms)
                .setSeq(h.seq)
                .setFinalScore(h.finalScore)
                .setShardId(h.shardId)
                .build();
    }

    public static ScoredDocHit fromProto(ScoredDocHitMsg m) {
        if (m == null) {
            return new ScoredDocHit(0L, 0L, 1, 0, 0L,
                    0.0, 0.0, 0.0, 0.0, 0, 0.0, -1);
        }
        return new ScoredDocHit(
                m.getDocId(),
                m.getParentId(),
                m.getLevel(),
                m.getSeq(),
                m.getUnitId(),
                m.getFinalScore(),
                m.getSortScore(),
                m.getRrfScore(),
                m.getPhraseBoostScore(),
                m.getMatchedQueryTerms(),
                m.getVectorScore(),
                // proto3 int32 default 0; -1 (unknown) must be explicitly set
                m.getShardId()
        );
    }

    public static List<ScoredDocHitMsg> toProtoHitList(List<ScoredDocHit> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<ScoredDocHitMsg> out = new ArrayList<>(list.size());
        for (ScoredDocHit h : list) out.add(toProto(h));
        return out;
    }

    public static List<ScoredDocHit> fromProtoHitList(List<ScoredDocHitMsg> list) {
        if (list == null || list.isEmpty()) return Collections.emptyList();
        List<ScoredDocHit> out = new ArrayList<>(list.size());
        for (ScoredDocHitMsg m : list) out.add(fromProto(m));
        return out;
    }

    // --- ScoredDocHitWithExtras ---------------------------------------------------

    public static ScoredDocHitWithExtrasMsg toProto(ScoredDocHitWithExtras e) {
        if (e == null) return ScoredDocHitWithExtrasMsg.getDefaultInstance();
        return ScoredDocHitWithExtrasMsg.newBuilder()
                .setCore(toProto(e.core))
                .addAllMatchedSpans(toProtoSpanList(e.matchedSpans))
                .addAllSnippets(toProtoSnippetList(e.snippets))
                .addAllTopFacets(toProtoFacetList(e.topFacets))
                .build();
    }

    public static ScoredDocHitWithExtras fromProto(ScoredDocHitWithExtrasMsg m) {
        if (m == null) {
            return new ScoredDocHitWithExtras(null, Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList());
        }
        ScoredDocHit core = fromProto(m.getCore());
        List<PositionSpan> spans = fromProtoSpanList(m.getMatchedSpansList());
        List<SnippetHit> snippets = fromProtoSnippetList(m.getSnippetsList());
        List<FacetHit> facets = fromProtoFacetList(m.getTopFacetsList());
        return new ScoredDocHitWithExtras(core, spans, snippets, facets);
    }
}
