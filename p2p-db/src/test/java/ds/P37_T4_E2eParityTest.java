package ds;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runners.MethodSorters;

import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore.FarmHash64;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegment;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentReader;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentStore;
import com.q3lives.ds.database.fulltext.segment.DsIndexSegmentWriter;

/**
 * P37 T4：Inproc E2E 冷启动 parity，3 TR 0F0E。
 *
 * <p>
 * 同源数据（P22 {@code FulltextStore} 真实 ingest）→ 以 P22 公共探针为 oracle 喂入段 →
 * 断言段读取与 WeightStore 直读 **逐条全等**（docStat 6 level / termStat idf bit 级 / tf）。
 *
 * <p>
 * TR4.1 docStat（docLen[6]+nTerms[6]）+ termStat（idf double bit 级 + payload）逐条全等
 * <br>TR4.2 tf（mix4(uid,doc,parent,lv) 键）逐条全等 + miss=0
 * <br>TR4.3 零重建（隔离目录仅凭段文件读全量）+ 二次 open 全等 + 冷启动 P95 + 压缩收益
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class P37_T4_E2eParityTest {

    private static final String[] TEXTS = {
            "alpha beta gamma delta epsilon",
            "beta gamma zeta eta theta",
            "gamma delta iota kappa lambda mu nu"
    };

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();
    private FulltextStore store;
    private DsIndexSegmentStore segStore;

    @Before
    public void setUp() throws Exception {
        File root = tmp.newFolder("p37t4-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        segStore = new DsIndexSegmentStore(tmp.newFolder("p37t4-seg").getAbsolutePath());
    }

    @After
    public void tearDown() {
        try {
            store.close();
        } catch (Exception ignored) {
        }
        try {
            segStore.close();
        } catch (Exception ignored) {
        }
    }

    /* -------- TR4.1 docStat + termStat parity -------- */

    @Test
    public void tr41_docStatAndTermStatParity() throws Exception {
        buildSegmentFromOracle();
        DsIndexSegmentReader r = segStore.openReader(1L);

        int docs = 0;
        for (long docId : docIds()) {
            FulltextStore.WeightDocStatRow oracle = store.getDocStatForTest(docId);
            FulltextStore.WeightDocStatRow got = r.getDocStat(docId);
            Assert.assertNotNull("segment miss docId=" + docId, got);
            Assert.assertArrayEquals("docLen docId=" + docId, oracle.docLen, got.docLen);
            Assert.assertArrayEquals("nTerms docId=" + docId, oracle.nTerms, got.nTerms);
            docs++;
        }
        Assert.assertTrue("至少 1 个 docStat", docs > 0);

        int terms = 0;
        for (long uid : wordUids()) {
            FulltextStore.WeightTermStatRow oracle = store.getTermStatForTest(uid);
            FulltextStore.WeightTermStatRow got = r.getTermStat(uid);
            Assert.assertNotNull("segment miss uid=" + uid, got);
            Assert.assertEquals("idf bit 级 uid=" + uid,
                    Double.doubleToLongBits(oracle.idf), Double.doubleToLongBits(got.idf));
            Assert.assertArrayEquals("payload uid=" + uid, oracle.payload, got.payload);
            terms++;
        }
        Assert.assertTrue("至少 1 个 termStat", terms > 0);
        r.close();
    }

    /* -------- TR4.2 tf parity -------- */

    @Test
    public void tr42_tfParity() throws Exception {
        buildSegmentFromOracle();
        DsIndexSegmentReader r = segStore.openReader(1L);
        List<Long> uids = wordUids();
        int hit = 0;
        for (long docId : docIds()) {
            for (int lv = 0; lv < 6; lv++) {
                long parentId = docId * 10L + lv;
                for (long uid : uids) {
                    int tfv = store.getTfForTest(uid, docId, parentId, lv);
                    long key = FarmHash64.mix4(uid, docId, parentId, (long) lv);
                    Assert.assertEquals("tf mismatch key=" + key, tfv, r.getTf(key));
                    if (tfv > 0) {
                        hit++;
                    }
                }
            }
        }
        Assert.assertTrue("至少命中 1 条 tf", hit > 0);
        Assert.assertEquals("miss=0", 0, r.getTf(-12345L));
        r.close();
    }

    /* -------- TR4.3 零重建 + 冷启动 P95 + 压缩收益 -------- */

    @Test
    public void tr43_zeroRebuildColdStartAndCompression() throws Exception {
        DsIndexSegment seg = buildSegmentFromOracle();
        File segFile = segStore.segmentFile(1L);

        // 隔离目录（无任何 P21/P22 源）→ 仅凭段文件读全量：零重建自描述
        File iso = tmp.newFolder("p37t4-iso");
        File lone = new File(iso, "lone.didx");
        Files.copy(segFile.toPath(), lone.toPath());
        DsIndexSegmentReader iso1 = DsIndexSegmentReader.open(lone);
        for (long docId : docIds()) {
            Assert.assertNotNull("iso miss docId=" + docId, iso1.getDocStat(docId));
        }
        iso1.close();

        // 二次 open 结果全等 + 冷启动 P95
        int runs = 20;
        long[] ms = new long[runs];
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            DsIndexSegmentReader rr = DsIndexSegmentReader.open(lone);
            ms[i] = (System.nanoTime() - t0) / 1_000_000L;
            Assert.assertEquals(seg.docCount, rr.docCount());
            Assert.assertEquals(seg.fingerprint(), rr.segment().fingerprint());
            rr.close();
        }
        Arrays.sort(ms);
        long p95 = ms[(int) Math.floor(runs * 0.95) - 1];
        Assert.assertTrue("cold-start open P95 <= 200ms, actual=" + p95 + "ms", p95 <= 200);

        // 压缩收益：大 compressible payload 段体积 < 未压缩等价（头 + termStat 列 + raw + 逐块 5B 帧头）
        File cSeg = new File(iso, "c.didx");
        DsIndexSegmentWriter cw = new DsIndexSegmentWriter(cSeg);
        byte[] fat = new byte[20000];
        Arrays.fill(fat, (byte) 'x');
        cw.addTermStat(1L, 1.0, fat);
        DsIndexSegment c = cw.finish();
        long blocks = (fat.length + 4095L) / 4096L;
        long rawEquiv = DsIndexSegment.HEADER_SIZE + c.termStatLen + fat.length + 5L * blocks;
        Assert.assertTrue("段体积应小于未压缩等价: seg=" + cSeg.length() + " raw=" + rawEquiv,
                cSeg.length() < rawEquiv);
    }

    /* -------- helpers -------- */

    /** 同源 ingest + 以 P22 探针为 oracle 构建段并 append 到 store。 */
    private DsIndexSegment buildSegmentFromOracle() throws Exception {
        for (int i = 0; i < TEXTS.length; i++) {
            store.putDoc(i + 1L, TEXTS[i]);
        }
        List<Long> uids = wordUids();
        store.recomputeIdfForTerms(uids);

        DsIndexSegmentWriter w = new DsIndexSegmentWriter(segStore.segmentFile(1L));
        for (long docId : docIds()) {
            FulltextStore.WeightDocStatRow d = store.getDocStatForTest(docId);
            w.addDocStat(docId, d.docLen, d.nTerms);
        }
        for (long uid : uids) {
            FulltextStore.WeightTermStatRow t = store.getTermStatForTest(uid);
            w.addTermStat(uid, t.idf, t.payload);
        }
        for (long docId : docIds()) {
            for (int lv = 0; lv < 6; lv++) {
                long parentId = docId * 10L + lv;
                for (long uid : uids) {
                    int tfv = store.getTfForTest(uid, docId, parentId, lv);
                    if (tfv > 0) {
                        w.addTf(FarmHash64.mix4(uid, docId, parentId, (long) lv), tfv);
                    }
                }
            }
        }
        DsIndexSegment seg = w.finish();
        segStore.append(1L, seg);
        Assert.assertTrue("docStat non-empty", seg.docCount > 0);
        return seg;
    }

    private List<Long> docIds() throws Exception {
        List<Long> ids = new ArrayList<>(store.weightStoreAllDocKeysForTest());
        Collections.sort(ids);
        return ids;
    }

    /** level-1 词级 uid 集合（文本空白切词）。 */
    private List<Long> wordUids() throws Exception {
        List<Long> uids = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (String t : TEXTS) {
            for (String word : t.split("\\s+")) {
                long u = store.termDictToUidForTest(word, false);
                if (u > 0L && seen.add(u)) {
                    uids.add(u);
                }
            }
        }
        Collections.sort(uids);
        return uids;
    }
}
