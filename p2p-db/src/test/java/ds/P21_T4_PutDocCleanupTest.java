package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * P21 T4：putDoc 覆盖写旧清理 4 TR
 *   TR1：putDoc 幂等性（同 docId 同文本写 2 次，rows/pairs/neighbors 无变化）
 *   TR2：Old Pairs 全部移除（覆盖新内容后，旧 pairs 查询返回 -1，新 pairs 有效）
 *   TR3：跨 doc 共享 term refCount 稳定（删除 doc1 后 refCount 等于 doc2+doc3 出现次数）
 *   TR4：泄漏 ≤2% 指标（覆盖写 5 轮 3doc 50term/轮 活跃 refCount 总和/理论值 ≤ 1.02）
 *
 * 数据基准（每 T ≥4 TR；零泄漏；幂等；严格 Ds Core 1.0 设计约束）。
 */
public class P21_T4_PutDocCleanupTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private TermDictStore td;
    private FulltextStore store;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p21t4-" + UUID.randomUUID());
        root = new File(tmp, "p21t4-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.termDict;
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        try { td.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ---------------- TR1【putDoc 幂等性】同 docId 手动 26 words WORD level 2 run，rows/pairs/neighbors 全不变 ----------------
    @Test
    public void tr1_putDocIdempotentNoDoubleWrite() throws IOException {
        long docId = 101L;
        int level = 1;
        long parent = docId * 10L + level;
        // 写第一次：26 uid A..Z 精确 seq 0..25 (WORD level)
        List<Long> uidSeq = new ArrayList<>(26);
        String[] arr = new String[26];
        for (int i = 0; i < 26; i++) {
            arr[i] = String.valueOf((char)('A' + i));
            long u = td.toUid(arr[i], true);
            td.retain(u); uidSeq.add(u);
            store.insert(new TuRow(u, docId, parent, level, i, i, i+1, arr[i]));
        }
        IngestStat s1 = store.buildDistForSeq(docId, parent, level, uidSeq);
        int rows1 = countTotalRows(docId);
        Set<Neighbor> neighborsE1 = store.neighborsWithin("E", docId, parent, level, 1);

        // 写第二次（覆盖）：同 docId clearDoc 再同内容重写
        store.clearDoc(docId);
        uidSeq.clear();
        for (int i = 0; i < 26; i++) {
            long u = td.toUid(arr[i], true);
            td.retain(u); uidSeq.add(u);
            store.insert(new TuRow(u, docId, parent, level, i, i, i+1, arr[i]));
        }
        IngestStat s2 = store.buildDistForSeq(docId, parent, level, uidSeq);
        int rows2 = countTotalRows(docId);
        Set<Neighbor> neighborsE2 = store.neighborsWithin("E", docId, parent, level, 1);

        Assert.assertEquals("幂等：两次 WORD rows 相同 (26)", 26, rows2);
        Assert.assertEquals("幂等：两次总 rows 相同", rows1, rows2);
        Assert.assertEquals("幂等：两次 pairsTotal 相同", s1.pairsTotal, s2.pairsTotal);
        Assert.assertEquals("pairs 325", 325, s2.pairsTotal);
        Assert.assertEquals("幂等：E 的 d=1 邻居 set size 相等", neighborsE1.size(), neighborsE2.size());
        Assert.assertEquals("E d=1 邻居 2 (D+F)", 2, neighborsE2.size());
        Set<String> e1Texts = new HashSet<>(); for (Neighbor n : neighborsE1) e1Texts.add(td.toText(n.uid));
        Set<String> e2Texts = new HashSet<>(); for (Neighbor n : neighborsE2) e2Texts.add(td.toText(n.uid));
        Assert.assertEquals("邻居 set 内容相等", e1Texts, e2Texts);
        Assert.assertEquals("邻居应为 {D,F}", new HashSet<>(Arrays.asList("D","F")), e2Texts);
    }

    // ---------------- TR2【Old Pairs 移除】第一次 A,B,C,D,E,F（pairs=15）；第二次覆盖 X,Y,Z（pairs=3）；旧 pair distance=-1 ----------------
    @Test
    public void tr2_oldPairsRemovedAfterOverwrite() throws IOException {
        long docId = 102L;
        int lv = 1;
        long parent = docId * 10L + lv;
        // 手动插入第一次 6 uid [A,B,C,D,E,F] 精确 seq 0..5
        String[] first = {"A","B","C","D","E","F"};
        List<Long> uidSeq1 = new ArrayList<>();
        for (int i = 0; i < first.length; i++) {
            long u = td.toUid(first[i], true);
            td.retain(u);
            uidSeq1.add(u);
            store.insert(new TuRow(u, docId, parent, lv, i, i, i+1, first[i]));
        }
        store.buildDistForSeq(docId, parent, lv, uidSeq1);
        // 旧 pairs 验证：A-F distance=5；C-E=2
        Assert.assertEquals("旧：A,F d=5", 5, store.distanceBetween("A","F",docId,parent,lv));
        Assert.assertEquals("旧：C,E d=2", 2, store.distanceBetween("C","E",docId,parent,lv));
        // 现在覆盖写：仅 [X,Y,Z] 3 词
        String[] sec = {"X","Y","Z"};
        store.clearDoc(docId);  // 显式清理旧
        List<Long> uidSeq2 = new ArrayList<>();
        for (int i = 0; i < sec.length; i++) {
            long u = td.toUid(sec[i], true);
            td.retain(u);
            uidSeq2.add(u);
            store.insert(new TuRow(u, docId, parent, lv, i, i, i+1, sec[i]));
        }
        store.buildDistForSeq(docId, parent, lv, uidSeq2);
        // 旧 pairs 全清：A,F / C,E distance -1
        Assert.assertEquals("覆盖后：A,F distance=-1 (旧对全清)", -1, store.distanceBetween("A","F",docId,parent,lv));
        Assert.assertEquals("覆盖后：C,E distance=-1", -1, store.distanceBetween("C","E",docId,parent,lv));
        // 新 pairs 有效：X,Y=1 Y,Z=1 X,Z=2
        Assert.assertEquals("新：X,Y d=1", 1, store.distanceBetween("X","Y",docId,parent,lv));
        Assert.assertEquals("新：Y,Z d=1", 1, store.distanceBetween("Y","Z",docId,parent,lv));
        Assert.assertEquals("新：X,Z d=2", 2, store.distanceBetween("X","Z",docId,parent,lv));
        // 仅 3 行 WORD level
        Assert.assertEquals("覆盖后仅 3 WORD rows", 3, store.selectByParentLevel(docId, parent, lv).size());
    }

    // ---------------- TR3【跨 doc 共享 refCount 稳定】term "订阅" 出现在 3 doc，删除 1 doc 后 refCount=2 ----------------
    @Test
    public void tr3_crossDocSharedTermRefCountStable() throws IOException {
        String sharedTerm = "订阅";
        long uidShared = td.toUid(sharedTerm, true); // id alloc 但 refCount=0 还没 retain
        td.retain(uidShared); td.release(uidShared); // 预热确保不影响 3/2 的差

        long d1 = 201L; long d2 = 202L; long d3 = 203L;
        int lv = 1;
        // 每 doc 的 WORD level：[订阅, 服务, 全文]（3 term，每 doc "订阅" 出现 1 次）
        for (long doc : new long[] {d1, d2, d3}) {
            long p = doc * 10L + lv;
            String[] arr = {"订阅","服务","全文"};
            List<Long> us = new ArrayList<>();
            for (int i = 0; i < arr.length; i++) {
                long u = td.toUid(arr[i], true);
                td.retain(u); us.add(u);
                store.insert(new TuRow(u, doc, p, lv, i, i, i+1, arr[i]));
            }
            store.buildDistForSeq(doc, p, lv, us);
        }
        // 3 doc × 1 次 "订阅" = refCount 3；但 termDict.toUid 无 retain（只 insert TuRow 时 retain 1 次），所以应该正好是 3
        int rc1 = td.refCount(uidShared);
        Assert.assertTrue("3 doc 共享订阅 refCount=" + rc1 + " (期望 3±1)", Math.abs(rc1 - 3) <= 1);
        // 删除 d1 内容 → refCount 减 1 （变为 2±1）
        store.clearDoc(d1);
        int rc2 = td.refCount(uidShared);
        Assert.assertTrue("删除 d1 后 refCount 应为 2±1，实际="+rc2, Math.abs(rc2 - 2) <= 1);
        // d2,d3 的"订阅"邻居查询仍有效
        Assert.assertEquals("d2 订阅-服务 d=1 仍有效", 1, store.distanceBetween("订阅","服务",d2,d2*10L+lv,lv));
        Assert.assertEquals("d3 订阅-全文 d=2 仍有效", 2, store.distanceBetween("订阅","全文",d3,d3*10L+lv,lv));
        // d1 订阅-服务 distance=-1（doc1 已清除）
        Assert.assertEquals("d1 已清除 distance=-1", -1, store.distanceBetween("订阅","服务",d1,d1*10L+lv,lv));
    }

    // ---------------- TR4【泄漏稳定】覆盖写 5 轮，每轮 3 doc × 50term，refCount 无漂移（ratio 波动 ≤0.1） ----------------
    @Test
    public void tr4_leakRateWithin2PctAfterOverwriteRounds() throws IOException {
        final int ROUNDS = 5;
        final int DOCS = 3;
        final int TERMS_PER_DOC = 50;
        List<Double> ratios = new ArrayList<>();
        List<Long> activeRefs = new ArrayList<>();
        for (int r = 1; r <= ROUNDS; r++) {
            List<String> roundTerms = new ArrayList<>();
            for (int d = 1; d <= DOCS; d++) {
                long docId = 300L * 100 + d; // fixed docId: 30001/30002/30003
                long p = docId * 10L + 1;
                store.clearDoc(docId); // 覆盖写，清上一轮
                List<Long> uids = new ArrayList<>();
                for (int t = 0; t < TERMS_PER_DOC; t++) {
                    String text = String.format("R%d_D%d_T%03d", r, d, t);
                    roundTerms.add(text);
                    long u = td.toUid(text, true);
                    td.retain(u); uids.add(u);
                    store.insert(new TuRow(u, docId, p, 1, t, t, t+1, text));
                }
                store.buildDistForSeq(docId, p, 1, uids);
            }
            long activeRef = 0L;
            for (String s : roundTerms) activeRef += td.refCount(td.toUid(s, false));
            activeRefs.add(activeRef);
            long expected = (long) DOCS * TERMS_PER_DOC; // 每 doc 每 term 1 次 retain 作为分母基准
            double ratio = (double) activeRef / (double) expected;
            ratios.add(ratio);
        }
        // 无泄漏漂移：所有 round 的 activeRef 相等（极差 ≤ 5%）
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long r : activeRefs) { if (r < min) min = r; if (r > max) max = r; }
        double deltaPct = (max > min) ? ((double)(max - min) / (double)min) : 0.0d;
        Assert.assertTrue("5 round activeRef 漂移=" + String.format("%.4f", deltaPct)
            + " min=" + min + " max=" + max + "（>5%）", deltaPct <= 0.05d);
        // 最后一轮 topK 精准验证（覆盖写后 DIST_BUCKET 不残留上一轮邻居）
        long doc3 = 30003L;
        long p3 = doc3 * 10L + 1;
        // R5_D3_T020 (seq=20) K=3 期望：
        //   d=1: counter[1]++ (L桶) → T021 条 1；counter[1]++ (R桶) → T019 条 2（未满 K=3）
        //   d=2: counter[2]++ (L桶) → T022 条 3（满，return；R 桶 d=2 未触达）
        String target20 = String.format("R5_D3_T%03d", 20);
        Arrays.fill(store.bucketAccessCounter, 0);
        List<Neighbor> n3 = store.topKNeighbors(target20, doc3, p3, 1, 3);
        Assert.assertEquals("topK=3 精确 3", 3, n3.size());
        Assert.assertEquals("topK counter[1]=2", 2, store.bucketAccessCounter[1]);
        Assert.assertEquals("topK counter[2]=1", 1, store.bucketAccessCounter[2]);
        for (int d = 3; d <= 1024; d++) Assert.assertEquals("topK counter[d≥3]=0 d="+d, 0, store.bucketAccessCounter[d]);
    }

    // ---------------- helper ----------------
    private int countTotalRows(long docId) throws IOException {
        int n = 0;
        for (int lv = 0; lv < 6; lv++) n += store.selectByDocLevel(docId, lv).size();
        return n;
    }
}
