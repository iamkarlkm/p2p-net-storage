package ds;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import com.q3lives.ds.database.fulltext.FulltextStore.Neighbor;
import com.q3lives.ds.database.fulltext.FulltextStore.Ranker;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.Similarity;
import com.q3lives.ds.database.fulltext.TermDictStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * P23 T5：Gatekeeper 4 TR
 *   TR5.1：P21 核心语义零破坏 + bucketAccessCounter 在 searchWithVector 路径下【绝对零 ++】
 *   TR5.2：ZeroLeak — fulltext 独立类 = 3（P23 无新增独立类）
 *   TR5.3：NG-3 零实依赖 9 CNF（P22 5 基础 + 4 向量/HNSW 库）
 *   TR5.4：Facade 非递减 ≥36 — TermDict public≥6 + FulltextStore public≥30（22+8 个 P23 探针）
 *
 * 关键约束：
 *   USER-HC-0 unit_id 纯文本；P21 TOPK bucketAccessCounter 仅 P21 topKNeighbors 可 ++；
 *   search/searchWithVector/任何打分路径绝不允许 bucketAccessCounter[d]++。
 */
public class P23_T5_GatekeeperTest {

    @Rule public TemporaryFolder tf = new TemporaryFolder();

    private File root;
    private TermDictStore td;
    private FulltextStore store;

    @Before public void setUp() throws IOException {
        File tmp = tf.newFolder("p23t5-" + UUID.randomUUID());
        root = new File(tmp, "p23t5-root");
        System.setProperty("p2p.db.home", tmp.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.termDict;
    }

    @After public void tearDown() {
        try { store.close(); } catch (Exception ignore) {}
        try { td.close(); } catch (Exception ignore) {}
        System.clearProperty("p2p.db.home");
    }

    // ---------------- TR5.1【P21 语义 + P23 HC：bucketAccessCounter 绝对零污染 searchWithVector】 ----------------
    @Test
    public void tr5_1_p21SemanticsPlusSearchVectorCounterZeroPollution() throws IOException {
        long docId = 601L;
        int lv = 1;
        long parent = docId * 10L + lv;
        String[] arr = {"A","B","C","D","E","F"};
        List<Long> uidSeq = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i++) {
            long u = td.toUid(arr[i], true);
            td.retain(u); uidSeq.add(u);
            store.insert(new TuRow(u, docId, parent, lv, i, i, i+1, arr[i]));
        }
        store.buildDistForSeq(docId, parent, lv, uidSeq);

        // (1) P21 铁律 distanceBetween A,F=5 C,E=2
        Assert.assertEquals("P21语义：A,F d=5", 5, store.distanceBetween("A","F",docId,parent,lv));
        Assert.assertEquals("P21语义：C,E d=2", 2, store.distanceBetween("C","E",docId,parent,lv));

        // (2) P21 topKNeighbors(B, K=2)：d=1(A,C) 即满足 K=2 → counter[1]=2
        Arrays.fill(store.bucketAccessCounter, 0);
        List<Neighbor> nb = store.topKNeighbors("B", docId, parent, lv, 2);
        Assert.assertEquals("P21语义 topK(B,2)=2", 2, nb.size());
        Assert.assertEquals("P21语义 counter[1]=2（A+C，d=1 即满 K=2，不扫 d≥2）", 2, store.bucketAccessCounter[1]);
        for (int d = 2; d <= 1024; d++)
            Assert.assertEquals("P21语义 counter[d≥2]=0 d="+d, 0, store.bucketAccessCounter[d]);

        // ====================== 【关键 P23 HC】searchWithVector 路径下 bucketAccessCounter 绝对 0 ======================
        // 构造 3 doc 有 term vector 以便 searchWithVectorForTest 走 R1/R2/R3 三路全量
        long d1 = 6001L, d2 = 6002L, d3 = 6003L;
        long uVec = td.toUid("SIM", true);
        float[] v1 = new float[8]; for (int i = 0; i < 8; i++) v1[i] = (i%2==0)? 1f : -1f;
        store.indexTermVector(uVec, v1);
        store.putDoc(d1, "SIM 语料 第一个 文档");
        store.putDoc(d2, "SIM 语料 第二个 文档");
        store.putDoc(d3, "SIM 语料 第三个 文档");

        SearchOptions opts = SearchOptions.defaults();
        opts.ranker = Ranker.BM25;
        opts.vectorEfSearch = 16;
        opts.vectorTopK = 5;
        opts.vectorSim = Similarity.COSINE;
        // 关键：先把 counter 清零，调用 searchWithVector（会走 R2 neighborsWithinD，但 HC 禁止 ++counter）
        Arrays.fill(store.bucketAccessCounter, 0);
        List<ScoredDocHit> hits = store.searchWithVectorForTest("SIM", null, 10, opts);
        Assert.assertFalse("P23 HC：searchWithVector 结果非空", hits.isEmpty());
        // 【铁律断言】bucketAccessCounter 1..1024 全部为 0 —— search 任何路径不许 touch
        int sumCounter = 0;
        for (int d = 1; d <= 1024; d++) sumCounter += store.bucketAccessCounter[d];
        Assert.assertEquals("P23 HC 红线：searchWithVector 路径下 bucketAccessCounter 总和必须为 0（禁止任何打分/召回/邻居路径触碰）",
            0, sumCounter);

        // 再额外验证普通 search 路径同样 0（P22 基线保持）
        Arrays.fill(store.bucketAccessCounter, 0);
        List<ScoredDocHit> hits2 = store.search("SIM 文档", 5, SearchOptions.defaults());
        Assert.assertFalse("P22 baseline search 结果非空", hits2.isEmpty());
        int sum2 = 0; for (int d = 1; d <= 1024; d++) sum2 += store.bucketAccessCounter[d];
        Assert.assertEquals("P22 baseline HC：search 路径下 bucketAccessCounter 总和=0", 0, sum2);
    }

    // ---------------- TR5.2【ZeroLeak 独立类永=3】 ----------------
    @Test
    public void tr5_2_zeroLeakIndependentClassesStillThree() throws Exception {
        Set<String> classes = P22_T5_GatekeeperTestHelper.scanTopLevelClassesInPackage("com.q3lives.ds.database.fulltext");
        final String PKG = "com.q3lives.ds.database.fulltext";
        Set<String> topLevel = new HashSet<>();
        for (String cn : classes) {
            if (cn.contains("$")) continue;
            if (!cn.startsWith(PKG + ".")) continue;
            String tail = cn.substring(PKG.length() + 1);
            if (tail.contains(".")) continue; // 跳过 cluster / handler 等子包类
            topLevel.add(cn);
        }
        Set<String> expected = new HashSet<>(Arrays.asList(
            "com.q3lives.ds.database.fulltext.TermDictStore",
            "com.q3lives.ds.database.fulltext.FulltextStore",
            "com.q3lives.ds.database.fulltext.FulltextSegmenter"
        ));
        Assert.assertEquals("TR5.2 ZeroLeak 独立类数仍=3（P23 零新增独立类）", 3, topLevel.size());
        Assert.assertEquals("TR5.2 ZeroLeak 集合精确全等", expected, topLevel);
    }

    // ---------------- TR5.3【NG-3 零实依赖 9 CNF】5 基础 + 4 HNSW/向量库 ----------------
    @Test
    public void tr5_3_ng3NineCnfAllClassNotFound() {
        String[] forbidden = {
            // P22 基础 5
            "com.huaban.analysis.jieba.JiebaSegmenter",
            "org.wltea.analyzer.lucene.IKAnalyzer",
            "com.hankcs.hanlp.HanLP",
            "spacy.Spacy",
            "org.apache.lucene.search.similarities.BM25Similarity",
            // P23 加 4：HNSW/向量 经典实现
            "org.apache.lucene.util.hnsw.HnswGraph",          // Lucene HNSW
            "com.github.jelmerk.knn.hnsw.HnswIndex",          // jelmerk knn
            "org.nd4j.linalg.factory.Nd4j",                   // ND4J tensor
            "org.apache.commons.math3.ml.distance.EuclideanDistance"  // commons-math
        };
        int cnfCount = 0;
        List<String> failed = new ArrayList<>();
        for (String cn : forbidden) {
            try {
                Class.forName(cn);
                failed.add(cn + " 被加载（应为 CNF）");
            } catch (ClassNotFoundException ok) {
                cnfCount++;
            } catch (Throwable t) {
                failed.add(cn + " 抛 " + t.getClass().getSimpleName() + "（仅允许 CNF，NoClassDefFound=隐式泄漏）");
            }
        }
        Assert.assertTrue("TR5.3 NG-3 失败明细: " + failed, failed.isEmpty());
        Assert.assertEquals("TR5.3 NG-3 9 类全 CNF（零实依赖单调递增 5→9）", 9, cnfCount);
    }

    // ---------------- TR5.4【Facade≥36】TermDict≥6 + Fulltext≥30 非递减 ----------------
    @Test
    public void tr5_4_facadeNonDecreasingAtLeast36() throws Exception {
        Set<String> p21TermDictBaseline = new HashSet<>(Arrays.asList(
            "toUid","toText","retain","release","refCount","close"
        ));
        Set<String> p22FulltextBaseline = new HashSet<>(Arrays.asList(
            "putDoc","clearDoc","ingestDoc","insert","deleteByPk","selectByParentLevel",
            "selectByDocLevel","selectByUnit","buildDistForSeq","distanceBetween","topKNeighbors",
            "neighborsWithin","close","search","recomputeIdfForTerms"
        ));
        Set<String> p23ExpectedNew = new HashSet<>(Arrays.asList(
            "getTermVectorForTest", "indexTermVector",
            "cosineSimForTest", "l2DistForTest",
            "knnSearchForTest", "getHnswMetaForTest",
            "searchWithVectorForTest"
        ));

        Method[] tdMeths = TermDictStore.class.getDeclaredMethods();
        Method[] fsMeths = FulltextStore.class.getDeclaredMethods();
        Set<String> tdPub = new HashSet<>();
        for (Method m : tdMeths) if (Modifier.isPublic(m.getModifiers())) tdPub.add(m.getName());
        Set<String> fsPub = new HashSet<>();
        for (Method m : fsMeths) if (Modifier.isPublic(m.getModifiers())) fsPub.add(m.getName());

        int tdCount = tdPub.size();
        int fsCount = fsPub.size();
        int total = tdCount + fsCount;

        // 非递减基线
        for (String name : p21TermDictBaseline)
            Assert.assertTrue("TR5.4 P21 TermDict 基线不删除: " + name, tdPub.contains(name));
        for (String name : p22FulltextBaseline)
            Assert.assertTrue("TR5.4 P22 Fulltext 基线不删除: " + name + "（当前="+fsPub+"）", fsPub.contains(name));
        // P23 新增必须全部存在
        for (String name : p23ExpectedNew)
            Assert.assertTrue("TR5.4 P23 新增 public API 必须存在: " + name + "（当前="+fsPub+"）", fsPub.contains(name));

        Assert.assertTrue("TR5.4 TermDict public≥6（实际 "+tdCount+"）", tdCount >= 6);
        Assert.assertTrue("TR5.4 Fulltext public≥30（实际 "+fsCount+"，8 个 P23 新增 + P22≥22）", fsCount >= 30);
        Assert.assertTrue("TR5.4 Facade 合计≥36（实际 "+total+"）", total >= 36);

        // 额外硬断言：Similarity / ScoredDocHit(vectorScore 11 参) 结构
        Assert.assertTrue("TR5.4 Similarity enum 至少 COSINE/L2", Similarity.values().length >= 2);
        Assert.assertTrue("TR5.4 ScoredDocHit 至少 10+11 两个构造（P22 兼容 + P23 新）",
            Arrays.stream(ScoredDocHit.class.getConstructors())
                .mapToInt(c -> c.getParameterCount())
                .filter(n -> n == 10 || n == 11)
                .count() >= 2);
    }

    // 复用 P22_T5 的 scanTopLevelClassesInPackage 逻辑（静态 helper 内部类，避免重复复制 50+ 行）
    static final class P22_T5_GatekeeperTestHelper {
        static Set<String> scanTopLevelClassesInPackage(String pkg) throws Exception {
            Set<String> out = new HashSet<>();
            String path = pkg.replace('.', '/');
            java.lang.ClassLoader cl = Thread.currentThread().getContextClassLoader();
            java.util.Enumeration<java.net.URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                java.net.URL url = resources.nextElement();
                String proto = url.getProtocol();
                if ("file".equals(proto)) {
                    java.io.File dir = new java.io.File(url.toURI());
                    collect(dir, pkg, out);
                } else if ("jar".equals(proto)) {
                    String jarPath = url.getPath();
                    if (jarPath.startsWith("file:")) jarPath = jarPath.substring(5);
                    int bang = jarPath.indexOf('!');
                    if (bang > 0) jarPath = jarPath.substring(0, bang);
                    try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarPath)) {
                        java.util.Enumeration<java.util.jar.JarEntry> es = jf.entries();
                        while (es.hasMoreElements()) {
                            java.util.jar.JarEntry je = es.nextElement();
                            String name = je.getName();
                            if (name.startsWith(path) && name.endsWith(".class")) {
                                String cn = name.substring(0, name.length() - 6).replace('/', '.');
                                out.add(cn);
                            }
                        }
                    }
                }
            }
            return out;
        }
        private static void collect(java.io.File dir, String pkg, Set<String> out) {
            if (!dir.isDirectory()) return;
            java.io.File[] fs = dir.listFiles();
            if (fs == null) return;
            for (java.io.File f : fs) {
                if (f.isDirectory()) collect(f, pkg + "." + f.getName(), out);
                else if (f.getName().endsWith(".class")) {
                    out.add(pkg + "." + f.getName().substring(0, f.getName().length() - 6));
                }
            }
        }
    }
}
