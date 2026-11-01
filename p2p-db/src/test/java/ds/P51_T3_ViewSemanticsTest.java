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
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.NeighborDir;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;

/**
 * P51 T3：跨段词网只读视图语义（4 TR）。
 *
 * <p>
 * TR3.1 {@code neighborsWithin} 命中/miss + {@code level} IAE + {@code maxD} 钳制 [1,1024]；TR3.2
 * {@code distanceBetween} 方向语义（L→R 正距离 / 反向与不存在 = -1）；TR3.3 {@code topKNeighbors}
 * TOPK 铁律（d 非递减、满 K 立即返回、去重）；TR3.4 多段「新段优先」短路覆盖 + {@code fingerprint} 随段序变化 +
 * {@code close} 幂等。
 */
public class P51_T3_ViewSemanticsTest {

    private static final long DOC = 900L;
    private static final long PARENT = 9001L;
    private static final int LEVEL = 1;
    /** A..H 的显式 uid（不依赖 TermDict 分配，保证键跨 store 恒定）。 */
    private static final long[] UIDS = {101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private WordNetSegmentView view;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p51-t3-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        insertSeq(store, DOC, PARENT, LEVEL, UIDS);
        store.buildDistForSeq(DOC, PARENT, LEVEL, toList(UIDS));
        new WordNetExporter().export(store, rootDir.getAbsolutePath(), 1L);
        view = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{1L});
    }

    @After
    public void after() {
        try {
            if (view != null) {
                view.close();
            }
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
        try {
            store.close();
        } catch (Exception ignore) {
            // 关闭失败不阻塞用例
        }
    }

    // ---------------- TR3.1 neighborsWithin 命中/miss + level IAE + maxD 钳制 ----------------
    @Test(timeout = 60000)
    public void tr31_neighborsWithinHitMissAndClamp() {
        long target = UIDS[4]; // E：左 D(d1) C(d2) B(d3) A(d4)；右 F(d1) G(d2) H(d3)
        Set<String> within2 = keys(view.neighborsWithin(target, DOC, PARENT, LEVEL, 2));
        Assert.assertEquals("TR3.1 maxD=2 邻居数", 4, within2.size());
        Assert.assertEquals("TR3.1 maxD=2 集合",
                new HashSet<>(Arrays.asList(
                        UIDS[3] + ":L:1", UIDS[2] + ":L:2", UIDS[5] + ":R:1", UIDS[6] + ":R:2")),
                within2);

        Set<String> clampedLow = keys(view.neighborsWithin(target, DOC, PARENT, LEVEL, 0));
        Assert.assertEquals("TR3.1 maxD=0 钳到 1 → 2 邻居", 2, clampedLow.size());
        Assert.assertEquals("TR3.1 maxD=0 集合",
                new HashSet<>(Arrays.asList(UIDS[3] + ":L:1", UIDS[5] + ":R:1")), clampedLow);

        List<Neighbor> clampedHigh = view.neighborsWithin(target, DOC, PARENT, LEVEL, 99999);
        Assert.assertEquals("TR3.1 maxD 巨大钳到 1024 → 全 7 邻居", 7, clampedHigh.size());
        for (Neighbor n : clampedHigh) {
            Assert.assertTrue("TR3.1 距离 ≤ 1024: " + n.distance, n.distance <= FulltextStore.DIST_MAX);
        }

        Assert.assertTrue("TR3.1 未知 uid miss 为空",
                view.neighborsWithin(0L, DOC, PARENT, LEVEL, 5).isEmpty());
        Assert.assertTrue("TR3.1 未知 ctx miss 为空",
                view.neighborsWithin(target, DOC, PARENT + 777L, LEVEL, 5).isEmpty());

        int thrown = 0;
        thrown += expectIae(() -> view.neighborsWithin(target, DOC, PARENT, -1, 5));
        thrown += expectIae(() -> view.neighborsWithin(target, DOC, PARENT, 6, 5));
        Assert.assertEquals("TR3.1 level ∉ [0,5] 两条 IAE", 2, thrown);
    }

    // ---------------- TR3.2 distanceBetween 方向语义 ----------------
    @Test(timeout = 60000)
    public void tr32_distanceBetweenDirectionSemantics() {
        Assert.assertEquals("TR3.2 A→C 距离=2", 2, view.distanceBetween(UIDS[0], UIDS[2], DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 A→H 距离=7", 7, view.distanceBetween(UIDS[0], UIDS[7], DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 A→B 距离=1", 1, view.distanceBetween(UIDS[0], UIDS[1], DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 反向 H→A 不存在 = -1", -1,
                view.distanceBetween(UIDS[7], UIDS[0], DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 目标不存在 = -1", -1,
                view.distanceBetween(UIDS[0], 999999L, DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 源不存在 = -1", -1,
                view.distanceBetween(0L, UIDS[0], DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.2 自身距离不存在 = -1", -1,
                view.distanceBetween(UIDS[0], UIDS[0], DOC, PARENT, LEVEL));
    }

    // ---------------- TR3.3 topKNeighbors TOPK 铁律 ----------------
    @Test(timeout = 60000)
    public void tr33_topKNeighborsTopkInvariant() {
        long target = UIDS[4]; // E
        List<Neighbor> k3 = view.topKNeighbors(target, DOC, PARENT, LEVEL, 3);
        Assert.assertEquals("TR3.3 K=3 结果 3 条", 3, k3.size());
        Assert.assertEquals("TR3.3 K=3 序列（d↑ 且同 d R 先于 L）",
                new ArrayList<>(Arrays.asList(UIDS[5] + ":R:1", UIDS[3] + ":L:1", UIDS[6] + ":R:2")),
                listKeys(k3));
        int prev = 0;
        Set<String> uidDir = new HashSet<>();
        for (Neighbor n : k3) {
            Assert.assertTrue("TR3.3 d 非递减: " + n.distance, n.distance >= prev);
            prev = n.distance;
            Assert.assertTrue("TR3.3 (uid,dir) 去重: " + n.uid + ":" + n.dir,
                    uidDir.add(n.uid + ":" + n.dir));
        }

        List<Neighbor> k2 = view.topKNeighbors(target, DOC, PARENT, LEVEL, 2);
        Assert.assertEquals("TR3.3 满 K=2 立即返回前 2",
                new ArrayList<>(Arrays.asList(UIDS[5] + ":R:1", UIDS[3] + ":L:1")), listKeys(k2));

        Assert.assertTrue("TR3.3 K=0 空", view.topKNeighbors(target, DOC, PARENT, LEVEL, 0).isEmpty());
        Assert.assertTrue("TR3.3 K=-1 空", view.topKNeighbors(target, DOC, PARENT, LEVEL, -1).isEmpty());
        Assert.assertTrue("TR3.3 miss 空", view.topKNeighbors(0L, DOC, PARENT, LEVEL, 5).isEmpty());

        List<Neighbor> all = view.topKNeighbors(target, DOC, PARENT, LEVEL, 100);
        Assert.assertEquals("TR3.3 K 超总量 → 全 7", 7, all.size());
        assertNonDecreasing(all);
    }

    // ---------------- TR3.4 多段「新段优先」覆盖 + fingerprint 随段序变化 + close 幂等 ----------------
    @Test(timeout = 60000)
    public void tr34_multiSegmentNewestWinsAndCloseIdempotent() throws IOException {
        File wnRoot = tmp.newFolder("p51-t3-wn-" + UUID.randomUUID().toString().replace("-", ""));
        File fa = tmp.newFolder("p51-t3-fa-" + UUID.randomUUID().toString().replace("-", ""));
        File fb = tmp.newFolder("p51-t3-fb-" + UUID.randomUUID().toString().replace("-", ""));
        long[] oldSeq = {11L, 12L, 13L, 14L, 15L};
        long[] newSeq = {11L, 12L, 99L};

        FulltextStore a = new FulltextStore(fa.getAbsolutePath());
        FulltextStore b = new FulltextStore(fb.getAbsolutePath());
        try {
            insertSeq(a, DOC, PARENT, LEVEL, oldSeq);
            a.buildDistForSeq(DOC, PARENT, LEVEL, toList(oldSeq));
            new WordNetExporter().export(a, wnRoot.getAbsolutePath(), 1L);

            insertSeq(b, DOC, PARENT, LEVEL, newSeq);
            b.buildDistForSeq(DOC, PARENT, LEVEL, toList(newSeq));
            new WordNetExporter().export(b, wnRoot.getAbsolutePath(), 2L);
        } finally {
            a.close();
            b.close();
        }

        try (WordNetSegmentView newest2 = WordNetSegmentView.open(wnRoot.getAbsolutePath(), new long[]{1L, 2L})) {
            Assert.assertEquals("TR3.4 段数=2", 2, newest2.segmentCount());
            Assert.assertArrayEquals("TR3.4 段序快照（旧→新）", new long[]{1L, 2L}, newest2.segIdsOldToNew());
            Assert.assertEquals("TR3.4 新段优先：取 seg2 邻接 {12,99}",
                    new HashSet<>(Arrays.asList(12L + ":R:1", 99L + ":R:2")),
                    keys(newest2.neighborsWithin(11L, DOC, PARENT, LEVEL, 1024)));
            Assert.assertEquals("TR3.4 seg2 邻接数 2", 2,
                    newest2.neighborsWithin(11L, DOC, PARENT, LEVEL, 1024).size());
        }

        try (WordNetSegmentView newest1 = WordNetSegmentView.open(wnRoot.getAbsolutePath(), new long[]{2L, 1L})) {
            Assert.assertEquals("TR3.4 段序反转后取 seg1 邻接 {12,13,14,15}",
                    new HashSet<>(Arrays.asList(12L + ":R:1", 13L + ":R:2", 14L + ":R:3", 15L + ":R:4")),
                    keys(newest1.neighborsWithin(11L, DOC, PARENT, LEVEL, 1024)));

            WordNetSegmentView vA = WordNetSegmentView.open(wnRoot.getAbsolutePath(), new long[]{1L, 2L});
            try {
                Assert.assertNotEquals("TR3.4 fingerprint 随段序变化", vA.fingerprint(), newest1.fingerprint());
                Assert.assertTrue("TR3.4 fingerprint 64-hex", vA.fingerprint().matches("[0-9a-f]{64}"));
            } finally {
                vA.close();
            }
        }

        WordNetSegmentView idem = WordNetSegmentView.open(wnRoot.getAbsolutePath(), new long[]{1L, 2L});
        idem.close();
        idem.close(); // 幂等，不抛
        int thrown = 0;
        thrown += expectIae(() -> idem.neighborsWithin(11L, DOC, PARENT, LEVEL, 5));
        thrown += expectIae(() -> idem.distanceBetween(11L, 12L, DOC, PARENT, LEVEL));
        thrown += expectIae(() -> idem.topKNeighbors(11L, DOC, PARENT, LEVEL, 3));
        thrown += expectIae(() -> idem.positions(11L, DOC, PARENT, LEVEL));
        Assert.assertEquals("TR3.4 close 后四类查询均 IAE", 4, thrown);
    }

    // ---------------- helpers ----------------
    private static void insertSeq(FulltextStore s, long docId, long parentId, int level, long[] uids)
            throws IOException {
        for (int i = 0; i < uids.length; i++) {
            s.insert(new TuRow(uids[i], docId, parentId, level, i, i * 2, i * 2 + 1, "u" + uids[i]));
        }
    }

    private static List<Long> toList(long[] a) {
        List<Long> out = new ArrayList<>(a.length);
        for (long x : a) {
            out.add(x);
        }
        return out;
    }

    private static Set<String> keys(List<Neighbor> ns) {
        Set<String> out = new LinkedHashSet<>();
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private static List<String> listKeys(List<Neighbor> ns) {
        List<String> out = new ArrayList<>(ns.size());
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private static void assertNonDecreasing(List<Neighbor> ns) {
        int prev = 0;
        for (Neighbor n : ns) {
            Assert.assertTrue("d 非递减: " + n.distance, n.distance >= prev);
            prev = n.distance;
        }
    }

    private interface Thrower {
        void run();
    }

    private static int expectIae(Thrower t) {
        try {
            t.run();
        } catch (IllegalArgumentException e) {
            return 1;
        }
        Assert.fail("期望 IllegalArgumentException 但未抛出");
        return 0;
    }
}
