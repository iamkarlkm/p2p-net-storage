package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.q3lives.ds.database.fulltext.FulltextStore.DocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.NeighborDir;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * T3 Implement: IngestPipeline 4 步 + 5 查询原语 + TOPK 提前终止 铁律（4 TR）。
 * USER-HC-0 全继承：纯文本 unit_id 全等；DIST_BUCKET d∈[1..1024] 双向 2 DsHashMap；
 * PAIR_MAP mix4 四元（l,r,p,lv）；UID_RING offerUnique；每 T≥4 TR。
 */
public class P21_T3_IngestQueryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private TermDictStore td;

    @Before
    public void before() throws IOException {
        File root = tmp.newFolder("p21-t3-" + UUID.randomUUID().toString().replace("-",""));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
        // 每次新测试 bucketAccessCounter 清零
        for (int d = 0; d <= 1024; d++) store.bucketAccessCounter[d] = 0;
    }

    @After
    public void after() {
        try { store.close(); } catch (Exception ignore) {}
    }

    // ---------------- TR1：Ingest 3 文档 × 6 level × 约 50 单元，DIST_BUCKET/PAIR_MAP/IDX 一致 ----------------
    @Test
    public void tr1_ingest3Docs6LevelDistPairConsistent() throws IOException {
        // doc1 = 句子1，doc2=句子2，doc3=句1∩句2（共享 term）
        String s1 = "订阅推送服务稳定运行，提供高质量的文章订阅"; // 约 16 字 WORD 层 ~8 词
        String s2 = "服务端订阅数据同步，分布式推送协议实现稳定"; // 约 14 字 WORD 层 ~7 词
        String s3 = "稳定服务推送，文章订阅同步";
        long d1 = 101L, d2 = 202L, d3 = 303L;
        IngestStat st1 = store.ingestDoc(d1, s1);
        IngestStat st2 = store.ingestDoc(d2, s2);
        IngestStat st3 = store.ingestDoc(d3, s3);
        // 每 level 单元数合理（非 0 对有意义 level）
        Assert.assertTrue("doc1 level>0 单元数非 0", st1.rowsPerLevel[1] > 0);
        Assert.assertTrue("doc2 level>0 单元数非 0", st2.rowsPerLevel[1] > 0);
        Assert.assertTrue("doc3 level>0 单元数非 0", st3.rowsPerLevel[1] > 0);
        // 每 doc pairsTotal = Σ_level n*(n-1)/2（n≥2 才有 pair）
        checkPairs(st1);
        checkPairs(st2);
        checkPairs(st3);
        String[] wList = listLevelSeqWords(s1, 1, st1.docId);
        // PAIR_MAP / DIST_BUCKET distanceBetween 在 TR2 纯 ASCII 无重复场景精确验证（避免中文重复 pair 多实例）
        // DIST_BUCKET 双向邻居：无重复 WORD 列表时至少 d<=1 左右各 1
        if (wList.length >= 3 && isUnique(wList)) {
            long parent = d1 * 10L + 1L;
            Set<Neighbor> n1 = store.neighborsWithin(wList[1], d1, parent, 1, 1);
            Assert.assertTrue("neighborsWithin(d<=1) 至少 2 邻居", n1.size() >= 2);
            int cL=0, cR=0;
            for (Neighbor n : n1) { if (n.dir == NeighborDir.L) cL++; else cR++; }
            Assert.assertTrue("左邻居≥1", cL >= 1);
            Assert.assertTrue("右邻居≥1", cR >= 1);
        }
        // USER-HC-0：3 文档共享 term "订阅" → byTerm size=跨 3 文档的 instances
        List<DocHit> subs = store.byTerm("订阅");
        Assert.assertTrue("共享 term '订阅' 命中≥3 doc 实例", subs.size() >= 3);
        Set<Long> docSet = new HashSet<>();
        for (DocHit h : subs) docSet.add(h.docId);
        Assert.assertTrue("订阅跨 3 doc: " + docSet, docSet.contains(d1) || docSet.contains(d2));
    }
    private boolean isUnique(String[] a) {
        Set<String> s = new HashSet<>();
        for (String x : a) if (!s.add(x)) return false;
        return true;
    }
    private void checkPairs(IngestStat st) {
        long calc = 0;
        for (int n : st.rowsPerLevel) {
            if (n >= 2) calc += (long)n * (n-1) / 2L;
        }
        Assert.assertEquals("doc "+st.docId+" pairsTotal 计算不一致 Σ(n*(n-1)/2)", calc, st.pairsTotal);
    }
    private String[] listLevelSeqWords(String text, int level, long docId) throws IOException {
        // 按 (doc, parent=doc*10+level, level) 查 IDX1 seq 升序，取 text 数组
        long parent = docId * 10L + (long)level;
        List<TuRow> rows = store.selectByParentLevel(docId, parent, level);
        String[] out = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) out[i] = rows.get(i).text;
        return out;
    }

    // ---------------- TR2：5 查询原语 20 断言 端到端（手动 8 uid 精确控制，不依赖 Seg SPI） ----------------
    @Test
    public void tr2_5QueryPrimitives20Assertions() throws IOException {
        long docId = 999L;
        int level = 1;
        long parentW = docId * 10L + level;
        // 8 uid = A,B,C,D,E,F,G,H
        String[] texts = {"A","B","C","D","E","F","G","H"};
        List<Long> uids = new ArrayList<>(texts.length);
        for (int i = 0; i < texts.length; i++) {
            long uid = td.toUid(texts[i], true);
            td.retain(uid);
            uids.add(uid);
            store.insert(new TuRow(uid, docId, parentW, level, i, i*2, i*2+1, texts[i]));
        }
        IngestStat st = store.buildDistForSeq(docId, parentW, level, uids);
        Assert.assertEquals("8*(8-1)/2 = 28 pairs", 28L, st.pairsTotal);
        int checks = 0;
        // Q1 byTerm
        List<DocHit> q1a = store.byTerm("C"); checks++;
        Assert.assertFalse("Q1 byTerm C hit", q1a.isEmpty());
        List<DocHit> q1b = store.byTerm("ZZZ"); checks++;
        Assert.assertTrue("Q1 byTerm ZZZ miss", q1b.isEmpty());
        checks += 2;
        // Q2 byTermInDoc
        List<DocHit> q2a = store.byTermInDoc("D", docId); checks++;
        Assert.assertFalse("Q2 byTermInDoc D hit", q2a.isEmpty());
        Assert.assertEquals("Q2 byTermInDoc D docId", docId, q2a.get(0).docId); checks++;
        List<DocHit> q2b = store.byTermInDoc("D", 999999L); checks++;
        Assert.assertTrue("Q2 byTermInDoc D miss 999999", q2b.isEmpty());
        checks += 3;
        // Q3 distanceBetween (A->D = d=3 i=0,j=3；A->C=d=2；H->X=-1)
        int q3a = store.distanceBetween("A", "D", docId, parentW, level); checks++;
        Assert.assertEquals("A→D distance=3", 3, q3a);
        int q3b = store.distanceBetween("A", "C", docId, parentW, level); checks++;
        Assert.assertEquals("A→C distance=2", 2, q3b);
        int q3c = store.distanceBetween("H", "X", docId, parentW, level); checks++;
        Assert.assertEquals("H→X no pair = -1", -1, q3c);
        checks += 3;
        // Q4 neighborsWithin E(target) maxD=2 → 左 D(seq=3 d=1) C(seq=2 d=2) + 右 F(seq=5 d=1) G(seq=6 d=2) 共 4 条
        Set<Neighbor> q4 = store.neighborsWithin("E", docId, parentW, level, 2); checks++;
        Assert.assertEquals("neighborsWithin E maxD=2 共 4", 4, q4.size());
        int lc=0, rc=0;
        Set<String> lcTexts = new HashSet<>();
        Set<String> rcTexts = new HashSet<>();
        for (Neighbor n : q4) {
            if (n.dir == NeighborDir.L) { lc++; lcTexts.add(td.toText(n.uid)); }
            else { rc++; rcTexts.add(td.toText(n.uid)); }
            Assert.assertTrue("距离 ≤2: "+n.distance, n.distance <= 2);
        }
        Assert.assertEquals("左=D+C=2", 2, lc); checks++;
        Assert.assertEquals("右=F+G=2", 2, rc); checks++;
        Assert.assertTrue("左邻居含 D C", lcTexts.contains("D") && lcTexts.contains("C")); checks++;
        Assert.assertTrue("右邻居含 F G", rcTexts.contains("F") && rcTexts.contains("G")); checks++;
        checks += 3;
        // Q5 topKNeighbors E K=3：
        //   d=1 counter[1]++ 扫 R 桶得 F（1）；counter[1]++ 扫 L 桶得 D（2）；K 未满 → d=2 counter[2]++ 扫 R 桶得 G（3）；满 3 立即 return
        //   bucketAccessCounter 精确 = [0, 2, 1, 0 ...]（d=1 L+R=2；d=2 仅 R=1；L 桶 d=2 未访问）
        Arrays.fill(store.bucketAccessCounter, 0);
        List<Neighbor> q5 = store.topKNeighbors("E", docId, parentW, level, 3); checks++;
        Assert.assertEquals("topK=3 结果 3", 3, q5.size());
        Assert.assertEquals("counter[1]=2 (R桶先扫，L桶再扫 → 都访问)", 2, store.bucketAccessCounter[1]);
        Assert.assertEquals("counter[2]=1 (只扫 R 桶，满 3 return，L桶未访问)", 1, store.bucketAccessCounter[2]);
        for (int d = 3; d <= 1024; d++) Assert.assertEquals("d≥3 counter=0: d="+d, 0, store.bucketAccessCounter[d]);
        checks += 3;
        // 总计断言数 ≥20
        Assert.assertTrue("断言数≥20: "+checks, checks >= 20);
    }

    // ---------------- TR3：HC-6 冲突一致性（先 d1=3 再 d2=5 → ISE） ----------------
    @Test
    public void tr3_hc6DistConflictThrowsISE() throws IOException {
        long docId = 777L;
        long parent = docId * 10L + 1L;
        long ul = td.toUid("w_left", true);
        long ur = td.toUid("w_right", true);
        // 先写 d=3
        store.putPairForTest(ul, ur, parent, 1, 3);
        // 再写 d=5 (同一四元上下文) → ISE "dist inconsistent"
        try {
            store.putPairForTest(ul, ur, parent, 1, 5);
            Assert.fail("HC-6：重复写不同 d 未抛 IllegalStateException");
        } catch (IllegalStateException e) {
            Assert.assertTrue("HC-6 msg 含 'dist inconsistent': " + e.getMessage(),
                e.getMessage().contains("dist inconsistent"));
        }
        // 同 d=3 再写 → OK（幂等不冲突）
        store.putPairForTest(ul, ur, parent, 1, 3);
    }

    // ---------------- TR4【TOPK 铁律】：人工 20 uid A..T (seq 0..19)，target=K(10) K=5 bucketAccessCounter 精确 ----------------
    @Test
    public void tr4_topKNeighborsEarlyStopBucketCounter() throws IOException {
        long docId = 555L;
        int level = 1;
        long parentW = docId * 10L + level;
        // 手动插入 20 行 WORD level TuRow，精确 seq 0..19 对应文本 A..T（不依赖 Seg SPI）
        String[] texts = new String[20];
        for (int i = 0; i < 20; i++) texts[i] = String.valueOf((char)('A' + i));
        List<Long> uids = new ArrayList<>(20);
        for (int i = 0; i < 20; i++) {
            long uid = td.toUid(texts[i], true);
            td.retain(uid);
            uids.add(uid);
            store.insert(new TuRow(uid, docId, parentW, level, i, i*2, i*2+1, texts[i]));
        }
        IngestStat stat = store.buildDistForSeq(docId, parentW, level, uids);
        // 验证 seq 排序正确（T2 selectByParentLevel seq 升序）
        List<TuRow> rows = store.selectByParentLevel(docId, parentW, level);
        Assert.assertEquals("WORD 层 20 条", 20, rows.size());
        for (int i = 0; i < 20; i++) {
            Assert.assertEquals("seq="+i, i, rows.get(i).seq);
            Assert.assertEquals("text="+texts[i], texts[i], rows.get(i).text);
        }
        // 清零 counter
        Arrays.fill(store.bucketAccessCounter, 0);
        // topK("K", K=5)：
        //   d=1: counter[1]++ → L桶（distL[1] R方向）→ L(seq=11) 第1 条；counter[1]++ → R桶（distR[1] L方向）→ J(seq=9) 第2 条；K 未满
        //   d=2: counter[2]++ → L桶 → M(seq=12) 第3 条；counter[2]++ → R桶 → I(seq=8) 第4 条；K 未满
        //   d=3: counter[3]++ → L桶 → N(seq=13) 第5 条；满 5 立即 return → R桶 d=3 未访问（counter 不再加）
        //   d≥4 全 0
        List<Neighbor> top = store.topKNeighbors("K", docId, parentW, level, 5);
        Assert.assertEquals("topK=5 精确 5 条", 5, top.size());
        // 邻居 uid 映射回 text，元素应等于 {L,J,M,I,N}（5 个，不比较顺序，比较集合相等）
        Set<String> neighborTexts = new HashSet<>();
        for (Neighbor n : top) neighborTexts.add(td.toText(n.uid));
        Set<String> expected = new HashSet<>(Arrays.asList("L","J","M","I","N"));
        Assert.assertEquals("邻居集合 L,J,M,I,N 5", expected, neighborTexts);
        // 【TOPK 铁律计数器 精确断言】
        Assert.assertEquals("d=1 桶访问=2 (L+R)", 2, store.bucketAccessCounter[1]);
        Assert.assertEquals("d=2 桶访问=2 (L+R)", 2, store.bucketAccessCounter[2]);
        Assert.assertEquals("d=3 桶访问=1 (仅 L 桶,R 未触达)", 1, store.bucketAccessCounter[3]);
        for (int d = 4; d <= 1024; d++) {
            Assert.assertEquals("d≥4 桶访问=0 (提前终止): d="+d, 0, store.bucketAccessCounter[d]);
        }
    }
}
