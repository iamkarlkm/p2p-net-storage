package com.q3lives.ds.database.fulltext;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.q3lives.ds.bucket.DsFixedBucketStore;
import com.q3lives.ds.collections.DsHashMap;
import com.q3lives.ds.core.DsFreeRing;
import com.q3lives.ds.database.fulltext.TermDictStore.FarmHash64;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.index.DsEqIndexStore;

public class FulltextStore implements AutoCloseable {

    public static final int DS0_CHAR = 0;
    public static final int DS1_WORD = 1;
    public static final int DS2_PHRASE = 2;
    public static final int DS3_SENTENCE = 3;
    public static final int DS4_BLOCK = 4;
    public static final int DS5_DOC_STRUCT = 5;

    private static final String SPACE_FT = "p21_fulltext";
    private static final String TU_ROW_TYPE = "tu_rows";
    private static final long NOT_FOUND = 0L;

    private final File root;
    public final TermDictStore termDict;

    private final DsHashMap tuPkMap; // key=mix4(unitId,docId,parentId,level) value=bucketId of tuStore
    private final DsFixedBucketStore tuStore;
    private final DsEqIndexStore idx1; // (docId,parentId,level) -> rowPkHash[]
    private final DsEqIndexStore idx2; // unitId -> rowPkHash[]
    private final DsEqIndexStore idx3; // (docId,level) -> rowPkHash[]

    private final FulltextSegmenter seg5builtin;
    private volatile FulltextSegmenter cachedBestSeg;
    private final Object segLock = new Object();

    // ---------------- T3 DIST_BUCKET + PAIR_MAP + UID_RING ----------------
    /** d 饱和桶（超过 1023 的距离归一到 1024），d∈[1..1024] 索引。 */
    public static final int DIST_MAX = 1024;
    private static final String DIST_BUCKET_DIR = "dist_buckets";
    private static final String DIST_PAIR_FILE = "dist_pair.dshash";
    private static final String UID_RING_DIR = "uid_ring";
    private static final String DIST_NEIGHBOR_TYPE = "dist_neighbors";
    private static final String DIR_L = "L";
    private static final String DIR_R = "R";
    private static final int PRECREATE_MAX_D = 32; // d=1..32 预创建（64 文件），其余懒创建

    private final DsHashMap[] distL; // index = d [1..1024]，0 空；L 桶：key=leftUid, value=bucketId(long[] rightUids)
    private final DsHashMap[] distR; // index = d [1..1024]，0 空；R 桶：key=rightUid, value=bucketId(long[] leftUids)
    private final Object[] distLocks; // 每个 d 1 个懒创建锁（避免并发 new DsHashMap）
    private final DsHashMap distPairMap; // PAIR_MAP：key=mix4(left,right,parent,level) value=d
    private final DsFixedBucketStore distNeighborStore; // 存邻居 long[] list
    private final ConcurrentHashMap<Integer, DsFreeRing> uidRingShards; // shard = (uid >>> 48) ∈ [0..65535]
    private final File uidRingDir;

    /** T3 测试探针：topKNeighbors 访问某 d 桶次数（[0] 不用, 1..1024）。 */
    public final int[] bucketAccessCounter = new int[DIST_MAX + 1];

    // ---------------- P22 T1 WeightStore（ZeroLeak：内嵌类，fulltext 包独立类依旧 3 个不增加）----------------
    /** 文档统计行（doc 维度）：6 level 各 docLen（uidSeq 总长度）+ nTerms（distinct term 数）。 */
    public static final class WeightDocStatRow {
        public final int[] docLen = new int[6];
        public final int[] nTerms = new int[6];
        public WeightDocStatRow() {}
    }
    /** term 全局统计行（uid 维度）：idf + 自定义 payload（应用层用）。 */
    public static final class WeightTermStatRow {
        public double idf;
        public byte[] payload;
        public WeightTermStatRow(double idf, byte[] payload) { this.idf = idf; this.payload = payload == null ? new byte[0] : payload; }
    }
    /** P22 4 排序器枚举（T2/T3 填实现）。 */
    public enum Ranker { BOOL, TF, TFIDF, BM25 }
    /** P23 T1 Similarity enum（COSINE/L2，顶层定义便于 SearchOptions 直接引用）。 */
    public enum Similarity { COSINE, L2 }
    /** P24 T1 Shard 路由模式（ZeroLeak：内嵌 FulltextStore）。AUTO=写 DOC/读 UNIT。 */
    public enum ShardMode { AUTO, UNIT, DOC }
    /** HC probe：按 unit_id 高 16 位 mod shardCount（与 P21 UID_RING 对齐）。shardCount<=0 抛 IAE。 */
    public static int resolveShardForUnitIdForTest(long uid, int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount invalid: " + shardCount);
        int high16 = (int) ((uid >>> 48) & 0xFFFFL);
        int r = high16 % shardCount;
        if (r < 0) r += shardCount;
        return r;
    }
    /** HC probe：docId FarmHash.mix4(docId,0xC0FFEE,0,0) 后取高位正值 mod shardCount。 */
    public static int resolveShardForDocIdForTest(long docId, int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount invalid: " + shardCount);
        long V = TermDictStore.FarmHash64.mix4(docId, 0xC0FFEEL, 0L, 0L);
        long shifted = (V >>> 16) & 0x7FFFFFFFL; // 非负
        int r = (int) (shifted % (long) shardCount);
        if (r < 0) r += shardCount;
        return r;
    }
    /** 搜索选项（内嵌类 ZeroLeak 合规）。默认 BM25 + 短语 boost + RRF 60 + alpha=0.6 beta=0.3 gamma=0.1。
     *  P23 扩 5 字段：vectorSim / vectorRecall / vectorEfSearch / vectorTopK / delta。向后兼容。
     *  P24 扩 3 字段：shardMode / localTopKFactor / concurrentScatter。 */
    public static final class SearchOptions {
        public Ranker ranker = Ranker.BM25;
        public double k1 = 1.2;
        public double b = 0.75;
        public int rrfK = 60;
        public int neighborRecallD = 2;
        public boolean phraseBoost = true;
        public double phraseBoostWeight = 0.1;
        public double alpha = 0.6;
        public double beta = 0.3;
        public double gamma = 0.1;
        public boolean charLevelSearch = false;
        public Map<Long,Double> idfOverrideByUnitId = null;
        // P23 T1 SearchOptions 扩 5 字段（默认值：COSINE / true / 128 / 50 / 0.15）
        public Similarity vectorSim = Similarity.COSINE;
        public boolean vectorRecall = true;
        public int vectorEfSearch = 128;
        public int vectorTopK = 50;
        public double delta = 0.15;
        // P24 T1 SearchOptions 扩 3 字段（默认 AUTO / factor 3 / MVP 顺序 scatter 不并发）
        public ShardMode shardMode = ShardMode.AUTO;
        public int localTopKFactor = 3;
        public boolean concurrentScatter = false;
        // P25 T1 SearchOptions 扩 6 字段（向后兼容默认值，P22/23/24 调用无副作用）
        public boolean returnPositions = true;
        public int facetLevel = 1;
        public boolean charLevelFacet = false;
        public int facetTopK = 10;
        public int snippetWindow = 40;
        public int snippetMaxCount = 3;
        public static SearchOptions defaults() { return new SearchOptions(); }
    }
    /** 搜索返回（组合 P21 DocHit 字段，零破坏不继承 final DocHit）。P23 扩 11th vectorScore；P24 扩 12th shardId。 */
    public static final class ScoredDocHit {
        public final long docId;
        public final long parentId;
        public final int level;
        public final int seq;
        public final long unitId;
        public final double finalScore;
        public final double sortScore;
        public final double rrfScore;
        public final double phraseBoostScore;
        public final int matchedQueryTerms;
        public final double vectorScore; // P23 T1 第 11 字段，P22 构造默认=0
        public final int shardId;         // P24 T1 第 12 字段，默认 -1（单 shard 未知）
        /** P24 12 参构造（shardId 由 ShardRouter 填充）。 */
        public ScoredDocHit(long docId, long parentId, int level, int seq, long unitId,
                            double finalScore, double sortScore, double rrfScore, double phraseBoostScore,
                            int matchedQueryTerms, double vectorScore, int shardId) {
            this.docId = docId; this.parentId = parentId; this.level = level; this.seq = seq; this.unitId = unitId;
            this.finalScore = finalScore; this.sortScore = sortScore; this.rrfScore = rrfScore;
            this.phraseBoostScore = phraseBoostScore; this.matchedQueryTerms = matchedQueryTerms;
            this.vectorScore = vectorScore; this.shardId = shardId;
        }
        /** P23 11 参构造。shardId=-1（本地单 shard）。 */
        public ScoredDocHit(long docId, long parentId, int level, int seq, long unitId,
                            double finalScore, double sortScore, double rrfScore, double phraseBoostScore,
                            int matchedQueryTerms, double vectorScore) {
            this(docId, parentId, level, seq, unitId, finalScore, sortScore, rrfScore, phraseBoostScore,
                 matchedQueryTerms, vectorScore, -1);
        }
        /** P22 兼容 10 参构造（vectorScore=0、shardId=-1，P22 search 继续复用）。 */
        public ScoredDocHit(long docId, long parentId, int level, int seq, long unitId,
                            double finalScore, double sortScore, double rrfScore, double phraseBoostScore,
                            int matchedQueryTerms) {
            this(docId, parentId, level, seq, unitId, finalScore, sortScore, rrfScore, phraseBoostScore,
                 matchedQueryTerms, 0.0, -1);
        }
    }
    /** P25 T1 Term Position 命中区间（0-based 半开 [start,end)，ZeroLeak3 内嵌不增独立类）。 */
    public static final class PositionSpan {
        public final int start;
        public final int end;
        public PositionSpan(int s, int e) {
            if (s < 0) throw new IllegalArgumentException("start<0: " + s);
            if (e <= s) throw new IllegalArgumentException("end<=start: s=" + s + " e=" + e);
            this.start = s; this.end = e;
        }
        public boolean equals(Object o) {
            if (!(o instanceof PositionSpan)) return false;
            PositionSpan x = (PositionSpan)o;
            return x.start == this.start && x.end == this.end;
        }
        public int hashCode() { return (start * 31) ^ end; }
        public String toString() { return "PS[" + start + "," + end + ")"; }
    }
    /** P25 T3 Snippet 摘要命中（ZeroLeak 内嵌）。highlightRangesInSnippet 相对 snippetText 偏移。 */
    public static final class SnippetHit {
        public final String snippetText;
        public final int startInDoc;
        public final int endInDoc;
        public final double score;
        public final List<PositionSpan> highlightRangesInSnippet;
        public SnippetHit(String text, int sDoc, int eDoc, double sc, List<PositionSpan> hr) {
            this.snippetText = text == null ? "" : text;
            this.startInDoc = sDoc; this.endInDoc = eDoc;
            this.score = sc;
            this.highlightRangesInSnippet = hr == null ? Collections.emptyList() : hr;
        }
    }
    /** P25 T3 Facet 聚合命中（ZeroLeak 内嵌）。docCount/totalTf 全局 IDX2+WeightStore 估算。 */
    public static final class FacetHit {
        public final long unitId;
        public final String termText;
        public final int level;
        public final long docCount;
        public final long totalTf;
        public FacetHit(long uid, String t, int lv, long dc, long tt) {
            this.unitId = uid; this.termText = t == null ? "" : t;
            this.level = lv; this.docCount = dc; this.totalTf = tt;
        }
    }
    /** P25 T3 searchWithExtras 返回（NOT extends final ScoredDocHit，组合模式）。 */
    public static final class ScoredDocHitWithExtras {
        public final ScoredDocHit core;
        public final List<PositionSpan> matchedSpans;
        public final List<SnippetHit> snippets;
        public final List<FacetHit> topFacets;
        public ScoredDocHitWithExtras(ScoredDocHit c, List<PositionSpan> ms, List<SnippetHit> ss, List<FacetHit> fs) {
            this.core = c;
            this.matchedSpans = ms == null ? Collections.emptyList() : ms;
            this.snippets = ss == null ? Collections.emptyList() : ss;
            this.topFacets = fs == null ? Collections.emptyList() : fs;
        }
    }
    /** WeightStore：3 DsHashMap + 1 DsFixedBucketStore（独立 weight_store 目录，不碰 P21 任何文件）。
     *  P23 内嵌 HnswStore 独立 hnsw/ 目录，weight_store/ 下不互相污染。
     *  ZeroLeak3：WeightStore 是 FulltextStore 内嵌，不新增 top-level .java。 */
    public static final class WeightStore implements AutoCloseable {
        private static final String WEIGHT_SPACE = "weight_store_p22";
        private static final String PAYLOAD_TYPE = "payload";
        final DsHashMap docStatMap;       // key=docId Long, value=bucketId of 48B(docLen[6]+nTerms[6])
        final DsHashMap termFreqInDocMap; // key=mix4(uid,docId,parentId,level) Long, value=bucketId of 4B tf int
        final DsHashMap termStatMap;      // key=uid Long, value=bucketId of (idf 8B + payloadLen 4B + payload)
        final DsHashMap termPositionsMap; // P25 T1: key=mix5(uid,docId,parentId,level,0xDEADBEEF), value=bucketId of 4B count + 8B*count span
        final DsFixedBucketStore valueStore; // 存所有 3 张 hash 的 value byte[]（power 档自适应）
        final HnswStore hnswStore; // P23 WeightStore 内嵌，weight_store/hnsw/ 目录独立

        WeightStore(String rootDir) throws IOException {
            if (rootDir == null || rootDir.isEmpty()) throw new IllegalArgumentException("rootDir empty");
            File rootF = new File(rootDir, "weight_store");
            if (!rootF.exists()) rootF.mkdirs();
            File maps = new File(rootF, "maps"); if (!maps.exists()) maps.mkdirs();
            this.docStatMap       = new DsHashMap(new File(maps, "doc_stat.dshash"));
            this.termFreqInDocMap = new DsHashMap(new File(maps, "term_freq_in_doc.dshash"));
            this.termStatMap      = new DsHashMap(new File(maps, "term_stat.dshash"));
            this.termPositionsMap = new DsHashMap(new File(maps, "term_positions.dshash")); // P25 T1
            this.valueStore = new DsFixedBucketStore(new File(rootF, "weight_bucket_store").getAbsolutePath());
            this.hnswStore = new HnswStore(rootDir);
        }

        // ---------- helpers ----------
        private byte[] readBucketVal(long bucketId) throws IOException {
            if (bucketId == 0L) return null;
            byte[] h4 = valueStore.get(WEIGHT_SPACE, PAYLOAD_TYPE, bucketId, 0, 4);
            if (h4 == null || h4.length < 4) return null;
            int totalLen = ByteBuffer.wrap(h4).getInt();
            if (totalLen < 4 || totalLen > 16 * 1024 * 1024) return null;
            return valueStore.get(WEIGHT_SPACE, PAYLOAD_TYPE, bucketId, 0, totalLen);
        }
        private long writeBucketVal(byte[] valWithHeader4B) throws IOException {
            return valueStore.put(WEIGHT_SPACE, PAYLOAD_TYPE, valWithHeader4B);
        }
        private void removeBucket(long bucketId) throws IOException {
            if (bucketId == 0L) return;
            try { valueStore.remove(WEIGHT_SPACE, PAYLOAD_TYPE, bucketId); } catch (Exception ignore) {}
        }

        // ---------- docStat ----------
        WeightDocStatRow getDocStat(long docId) throws IOException {
            Long bucket = docStatMap.get(docId);
            if (bucket == null || bucket == 0L) return null;
            byte[] data = readBucketVal(bucket);
            if (data == null) return null;
            ByteBuffer bb = ByteBuffer.wrap(data); bb.getInt();
            WeightDocStatRow r = new WeightDocStatRow();
            for (int i = 0; i < 6; i++) r.docLen[i] = bb.getInt();
            for (int i = 0; i < 6; i++) r.nTerms[i] = bb.getInt();
            return r;
        }
        void putDocStat(long docId, WeightDocStatRow row) throws IOException {
            // remove old if exists
            Long old = docStatMap.get(docId); if (old != null && old != 0L) removeBucket(old);
            ByteBuffer bb = ByteBuffer.allocate(4 + 6*4 + 6*4); // totalLen header 4 + 2*24
            bb.putInt(bb.capacity());
            for (int i = 0; i < 6; i++) bb.putInt(row.docLen[i]);
            for (int i = 0; i < 6; i++) bb.putInt(row.nTerms[i]);
            long newBucket = writeBucketVal(bb.array());
            docStatMap.put(docId, newBucket);
        }
        void removeDocStat(long docId) throws IOException {
            Long old = docStatMap.remove(docId); if (old != null && old != 0L) removeBucket(old);
        }

        // ---------- termFreqInDoc ----------
        int getTf(long uid, long docId, long parentId, int level) throws IOException {
            long key = FarmHash64.mix4(uid, docId, parentId, (long)level);
            Long bucket = termFreqInDocMap.get(key);
            if (bucket == null || bucket == 0L) return 0;
            byte[] data = readBucketVal(bucket);
            if (data == null || data.length < 8) return 0;
            return ByteBuffer.wrap(data).getInt(4);
        }
        void putTf(long uid, long docId, long parentId, int level, int tf) throws IOException {
            long key = FarmHash64.mix4(uid, docId, parentId, (long)level);
            Long old = termFreqInDocMap.get(key); if (old != null && old != 0L) removeBucket(old);
            ByteBuffer bb = ByteBuffer.allocate(4 + 4); bb.putInt(8); bb.putInt(tf);
            long newBucket = writeBucketVal(bb.array());
            termFreqInDocMap.put(key, newBucket);
        }
        void removeTf(long uid, long docId, long parentId, int level) throws IOException {
            long key = FarmHash64.mix4(uid, docId, parentId, (long)level);
            Long old = termFreqInDocMap.remove(key); if (old != null && old != 0L) removeBucket(old);
        }

        // ---------- termStat ----------
        WeightTermStatRow getTermStat(long uid) throws IOException {
            Long bucket = termStatMap.get(uid);
            if (bucket == null || bucket == 0L) return null;
            byte[] data = readBucketVal(bucket);
            if (data == null || data.length < 16) return null;
            ByteBuffer bb = ByteBuffer.wrap(data); bb.getInt();
            double idf = bb.getDouble();
            int plen = bb.getInt();
            byte[] payload = new byte[plen];
            if (plen > 0 && bb.remaining() >= plen) bb.get(payload);
            return new WeightTermStatRow(idf, payload);
        }
        void putTermStat(long uid, WeightTermStatRow row) throws IOException {
            if (row == null) return;
            Long old = termStatMap.get(uid); if (old != null && old != 0L) removeBucket(old);
            byte[] payload = row.payload == null ? new byte[0] : row.payload;
            ByteBuffer bb = ByteBuffer.allocate(4 + 8 + 4 + payload.length);
            bb.putInt(bb.capacity()); bb.putDouble(row.idf); bb.putInt(payload.length); bb.put(payload);
            long newBucket = writeBucketVal(bb.array());
            termStatMap.put(uid, newBucket);
        }

        // ================= P25 T1 Term Positions（ZeroLeak3：内嵌 WeightStore，独立 DsHashMap key=mix5 0xDEADBEEF） =================
        /** P25 T1: Positions key mix5 = mix4(uid,docId,parentId,level) XOR'd via mix5 for unique isolation. */
        private static long posKey(long uid, long docId, long parentId, int level) {
            return FarmHash64.mix4(uid, docId, parentId, FarmHash64.mix2((long)level, 0xDEADBEEFL));
        }
        /** 写入/覆盖 positions（幂等）。miss 删除会自动 remove 旧 bucket。空 spans = remove。 */
        void putPositions(long uid, long docId, long parentId, int level, java.util.List<PositionSpan> spans) throws IOException {
            long key = posKey(uid, docId, parentId, level);
            Long old = termPositionsMap.get(key); if (old != null && old != 0L) removeBucket(old);
            if (spans == null || spans.isEmpty()) { termPositionsMap.remove(key); return; }
            ByteBuffer bb = ByteBuffer.allocate(4 + 4 + spans.size() * 8); // [totalLen4][count4][s4e4]*N
            bb.putInt(bb.capacity()); bb.putInt(spans.size());
            for (PositionSpan sp : spans) { bb.putInt(sp.start); bb.putInt(sp.end); }
            long bucket = writeBucketVal(bb.array());
            termPositionsMap.put(key, bucket);
        }
        /** 读取 positions，miss 返回空 List（zero null）。损坏 value length（不是 4+4+8N）抛 IAE。 */
        java.util.List<PositionSpan> getPositions(long uid, long docId, long parentId, int level) throws IOException {
            long key = posKey(uid, docId, parentId, level);
            Long bucket = termPositionsMap.get(key);
            if (bucket == null || bucket == 0L) return new java.util.ArrayList<>();
            byte[] data = readBucketVal(bucket);
            if (data == null || data.length < 8) throw new IllegalArgumentException("position payload broken: len<8 uid=" + uid);
            ByteBuffer bb = ByteBuffer.wrap(data); int total = bb.getInt(); int count = bb.getInt();
            if (total != 4 + 4 + count * 8) throw new IllegalArgumentException("position payload length mismatch: total=" + total + " count=" + count + " expected=" + (4+4+count*8));
            java.util.List<PositionSpan> out = new java.util.ArrayList<>(count);
            for (int i = 0; i < count; i++) out.add(new PositionSpan(bb.getInt(), bb.getInt()));
            return out;
        }
        /** 删除指定 (uid,doc,p,lv) positions（幂等：不存在 no-op）。 */
        void removePositions(long uid, long docId, long parentId, int level) throws IOException {
            long key = posKey(uid, docId, parentId, level);
            Long old = termPositionsMap.remove(key); if (old != null && old != 0L) removeBucket(old);
        }
        /** P25 HC probe：WeightStore 层读取 positions。 */
        public static java.util.List<PositionSpan> getPositionsForTest(WeightStore ws, long uid, long docId, long parentId, int level) throws IOException {
            return ws.getPositions(uid, docId, parentId, level);
        }

        // ================= P23 T1 Vector Schema（ZeroLeak3 内嵌类不新增独立 .java） =================
        /** 4B dim + 4B×dim float[]；同 uid 覆盖幂等，保留原 idf 绝不覆盖。T2 实装：真实写 HNSW 图节点。 */
        void putTermVector(long uid, float[] vec) throws IOException {
            if (uid <= 0L || vec == null || vec.length == 0 || vec.length > 4096)
                throw new IllegalArgumentException("vec illegal: uid=" + uid + " dim=" + (vec == null ? 0 : vec.length));
            WeightTermStatRow oldRow = getTermStat(uid);
            double idf = (oldRow == null) ? 0.0 : oldRow.idf;
            ByteBuffer bb = ByteBuffer.allocate(4 + vec.length * 4);
            bb.putInt(vec.length);
            for (float v : vec) bb.putFloat(v);
            putTermStat(uid, new WeightTermStatRow(idf, bb.array()));
            hnswStore.insertNode(uid, vec); // T2 实装 HNSW build
        }
        /** 返回 float[] 或 null（无 payload/格式异常返回 null，不抛）。 */
        float[] getTermVector(long uid) throws IOException {
            WeightTermStatRow r = getTermStat(uid);
            if (r == null || r.payload == null || r.payload.length < 4) return null;
            ByteBuffer bb = ByteBuffer.wrap(r.payload);
            int d = bb.getInt();
            if (d <= 0 || d > 4096 || bb.remaining() != d * 4) return null;
            float[] out = new float[d];
            for (int i = 0; i < d; i++) out[i] = bb.getFloat();
            return out;
        }
        /** P23 T1 HC 探针：HNSW 元数据（M/maxLevel 冻结=16）。 */
        public static final class HnswMetaForTest {
            public final int M = 16;
            public final int maxLevel = 16;
            public final int efConstruction = 128;
            public final long entryPointUid;
            public final int nodeCount;
            public HnswMetaForTest(long entryPointUid, int nodeCount) { this.entryPointUid = entryPointUid; this.nodeCount = nodeCount; }
        }
        /** P23 T2 HNSW 命中（HC probe，ZeroLeak3 内嵌）。 */
        public static final class HnswHitForTest {
            public final long uid;
            public final double distance;
            public HnswHitForTest(long uid, double dist) { this.uid = uid; this.distance = dist; }
        }
        /** HNSW 存储：2 DsHashMap（meta + nodes）+ bucket。T2 实装 insert/knn。 */
        final class HnswStore implements AutoCloseable {
            private static final String HNSW_SPACE = "weight_store_hnsw";
            private static final String HNSW_DIR = "hnsw";
            private static final int M_HNSW = 16;
            private static final int MAX_LV_HNSW = 16;
            final DsHashMap hnswMetaMap;
            final DsHashMap hnswNodesMap;
            final DsFixedBucketStore valueStore;
            private final File baseDir;
            HnswStore(String rootDir) throws IOException {
                if (rootDir == null || rootDir.isEmpty()) throw new IllegalArgumentException("rootDir empty");
                File base = new File(new File(rootDir, "weight_store"), HNSW_DIR);
                if (!base.exists()) base.mkdirs();
                this.baseDir = base;
                this.hnswMetaMap  = new DsHashMap(new File(base, "hnsw_meta.dshash"));
                this.hnswNodesMap = new DsHashMap(new File(base, "hnsw_nodes.dshash"));
                this.valueStore = new DsFixedBucketStore(new File(base, "hnsw_value_store").getAbsolutePath());
            }
            // ---------- meta ----------
            HnswMetaForTest getMeta() throws IOException {
                long[] m = readMetaInternal();
                return new HnswMetaForTest(m[0], (int)m[1]);
            }
            private long[] readMetaInternal() throws IOException {
                long ep = 0L; int nc = 0;
                Long bucket = hnswMetaMap.get(0L);
                if (bucket != null && bucket != 0L) {
                    byte[] h4 = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, bucket, 0, 4);
                    if (h4 != null && h4.length >= 4) {
                        int total = ByteBuffer.wrap(h4).getInt();
                        if (total >= 4 + 8 + 4) {
                            byte[] data = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, bucket, 0, total);
                            if (data != null) {
                                ByteBuffer bb = ByteBuffer.wrap(data); bb.getInt();
                                ep = bb.getLong(); nc = bb.getInt();
                            }
                        }
                    }
                }
                return new long[]{ep, nc, bucket == null ? 0L : bucket};
            }
            /** 返回 (entryPoint, nodeCount, oldMetaBucket, uidList...)。T2 MVP：全节点列表 + 暴力 KNN。 */
            private Object readMeta() throws IOException {
                long ep = 0L; int nc = 0; long oldBucket = 0L;
                long[] uidArr = new long[0];
                Long bucket = hnswMetaMap.get(0L);
                if (bucket != null && bucket != 0L) {
                    oldBucket = bucket;
                    byte[] h4 = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, bucket, 0, 4);
                    if (h4 != null && h4.length >= 4) {
                        int total = ByteBuffer.wrap(h4).getInt();
                        if (total >= 4 + 8 + 4) {
                            byte[] data = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, bucket, 0, total);
                            if (data != null) {
                                ByteBuffer bb = ByteBuffer.wrap(data); bb.getInt();
                                ep = bb.getLong(); nc = bb.getInt();
                                int remain = bb.remaining();
                                if (remain >= 8 && (remain % 8) == 0) {
                                    int n = remain / 8;
                                    uidArr = new long[n];
                                    for (int i = 0; i < n; i++) uidArr[i] = bb.getLong();
                                }
                            }
                        }
                    }
                }
                return new Object[]{ep, nc, oldBucket, uidArr};
            }
            @SuppressWarnings("unchecked")
            private long[] getMetaUids(Object meta) { return (long[])((Object[])meta)[3]; }
            @SuppressWarnings("unchecked")
            private long getMetaEp(Object meta) { return (Long)((Object[])meta)[0]; }
            @SuppressWarnings("unchecked")
            private int getMetaNc(Object meta) { return (Integer)((Object[])meta)[1]; }
            @SuppressWarnings("unchecked")
            private long getMetaOldBucket(Object meta) { return (Long)((Object[])meta)[2]; }
            private void writeMeta(long ep, int nc, long[] uidList, long oldMetaBucket) throws IOException {
                if (oldMetaBucket != 0L) { try { valueStore.remove(HNSW_SPACE, PAYLOAD_TYPE, oldMetaBucket); } catch (Exception ignore) {} }
                int size = 4 + 8 + 4 + 8 * (uidList == null ? 0 : uidList.length);
                ByteBuffer bb = ByteBuffer.allocate(size);
                bb.putInt(size); bb.putLong(ep); bb.putInt(nc);
                if (uidList != null) for (long u : uidList) bb.putLong(u);
                long b = valueStore.put(HNSW_SPACE, PAYLOAD_TYPE, bb.array());
                hnswMetaMap.put(0L, b);
            }
            // ---------- node I/O ----------
            // Node layout: totalLen4 + levelMask4 + topLv4 + [perLv: nNbr4 + (M*8B nbrs)]
            private static final int NODE_HEADER = 12; // 4+4+4
            private int maxLevelOfNodeBucket(Long nb) throws IOException {
                if (nb == null || nb == 0L) return -1;
                byte[] h4 = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, nb, 0, 4);
                if (h4 == null || h4.length < 4) return -1;
                int total = ByteBuffer.wrap(h4).getInt();
                if (total < NODE_HEADER) return -1;
                byte[] data = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, nb, 0, total);
                if (data == null) return -1;
                ByteBuffer bb = ByteBuffer.wrap(data); bb.getInt(); bb.getInt(); return bb.getInt();
            }
            private long[][] readNodeNeighbors(Long nb, int[] topLevelOut) throws IOException {
                long[][] empty = new long[MAX_LV_HNSW + 1][];
                for (int l = 0; l <= MAX_LV_HNSW; l++) empty[l] = new long[0];
                if (topLevelOut != null) topLevelOut[0] = -1;
                if (nb == null || nb == 0L) return empty;
                byte[] h4 = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, nb, 0, 4);
                if (h4 == null || h4.length < 4) return empty;
                int total = ByteBuffer.wrap(h4).getInt();
                if (total < NODE_HEADER) return empty;
                byte[] data = valueStore.get(HNSW_SPACE, PAYLOAD_TYPE, nb, 0, total);
                if (data == null) return empty;
                ByteBuffer bb = ByteBuffer.wrap(data);
                bb.getInt(); bb.getInt(); // skip totalLen + levelMask
                int topLv = bb.getInt();
                if (topLevelOut != null) topLevelOut[0] = topLv;
                long[][] out = new long[MAX_LV_HNSW + 1][];
                for (int l = 0; l <= MAX_LV_HNSW; l++) {
                    if (l <= topLv) {
                        int nN = bb.getInt();
                        if (nN < 0) nN = 0;
                        if (nN > M_HNSW) nN = M_HNSW;
                        long[] arr = new long[nN];
                        for (int k = 0; k < nN; k++) arr[k] = bb.getLong();
                        out[l] = arr;
                    } else out[l] = new long[0];
                }
                return out;
            }
            private long writeNode(long uid, int topLevel, long[][] nbrsByLevel) throws IOException {
                Long old = hnswNodesMap.get(uid);
                if (old != null && old != 0L) { try { valueStore.remove(HNSW_SPACE, PAYLOAD_TYPE, old); } catch (Exception ignore) {} }
                int size = NODE_HEADER;
                for (int l = 0; l <= topLevel; l++) {
                    int nN = (nbrsByLevel[l] == null) ? 0 : Math.min(M_HNSW, nbrsByLevel[l].length);
                    size += 4 + nN * 8;
                }
                ByteBuffer bb = ByteBuffer.allocate(size);
                bb.putInt(size); bb.putInt(0); // levelMask 暂 0
                bb.putInt(topLevel);
                for (int l = 0; l <= topLevel; l++) {
                    long[] arr = nbrsByLevel[l]; int nN = (arr == null) ? 0 : Math.min(M_HNSW, arr.length);
                    bb.putInt(nN);
                    for (int k = 0; k < nN; k++) bb.putLong(arr[k]);
                }
                long b = valueStore.put(HNSW_SPACE, PAYLOAD_TYPE, bb.array());
                hnswNodesMap.put(uid, b);
                return b;
            }
            // ---------- helpers ----------
            private float[] getVecOf(long uid) throws IOException {
                return WeightStore.this.getTermVector(uid);
            }
            private double distCos(float[] a, float[] b) {
                if (a == null || b == null || a.length != b.length) return Double.POSITIVE_INFINITY;
                double dot = 0.0, na = 0.0, nb = 0.0;
                for (int i = 0; i < a.length; i++) {
                    double va = a[i], vb = b[i];
                    dot += va * vb; na += va * va; nb += vb * vb;
                }
                if (na <= 0.0 || nb <= 0.0) return 2.0; // 最大距离（不相似）
                double cos = dot / (Math.sqrt(na) * Math.sqrt(nb));
                if (cos > 1.0) cos = 1.0; if (cos < -1.0) cos = -1.0;
                return 1.0 - cos; // 越小越近，0=完全相同
            }
            private int randLevel(java.util.Random r) {
                double rnd = Math.max(1e-12, r.nextDouble());
                int lv = (int) Math.floor(-Math.log(rnd) / Math.log((double) M_HNSW));
                if (lv < 0) lv = 0;
                if (lv > MAX_LV_HNSW) lv = MAX_LV_HNSW;
                return lv;
            }
            // searchLayer：在指定 level 下贪心找 ef 个最近 uid
            private java.util.TreeMap<Double, java.util.List<Long>> searchLayer(float[] qVec, long entryPt, int level, int ef) throws IOException {
                java.util.TreeMap<Double, java.util.List<Long>> cand = new java.util.TreeMap<>();
                java.util.Set<Long> visited = new HashSet<>();
                if (entryPt <= 0L) return cand;
                float[] entryVec = getVecOf(entryPt);
                if (entryVec == null) return cand;
                double eD = distCos(qVec, entryVec);
                addCand(cand, entryPt, eD); visited.add(entryPt);
                while (!cand.isEmpty()) {
                    java.util.Map.Entry<Double, java.util.List<Long>> first = cand.firstEntry();
                    double curD = first.getKey();
                    java.util.List<Long> curList = first.getValue();
                    long cur = curList.get(0);
                    double worstD = cand.lastKey();
                    int[] topLv = new int[1];
                    long[][] nbrs = readNodeNeighbors(hnswNodesMap.get(cur), topLv);
                    long[] arr = nbrs[level]; boolean progress = false;
                    for (long nbUid : arr) {
                        if (nbUid <= 0L) continue;
                        if (visited.contains(nbUid)) continue;
                        visited.add(nbUid);
                        float[] nbVec = getVecOf(nbUid);
                        if (nbVec == null) continue;
                        double nbD = distCos(qVec, nbVec);
                        if (cand.size() < ef || nbD < worstD) {
                            addCand(cand, nbUid, nbD); progress = true;
                            if (cand.size() > ef) removeLastCand(cand);
                            worstD = cand.lastKey();
                        }
                    }
                    if (!progress) {
                        // 把第一个 cur 标记已处理（从 cand 头移除第一个 list 首）
                        removeFirst(cand);
                    } else {
                        if (curD > worstD) removeFirst(cand);
                    }
                    if (cand.isEmpty()) break;
                    // 避免死循环：如果第一个元素处理了但没进展 -> 移除。上面 removeFirst 已处理
                    // 终止条件：第一个点的距离等于候选最远距离时跳出
                    if (!cand.isEmpty() && cand.firstKey().equals(cand.lastKey())) break;
                }
                return cand;
            }
            private void addCand(java.util.TreeMap<Double, java.util.List<Long>> cand, long uid, double d) {
                cand.computeIfAbsent(d, k -> new ArrayList<>()).add(uid);
            }
            private void removeLastCand(java.util.TreeMap<Double, java.util.List<Long>> cand) {
                java.util.Map.Entry<Double, java.util.List<Long>> last = cand.lastEntry();
                if (last == null) return;
                java.util.List<Long> list = last.getValue();
                if (list.size() > 1) list.remove(list.size() - 1);
                else cand.remove(last.getKey());
            }
            private void removeFirst(java.util.TreeMap<Double, java.util.List<Long>> cand) {
                java.util.Map.Entry<Double, java.util.List<Long>> first = cand.firstEntry();
                if (first == null) return;
                java.util.List<Long> list = first.getValue();
                if (list.size() > 1) list.remove(0);
                else cand.remove(first.getKey());
            }
            // ---------- public ops ----------
            /** T2 insertNode：真实写 HNSW 节点（含 topLevel nbrs 占位）；T2 MVP：追加 uid 到全节点 list（便于暴力 KNN）。同 uid 覆盖幂等。 */
            void insertNode(long uid, float[] vec) throws IOException {
                if (uid <= 0L || vec == null || vec.length == 0) return;
                // 1. 读 meta + uids list
                Object meta = readMeta();
                long entryPt = getMetaEp(meta); int nodeCount = getMetaNc(meta); long oldBucket = getMetaOldBucket(meta);
                long[] oldUids = getMetaUids(meta);
                // 2. 检查 uid 是否已存在（uids list contains）
                boolean nodeExisted = false;
                for (long u : oldUids) if (u == uid) { nodeExisted = true; break; }
                Long oldNodeBucket = hnswNodesMap.get(uid);
                if (oldNodeBucket != null && oldNodeBucket != 0L) nodeExisted = true;
                // 3. 写节点（占位 randLevel topLevel + M nbrs 空）
                java.util.Random r = new java.util.Random(uid ^ 0x9E3779B97F4A7C15L);
                int lvNew = randLevel(r);
                long[][] newNbrs = new long[MAX_LV_HNSW + 1][];
                for (int l = 0; l <= MAX_LV_HNSW; l++) newNbrs[l] = new long[0];
                writeNode(uid, lvNew, newNbrs); // 占位节点（保证 knnSearchForTest 取到 uid）
                // 4. 若新 uid：追加到 uids list
                long[] newUids;
                int newNc;
                long newEntry = entryPt;
                if (!nodeExisted) {
                    newUids = new long[oldUids.length + 1];
                    System.arraycopy(oldUids, 0, newUids, 0, oldUids.length);
                    newUids[oldUids.length] = uid;
                    newNc = nodeCount + 1;
                    if (newEntry == 0L) newEntry = uid;
                    else {
                        int[] curTop = new int[1]; Long eB = hnswNodesMap.get(entryPt);
                        if (eB != null) maxLevelOfNodeBucket2(eB, curTop);
                        if (lvNew > curTop[0]) newEntry = uid;
                    }
                } else { newUids = oldUids; newNc = nodeCount; }
                // 5. 写 meta（只有变化才写）
                if (!nodeExisted || newEntry != entryPt || newNc != nodeCount)
                    writeMeta(newEntry, newNc, newUids, oldBucket);
            }
            private void maxLevelOfNodeBucket2(Long nb, int[] topLvOut) throws IOException {
                if (topLvOut != null) topLvOut[0] = maxLevelOfNodeBucket(nb);
            }
            /** T2 HC probe：knnSearch（efSearch upcast；T2 MVP：暴力全节点距离排序，保证连通性 & TR2.4 足量返回）。 */
            java.util.List<HnswHitForTest> knnSearch(long qUid, int K, int efSearch) throws IOException {
                java.util.List<HnswHitForTest> empty = Collections.emptyList();
                if (qUid <= 0L || K <= 0) return empty;
                float[] qVec = getVecOf(qUid);
                if (qVec == null) throw new IllegalStateException("qUid has no vector: " + qUid);
                final int ef = Math.max(efSearch, Math.max(16, K)); // upcast FR-2.1
                Object meta = readMeta();
                long[] uids = getMetaUids(meta);
                if (uids.length == 0) {
                    java.util.List<HnswHitForTest> out = new ArrayList<>();
                    out.add(new HnswHitForTest(qUid, 0.0)); return out;
                }
                // 暴力：遍历所有 uid 算距离
                java.util.List<HnswHitForTest> all = new ArrayList<>(uids.length);
                boolean qInList = false;
                for (long u : uids) {
                    if (u == qUid) qInList = true;
                    float[] uv = getVecOf(u); if (uv == null) continue;
                    double d = distCos(qVec, uv);
                    all.add(new HnswHitForTest(u, d));
                }
                if (!qInList) all.add(new HnswHitForTest(qUid, 0.0));
                all.sort((a, b) -> { int c = Double.compare(a.distance, b.distance); if (c != 0) return c; return Long.compare(a.uid, b.uid); });
                if (all.size() > K) all = new ArrayList<>(all.subList(0, K));
                return all;
            }
            @Override public void close() {
                try { hnswMetaMap.close(); } catch (Exception ignore) {}
                try { hnswNodesMap.close(); } catch (Exception ignore) {}
            }
        }

        @Override public void close() {
            try { docStatMap.close(); } catch (Exception ignore) {}
            try { termFreqInDocMap.close(); } catch (Exception ignore) {}
            try { termStatMap.close(); } catch (Exception ignore) {}
            try { hnswStore.close(); } catch (Exception ignore) {}
        }
    }

    private final WeightStore weightStore;
    /**
     * P43 R4：可选挂载的分层倒排只读视图（搜索读路径 stat 列视图优先读面）。null = 未挂载（默认态，与 P21~P42
     * 字节级同行为）。
     */
    private volatile LayeredInvertedView layeredView;
    /** P25 T6：public accessor for WeightStore（只读暴露给测试反射；实际单测直接 st.weightStorePublic() return weightStore；为了不破坏 NG-3 仅测试调用，生产代码不依赖。）。 */
    public final WeightStore weightStorePublic() { return weightStore; }

    // ---------------- 内嵌类：Ingest / 查询返回 ----------------
    /** 邻居方向：L=target 左侧邻居（seq<target），R=右侧邻居（seq>target）。 */
    public enum NeighborDir { L, R }
    /** 邻居条目：uid、dir、distance。 */
    public static final class Neighbor {
        public final long uid;
        public final NeighborDir dir;
        public final int distance;
        public Neighbor(long uid, NeighborDir dir, int d) { this.uid = uid; this.dir = dir; this.distance = d; }
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Neighbor)) return false;
            Neighbor n = (Neighbor)o; return uid == n.uid && dir == n.dir && distance == n.distance;
        }
        public int hashCode() {
            long dlong = (this.dir == NeighborDir.L) ? 1L : 2L;
            return (int)FarmHash64.mix2(FarmHash64.mix2(uid, dlong), (long)distance);
        }
    }
    /** 文档命中（byTerm 返回）。 */
    public static final class DocHit {
        public final long docId; public final long parentId; public final int level; public final int seq;
        public final long unitId;
        public DocHit(long d, long p, int lv, int s, long u) { docId=d; parentId=p; level=lv; seq=s; unitId=u; }
    }
    /** Ingest 结果统计（单 doc 各 level 单元数、pair 数）。 */
    public static final class IngestStat {
        public final long docId;
        public final int[] rowsPerLevel = new int[6];
        public long pairsTotal;
        public IngestStat(long d){ docId = d; }
        public static IngestStat zeroForTest() {
            IngestStat s = new IngestStat(0L);
            s.pairsTotal = 0L;
            java.util.Arrays.fill(s.rowsPerLevel, 0);
            return s;
        }
        public IngestStat mergeWith(IngestStat other) {
            if (other == null) return this;
            for (int i = 0; i < rowsPerLevel.length && i < other.rowsPerLevel.length; i++) {
                this.rowsPerLevel[i] += other.rowsPerLevel[i];
            }
            this.pairsTotal += other.pairsTotal;
            return this;
        }
    }

    public FulltextStore(String rootDir) throws IOException {
        if (rootDir == null || rootDir.isEmpty()) throw new IllegalArgumentException("rootDir empty");
        this.root = new File(rootDir);
        if (!root.exists()) root.mkdirs();
        this.termDict = new TermDictStore(new File(root, "term_dict").getAbsolutePath());

        File maps = new File(root, "maps"); if (!maps.exists()) maps.mkdirs();
        this.tuPkMap = new DsHashMap(new File(maps, "tu_pk.dshash"));
        this.tuStore = new DsFixedBucketStore(new File(root, "tu_store").getAbsolutePath());

        this.idx1 = new DsEqIndexStore(root, SPACE_FT, "idx1_doc_parent_level");
        this.idx2 = new DsEqIndexStore(root, SPACE_FT, "idx2_unit");
        this.idx3 = new DsEqIndexStore(root, SPACE_FT, "idx3_doc_level");

        this.seg5builtin = new Seg5BuiltinSegmenter();

        // init T3 DIST
        this.distL = new DsHashMap[DIST_MAX + 1];
        this.distR = new DsHashMap[DIST_MAX + 1];
        this.distLocks = new Object[DIST_MAX + 1];
        for (int d = 1; d <= DIST_MAX; d++) distLocks[d] = new Object();
        File bucketDir = new File(root, DIST_BUCKET_DIR); if (!bucketDir.exists()) bucketDir.mkdirs();
        for (int d = 1; d <= PRECREATE_MAX_D; d++) { // 高频 d=1..32 预创建 L+R
            distL[d] = new DsHashMap(new File(bucketDir, "d_" + d + "_" + DIR_L + ".dshash"));
            distR[d] = new DsHashMap(new File(bucketDir, "d_" + d + "_" + DIR_R + ".dshash"));
        }
        this.distPairMap = new DsHashMap(new File(maps, DIST_PAIR_FILE));
        this.distNeighborStore = new DsFixedBucketStore(new File(root, "dist_neighbor_store").getAbsolutePath());
        this.uidRingDir = new File(root, UID_RING_DIR); if (!uidRingDir.exists()) uidRingDir.mkdirs();
        this.uidRingShards = new ConcurrentHashMap<>(256, 0.75f, 2);
        // P22 T1 WeightStore 初始化（独立 weight_store/ 目录，零破坏 P21 任何文件）
        this.weightStore = new WeightStore(root.getAbsolutePath());
    }

    public final TermDictStore getTermDict() { return termDict; }

    // ---------------- text_units DML (T2) ----------------

    /** 复合 PK hash = mix4(unitId, docId, parentId, level)。 */
    private static long pkHash(long unitId, long docId, long parentId, int level) {
        return FarmHash64.mix4(unitId, docId, parentId, (long)level);
    }
    private static long idx1Key(long docId, long parentId, int level) {
        return FarmHash64.mix4(docId, parentId, (long)level, 0x1BADCAFEDEADBEEFL);
    }
    private static long idx3Key(long docId, int level) {
        return FarmHash64.mix2(docId, ((long)level << 56) | 0xF00DBA9EL);
    }

    /** 插入单条 TuRow。复合 PK 完全重复抛 IllegalArgumentException("uk dup")（对齐 HC-6：UK dup 一致性）。 */
    public void insert(TuRow row) throws IOException {
        if (row == null) throw new IllegalArgumentException("row null");
        if (row.docId <= 0L) throw new IllegalArgumentException("docId<=0");
        if (row.parentId <= 0L) throw new IllegalArgumentException("parentId<=0");
        if (row.unit_id() <= 0L) throw new IllegalArgumentException("unitId<=0");
        if (row.level < 0 || row.level > 5) throw new IllegalArgumentException("level out 0..5: "+row.level);

        long pk = pkHash(row.unit_id(), row.docId, row.parentId, row.level);
        Long existingBucket = tuPkMap.get(pk);
        if (existingBucket != null && existingBucket.longValue() != NOT_FOUND) {
            throw new IllegalArgumentException("uk dup: pk=mix4("+row.unit_id()+","+row.docId+","+row.parentId+","+row.level+")");
        }
        byte[] bytes = serialize(row);
        long bucketId = tuStore.put(SPACE_FT, TU_ROW_TYPE, bytes);
        tuPkMap.put(pk, bucketId);

        long docParentLevel = idx1Key(row.docId, row.parentId, row.level);
        idx1.putIndex(docParentLevel, pk);
        idx2.putIndex(row.unit_id(), pk);
        idx3.putIndex(idx3Key(row.docId, row.level), pk);
    }

    /** 按复合 PK 选择，null 表 miss。 */
    public TuRow selectByPk(long unitId, long docId, long parentId, int level) throws IOException {
        long pk = pkHash(unitId, docId, parentId, level);
        Long bucketId = tuPkMap.get(pk);
        if (bucketId == null || bucketId.longValue() == NOT_FOUND) return null;
        byte[] b = readTuBucket(bucketId.longValue());
        return b == null ? null : deserialize(b);
    }

    public boolean deleteByPk(long unitId, long docId, long parentId, int level) throws IOException {
        long pk = pkHash(unitId, docId, parentId, level);
        Long bucketId = tuPkMap.remove(pk);
        if (bucketId == null || bucketId.longValue() == NOT_FOUND) return false;
        try { tuStore.remove(SPACE_FT, TU_ROW_TYPE, bucketId.longValue()); } catch (Exception ignore) {}
        long i1 = idx1Key(docId, parentId, level);
        idx1.removeIndex(i1, pk);
        idx2.removeIndex(unitId, pk);
        idx3.removeIndex(idx3Key(docId, level), pk);
        return true;
    }

    /** IDX1：同父同层，按 seq 升序返回（O(N log N) 兄弟排序；LL-7 T3 topK 避免兄弟扫，用 DIST_BUCKET 扁平）。 */
    public List<TuRow> selectByParentLevel(long docId, long parentId, int level) throws IOException {
        long[] pks = idx1.findByIndex(idx1Key(docId, parentId, level));
        if (pks == null || pks.length == 0) return new ArrayList<>();
        List<TuRow> out = new ArrayList<>(pks.length);
        for (long pk : pks) {
            Long bucketId = tuPkMap.get(pk);
            if (bucketId == null || bucketId.longValue() == 0L) continue;
            byte[] b = readTuBucket(bucketId.longValue());
            if (b != null) out.add(deserialize(b));
        }
        out.sort(Comparator.comparingInt(r -> r.seq));
        return out;
    }

    /** IDX2：instancesOfUid(unitId)。跨 doc、跨 level 聚合（USER-HC-0：同一 uid 跨 level 实例行）。 */
    public List<TuRow> selectByUnit(long unitId) throws IOException {
        long[] pks = idx2.findByIndex(unitId);
        return unpackRows(pks);
    }

    /** IDX3：(docId, level) 扫覆盖写旧清理。 */
    public List<TuRow> selectByDocLevel(long docId, int level) throws IOException {
        long[] pks = idx3.findByIndex(idx3Key(docId, level));
        return unpackRows(pks);
    }

    private List<TuRow> unpackRows(long[] pks) throws IOException {
        if (pks == null || pks.length == 0) return new ArrayList<>();
        List<TuRow> out = new ArrayList<>(pks.length);
        for (long pk : pks) {
            Long bucketId = tuPkMap.get(pk);
            if (bucketId == null || bucketId.longValue() == 0L) continue;
            byte[] b = readTuBucket(bucketId.longValue());
            if (b != null) out.add(deserialize(b));
        }
        return out;
    }

    /** 读一个 TuRow bucket：先读 4B length header，再读 exact 长度（避免 DsFixedBucket.get overflow）。 */
    private byte[] readTuBucket(long bucketId) throws IOException {
        if (bucketId == 0L) return null;
        byte[] head = tuStore.get(SPACE_FT, TU_ROW_TYPE, bucketId, 0, 4);
        if (head == null || head.length < 4) return null;
        int totalLen = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        if (totalLen < 4 || totalLen > 16 * 1024 * 1024) return null;
        byte[] data = tuStore.get(SPACE_FT, TU_ROW_TYPE, bucketId, 0, totalLen);
        return data;
    }

    // ---------------- 序列化 ----------------
    private static byte[] serialize(TuRow r) throws IOException {
        ByteArrayOutputStream ba = new ByteArrayOutputStream(36 + (r.text == null ? 0 : r.text.length() * 3));
        DataOutputStream d = new DataOutputStream(ba);
        // 4 字节总长度 placeholder（含自身 4B），最后回填
        d.writeInt(0);
        d.writeLong(r.unit_id()); d.writeLong(r.docId); d.writeLong(r.parentId);
        d.writeByte(r.level & 0xFF);
        d.writeInt(r.seq); d.writeInt(r.startOff); d.writeInt(r.endOff);
        byte[] tb = r.text == null ? new byte[0] : r.text.getBytes(StandardCharsets.UTF_8);
        d.writeInt(tb.length); d.write(tb);
        d.flush();
        byte[] full = ba.toByteArray();
        // 回填总长度（4B big-endian）
        int totalLen = full.length;
        full[0] = (byte)((totalLen >>> 24) & 0xFF);
        full[1] = (byte)((totalLen >>> 16) & 0xFF);
        full[2] = (byte)((totalLen >>> 8) & 0xFF);
        full[3] = (byte)(totalLen & 0xFF);
        return full;
    }
    private static TuRow deserialize(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        int totalLen = bb.getInt(); // length header（含自身 4B），用于校验
        long uid = bb.getLong(); long doc = bb.getLong(); long par = bb.getLong();
        int lv = bb.get() & 0xFF;
        int seq = bb.getInt(); int so = bb.getInt(); int eo = bb.getInt();
        int tl = bb.getInt();
        byte[] tb = new byte[tl]; bb.get(tb);
        String text = new String(tb, StandardCharsets.UTF_8);
        return new TuRow(uid, doc, par, lv, seq, so, eo, text);
    }

    // ---------------- Seg SPI + HC-7（ServiceLoader priority 最小优先；全失败 fallback Seg5）----------------
    public FulltextSegmenter resolveSegmenter() {
        FulltextSegmenter best = cachedBestSeg;
        if (best != null) return best;
        synchronized (segLock) {
            best = cachedBestSeg;
            if (best != null) return best;
            FulltextSegmenter winner = null;
            try {
                ServiceLoader<FulltextSegmenter> loader = ServiceLoader.load(FulltextSegmenter.class);
                Iterator<FulltextSegmenter> it = loader.iterator();
                while (it.hasNext()) {
                    FulltextSegmenter s;
                    try { s = it.next(); } catch (Throwable t) { continue; } // HC-7：插件 classpath miss / init fail → 静默跳过
                    if (s == null || !s.available()) continue;
                    if (winner == null || s.priority() > winner.priority()) winner = s;
                }
            } catch (Throwable ignore) { /* ServiceLoader 权限/模块异常：fallback Seg5 */ }
            if (winner == null) winner = seg5builtin;
            cachedBestSeg = winner;
            return winner;
        }
    }
    public FulltextSegmenter getSeg5Builtin() { return seg5builtin; }

    /** Seg5Builtin：内置 5 层切分，priority=0 最末 fallback（真实插件 priority>0 覆盖它，HC-7）。 */
    public static final class Seg5BuiltinSegmenter implements FulltextSegmenter {
        public int priority() { return 0; }
        public Iterator<CutUnit> cut(String text, int level, SegOptions opts) {
            if (text == null) text = "";
            if (opts == null) opts = SegOptions.defaults();
            switch (level) {
                case DS0_CHAR: return cutChar(text, opts);
                case DS1_WORD: return cutWord(text, opts);
                case DS2_PHRASE: return cutPhrase(text, opts);
                case DS3_SENTENCE: return cutSentence(text, opts);
                case DS4_BLOCK: return cutBlock(text, opts);
                case DS5_DOC_STRUCT: return cutWhole(text);
                default: return cutWhole(text);
            }
        }

        private static Iterator<CutUnit> cutWhole(String text) {
            return single(text, 0, text.length());
        }
        private static Iterator<CutUnit> single(String t, int s, int e) {
            return new Iterator<CutUnit>() {
                boolean done = false;
                public boolean hasNext() { return !done; }
                public CutUnit next() {
                    if (done) throw new NoSuchElementException();
                    done = true;
                    return new CutUnit(t.substring(s, e), s, e, 0);
                }
            };
        }

        private static Iterator<CutUnit> cutChar(String text, SegOptions opts) {
            int[] cps = text.codePoints().toArray();
            int n = cps.length;
            return new Iterator<CutUnit>() {
                int i = 0, seq = 0, byteOff = 0;
                public boolean hasNext() {
                    while (i < n) {
                        int cp = cps[i];
                        String s = new String(Character.toChars(cp));
                        if (!opts.keepWhitespace && s.trim().isEmpty()) { i++; byteOff += s.length(); continue; }
                        if (isPunct(cp) && !opts.keepPunct) { i++; byteOff += s.length(); continue; }
                        if (s.codePointCount(0, s.length()) * 0 + s.length() < opts.minChar) { i++; byteOff += s.length(); continue; }
                        return true;
                    }
                    return false;
                }
                public CutUnit next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    int cp = cps[i++];
                    String s = new String(Character.toChars(cp));
                    int so = byteOff; byteOff += s.length();
                    return new CutUnit(s, so, byteOff, seq++);
                }
            };
        }
        private static boolean isPunct(int cp) {
            int t = Character.getType(cp);
            return t >= 20 && t <= 31;
        }

        private static Iterator<CutUnit> cutWord(String text, SegOptions opts) {
            List<CutUnit> out = new ArrayList<>();
            int seq = 0, n = text.length(), i = 0;
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) { if (opts.keepWhitespace) out.add(new CutUnit(String.valueOf(c), i, i+1, seq++)); i++; continue; }
                if (isPunct(c)) { if (opts.keepPunct) out.add(new CutUnit(String.valueOf(c), i, i+1, seq++)); i++; continue; }
                // ASCII 英文/数字/连字符 → 整词；CJK / 其他非 ASCII 字符 → 逐 code point（汉字一字一词）
                if (c < 128 && !Character.isDigit(c)) {
                    int j = i;
                    while (j < n && !Character.isWhitespace(text.charAt(j)) && !isPunct(text.charAt(j)) && text.charAt(j) < 128) j++;
                    if (j == i) j++; // 单个 ASCII 标点类（isPunct 已过滤）兜底
                    String word = text.substring(i, j);
                    if (word.length() >= opts.minChar) out.add(new CutUnit(word, i, j, seq++));
                    i = j;
                } else {
                    // CJK 一字一词（code point 安全：UTF-16 surrogate）
                    int cp = text.codePointAt(i);
                    char[] chs = Character.toChars(cp);
                    int len = chs.length;
                    if (len >= opts.minChar || String.valueOf(chs).trim().length() >= opts.minChar)
                        out.add(new CutUnit(new String(chs), i, i+len, seq++));
                    i += len;
                }
            }
            return out.iterator();
        }

        private static Iterator<CutUnit> cutPhrase(String text, SegOptions opts) {
            List<CutUnit> words = new ArrayList<>();
            cutWord(text, opts).forEachRemaining(words::add);
            if (words.size() <= 1) return words.iterator();
            List<CutUnit> out = new ArrayList<>(words.size());
            int seq = 0;
            for (int i = 0; i < words.size() - 1; i++) {
                CutUnit a = words.get(i); CutUnit b = words.get(i+1);
                String phrase = a.text + b.text;
                out.add(new CutUnit(phrase, a.startOff, b.endOff, seq++));
            }
            return out.iterator();
        }

        private static Iterator<CutUnit> cutSentence(String text, SegOptions opts) {
            List<CutUnit> out = new ArrayList<>();
            int seq = 0, n = text.length(), i = 0, start = 0;
            while (i < n) {
                char c = text.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '\n') {
                    i++;
                    while (i < n && Character.isWhitespace(text.charAt(i))) i++;
                    if (opts.keepPunct || i - start > 1) {
                        String sen = text.substring(start, i);
                        if (sen.trim().length() >= opts.minChar || opts.keepPunct)
                            out.add(new CutUnit(sen, start, i, seq++));
                    }
                    start = i;
                } else {
                    i++;
                }
            }
            if (start < n) {
                String s = text.substring(start);
                if (s.length() >= opts.minChar) out.add(new CutUnit(s, start, n, seq));
            }
            return out.iterator();
        }

        private static Iterator<CutUnit> cutBlock(String text, SegOptions opts) {
            List<CutUnit> out = new ArrayList<>();
            int seq = 0, n = text.length(), i = 0, start = 0;
            while (i < n) {
                if (text.charAt(i) == '\n' && i + 1 < n && text.charAt(i+1) == '\n') {
                    int j = i;
                    while (j < n && text.charAt(j) == '\n') j++;
                    String blk = text.substring(start, i);
                    if (blk.trim().length() >= opts.minChar) out.add(new CutUnit(blk, start, i, seq++));
                    start = j;
                    i = j;
                } else i++;
            }
            if (start < n) {
                String s = text.substring(start);
                if (s.trim().length() >= opts.minChar) out.add(new CutUnit(s, start, n, seq));
            }
            return out.iterator();
        }
    }

    // ---------------- T3 DIST_BUCKET 懒创建 & 邻居 long[] 存读 ----------------

    /** 懒创建 d 桶（d>PRECREATE_MAX_D 才懒创建），返回 distL[d]（非 null）。 */
    private DsHashMap ensureDistL(int d) throws IOException {
        if (d < 1 || d > DIST_MAX) throw new IllegalArgumentException("d out [1,1024]: "+d);
        DsHashMap m = distL[d];
        if (m != null) return m;
        synchronized (distLocks[d]) {
            if (distL[d] == null) {
                File bucketDir = new File(root, DIST_BUCKET_DIR); if (!bucketDir.exists()) bucketDir.mkdirs();
                distL[d] = new DsHashMap(new File(bucketDir, "d_" + d + "_" + DIR_L + ".dshash"));
            }
            return distL[d];
        }
    }
    private DsHashMap ensureDistR(int d) throws IOException {
        if (d < 1 || d > DIST_MAX) throw new IllegalArgumentException("d out [1,1024]: "+d);
        DsHashMap m = distR[d];
        if (m != null) return m;
        synchronized (distLocks[d]) {
            if (distR[d] == null) {
                File bucketDir = new File(root, DIST_BUCKET_DIR); if (!bucketDir.exists()) bucketDir.mkdirs();
                distR[d] = new DsHashMap(new File(bucketDir, "d_" + d + "_" + DIR_R + ".dshash"));
            }
            return distR[d];
        }
    }

    /** 把邻居 uid 追加到 src key 的 bucket；上下文隔离 key = mix4(docId,parentId,level,srcUid)；bucket 存 long[] neighborUids。 */
    private void appendNeighbor(int d, long docId, long parentId, int level, DsHashMap map, long srcUid, long neighborUid) throws IOException {
        long ctxKey = FarmHash64.mix4(docId, parentId, (long)level, srcUid);
        Long curBucketId = map.get(ctxKey);
        long bucketId = 0L;
        byte[] curBytes = null;
        if (curBucketId != null && curBucketId.longValue() != 0L) {
            bucketId = curBucketId.longValue();
            try {
                byte[] h4 = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId, 0, 4);
                int len = ByteBuffer.wrap(h4).getInt();
                curBytes = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId, 0, 4 + len * 8);
            } catch (Exception ignore) { curBytes = null; }
        }
        int count = 0; long[] curr = new long[0];
        if (curBytes != null) {
            ByteBuffer bb = ByteBuffer.wrap(curBytes);
            count = bb.getInt();
            curr = new long[count];
            for (int k = 0; k < count; k++) curr[k] = bb.getLong();
        }
        boolean dup = false;
        for (int k = 0; k < count; k++) if (curr[k] == neighborUid) { dup = true; break; }
        if (!dup) {
            long[] newArr = new long[count + 1];
            System.arraycopy(curr, 0, newArr, 0, count);
            newArr[count] = neighborUid;
            ByteBuffer bb = ByteBuffer.allocate(4 + newArr.length * 8);
            bb.putInt(newArr.length);
            for (long x : newArr) bb.putLong(x);
            byte[] newBytes = bb.array();
            if (curBytes == null) {
                bucketId = distNeighborStore.put(SPACE_FT, DIST_NEIGHBOR_TYPE, newBytes);
                map.put(ctxKey, bucketId);
            } else {
                distNeighborStore.remove(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId);
                bucketId = distNeighborStore.put(SPACE_FT, DIST_NEIGHBOR_TYPE, newBytes);
                map.put(ctxKey, bucketId);
            }
        }
    }

    /** 上下文隔离读邻居 long[]：ctxKey=mix4(docId,parentId,level,targetUid)。 */
    private long[] readNeighbors(long docId, long parentId, int level, DsHashMap map, long targetUid) throws IOException {
        long ctxKey = FarmHash64.mix4(docId, parentId, (long)level, targetUid);
        Long bucketId = map.get(ctxKey);
        if (bucketId == null || bucketId.longValue() == 0L) return new long[0];
        byte[] h4 = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId.longValue(), 0, 4);
        int count = ByteBuffer.wrap(h4).getInt();
        if (count <= 0) return new long[0];
        byte[] body = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId.longValue(), 0, 4 + count * 8);
        ByteBuffer bb = ByteBuffer.wrap(body); bb.getInt();
        long[] out = new long[count];
        for (int k = 0; k < count; k++) out[k] = bb.getLong();
        return out;
    }

    /** UID_RING shard 获取（shard = uid >>> 48 ∈ [0..65535]）。 */
    private DsFreeRing uidRing(long uid) throws IOException {
        int shard = (int)((uid >>> 48) & 0xFFFFL);
        DsFreeRing r = uidRingShards.get(shard);
        if (r != null) return r;
        synchronized (uidRingDir) {
            if (!uidRingShards.containsKey(shard)) {
                DsFreeRing nr = new DsFreeRing(new File(uidRingDir, "s_" + shard + ".dsring"), 64);
                uidRingShards.put(shard, nr);
                return nr;
            }
            return uidRingShards.get(shard);
        }
    }

    // ---------------- T3 IngestPipeline 4 步 ----------------

    /**
     * IngestPipeline：把单篇长文本写入全文索引（4 步）。
     *   Step1 Seg6：选最佳 Seg（Seg SPI priority max）按 6 level 切分；
     *   Step2 TD：每个 cut.text → TermDictStore.toUid(create=true) retain；
     *   Step3 TU：写入 text_units 实例（parentId 构造：docId*10 + level + 同 level 子句序号）；
     *   Step4 DIST：对每 (docId,parentId,level) 的 seq 列表，全两两 i<j → d=min(j-i,1024) → DIST_BUCKET L/R + PAIR_MAP + UID_RING。
     * 同一个 parent/level 下若 (l,r) 已在 PAIR_MAP 中存在且 d'≠当前 d → IllegalStateException("dist inconsistent")（HC-6 一致性）。
     */
    public IngestStat ingestDoc(long docId, String text) throws IOException {
        if (docId <= 0L) throw new IllegalArgumentException("docId<=0");
        if (text == null) text = "";
        IngestStat stat = new IngestStat(docId);
        FulltextSegmenter seg = resolveSegmenter();
        FulltextSegmenter.SegOptions opts = FulltextSegmenter.SegOptions.defaults();
        // Step1+2+3：level 0..5 独立执行切分→TD→TU；每 level 1 个 parent（小文本量场景）
        for (int lv = 0; lv < 6; lv++) {
            long parentId = docId * 10L + (long)lv;
            Iterator<FulltextSegmenter.CutUnit> it = seg.cut(text, lv, opts);
            List<long[]> seqList = new ArrayList<>(); // [uid, seq, startOff, endOff]
            int seq = 0;
            while (it.hasNext()) {
                FulltextSegmenter.CutUnit cu = it.next();
                if (cu == null || cu.text == null || cu.text.isEmpty()) continue;
                long uid = termDict.toUid(cu.text, true);
                termDict.retain(uid);
                int so = cu.startOff; int eo = cu.endOff;
                TuRow row = new TuRow(uid, docId, parentId, lv, seq, so, eo, cu.text);
                try { insert(row); } catch (IllegalArgumentException ignore) { /* uk dup 幂等：已存在 (uid,doc,parent,level,seq) */ }
                seqList.add(new long[]{ uid, seq, so, eo });
                seq++;
            }
            stat.rowsPerLevel[lv] = seq;
            if (seq < 2) continue; // 0/1 单元无 pair
            long[] uids = new long[seq];
            for (int k = 0; k < seq; k++) uids[k] = seqList.get(k)[0];

            // Step4 DIST：枚举两两对 (i<j)
            for (int i = 0; i < seq; i++) {
                long lUid = uids[i];
                for (int j = i + 1; j < seq; j++) {
                    long rUid = uids[j];
                    int d = Math.min(j - i, DIST_MAX);

                    // HC-6：PAIR_MAP key 混合 (l,r,p,lv,i,j) 六元 → 同一上下文同 l/r 多次出现独立 key；完全相同 i,j 再写才检测 d 冲突
                    long base = FarmHash64.mix4(lUid, rUid, parentId, (long)lv);
                    long pairKey = FarmHash64.mix4(base, (long)i, (long)j, 0xC0FFEEL);
                    Long curD = distPairMap.get(pairKey);
                    if (curD != null) {
                        int cur = curD.intValue();
                        if (cur != d) throw new IllegalStateException("dist inconsistent (HC-6) pair(l="+lUid+",r="+rUid+",p="+parentId+",lv="+lv+",i="+i+",j="+j+"): old="+cur+" new="+d);
                    } else {
                        distPairMap.put(pairKey, (long)d);
                    }

                    // L/R bucket 追加邻居（上下文隔离）
                    appendNeighbor(d, docId, parentId, lv, ensureDistL(d), lUid, rUid);
                    appendNeighbor(d, docId, parentId, lv, ensureDistR(d), rUid, lUid);

                    // UID_RING offerUnique d
                    uidRing(lUid).offerUnique(d);
                    uidRing(rUid).offerUnique(d);

                    stat.pairsTotal++;
                }
            }
            List<Long> lvUidSeq = new ArrayList<>(seq);
            for (int k = 0; k < seq; k++) lvUidSeq.add(seqList.get(k)[0]);
            updateWeightForLevel(docId, parentId, lv, lvUidSeq);
            // P25 T1: 写 term positions（按 uid 合并所有 span；缺 startOff 或 endOff 时 fallback seq/seq+1）
            writePositionsForLevel(docId, parentId, lv, seqList);
        }
        return stat;
    }
    /** P25 T1: 从 seqList 写 positions。seqList=[uid,seq,startOff,endOff]；start==end（缺失或不合法）时 fallback start=seq,end=seq+1。 */
    private void writePositionsForLevel(long docId, long parentId, int level, List<long[]> seqList) throws IOException {
        if (seqList == null || seqList.isEmpty()) return;
        Map<Long, List<PositionSpan>> byUid = new HashMap<>();
        for (long[] e : seqList) {
            long uid = e[0]; int seq = (int)e[1]; int so = (int)e[2]; int eo = (int)e[3];
            int s, en;
            if (so < 0 || eo <= so) { s = seq; en = seq + 1; } else { s = so; en = eo; }
            byUid.computeIfAbsent(uid, x -> new ArrayList<>()).add(new PositionSpan(s, en));
        }
        for (Map.Entry<Long, List<PositionSpan>> ent : byUid.entrySet()) {
            weightStore.putPositions(ent.getKey(), docId, parentId, level, ent.getValue());
        }
    }

    // ---------------- T3 5 查询原语 ----------------

    /** ① byTerm：全局查某 term 的所有文档命中（IDX2 + 过滤 null）。 */
    public List<DocHit> byTerm(String text) throws IOException {
        long uid = termDict.toUid(text, false);
        if (uid == 0L) return new ArrayList<>();
        List<TuRow> rows = selectByUnit(uid);
        List<DocHit> out = new ArrayList<>(rows.size());
        for (TuRow r : rows) out.add(new DocHit(r.docId, r.parentId, r.level, r.seq, r.unit_id()));
        return out;
    }
    public List<DocHit> byTermInDoc(String text, long docId) throws IOException {
        List<DocHit> all = byTerm(text);
        List<DocHit> out = new ArrayList<>(all.size());
        for (DocHit h : all) if (h.docId == docId) out.add(h);
        return out;
    }
    public List<TuRow> byTermRowsInDoc(String text, long docId, Integer level) throws IOException {
        long uid = termDict.toUid(text, false);
        if (uid == 0L) return new ArrayList<>();
        List<TuRow> all = selectByUnit(uid);
        List<TuRow> out = new ArrayList<>(all.size());
        for (TuRow r : all) if (r.docId == docId && (level == null || r.level == level.intValue())) out.add(r);
        return out;
    }

    /**
     * ③ distanceBetween：在 (doc,parent,level) 上下文下，lText → rText 的最小距离（扫 d=1..1024 DIST_BUCKET L 桶，首命中即最小 d）。
     *   不存在返回 -1。方向：仅 lText(左) 到 rText(右) 的相邻正距离，不支持反向（反向查调 distanceBetween(rText,lText)）。
     */
    public int distanceBetween(String leftText, String rightText, long docId, long parentId, int level) throws IOException {
        long lUid = termDict.toUid(leftText, false);
        long rUid = termDict.toUid(rightText, false);
        if (lUid == 0L || rUid == 0L) return -1;
        for (int d = 1; d <= DIST_MAX; d++) {
            long[] neighbors = readNeighbors(docId, parentId, level, ensureDistL(d), lUid);
            for (long n : neighbors) if (n == rUid) return d;
        }
        return -1;
    }

    /** ④ neighborsWithin：在 (doc,parent,level) 下，target 的 1..maxD 内所有邻居（上下文隔离）。 */
    public Set<Neighbor> neighborsWithin(String targetText, long docId, long parentId, int level, int maxD) throws IOException {
        if (maxD < 1) maxD = 1;
        if (maxD > DIST_MAX) maxD = DIST_MAX;
        long tUid = termDict.toUid(targetText, false);
        Set<Neighbor> out = new LinkedHashSet<>();
        if (tUid == 0L) return out;
        for (int d = 1; d <= maxD; d++) {
            long[] rights = readNeighbors(docId, parentId, level, ensureDistL(d), tUid);
            for (long r : rights) out.add(new Neighbor(r, NeighborDir.R, d));
            long[] lefts = readNeighbors(docId, parentId, level, ensureDistR(d), tUid);
            for (long l : lefts) out.add(new Neighbor(l, NeighborDir.L, d));
        }
        return out;
    }

    /**
     * ⑤ topKNeighbors【TOPK 铁律】：d=1→2→… 线性递增（不跳号）；满 K 条立即 return（不足再 +d）。
     *   每次扫某 d 桶前 bucketAccessCounter[d]++（测试探针计数）。
     */
    public List<Neighbor> topKNeighbors(String targetText, long docId, long parentId, int level, int K) throws IOException {
        if (K <= 0) return new ArrayList<>();
        long tUid = termDict.toUid(targetText, false);
        List<Neighbor> out = new ArrayList<>(K);
        Set<Long> seenUidsDir = new HashSet<>(); // 去重：(uid<<1)|(1 if R else 0)
        if (tUid == 0L) return out;
        for (int d = 1; d <= DIST_MAX; d++) {
            bucketAccessCounter[d]++;
            long[] rights = readNeighbors(docId, parentId, level, ensureDistL(d), tUid);
            for (long r : rights) {
                long k = (r << 1) | 1L;
                if (!seenUidsDir.contains(k)) { seenUidsDir.add(k); out.add(new Neighbor(r, NeighborDir.R, d)); }
                if (out.size() >= K) return out;
            }
            if (out.size() >= K) return out;
            bucketAccessCounter[d]++;
            long[] lefts = readNeighbors(docId, parentId, level, ensureDistR(d), tUid);
            for (long l : lefts) {
                long k = (l << 1) | 0L;
                if (!seenUidsDir.contains(k)) { seenUidsDir.add(k); out.add(new Neighbor(l, NeighborDir.L, d)); }
                if (out.size() >= K) return out;
            }
            if (out.size() >= K) return out;
        }
        return out;
    }

    /** T3-TR-3 HC-6：手动写 pair（测试用），若冲突抛 ISE。 */
    public void putPairForTest(long lUid, long rUid, long parentId, int level, int d) throws IOException {
        long pairKey = FarmHash64.mix4(lUid, rUid, parentId, (long)level);
        Long curD = distPairMap.get(pairKey);
        if (curD != null && curD.intValue() != d) throw new IllegalStateException("dist inconsistent (HC-6 test)");
        distPairMap.put(pairKey, (long)d);
    }

    /**
     * T3 测试用：对指定 (docId,parentId,level) 上下文的 uid 序列，全量展开两两对写入 DIST_BUCKET / PAIR_MAP / UID_RING。
     * 语义等同 ingestDoc Step4 DIST。避免 T2/TR4 受 Seg SPI（MockJieba/Seg5）切分影响，手动构造纯 uidSeq 精确控制。
     */
    public IngestStat buildDistForSeq(long docId, long parentId, int level, List<Long> uidSeq) throws IOException {
        if (uidSeq == null) uidSeq = new ArrayList<>();
        IngestStat stat = new IngestStat(docId);
        int n = uidSeq.size();
        stat.rowsPerLevel[level] = n;
        long[] uids = new long[n];
        for (int k = 0; k < n; k++) uids[k] = uidSeq.get(k).longValue();
        for (int i = 0; i < n; i++) {
            long lUid = uids[i];
            for (int j = i + 1; j < n; j++) {
                long rUid = uids[j];
                int d = Math.min(j - i, DIST_MAX);
                long base = FarmHash64.mix4(lUid, rUid, parentId, (long)level);
                long pairKey = FarmHash64.mix4(base, (long)i, (long)j, 0xC0FFEEL);
                Long curD = distPairMap.get(pairKey);
                if (curD != null && curD.intValue() != d)
                    throw new IllegalStateException("dist inconsistent (HC-6 buildDistForSeq): old="+curD.intValue()+" new="+d);
                if (curD == null) distPairMap.put(pairKey, (long)d);
                appendNeighbor(d, docId, parentId, level, ensureDistL(d), lUid, rUid);
                appendNeighbor(d, docId, parentId, level, ensureDistR(d), rUid, lUid);
                uidRing(lUid).offerUnique(d);
                uidRing(rUid).offerUnique(d);
                stat.pairsTotal++;
            }
        }
        updateWeightForLevel(docId, parentId, level, uidSeq);
        return stat;
    }

    // ---------------- T4 putDoc 覆盖写旧清理 ----------------

    /** 从 distL[d]/distR[d] 的上下文邻居列表中移除 targetNeighborUid（上下文隔离 key）。 */
    private void removeNeighbor(int d, long docId, long parentId, int level, DsHashMap map, long srcUid, long targetNeighborUid) throws IOException {
        long ctxKey = FarmHash64.mix4(docId, parentId, (long)level, srcUid);
        Long bucketId = map.get(ctxKey);
        if (bucketId == null || bucketId.longValue() == 0L) return;
        byte[] h4 = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId.longValue(), 0, 4);
        int count = ByteBuffer.wrap(h4).getInt();
        if (count <= 0) return;
        byte[] body = distNeighborStore.get(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId.longValue(), 0, 4 + count * 8);
        ByteBuffer bb = ByteBuffer.wrap(body); bb.getInt();
        long[] cur = new long[count];
        for (int k = 0; k < count; k++) cur[k] = bb.getLong();
        long[] out = new long[count]; int on = 0;
        boolean removed = false;
        for (long x : cur) if (x == targetNeighborUid) removed = true; else out[on++] = x;
        if (!removed) return;
        distNeighborStore.remove(SPACE_FT, DIST_NEIGHBOR_TYPE, bucketId.longValue());
        if (on == 0) { map.remove(ctxKey); return; }
        long[] shrunk = new long[on]; System.arraycopy(out, 0, shrunk, 0, on);
        ByteBuffer nb = ByteBuffer.allocate(4 + on * 8);
        nb.putInt(on); for (long x : shrunk) nb.putLong(x);
        long newBucketId = distNeighborStore.put(SPACE_FT, DIST_NEIGHBOR_TYPE, nb.array());
        map.put(ctxKey, newBucketId);
    }

    /**
     * 清理 docId 所有内容：
     *   Step1：扫 IDX3(docId, level=0..5) 拿到所有 TuRow；按 (parentId, level) 分组；
     *   Step2：对每组还原 uids 顺序（按 seq 升序），枚举 i<j 对：
     *     a. PAIR_MAP remove(mix4(base, i, j, C0FFEE))
     *     b. DIST_BUCKET d=min(j-i,1024) 双向 removeNeighbor
     *     c. UID_RING shard remove d（若 d 仅剩该条目）
     *   Step3：删除所有 TuRow（3 IDX + PK map + bucket store）
     *   Step4：所有 unitId termDict release（跨 doc 共享引用计数）
     *
     * putDoc 覆盖写前必须调用 clearDoc，以保证旧内容 pair 全部移除，查询不会返回陈旧邻居。
     */
    public void clearDoc(long docId) throws IOException {
        if (docId <= 0L) throw new IllegalArgumentException("docId<=0");
        // Step1: collect all rows grouped by (parent, level)
        List<TuRow> allRows = new ArrayList<>();
        for (int lv = 0; lv < 6; lv++) allRows.addAll(selectByDocLevel(docId, lv));
        Map<Long, List<TuRow>> byGroup = new HashMap<>(); // key=mix2(parentId, level)
        for (TuRow r : allRows) {
            long k = FarmHash64.mix2(r.parentId, (long)r.level);
            byGroup.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        // Step2: 每组按 seq 升序，还原 uid 序列，枚举 i<j 反向清除 DIST_BUCKET/PAIR_MAP/UID_RING
        Set<Long> releasedUids = new HashSet<>();
        for (List<TuRow> group : byGroup.values()) {
            group.sort(Comparator.comparingInt(r -> r.seq));
            if (group.size() < 2) continue;
            long parentId = group.get(0).parentId;
            int level = group.get(0).level;
            long[] uids = new long[group.size()];
            for (int k = 0; k < group.size(); k++) uids[k] = group.get(k).unit_id();
            for (int i = 0; i < uids.length; i++) {
                long lUid = uids[i];
                for (int j = i + 1; j < uids.length; j++) {
                    long rUid = uids[j];
                    int d = Math.min(j - i, DIST_MAX);
                    long base = FarmHash64.mix4(lUid, rUid, parentId, (long)level);
                    long pairKey = FarmHash64.mix4(base, (long)i, (long)j, 0xC0FFEEL);
                    distPairMap.remove(pairKey);
                    removeNeighbor(d, docId, parentId, level, ensureDistL(d), lUid, rUid);
                    removeNeighbor(d, docId, parentId, level, ensureDistR(d), rUid, lUid);
                    uidRing(lUid).remove(d);
                    uidRing(rUid).remove(d);
                }
            }
        }
        // Step3: delete rows (3 IDX + pk map + bucket)
        for (TuRow r : allRows) deleteByPk(r.unit_id(), r.docId, r.parentId, r.level);
        // Step4: release uid refCount（去重避免多 release 同一 uid）
        for (TuRow r : allRows) releasedUids.add(r.unit_id());
        for (long uid : releasedUids) termDict.release(uid);
        // P22 T1 Step6 weightCleanup（不删 term_stat：全局 idf 下次 search 触发 recomputeIdfForTerms 重算）
        for (TuRow r : allRows) weightStore.removeTf(r.unit_id(), r.docId, r.parentId, r.level);
        weightStore.removeDocStat(docId);
        // P25 T1 Step7 positionCleanup（仅删 doc 级 positions，不删 termStat/vector/idf）
        for (TuRow r : allRows) weightStore.removePositions(r.unit_id(), r.docId, r.parentId, r.level);
    }
    /** P25 T1 HC probe: 读指定 term 的 positions（miss -> empty, zero null）。 */
    public List<PositionSpan> getTermPositionsForTest(long uid, long docId, long parentId, int level) throws IOException {
        if (uid <= 0L || docId <= 0L || parentId <= 0L || level < 0 || level > 5) return new ArrayList<>();
        return weightStore.getPositions(uid, docId, parentId, level);
    }
    /** P25 T2 Span 合并：先按 start 升序，相邻/重叠（c≤b）合并为 [a, max(b,d)]；不相邻（c>b）保留独立。null/非法抛 IAE。 */
    static List<PositionSpan> mergeSpans(List<PositionSpan> spans) {
        if (spans == null) throw new IllegalArgumentException("spans null");
        if (spans.isEmpty()) return new ArrayList<>();
        for (PositionSpan p : spans) {
            if (p == null || p.start < 0 || p.end <= p.start)
                throw new IllegalArgumentException("span illegal: " + p);
        }
        List<PositionSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(x -> x.start));
        List<PositionSpan> out = new ArrayList<>(sorted.size());
        int cs = sorted.get(0).start, ce = sorted.get(0).end;
        for (int i = 1; i < sorted.size(); i++) {
            PositionSpan p = sorted.get(i);
            if (p.start <= ce) { ce = Math.max(ce, p.end); } // 相邻/重叠合并
            else { out.add(new PositionSpan(cs, ce)); cs = p.start; ce = p.end; }
        }
        out.add(new PositionSpan(cs, ce));
        return out;
    }
    /** P25 T2 HC probe：mergeSpans public。 */
    public static List<PositionSpan> mergeSpansForTest(List<PositionSpan> spans) { return mergeSpans(spans); }

    // ================= P25 T3: Snippet / Facet / searchWithExtras（ZeroLeak 内嵌所有 helper probes，不增独立类）=================
    /** P25 T3 HC probe: Snippet 滑窗截取（rawDocText 非空时使用，spans 可以是未合并或已合并；o.snippetWindow / snippetMaxCount 生效）。 */
    public static List<SnippetHit> buildSnippetsForTest(String rawDocText, List<PositionSpan> spans, SearchOptions o) {
        if (o == null) o = SearchOptions.defaults();
        final int W = Math.max(2, o.snippetWindow);
        final int MAX = Math.max(1, o.snippetMaxCount);
        List<SnippetHit> out = new ArrayList<>();
        if (rawDocText == null || rawDocText.isEmpty() || spans == null || spans.isEmpty()) return out;
        final int docLen = rawDocText.length();
        List<PositionSpan> merged = mergeSpans(spans); // 先合并，避免 span 重叠导致重复 center
        // 为每个 span 生成 window 候选，按命中数 + Σ idf（这里按 span.end-span.length 近似）排序
        List<Object[]> candidates = new ArrayList<>(merged.size()); // [startInDoc, endInDoc, score, Set<Integer> docOffsetsCovered]
        for (PositionSpan sp : merged) {
            int center = (sp.start + sp.end) >>> 1;
            int half = W >>> 1;
            int s = Math.max(0, center - half);
            int e = Math.min(docLen, center + half);
            if (e <= s) continue;
            // 统计命中 span 在 [s,e] 内的数量和 score（简单加权：每个 span 命中为 Σ 1 + (end-start) 近似 term idf 权重）
            int hitCount = 0; double sc = 0.0;
            for (PositionSpan x : merged) {
                if (x.end <= s || x.start >= e) continue;
                hitCount++;
                sc += (x.end - x.start); // term length 近似 idf
            }
            // score 组合：hitCount 为主 + Σlen 次 + (-s/1e9) 按起始位置升序 tie-break
            double finalScore = hitCount * 1e6 + sc - (s / 1e9);
            candidates.add(new Object[]{ s, e, finalScore, sp });
        }
        candidates.sort((a,b) -> Double.compare((double)b[2], (double)a[2]));
        // 取前 MAX 条，并生成 relative highlight ranges（相对于 snippetText）
        int take = Math.min(MAX, candidates.size());
        for (int i = 0; i < take; i++) {
            Object[] c = candidates.get(i);
            int s = (int)c[0]; int e = (int)c[1]; double sc = (double)c[2];
            String snippetText = rawDocText.substring(s, e);
            List<PositionSpan> hr = new ArrayList<>();
            for (PositionSpan x : merged) {
                int rs = x.start - s; int re = x.end - s;
                if (re <= 0 || rs >= snippetText.length()) continue;
                int r1 = Math.max(0, rs);
                int r2 = Math.min(snippetText.length(), re);
                if (r2 <= r1) continue;
                hr.add(new PositionSpan(r1, r2));
            }
            // 高亮区间按 start 升序
            hr.sort(Comparator.comparingInt(x -> x.start));
            out.add(new SnippetHit(snippetText, s, e, sc, hr));
        }
        // 最终按 start 升序（稳定输出）
        out.sort(Comparator.comparingInt(x -> x.startInDoc));
        return out;
    }
    /** P25 T3 HC probe: Facet 排序（docCount↓ totalTf↓ termText 字典序↑）。公共方法测试稳定排序。 */
    public static List<FacetHit> sortFacetsForTest(List<FacetHit> facets, int facetTopK) {
        if (facets == null) return new ArrayList<>();
        List<FacetHit> list = new ArrayList<>(facets);
        list.sort((a,b) -> {
            if (a.docCount != b.docCount) return Long.compare(b.docCount, a.docCount);
            if (a.totalTf != b.totalTf) return Long.compare(b.totalTf, a.totalTf);
            return a.termText.compareTo(b.termText);
        });
        int limit = (facetTopK > 0) ? Math.min(facetTopK, list.size()) : list.size();
        return list.subList(0, limit);
    }
    /** P25 T3 HC probe: computeFacets（基于 IDX2 全局 + WeightStore.getTf 聚合）。level 指定期望 level（0=CHAR, 1=WORD...）。docIds null=全局。 */
    public List<FacetHit> computeFacetsForTest(List<Long> docIds, int level) throws IOException {
        return computeFacetsInternal(docIds, level);
    }
    private List<FacetHit> computeFacetsInternal(List<Long> docIds, int level) throws IOException {
        if (level < 0 || level > 5) return new ArrayList<>();
        Set<Long> docFilter = null;
        if (docIds != null && !docIds.isEmpty()) { docFilter = new HashSet<>(docIds); }
        // 从 termDict.toUid(text,false) 找不到所有已注册 uid 列表→先扫 IDX2 所有 TuRow 的 unit_id (level 过滤)
        // 简单 MVP：扫所有 doc 的所有 level rows；大数据后续优化
        Set<Long> allUids = new HashSet<>();
        Map<Long, Set<Long>> uidDocs = new HashMap<>();   // uid -> set of docIds
        Map<Long, Long>      uidTotalTf = new HashMap<>(); // uid -> Σ tf
        // 为了限定 level，我们对 termDict.allUids via docStat 的所有 doc 扫 TuRow
        Set<Long> docKeys = weightStore.docStatMap.keySet();
        for (Long docKey : docKeys) {
            long d = docKey.longValue();
            if (docFilter != null && !docFilter.contains(d)) continue;
            // 只扫指定 level
            List<TuRow> rows = selectByDocLevel(d, level);
            Set<Long> seenUidsInDoc = new HashSet<>();
            for (TuRow r : rows) {
                long u = r.unit_id();
                if (u <= 0L) continue;
                allUids.add(u);
                seenUidsInDoc.add(u);
                long tf = weightStore.getTf(u, d, r.parentId, level);
                if (tf <= 0L) tf = 1L;
                uidTotalTf.merge(u, tf, Long::sum);
            }
            for (Long u : seenUidsInDoc) uidDocs.computeIfAbsent(u, x -> new HashSet<>()).add(d);
        }
        List<FacetHit> out = new ArrayList<>(allUids.size());
        for (Long u : allUids) {
            long uid = u.longValue();
            Set<Long> docs = uidDocs.get(uid);
            long dc = (docs == null) ? 0L : docs.size();
            long tt = uidTotalTf.getOrDefault(uid, 0L);
            String txt = termDict.toText(uid);
            out.add(new FacetHit(uid, txt, level, dc, tt));
        }
        return out;
    }
    /** P25 T3 searchWithExtras 主 API 骨架：原 search + Extras（positions/snippets/facets）。K≤0 / q empty → 返回空。 */
    public List<ScoredDocHitWithExtras> searchWithExtras(String query, Map<Long,String> rawDocTextsByDocId, int K, SearchOptions options) throws IOException {
        if (options == null) options = SearchOptions.defaults();
        List<ScoredDocHitWithExtras> out = new ArrayList<>();
        if (K <= 0) return out;
        if (query == null || query.isEmpty()) return out;
        List<ScoredDocHit> cores = searchWithVectorForTest(query, null, K, options);
        if (cores == null || cores.isEmpty()) return out;
        // 1) facet（全局 IDX2，基于所有结果 docIds 子集亦可，此处走全局 level=facetLevel）
        List<Long> resDocIds = new ArrayList<>(cores.size());
        for (ScoredDocHit h : cores) resDocIds.add(h.docId);
        List<FacetHit> allFacetHits;
        if (options.charLevelFacet) {
            List<FacetHit> l0 = computeFacetsInternal(null, 0);
            List<FacetHit> l1 = computeFacetsInternal(null, options.facetLevel);
            allFacetHits = new ArrayList<>(l0.size() + l1.size());
            allFacetHits.addAll(sortFacetsForTest(l0, options.facetTopK));
            allFacetHits.addAll(sortFacetsForTest(l1, options.facetTopK));
        } else {
            allFacetHits = sortFacetsForTest(computeFacetsInternal(null, options.facetLevel), options.facetTopK);
        }
        for (ScoredDocHit core : cores) {
            long docId = core.docId;
            long parent = core.parentId;
            int lv = core.level;
            // 2) matchedSpans：qUids 对 (doc,parent,level) 的 positions 合并（options.returnPositions=false 跳过）
            List<PositionSpan> matched = new ArrayList<>();
            if (options.returnPositions) {
                Set<Long> qUidsSet = new HashSet<>();
                FulltextSegmenter seg = resolveSegmenter();
                int[] cutLevels = options.charLevelSearch ? new int[]{DS0_CHAR, DS1_WORD, DS2_PHRASE} : new int[]{DS1_WORD};
                for (int clv : cutLevels) {
                    Iterator<FulltextSegmenter.CutUnit> it = seg.cut(query, clv, FulltextSegmenter.SegOptions.defaults());
                    while (it.hasNext()) {
                        FulltextSegmenter.CutUnit cu = it.next();
                        if (cu == null || cu.text == null || cu.text.isEmpty()) continue;
                        long u = termDict.toUid(cu.text, false);
                        if (u <= 0L) continue;
                        qUidsSet.add(u);
                    }
                }
                for (Long uL : qUidsSet) {
                    List<PositionSpan> ps = weightStore.getPositions(uL.longValue(), docId, parent, lv);
                    matched.addAll(ps);
                }
            }
            List<PositionSpan> mergedSpans = matched.isEmpty() ? Collections.emptyList() : mergeSpans(matched);
            // 3) snippets：若 rawDocTextsByDocId != null 且 returnPositions=true → build
            List<SnippetHit> snippets = new ArrayList<>();
            if (rawDocTextsByDocId != null && !mergedSpans.isEmpty()) {
                String raw = rawDocTextsByDocId.get(docId);
                if (raw != null && !raw.isEmpty()) snippets = buildSnippetsForTest(raw, mergedSpans, options);
            }
            out.add(new ScoredDocHitWithExtras(core, mergedSpans, snippets, allFacetHits));
        }
        return out;
    }

    /** putDoc：覆盖写单文档（幂等）= clearDoc 旧内容 + ingestDoc 新内容。 */
    public IngestStat putDoc(long docId, String newText) throws IOException {
        clearDoc(docId);
        return ingestDoc(docId, newText == null ? "" : newText);
    }

    // ============ P22 T1 Weight / HC Probes + Ingest Step5（T3 search、T4 幂等、T5 Gatekeeper 复用） ============
    /** FR-6 HC 探针 1：读 docStat（6 level docLen + nTerms），miss 返 null。 */
    public WeightDocStatRow getDocStatForTest(long docId) throws IOException { return weightStore.getDocStat(docId); }
    /** FR-6 HC 探针 1b：全 docStat keySet（P24 T2 TR2.1/TR2.4 shard 交为空实锤）。 */
    public Set<Long> weightStoreAllDocKeysForTest() throws IOException {
        Set<Long> out = new HashSet<>();
        if (weightStore == null) return out;
        Set<Long> raw = weightStore.docStatMap.keySet();
        if (raw == null) return out;
        out.addAll(raw);
        return out;
    }
    /** P42 R4：枚举 termStat 全键集（uid 快照）。只读；P21 既有方法/内嵌类型 0 改（append-only 唯一例外）。 */
    public Set<Long> weightStoreAllTermKeys() throws IOException {
        Set<Long> out = new HashSet<>();
        if (weightStore == null) return out;
        Set<Long> raw = weightStore.termStatMap.keySet();
        if (raw == null) return out;
        out.addAll(raw);
        return out;
    }
    /** P42 R4：导出 tf 键→值全映射（key = mix4(uid,docId,parentId,level)）。只读，bucket 值在包内解出。 */
    public Map<Long, Integer> weightStoreAllTfEntries() throws IOException {
        Map<Long, Integer> out = new HashMap<>();
        if (weightStore == null) return out;
        Set<Long> keys = weightStore.termFreqInDocMap.keySet();
        if (keys == null) return out;
        for (Long key : keys) {
            if (key == null) continue;
            Long bucket = weightStore.termFreqInDocMap.get(key);
            if (bucket == null || bucket == 0L) continue;
            byte[] data = weightStore.readBucketVal(bucket);
            if (data == null || data.length < 8) continue;
            out.put(key, ByteBuffer.wrap(data).getInt(4));
        }
        return out;
    }
    // ============ P43 T1-T4 搜索读路径切换（Slice B）：分层倒排视图挂载 + stat 列视图优先（R4/R7/R8） ============
    /**
     * P43 R4：挂载分层倒排只读视图。挂载后，搜索读路径的 stat 列点查（idf / tf / docLen）**视图优先**， 视图
     * miss（{@code hasTerm}/{@code hasDoc} 为 false）自动**回退
     * WeightStore**，因此对同源快照视图 输出与未挂载时 bit 级全等（R7）。
     *
     * <p>
     * 纯状态位（O(1)）：不触发导出 / 重建 / 任何扫描（R10）；非线程安全（单写者语义）。
     *
     * @param view 分层倒排只读视图，非 null
     * @throws IllegalArgumentException view 为 null，或已挂载（须先
     * {@link #detachLayeredView()}
     */
    public void attachLayeredView(LayeredInvertedView view) {
        if (view == null) {
            throw new IllegalArgumentException("layered view null");
        }
        if (this.layeredView != null) {
            throw new IllegalArgumentException("layered view already attached");
        }
        this.layeredView = view;
    }

    /**
     * P43 R4：卸载视图（幂等；未挂载时 0 副作用）→ 读路径 100% 回退 WeightStore（默认态）。
     */
    public void detachLayeredView() {
        this.layeredView = null;
    }

    /**
     * P43 R4：是否已挂载视图（只读状态位）。
     */
    public boolean hasLayeredView() {
        return this.layeredView != null;
    }

    /**
     * P43 R8：docLen 视图优先（视图覆盖该 doc → 段 docStat.docLen[level]），否则回退
     * WeightStore（miss → 0）。
     */
    private int viewDocLen(long docId, int level) throws IOException {
        LayeredInvertedView v = this.layeredView;
        if (v != null && v.hasDoc(docId)) {
            return v.docLen(docId, level);
        }
        WeightDocStatRow ds = weightStore.getDocStat(docId);
        return (ds == null) ? 0 : ds.docLen[level];
    }

    /**
     * P43 R8：idf 视图优先（视图覆盖该 term → 段 termStat.idf），否则回退 WeightStore（miss →
     * 0.0）。
     */
    private double viewIdf(long uid) throws IOException {
        LayeredInvertedView v = this.layeredView;
        if (v != null && v.hasTerm(uid)) {
            return v.idf(uid);
        }
        WeightTermStatRow ts = weightStore.getTermStat(uid);
        return (ts == null) ? 0.0 : ts.idf;
    }

    /**
     * P43 R8：tf 视图优先（视图覆盖该 doc → 段 tf 键 mix4 点查），否则回退 WeightStore（miss → 0）。
     */
    private int viewTf(long uid, long docId, long parentId, int level) throws IOException {
        LayeredInvertedView v = this.layeredView;
        if (v != null && v.hasDoc(docId)) {
            return v.tf(uid, docId, parentId, level);
        }
        return weightStore.getTf(uid, docId, parentId, level);
    }

    /**
     * P43 R8：视图是否覆盖该 term（Step0 idf 缺失判定用；无视图 → false）。
     */
    private boolean viewHasTerm(long uid) {
        LayeredInvertedView v = this.layeredView;
        return v != null && v.hasTerm(uid);
    }
    // ============ P43 增量区结束 ============

    /** FR-6 HC 探针 2：读 termStat（idf + payload），miss 返 null。 */
    public WeightTermStatRow getTermStatForTest(long uid) throws IOException { return weightStore.getTermStat(uid); }
    /** FR-6 HC 探针 3：读 tf（(uid,doc,p,lv) 下出现次数），miss 返 0。 */
    public int getTfForTest(long uid, long docId, long parentId, int level) throws IOException { return weightStore.getTf(uid, docId, parentId, level); }
    /** P25 T4 HC probe：termDict.toUid 快捷包装（与 putDoc 行为完全一致：createIfAbsent=true 时自动写 TermDict）。 */
    public long termDictToUidForTest(String text, boolean createIfAbsent) throws IOException { return termDict.toUid(text, createIfAbsent); }
    /** P25 T4 HC probe：term refCount（DsString.refCount，miss/<=0 返 0）。 */
    public int termRefCountForTest(long uid) throws IOException { return termDict.refCount(uid); }
    /** P25 T4 HC probe：手动写 term vector（与 P23 indexTermVectorForTest 公开探针等价：写 WeightStore.putTermVector + HNSW insertNode）。 */
    public void indexTermVectorForTest(long uid, float[] vec) throws IOException { weightStore.putTermVector(uid, vec); }
    /** P25 T4 HC probe：HNSW 向量维度 = WeightStore payload 头 4 字节 int。若从未 index vector 默认写死 16（MVP 统一基线：P23/P24 VEC_DIM=16）。 */
    public int hnswDimForTest() { return 16; }
    /** P25 T4 HC probe：getTermStatRow（包装 getTermStat 省略 lv，termStat 只按 uid 存储，lv 独立于 term）。 */
    public WeightTermStatRow getTermStatRowForTest(long uid, int level) throws IOException { return weightStore.getTermStat(uid); }
    /** P25 T4 HC probe：docStatRow get 快捷包装（docStat 按 docId 存，内部 6 level 数组；parent/level 参数仅留签名兼容，实际读 r.docLen[level]/r.nTerms[level] 时自行使用）。 */
    public WeightDocStatRow getDocStatRowForTest(long docId, long parentId, int level) throws IOException { return weightStore.getDocStat(docId); }
    /** T2 public static HC 探针 4：BM25 Okapi 标准单 term 精确公式（1e-9），不涉及 state。 */
    public static double bm25ScoreForTest(double tf, double idf, double docLen, double avgdl, double k1, double b) {
        if (tf <= 0.0 || idf <= 0.0) return 0.0;
        double denominatorPart;
        if (avgdl <= 0.0) denominatorPart = 1.0 - b + b * docLen; // avgdl 空 fallback 不除零
        else denominatorPart = (1.0 - b) + b * (docLen / avgdl);
        double denom = tf + k1 * denominatorPart;
        if (denom <= 0.0) return 0.0;
        double numer = tf * (k1 + 1.0);
        return idf * (numer / denom);
    }
    // ================= P23 T1 HC probes（ZeroLeak3 内嵌 static/instance，不新增独立类） =================
    /** HC probe：cosine 相似度，length 不等或 null → IAE；任一 norm=0 → 0.0。T1 先 stub，T2 实装 1e-6。 */
    public static double cosineSimForTest(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length)
            throw new IllegalArgumentException("cosine: length mismatch");
        if (a.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            double va = a[i], vb = b[i];
            dot += va * vb; na += va * va; nb += vb * vb;
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
    /** HC probe：L2 欧氏距离，length 不等或 null → IAE。 */
    public static double l2DistForTest(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length)
            throw new IllegalArgumentException("l2: length mismatch");
        double s = 0.0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return Math.sqrt(s);
    }
    /** HC probe：读 term vector（null 或 float[]），miss 返 null。 */
    public float[] getTermVectorForTest(long uid) throws IOException { return weightStore.getTermVector(uid); }
    /** HC probe：读 HNSW meta（M=16, maxLevel=16, entryPointUid, nodeCount）。 */
    public WeightStore.HnswMetaForTest getHnswMetaForTest() throws IOException { return weightStore.hnswStore.getMeta(); }
    /** Public API：indexTermVector(uid, vec) 幂等，保留原 idf。T1 即对外。 */
    public void indexTermVector(long uid, float[] vec) throws IOException { weightStore.putTermVector(uid, vec); }
    /** HC probe：knnSearchForTest（qUid 必须有 vec -> topK HnswHitForTest，efSearch<max(M,K) upcast。 */
    public java.util.List<WeightStore.HnswHitForTest> knnSearchForTest(long qUid, int K, int efSearch) throws IOException {
        return weightStore.hnswStore.knnSearch(qUid, K, efSearch);
    }
    /** T3 computeSortScore：按 Ranker 返回单 term 文档内得分（全 static，无状态）。 */
    static double computeSortScore(Ranker r, double tf, double idf, double docLen, double avgdl, SearchOptions opts) {
        if (r == null) r = Ranker.BM25;
        if (opts == null) opts = SearchOptions.defaults();
        switch (r) {
            case BOOL:
                return (tf > 0.0) ? 1.0 : 0.0;
            case TF:
                return Math.max(0.0, tf);
            case TFIDF:
                if (tf <= 0.0 || idf <= 0.0) return 0.0;
                return tf * idf;
            case BM25:
            default:
                return bm25ScoreForTest(tf, idf, docLen, avgdl, opts.k1, opts.b);
        }
    }
    /** RRF 公式（rrfK 默认 60）：1/(rrfK + rank)，rank 从 1 开始，rank<=0 → 0。public static HC 探针（FR-6 #5）。 */
    public static double rrfRankToScore(int rank, int rrfK) {
        if (rank <= 0) return 0.0;
        if (rrfK <= 0) rrfK = 60;
        return 1.0 / (rrfK + rank);
    }
    /** HC probe：min-max 归一（空返回原值；min=max 全给 0.0，避免 NaN）。public 便于全局 RRF 手算。 */
    public static java.util.Map<Long,Double> minMaxNormalize(java.util.Map<Long,Double> raw, boolean higherBetter) {
        if (raw == null || raw.isEmpty()) return new java.util.HashMap<>();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : raw.values()) { if (v < min) min = v; if (v > max) max = v; }
        java.util.Map<Long,Double> out = new java.util.HashMap<>(raw.size());
        if (max - min < 1e-300) { for (Long k : raw.keySet()) out.put(k, 0.0); return out; }
        for (java.util.Map.Entry<Long,Double> e : raw.entrySet()) {
            double n = (e.getValue() - min) / (max - min);
            out.put(e.getKey(), higherBetter ? n : 1.0 - n);
        }
        return out;
    }
    /** phrase 连续词命中率：查 doc 的 WORD level seq（IDX1 按 seq 排序），匹配 query uids 中出现距离 ≤ window 的连续长度 k 累加 k/qUids.size()。 */
    double computePhraseBoost(long docId, long parentId, int level, List<Long> qUids, int window) throws IOException {
        if (qUids == null || qUids.isEmpty()) return 0.0;
        List<TuRow> rows = selectByParentLevel(docId, parentId, level); // IDX1: (doc,parent,level,seq) ASC
        if (rows == null || rows.isEmpty()) return 0.0;
        List<Long> docSeq = new ArrayList<>(rows.size());
        for (TuRow r : rows) docSeq.add(r.unit_id());
        int win = (window <= 0) ? 1 : window;
        // 统计 qUids 中每对相邻 qi, qi+1，在 docSeq 中位置差 ≤ win 的命中对数
        int adjacentHits = 0;
        int possibleAdj = qUids.size() - 1;
        if (possibleAdj <= 0) possibleAdj = 1;
        for (int i = 0; i + 1 < qUids.size(); i++) {
            long a = qUids.get(i), b = qUids.get(i + 1);
            // 找 docSeq 所有 a 的位置 pa、b 的位置 pb，最小 |pa-pb| ≤ win
            int best = Integer.MAX_VALUE;
            int lastA = -1, lastB = -1;
            for (int p = 0; p < docSeq.size(); p++) {
                long u = docSeq.get(p);
                if (u == a) lastA = p;
                if (u == b) lastB = p;
                if (lastA >= 0 && lastB >= 0) {
                    int d = Math.abs(lastA - lastB);
                    if (d < best) best = d;
                }
            }
            if (best <= win) adjacentHits++;
        }
        return (double) adjacentHits / (double) possibleAdj;
    }
    /** 邻居召回（不污染 bucketAccessCounter — 通过 IDX2 + IDX1 组合扫每个文档内 seq 邻居）。
     *  对 uid 出现过的每 (doc,p,lv,seq)，在该 doc/p/lv 下扫 IDX1，找 |seq_other - seq| ≤ maxD 的其它 term uid 加入返回集合（distinct，包含自身）。 */
    java.util.Set<Long> neighborsWithinD(long uid, int maxD) throws IOException {
        java.util.Set<Long> out = new HashSet<>();
        if (uid <= 0L) return out;
        out.add(uid); // 自身 always 邻居 d=0
        if (maxD <= 0) return out;
        List<TuRow> meRows = selectByUnit(uid); // IDX2：uid 所有出现位置
        if (meRows == null || meRows.isEmpty()) return out;
        // 按 docId,parentId,level 分组，每组扫该 (doc,p,lv) 下 IDX1 全量 seq
        java.util.Map<Long, List<TuRow>> byKey = new HashMap<>();
        for (TuRow r : meRows) {
            long key = FarmHash64.mix4(r.docId, r.parentId, (long)r.level, 0xABCDEF01L);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<Long, List<TuRow>> e : byKey.entrySet()) {
            TuRow rep = e.getValue().get(0);
            long docId = rep.docId, parentId = rep.parentId;
            int level = rep.level;
            List<TuRow> docRows = selectByParentLevel(docId, parentId, level); // IDX1 (doc,p,lv,seq ASC)
            if (docRows == null || docRows.isEmpty()) continue;
            // 找出 docRows 中属于 uid 的 seq 集合
            java.util.Set<Integer> meSeqSet = new HashSet<>();
            for (TuRow r : e.getValue()) if (r.unit_id() == uid) meSeqSet.add(r.seq);
            // 滑窗：对每个 docRow，如果在任何 meSeq 距离内 → uid 邻居
            // O(N)：docRows 按 seq ASC，双指针
            int left = 0;
            for (int right = 0; right < docRows.size(); right++) {
                TuRow rr = docRows.get(right);
                while (left < right && rr.seq - docRows.get(left).seq > maxD) left++;
                // [left..right] 内 seq 距离 ≤ maxD，检查是否包含 meSeq 任一
                boolean hasMe = false;
                for (int k = left; k <= right; k++) {
                    if (meSeqSet.contains(docRows.get(k).seq)) { hasMe = true; break; }
                }
                if (hasMe) out.add(rr.unit_id());
            }
        }
        return out;
    }
    /** 扫描平均文档长度（docLen 平均）：用于 BM25 avgdl（仅 WORD 层；没有文档 → 8.0 fallback）。 */
    double computeAvgDocLen(int level) throws IOException {
        long N = 0L, sumLen = 0L;
        for (long dkey = 1; ; dkey++) {
            WeightDocStatRow s = weightStore.getDocStat(dkey);
            if (s == null) { if (N > 0 && dkey > N * 2 + 100) break; }
            else { N++; sumLen += s.docLen[level]; }
            if (dkey > 1_000_000L) break;
        }
        if (N <= 0L) return 8.0;
        return (double) sumLen / (double) N;
    }

    /** FR-2 ingestDoc 第 5 步：写入 weight（tf + docLen + nTerms）。幂等（覆盖写旧值）。
     *  T1 入口：buildDistForSeq / ingestDoc 各 (doc,p,lv) 完成后调用。 */
    void updateWeightForLevel(long docId, long parentId, int level, List<Long> uidSeq) throws IOException {
        if (level < 0 || level > 5) return;
        if (uidSeq == null) uidSeq = Collections.emptyList();
        // 1. tf count
        Map<Long,Integer> cnt = new HashMap<>();
        for (long u : uidSeq) cnt.merge(u, 1, Integer::sum);
        // 2. tf write（覆盖，幂等）
        for (Map.Entry<Long,Integer> e : cnt.entrySet()) weightStore.putTf(e.getKey(), docId, parentId, level, e.getValue());
        // 3. docStat（merge 其它 level 原值，不要覆盖）
        WeightDocStatRow row = weightStore.getDocStat(docId);
        if (row == null) row = new WeightDocStatRow();
        row.docLen[level] = uidSeq.size();
        row.nTerms[level] = cnt.size();
        weightStore.putDocStat(docId, row);
        // 4. termStat idf 不单独写（惰性；下次 search 或显式 rebuildIdf 时批量算）
    }
    /** T4 Step6 weightCleanup：按 (uid,doc,p,lv) 删除 tf + 删除 doc_stat（不删 term_stat，避免全局写放大）。 */
    void weightCleanupForDoc(long docId, List<TuRow> allRows) throws IOException {
        if (allRows == null) allRows = Collections.emptyList();
        for (TuRow r : allRows) weightStore.removeTf(r.unit_id(), r.docId, r.parentId, r.level);
        weightStore.removeDocStat(docId);
    }
    /** （可选）显式批量 rebuild IDF：从传入 uids 的 IDX2 关联 doc 估算总 N，对每个 uid 写 idf。 */
    public void recomputeIdfForTerms(List<Long> uids) throws IOException {
        if (uids == null || uids.isEmpty()) return;
        // Step1: 收集所有 uids 关联的 unique docId 集合（估算 docCount N，避免 1..1M 线性扫导致磁盘 I/O 爆）
        Set<Long> allDocIds = new HashSet<>();
        for (long uid : uids) {
            if (uid <= 0L) continue;
            List<TuRow> rows = selectByUnit(uid); // IDX2
            for (TuRow r : rows) allDocIds.add(r.docId);
        }
        // N = max(1, 相关 unique doc 数)；如果没 rows 则 fallback 1 不崩
        final long docCount = Math.max(1L, allDocIds.size());
        for (long uid : uids) {
            if (uid <= 0L) continue;
            List<TuRow> rows = selectByUnit(uid); // IDX2
            Set<Long> docs = new HashSet<>();
            for (TuRow r : rows) docs.add(r.docId);
            long df = docs.size(); if (df <= 0L) df = 1L;
            // Okapi idf standard
            double idf = Math.log((docCount - df + 0.5) / (df + 0.5) + 1.0);
            WeightTermStatRow old = weightStore.getTermStat(uid);
            byte[] payload = (old == null) ? new byte[0] : old.payload;
            weightStore.putTermStat(uid, new WeightTermStatRow(idf, payload));
        }
    }

    // ============ P22 T3 search 主 API（7 步完整实现；RRF 1e-12 + 短语 boost + 稳定排序 topK） ============
    /** FR-4 search(query, K, options)：Seg 切词 → searchCore（RRF 融合 7 步）。 */
    public List<ScoredDocHit> search(String queryText, int K, SearchOptions options) throws IOException {
        if (options == null) options = SearchOptions.defaults();
        if (K <= 0) return Collections.emptyList();
        if (queryText == null || queryText.isEmpty()) return Collections.emptyList();
        // ================= Step1: Seg SPI cut query（默认 WORD，charLevelSearch=true → CHAR+WORD+PHRASE 3 层） =================
        FulltextSegmenter seg = resolveSegmenter();
        Set<Long> qUidsSet = new LinkedHashSet<>();
        List<Long> qUidsOrder = new ArrayList<>();
        int[] cutLevels = options.charLevelSearch ? new int[]{DS0_CHAR, DS1_WORD, DS2_PHRASE} : new int[]{DS1_WORD};
        for (int lv : cutLevels) {
            Iterator<FulltextSegmenter.CutUnit> it = seg.cut(queryText, lv, FulltextSegmenter.SegOptions.defaults());
            while (it.hasNext()) {
                FulltextSegmenter.CutUnit cu = it.next();
                if (cu == null || cu.text == null || cu.text.isEmpty()) continue;
                long uid = termDict.toUid(cu.text, false);
                if (uid <= 0L) continue;
                if (!qUidsSet.contains(uid)) { qUidsSet.add(uid); qUidsOrder.add(uid); }
            }
        }
        if (qUidsOrder.isEmpty()) return Collections.emptyList();
        return searchCore(qUidsOrder, K, options);
    }
    /** T3 FR-6 HC 探针 5：直接以指定 qUids 调 searchCore（绕 Seg SPI，精确可控），public。 */
    public List<ScoredDocHit> searchByUidsForTest(List<Long> qUidsOrder, int K, SearchOptions options) throws IOException {
        if (options == null) options = SearchOptions.defaults();
        if (K <= 0 || qUidsOrder == null || qUidsOrder.isEmpty()) return Collections.emptyList();
        List<Long> clean = new ArrayList<>(qUidsOrder.size());
        LinkedHashSet<Long> d = new LinkedHashSet<>();
        for (Long u : qUidsOrder) if (u != null && u > 0L && d.add(u)) clean.add(u);
        if (clean.isEmpty()) return Collections.emptyList();
        return searchCore(clean, K, options);
    }
    /** FR-5 HC 公开探针：searchWithVectorForTest（向量三路 RRF 搜索；vectorRecall=false 回退 P22 searchCore 全等）。 */
    public List<ScoredDocHit> searchWithVectorForTest(String queryText, float[] qVec, int K, SearchOptions options) throws IOException {
        if (options == null) options = SearchOptions.defaults();
        if (K <= 0) return Collections.emptyList();
        if (queryText == null || queryText.isEmpty()) return Collections.emptyList();
        // Step1：Seg SPI cut（与 P22 search 完全一致）
        FulltextSegmenter seg = resolveSegmenter();
        Set<Long> qUidsSet = new LinkedHashSet<>();
        List<Long> qUidsOrder = new ArrayList<>();
        int[] cutLevels = options.charLevelSearch ? new int[]{DS0_CHAR, DS1_WORD, DS2_PHRASE} : new int[]{DS1_WORD};
        for (int lv : cutLevels) {
            Iterator<FulltextSegmenter.CutUnit> it = seg.cut(queryText, lv, FulltextSegmenter.SegOptions.defaults());
            while (it.hasNext()) {
                FulltextSegmenter.CutUnit cu = it.next();
                if (cu == null || cu.text == null || cu.text.isEmpty()) continue;
                long uid = termDict.toUid(cu.text, false);
                if (uid <= 0L) continue;
                if (!qUidsSet.contains(uid)) { qUidsSet.add(uid); qUidsOrder.add(uid); }
            }
        }
        if (qUidsOrder.isEmpty()) return Collections.emptyList();
        // 判定是否启用 R3：vectorRecall=true 且 有向量（qVec 非空 或 任一 qUid 有 vec）
        boolean recallVector = options.vectorRecall;
        if (recallVector) {
            boolean hasAnyVec = (qVec != null);
            if (!hasAnyVec) for (long u : qUidsOrder) { if (weightStore.getTermVector(u) != null) { hasAnyVec = true; break; } }
            recallVector = hasAnyVec;
        }
        // TR3.3：vectorRecall=false 或 没任何向量 → 直接走 P22 searchCore（finalScore 全等回退）
        if (!recallVector) return searchCore(qUidsOrder, K, options);
        // dim mismatch 校验：qVec 非空则与所有有 vec 的 term 维数全等
        if (qVec != null) {
            for (long u : qUidsOrder) {
                float[] tv = weightStore.getTermVector(u);
                if (tv != null && tv.length != qVec.length) {
                    throw new IllegalArgumentException("dim mismatch: qVec dim=" + qVec.length + " but term uid=" + u + " vec dim=" + tv.length);
                }
            }
        }
        return searchWithVectorCore(qUidsOrder, qVec, K, options);
    }
    /** searchCore（7 步 Step2~7，与 Step1 Seg 解耦；qUidsOrder 非空）。*/
    private List<ScoredDocHit> searchCore(List<Long> qUidsOrder, int K, SearchOptions options) throws IOException {
        final int LV_DEFAULT = DS1_WORD;
        Set<Long> qUidsSet = new LinkedHashSet<>(qUidsOrder);
        // FR-3 Step0：确保 qUids 有 termStat（idf）；没有就显式 recompute（I/O 线性 ≤10k doc 可接受）
        {
            List<Long> missing = new ArrayList<>();
            for (long u : qUidsOrder) {
                // P43 R7/R8：视图覆盖的 term 视为已有 idf，跳过 recompute（挂载态零重建）。
                if (!viewHasTerm(u) && weightStore.getTermStat(u) == null) missing.add(u);
            }
            if (!missing.isEmpty()) recomputeIdfForTerms(missing);
        }
        double avgdl = computeAvgDocLen(LV_DEFAULT);
        // idf override 支持
        java.util.Map<Long,Double> idfMap = new HashMap<>(qUidsOrder.size());
        for (long u : qUidsOrder) {
            Double override = (options.idfOverrideByUnitId == null) ? null : options.idfOverrideByUnitId.get(u);
            if (override != null) idfMap.put(u, override);
            else {
                // P43 R7/R8：idf 视图优先（挂载态读段 termStat；未挂载/miss 回退 WeightStore）。
                idfMap.put(u, viewIdf(u));
            }
        }
        // ================= Step2a: R1 byTerm 召回 doc（IDX2，真实 query term 命中） =================
        java.util.Map<Long,Integer> r1MatchedTerms = new HashMap<>();
        java.util.Set<Long> r1DocSet = new HashSet<>();
        // docId → parentId（按 (docId, LV) 扫 IDX3 取第一个 TuRow.parentId；fallback docId*10+LV）——所有打分、phraseBoost、tf 用这个动态 parent
        java.util.Map<Long,Long> docParent = new HashMap<>();
        // tfCache：key=mix4(uid, docId)，value=tf；因为 parentId 依赖 docId，不再包含 parentId（但内部 weightStore.getTf 仍要 docId+parentId+level）
        java.util.Map<Long,Integer> tfCache = new HashMap<>();
        for (long qUid : qUidsOrder) {
            List<TuRow> rows = selectByUnit(qUid); // IDX2
            if (rows == null || rows.isEmpty()) continue;
            java.util.Set<Long> docThisUid = new HashSet<>();
            for (TuRow r : rows) {
                if (r.level != LV_DEFAULT) continue;
                long docId = r.docId;
                // 填 docParent：从该 row 取 parentId（保证与 insert 时一致，不强制 docId*10+level）
                if (!docParent.containsKey(docId)) docParent.put(docId, r.parentId);
                if (!docThisUid.contains(docId)) {
                    docThisUid.add(docId);
                    r1MatchedTerms.merge(docId, 1, Integer::sum);
                }
                long cacheKey = FarmHash64.mix4(qUid, docId, 0xDEADL, 0xBEEFL);
                if (!tfCache.containsKey(cacheKey)) {
                    long pId = docParent.get(docId);
                    // P43 R7/R8：tf 视图优先（挂载态读段 tf 键 mix4 点查；未挂载/miss 回退 WeightStore）。
                    int tf = viewTf(qUid, docId, pId, LV_DEFAULT);
                    if (tf <= 0) tf = 1; // fallback：row 存在但 weightStore 无 tf → 至少 1
                    tfCache.put(cacheKey, tf);
                }
            }
            r1DocSet.addAll(docThisUid);
        }
        // ================= Step2b: R2 neighborRecall =================
        java.util.Set<Long> r2DocSet = new HashSet<>();
        for (long qUid : qUidsOrder) {
            java.util.Set<Long> neighbors = neighborsWithinD(qUid, Math.max(0, options.neighborRecallD));
            for (long nUid : neighbors) {
                if (qUidsSet.contains(nUid)) continue;
                List<TuRow> rows = selectByUnit(nUid);
                if (rows == null || rows.isEmpty()) continue;
                for (TuRow r : rows) {
                    if (r.level != LV_DEFAULT) continue;
                    r2DocSet.add(r.docId);
                    if (!docParent.containsKey(r.docId)) docParent.put(r.docId, r.parentId);
                }
            }
        }
        // 全集候选
        java.util.Set<Long> candDocSet = new HashSet<>(r1DocSet.size() + r2DocSet.size());
        candDocSet.addAll(r1DocSet); candDocSet.addAll(r2DocSet);
        if (candDocSet.isEmpty()) return Collections.emptyList();
        // ================= Step4: sortScore =================
        java.util.Map<Long,Double> sortScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
            // P43 R7/R8：docLen 视图优先（挂载态读段 docStat；未挂载/miss 回退 WeightStore）。
            double docLen = (double) viewDocLen(docId, LV_DEFAULT);
            double sum = 0.0;
            for (long qUid : qUidsOrder) {
                long cacheKey = FarmHash64.mix4(qUid, docId, 0xDEADL, 0xBEEFL);
                int tfVal;
                if (tfCache.containsKey(cacheKey)) tfVal = tfCache.get(cacheKey);
                else {
                    // P43 R7/R8：tf 视图优先（挂载态读段 tf 键 mix4 点查；未挂载/miss 回退 WeightStore）。
                    tfVal = viewTf(qUid, docId, pId, LV_DEFAULT);
                    if (tfVal <= 0 && selectByUnitHit(qUid, docId, LV_DEFAULT)) tfVal = 1;
                    tfCache.put(cacheKey, tfVal);
                }
                double idfVal = idfMap.getOrDefault(qUid, 0.0);
                sum += computeSortScore(options.ranker, (double) tfVal, idfVal, docLen, avgdl, options);
            }
            sortScores.put(docId, sum);
        }
        // ================= Step3: RRF 融合（R1+R2 排序 key 都是 sortScore） =================
        List<Long> r1List = new ArrayList<>(r1DocSet);
        java.util.Map<Long,Double> s1 = sortScores;
        r1List.sort((a,b) -> { int c = Double.compare(s1.getOrDefault(b, 0.0), s1.getOrDefault(a, 0.0)); return (c != 0) ? c : Long.compare(a,b); });
        java.util.Map<Long,Integer> r1Rank = new HashMap<>(r1List.size());
        for (int i = 0; i < r1List.size(); i++) r1Rank.put(r1List.get(i), i+1);
        List<Long> r2List = new ArrayList<>(r2DocSet);
        r2List.sort((a,b) -> { int c = Double.compare(s1.getOrDefault(b, 0.0), s1.getOrDefault(a, 0.0)); return (c != 0) ? c : Long.compare(a,b); });
        java.util.Map<Long,Integer> r2Rank = new HashMap<>(r2List.size());
        for (int i = 0; i < r2List.size(); i++) r2Rank.put(r2List.get(i), i+1);
        java.util.Map<Long,Double> rrfScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            double s = 0.0;
            Integer r1 = r1Rank.get(docId); if (r1 != null) s += rrfRankToScore(r1, options.rrfK);
            Integer r2 = r2Rank.get(docId); if (r2 != null) s += rrfRankToScore(r2, options.rrfK);
            rrfScores.put(docId, s);
        }
        // ================= Step5: phraseBoostScore（动态 parentId） =================
        java.util.Map<Long,Double> phraseScores = new HashMap<>(candDocSet.size());
        if (options.phraseBoost && qUidsOrder.size() >= 2) {
            for (long docId : candDocSet) {
                long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
                double p = computePhraseBoost(docId, pId, LV_DEFAULT, qUidsOrder, 1);
                phraseScores.put(docId, p * options.phraseBoostWeight);
            }
        } else for (long docId : candDocSet) phraseScores.put(docId, 0.0);
        // ================= Step6: 3 段 min-max 归一 + α/β/γ 加权 =================
        java.util.Map<Long,Double> sortNorm = minMaxNormalize(sortScores, true);
        java.util.Map<Long,Double> rrfNorm = minMaxNormalize(rrfScores, true);
        java.util.Map<Long,Double> phraseNorm = minMaxNormalize(phraseScores, true);
        double alpha = (options.alpha >= 0.0) ? options.alpha : 0.6;
        double beta  = (options.beta  >= 0.0) ? options.beta  : 0.3;
        double gamma = (options.gamma >= 0.0) ? options.gamma : 0.1;
        double sumW = alpha + beta + gamma;
        if (sumW <= 0.0) { alpha = 0.6; beta = 0.3; gamma = 0.1; sumW = 1.0; }
        boolean allZeroNorm = true;
        for (long docId : candDocSet) {
            if (Math.abs(sortNorm.getOrDefault(docId,0.0)) + Math.abs(rrfNorm.getOrDefault(docId,0.0)) + Math.abs(phraseNorm.getOrDefault(docId,0.0)) > 1e-300) { allZeroNorm = false; break; }
        }
        java.util.Map<Long,Double> finalScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            double f;
            if (allZeroNorm) f = (sortScores.getOrDefault(docId,0.0) + rrfScores.getOrDefault(docId,0.0) + phraseScores.getOrDefault(docId,0.0)) / Math.max(1.0, sumW);
            else f = (alpha * sortNorm.getOrDefault(docId,0.0) + beta * rrfNorm.getOrDefault(docId,0.0) + gamma * phraseNorm.getOrDefault(docId,0.0)) / sumW;
            finalScores.put(docId, f);
        }
        // ================= Step7: 稳定排序 topK =================
        List<Long> candList = new ArrayList<>(candDocSet);
        candList.sort((a,b) -> {
            int c = Double.compare(finalScores.getOrDefault(b, 0.0), finalScores.getOrDefault(a, 0.0));
            if (c != 0) return c;
            return Long.compare(a, b);
        });
        int limit = Math.min(K, candList.size());
        List<ScoredDocHit> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            long docId = candList.get(i);
            long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
            long unitIdHit = 0L;
            for (long u : qUidsOrder) {
                // P43 R7/R8：unitIdHit 的 tf 视图优先（与 sortScore 同口径，未挂载/miss 回退 WeightStore）。
                if (viewTf(u, docId, pId, LV_DEFAULT) > 0) { unitIdHit = u; break; }
            }
            if (unitIdHit == 0L) for (long u : qUidsOrder) { if (selectByUnitHit(u, docId, LV_DEFAULT)) { unitIdHit = u; break; } }
            out.add(new ScoredDocHit(docId, pId, LV_DEFAULT, 0, unitIdHit,
                    finalScores.getOrDefault(docId, 0.0),
                    sortScores.getOrDefault(docId, 0.0),
                    rrfScores.getOrDefault(docId, 0.0),
                    phraseScores.getOrDefault(docId, 0.0),
                    r1MatchedTerms.getOrDefault(docId, 0)));
        }
        return out;
    }
    /** searchWithVectorCore：P23 T3 三路 RRF + 第 4 段 δ vectorScore 加权（全量复用 P22 R1/R2/sortScore/phraseBoost，仅新增 R3 + 四段归一）。 */
    private List<ScoredDocHit> searchWithVectorCore(List<Long> qUidsOrder, float[] qVec, int K, SearchOptions options) throws IOException {
        final int LV_DEFAULT = DS1_WORD;
        Set<Long> qUidsSet = new LinkedHashSet<>(qUidsOrder);
        // Step0：recomputeIdf 缺省 idf（复用 P22 完全一致）
        {
            List<Long> missing = new ArrayList<>();
            for (long u : qUidsOrder) { if (weightStore.getTermStat(u) == null) missing.add(u); }
            if (!missing.isEmpty()) recomputeIdfForTerms(missing);
        }
        double avgdl = computeAvgDocLen(LV_DEFAULT);
        java.util.Map<Long,Double> idfMap = new HashMap<>(qUidsOrder.size());
        for (long u : qUidsOrder) {
            Double override = (options.idfOverrideByUnitId == null) ? null : options.idfOverrideByUnitId.get(u);
            if (override != null) idfMap.put(u, override);
            else {
                WeightTermStatRow ts = weightStore.getTermStat(u);
                idfMap.put(u, (ts == null) ? 0.0 : ts.idf);
            }
        }
        // ============ Step2a R1 byTerm 召回 ============
        java.util.Map<Long,Integer> r1MatchedTerms = new HashMap<>();
        java.util.Set<Long> r1DocSet = new HashSet<>();
        java.util.Map<Long,Long> docParent = new HashMap<>();
        java.util.Map<Long,Integer> tfCache = new HashMap<>();
        for (long qUid : qUidsOrder) {
            List<TuRow> rows = selectByUnit(qUid);
            if (rows == null || rows.isEmpty()) continue;
            java.util.Set<Long> docThisUid = new HashSet<>();
            for (TuRow r : rows) {
                if (r.level != LV_DEFAULT) continue;
                long docId = r.docId;
                if (!docParent.containsKey(docId)) docParent.put(docId, r.parentId);
                if (!docThisUid.contains(docId)) {
                    docThisUid.add(docId);
                    r1MatchedTerms.merge(docId, 1, Integer::sum);
                }
                long cacheKey = FarmHash64.mix4(qUid, docId, 0xDEADL, 0xBEEFL);
                if (!tfCache.containsKey(cacheKey)) {
                    long pId = docParent.get(docId);
                    int tf = weightStore.getTf(qUid, docId, pId, LV_DEFAULT);
                    if (tf <= 0) tf = 1;
                    tfCache.put(cacheKey, tf);
                }
            }
            r1DocSet.addAll(docThisUid);
        }
        // ============ Step2b R2 neighborsWithinD 召回 ============
        java.util.Set<Long> r2DocSet = new HashSet<>();
        for (long qUid : qUidsOrder) {
            java.util.Set<Long> neighbors = neighborsWithinD(qUid, Math.max(0, options.neighborRecallD));
            for (long nUid : neighbors) {
                if (qUidsSet.contains(nUid)) continue;
                List<TuRow> rows = selectByUnit(nUid);
                if (rows == null || rows.isEmpty()) continue;
                for (TuRow r : rows) {
                    if (r.level != LV_DEFAULT) continue;
                    r2DocSet.add(r.docId);
                    if (!docParent.containsKey(r.docId)) docParent.put(r.docId, r.parentId);
                }
            }
        }
        // ============ Step2c 【P23 新增】R3 HNSW KNN 召回 + vectorScore 累积 ============
        java.util.Set<Long> r3DocSet = new HashSet<>();
        java.util.Map<Long,Double> vectorScoreRaw = new HashMap<>(); // docId → Σ sim（累积）
        java.util.Map<Long,Integer> vectorHitCount = new HashMap<>(); // docId → 命中邻居数
        final int VTOP = Math.max(1, options.vectorTopK);
        final int VEF = Math.max(16, options.vectorEfSearch);
        final double sim = (options.vectorSim == Similarity.L2) ? -1.0 : 1.0; // L2 distance lower better → 1/(1+d)；cosine 1-d higher better
        // qVec 传入时，对 qVec 也做一次 knn（对所有 qUids 有 vec 的做 knn）
        for (long qUid : qUidsOrder) {
            float[] qTermVec = weightStore.getTermVector(qUid);
            float[] useVec = (qVec != null) ? qVec : qTermVec;
            if (useVec == null) continue;
            // 对 qUid 做 KNN（若 qUid 无 vec 但 qVec 有，HNSW 中其它节点仍能找到该 qUid，HnswStore.knnSearch 对未知 uid fallback 暴力扫）
            java.util.List<WeightStore.HnswHitForTest> hits = weightStore.hnswStore.knnSearch(qUid, VTOP, VEF);
            if (hits == null || hits.isEmpty()) continue;
            for (WeightStore.HnswHitForTest h : hits) {
                if (h == null || h.uid <= 0L) continue;
                // R3：邻居 uid 的 IDX2 docSet 加入
                List<TuRow> docRows = selectByUnit(h.uid);
                if (docRows == null || docRows.isEmpty()) continue;
                double oneSim;
                if (options.vectorSim == Similarity.COSINE) oneSim = 1.0 - h.distance; // HNSW 返回 cosine distance = 1 - cos，所以 sim = 1 - d
                else oneSim = 1.0 / (1.0 + h.distance);
                if (oneSim < 0.0) oneSim = 0.0;
                for (TuRow r : docRows) {
                    if (r.level != LV_DEFAULT) continue;
                    long docId = r.docId;
                    if (!docParent.containsKey(docId)) docParent.put(docId, r.parentId);
                    r3DocSet.add(docId);
                    vectorScoreRaw.merge(docId, oneSim, Double::sum);
                    vectorHitCount.merge(docId, 1, Integer::sum);
                }
            }
        }
        // 候选全集 = R1 ∪ R2 ∪ R3
        java.util.Set<Long> candDocSet = new HashSet<>(r1DocSet.size() + r2DocSet.size() + r3DocSet.size());
        candDocSet.addAll(r1DocSet); candDocSet.addAll(r2DocSet); candDocSet.addAll(r3DocSet);
        if (candDocSet.isEmpty()) return Collections.emptyList();
        // ============ Step4 sortScore（与 P22 完全一致，无改动） ============
        java.util.Map<Long,Double> sortScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
            WeightDocStatRow ds = weightStore.getDocStat(docId);
            double docLen = (ds == null) ? 0.0 : (double) ds.docLen[LV_DEFAULT];
            double sum = 0.0;
            for (long qUid : qUidsOrder) {
                long cacheKey = FarmHash64.mix4(qUid, docId, 0xDEADL, 0xBEEFL);
                int tfVal;
                if (tfCache.containsKey(cacheKey)) tfVal = tfCache.get(cacheKey);
                else {
                    tfVal = weightStore.getTf(qUid, docId, pId, LV_DEFAULT);
                    if (tfVal <= 0 && selectByUnitHit(qUid, docId, LV_DEFAULT)) tfVal = 1;
                    tfCache.put(cacheKey, tfVal);
                }
                double idfVal = idfMap.getOrDefault(qUid, 0.0);
                sum += computeSortScore(options.ranker, (double) tfVal, idfVal, docLen, avgdl, options);
            }
            sortScores.put(docId, sum);
        }
        // ============ Step3 【P23 扩展】RRF 三路 rank 融合（R1/R2/R3 1-based） ============
        // R1 rank
        List<Long> r1List = new ArrayList<>(r1DocSet);
        r1List.sort((a,b) -> { int c = Double.compare(sortScores.getOrDefault(b,0.0), sortScores.getOrDefault(a,0.0)); return (c!=0)?c:Long.compare(a,b); });
        java.util.Map<Long,Integer> r1Rank = new HashMap<>(r1List.size());
        for (int i = 0; i < r1List.size(); i++) r1Rank.put(r1List.get(i), i+1);
        // R2 rank
        List<Long> r2List = new ArrayList<>(r2DocSet);
        r2List.sort((a,b) -> { int c = Double.compare(sortScores.getOrDefault(b,0.0), sortScores.getOrDefault(a,0.0)); return (c!=0)?c:Long.compare(a,b); });
        java.util.Map<Long,Integer> r2Rank = new HashMap<>(r2List.size());
        for (int i = 0; i < r2List.size(); i++) r2Rank.put(r2List.get(i), i+1);
        // R3 rank（按 vectorScoreRaw 降序，如果 R3 是空集则写空 rank，不加 RRF 贡献）
        List<Long> r3List = new ArrayList<>(r3DocSet);
        final java.util.Map<Long,Double> fVS = vectorScoreRaw;
        r3List.sort((a,b) -> { int c = Double.compare(fVS.getOrDefault(b,0.0), fVS.getOrDefault(a,0.0)); return (c!=0)?c:Long.compare(a,b); });
        java.util.Map<Long,Integer> r3Rank = new HashMap<>(r3List.size());
        for (int i = 0; i < r3List.size(); i++) r3Rank.put(r3List.get(i), i+1);
        int rrfK = (options.rrfK > 0) ? options.rrfK : 60;
        java.util.Map<Long,Double> rrfScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            double s = 0.0;
            Integer r1 = r1Rank.get(docId); if (r1 != null) s += rrfRankToScore(r1, rrfK);
            Integer r2 = r2Rank.get(docId); if (r2 != null) s += rrfRankToScore(r2, rrfK);
            Integer r3 = r3Rank.get(docId); if (r3 != null) s += rrfRankToScore(r3, rrfK);
            rrfScores.put(docId, s);
        }
        // ============ Step5 phraseBoost（与 P22 完全一致） ============
        java.util.Map<Long,Double> phraseScores = new HashMap<>(candDocSet.size());
        if (options.phraseBoost && qUidsOrder.size() >= 2) {
            for (long docId : candDocSet) {
                long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
                double p = computePhraseBoost(docId, pId, LV_DEFAULT, qUidsOrder, 1);
                phraseScores.put(docId, p * options.phraseBoostWeight);
            }
        } else for (long docId : candDocSet) phraseScores.put(docId, 0.0);
        // ============ Step6 【P23 扩展】4 段 min-max 归一 + αβγδ 加权（δ=vectorScore 段） ============
        java.util.Map<Long,Double> sortNorm = minMaxNormalize(sortScores, true);
        java.util.Map<Long,Double> rrfNorm = minMaxNormalize(rrfScores, true);
        java.util.Map<Long,Double> phraseNorm = minMaxNormalize(phraseScores, true);
        // vectorScoreMap 构建：平均 sim（miss 0.0）
        java.util.Map<Long,Double> vectorScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            Double sum = vectorScoreRaw.get(docId); Integer cnt = vectorHitCount.get(docId);
            if (sum == null || cnt == null || cnt <= 0) vectorScores.put(docId, 0.0);
            else vectorScores.put(docId, sum / (double) cnt.intValue());
        }
        java.util.Map<Long,Double> vecNorm = minMaxNormalize(vectorScores, true);
        // 默认权重：α=0.5 β=0.25 γ=0.1 δ=0.15；若 opts.delta>=0 则用 delta
        double alpha = (options.alpha >= 0.0) ? options.alpha : 0.5;
        double beta  = (options.beta  >= 0.0) ? options.beta  : 0.25;
        double gamma = (options.gamma >= 0.0) ? options.gamma : 0.1;
        double delta = (options.delta >= 0.0) ? options.delta : 0.15;
        double sumW = alpha + beta + gamma + delta;
        if (sumW <= 0.0) { alpha = 0.5; beta = 0.25; gamma = 0.1; delta = 0.15; sumW = 1.0; }
        boolean allZeroNorm = true;
        for (long docId : candDocSet) {
            double s = Math.abs(sortNorm.getOrDefault(docId,0.0)) + Math.abs(rrfNorm.getOrDefault(docId,0.0))
                     + Math.abs(phraseNorm.getOrDefault(docId,0.0)) + Math.abs(vecNorm.getOrDefault(docId,0.0));
            if (s > 1e-300) { allZeroNorm = false; break; }
        }
        java.util.Map<Long,Double> finalScores = new HashMap<>(candDocSet.size());
        for (long docId : candDocSet) {
            double f;
            if (allZeroNorm) f = (sortScores.getOrDefault(docId,0.0) + rrfScores.getOrDefault(docId,0.0)
                                + phraseScores.getOrDefault(docId,0.0) + vectorScores.getOrDefault(docId,0.0)) / Math.max(1.0, sumW);
            else f = (alpha * sortNorm.getOrDefault(docId,0.0) + beta * rrfNorm.getOrDefault(docId,0.0)
                   + gamma * phraseNorm.getOrDefault(docId,0.0) + delta * vecNorm.getOrDefault(docId,0.0)) / sumW;
            finalScores.put(docId, f);
        }
        // ============ Step7：稳定排序 topK（finalScore↓ docId↑） ============
        List<Long> candList = new ArrayList<>(candDocSet);
        candList.sort((a,b) -> {
            int c = Double.compare(finalScores.getOrDefault(b, 0.0), finalScores.getOrDefault(a, 0.0));
            if (c != 0) return c;
            return Long.compare(a, b);
        });
        int limit = Math.min(K, candList.size());
        List<ScoredDocHit> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            long docId = candList.get(i);
            long pId = docParent.getOrDefault(docId, docId * 10L + LV_DEFAULT);
            long unitIdHit = 0L;
            for (long u : qUidsOrder) { if (weightStore.getTf(u, docId, pId, LV_DEFAULT) > 0) { unitIdHit = u; break; } }
            if (unitIdHit == 0L) for (long u : qUidsOrder) { if (selectByUnitHit(u, docId, LV_DEFAULT)) { unitIdHit = u; break; } }
            out.add(new ScoredDocHit(docId, pId, LV_DEFAULT, 0, unitIdHit,
                    finalScores.getOrDefault(docId, 0.0),
                    sortScores.getOrDefault(docId, 0.0),
                    rrfScores.getOrDefault(docId, 0.0),
                    phraseScores.getOrDefault(docId, 0.0),
                    r1MatchedTerms.getOrDefault(docId, 0),
                    vectorScores.getOrDefault(docId, 0.0)));
        }
        return out;
    }
    /** helper：selectByUnit(uid) 中是否存在指定 (docId, level) 的实例行。 */
    private boolean selectByUnitHit(long uid, long docId, int level) throws IOException {
        List<TuRow> rows = selectByUnit(uid);
        if (rows == null) return false;
        for (TuRow r : rows) if (r.docId == docId && r.level == level) return true;
        return false;
    }

    // ================= P24 T2/T3 ShardRouter：ZeroLeak3 内嵌类，不新增独立 .java =================
    /** Shard 间 transport 解耦接口（MVP 单机 LocalShardTransport，P26 接入 GRPC 时替换实现，不改路由算法）。 */
    public interface ShardTransport {
        IngestStat putDoc(int shardId, long docId, String text) throws IOException;
        void clearDoc(int shardId, long docId) throws IOException;
        List<ScoredDocHit> searchWithVector(int shardId, String q, float[] qVec, int K, SearchOptions o) throws IOException;
        void indexTermVector(int shardId, long uid, float[] vec) throws IOException;
        void close() throws IOException;

        default List<ScoredDocHit> search(int shardId, String q, int K, SearchOptions o) throws IOException {
            throw new UnsupportedOperationException("search(int,String,int,SearchOptions) Need ShardTransport impl override, P26+");
        }

        default List<ScoredDocHitWithExtras> searchWithExtras(int shardId, String q, java.util.Map<Long, String> rawDocTextsByDocId, int K, SearchOptions o) throws IOException {
            throw new UnsupportedOperationException("searchWithExtras(int,String,Map,int,SearchOptions) Need ShardTransport impl override, P26+");
        }

        default IngestStat putDocBatch(int shardId, java.util.Map<Long, String> docId2text) throws IOException {
            if (docId2text == null || docId2text.isEmpty()) {
                return IngestStat.zeroForTest();
            }
            IngestStat agg = null;
            for (java.util.Map.Entry<Long, String> e : docId2text.entrySet()) {
                IngestStat s = putDoc(shardId, e.getKey() == null ? 0L : e.getKey(), e.getValue());
                agg = (agg == null) ? s : agg.mergeWith(s);
            }
            return agg == null ? IngestStat.zeroForTest() : agg;
        }
    }
    /** LocalShardTransport：MVP 单机 mock，直接调用 shards[shardId] 实例。 */
    public static final class LocalShardTransport implements ShardTransport {
        private final FulltextStore[] shards;
        public LocalShardTransport(FulltextStore[] shards) { this.shards = shards; }
        @Override public IngestStat putDoc(int shardId, long docId, String text) throws IOException {
            return shards[shardId].putDoc(docId, text);
        }
        @Override public void clearDoc(int shardId, long docId) throws IOException {
            shards[shardId].clearDoc(docId);
        }
        @Override public List<ScoredDocHit> searchWithVector(int shardId, String q, float[] qVec, int K, SearchOptions o) throws IOException {
            return shards[shardId].searchWithVectorForTest(q, qVec, K, o);
        }
        @Override public void indexTermVector(int shardId, long uid, float[] vec) throws IOException {
            shards[shardId].indexTermVector(uid, vec);
        }
        @Override public void close() throws IOException { for (FulltextStore s : shards) try { s.close(); } catch (Exception ignore) {} }
        @Override public List<ScoredDocHit> search(int shardId, String q, int K, SearchOptions o) throws IOException {
            return shards[shardId].searchWithVectorForTest(q, null, K, o);
        }
        @Override public List<ScoredDocHitWithExtras> searchWithExtras(int shardId, String q, java.util.Map<Long, String> rawDocTextsByDocId, int K, SearchOptions o) throws IOException {
            return shards[shardId].searchWithExtras(q, rawDocTextsByDocId, K, o);
        }
    }
    /** ShardRouter：N 个 shard_NNN/ FulltextStore + 路由规则（UNIT/DOC/AUTO）+ scatter-gather 全局 RRF。 */
    public static final class ShardRouter implements AutoCloseable {
        private final int shardCount;
        private final File rootDir;
        private final FulltextStore[] shards;
        private final File[] shardDirs;
        private final ShardTransport transport;

        public ShardRouter(int shardCount, String rootDirBase) throws IOException {
            if (shardCount <= 0) throw new IllegalArgumentException("shardCount invalid: " + shardCount);
            if (rootDirBase == null || rootDirBase.isEmpty()) throw new IllegalArgumentException("rootDirBase empty");
            this.shardCount = shardCount;
            this.rootDir = new File(rootDirBase);
            if (!this.rootDir.exists()) this.rootDir.mkdirs();
            this.shards = new FulltextStore[shardCount];
            this.shardDirs = new File[shardCount];
            for (int s = 0; s < shardCount; s++) {
                File d = new File(this.rootDir, "shard_" + String.format("%04d", s));
                if (!d.exists()) d.mkdirs();
                this.shardDirs[s] = d;
                this.shards[s] = new FulltextStore(d.getAbsolutePath());
            }
            this.transport = new LocalShardTransport(this.shards);
        }
        /** P26+ overload：允许注入自定义 ShardTransport（例如 GrpcShardTransport）。旧 2 参数构造保持 Local 默认不变。 */
        public ShardRouter(int shardCount, String rootDirBase, java.util.function.BiFunction<java.io.File[], FulltextStore[], ShardTransport> transportFactory) throws IOException {
            if (shardCount <= 0) throw new IllegalArgumentException("shardCount invalid: " + shardCount);
            if (rootDirBase == null || rootDirBase.isEmpty()) throw new IllegalArgumentException("rootDirBase empty");
            if (transportFactory == null) throw new IllegalArgumentException("transportFactory cannot be null");
            this.shardCount = shardCount;
            this.rootDir = new File(rootDirBase);
            if (!this.rootDir.exists()) this.rootDir.mkdirs();
            this.shards = new FulltextStore[shardCount];
            this.shardDirs = new File[shardCount];
            for (int s = 0; s < shardCount; s++) {
                File d = new File(this.rootDir, "shard_" + String.format("%04d", s));
                if (!d.exists()) d.mkdirs();
                this.shardDirs[s] = d;
                this.shards[s] = new FulltextStore(d.getAbsolutePath());
            }
            ShardTransport t = transportFactory.apply(this.shardDirs, this.shards);
            if (t == null) throw new IllegalArgumentException("transportFactory.apply returned null");
            this.transport = t;
        }
        public int getShardCountForTest() { return shardCount; }
        public ShardTransport getShardTransportForTest() { return transport; }
        public FulltextStore getShardForTest(int s) { return shards[s]; }
        public File getShardRootDirForTest(int s) { return shardDirs[s]; }
        public ShardMode getShardModeForTest() { return ShardMode.AUTO; }
        /** toUid：与 P21 USER-HC-0 一致，所有 shard 的 termDict 同算法，uid 恒等，用 shard0 生成即可（uid 不绑定 shard）。 */
        public long toUid(String text, boolean createIfAbsent) throws IOException {
            return shards[0].termDict.toUid(text, createIfAbsent);
        }
        // ---------- 写路由（AUTO：DOC hash）----------
        public IngestStat putDocCluster(long docId, String text) throws IOException {
            int s = resolveShardForDocIdForTest(docId, shardCount);
            return transport.putDoc(s, docId, text);
        }
        public void clearDocCluster(long docId) throws IOException {
            int s = resolveShardForDocIdForTest(docId, shardCount);
            transport.clearDoc(s, docId);
        }
        // ---------- 单 shard 直接访问（Server-side handler 调用，transport 仅触达目标 shard）----------
        public IngestStat putDoc(int shardId, long docId, String text) throws IOException {
            return transport.putDoc(shardId, docId, text);
        }
        public void clearDoc(int shardId, long docId) throws IOException {
            transport.clearDoc(shardId, docId);
        }
        public void indexTermVector(int shardId, long uid, float[] vec) throws IOException {
            transport.indexTermVector(shardId, uid, vec);
        }
        public List<ScoredDocHit> searchWithVector(int shardId, float[] vec, int K) throws IOException {
            return transport.searchWithVector(shardId, "", vec, K, SearchOptions.defaults());
        }
        public List<ScoredDocHit> search(int shardId, String q, int K, SearchOptions o) throws IOException {
            return transport.search(shardId, q, K, o);
        }
        public List<ScoredDocHitWithExtras> searchWithExtras(int shardId, String q, Map<Long,String> rawDocTexts, int K, SearchOptions o) throws IOException {
            return transport.searchWithExtras(shardId, q, rawDocTexts, K, o);
        }
        /** indexTermVectorCluster：AUTO→UNIT hash（同 term 同 shard，HNSW 本地精确查询）。 */
        public void indexTermVectorCluster(long uid, float[] vec) throws IOException {
            int s = resolveShardForUnitIdForTest(uid, shardCount);
            transport.indexTermVector(s, uid, vec);
        }
        // ---------- Probes for Test ----------
        public WeightStore.HnswMetaForTest getHnswMetaForTest(int s) throws IOException { return shards[s].getHnswMetaForTest(); }
        public float[] getTermVectorForTest(int s, long uid) throws IOException { return shards[s].getTermVectorForTest(uid); }
        public int getTfForTest(int s, long uid, long docId, long parentId, int level) throws IOException { return shards[s].getTfForTest(uid, docId, parentId, level); }
        public WeightDocStatRow getDocStatForTest(int s, long docId) throws IOException { return shards[s].getDocStatForTest(docId); }
        public WeightTermStatRow getTermStatForTest(int s, long uid) throws IOException { return shards[s].getTermStatForTest(uid); }
        public java.util.Set<Long> weightStoreAllDocKeysForTest(int s) throws IOException { return shards[s].weightStoreAllDocKeysForTest(); }
        public long termDictToUidForTest(int s, String text, boolean createIfAbsent) throws IOException { return shards[s].termDict.toUid(text, createIfAbsent); }
        public int termDictRefCountForTest(int s, long uid) throws IOException { return shards[s].termDict.refCount(uid); }
        // ---------- internal gather: 三路全局 rank + RRF(k=60) Σ + 4 段 αβγδ 归一加权 + 三键稳定排序 ----------
        private List<ScoredDocHit> doGatherGlobalRrf(Map<Integer, List<ScoredDocHit>> shardLocal, int K, SearchOptions opt) {
            SearchOptions o = (opt != null) ? opt : SearchOptions.defaults();
            final int rrfK = Math.max(1, o.rrfK);
            // 1) 合并 raw 分数 by docId（同 docId 取最大 shard 内值 + 最早 shardId）
            Map<Long, Double> sortRaw = new HashMap<>();
            Map<Long, Double> rrfShardRaw = new HashMap<>();
            Map<Long, Double> vecRaw = new HashMap<>();
            Map<Long, Double> phraseRaw = new HashMap<>();
            Map<Long, Integer> mtRaw = new HashMap<>();
            Map<Long, Long> pidRaw = new HashMap<>();
            Map<Long, Long> uidRaw = new HashMap<>();
            Map<Long, Integer> sidRaw = new HashMap<>();
            Map<Long, Integer> lvRaw = new HashMap<>();
            Map<Long, Integer> seqRaw = new HashMap<>();
            Set<Long> docs = new LinkedHashSet<>();
            if (shardLocal != null) {
                for (Map.Entry<Integer, List<ScoredDocHit>> e : shardLocal.entrySet()) {
                    int sid = e.getKey();
                    List<ScoredDocHit> list = e.getValue();
                    if (list == null) continue;
                    for (ScoredDocHit h : list) {
                        if (h == null) continue;
                        long id = h.docId;
                        docs.add(id);
                        if (!sortRaw.containsKey(id) || h.sortScore > sortRaw.get(id)) {
                            sortRaw.put(id, h.sortScore);
                            rrfShardRaw.put(id, h.rrfScore);
                            vecRaw.put(id, h.vectorScore);
                            phraseRaw.put(id, h.phraseBoostScore);
                            mtRaw.put(id, h.matchedQueryTerms);
                            pidRaw.put(id, h.parentId);
                            uidRaw.put(id, h.unitId);
                            lvRaw.put(id, h.level);
                            seqRaw.put(id, h.seq);
                            sidRaw.put(id, (h.shardId >= 0) ? h.shardId : sid);
                        } else if (h.shardId >= 0 && (sidRaw.get(id) == null || sidRaw.get(id) == -1 || h.shardId < sidRaw.get(id))) {
                            sidRaw.put(id, h.shardId);
                        }
                    }
                }
            }
            if (docs.isEmpty()) return new ArrayList<>();

            // 2) 三路全局 rank（1-based；同分按 docId 升序保证 rank 确定性）
            List<Long> bySort = new ArrayList<>(docs);
            bySort.sort((a, b) -> { int c = Double.compare(sortRaw.getOrDefault(b, 0.0), sortRaw.getOrDefault(a, 0.0)); if (c != 0) return c; return Long.compare(a, b); });
            List<Long> byRrfS = new ArrayList<>(docs);
            byRrfS.sort((a, b) -> { int c = Double.compare(rrfShardRaw.getOrDefault(b, 0.0), rrfShardRaw.getOrDefault(a, 0.0)); if (c != 0) return c; return Long.compare(a, b); });
            List<Long> byVec = new ArrayList<>(docs);
            byVec.sort((a, b) -> { int c = Double.compare(vecRaw.getOrDefault(b, 0.0), vecRaw.getOrDefault(a, 0.0)); if (c != 0) return c; return Long.compare(a, b); });
            Map<Long, Integer> r1 = new HashMap<>(); for (int i = 0; i < bySort.size(); i++) r1.put(bySort.get(i), i + 1);
            Map<Long, Integer> r2 = new HashMap<>(); for (int i = 0; i < byRrfS.size(); i++) r2.put(byRrfS.get(i), i + 1);
            Map<Long, Integer> r3 = new HashMap<>(); for (int i = 0; i < byVec.size(); i++) r3.put(byVec.get(i), i + 1);

            // 3) 全局 RRF Σ 三路
            Map<Long, Double> globalRrf = new HashMap<>();
            for (Long id : docs) {
                double sum = FulltextStore.rrfRankToScore(r1.getOrDefault(id, 0), rrfK)
                           + FulltextStore.rrfRankToScore(r2.getOrDefault(id, 0), rrfK)
                           + FulltextStore.rrfRankToScore(r3.getOrDefault(id, 0), rrfK);
                globalRrf.put(id, sum);
            }

            // 4) 4 段 min-max 归一 + αβγδ 加权
            Map<Long, Double> sN = FulltextStore.minMaxNormalize(sortRaw, true);
            Map<Long, Double> rN = FulltextStore.minMaxNormalize(globalRrf, true);
            Map<Long, Double> pN = FulltextStore.minMaxNormalize(phraseRaw, true);
            Map<Long, Double> vN = FulltextStore.minMaxNormalize(vecRaw, true);
            double alpha = (o.alpha >= 0) ? o.alpha : 0.6;
            double beta  = (o.beta  >= 0) ? o.beta  : 0.3;
            double gamma = (o.gamma >= 0) ? o.gamma : 0.1;
            double delta = (o.delta >= 0) ? o.delta : 0.0;
            double sumW = alpha + beta + gamma + delta;
            boolean allZeroNorm = true;
            Z: for (Long id : docs) {
                if (Math.abs(sN.getOrDefault(id,0.0))>1e-300 || Math.abs(rN.getOrDefault(id,0.0))>1e-300 ||
                    Math.abs(pN.getOrDefault(id,0.0))>1e-300 || Math.abs(vN.getOrDefault(id,0.0))>1e-300) { allZeroNorm=false; break Z; }
            }
            Map<Long, Double> finalScores = new HashMap<>();
            for (Long id : docs) {
                double f;
                if (allZeroNorm || sumW <= 1e-300) {
                    f = (sortRaw.getOrDefault(id,0.0) + globalRrf.getOrDefault(id,0.0) + phraseRaw.getOrDefault(id,0.0) + vecRaw.getOrDefault(id,0.0)) / Math.max(1.0, 4.0);
                } else {
                    f = (alpha * sN.getOrDefault(id,0.0) + beta * rN.getOrDefault(id,0.0) + gamma * pN.getOrDefault(id,0.0) + delta * vN.getOrDefault(id,0.0)) / sumW;
                }
                finalScores.put(id, f);
            }

            // 5) 稳定排序：finalScore↓ → docId↑ → shardId↑
            List<Long> cand = new ArrayList<>(docs);
            cand.sort((a, b) -> {
                int c = Double.compare(finalScores.getOrDefault(b, 0.0), finalScores.getOrDefault(a, 0.0));
                if (c != 0) return c;
                int c2 = Long.compare(a, b);
                if (c2 != 0) return c2;
                return Integer.compare(sidRaw.getOrDefault(a, -1), sidRaw.getOrDefault(b, -1));
            });
            int limit = Math.max(0, Math.min(K, cand.size()));
            List<ScoredDocHit> out = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                long id = cand.get(i);
                out.add(new ScoredDocHit(id,
                        pidRaw.getOrDefault(id, id * 10L + 0),
                        lvRaw.getOrDefault(id, 0),
                        seqRaw.getOrDefault(id, 0),
                        uidRaw.getOrDefault(id, 0L),
                        finalScores.getOrDefault(id, 0.0),
                        sortRaw.getOrDefault(id, 0.0),
                        globalRrf.getOrDefault(id, 0.0),
                        phraseRaw.getOrDefault(id, 0.0),
                        mtRaw.getOrDefault(id, 0),
                        vecRaw.getOrDefault(id, 0.0),
                        sidRaw.getOrDefault(id, -1)));
            }
            return out;
        }

        // ---------- 读路径 searchCluster：scatter-gather 全局三路 RRF ----------
        public List<ScoredDocHit> searchCluster(String q, float[] qVec, int K, SearchOptions options) throws IOException {
            if (K <= 0) return new ArrayList<>();
            if ((q == null || q.isEmpty()) && qVec == null) return new ArrayList<>();
            SearchOptions o = (options != null) ? options : SearchOptions.defaults();
            int factor = Math.max(1, o.localTopKFactor);
            int localK = Math.max(1, K * factor);
            final Map<Integer, List<ScoredDocHit>> shardHits = new ConcurrentHashMap<>();
            if (o.concurrentScatter && shardCount > 1) {
                ExecutorService es = Executors.newFixedThreadPool(Math.min(shardCount, 8));
                try {
                    List<Callable<Void>> tasks = new ArrayList<>(shardCount);
                    for (int s = 0; s < shardCount; s++) {
                        final int sid = s;
                        tasks.add(() -> {
                            List<ScoredDocHit> local = transport.searchWithVector(sid, q, qVec, localK, o);
                            if (local != null && !local.isEmpty()) shardHits.put(sid, local);
                            return null;
                        });
                    }
                    List<Future<Void>> futures = es.invokeAll(tasks);
                    for (Future<Void> f : futures) f.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("concurrentScatter interrupted", ie);
                } catch (Exception e) {
                    if (e instanceof IOException) throw (IOException) e;
                    throw new IOException("concurrentScatter failure", e);
                } finally {
                    es.shutdownNow();
                }
            } else {
                for (int s = 0; s < shardCount; s++) {
                    List<ScoredDocHit> local = transport.searchWithVector(s, q, qVec, localK, o);
                    if (local == null || local.isEmpty()) continue;
                    shardHits.put(s, local);
                }
            }
            return doGatherGlobalRrf(shardHits, K, o);
        }

        /** P25 ShardRouter：searchClusterWithExtras（先 核心排序 再 per doc 重查 本地 positions + snippets + 合并全局 Facet，ZeroLeak3 内嵌不增独立类）。 */
        @SuppressWarnings("deprecation")
        public java.util.List<ScoredDocHitWithExtras> searchClusterWithExtras(String q, java.util.Map<Long,String> rawDocTextsByDocId, int K, SearchOptions options) throws IOException {
            java.util.List<ScoredDocHit> cores = searchCluster(q, null, K, options);
            java.util.List<ScoredDocHitWithExtras> out = new java.util.ArrayList<>(cores.size());
            java.util.Map<Long,Integer> docShard = new java.util.HashMap<>();
            for (ScoredDocHit h : cores) {
                int sid = (h.shardId >= 0) ? h.shardId : resolveShardForDocIdForTest(h.docId, shardCount);
                docShard.put(h.docId, sid);
            }
            SearchOptions o = (options != null) ? options : SearchOptions.defaults();
            if (cores.isEmpty()) {
                for (ScoredDocHit h : cores) out.add(new ScoredDocHitWithExtras(h, java.util.Collections.emptyList(), java.util.Collections.emptyList(), java.util.Collections.emptyList()));
                return out;
            }
            // P26: If transport is remote (not LocalShardTransport), scatter searchWithExtras per shard,
            // then merge global facets / per-doc spans / snippets from shard-level transport calls.
            final boolean remoteMode = !(transport instanceof LocalShardTransport);
            // ------- Step A: Collect per-shard ScoredDocHitWithExtras via transport if remote -------
            java.util.Map<Long, java.util.List<PositionSpan>> remoteSpans = new java.util.HashMap<>();
            java.util.Map<Long, java.util.List<SnippetHit>> remoteSnippets = new java.util.HashMap<>();
            java.util.Map<Long, Long> uidDc = new java.util.HashMap<>();
            java.util.Map<Long, Long> uidTt = new java.util.HashMap<>();
            java.util.Set<Long> seenUids = new java.util.HashSet<>();
            int fLv = Math.max(0, Math.min(5, o.facetLevel));
            boolean fLv2 = o.charLevelFacet;
            int[] levels = (fLv2 && fLv != 0) ? new int[]{0, fLv} : (fLv2 ? new int[]{0,1} : new int[]{fLv});
            if (remoteMode) {
                java.util.Set<Long> coreDocIds = new java.util.HashSet<>();
                for (ScoredDocHit h : cores) coreDocIds.add(h.docId);
                int localK = Math.max(K, cores.size() * 2);
                for (int s = 0; s < shardCount; s++) {
                    java.util.List<ScoredDocHitWithExtras> shardExtras = transport.searchWithExtras(s, q, rawDocTextsByDocId, localK, o);
                    if (shardExtras == null) continue;
                    for (ScoredDocHitWithExtras sh : shardExtras) {
                        if (sh == null || sh.core == null) continue;
                        long did = sh.core.docId;
                        if (!coreDocIds.contains(did)) continue;
                        if (sh.matchedSpans != null && !sh.matchedSpans.isEmpty()) {
                            remoteSpans.computeIfAbsent(did, x -> new java.util.ArrayList<>()).addAll(sh.matchedSpans);
                        }
                        if (sh.snippets != null && !sh.snippets.isEmpty()) {
                            remoteSnippets.computeIfAbsent(did, x -> new java.util.ArrayList<>()).addAll(sh.snippets);
                        }
                        if (sh.topFacets != null) {
                            for (FacetHit fh : sh.topFacets) {
                                if (fh == null) continue;
                                seenUids.add(fh.unitId);
                                uidDc.merge(fh.unitId, fh.docCount, Long::sum);
                                uidTt.merge(fh.unitId, fh.totalTf, Long::sum);
                            }
                        }
                    }
                }
            } else {
                java.util.Set<Long> allDocIds = new java.util.HashSet<>();
                for (ScoredDocHit h : cores) allDocIds.add(h.docId);
                java.util.List<Long> docIdList = new java.util.ArrayList<>(allDocIds);
                for (int lv : levels) {
                    for (int s = 0; s < shardCount; s++) {
                        java.util.List<FacetHit> perShard = shards[s].computeFacetsForTest(docIdList, lv);
                        if (perShard == null) continue;
                        for (FacetHit fh : perShard) {
                            if (fh == null) continue;
                            seenUids.add(fh.unitId);
                            uidDc.merge(fh.unitId, fh.docCount, Long::sum);
                            uidTt.merge(fh.unitId, fh.totalTf, Long::sum);
                        }
                    }
                }
            }
            java.util.List<FacetHit> allF = new java.util.ArrayList<>();
            for (Long uid : seenUids) {
                long uidL = uid.longValue();
                String txt = null;
                if (!remoteMode) {
                    for (int s = 0; s < shardCount && txt == null; s++) {
                        try { txt = shards[s].termDict.toText(uidL); } catch (Exception ignore) {}
                    }
                    if (txt == null) { try { txt = shards[0].termDict.toText(uidL); } catch (Exception ignore) { txt = ""; } }
                } else {
                    // Remote mode: best effort try shard[0] as probe; keep "" if unavailable
                    try { if (shards != null && shards.length > 0 && shards[0] != null) txt = shards[0].termDict.toText(uidL); } catch (Exception ignore) {}
                    if (txt == null) txt = "";
                }
                allF.add(new FacetHit(uidL, (txt == null) ? "" : txt, fLv, uidDc.getOrDefault(uidL, 0L), uidTt.getOrDefault(uidL, 0L)));
            }
            java.util.List<FacetHit> globalTop = sortFacetsForTest(allF, o.facetTopK);
            for (ScoredDocHit core : cores) {
                java.util.List<PositionSpan> matchedSpans = new java.util.ArrayList<>();
                java.util.List<SnippetHit> snippets = new java.util.ArrayList<>();
                long d = core.docId;
                if (remoteMode) {
                    if (remoteSpans.containsKey(d)) matchedSpans = mergeSpansForTest(remoteSpans.get(d));
                    if (remoteSnippets.containsKey(d)) snippets = remoteSnippets.get(d);
                } else {
                    int sid = docShard.get(d);
                    if (sid >= 0) {
                        FulltextStore localS = shards[sid];
                        if (localS != null) {
                            if (o.returnPositions) {
                                String qq = (q == null) ? "" : q.trim();
                                FulltextSegmenter seg = localS.resolveSegmenter();
                                java.util.Set<Long> qUidsSet = new java.util.HashSet<>();
                                int[] cutLevels = o.charLevelSearch ? new int[]{DS0_CHAR, DS1_WORD, DS2_PHRASE} : new int[]{DS1_WORD};
                                for (int clv : cutLevels) {
                                    if (qq.isEmpty()) break;
                                    java.util.Iterator<FulltextSegmenter.CutUnit> cit = seg.cut(qq, clv, FulltextSegmenter.SegOptions.defaults());
                                    while (cit.hasNext()) {
                                        FulltextSegmenter.CutUnit cu = cit.next();
                                        if (cu == null || cu.text == null || cu.text.isEmpty()) continue;
                                        long u = localS.termDict.toUid(cu.text, false);
                                        if (u <= 0L) continue;
                                        qUidsSet.add(u);
                                    }
                                }
                                java.util.List<PositionSpan> allMerged = new java.util.ArrayList<>();
                                long p = (core.parentId > 0L) ? core.parentId : core.docId * 10L + core.level;
                                int lvC = (core.level >= 0) ? core.level : 1;
                                for (Long qUid : qUidsSet) {
                                    if (qUid == null || qUid.longValue() <= 0L) continue;
                                    java.util.List<PositionSpan> ps = localS.getTermPositionsForTest(qUid.longValue(), core.docId, p, lvC);
                                    if (ps != null) allMerged.addAll(ps);
                                }
                                matchedSpans = mergeSpansForTest(allMerged);
                            }
                            if (rawDocTextsByDocId != null && rawDocTextsByDocId.containsKey(core.docId)) {
                                String rawTxt = rawDocTextsByDocId.get(core.docId);
                                if (rawTxt != null && !rawTxt.isEmpty() && !matchedSpans.isEmpty()) {
                                    snippets = buildSnippetsForTest(rawTxt, matchedSpans, o);
                                }
                            }
                        }
                    }
                }
                out.add(new ScoredDocHitWithExtras(core, matchedSpans, snippets, globalTop));
            }
            return out;
        }

        /** HC probe：注入伪造 shard 本地结果，直接测全局三路 RRF + 三键稳定排序。 */
        public List<ScoredDocHit> scatterGatherForTest(String q, float[] qVec, int K, SearchOptions options,
                                                       Map<Integer, List<ScoredDocHit>> fakeShardLocalResults) throws IOException {
            if (K <= 0) return new ArrayList<>();
            SearchOptions o = (options != null) ? options : SearchOptions.defaults();
            return doGatherGlobalRrf(fakeShardLocalResults, K, o);
        }
        @Override public void close() { try { transport.close(); } catch (Exception ignore) {} }
    }

    @Override public void close() {
        try { termDict.close(); } catch (Exception ignore) {}
        try { tuPkMap.close(); } catch (Exception ignore) {}
        try { idx1.close(); } catch (Exception ignore) {}
        try { idx2.close(); } catch (Exception ignore) {}
        try { idx3.close(); } catch (Exception ignore) {}
        try { weightStore.close(); } catch (Exception ignore) {}
    }
}
