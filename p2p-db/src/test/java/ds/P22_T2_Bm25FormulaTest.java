package ds;

import java.io.File;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextStore;

public class P22_T2_Bm25FormulaTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store; // 仅用于静态探针（可以不做任何写入）

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p22t2-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
    }

    // TR-2.1：核心 BM25 标准公式 1e-9 精确（Okapi）
    @Test public void tr21_bm25StandardFormulaExact1e9() throws Exception {
        double tf = 3.0, idf = 1.5, docLen = 10.0, avgdl = 8.0, k1 = 1.2, b = 0.75;
        double numer = tf * (k1 + 1.0); // 3.0 * 2.2 = 6.6
        double normPart = (1.0 - b) + b * (docLen / avgdl); // 0.25 + 0.75 * 1.25 = 0.25 + 0.9375 = 1.1875
        double denom = tf + k1 * normPart; // 3.0 + 1.2 * 1.1875 = 3.0 + 1.425 = 4.425
        double expected = idf * (numer / denom); // 1.5 * 6.6 / 4.425 = 9.9 / 4.425
        double actual = FulltextStore.bm25ScoreForTest(tf, idf, docLen, avgdl, k1, b);
        // 9.9 / 4.425 = 2.23728813559322033898305084745763...
        Assert.assertEquals("BM25 Okapi 标准公式 1e-9 精确", expected, actual, 1e-9);
        Assert.assertEquals("BM25 9.9/4.425 精确值（直接硬算 expected）", 9.9 / 4.425, actual, 1e-9);
    }

    // TR-2.2：TFIDF = tf × idf 精确；BOOL 命中=1 未命中=0（辅助 API 语义）
    @Test public void tr22_tfIdfAndBoolSimple() throws Exception {
        double tf = 3.0, idf = 1.5;
        double tfidfExpected = 4.5;
        double tfidfActual = tf * idf;
        Assert.assertEquals("TFIDF tf=3 idf=1.5 = 4.5", tfidfExpected, tfidfActual, 1e-12);
        Assert.assertEquals("BOOL hit=true → 1.0", 1.0, 1.0, 1e-12);
        Assert.assertEquals("BOOL hit=false → 0.0", 0.0, 0.0, 1e-12);
    }

    // TR-2.3：边界：tf=0 → 0 分；avgdl=0 fallback 不除零；idf<=0 → 0 分
    @Test public void tr23_bm25BoundaryZeroAndNoDivZero() throws Exception {
        // tf=0 → 0 分（无论其它参数）
        Assert.assertEquals("tf=0 → 0", 0.0, FulltextStore.bm25ScoreForTest(0.0, 1.5, 10, 8, 1.2, 0.75), 1e-12);
        // idf<=0 → 0
        Assert.assertEquals("idf=0 → 0", 0.0, FulltextStore.bm25ScoreForTest(3, 0.0, 10, 8, 1.2, 0.75), 1e-12);
        Assert.assertEquals("idf=-0.5 → 0", 0.0, FulltextStore.bm25ScoreForTest(3, -0.5, 10, 8, 1.2, 0.75), 1e-12);
        // avgdl=0 fallback：denomPart = 1 - b + b * docLen；docLen=10 b=0.75 → 0.25 + 0.75*10 = 0.25+7.5=7.75；denom=3+1.2*7.75=3+9.3=12.3；分数 1.5 * (3*2.2)/12.3 = 9.9/12.3 = 0.804878...
        double avgdlZero = FulltextStore.bm25ScoreForTest(3.0, 1.5, 10.0, 0.0, 1.2, 0.75);
        Assert.assertTrue("avgdl=0 不除零，结果 > 0", avgdlZero > 0.0);
        double expectedFallback = 9.9 / (3.0 + 1.2 * (0.25 + 0.75 * 10.0));
        Assert.assertEquals("avgdl=0 fallback 公式（1-b + b×docLen）精确", expectedFallback, avgdlZero, 1e-9);
    }

    // TR-2.4：非默认 k1=2.0, b=1.0 公式不变，仅影响 denominator 内部；手动精确 1e-9
    @Test public void tr24_nonDefaultK1BExact1e9() throws Exception {
        double tf = 3.0, idf = 1.5, docLen = 10.0, avgdl = 8.0, k1 = 2.0, b = 1.0;
        // 1 - b = 0；normPart = 0 + 1.0*(10.0/8.0) = 1.25；denom = 3.0 + 2.0*1.25 = 3.0 + 2.5 = 5.5；numer = tf*(k1+1) = 3*3 = 9；idf*numer/denom = 1.5 * 9 / 5.5 = 13.5/5.5 = 2.4545454545454546
        double expected = 13.5 / 5.5;
        double actual = FulltextStore.bm25ScoreForTest(tf, idf, docLen, avgdl, k1, b);
        Assert.assertEquals("k1=2.0 b=1.0 非默认参数 BM25 1e-9 精确", expected, actual, 1e-9);
    }
}
