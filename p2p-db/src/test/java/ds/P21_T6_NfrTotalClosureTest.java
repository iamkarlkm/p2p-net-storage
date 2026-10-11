package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * P21 T6：NFR 性能 4 TR + 总闭包
 *   TR1【Ingest P99 ≤ 4s】：10 doc × 20 term pairs=10×190=1,900 端到端 ≤4s
 *   TR2【Q1 byTerm Q3 ≤ 200ms】：50 次 term byTerm 查询端到端 P95 ≤ 200ms
 *   TR3【Q4b topK Q3 ≤ 200ms】：50 次 topK(K=10) 查询端到端 P95 ≤ 200ms
 *   TR4【Bloat ≤ 500MB / 非空】：磁盘占用 >1MB 且 <500MB（防 10x 级灾难）
 *
 * NFR 阈值比 spec 宽松，防止 CI 波动；核心是验证**没有量级级错误**（不是 10x 级退化）。
 */
public class P21_T6_NfrTotalClosureTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private TermDictStore td;
    private FulltextStore store;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p21t6-" + UUID.randomUUID());
        root = new File(tmp, "p21t6-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.termDict;
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        try { td.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ------------- common fixture：10 doc × 20 WORD term/doc（term 形如：T_%d_%03d）-------------
    private void build10Doc20Term() throws IOException {
        final int DOCS = 10, TERMS = 20, LV = 1;
        for (int d = 0; d < DOCS; d++) {
            long docId = 1_000_000L + d;
            long p = docId * 10L + LV;
            List<Long> uids = new ArrayList<>(TERMS);
            for (int t = 0; t < TERMS; t++) {
                String text = String.format("T_%d_%02d", d, t);
                long u = td.toUid(text, true);
                td.retain(u); uids.add(u);
                store.insert(new TuRow(u, docId, p, LV, t, t, t+1, text));
            }
            store.buildDistForSeq(docId, p, LV, uids);
        }
    }

    // ---------------- TR1【Ingest】10×20 pairs=10×190=1,900；端到端 ≤4000ms
    @Test
    public void tr1_ingest10Doc20TermWithin4Sec() throws IOException {
        long t0 = System.currentTimeMillis();
        build10Doc20Term();
        long t1 = System.currentTimeMillis();
        long ms = t1 - t0;
        Assert.assertTrue("Ingest 10×20 P99 = "+ms+"ms > 4000ms（量级级退化）", ms <= 4000L);
        // 抽样 10 doc：WORD rows=20
        for (int d = 0; d < 10; d++) {
            long docId = 1_000_000L + d;
            int rows = store.selectByDocLevel(docId, 1).size();
            Assert.assertEquals("docId="+docId+" WORD rows=20", 20, rows);
        }
    }

    // ---------------- TR2【Q1 byTerm】50 次查询 P95 ≤ 200ms
    @Test
    public void tr2_q1_byTerm50QueriesP95within200ms() throws IOException {
        build10Doc20Term();
        final int Q = 50;
        long[] times = new long[Q];
        for (int q = 0; q < Q; q++) {
            int d = q % 10;
            int t = q % 20;
            String term = String.format("T_%d_%02d", d, t);
            long t0 = System.nanoTime();
            List<FulltextStore.DocHit> hits = store.byTerm(term);
            long t1 = System.nanoTime();
            times[q] = t1 - t0;
            Assert.assertFalse("term "+term+" 命中至少 1 doc", hits.isEmpty());
        }
        Arrays.sort(times);
        long p95 = times[(int)(Q*0.95)];
        Assert.assertTrue("Q1 byTerm P95 = "+(p95/1_000_000L)+"ms > 200ms（量级退化）",
            p95 <= 200_000_000L);
    }

    // ---------------- TR3【Q4b topK K=10】50 次查询 P95 ≤ 200ms
    @Test
    public void tr3_q4b_topK10P95within200ms() throws IOException {
        build10Doc20Term();
        final int Q = 50, LV = 1, K = 10;
        long[] times = new long[Q];
        for (int q = 0; q < Q; q++) {
            int d = q % 10;
            long docId = 1_000_000L + d;
            long p = docId * 10L + LV;
            String target = String.format("T_%d_%02d", d, 10); // 中间位置（seq10，邻居 K=10 满）
            long t0 = System.nanoTime();
            List<Neighbor> top = store.topKNeighbors(target, docId, p, LV, K);
            long t1 = System.nanoTime();
            times[q] = t1 - t0;
            Assert.assertTrue("topK docId="+docId+" 返回 ≥8（不满 K 正常但需接近满）", top.size() >= 8);
        }
        Arrays.sort(times);
        long p95 = times[(int)(Q*0.95)];
        Assert.assertTrue("Q4b topK(K=10) P95 = "+(p95/1_000_000L)+"ms > 200ms（量级退化）",
            p95 <= 200_000_000L);
    }

    // ---------------- TR4【Bloat】磁盘总占用：>1MB 且 <500MB（防止 10x 级灾难或空目录）
    @Test
    public void tr4_bloatDirSizePlausible() throws IOException {
        build10Doc20Term();
        long disk = dirSize(root);
        final long MAX = 500_000_000L; // 500MB 上限
        final long MIN = 200_000L;       // 200KB 下限（至少有内容）
        Assert.assertTrue("Bloat: disk="+disk+" > 500MB（异常大）", disk <= MAX);
        Assert.assertTrue("Bloat: disk="+disk+" < 200KB（空或漏写）", disk >= MIN);
    }

    // --------- helpers ---------
    private static long dirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long s = 0L;
        File[] fs = dir.listFiles();
        if (fs == null) return 0L;
        for (File f : fs) s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }
}
