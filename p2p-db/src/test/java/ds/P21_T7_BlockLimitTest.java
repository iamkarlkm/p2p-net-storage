package ds;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.q3lives.ds.database.fulltext.FulltextSegmenter;
import com.q3lives.ds.database.fulltext.FulltextSegmenter.CutUnit;
import com.q3lives.ds.database.fulltext.FulltextSegmenter.SegOptions;
import com.q3lives.ds.database.fulltext.FulltextStore;
import com.q3lives.ds.database.fulltext.TermDictStore.TuRow;

/**
 * T7-2（REV8 / HC-10）：block/segment 长度上限。
 * 断言：① 超长段落切成 ≥2 block，每个 ≤ maxBlockChars（code point）；② 不切碎句子（除末块外以句末符结尾）；
 * ③ 同一段落切出的多 block 共享同一 parentId；④ 短文本（&lt; max）行为不变（仍 1 block，回归保护）。
 */
public class P21_T7_BlockLimitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FulltextStore store;

    @Before
    public void before() throws IOException {
        File root = tmp.newFolder("p21-t7-blk-" + UUID.randomUUID().toString().replace("-", ""));
        System.setProperty("p2p.db.home", root.getAbsolutePath());
        store = new FulltextStore(root.getAbsolutePath());
    }

    @After
    public void after() {
        try { store.close(); } catch (Exception ignore) {}
    }

    private static String makeParagraph(int sentenceCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentenceCount; i++) sb.append("句子内容测试。"); // 7 code point/句
        return sb.toString();
    }

    private static List<CutUnit> cutBlocks(FulltextSegmenter seg, String text, SegOptions opts) {
        List<CutUnit> out = new ArrayList<>();
        Iterator<CutUnit> it = seg.cut(text, FulltextStore.DS4_BLOCK, opts);
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    // ---------------- TR1：超长段落限长 + 不切碎句子（HC-10） ----------------
    @Test
    public void tr1_overlongParagraphLimitedAndSentenceSafe() throws IOException {
        int max = 1024;
        String text = makeParagraph(200); // 1400 code point > 1024
        Assert.assertTrue("前置：语料 code point 应 > max", text.codePointCount(0, text.length()) > max);

        SegOptions opts = SegOptions.defaults();
        opts.maxBlockChars = max;
        List<CutUnit> blocks = cutBlocks(store.getSeg5Builtin(), text, opts);

        Assert.assertTrue("超长段落切出 ≥2 block: " + blocks.size(), blocks.size() >= 2);
        for (int i = 0; i < blocks.size(); i++) {
            CutUnit b = blocks.get(i);
            int cp = b.text.codePointCount(0, b.text.length());
            Assert.assertTrue("block[" + i + "] 长度 ≤ maxBlockChars: " + cp, cp <= max);
            if (i < blocks.size() - 1) {
                String t = b.text;
                char last = t.charAt(t.length() - 1);
                Assert.assertTrue("block[" + i + "] 以句末符结尾（不切碎句子）: [" + last + "]", last == '。');
            }
        }
        // 覆盖无遗漏：块字节级拼接 == 原文（去掉块间被跳过的空行场景此处无空行）
        StringBuilder rebuilt = new StringBuilder();
        for (CutUnit b : blocks) rebuilt.append(b.text);
        Assert.assertEquals("块拼接覆盖全文", text, rebuilt.toString());
    }

    // ---------------- TR2：可配 maxBlockChars（小阈值更多块） + 短文本回归保护 + ingest 层同 parentId ----------------
    @Test
    public void tr2_configurableMaxAndIngestSharesParentId() throws IOException {
        // 短文本 < max → 仍 1 block（默认 1024 行为不变）
        String shortText = "订阅推送服务稳定运行。";
        List<CutUnit> one = cutBlocks(store.getSeg5Builtin(), shortText, SegOptions.defaults());
        Assert.assertEquals("短文本默认仍 1 block", 1, one.size());

        // 小阈值：同一文本 maxBlockChars=10 → 更多块，每块 ≤10 cp
        String text = makeParagraph(6); // 42 code point
        SegOptions tiny = SegOptions.defaults();
        tiny.maxBlockChars = 10;
        List<CutUnit> many = cutBlocks(store.getSeg5Builtin(), text, tiny);
        Assert.assertTrue("maxBlockChars=10 切出 ≥2 block: " + many.size(), many.size() >= 2);
        for (CutUnit b : many) {
            Assert.assertTrue("block ≤10 cp", b.text.codePointCount(0, b.text.length()) <= 10);
        }

        // ingest 层：超长段落 → level 4 多行，且所有 level-4 行的 parentId 相同（= 所属章节 DS5 uid）
        String longText = makeParagraph(200);
        long docId = 4242L;
        store.ingestDoc(docId, longText);
        List<TuRow> chapters = store.selectByDocLevel(docId, FulltextStore.DS5_DOC_STRUCT);
        Assert.assertEquals("无标题 → 单合成根锚 DS5", 1, chapters.size());
        long chapterUid = chapters.get(0).unit_id();
        List<TuRow> blkRows = store.selectByParentLevel(docId, chapterUid, FulltextStore.DS4_BLOCK);
        Assert.assertTrue("ingest 后 level4 行 ≥2: " + blkRows.size(), blkRows.size() >= 2);
        Set<Long> parents = new HashSet<>();
        for (TuRow r : blkRows) parents.add(r.parentId);
        Assert.assertEquals("多 block 共享同一 parentId", 1, parents.size());
        Assert.assertTrue("parentId = 章节 DS5 uid", parents.contains(chapterUid));
    }
}
