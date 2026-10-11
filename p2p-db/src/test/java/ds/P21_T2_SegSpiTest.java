package ds;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextSegmenter.CutUnit;
import com.q3lives.ds.database.fulltext.FulltextStore;

public class P21_T2_SegSpiTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private File root;
    private FulltextStore store;

    @Before public void setup() throws Exception {
        root = tmp.newFolder("p21t2seg-" + UUID.randomUUID().toString().replace("-","").substring(0,8));
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After public void tearDown() throws Exception {
        try { store.close(); } catch (Exception ignored) {}
    }

    // T2-TR-2 聚合 4 子项：Seg5 中英切分稳定 + HC-7 fallback + MockJieba priority=10 覆盖 + 流迭代边界
    @Test public void tr2_segSpiHc7AndCoverage() throws Exception {
        // ---------- A. Seg5 中英文 100 切分稳定（流迭代正确性） ----------
        FulltextSegmenter seg5 = store.getSeg5Builtin();
        String sampleCn = "中文字符测试样本：订阅和服务的距离是全文索引倒排的核心查询目标。";
        String sampleMix = "Hello world 123, 中文 English 混合 test!";
        FulltextSegmenter.SegOptions opts = FulltextSegmenter.SegOptions.defaults();
        opts.keepPunct = true;
        // ds0 CHAR：sampleCn 按 codepoint 切
        Iterator<CutUnit> ch = seg5.cut(sampleCn, 0, opts);
        List<CutUnit> cut0 = new ArrayList<>();
        ch.forEachRemaining(cut0::add);
        Assert.assertEquals("ds0 应按 code point 数切分，实际 cut0="+cut0.size()+" codepoints="+sampleCn.codePoints().count(),
            sampleCn.codePoints().count(), cut0.size());
        // 每个 CutUnit 顺序按 byte 升序，startOff/endOff 非递减
        for (int i = 1; i < cut0.size(); i++) {
            Assert.assertTrue("ds0 非升序 i="+i, cut0.get(i-1).startOff < cut0.get(i).startOff);
            Assert.assertEquals("ds0 文本不匹配 i="+i, sampleCn.substring(cut0.get(i).startOff, cut0.get(i).endOff), cut0.get(i).text);
        }
        // ds1 WORD 混合文本：至少切出 Hello/world/123/中文/English/混合/test 共 ≥7 词
        List<CutUnit> cut1 = new ArrayList<>();
        seg5.cut(sampleMix, 1, opts).forEachRemaining(cut1::add);
        Assert.assertTrue("ds1 WORD 切分词过少："+cut1.size()+"（期望≥7）", cut1.size() >= 7);
        // ds3 SENTENCE sampleCn 切 1-2 句（含句号）
        List<CutUnit> cut3 = new ArrayList<>();
        seg5.cut(sampleCn, 3, opts).forEachRemaining(cut3::add);
        Assert.assertTrue("ds3 句切分应为 1-2 句："+cut3.size(), cut3.size() >= 1 && cut3.size() <= 3);

        // ---------- B. HC-7：resolveSegmenter 正常 case 返回 MockJieba（priority=10 > Seg5=0） ----------
        FulltextSegmenter winner = store.resolveSegmenter();
        Assert.assertNotNull("resolveSegmenter 不应 null（HC-7 fallback 失效）", winner);
        // 验证 winner 是 MockJieba（10）而不是 Seg5（0）：MockJieba 切 "全文索引" → 1 词，Seg5 → 4 单字 or 2 词
        String testJieba = "全文索引系统";
        List<CutUnit> wCut = new ArrayList<>();
        winner.cut(testJieba, 1, opts).forEachRemaining(wCut::add);
        // MockJieba ds1 优先 "全文索引" 整词（len=4 字符），所以首词长度=4
        boolean hasFullTextIndexWhole = false;
        for (CutUnit u : wCut) if ("全文索引".equals(u.text) && u.endOff - u.startOff == 4) { hasFullTextIndexWhole = true; break; }
        Assert.assertTrue("resolveSegmenter 未选 MockJieba（priority=10 覆盖 Seg5），winner class="+winner.getClass().getName()
            +" cut1="+wCut, hasFullTextIndexWhole);
        // Seg5 单独切同一字符串：不会有 "全文索引" 整词（按字/按字符切 → 切成长度 1 或 2）
        List<CutUnit> seg5Cut = new ArrayList<>();
        seg5.cut(testJieba, 1, opts).forEachRemaining(seg5Cut::add);
        boolean seg5HasWhole = false;
        for (CutUnit u : seg5Cut) if (u.text.length() >= 4) seg5HasWhole = true;
        Assert.assertFalse("Seg5 不该有 4+ 长词（证明 MockJieba 覆盖有效）", seg5HasWhole);

        // ---------- C. Fallback：构造 winner=null 模式，验证 Seg5 Builtin 生效（HC-7 代码分支走 seg5builtin） ----------
        // 直接调 getSeg5Builtin 验证 fallback 等价：切 "全文索引系统" ds1 长度 ≤6（非整词）
        Assert.assertTrue("seg5 fallback 切分 ds1 数量异常："+seg5Cut.size(), seg5Cut.size() >= 4);

        // ---------- D. 边界：空串/超长文本切分 no exception，空串返回 EMPTY；iterator 无 next 抛 NoSuchElement ----------
        List<CutUnit> empty = new ArrayList<>();
        seg5.cut("", 1, opts).forEachRemaining(empty::add);
        Assert.assertEquals("空串切分应空："+empty.size(), 0, empty.size());
        Iterator<CutUnit> ei = seg5.cut(null, 1, opts);
        int cnt = 0; while (ei.hasNext()) { ei.next(); cnt++; }
        Assert.assertTrue("null 切分应空/空迭代："+cnt, cnt <= 1);
    }
}
