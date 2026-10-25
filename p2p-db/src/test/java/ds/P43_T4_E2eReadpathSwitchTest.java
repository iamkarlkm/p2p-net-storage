package ds;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.layered.LayeredIndexExporter;
import com.q3lives.ds.database.fulltext.layered.LayeredInvertedView;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;

/**
 * P43 T4：E2E + 跨实例 / 跨进程，3 TR 0F0E。
 *
 * <p>
 * TR4.1 真实 {@link FulltextStore}（独立 temp home）ingest → export → attach → search
 * 等价 + detach 后仍等价
 * <br>TR4.2 {@code close()} 后新 {@link FulltextStore} 实例 + 新视图 attach →
 * {@code searchByUidsForTest} 跨实例全等
 * <br>TR4.3 跨进程 attach 后 search 结果 fingerprint 64-hex 一致（子 JVM spawn）
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P43_T4_E2eReadpathSwitchTest {

    private static final String[] VOCAB = {"alpha", "beta", "gamma", "delta"};

    private File root;
    private FulltextStore ft;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        root = new File(System.getProperty("java.io.tmpdir"), "p43t4-" + System.nanoTime());
        root.mkdirs();
        ft = new FulltextStore(root.getAbsolutePath());
        segStore = new DsIndexSegmentStore(root.getAbsolutePath());
    }

    @After
    public void tearDown() {
        try {
            ft.close();
        } catch (Exception ignored) {
            // 清理
        }
        try {
            segStore.close();
        } catch (Exception ignored) {
            // 清理
        }
        deleteRecursive(root);
    }

    /* -------- TR4.1 E2E 读路径切换 -------- */
    @Test(timeout = 120000)
    public void tr41_e2eSwitchAndDetach() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));

        SearchOptions opts = SearchOptions.defaults();
        List<ScoredDocHit> base = ft.search("alpha beta", 10, opts);
        Assert.assertFalse(base.isEmpty());

        new LayeredIndexExporter().export(ft, segStore, 10L);
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{10L})) {
            ft.attachLayeredView(view);
            Assert.assertTrue(ft.hasLayeredView());
            assertSameHits("tr41-attached", base, ft.search("alpha beta", 10, opts));
            ft.detachLayeredView();
        }
        assertSameHits("tr41-detached", base, ft.search("alpha beta", 10, opts));
    }

    /* -------- TR4.2 跨实例 -------- */
    @Test(timeout = 120000)
    public void tr42_crossInstanceEquivalence() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));
        List<Long> qUids = uidsOf(ft);
        SearchOptions opts = SearchOptions.defaults();

        new LayeredIndexExporter().export(ft, segStore, 11L);

        List<ScoredDocHit> old;
        try (LayeredInvertedView v1 = LayeredInvertedView.open(segStore, new long[]{11L})) {
            ft.attachLayeredView(v1);
            old = ft.searchByUidsForTest(qUids, 10, opts);
            ft.detachLayeredView();
        }
        Assert.assertFalse("旧实例应召回", old.isEmpty());
        ft.close();

        FulltextStore ft2 = new FulltextStore(root.getAbsolutePath());
        DsIndexSegmentStore seg2 = new DsIndexSegmentStore(root.getAbsolutePath());
        try (LayeredInvertedView v2 = LayeredInvertedView.open(seg2, new long[]{11L})) {
            ft2.attachLayeredView(v2);
            assertEquals("tr42-cross-instance", old, ft2.searchByUidsForTest(qUids, 10, opts));
            ft2.detachLayeredView();
        } finally {
            try {
                ft2.close();
            } catch (Exception ignored) {
                // 清理
            }
            try {
                seg2.close();
            } catch (Exception ignored) {
                // 清理
            }
        }
    }

    /* -------- TR4.3 跨进程 fingerprint -------- */
    @Test(timeout = 180000)
    public void tr43_crossProcessResultFingerprint() throws Exception {
        ingestCorpus(ft);
        ft.recomputeIdfForTerms(uidsOf(ft));
        new LayeredIndexExporter().export(ft, segStore, 12L);

        String localFp;
        try (LayeredInvertedView view = LayeredInvertedView.open(segStore, new long[]{12L})) {
            ft.attachLayeredView(view);
            localFp = digest(ft.search("alpha beta", 10, SearchOptions.defaults()));
            ft.detachLayeredView();
        }
        Assert.assertEquals(64, localFp.length());

        String childFp = runChild(root.getAbsolutePath());
        Assert.assertEquals("跨进程 attach 后 search 结果 fingerprint 应一致", localFp, childFp);
    }

    /* -------- 子 JVM 入口 -------- */
    /**
     * 子进程入口：在指定 home 重建语料 → export → attach → search → 打印 64-hex fingerprint。
     */
    public static void main(String[] args) {
        try {
            File home = new File(args[0]);
            FulltextStore store = new FulltextStore(home.getAbsolutePath());
            DsIndexSegmentStore seg = new DsIndexSegmentStore(home.getAbsolutePath());
            try {
                ingestCorpus(store);
                store.recomputeIdfForTerms(uidsOf(store));
                new LayeredIndexExporter().export(store, seg, 13L);
                try (LayeredInvertedView view = LayeredInvertedView.open(seg, new long[]{13L})) {
                    store.attachLayeredView(view);
                    System.out.println(digest(store.search("alpha beta", 10, SearchOptions.defaults())));
                }
            } finally {
                try {
                    store.close();
                } catch (Exception ignored) {
                    // 清理
                }
                try {
                    seg.close();
                } catch (Exception ignored) {
                    // 清理
                }
            }
        } catch (Throwable t) {
            System.out.println("ERROR: " + t.getMessage());
        }
    }

    /* -------- helpers -------- */
    private static void ingestCorpus(FulltextStore store) throws Exception {
        store.ingestDoc(1L, "alpha beta gamma");
        store.ingestDoc(2L, "alpha beta delta");
        store.ingestDoc(3L, "beta gamma delta");
        store.ingestDoc(4L, "alpha gamma delta");
    }

    private static List<Long> uidsOf(FulltextStore store) throws Exception {
        List<Long> out = new ArrayList<>();
        for (String t : VOCAB) {
            long u = store.termDictToUidForTest(t, false);
            if (u > 0L) {
                out.add(u);
            }
        }
        return out;
    }

    private static String digest(List<ScoredDocHit> hits) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (ScoredDocHit h : hits) {
            sb.append(h.docId).append('|').append(h.parentId).append('|').append(h.level).append('|')
                    .append(h.unitId).append('|').append(h.matchedQueryTerms).append('|')
                    .append(Double.doubleToLongBits(h.finalScore)).append('|')
                    .append(Double.doubleToLongBits(h.sortScore)).append('|')
                    .append(Double.doubleToLongBits(h.rrfScore)).append('|')
                    .append(Double.doubleToLongBits(h.phraseBoostScore)).append('\n');
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : d) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static void assertEquals(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
        Assert.assertEquals(tag + " 命中数", a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            ScoredDocHit x = a.get(i);
            ScoredDocHit y = b.get(i);
            Assert.assertEquals(tag + " docId@" + i, x.docId, y.docId);
            Assert.assertEquals(tag + " unitId@" + i, x.unitId, y.unitId);
            Assert.assertEquals(tag + " finalScore@" + i,
                    Double.doubleToLongBits(x.finalScore), Double.doubleToLongBits(y.finalScore));
            Assert.assertEquals(tag + " sortScore@" + i,
                    Double.doubleToLongBits(x.sortScore), Double.doubleToLongBits(y.sortScore));
        }
    }

    private static void assertSameHits(String tag, List<ScoredDocHit> a, List<ScoredDocHit> b) {
        assertEquals(tag, a, b);
    }

    private static String runChild(String homePath) throws Exception {
        String classpath = System.getProperty("java.class.path");
        String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-Xmx512m", "-Dfile.encoding=UTF-8", "-cp", classpath,
                P43_T4_E2eReadpathSwitchTest.class.getName(), homePath + "-child");
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
        p.waitFor(60, TimeUnit.SECONDS);
        if (p.isAlive()) {
            p.destroyForcibly();
        }
        return out;
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
