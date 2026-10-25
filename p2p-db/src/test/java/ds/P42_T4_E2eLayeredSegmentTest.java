package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.layered.LayeredSnapshot;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P42 T4：E2E 零上游依赖 + 冷启动零重建 + 跨进程快照指纹，3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link FulltextStore} → 导出 → {@code close()} → 重开视图逐条等价（零上游依赖）
 * <br>TR4.2 冷启动 {@code open} P95 ≤ 200ms（R10 零重建）+ 段文件自描述
 * <br>TR4.3 跨进程 {@code LayeredSnapshot.fingerprint()} 64-hex 一致（子 JVM spawn）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P42_T4_E2eLayeredSegmentTest {

    private static final long SEG = 10L;

    private File root;

    @Before
    public void setUp() {
        root = new File(System.getProperty("java.io.tmpdir"), "p42t4-" + System.nanoTime());
        root.mkdirs();
    }

    @After
    public void tearDown() {
        deleteRecursive(root);
    }

    /* -------- TR4.1 零上游依赖 -------- */
    @Test(timeout = 120000)
    public void tr41_zeroUpstreamDependencyAfterClose() throws Exception {
        FulltextStore ft = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        ft.ingestDoc(1L, "hello world trae");
        ft.ingestDoc(2L, "hello trae engine");
        ft.ingestDoc(3L, "world engine hello");

        Map<Long, int[]> truthLen = new HashMap<>();
        Map<Long, int[]> truthNt = new HashMap<>();
        Set<Long> docKeys = ft.weightStoreAllDocKeysForTest();
        for (Long d : docKeys) {
            FulltextStore.WeightDocStatRow row = ft.getDocStatForTest(d);
            truthLen.put(d, row.docLen.clone());
            truthNt.put(d, row.nTerms.clone());
        }
        Map<Long, double[]> truthIdf = new HashMap<>();
        Map<Long, byte[]> truthPayload = new HashMap<>();
        Set<Long> termKeys = ft.weightStoreAllTermKeys();
        for (Long u : termKeys) {
            FulltextStore.WeightTermStatRow row = ft.getTermStatForTest(u);
            truthIdf.put(u, new double[]{row.idf});
            truthPayload.put(u, row.payload.clone());
        }
        Map<Long, Integer> truthTf = ft.weightStoreAllTfEntries();

        LayeredSnapshot snap = new LayeredIndexExporter().export(ft, store, SEG);
        ft.close(); // 证明视图不依赖上游 FulltextStore / WeightStore

        DsIndexSegmentStore reopened = new DsIndexSegmentStore(root.getAbsolutePath());
        try (LayeredInvertedView view = LayeredInvertedView.open(reopened, new long[]{SEG}); DsIndexSegmentReader r = reopened.openReader(SEG)) {
            Assert.assertEquals(docKeys.size(), snap.docCount);
            Assert.assertEquals(termKeys.size(), snap.termCount);
            Assert.assertEquals(truthTf.size(), snap.tfCount);
            for (Long d : docKeys) {
                for (int lv = 0; lv < 6; lv++) {
                    Assert.assertEquals("docLen doc" + d + " lv" + lv, truthLen.get(d)[lv], view.docLen(d, lv));
                    Assert.assertEquals("nTerms doc" + d + " lv" + lv, truthNt.get(d)[lv], view.nTerms(d, lv));
                }
            }
            for (Long u : termKeys) {
                Assert.assertEquals("idf uid" + u, 0, Double.compare(truthIdf.get(u)[0], view.idf(u)));
                Assert.assertArrayEquals("payload uid" + u, truthPayload.get(u), view.termPayload(u));
            }
            // tf 逐条与内存真值全等（经重开 reader）
            for (Map.Entry<Long, Integer> e : truthTf.entrySet()) {
                Assert.assertEquals("tf key=" + e.getKey(), (int) e.getValue(), r.getTf(e.getKey()));
            }
            // 视图 tf 绑定与 reader 一致（网格抽样，含 level 维）
            for (Long u : termKeys) {
                for (long d : new long[]{1L, 2L, 3L}) {
                    for (int lv = 0; lv < 6; lv++) {
                        long key = TermDictStore.FarmHash64.mix4(u, d, d, lv);
                        Assert.assertEquals("view.tf vs reader.tf uid" + u + " doc" + d + " lv" + lv,
                                r.getTf(key), view.tf(u, d, d, lv));
                    }
                }
            }
        }
    }

    /* -------- TR4.2 冷启动零重建 -------- */
    @Test(timeout = 120000)
    public void tr42_coldStartOpenP95AndSelfDescribingSegment() throws Exception {
        // 段文件自描述：不借助 FulltextStore，直接由 writer 落盘后裸开视图
        DsIndexSegmentStore store = new DsIndexSegmentStore(root.getAbsolutePath());
        DsIndexSegmentWriter w = new DsIndexSegmentWriter(store.segmentFile(SEG));
        for (int i = 0; i < 3000; i++) {
            w.addDocStat(1000 + i, six(i + 1), six(3));
            w.addTermStat(2000 + i, 1.0 + i * 1e-3, new byte[]{(byte) (i & 0xFF)});
            w.addTf(3000 + i, 1 + (i % 7));
        }
        DsIndexSegment s = w.finish();
        store.append(SEG, s);

        for (int i = 0; i < 5; i++) {
            LayeredInvertedView.open(store, new long[]{SEG}).close();
        }
        int n = 25;
        long[] dur = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            LayeredInvertedView v = LayeredInvertedView.open(store, new long[]{SEG});
            dur[i] = System.nanoTime() - t0;
            v.close();
        }
        Arrays.sort(dur);
        long p95Ms = dur[(int) Math.ceil(0.95 * n) - 1] / 1_000_000L;
        Assert.assertTrue("冷启动 open P95 应 ≤ 200ms, got " + p95Ms + "ms", p95Ms <= 200L);

        try (LayeredInvertedView view = LayeredInvertedView.open(store, new long[]{SEG})) {
            Assert.assertEquals(3000L, store.load(SEG).docCount);
            Assert.assertEquals("docId 1000 的 docLen[0] = i+1 = 1", 1, view.docLen(1000L, 0));
            Assert.assertEquals("docId 3999 的 docLen[0] = 3000", 3000, view.docLen(3999L, 0));
            Assert.assertTrue(view.hasDoc(3999L));
        }
    }

    /* -------- TR4.3 跨进程快照指纹 -------- */
    @Test(timeout = 120000)
    public void tr43_crossProcessSnapshotFingerprint() throws Exception {
        LayeredSnapshot local = new LayeredSnapshot(10L, 3L, 9L, 17L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        String localFp = local.fingerprint();
        Assert.assertEquals(64, localFp.length());

        String[] child = runHelperTwice("10", "3", "9", "17",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        Assert.assertEquals("子进程0 fingerprint 一致", localFp, child[0]);
        Assert.assertEquals("子进程1 fingerprint 一致", localFp, child[1]);
    }

    /* -------- helpers -------- */
    private static int[] six(int v) {
        return new int[]{v, 0, 0, 0, 0, 0};
    }

    private static String[] runHelperTwice(String segId, String doc, String term, String tf, String fp)
            throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String[] outputs = new String[2];
        for (int i = 0; i < 2; i++) {
            ProcessBuilder pb = new ProcessBuilder(javaExe, "-Xmx512m", "-Dfile.encoding=UTF-8", "-cp", classpath,
                    P42_T4_E2eLayeredSegmentTest.class.getName(), segId, doc, term, tf, fp);
            pb.environment().remove("JAVA_TOOL_OPTIONS");
            pb.environment().remove("_JAVA_OPTIONS");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String out = "";
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (line.startsWith("Picked up ") || t.isEmpty()) {
                    continue;
                }
                out = t;
                break;
            }
            p.waitFor(20, TimeUnit.SECONDS);
            if (p.isAlive()) {
                p.destroyForcibly();
            }
            outputs[i] = out;
        }
        return outputs;
    }

    /**
     * 子 JVM 入口：按参数构造快照 → 打印 fingerprint（或 ERROR: ...）。
     */
    public static void main(String[] args) {
        try {
            LayeredSnapshot snap = new LayeredSnapshot(Long.parseLong(args[0]), Long.parseLong(args[1]),
                    Long.parseLong(args[2]), Long.parseLong(args[3]), args[4]);
            System.out.println(snap.fingerprint());
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
