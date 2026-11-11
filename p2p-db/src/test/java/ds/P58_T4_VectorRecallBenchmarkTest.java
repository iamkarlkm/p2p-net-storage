package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.WeightStore.HnswHitForTest;
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * P58 T4：P2 Feature#4 验收 —— 为向量检索引入召回率基准（TPC-H 类似 P33 的 dataset）。
 *
 * <p>
 * 目的：在不改生产代码的前提下，建立一条**可复现的向量召回率基准**，用于度量自研 HNSW
 * （{@code FulltextStore.knnSearchForTest}）相对精确暴力 top-K 的召回率。基准数据集由固定种子
 * 的伪随机生成器构造（分维度/规模/种子三参数，类似 P33 的 TPC-H dataset），ground truth 用
 * {@code cosineSimForTest} 独立于索引精确计算，二者集合求交得到 recall@K。
 *
 * <p>
 * 当前 HNSW MVP 为暴力全节点距离排序（精确），故 recall@K ≡ 1.0；本基准即锁定该**召回基线 ≈ 100%**。
 * 后续引入真·近似 ANN（受限 efSearch 邻居扩展）时，本基线的阈值断言将立即捕获召回退化。
 *
 * <p>
 * 覆盖 6 TR：
 * <ol>
 * <li>TR4.1 dataset 确定性：同种子两次生成逐位相等 + 全部入索引后 nodeCount == N</li>
 * <li>TR4.2 recall@K 基准：ground truth（暴力）vs HNSW，平均 recall@10 ≥ 0.95（精确时 = 1.0）</li>
 * <li>TR4.3 efSearch 影响：recall 随 ef 单调不减，且各档 ≥ 阈值</li>
 * <li>TR4.4 L2 变体：单位归一后 L2 序 = cosine 序 → HNSW top-K 集合与 L2 ground truth 全等</li>
 * <li>TR4.5 统计稳定性：多种子重复基准，min recall ≥ 阈值（防抖/防退化）</li>
 * <li>TR4.6 基准骨架 Fail-First：维度不一致 → IAE；空向量/非法 uid 不入索引（nodeCount 不变）</li>
 * </ol>
 *
 * @since P58
 */
public class P58_T4_VectorRecallBenchmarkTest {

    private static final int DIM = 16;
    private static final int CORPUS_N = 200;
    private static final int QUERY_N = 20;
    private static final int K = 10;
    private static final int EF = 128;
    private static final double RECALL_THRESHOLD = 0.95;
    private static final long SEED = 20261108L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("p58t4-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After
    public void tearDown() {
        if (store != null) {
            try { store.close(); } catch (Exception ignore) { /* 资源清理 */ }
        }
    }

    // ============== 基准数据集（TPC-H 式：固定种子的确定性生成） ==============

    /** 单条基准记录：uid + 向量。 */
    private static final class VecRow {
        final long uid;
        final float[] vec;
        VecRow(long uid, float[] vec) { this.uid = uid; this.vec = vec; }
    }

    /** 排序用候选：uid 以 long 精确保存（uid 在 2^58 量级，double 只能精确到 64 的倍数）。 */
    private static final class Cand {
        final long uid;
        final double d;
        Cand(long uid, double d) { this.uid = uid; this.d = d; }
    }

    /** 基准报表（召回率基准产物，可复现/可打印）。 */
    private static final class RecallReport {
        final int corpusN;
        final int queryN;
        final int k;
        final int efSearch;
        final double recallAtK;
        RecallReport(int corpusN, int queryN, int k, int efSearch, double recallAtK) {
            this.corpusN = corpusN;
            this.queryN = queryN;
            this.k = k;
            this.efSearch = efSearch;
            this.recallAtK = recallAtK;
        }
        @Override public String toString() {
            return "RecallReport{corpus=" + corpusN + ",queries=" + queryN + ",K=" + k
                    + ",ef=" + efSearch + ",recall@" + k + "=" + String.format("%.4f", recallAtK) + "}";
        }
    }

    /** 固定种子生成基准数据集：CORPUS_N 语料 + QUERY_N 查询（dim=DIM 高斯向量）。 */
    private static List<VecRow> genDataset(long seed) {
        Random r = new Random(seed);
        List<VecRow> all = new ArrayList<>(CORPUS_N + QUERY_N);
        for (int i = 0; i < CORPUS_N; i++) all.add(new VecRow(0L, gauss(r)));
        for (int i = 0; i < QUERY_N; i++) all.add(new VecRow(0L, gauss(r)));
        return all;
    }

    private static float[] gauss(Random r) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) v[i] = (float) r.nextGaussian();
        return v;
    }

    private static float[] unit(float[] v) {
        double n = 0.0;
        for (float x : v) n += (double) x * x;
        double inv = (n <= 0.0) ? 1.0 : 1.0 / Math.sqrt(n);
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] * inv);
        return out;
    }

    /** 入索引并回填真实 uid，返回 uid -> vec 全量视图（corpus 在前，query 紧随）。 */
    private Map<Long, float[]> indexDataset(List<VecRow> rows) throws Exception {
        Map<Long, float[]> universe = new LinkedHashMap<>();
        int idx = 0;
        for (VecRow row : rows) {
            long uid = td.toUid("p58t4_v" + idx++, true);
            store.indexTermVector(uid, row.vec);
            universe.put(uid, row.vec);
        }
        return universe;
    }

    private static long uidAt(Map<Long, float[]> universe, int order) {
        int i = 0;
        for (Long u : universe.keySet()) if (i++ == order) return u;
        throw new IllegalStateException("order out of range: " + order);
    }

    /** 精确暴力 top-K（cosine 降序，tie-break uid 升序），exclude 自身。 */
    private static List<Long> exactTopK(Map<Long, float[]> universe, long qUid, int k) {
        float[] q = universe.get(qUid);
        List<Cand> cands = new ArrayList<>(universe.size());
        for (Map.Entry<Long, float[]> e : universe.entrySet()) {
            if (e.getKey() == qUid) continue;
            cands.add(new Cand(e.getKey(), FulltextStore.cosineSimForTest(q, e.getValue())));
        }
        cands.sort((a, b) -> {
            int c = Double.compare(b.d, a.d);
            return (c != 0) ? c : Long.compare(a.uid, b.uid);
        });
        List<Long> out = new ArrayList<>(k);
        for (int i = 0; i < cands.size() && i < k; i++) out.add(cands.get(i).uid);
        return out;
    }

    /** HNSW 近似 top-K：knnSearch 后剔除查询自身，取前 k。 */
    private List<Long> hnswTopK(long qUid, int k, int ef) throws Exception {
        List<HnswHitForTest> hits = store.knnSearchForTest(qUid, k + 8, ef);
        List<Long> out = new ArrayList<>(k);
        for (HnswHitForTest h : hits) {
            if (h.uid == qUid) continue;
            out.add(h.uid);
            if (out.size() == k) break;
        }
        return out;
    }

    private static double recall(List<Long> approx, List<Long> truth, int k) {
        if (k <= 0 || truth.isEmpty()) return 0.0;
        Set<Long> t = new TreeSet<>(truth);
        int hit = 0;
        for (Long a : approx) if (t.contains(a)) hit++;
        return hit / (double) Math.min(k, truth.size());
    }

    /** 跑一轮完整基准：建索引 + 逐查询算 ground truth 与 HNSW，返回平均 recall@K。 */
    private RecallReport runBenchmark(long seed, int k, int ef) throws Exception {
        Map<Long, float[]> universe = indexDataset(genDataset(seed));
        int qStart = CORPUS_N;
        double sum = 0.0;
        for (int i = 0; i < QUERY_N; i++) {
            long qUid = uidAt(universe, qStart + i);
            List<Long> truth = exactTopK(universe, qUid, k);
            List<Long> approx = hnswTopK(qUid, k, ef);
            sum += recall(approx, truth, k);
        }
        return new RecallReport(CORPUS_N, QUERY_N, k, ef, sum / QUERY_N);
    }

    // ============== TR4.1 dataset 确定性 + nodeCount ==============
    @Test(timeout = 180000)
    public void tr4_1_datasetDeterministicAndIndexed() throws Exception {
        List<VecRow> a = genDataset(SEED);
        List<VecRow> b = genDataset(SEED);
        Assert.assertEquals("数据集规模 = corpus+query", CORPUS_N + QUERY_N, a.size());
        for (int i = 0; i < a.size(); i++) {
            Assert.assertArrayEquals("同种子逐位相等 idx=" + i, a.get(i).vec, b.get(i).vec, 0f);
        }
        List<VecRow> c = genDataset(SEED + 1);
        boolean diff = false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i).vec, c.get(i).vec)) { diff = true; break; }
        }
        Assert.assertTrue("不同种子应产生不同数据集", diff);

        indexDataset(a);
        Assert.assertEquals("全部入索引后 nodeCount", CORPUS_N + QUERY_N,
                store.getHnswMetaForTest().nodeCount);
    }

    // ============== TR4.2 recall@K 基准 ==============
    @Test(timeout = 300000)
    public void tr4_2_recallAtKBaseline() throws Exception {
        RecallReport rep = runBenchmark(SEED, K, EF);
        Assert.assertTrue("recall@K 基准应 ≥ " + RECALL_THRESHOLD + "（实测 " + rep + "）",
                rep.recallAtK >= RECALL_THRESHOLD);
        Assert.assertTrue("recall 必须为有限值 " + rep, !Double.isNaN(rep.recallAtK));
    }

    // ============== TR4.3 efSearch 影响 ==============
    @Test(timeout = 300000)
    public void tr4_3_recallMonotonicInEfSearch() throws Exception {
        int[] efs = {16, 32, 64, 128};
        double prev = -1.0;
        double last = -1.0;
        for (int ef : efs) {
            RecallReport rep = runBenchmark(SEED, K, ef);
            Assert.assertTrue("ef=" + ef + " recall ≥ 阈值（" + rep + "）", rep.recallAtK >= RECALL_THRESHOLD);
            Assert.assertTrue("recall 随 ef 单调不减 ef=" + ef + " prev=" + prev + " cur=" + rep.recallAtK,
                    rep.recallAtK >= prev - 1e-9);
            prev = rep.recallAtK;
            last = rep.recallAtK;
        }
        Assert.assertTrue("最高档 ef=128 recall ≥ " + RECALL_THRESHOLD, last >= RECALL_THRESHOLD);
    }

    // ============== TR4.4 L2 变体（单位归一后 L2 序 == cosine 序） ==============
    @Test(timeout = 300000)
    public void tr4_4_l2VariantEqualsCosineOnUnitVectors() throws Exception {
        List<VecRow> rows = genDataset(SEED);
        Map<Long, float[]> universe = new LinkedHashMap<>();
        int idx = 0;
        for (VecRow row : rows) {
            long uid = td.toUid("p58t4_l2_v" + idx++, true);
            float[] u = unit(row.vec);
            store.indexTermVector(uid, u);
            universe.put(uid, u);
        }
        int qStart = CORPUS_N;
        for (int i = 0; i < 5; i++) {
            long qUid = uidAt(universe, qStart + i);
            float[] q = universe.get(qUid);
            // L2 ground truth（单位向量：L2²=2-2cos，序与 cosine 全等）
            List<Long> truthL2 = exactTopKByL2(universe, qUid, q, K);
            List<Long> approx = hnswTopK(qUid, K, EF);
            Assert.assertEquals("L2 ground truth = HNSW top-K（归一后）q=" + qUid,
                    new TreeSet<>(truthL2), new TreeSet<>(approx));
        }
    }

    /** 精确暴力 top-K（L2 升序，tie-break uid 升序），exclude 自身。 */
    private static List<Long> exactTopKByL2(Map<Long, float[]> universe, long qUid, float[] q, int k) {
        List<Cand> cands = new ArrayList<>(universe.size());
        for (Map.Entry<Long, float[]> e : universe.entrySet()) {
            if (e.getKey() == qUid) continue;
            cands.add(new Cand(e.getKey(), FulltextStore.l2DistForTest(q, e.getValue())));
        }
        cands.sort((a, b) -> {
            int c = Double.compare(a.d, b.d);
            return (c != 0) ? c : Long.compare(a.uid, b.uid);
        });
        List<Long> out = new ArrayList<>(k);
        for (int i = 0; i < cands.size() && i < k; i++) out.add(cands.get(i).uid);
        return out;
    }

    // ============== TR4.5 统计稳定性（多种子） ==============
    @Test(timeout = 300000)
    public void tr4_5_multiSeedStatisticallyStable() throws Exception {
        double min = Double.MAX_VALUE;
        for (int s = 0; s < 3; s++) {
            RecallReport rep = runBenchmark(SEED + s * 101L, K, EF);
            Assert.assertTrue("seed#" + s + " recall ≥ 阈值（" + rep + "）", rep.recallAtK >= RECALL_THRESHOLD);
            min = Math.min(min, rep.recallAtK);
        }
        Assert.assertTrue("多种子 min recall ≥ " + RECALL_THRESHOLD + "（实测 " + min + "）",
                min >= RECALL_THRESHOLD);
    }

    // ============== TR4.6 基准骨架 Fail-First ==============
    @Test(timeout = 60000)
    public void tr4_6_benchmarkHarnessFailFast() throws Exception {
        // 维度不一致 → IAE（cosine / L2）
        try {
            FulltextStore.cosineSimForTest(new float[]{1, 2}, new float[]{1});
            Assert.fail("cosine 维度不一致必须 IAE");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            FulltextStore.l2DistForTest(new float[]{1, 2}, new float[]{1});
            Assert.fail("L2 维度不一致必须 IAE");
        } catch (IllegalArgumentException expected) { /* ok */ }

        // 空向量 / null / 非法 uid → Fail-First IAE（校验先于任何写入）
        int before = store.getHnswMetaForTest().nodeCount;
        expectIae("空向量", 1L, new float[0]);
        expectIae("null 向量", 2L, null);
        expectIae("uid=0", 0L, gauss(new Random(1L)));
        expectIae("uid<0", -5L, gauss(new Random(2L)));
        Assert.assertEquals("非法输入不得新增节点", before, store.getHnswMetaForTest().nodeCount);

        // 合法向量 → nodeCount +1；同 uid 覆盖幂等 → nodeCount 不变
        long u = td.toUid("p58t4_ff_probe", true);
        store.indexTermVector(u, gauss(new Random(3L)));
        Assert.assertEquals("首次写入 +1", before + 1, store.getHnswMetaForTest().nodeCount);
        store.indexTermVector(u, gauss(new Random(4L)));
        Assert.assertEquals("同 uid 覆盖幂等", before + 1, store.getHnswMetaForTest().nodeCount);
        Assert.assertNotNull("覆盖后可读回向量", store.getTermVectorForTest(u));
    }

    private void expectIae(String label, long uid, float[] vec) throws Exception {
        try {
            store.indexTermVector(uid, vec);
            Assert.fail(label + " 必须 IAE（uid=" + uid + "）");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }
}
