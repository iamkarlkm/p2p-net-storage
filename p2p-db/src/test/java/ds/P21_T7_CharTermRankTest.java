package ds;

import java.io.File;
import java.io.IOException;
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
import com.q3lives.ds.database.fulltext.FulltextStore.DocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.IngestStat;
import com.q3lives.ds.database.fulltext.FulltextStore.ScoredDocHit;
import com.q3lives.ds.database.fulltext.FulltextStore.SearchOptions;
import com.q3lives.ds.database.fulltext.TermDictStore;

/**
 * T7-4（REV8）：char 层「字符→词/词组」倒排（HC-8 豁免）+ 同义/近义一等倒排（HC-12②）
 * + 精确优先降权召回原语（HC-12③）。
 * HC-8 不破：char 层仍不建两两 DIST（pairsTotal 只累计 level ≥ 1）。
 */
public class P21_T7_CharTermRankTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;
    private TermDictStore td;

    @Before
    public void before() throws IOException {
        File root = tmp.newFolder("p21-t7-ct-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
        td = store.getTermDict();
    }

    @After
    public void after() {
        try { store.close(); } catch (Exception ignore) {}
    }

    // ---------------- TR1【AC-FR11-2】：精确优先降权——长词仍召回但精确整词排序严格更前 ----------------
    @Test
    public void tr1_preciseFirstTermOrder() throws IOException {
        // 语料分三文档，HanLP 词单元：人民 / 人民币 / 人民银行（长度递增，均含「人民」二字）
        store.ingestDoc(101L, "热爱人民");
        store.ingestDoc(202L, "人民币汇率");
        store.ingestDoc(303L, "人民银行政策");
        long uPeople = td.toUid("人民", false);
        long uRmb = td.toUid("人民币", false);
        long uPbc = td.toUid("人民银行", false);
        Assert.assertTrue("词单元 人民 存在", uPeople > 0L);
        Assert.assertTrue("词单元 人民币 存在", uRmb > 0L);
        Assert.assertTrue("词单元 人民银行 存在", uPbc > 0L);

        List<Long> terms = store.recallTermsByChars("人民");
        int iPeople = terms.indexOf(uPeople);
        int iRmb = terms.indexOf(uRmb);
        int iPbc = terms.indexOf(uPbc);
        Assert.assertTrue("精确整词 人民 被召回: " + iPeople, iPeople >= 0);
        Assert.assertTrue("长词 人民币 仍被召回（降权不丢召回）: " + iRmb, iRmb >= 0);
        Assert.assertTrue("长词 人民银行 仍被召回: " + iPbc, iPbc >= 0);
        Assert.assertTrue("人民 严格先于 人民币: iPeople=" + iPeople + ", iRmb=" + iRmb, iPeople < iRmb);
        Assert.assertTrue("人民币 先于 人民银行: iRmb=" + iRmb + ", iPbc=" + iPbc, iRmb < iPbc);

        // doc 级：含 人民 的文档与含 人民币 的文档均被召回
        Set<Long> ids = new HashSet<>();
        for (DocHit h : store.recallDocsByChars("人民", 10)) ids.add(h.docId);
        Assert.assertTrue("doc(热爱人民) 召回", ids.contains(101L));
        Assert.assertTrue("doc(人民币汇率) 召回", ids.contains(202L));
    }

    // ---------------- TR2【AC-FR11-1】：char 层挂词/词组倒排，但 char 两两 DIST 仍 = 0 ----------------
    @Test
    public void tr2_charPostingsWithoutCharDist() throws IOException {
        long docId = 777L;
        IngestStat st = store.ingestDoc(docId, "订阅推送服务稳定运行，提供高质量的文章订阅");
        long calc = 0;
        for (int lv = 1; lv < st.rowsPerLevel.length; lv++) {
            int n = st.rowsPerLevel[lv];
            if (n >= 2) calc += (long) n * (n - 1) / 2L;
        }
        Assert.assertEquals("HC-8 仍生效：pairsTotal 只累计 level ≥ 1（char 层 DIST 对 = 0）", calc, st.pairsTotal);

        // charLevelSearch=true：char 层候选经词/词组倒排命中（HC-8 豁免边界：仅倒排链，非距离对）
        SearchOptions opts = SearchOptions.defaults();
        opts.charLevelSearch = true;
        List<ScoredDocHit> hits = store.search("订阅", 10, opts);
        Assert.assertFalse("charLevelSearch 命中词单元", hits.isEmpty());
        boolean hitDoc = false;
        for (ScoredDocHit h : hits) if (h.docId == docId) hitDoc = true;
        Assert.assertTrue("命中目标文档", hitDoc);
    }

    // ---------------- TR3【AC-FR12-1/2】：同义近义一等倒排（配置后召回，未配不误召） ----------------
    @Test
    public void tr3_synonymRecall() throws IOException {
        long dPeople = 101L, dMass = 404L;
        store.ingestDoc(dPeople, "热爱人民");
        store.ingestDoc(dMass, "群众路线");

        // 未配 synonym：搜 人民 不因同义机制召回 群众 文档
        Set<Long> noSyn = new HashSet<>();
        for (DocHit h : store.recallDocsWithSynonyms("人民", 10)) noSyn.add(h.docId);
        Assert.assertTrue("精确文档召回", noSyn.contains(dPeople));
        Assert.assertFalse("未配 synonym 不误召 群众 文档", noSyn.contains(dMass));

        // 配置 同义/近义：人民 ↔ 群众 → 经 synonymSet 召回 群众 文档
        store.putSynonym("人民", "群众");
        long uPeople = td.toUid("人民", false);
        long uMass = td.toUid("群众", false);
        Assert.assertTrue("synonymSet 双向写入", contains(store.synonymsOf(uPeople), uMass) && contains(store.synonymsOf(uMass), uPeople));

        Set<Long> withSyn = new HashSet<>();
        for (DocHit h : store.recallDocsWithSynonyms("人民", 10)) withSyn.add(h.docId);
        Assert.assertTrue("配置 synonym 后经同义召回 群众 文档", withSyn.contains(dMass));
        Assert.assertTrue("精确文档仍在", withSyn.contains(dPeople));
    }

    private static boolean contains(long[] arr, long v) {
        for (long x : arr) if (x == v) return true;
        return false;
    }
}
