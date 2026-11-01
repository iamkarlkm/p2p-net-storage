package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.PositionSpan;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;
import com.q3lives.ds.database.fulltext.wordnet.WordNetExporter;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSegmentView;
import com.q3lives.ds.database.fulltext.wordnet.WordNetSnapshot;

/**
 * P51 T4：E2E + 跨进程 + R12 等价性（4 TR）。
 *
 * <p>
 * TR4.1 真实 {@code FulltextStore} 构建词网 → export → {@code close()} → 重开 view
 * 后逐条与导出前内存真值全等（零上游依赖）；TR4.2 冷启动 {@code open} P95 ≤ 200ms + 段自描述（无上游扫描）；TR4.3
 * 跨进程 {@code fingerprint()} 64-hex 一致（子 JVM spawn）；TR4.4 R12 等价性（view 三 API ≡
 * 内存 {@code FulltextStore} 同 API）。
 */
public class P51_T4_E2eCrossSegmentWordNetTest {

    private static final long DOC_A = 301L;
    private static final long DOC_B = 302L;
    private static final String TEXT_A = "跨段词网聚合导出订阅推送服务稳定运行高质量内容分发";
    private static final String TEXT_B = "服务端订阅数据同步分布式推送协议实现稳定可靠一致性";
    private static final int OPEN_ITER = 20;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File rootDir;
    private FulltextStore store;
    private TermDictStore td;
    private WordNetSnapshot snap;
    private boolean storeClosed;

    @Before
    public void before() throws IOException {
        rootDir = tmp.newFolder("p51-t4-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", rootDir.getAbsolutePath());
        store = new FulltextStore(rootDir.getAbsolutePath());
        td = store.getTermDict();
        store.ingestDoc(DOC_A, TEXT_A);
        store.ingestDoc(DOC_B, TEXT_B);
        snap = new WordNetExporter().export(store, rootDir.getAbsolutePath(), 30L);
    }

    @After
    public void after() {
        if (!storeClosed) {
            try {
                store.close();
            } catch (Exception ignore) {
                // 关闭失败不阻塞用例
            }
        }
    }

    // ---------------- TR4.1 导出 → close → 重开 view 逐条全等（零上游依赖） ----------------
    @Test(timeout = 120000)
    public void tr41_exportThenCloseThenReopenViewEquals() throws IOException {
        Truth truth = collectTruth(store, td);
        Assert.assertTrue("TR4.1 语料须产生邻接真值", truth.neighborTotal > 0);
        Assert.assertTrue("TR4.1 语料须产生 positions 真值", truth.posTotal > 0);
        store.close();
        storeClosed = true;

        try (WordNetSegmentView view = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{30L})) {
            int checkedAdj = 0;
            for (AdjTruth a : truth.adj) {
                Set<String> seg = toKeySet(view.neighborsWithin(a.srcUid, a.docId, a.parentId, a.level,
                        FulltextStore.DIST_MAX));
                Assert.assertEquals("TR4.1 邻接全等 doc=" + a.docId + " p=" + a.parentId + " src=" + a.srcUid,
                        a.neighbors, seg);
                checkedAdj++;
            }
            Assert.assertTrue("TR4.1 至少校验 1 处邻接", checkedAdj > 0);

            int checkedPos = 0;
            for (PosTruth p : truth.pos) {
                List<String> seg = toKeyList(view.positions(p.uid, p.docId, p.parentId, p.level));
                Assert.assertEquals("TR4.1 positions 全等 uid=" + p.uid + " doc=" + p.docId, p.spans, seg);
                if (!p.spans.isEmpty()) {
                    checkedPos++;
                }
            }
            Assert.assertTrue("TR4.1 至少校验 1 处非空 positions", checkedPos > 0);
            Assert.assertEquals("TR4.1 段数=1", 1, view.segmentCount());
        }
    }

    // ---------------- TR4.2 冷启动 open P95 ≤ 200ms + 段自描述 ----------------
    @Test(timeout = 120000)
    public void tr42_coldOpenP95AndSelfDescribing() throws IOException {
        // 段自描述：把 wordnet/ 平铺复制到全新空目录后仍可零依赖打开
        File copyRoot = tmp.newFolder("p51-t4-copy-" + UUID.randomUUID().toString().replace("-", ""));
        File srcWordnet = new File(rootDir, "wordnet");
        File dstWordnet = new File(copyRoot, "wordnet");
        Assert.assertTrue("TR4.2 源 wordnet 目录存在", srcWordnet.isDirectory());
        Assert.assertTrue("TR4.2 目标 wordnet 目录创建", dstWordnet.mkdirs() || dstWordnet.isDirectory());
        File[] segFiles = srcWordnet.listFiles();
        Assert.assertNotNull("TR4.2 源段文件列表非 null", segFiles);
        for (File f : segFiles) {
            Files.copy(f.toPath(), new File(dstWordnet, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        String expectFingerprint;
        try (WordNetSegmentView v = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{30L})) {
            expectFingerprint = v.fingerprint();
        }

        long[] samples = new long[OPEN_ITER];
        for (int i = 0; i < OPEN_ITER; i++) {
            long t0 = System.nanoTime();
            try (WordNetSegmentView v = WordNetSegmentView.open(copyRoot.getAbsolutePath(), new long[]{30L})) {
                Assert.assertEquals("TR4.2 自描述视图段数=1", 1, v.segmentCount());
                if (i == OPEN_ITER - 1) {
                    Assert.assertEquals("TR4.2 自描述副本指纹一致", expectFingerprint, v.fingerprint());
                }
            }
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);
        int p95Idx = (int) Math.ceil(OPEN_ITER * 0.95) - 1;
        if (p95Idx < 0) {
            p95Idx = 0;
        }
        if (p95Idx >= OPEN_ITER) {
            p95Idx = OPEN_ITER - 1;
        }
        long p95Ms = samples[p95Idx] / 1_000_000L;
        Assert.assertTrue("TR4.2 冷启动 open P95 ≤ 200ms: actual=" + p95Ms + "ms", p95Ms <= 200L);
    }

    // ---------------- TR4.3 跨进程 fingerprint 64-hex 一致（子 JVM spawn） ----------------
    @Test(timeout = 120000)
    public void tr43_crossProcessFingerprintConsistent() throws Exception {
        String inProc = snap.fingerprint();
        Assert.assertTrue("TR4.3 进程内指纹 64-hex", inProc.matches("[0-9a-f]{64}"));

        String javaBin = new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
        String cp = System.getProperty("surefire.test.class.path");
        if (cp == null || cp.isEmpty()) {
            cp = System.getProperty("java.class.path");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add(P51_T4_E2eCrossSegmentWordNetTest.class.getName());
        cmd.add("fingerprint");
        cmd.add(String.valueOf(snap.segId));
        cmd.add(String.valueOf(snap.ctxCount));
        cmd.add(String.valueOf(snap.adjCount));
        cmd.add(String.valueOf(snap.posCount));
        cmd.add(snap.segmentFingerprint);

        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        Assert.assertTrue("TR4.3 子 JVM 需在 60s 内结束", done);
        Assert.assertEquals("TR4.3 子 JVM exit=0, out=" + sb, 0, p.exitValue());

        // 子 JVM 启动横幅（如 "Picked up JAVA_TOOL_OPTIONS: ..."）会混入输出，只取 64-hex 行。
        String childOut = null;
        for (String line : sb.toString().split("\n")) {
            String t = line.trim();
            if (t.matches("[0-9a-f]{64}")) {
                childOut = t;
                break;
            }
        }
        Assert.assertNotNull("TR4.3 子 JVM 未输出 64-hex 指纹, out=" + sb, childOut);
        Assert.assertEquals("TR4.3 跨进程指纹一致, child=" + childOut, inProc, childOut);
    }

    // ---------------- TR4.4 R12 等价性：view 三 API ≡ FulltextStore 同 API ----------------
    @Test(timeout = 120000)
    public void tr44_r12EquivalenceViewVersusMemory() throws IOException {
        int ctxChecked = 0;
        int srcChecked = 0;
        int pairChecked = 0;
        try (WordNetSegmentView view = WordNetSegmentView.open(rootDir.getAbsolutePath(), new long[]{30L})) {
            for (long[] ctx : store.wordNetAllContextKeys()) {
                long docId = ctx[0];
                long parentId = ctx[1];
                int level = (int) ctx[2];
                ctxChecked++;
                Set<Long> srcs = new LinkedHashSet<>();
                for (TuRow r : store.selectByParentLevel(docId, parentId, level)) {
                    srcs.add(r.unit_id());
                }
                for (Long sLong : srcs) {
                    long srcUid = sLong.longValue();
                    String text = td.toText(srcUid);
                    if (text == null || text.isEmpty()) {
                        continue;
                    }
                    srcChecked++;

                    // ① neighborsWithin ≡
                    Set<String> mem = toKeySet(store.neighborsWithin(text, docId, parentId, level,
                            FulltextStore.DIST_MAX));
                    Set<String> seg = toKeySet(view.neighborsWithin(srcUid, docId, parentId, level,
                            FulltextStore.DIST_MAX));
                    Assert.assertEquals("TR4.4 neighborsWithin 等价 src=" + srcUid + " ctx=" + Arrays.toString(ctx),
                            mem, seg);

                    // ③ topKNeighbors ≡（K 覆盖 1..邻居数）
                    int k = mem.size() + 2;
                    List<String> memTop = listKeys(store.topKNeighbors(text, docId, parentId, level, k));
                    List<String> segTop = listKeys(view.topKNeighbors(srcUid, docId, parentId, level, k));
                    Assert.assertEquals("TR4.4 topKNeighbors 等价 src=" + srcUid + " K=" + k, memTop, segTop);
                }

                // ② distanceBetween ≡：以真值邻接（dir=R, uid=r）逐对验 L→R 正距离
                for (TuRow lRow : store.selectByParentLevel(docId, parentId, level)) {
                    String lText = td.toText(lRow.unit_id());
                    if (lText == null || lText.isEmpty()) {
                        continue;
                    }
                    Set<String> mem = toKeySet(store.neighborsWithin(lText, docId, parentId, level,
                            FulltextStore.DIST_MAX));
                    for (String nk : mem) {
                        String[] parts = nk.split(":");
                        if (!"R".equals(parts[1])) {
                            continue;
                        }
                        long rUid = Long.parseLong(parts[0]);
                        int memD = store.distanceBetween(lText, td.toText(rUid), docId, parentId, level);
                        int segD = view.distanceBetween(lRow.unit_id(), rUid, docId, parentId, level);
                        Assert.assertEquals("TR4.4 distanceBetween 等价 l=" + lRow.unit_id() + " r=" + rUid,
                                memD, segD);
                        pairChecked++;
                    }
                }
            }
        }
        Assert.assertTrue("TR4.4 至少校验 1 个上下文", ctxChecked > 0);
        Assert.assertTrue("TR4.4 至少校验 1 个 srcUid", srcChecked > 0);
        Assert.assertTrue("TR4.4 至少校验 1 个 L→R 距离对", pairChecked > 0);
    }

    // ---------------- 子 JVM 入口：打印不可变快照指纹（TR4.3 跨进程对照） ----------------
    /**
     * 子 JVM 入口：由 5 个字段重建 {@link WordNetSnapshot} 并打印其 {@code fingerprint()}。
     */
    public static void main(String[] args) {
        if (args.length < 6 || !"fingerprint".equals(args[0])) {
            System.err.println("usage: fingerprint <segId> <ctxCount> <adjCount> <posCount> <segFp>");
            System.exit(2);
        }
        WordNetSnapshot s = new WordNetSnapshot(Long.parseLong(args[1]), Long.parseLong(args[2]),
                Long.parseLong(args[3]), Long.parseLong(args[4]), args[5]);
        System.out.println(s.fingerprint());
    }

    // ---------------- helpers ----------------
    private static Truth collectTruth(FulltextStore ft, TermDictStore termDict) throws IOException {
        Truth t = new Truth();
        for (long[] ctx : ft.wordNetAllContextKeys()) {
            long docId = ctx[0];
            long parentId = ctx[1];
            int level = (int) ctx[2];
            Set<Long> srcs = new LinkedHashSet<>();
            for (TuRow r : ft.selectByParentLevel(docId, parentId, level)) {
                srcs.add(r.unit_id());
            }
            for (Long sLong : srcs) {
                long srcUid = sLong.longValue();
                String text = termDict.toText(srcUid);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                Set<String> ns = toKeySet(ft.neighborsWithin(text, docId, parentId, level, FulltextStore.DIST_MAX));
                if (ns.isEmpty()) {
                    continue;
                }
                t.adj.add(new AdjTruth(docId, parentId, level, srcUid, ns));
                t.neighborTotal += ns.size();
            }
            for (Long uLong : srcs) {
                List<PositionSpan> spans = ft.getTermPositionsForTest(uLong.longValue(), docId, parentId, level);
                t.pos.add(new PosTruth(docId, parentId, level, uLong.longValue(), toKeyList(spans)));
                t.posTotal += spans.size();
            }
        }
        return t;
    }

    private static Set<String> toKeySet(Set<Neighbor> ns) {
        Set<String> out = new HashSet<>();
        for (Neighbor n : ns) {
            out.add(n.uid + ":" + n.dir + ":" + n.distance);
        }
        return out;
    }

    private static Set<String> toKeySet(List<Neighbor> ns) {
        Set<String> out = new HashSet<>();
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

    private static List<String> toKeyList(List<PositionSpan> spans) {
        List<String> out = new ArrayList<>(spans.size());
        for (PositionSpan s : spans) {
            out.add(s.start + "-" + s.end);
        }
        return out;
    }

    private static final class AdjTruth {

        final long docId;
        final long parentId;
        final int level;
        final long srcUid;
        final Set<String> neighbors;

        AdjTruth(long docId, long parentId, int level, long srcUid, Set<String> neighbors) {
            this.docId = docId;
            this.parentId = parentId;
            this.level = level;
            this.srcUid = srcUid;
            this.neighbors = neighbors;
        }
    }

    private static final class PosTruth {

        final long docId;
        final long parentId;
        final int level;
        final long uid;
        final List<String> spans;

        PosTruth(long docId, long parentId, int level, long uid, List<String> spans) {
            this.docId = docId;
            this.parentId = parentId;
            this.level = level;
            this.uid = uid;
            this.spans = spans;
        }
    }

    private static final class Truth {

        final List<AdjTruth> adj = new ArrayList<>();
        final List<PosTruth> pos = new ArrayList<>();
        long neighborTotal;
        long posTotal;
    }
}
