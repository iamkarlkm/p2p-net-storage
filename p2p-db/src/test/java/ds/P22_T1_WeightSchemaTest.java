package ds;

import java.io.File;
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
import com.q3lives.ds.database.fulltext.FulltextStore.WeightDocStatRow;
import com.q3lives.ds.database.fulltext.TermDictStore;

public class P22_T1_WeightSchemaTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;
    private TermDictStore td;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p22t1-" + UUID.randomUUID().toString().replace("-","").substring(0, 8));
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignore) {}
    }

    // TR-1.1：Word level 10 uid Seq -> tf 中=2 国=2 人=1 其余各 1；docLen=10；nTerms=7（7 distinct）
    @Test public void tr11_word10TfAndDocStat() throws Exception {
        final long DOC = 1L, PARENT = 0L; final int LV = TermDictStore.DS1_WORD;
        // 用 TermDictStore.toUid(true) 精确创建独立 uid（避免 Seg SPI）
        long u中 = td.toUid("中", true);
        long u国 = td.toUid("国", true);
        long u人 = td.toUid("人", true);
        long u北 = td.toUid("北", true);
        long u京 = td.toUid("京", true);
        long u天 = td.toUid("天", true);
        long u安 = td.toUid("安", true);
        long u门 = td.toUid("门", true);
        Assert.assertTrue("中/国/人/北/京/天/安/门 8 uid >0（USER-HC-0 纯文本）", u中>0&&u国>0&&u人>0&&u北>0&&u京>0&&u天>0&&u安>0&&u门>0);
        List<Long> seq = Arrays.asList(u中, u国, u人, u中, u国, u北, u京, u天, u安, u门);
        // Weight 写入走 buildDistForSeq 入口（Step5 updateWeightForLevel），不依赖 Seg SPI
        store.buildDistForSeq(DOC, PARENT, LV, seq);
        // tf 断言
        Assert.assertEquals("tf 中=2", 2, store.getTfForTest(u中, DOC, PARENT, LV));
        Assert.assertEquals("tf 国=2", 2, store.getTfForTest(u国, DOC, PARENT, LV));
        Assert.assertEquals("tf 人=1", 1, store.getTfForTest(u人, DOC, PARENT, LV));
        Assert.assertEquals("tf 北=1", 1, store.getTfForTest(u北, DOC, PARENT, LV));
        Assert.assertEquals("tf 京=1", 1, store.getTfForTest(u京, DOC, PARENT, LV));
        Assert.assertEquals("tf 天=1", 1, store.getTfForTest(u天, DOC, PARENT, LV));
        Assert.assertEquals("tf 安=1", 1, store.getTfForTest(u安, DOC, PARENT, LV));
        Assert.assertEquals("tf 门=1", 1, store.getTfForTest(u门, DOC, PARENT, LV));
        // docStat 断言
        WeightDocStatRow st = store.getDocStatForTest(DOC);
        Assert.assertNotNull("docStat(1) 不为 null", st);
        Assert.assertEquals("docStat WORD docLen=10", 10, st.docLen[LV]);
        Assert.assertEquals("docStat WORD nTerms=8（中/国/人/北/京/天/安/门=8 个 distinct 汉字）", 8, st.nTerms[LV]);
        // 其余 level docLen 应为 0（仅 WORD 写）
        for (int lv = 0; lv < 6; lv++) if (lv != LV) Assert.assertEquals("其它 level docLen=0", 0, st.docLen[lv]);
    }

    // TR-1.2：不存在 uid tf=0；不存在 docId docStat=null
    @Test public void tr12_missTfAndDocStat() throws Exception {
        Assert.assertEquals("不存在 uid tf=0", 0, store.getTfForTest(9999L, 1L, 0L, 1));
        Assert.assertNull("不存在 docId docStat=null", store.getDocStatForTest(9999L));
    }

    // TR-1.3：CHAR 和 WORD 双 level 独立写读，互不覆盖（docLen 独立）
    @Test public void tr13_charWordDoubleLevelIndependent() throws Exception {
        final long DOC = 2L, PARENT = 0L;
        final int LV_CHAR = TermDictStore.DS0_CHAR, LV_WORD = TermDictStore.DS1_WORD;
        // CHAR 层：20 个单字（中/国/人 循环，精确 20 项，distinct=3）
        List<Long> charSeq = new ArrayList<>(20);
        long u中 = td.toUid("中", true), u国 = td.toUid("国", true), u人 = td.toUid("人", true);
        for (int i = 0; i < 20; i++) { long u = (i%3==0)?u中:(i%3==1)?u国:u人; charSeq.add(u); }
        // WORD 层：10 词（"中国/人民/北京/..." 简单 distinct 词）
        List<Long> wordSeq = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) { long u = td.toUid("w" + i, true); wordSeq.add(u); }
        store.buildDistForSeq(DOC, PARENT, LV_CHAR, charSeq);
        store.buildDistForSeq(DOC, PARENT, LV_WORD, wordSeq);
        WeightDocStatRow st = store.getDocStatForTest(DOC);
        Assert.assertNotNull("docStat 非 null", st);
        Assert.assertEquals("CHAR docLen=20", 20, st.docLen[LV_CHAR]);
        Assert.assertEquals("CHAR nTerms=3（中/国/人）", 3, st.nTerms[LV_CHAR]);
        Assert.assertEquals("WORD docLen=10", 10, st.docLen[LV_WORD]);
        Assert.assertEquals("WORD nTerms=10（10 个不同 w_i）", 10, st.nTerms[LV_WORD]);
        // tf 断言：CHAR 层 u中 出现次数 = ceil(20/3)=7
        int cntZhong = 0; for (int i = 0; i < 20; i++) if (i%3==0) cntZhong++;
        Assert.assertEquals("CHAR 中 tf=" + cntZhong, cntZhong, store.getTfForTest(u中, DOC, PARENT, LV_CHAR));
        // WORD 层 w0=1
        Assert.assertEquals("WORD w0 tf=1", 1, store.getTfForTest(td.toUid("w0", false), DOC, PARENT, LV_WORD));
        // 反向确认：WORD 层没有 CHAR 的 tf 泄漏
        Assert.assertEquals("WORD 层 u中 tf=0（无交叉污染）", 0, store.getTfForTest(u中, DOC, PARENT, LV_WORD));
    }

    // TR-1.4：空 WeightStore（new 完没写）探针全 miss
    @Test public void tr14_emptyWeightStoreNoCrash() throws Exception {
        WeightDocStatRow s = store.getDocStatForTest(1L);
        Assert.assertNull("空 store docStat(1) = null", s);
        Assert.assertEquals("空 store 随机 uid tf=0", 0, store.getTfForTest(12345L, 67L, 89L, 2));
        // weight_store 目录存在与否不抛异常（Directory 已创建）
        File weightDir = new File(root, "weight_store");
        Assert.assertTrue("weight_store 目录存在（构造初始化）", weightDir.exists() && weightDir.isDirectory());
    }
}
